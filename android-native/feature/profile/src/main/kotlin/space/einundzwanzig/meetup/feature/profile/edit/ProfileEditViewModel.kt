package space.einundzwanzig.meetup.feature.profile.edit

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.common.ApplicationScope
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.crypto.KeySecurity
import space.einundzwanzig.meetup.core.crypto.Nip49
import space.einundzwanzig.meetup.core.crypto.Nip49Exception
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.navigation.HumanityProof
import space.einundzwanzig.meetup.core.navigation.PlatformProofs
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarDurations
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.data.identity.AmberConnectResult
import space.einundzwanzig.meetup.data.identity.Nip46ConnectResult
import space.einundzwanzig.meetup.data.identity.SigningMode
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.identity.auth.DeviceAuthenticator
import space.einundzwanzig.meetup.data.identity.keys.LocalKeyVault
import space.einundzwanzig.meetup.data.identity.keys.NostrKeyService
import space.einundzwanzig.meetup.data.identity.keys.SecureKeyStore
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.meetups.MeetupService
import space.einundzwanzig.meetup.data.profile.LocalPictureImporter
import space.einundzwanzig.meetup.data.profile.NostrProfileService
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityProofRepository
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProofRepository
import space.einundzwanzig.meetup.feature.profile.R
import space.einundzwanzig.meetup.feature.profile.bunker.BunkerAction
import space.einundzwanzig.meetup.feature.profile.bunker.BunkerConnectController
import space.einundzwanzig.meetup.feature.profile.common.SnackbarMessage
import space.einundzwanzig.meetup.feature.profile.common.UiText
import androidx.navigation3.runtime.NavKey

/** One-shot effects the route executes (clipboard needs a Context, navigation the Navigator). */
sealed interface ProfileEditEffect {
    /** Plain copy (npub; no auto-clear). */
    data class CopyPlain(val text: String) : ProfileEditEffect

    data class Navigate(val key: NavKey) : ProfileEditEffect

    /** After save: clear the back stack, root = app shell (`resetTo(HomeTab)`). */
    data object ResetToHome : ProfileEditEffect

    data object Back : ProfileEditEffect

    /** Open the system photo picker (local avatar, spec 12 F14). */
    data object PickImage : ProfileEditEffect
}

/**
 * Profile screen (spec 07 §2.2) including the key management of spec 01 §2.7.
 * All data logic lives in the data modules; this class only orchestrates the flows.
 */
