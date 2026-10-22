package space.einundzwanzig.meetup.feature.onboarding.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarDurations
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.data.identity.AmberConnectResult
import space.einundzwanzig.meetup.data.identity.RecommendedExistingPath
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.identity.easyauth.LocalEasyAuth
import space.einundzwanzig.meetup.data.identity.keys.LocalKeyVault
import space.einundzwanzig.meetup.data.identity.keys.NostrKeyService
import space.einundzwanzig.meetup.data.identity.keys.SecureKeyStore
import space.einundzwanzig.meetup.data.identity.passkey.PasskeyPrfException
import space.einundzwanzig.meetup.data.identity.passkey.PasskeyPrfService
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.identity.review.AppReviewSignIn
import space.einundzwanzig.meetup.data.meetups.MeetupService
import space.einundzwanzig.meetup.feature.backup.BackupLauncher
import space.einundzwanzig.meetup.feature.onboarding.bunker.BunkerAction
import space.einundzwanzig.meetup.feature.onboarding.bunker.BunkerConnectController
import space.einundzwanzig.meetup.feature.onboarding.bunker.BunkerSheetState
import space.einundzwanzig.meetup.feature.onboarding.common.SnackbarMessage
import space.einundzwanzig.meetup.feature.onboarding.common.UiText

/**
 * Identity setup wizard (Dart `IdentitySetupScreen`, spec 01 §2.3): create a local key with
 * name + password (+ optional passkey), resume a surviving identity, connect Amber / a remote
 * signer, import nsec / ncryptsec, the name step, the meetup step and the "secure your identity"
 * sheet. Finishing resets the back stack to the app shell (spec 12 §2.4).
 */