@HiltViewModel
class ProfileEditViewModel @Inject constructor(
    private val profiles: UserProfileRepository,
    private val signing: SigningService,
    private val keyStore: SecureKeyStore,
    private val keyService: NostrKeyService,
    private val vault: LocalKeyVault,
    private val meetupService: MeetupService,
    private val platformProofs: PlatformProofRepository,
    private val humanity: HumanityProofRepository,
    private val profileService: NostrProfileService,
    private val deviceAuthenticator: DeviceAuthenticator,
    private val pictureImporter: LocalPictureImporter,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileEditUiState())
    val state: StateFlow<ProfileEditUiState> = _state.asStateFlow()

    private val snackbarChannel = Channel<SnackbarMessage>(Channel.BUFFERED)
    val snackbars: Flow<SnackbarMessage> = snackbarChannel.receiveAsFlow()

    private val effectChannel = Channel<ProfileEditEffect>(Channel.BUFFERED)
    val effects: Flow<ProfileEditEffect> = effectChannel.receiveAsFlow()

    /** The BunkerConnectSheet logic (spec 01 §2.5). */
    val bunker = BunkerConnectController(signing, viewModelScope, ::onBunkerFinished)

    /** Test seam: NIP-49 encryption (scrypt logN 16, 64 MiB). */
    internal var ncryptsecEncoder: (privHex: String, password: String) -> String = { privHex, password ->
        Nip49.encrypt(privHex, password, logN = Nip49.DEFAULT_LOG_N, keySecurity = KeySecurity.INSECURE)
    }

    /** Test seam: where the scrypt run happens. */
    internal var encodeDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default

    /** Secrets held only between an action and its device authentication. */
    private var pendingNsec: String? = null
    private var pendingPrivHex: String? = null

    /** The profile as loaded (source of the fields the form does not edit). */
    private var loadedUser: UserProfile? = null

    init {
        viewModelScope.launch { loadData() }
        // Identity tiles follow the proof stores (Dart refreshed them after returning from the screens).
        viewModelScope.launch {
            platformProofs.proofs
                .catch { AppLogger.warn(TAG, "Plattform-Nachweise nicht lesbar", it) }
                .collect { list -> _state.update { it.copy(platformProofCount = list.size) } }
        }
        viewModelScope.launch {
            humanity.status
                .catch { AppLogger.warn(TAG, "Humanity-Status nicht lesbar", it) }
                .collect { s -> _state.update { it.copy(humanityVerified = s.verified) } }
        }
    }

    /** Device authentication runs in the route (needs the activity). */
    suspend fun authenticate(activity: FragmentActivity, title: String): Boolean =
        try {
            deviceAuthenticator.authenticate(activity, title)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.warn(TAG, "Geräte-Authentifizierung fehlgeschlagen", e)
            false
        }

    fun onAction(action: ProfileEditAction) {
        when (action) {
            ProfileEditAction.Back -> effectChannel.trySend(ProfileEditEffect.Back)
            ProfileEditAction.Retry -> viewModelScope.launch {
                _state.update { it.copy(isLoading = true, loadError = null) }
                loadData()
            }
            ProfileEditAction.DismissDialog -> dismissDialog()
            ProfileEditAction.RequestEdit -> setDialog(ProfileDialog.UnlockWarning)
            ProfileEditAction.ConfirmEdit -> _state.update { it.copy(dialog = null, isEditing = true) }
            is ProfileEditAction.NicknameChanged -> _state.update { it.copy(nickname = action.value) }
            ProfileEditAction.OpenMeetupPicker ->
                _state.update { it.copy(dialog = ProfileDialog.MeetupPicker, homeMissing = false) }
            is ProfileEditAction.MeetupPickerDone -> onMeetupsPicked(action.favorites)
            ProfileEditAction.Save -> save()
            ProfileEditAction.CopyNpub -> copyNpub()
            ProfileEditAction.CreateKey -> setDialog(ProfileDialog.KeyEducation)
            is ProfileEditAction.KeyEducationResult -> {
                setDialog(null)
                if (action.confirmed) generateKey()
            }
            ProfileEditAction.NsecCopied -> snack(L10nR.string.profileNsecCopied, Tone.Warning)
            ProfileEditAction.ShowNsec -> showExistingNsec()
            ProfileEditAction.ShowNsecConfirmed -> {
                setDialog(null)
                if (pendingNsec != null) requestAuth(AuthPurpose.SHOW_NSEC)
            }
            ProfileEditAction.OpenImportNsec -> setDialog(ProfileDialog.ImportNsec)
            is ProfileEditAction.ImportNsec -> importNsec(action.nsec)
            ProfileEditAction.ExportEncrypted -> exportNcryptsec(forceNewPassword = false)
            is ProfileEditAction.ExportPassword -> encryptWithPassword(action.password)
            ProfileEditAction.NcryptsecCopied -> {
                setDialog(null)
                snack(L10nR.string.keyExportCopied, Tone.Success)
            }
            ProfileEditAction.ExportOtherPassword -> {
                setDialog(null)
                exportNcryptsec(forceNewPassword = true)
            }
            is ProfileEditAction.AuthResult -> onAuthResult(action.purpose, action.ok)
            is ProfileEditAction.AuthUnavailable -> {
                // Technical cause stays in the log; the user gets a generic, localized message.
                AppLogger.error(TAG, "DeviceAuth needs FragmentActivity")
                onAuthResult(action.purpose, ok = false)
                showError(UiText.res(R.string.native_auth_unavailable))
            }
            ProfileEditAction.CheckBunker -> checkBunker()
            ProfileEditAction.ConnectAmber -> requestSwitch(SignerTarget.AMBER)
            ProfileEditAction.ConnectBunker -> requestSwitch(SignerTarget.BUNKER)
            is ProfileEditAction.SwitchSignerConfirmed -> {
                setDialog(null)
                startConnect(action.target)
            }
            ProfileEditAction.Disconnect -> setDialog(ProfileDialog.DisconnectConfirm)
            ProfileEditAction.DisconnectConfirmed -> disconnect()
            ProfileEditAction.OpenPlatformProofs -> effectChannel.trySend(ProfileEditEffect.Navigate(PlatformProofs))
            ProfileEditAction.OpenHumanityProof -> effectChannel.trySend(ProfileEditEffect.Navigate(HumanityProof))
            ProfileEditAction.PickAvatar -> effectChannel.trySend(ProfileEditEffect.PickImage)
            is ProfileEditAction.AvatarPicked -> importAvatar(action.uri)
            is ProfileEditAction.Bunker -> onBunkerAction(action.action)
        }
    }

    // ---------------------------------------------------------------- load

    private suspend fun loadData() {
        try {
            val user = profiles.load()
            loadedUser = user
            val fetched = meetupService.fetchMeetups()
            val meetups = fetched.ifEmpty { meetupService.cached.ifEmpty { meetupService.fallbackMeetups } }
                .sortedBy { it.city }
            val hasKey = keyStore.hasKey()
            val npub = signing.npub()
            val mode = signing.getMode()
            val canSign = signing.canSign()
            val localPicture = try {
                profileService.getExistingLocalPicture()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val favorites = user.favoriteMeetupIds.ifEmpty {
                if (user.homeMeetupId.isNotEmpty()) listOf(user.homeMeetupId) else emptyList()
            }
            _state.update {
                it.copy(
                    isLoading = false,
                    loadError = null,
                    isEditing = !user.isAdminVerified,
                    nickname = user.nickname,
                    savedNickname = user.nickname,
                    selectedHome = user.homeMeetupId,
                    homeLabel = labelFor(user.homeMeetupId),
                    favorites = favorites,
                    meetups = meetups,
                    hasLocalKey = hasKey,
                    signingMode = mode,
                    canSign = canSign,
                    npub = npub ?: user.nostrNpub,
                    localPicture = localPicture,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // E.g. SecureStorageUnreadableException: never treat an unreadable key as "no key".
            AppLogger.error(TAG, "Profil nicht ladbar", e)
            _state.update { it.copy(isLoading = false, loadError = e.message ?: e.toString()) }
        }
    }

    /** Re-reads only the identity part (after a signer disconnect); the form input stays. */
    private suspend fun reloadIdentity() {
        val hasKey = keyStore.hasKey()
        val npub = signing.npub()
        val mode = signing.getMode()
        val canSign = signing.canSign()
        _state.update {
            it.copy(
                hasLocalKey = hasKey,
                signingMode = mode,
                canSign = canSign,
                npub = npub ?: loadedUser?.nostrNpub.orEmpty(),
            )
        }
    }

    private fun labelFor(id: String): String = if (id.isEmpty()) "" else meetupService.labelFor(id).ifEmpty { id }

    // ---------------------------------------------------------------- form

    private fun onMeetupsPicked(favorites: List<String>) {
        val home = favorites.firstOrNull() ?: ""
        _state.update {
            it.copy(
                dialog = null,
                favorites = favorites,
                selectedHome = home,
                homeLabel = labelFor(home),
                homeMissing = favorites.isEmpty(),
            )
        }
    }

    private fun save() {
        val s = _state.value
        if (s.isSaving) return
        val formOk = ProfileEditUiState.validateNickname(s.nickname.dartTrim()) == null
        val homeOk = s.selectedHome.dartTrim().isNotEmpty()
        _state.update { it.copy(showValidation = true, homeMissing = !homeOk) }
        if (!formOk || !homeOk) {
            val missing = buildList {
                if (!formOk) add(UiText.res(L10nR.string.profileNickname))
                if (!homeOk) add(UiText.res(L10nR.string.profileHomeMeetupDash))
            }
            val joined = UiText.Concat(
                missing.flatMapIndexed { i, t -> if (i == 0) listOf(t) else listOf(UiText.res(R.string.native_list_joiner), t) },
            )
            snackbarChannel.trySend(
                SnackbarMessage(UiText.res(L10nR.string.profileFillIn, joined), Tone.Error, SAVE_HINT_MILLIS),
            )
            return
        }
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val old = loadedUser ?: UserProfile()
            val hasNostrKey = s.hasNostrKey
            // Fresh profile like Dart `UserProfile(...)` (spec 07 §3.3): verified status is dropped,
            // portal/seed sources reset, `isAdmin` survives as vouch.
            val profile = UserProfile(
                nickname = s.nickname.dartTrim(),
                fullName = old.fullName,
                homeMeetupId = s.selectedHome,
                favoriteMeetupIds = s.favorites.ifEmpty {
                    if (s.selectedHome.isNotEmpty()) listOf(s.selectedHome) else emptyList()
                },
                nostrNpub = s.npub,
                telegramHandle = old.telegramHandle,
                twitterHandle = old.twitterHandle,
                isAdminVerified = false,
                adminViaVouch = old.isAdmin,
                isNostrVerified = hasNostrKey,
                hasNostrKey = hasNostrKey,
            )
            try {
                profiles.save(profile)
                loadedUser = profile
                _state.update { it.copy(isSaving = false) }
                effectChannel.send(ProfileEditEffect.ResetToHome)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.error(TAG, "Profil nicht gespeichert", e)
                _state.update { it.copy(isSaving = false) }
                showError(UiText.res(L10nR.string.errorGeneric, e.message ?: e.toString()))
            }
        }
    }

    // ---------------------------------------------------------------- keys

    private fun copyNpub() {
        val npub = _state.value.npub
        if (npub.isEmpty()) return
        effectChannel.trySend(ProfileEditEffect.CopyPlain(npub))
        snackbarChannel.trySend(
            SnackbarMessage(UiText.res(L10nR.string.profileNpubCopied), Tone.Warning, AppSnackbarDurations.Brief),
        )
    }

    private fun generateKey() {
        if (_state.value.isGeneratingKey) return
        _state.update { it.copy(isGeneratingKey = true) }
        viewModelScope.launch {
            try {
                val keys = keyService.generateKeyPair()
                signing.useLocalMode()
                vault.clearAll()
                // `canSign` is not refreshed here (Dart parity).
                _state.update {
                    it.copy(isGeneratingKey = false, hasLocalKey = true, signingMode = SigningMode.LOCAL, npub = keys.npub)
                }
                pendingNsec = keys.nsec
                requestAuth(AuthPurpose.SHOW_NEW_KEY)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.error(TAG, "Schlüssel nicht erzeugt", e)
                _state.update { it.copy(isGeneratingKey = false) }
                showError(UiText.res(L10nR.string.errorGeneric, e.message ?: e.toString()))
            }
        }
    }

    private fun showExistingNsec() {
        viewModelScope.launch {
            val keys = try {
                keyStore.loadKeys()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.warn(TAG, "Schlüssel nicht lesbar", e)
                showError(UiText.res(L10nR.string.errorGeneric, e.message ?: e.toString()))
                null
            } ?: return@launch
            pendingNsec = keys.nsec
            setDialog(ProfileDialog.ShowNsecConfirm)
        }
    }

    private fun importNsec(raw: String) {
        val nsec = raw.trim()
        // Not blocked by isGeneratingKey: the import button stays enabled then (spec 01 §2.7, Flutter).
        if (nsec.isEmpty() || _state.value.isImportingNsec) return
        _state.update { it.copy(isImportingNsec = true) }
        viewModelScope.launch {
            try {
                val keys = keyService.importNsec(nsec)
                signing.useLocalMode()
                vault.clearAll()
                val canSign = runCatching { signing.canSign() }.getOrDefault(true)
                _state.update {
                    it.copy(dialog = null, hasLocalKey = true, signingMode = SigningMode.LOCAL, npub = keys.npub, canSign = canSign)
                }
                snack(L10nR.string.profileKeyImported, Tone.Success)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Dialog stays open; the message is the Flutter text ("Ungültiger nsec Key: …").
                showError(UiText.Raw(e.message ?: e.toString()))
            } finally {
                _state.update { it.copy(isImportingNsec = false) }
            }
        }
    }

    private fun exportNcryptsec(forceNewPassword: Boolean) {
        // Lock first: blocks a second dialog / a parallel 64 MiB scrypt run.
        if (_state.value.isExporting) return
        _state.update { it.copy(isExporting = true) }
        requestAuth(if (forceNewPassword) AuthPurpose.EXPORT_NEW_PASSWORD else AuthPurpose.EXPORT)
    }

    private fun onAuthResult(purpose: AuthPurpose, ok: Boolean) {
        _state.update { it.copy(pendingAuth = null) }
        when (purpose) {
            AuthPurpose.SHOW_NEW_KEY, AuthPurpose.SHOW_NSEC -> {
                val nsec = pendingNsec
                pendingNsec = null
                if (ok && nsec != null) setDialog(ProfileDialog.NsecBackup(nsec))
            }
            AuthPurpose.EXPORT, AuthPurpose.EXPORT_NEW_PASSWORD -> {
                if (!ok) {
                    _state.update { it.copy(isExporting = false) }
                    return
                }
                viewModelScope.launch { continueExport(force = purpose == AuthPurpose.EXPORT_NEW_PASSWORD) }
            }
        }
    }

    private suspend fun continueExport(force: Boolean) {
        try {
            val privHex = keyStore.getPrivHex()
            if (privHex.isNullOrEmpty()) {
                showError(UiText.res(L10nR.string.keyExportNoKey))
                _state.update { it.copy(isExporting = false) }
                return
            }
            if (!force) {
                val existing = vault.getPasswordWrap()
                val npub = keyStore.getNpub()
                if (!existing.isNullOrEmpty() && npub != null && vault.passwordWrapMatchesNpub(npub)) {
                    _state.update { it.copy(isExporting = false, dialog = ProfileDialog.NcryptsecSheet(existing, fromVault = true)) }
                    return
                }
            }
            pendingPrivHex = privHex
            setDialog(ProfileDialog.ExportPassword)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.warn(TAG, "Export fehlgeschlagen", e)
            _state.update { it.copy(isExporting = false) }
            showError(UiText.res(L10nR.string.errorGeneric, e.message ?: e.toString()))
        }
    }

    private fun encryptWithPassword(password: String) {
        val privHex = pendingPrivHex
        pendingPrivHex = null
        setDialog(null)
        if (privHex == null) {
            _state.update { it.copy(isExporting = false) }
            return
        }
        viewModelScope.launch {
            try {
                delay(50) // let the spinner render
                val ncryptsec = withContext(encodeDispatcher) { ncryptsecEncoder(privHex, password) }
                // A newly generated ncryptsec is not stored in the vault.
                _state.update { it.copy(dialog = ProfileDialog.NcryptsecSheet(ncryptsec, fromVault = false)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Nip49Exception) {
                showError(UiText.res(L10nR.string.errorGeneric, e.message ?: e.toString()))
            } catch (e: Exception) {
                showError(UiText.res(L10nR.string.errorGeneric, e.toString()))
            } finally {
                _state.update { it.copy(isExporting = false) }
            }
        }
    }

    private fun requestAuth(purpose: AuthPurpose) = _state.update { it.copy(pendingAuth = purpose) }

    // ---------------------------------------------------------------- signers

    private fun requestSwitch(target: SignerTarget) {
        if (_state.value.isGeneratingKey) return
        if (_state.value.hasNostrKey) setDialog(ProfileDialog.SwitchSignerConfirm(target)) else startConnect(target)
    }

    private fun startConnect(target: SignerTarget) = when (target) {
        SignerTarget.AMBER -> connectAmber()
        SignerTarget.BUNKER -> {
            setDialog(ProfileDialog.BunkerConnect)
            bunker.open()
        }
    }

    private fun connectAmber() {
        val previous = _state.value.npub
        _state.update { it.copy(isGeneratingKey = true) }
        viewModelScope.launch {
            try {
                when (val r = signing.connectAmber()) {
                    is AmberConnectResult.Success -> {
                        _state.update { it.copy(signingMode = SigningMode.AMBER, canSign = true, npub = r.npub) }
                        snack(L10nR.string.profileAmberConnected, Tone.Success)
                        warnIfIdentityChanged(previous, r.npub)
                    }
                    AmberConnectResult.Missing -> setDialog(ProfileDialog.AmberNotFound)
                    AmberConnectResult.Cancelled -> snack(L10nR.string.profileAmberAborted, Tone.Neutral)
                    is AmberConnectResult.Error -> showError(UiText.res(L10nR.string.errorAmber, r.message))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.warn(TAG, "Amber-Verbindung fehlgeschlagen", e)
                showError(UiText.res(L10nR.string.errorGeneric, e.message ?: e.toString()))
            } finally {
                _state.update { it.copy(isGeneratingKey = false) }
            }
        }
    }

    private fun onBunkerFinished(result: Nip46ConnectResult.Success?) {
        val previous = _state.value.npub
        if (result == null) {
            if (_state.value.dialog == ProfileDialog.BunkerConnect) setDialog(null)
            return
        }
        _state.update {
            it.copy(
                dialog = if (it.dialog == ProfileDialog.BunkerConnect) null else it.dialog,
                signingMode = SigningMode.NIP46,
                canSign = true,
                npub = result.npub,
            )
        }
        snack(L10nR.string.bunkerConnected, Tone.Success)
        warnIfIdentityChanged(previous, result.npub)
    }

    private fun warnIfIdentityChanged(previous: String, current: String) {
        if (previous.isNotEmpty() && previous != current) {
            snackbarChannel.trySend(
                SnackbarMessage(UiText.res(L10nR.string.profileIdentityChanged), Tone.Warning, AppSnackbarDurations.Long),
            )
        }
    }

    private fun disconnect() {
        setDialog(null)
        val s = _state.value
        viewModelScope.launch {
            try {
                when {
                    s.isNip46 -> signing.disconnectNip46()
                    s.isNip07 -> signing.disconnectNip07()
                    s.isAmber -> signing.disconnectAmber()
                }
                reloadIdentity()
                snack(L10nR.string.profileDisconnectDone, Tone.Success)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.warn(TAG, "Signer nicht getrennt", e)
                showError(UiText.res(L10nR.string.errorGeneric, e.message ?: e.toString()))
            }
        }
    }

    private fun checkBunker() {
        if (_state.value.isCheckingBunker) return
        _state.update { it.copy(isCheckingBunker = true) }
        viewModelScope.launch {
            val alive = try {
                signing.nip46SessionAlive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.warn(TAG, "Bunker-Ping fehlgeschlagen", e)
                false
            }
            _state.update { it.copy(isCheckingBunker = false) }
            if (alive) {
                snack(L10nR.string.bunkerAlive, Tone.Success)
            } else {
                showError(UiText.res(L10nR.string.bunkerDead))
            }
        }
    }

    // ---------------------------------------------------------------- avatar

    /** Gallery pick → `:data:profile` LocalPictureImporter (scaled copy + `local_profile_picture`, spec 12 F14). */
    private fun importAvatar(uri: String) {
        if (_state.value.isImportingPicture) return
        _state.update { it.copy(isImportingPicture = true) }
        viewModelScope.launch {
            try {
                val path = pictureImporter.import(uri)
                _state.update { it.copy(localPicture = path, isImportingPicture = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.warn(TAG, "Profilbild nicht übernommen", e)
                _state.update { it.copy(isImportingPicture = false) }
                snackbarChannel.trySend(SnackbarMessage(UiText.res(L10nR.string.homeImageLoadError, e.toString())))
            }
        }
    }

    private fun onBunkerAction(action: BunkerAction) {
        when (action) {
            BunkerAction.StartSignerApp -> bunker.startSignerApp()
            BunkerAction.ShowPaste -> bunker.showPaste()
            BunkerAction.BackToChoose -> bunker.backToChoose()
            is BunkerAction.PasteChanged -> bunker.onPasteChanged(action.value)
            BunkerAction.ConnectPasted -> bunker.connectPasted()
            BunkerAction.Cancel -> bunker.cancel()
            BunkerAction.UriCopied -> snack(L10nR.string.bunkerCopied, Tone.Neutral)
            BunkerAction.NoSignerApp -> snack(L10nR.string.bunkerNoSignerApp, Tone.Neutral)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun dismissDialog() {
        when (_state.value.dialog) {
            ProfileDialog.ExportPassword -> {
                pendingPrivHex = null
                _state.update { it.copy(isExporting = false) }
            }
            ProfileDialog.ShowNsecConfirm -> pendingNsec = null
            ProfileDialog.BunkerConnect -> {
                bunker.cancel()
                return
            }
            else -> Unit
        }
        setDialog(null)
    }

    private fun setDialog(dialog: ProfileDialog?) = _state.update { it.copy(dialog = dialog) }

    private fun snack(id: Int, tone: Tone) {
        snackbarChannel.trySend(SnackbarMessage(UiText.res(id), tone))
    }

    private fun showError(text: UiText) {
        snackbarChannel.trySend(SnackbarMessage.error(text))
    }

    override fun onCleared() {
        pendingNsec = null
        pendingPrivHex = null
        bunker.dispose(appScope)
    }

    private companion object {
        const val TAG = "ProfileEdit"

        /** Flutter's default SnackBar duration (4 s) for the validation hint. */
        const val SAVE_HINT_MILLIS = 4_000L
    }
}