@HiltViewModel
class IdentitySetupViewModel @Inject constructor(
    private val signing: SigningService,
    private val keyStore: SecureKeyStore,
    private val vault: LocalKeyVault,
    private val keys: NostrKeyService,
    private val easyAuth: LocalEasyAuth,
    /** The route runs the Credential Manager ceremonies with it (they need the Activity). */
    val passkeys: PasskeyPrfService,
    private val profiles: UserProfileRepository,
    private val reviewSignIn: AppReviewSignIn,
    private val meetupService: MeetupService,
    private val backup: BackupLauncher,
    private val keyFileWriter: KeyFileWriter,
    private val time: TimeSource,
) : ViewModel() {

    private val _state = MutableStateFlow(IdentitySetupUiState())
    val state: StateFlow<IdentitySetupUiState> = _state.asStateFlow()

    private val _effects = Channel<SetupEffect>(Channel.BUFFERED)
    val effects: Flow<SetupEffect> = _effects.receiveAsFlow()

    private val _snackbars = Channel<SnackbarMessage>(Channel.BUFFERED)
    val snackbars: Flow<SnackbarMessage> = _snackbars.receiveAsFlow()

    /** "secure your identity" progress; never reset (Dart `_secureStep`). */
    private var secureStep = 1

    /** The password wrap shown in the secure sheet (an ncryptsec, never the raw key). */
    private var secureWrap: String? = null

    /** `true` when the bunker sheet was opened from the primary card (busy while open). */
    private var bunkerFromPrimary = false

    val bunker = BunkerConnectController(signing, viewModelScope) { result -> onBunkerFinished(result != null) }
    val bunkerState: StateFlow<BunkerSheetState> get() = bunker.state

    init {
        bootstrap()
    }

    // region bootstrap

    private fun bootstrap() {
        viewModelScope.launch {
            val path = runOr(RecommendedExistingPath.BUNKER) { signing.recommendedExistingPath() }
            val passkeySupported = runOr(false) { passkeys.isSupported() }
            // An unreadable key store counts as "a key is here": never invite overwriting it.
            val hasLocalKey = runOr(true) { keyStore.hasKey() }
            val hasPasswordWrap = runOr(false) { !vault.getPasswordWrap().isNullOrEmpty() }
            val hasPasskeyWrap = runOr(false) { vault.hasPasskeyWrap() }
            _state.update {
                it.copy(
                    path = path,
                    passkeySupported = passkeySupported,
                    hasLocalKey = hasLocalKey,
                    hasPasswordWrap = hasPasswordWrap,
                    hasPasskeyWrap = hasPasskeyWrap,
                    offerReviewSignIn = reviewSignIn.isOffered,
                    bootstrapping = false,
                )
            }
        }
    }

    private suspend fun <T> runOr(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLogger.warn(TAG, "Start-Erkennung fehlgeschlagen", e)
        fallback
    }

    // endregion

    fun onAction(action: SetupAction) {
        // Flutter `AbsorbPointer` (spec 01 §2.3.1): while busy or bootstrapping no step change, also
        // not via TalkBack's semantic click or a hardware keyboard, which bypass the pointer filter.
        if (_state.value.inputBlocked && action in STEP_ACTIONS) return
        when (action) {
            SetupAction.Close -> if (!_state.value.busy) send(SetupEffect.Back)
            SetupAction.Back -> back()
            SetupAction.PickResume -> _state.update { it.copy(error = null, step = SetupStep.RESUME) }
            SetupAction.PickNew -> _state.update {
                if (it.canResume) it.copy(confirmOverwrite = true) else it.copy(step = SetupStep.NEW)
            }
            SetupAction.ConfirmOverwrite -> _state.update { it.copy(confirmOverwrite = false, step = SetupStep.NEW) }
            SetupAction.DismissOverwrite -> _state.update { it.copy(confirmOverwrite = false) }
            SetupAction.PickExisting -> _state.update { it.copy(step = SetupStep.EXISTING) }
            is SetupAction.NameChanged -> _state.update { it.copy(name = action.value) }
            is SetupAction.PassChanged -> _state.update { it.copy(pass = action.value) }
            is SetupAction.Pass2Changed -> _state.update { it.copy(pass2 = action.value) }
            is SetupAction.ImportChanged -> _state.update { it.copy(importKey = action.value) }
            is SetupAction.ImportPassChanged -> _state.update { it.copy(importPass = action.value) }
            is SetupAction.ResumePassChanged -> _state.update { it.copy(resumePass = action.value) }
            is SetupAction.ToggleReveal -> _state.update {
                it.copy(revealed = if (action.field in it.revealed) it.revealed - action.field else it.revealed + action.field)
            }
            SetupAction.Register -> register()
            SetupAction.Resume -> resume(usePasskey = false)
            SetupAction.ResumeWithPasskey -> resume(usePasskey = true)
            SetupAction.UsePasswordInstead -> _state.update { it.copy(error = null, resumeUsePassword = true) }
            SetupAction.AddPasskey -> addPasskey()
            SetupAction.PasskeyLater -> if (!_state.value.busy) launch { offerBackupThenHome() }
            is SetupAction.PasskeyLaunched -> _state.update {
                if (it.pendingPasskey == action.request) it.copy(passkeyLaunched = true) else it
            }
            is SetupAction.PasskeyDone -> onPasskeyDone(action)
            SetupAction.ConnectPrimary -> connectPrimary()
            SetupAction.OtherWay -> _state.update { it.copy(step = SetupStep.EXISTING_MORE) }
            SetupAction.ImportKey -> importKey()
            SetupAction.ConnectBunker -> openBunker(fromPrimary = false)
            SetupAction.ConnectAmber -> {
                _state.update { it.copy(path = RecommendedExistingPath.AMBER) }
                connectPrimary()
            }
            SetupAction.RestoreBackup -> restoreBackup()
            SetupAction.SaveName -> saveName()
            SetupAction.OpenMeetupPicker -> _state.update { it.copy(showMeetupPicker = true) }
            SetupAction.DismissMeetupPicker -> _state.update { it.copy(showMeetupPicker = false) }
            is SetupAction.MeetupsPicked -> _state.update { it.copy(showMeetupPicker = false, selectedMeetups = action.ids) }
            SetupAction.SaveMeetup -> saveMeetup()
            SetupAction.MeetupLater -> send(SetupEffect.GoHome)
            SetupAction.SecureCreateBackup -> secureCreateBackup()
            SetupAction.SecureSkip -> if (_state.value.secureSheet?.busy == false) setSecureStep(2)
            SetupAction.SecureSaveFile -> secureSaveFile()
            SetupAction.SecureCopy -> secureCopy()
            SetupAction.SecureLater -> if (_state.value.secureSheet?.busy == false) closeSecureSheet()
            is SetupAction.KeyFilePicked -> onKeyFilePicked(action)
            is SetupAction.ReviewUserChanged -> _state.update { it.copy(reviewUser = action.value) }
            is SetupAction.ReviewPassChanged -> _state.update { it.copy(reviewPass = action.value) }
            SetupAction.ReviewSignIn -> reviewSignIn()
            is SetupAction.Bunker -> onBunkerAction(action.action)
        }
    }

    // region navigation

    /** Leading back arrow and system back (spec 01 §2.3.2). */
    private fun back() {
        val s = _state.value
        if (s.busy || s.secureSheet != null) return
        when (s.step) {
            SetupStep.CHOOSE -> send(SetupEffect.Back)
            SetupStep.MEETUP -> send(SetupEffect.GoHome)
            // Native deviation (spec 01 §2.3.6 SHOULD): back on the passkey step = "Später", so
            // "Loslegen" cannot run a second time and overwrite the key just created.
            SetupStep.PASSKEY -> launch { offerBackupThenHome() }
            else -> _state.update {
                it.copy(
                    error = null,
                    resumeUsePassword = false,
                    resumePass = "",
                    step = when (it.step) {
                        SetupStep.EXISTING_MORE, SetupStep.NAME_ONLY -> SetupStep.EXISTING
                        else -> SetupStep.CHOOSE
                    },
                )
            }
        }
    }

    /** Dart `_finishIfOnboarded`: name step without a custom nickname, else continue. */
    private suspend fun finishIfOnboarded() {
        val user = profiles.load()
        if (!user.hasCustomNickname) {
            _state.update { it.copy(busy = false, step = SetupStep.NAME_ONLY) }
            return
        }
        continueAfterIdentity(user)
    }

    /** Dart `_continueAfterIdentity`: home if a meetup is set, else the meetup step. */
    private suspend fun continueAfterIdentity(loaded: UserProfile? = null) {
        val user = loaded ?: profiles.load()
        val hasMeetup = user.homeMeetupId.dartTrim().isNotEmpty() || user.favoriteMeetupIds.isNotEmpty()
        if (hasMeetup) {
            _state.update { it.copy(busy = false) }
            _effects.send(SetupEffect.GoHome)
        } else {
            enterMeetupStep()
        }
    }

    private suspend fun enterMeetupStep() {
        _state.update {
            it.copy(busy = false, error = null, step = SetupStep.MEETUP, meetupsLoading = true, selectedMeetups = emptyList())
        }
        val list = try {
            meetupService.fetchMeetups()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.warn(TAG, "Meetups nicht geladen", e)
            emptyList()
        }
        _state.update {
            it.copy(
                meetups = list,
                meetupsLoading = false,
                error = if (list.isEmpty()) UiText.res(L10nR.string.idSetupMeetupLoadError) else it.error,
            )
        }
    }

    // endregion

    // region new identity

    /** Dart `_register`. */
    private fun register() {
        val s = _state.value
        if (s.busy) return
        val name = s.name.dartTrim()
        val error = when {
            name.isEmpty() || name == UserProfile.DEFAULT_NICKNAME -> UiText.res(L10nR.string.idSetupNameRequired)
            s.pass.length < LocalEasyAuth.MIN_PASSWORD_LENGTH -> UiText.res(L10nR.string.idSetupPasswordShort)
            s.pass != s.pass2 -> UiText.res(L10nR.string.keyExportMismatch)
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(error = error) }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        launch {
            try {
                easyAuth.register(name, s.pass)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = UiText.Raw(messageOf(e))) }
                return@launch
            }
            // The fresh identity replaced whatever survived: no resume card any more.
            _state.update { it.copy(hasLocalKey = true, hasPasswordWrap = true, hasPasskeyWrap = false) }
            if (_state.value.passkeySupported) {
                _state.update { it.copy(busy = false, step = SetupStep.PASSKEY) }
            } else {
                offerBackupThenHome()
            }
        }
    }

    /** Dart `_addPasskey`, split so the Credential Manager call runs in the UI scope. */
    private fun addPasskey() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        launch {
            try {
                val registration = easyAuth.passkeyRegistration(_state.value.name.dartTrim())
                _state.update { it.copy(pendingPasskey = PasskeyRequest.Register(registration)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = passkeyError(e)) }
            }
        }
    }

    private fun onPasskeyDone(action: SetupAction.PasskeyDone) {
        if (_state.value.pendingPasskey != action.request) return
        _state.update { it.copy(pendingPasskey = null, passkeyLaunched = false) }
        launch {
            try {
                val result = action.result.getOrThrow()
                when (val request = action.request) {
                    is PasskeyRequest.Register -> {
                        easyAuth.savePasskeyWrap(request.registration, result)
                        offerBackupThenHome()
                    }
                    is PasskeyRequest.Unlock -> {
                        easyAuth.unlockWithPrfSecret(result)
                        _state.update { it.copy(resumePass = "") }
                        finishIfOnboarded()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = passkeyError(e)) }
            }
        }
    }

    /** Dart `_offerBackupThenHome`: the secure sheet if a password wrap exists, then continue. */
    private suspend fun offerBackupThenHome() {
        _state.update { it.copy(busy = false) }
        val wrap = try {
            vault.getPasswordWrap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.warn(TAG, "Passwort-Hülle nicht lesbar", e)
            null
        }
        if (!wrap.isNullOrEmpty()) {
            secureWrap = wrap
            _state.update { it.copy(secureSheet = SecureSheetState(step = secureStep)) }
        } else {
            continueAfterIdentity()
        }
    }

    // endregion

    // region resume

    /** Dart `_resume`. */
    private fun resume(usePasskey: Boolean) {
        val s = _state.value
        if (s.busy) return
        _state.update { it.copy(busy = true, error = null) }
        launch {
            try {
                when {
                    s.hasLocalKey -> signing.useLocalMode()
                    usePasskey -> {
                        val ids = easyAuth.passkeyCredentialIds()
                        _state.update { it.copy(pendingPasskey = PasskeyRequest.Unlock(ids)) }
                        return@launch
                    }
                    s.resumePass.isEmpty() -> {
                        _state.update { it.copy(busy = false, error = UiText.res(L10nR.string.idSetupResumeNeedPassword)) }
                        return@launch
                    }
                    else -> easyAuth.unlockWithPassword(s.resumePass)
                }
                _state.update { it.copy(resumePass = "") }
                finishIfOnboarded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.warn(TAG, "Weitermachen fehlgeschlagen", e)
                val error = if (usePasskey) passkeyError(e) else UiText.res(L10nR.string.idSetupResumeWrongPassword)
                _state.update { it.copy(busy = false, error = error) }
            }
        }
    }

    // endregion

    // region existing identity

    /** Dart `_connectPrimary`. */
    private fun connectPrimary() {
        val s = _state.value
        if (s.busy) return
        if (s.path != RecommendedExistingPath.AMBER) {
            openBunker(fromPrimary = true)
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        launch {
            val result = try {
                signing.connectAmber()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.warn(TAG, "Amber-Verbindung fehlgeschlagen", e)
                null
            }
            if (result is AmberConnectResult.Success) {
                finishGuarded()
            } else {
                _state.update { it.copy(busy = false, error = UiText.res(L10nR.string.idSetupConnectFailed)) }
            }
        }
    }

    private fun openBunker(fromPrimary: Boolean) {
        if (_state.value.busy) return
        bunkerFromPrimary = fromPrimary
        _state.update { it.copy(busy = fromPrimary, error = null, showBunkerSheet = true) }
        bunker.open()
    }

    private fun onBunkerAction(action: BunkerAction) {
        when (action) {
            BunkerAction.StartSignerApp -> bunker.startSignerApp()
            BunkerAction.ShowPaste -> bunker.showPaste()
            BunkerAction.BackToChoose -> bunker.backToChoose()
            is BunkerAction.PasteChanged -> bunker.onPasteChanged(action.value)
            BunkerAction.ConnectPasted -> bunker.connectPasted()
            BunkerAction.Cancel -> bunker.cancel()
            is BunkerAction.Scanned -> bunker.onScanned(action.raw)
            BunkerAction.UriCopied -> snack(UiText.res(L10nR.string.bunkerCopied))
            BunkerAction.NoSignerApp -> snack(UiText.res(L10nR.string.bunkerNoSignerApp))
        }
    }

    private fun onBunkerFinished(connected: Boolean) {
        _state.update { it.copy(showBunkerSheet = false) }
        if (connected) {
            _state.update { it.copy(busy = true) }
            launch { finishGuarded() }
        } else if (bunkerFromPrimary) {
            _state.update { it.copy(busy = false) }
        }
        bunkerFromPrimary = false
    }

    /** Dart `_importKey`. */
    private fun importKey() {
        val s = _state.value
        if (s.busy) return
        val raw = s.importKey.dartTrim()
        if (raw.isEmpty()) {
            _state.update { it.copy(error = UiText.res(L10nR.string.idSetupImportEmpty)) }
            return
        }
        if (raw.startsWith("ncryptsec1") && s.importPass.isEmpty()) {
            _state.update { it.copy(error = UiText.res(L10nR.string.idSetupImportNeedPassword)) }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        launch {
            try {
                if (raw.startsWith("ncryptsec1")) keys.importNcryptsec(raw, s.importPass) else keys.importNsec(raw)
                // Old EasyAuth wraps belong to the previous identity (spec 01 §8.2).
                vault.clearAll()
                signing.useLocalMode()
                _state.update { it.copy(importKey = "", importPass = "") }
                finishIfOnboarded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = UiText.Raw(messageOf(e))) }
            }
        }
    }

    private fun restoreBackup() {
        if (_state.value.busy || backup.busy.value) return
        launch {
            if (backup.restore()) _effects.send(SetupEffect.GoHome)
        }
    }

    // endregion

    // region name, meetup

    /** Dart `_saveNameOnly`. */
    private fun saveName() {
        val s = _state.value
        if (s.busy) return
        val name = s.name.dartTrim()
        if (name.isEmpty() || name == UserProfile.DEFAULT_NICKNAME) {
            _state.update { it.copy(error = UiText.res(L10nR.string.idSetupNameRequired)) }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        launch {
            try {
                val user = profiles.update { it.copy(nickname = name) }
                continueAfterIdentity(user)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = UiText.Raw(messageOf(e))) }
            }
        }
    }

    /** Dart `_saveMeetupAndHome`: favorites = selection, home = first. */
    private fun saveMeetup() {
        val s = _state.value
        if (s.busy || s.selectedMeetups.isEmpty()) return
        val selected = s.selectedMeetups
        _state.update { it.copy(busy = true) }
        launch {
            try {
                profiles.update { it.copy(favoriteMeetupIds = selected, homeMeetupId = selected.first()) }
                _effects.send(SetupEffect.GoHome)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = UiText.Raw(messageOf(e))) }
            }
        }
    }

    // endregion

    // region secure sheet

    private fun setSecureStep(step: Int) {
        secureStep = step
        _state.update { s -> s.secureSheet?.let { s.copy(secureSheet = it.copy(step = step, busy = false)) } ?: s }
    }

    private fun secureCreateBackup() {
        val sheet = _state.value.secureSheet ?: return
        if (sheet.busy) return
        _state.update { it.copy(secureSheet = sheet.copy(busy = true)) }
        launch {
            try {
                backup.export() // result ignored (Dart)
            } finally {
                setSecureStep(2)
            }
        }
    }

    /** Opens the SAF picker; the sheet stays busy until the file is written or the picker is cancelled. */
    private fun secureSaveFile() {
        val sheet = _state.value.secureSheet ?: return
        if (sheet.busy) return
        _state.update { it.copy(secureSheet = sheet.copy(busy = true)) }
        send(SetupEffect.CreateKeyFile(KeyFile.fileName(time.nowMillis())))
    }

    private fun secureCopy() {
        val sheet = _state.value.secureSheet ?: return
        if (sheet.busy) return
        val wrap = secureWrap ?: return
        send(SetupEffect.CopyPlain(wrap))
        snack(UiText.res(L10nR.string.keyExportCopied))
        closeSecureSheet()
    }

    private fun onKeyFilePicked(action: SetupAction.KeyFilePicked) {
        val wrap = secureWrap ?: return
        if (action.uri == null) {
            closeSecureSheet()
            return
        }
        _state.update { s -> s.secureSheet?.let { s.copy(secureSheet = it.copy(busy = true)) } ?: s }
        launch {
            try {
                // Never leave a truncated key file behind, even if the screen goes away meanwhile.
                withContext(NonCancellable) { keyFileWriter.write(action.uri, KeyFile.content(action.header, wrap)) }
                snack(UiText.res(L10nR.string.idSetupSecureKeySaved), Tone.Success)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.warn(TAG, "Schlüsseldatei nicht gespeichert", e)
                _effects.send(SetupEffect.CopyPlain(wrap))
                snack(UiText.res(L10nR.string.keyExportCopied))
            }
            closeSecureSheet()
        }
    }

    private fun closeSecureSheet() {
        if (_state.value.secureSheet == null) return
        _state.update { it.copy(secureSheet = null, busy = true) }
        secureWrap = null
        launch { continueAfterIdentity() }
    }

    // endregion

    // region review demo

    private fun reviewSignIn() {
        val s = _state.value
        if (s.reviewBusy || s.busy) return
        _state.update { it.copy(reviewBusy = true, reviewError = null) }
        launch {
            try {
                reviewSignIn.signIn(s.reviewUser, s.reviewPass)
                _state.update { it.copy(reviewBusy = false, reviewPass = "") }
                finishIfOnboarded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(reviewBusy = false, reviewError = messageOf(e)) }
            }
        }
    }

    // endregion

    private suspend fun finishGuarded() {
        try {
            finishIfOnboarded()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, error = UiText.Raw(messageOf(e))) }
        }
    }

    private fun launch(block: suspend CoroutineScope.() -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.error(TAG, "Unerwarteter Fehler", e)
                _state.update { it.copy(busy = false, error = UiText.Raw(messageOf(e))) }
            }
        }
    }

    private fun send(effect: SetupEffect) {
        viewModelScope.launch { _effects.send(effect) }
    }

    private fun snack(text: UiText, tone: Tone = Tone.Neutral) {
        viewModelScope.launch { _snackbars.send(SnackbarMessage(text, tone, AppSnackbarDurations.Medium)) }
    }

    override fun onCleared() {
        bunker.dispose(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        super.onCleared()
    }

    companion object {
        private const val TAG = "IdentitySetup"

        /** Actions that change the step or start a flow; ignored while [IdentitySetupUiState.inputBlocked]. */
        private val STEP_ACTIONS: Set<SetupAction> = setOf(
            SetupAction.PickResume,
            SetupAction.PickNew,
            SetupAction.ConfirmOverwrite,
            SetupAction.PickExisting,
            SetupAction.OtherWay,
            SetupAction.ConnectAmber,
            SetupAction.ConnectPrimary,
            SetupAction.ConnectBunker,
            SetupAction.ImportKey,
            SetupAction.RestoreBackup,
            SetupAction.UsePasswordInstead,
            SetupAction.MeetupLater,
            SetupAction.OpenMeetupPicker,
            SetupAction.SaveName,
            SetupAction.SaveMeetup,
        )

        /** Exception text without a Dart/Kotlin type prefix (spec 01 §2.3.8: drop `FormatException: `). */
        fun messageOf(e: Throwable): String {
            val text = e.message?.takeIf { it.isNotBlank() } ?: e.toString()
            return text.removePrefix("FormatException: ").removePrefix("Exception: ")
        }

        /** Dart `tPasskeyError`: unavailable → `idSetupPasskeyUnavailable`, cancel → nothing, else the text. */
        fun passkeyError(e: Throwable): UiText? = when {
            e is PasskeyPrfException && e.isCancelled -> null
            e is PasskeyPrfException && e.isUnavailable -> UiText.res(L10nR.string.idSetupPasskeyUnavailable)
            else -> UiText.Raw(messageOf(e))
        }
    }
}
