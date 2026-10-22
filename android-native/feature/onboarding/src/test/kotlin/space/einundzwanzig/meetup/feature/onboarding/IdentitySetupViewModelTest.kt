package space.einundzwanzig.meetup.feature.onboarding

import androidx.lifecycle.viewModelScope
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.data.identity.R as IdentityR
import space.einundzwanzig.meetup.data.identity.IdentityStorageException
import space.einundzwanzig.meetup.data.identity.IdentityError
import space.einundzwanzig.meetup.data.identity.nip46.Nip46RemoteException
import space.einundzwanzig.meetup.data.identity.AmberConnectResult
import space.einundzwanzig.meetup.data.identity.KeyFormatException
import space.einundzwanzig.meetup.data.identity.LocalEasyAuthException
import space.einundzwanzig.meetup.data.identity.Nip46ConnectResult
import space.einundzwanzig.meetup.data.identity.RecommendedExistingPath
import space.einundzwanzig.meetup.data.identity.passkey.PasskeyPrfException
import space.einundzwanzig.meetup.data.identity.passkey.PasskeyPrfResult
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.feature.onboarding.bunker.BunkerAction
import space.einundzwanzig.meetup.feature.onboarding.bunker.BunkerStep
import space.einundzwanzig.meetup.feature.onboarding.common.SnackbarMessage
import space.einundzwanzig.meetup.feature.onboarding.common.UiText
import space.einundzwanzig.meetup.feature.onboarding.setup.IdentitySetupViewModel
import space.einundzwanzig.meetup.feature.onboarding.setup.KeyFile
import space.einundzwanzig.meetup.feature.onboarding.setup.PasskeyRequest
import space.einundzwanzig.meetup.feature.onboarding.setup.PasswordField
import space.einundzwanzig.meetup.feature.onboarding.setup.SetupAction
import space.einundzwanzig.meetup.feature.onboarding.setup.SetupEffect
import space.einundzwanzig.meetup.feature.onboarding.setup.SetupStep

@OptIn(ExperimentalCoroutinesApi::class)
class IdentitySetupViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val signing = FakeSigningService()
    private val keyStore = FakeSecureKeyStore()
    private val vault = FakeLocalKeyVault()
    private val keys = FakeNostrKeyService()
    private val profiles = FakeUserProfileRepository()
    private val easyAuth = FakeLocalEasyAuth(vault, profiles)
    private val passkeys = FakePasskeyPrfService()
    private val review = FakeAppReviewSignIn()
    private val meetups = FakeMeetupService(listOf(meetup("1", "Berlin"), meetup("2", "Wien")))
    private val backup = FakeBackupLauncher()
    private val writer = FakeKeyFileWriter()
    private val time = FixedTime(1_700_000_000_000)
    private val effects = mutableListOf<SetupEffect>()
    private val snacks = mutableListOf<SnackbarMessage>()
    private var vm: IdentitySetupViewModel? = null

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        vm?.viewModelScope?.cancel()
        Dispatchers.resetMain()
    }

    private fun TestScope.create(): IdentitySetupViewModel {
        val model = IdentitySetupViewModel(
            signing, keyStore, vault, keys, easyAuth, passkeys, profiles, review, meetups, backup, writer, time,
        )
        backgroundScope.launch(dispatcher) { model.effects.collect { effects += it } }
        backgroundScope.launch(dispatcher) { model.snackbars.collect { snacks += it } }
        vm = model
        return model
    }

    private val IdentitySetupViewModel.s get() = state.value

    private fun IdentitySetupViewModel.fillNew(name: String = "Satoshi", pass: String = "12345678", pass2: String = pass) {
        onAction(SetupAction.PickNew)
        onAction(SetupAction.NameChanged(name))
        onAction(SetupAction.PassChanged(pass))
        onAction(SetupAction.Pass2Changed(pass2))
    }

    // region bootstrap / choose

    @Test
    fun `bootstrap detects a fresh device`() = runTest(dispatcher) {
        signing.path = RecommendedExistingPath.AMBER
        val vm = create()
        assertFalse(vm.s.bootstrapping)
        assertFalse(vm.s.canResume)
        assertFalse(vm.s.inputBlocked)
        assertEquals(RecommendedExistingPath.AMBER, vm.s.path)
        assertEquals(SetupStep.CHOOSE, vm.s.step)
        assertFalse(vm.s.offerReviewSignIn)
    }

    @Test
    fun `bootstrap treats an unreadable key store as an existing key`() = runTest(dispatcher) {
        keyStore.failure = IllegalStateException("keystore")
        val vm = create()
        assertTrue(vm.s.hasLocalKey)
        assertTrue(vm.s.canResume)
    }

    @Test
    fun `new identity over a surviving one asks for confirmation`() = runTest(dispatcher) {
        vault.passwordWrap = "ncryptsec1old"
        val vm = create()
        assertTrue(vm.s.canResume)
        vm.onAction(SetupAction.PickNew)
        assertTrue(vm.s.confirmOverwrite)
        assertEquals(SetupStep.CHOOSE, vm.s.step)
        vm.onAction(SetupAction.DismissOverwrite)
        assertFalse(vm.s.confirmOverwrite)
        vm.onAction(SetupAction.PickNew)
        vm.onAction(SetupAction.ConfirmOverwrite)
        assertEquals(SetupStep.NEW, vm.s.step)
    }

    @Test
    fun `close on choose pops`() = runTest(dispatcher) {
        val vm = create()
        vm.onAction(SetupAction.Close)
        assertEquals(listOf<SetupEffect>(SetupEffect.Back), effects)
    }

    @Test
    fun `back mapping follows the Dart leading button`() = runTest(dispatcher) {
        val vm = create()
        vm.onAction(SetupAction.PickExisting)
        vm.onAction(SetupAction.OtherWay)
        assertEquals(SetupStep.EXISTING_MORE, vm.s.step)
        vm.onAction(SetupAction.Back)
        assertEquals(SetupStep.EXISTING, vm.s.step)
        vm.onAction(SetupAction.Back)
        assertEquals(SetupStep.CHOOSE, vm.s.step)
        vm.onAction(SetupAction.PickNew)
        vm.onAction(SetupAction.Back)
        assertEquals(SetupStep.CHOOSE, vm.s.step)
        vm.onAction(SetupAction.Back)
        assertEquals(SetupEffect.Back, effects.last())
    }

    @Test
    fun `password reveal is per field`() = runTest(dispatcher) {
        val vm = create()
        vm.onAction(SetupAction.ToggleReveal(PasswordField.PASS))
        assertEquals(setOf(PasswordField.PASS), vm.s.revealed)
        vm.onAction(SetupAction.ToggleReveal(PasswordField.PASS2))
        vm.onAction(SetupAction.ToggleReveal(PasswordField.PASS))
        assertEquals(setOf(PasswordField.PASS2), vm.s.revealed)
    }

    // endregion

    // region register + secure sheet

    @Test
    fun `register validates name, length and match`() = runTest(dispatcher) {
        val vm = create()
        vm.fillNew(name = "Anon")
        vm.onAction(SetupAction.Register)
        assertEquals(UiText.res(L10nR.string.idSetupNameRequired), vm.s.error)
        vm.onAction(SetupAction.NameChanged("  "))
        vm.onAction(SetupAction.Register)
        assertEquals(UiText.res(L10nR.string.idSetupNameRequired), vm.s.error)
        vm.onAction(SetupAction.NameChanged("Hal"))
        vm.onAction(SetupAction.PassChanged("1234567"))
        vm.onAction(SetupAction.Register)
        assertEquals(UiText.res(L10nR.string.idSetupPasswordShort), vm.s.error)
        assertFalse(vm.s.pwOk)
        vm.onAction(SetupAction.PassChanged("12345678"))
        vm.onAction(SetupAction.Pass2Changed("12345679"))
        vm.onAction(SetupAction.Register)
        assertEquals(UiText.res(L10nR.string.keyExportMismatch), vm.s.error)
        assertTrue(easyAuth.registered.isEmpty())
    }

    @Test
    fun `register without passkey support opens the secure sheet, then the meetup step`() = runTest(dispatcher) {
        val vm = create()
        vm.fillNew(name = "  Satoshi ")
        assertTrue(vm.s.pwOk)
        vm.onAction(SetupAction.Register)
        assertEquals(listOf("Satoshi" to "12345678"), easyAuth.registered)
        assertEquals(1, vm.s.secureSheet?.step)
        assertFalse(vm.s.busy)

        vm.onAction(SetupAction.SecureCreateBackup)
        assertEquals(1, backup.exportCalls)
        assertEquals(2, vm.s.secureSheet?.step)

        vm.onAction(SetupAction.SecureLater)
        assertNull(vm.s.secureSheet)
        assertEquals(SetupStep.MEETUP, vm.s.step)
        assertFalse(vm.s.meetupsLoading)
        assertEquals(2, vm.s.meetups.size)
    }

    @Test
    fun `register storage error uses a localized reason`() = runTest(dispatcher) {
        easyAuth.registerFailure = IdentityStorageException("Schlüssel konnte nicht gespeichert werden.")
        val vm = create()
        vm.fillNew()
        vm.onAction(SetupAction.Register)
        assertEquals(UiText.res(IdentityR.string.native_identity_storage), vm.s.error)
        assertFalse(vm.s.busy)
        assertEquals(SetupStep.NEW, vm.s.step)
    }

    @Test
    fun `skip leads to step 2, copy copies the wrap and continues`() = runTest(dispatcher) {
        profiles.current = UserProfile(homeMeetupId = "1")
        val vm = create()
        vm.fillNew()
        vm.onAction(SetupAction.Register)
        vm.onAction(SetupAction.SecureSkip)
        assertEquals(2, vm.s.secureSheet?.step)
        assertEquals(0, backup.exportCalls)
        vm.onAction(SetupAction.SecureCopy)
        assertEquals(SetupEffect.CopyPlain("ncryptsec1wrap"), effects[0])
        assertEquals(UiText.res(L10nR.string.keyExportCopied), snacks.single().text)
        assertNull(vm.s.secureSheet)
        // A meetup is already set: straight home.
        assertEquals(SetupEffect.GoHome, effects.last())
    }

    @Test
    fun `save key file writes header and wrap`() = runTest(dispatcher) {
        val vm = create()
        vm.fillNew()
        vm.onAction(SetupAction.Register)
        vm.onAction(SetupAction.SecureSkip)
        vm.onAction(SetupAction.SecureSaveFile)
        val create = effects.single() as SetupEffect.CreateKeyFile
        assertEquals(KeyFile.fileName(time.millis), create.fileName)
        vm.onAction(SetupAction.KeyFilePicked("content://doc/1", "HEADER"))
        assertEquals(listOf("content://doc/1" to "HEADER\n\nncryptsec1wrap\n"), writer.written)
        assertEquals(UiText.res(L10nR.string.idSetupSecureKeySaved), snacks.single().text)
        assertNull(vm.s.secureSheet)
    }

    @Test
    fun `failed key file write falls back to the clipboard`() = runTest(dispatcher) {
        writer.failure = java.io.IOException("disk")
        val vm = create()
        vm.fillNew()
        vm.onAction(SetupAction.Register)
        vm.onAction(SetupAction.SecureSkip)
        vm.onAction(SetupAction.KeyFilePicked("content://doc/1", "HEADER"))
        assertTrue(effects.contains(SetupEffect.CopyPlain("ncryptsec1wrap")))
        assertEquals(UiText.res(L10nR.string.keyExportCopied), snacks.single().text)
    }

    @Test
    fun `cancelled key file picker just closes the sheet`() = runTest(dispatcher) {
        val vm = create()
        vm.fillNew()
        vm.onAction(SetupAction.Register)
        vm.onAction(SetupAction.SecureSkip)
        vm.onAction(SetupAction.KeyFilePicked(null, "HEADER"))
        assertTrue(writer.written.isEmpty())
        assertTrue(snacks.isEmpty())
        assertNull(vm.s.secureSheet)
    }

    @Test
    fun `the sheet stays busy while the key file is picked and written`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        writer.gate = gate
        val vm = create()
        vm.fillNew()
        vm.onAction(SetupAction.Register)
        vm.onAction(SetupAction.SecureSkip)
        vm.onAction(SetupAction.SecureSaveFile)
        assertEquals(true, vm.s.secureSheet?.busy)
        // A second picker, "Später" and the clipboard are ignored while the picker / write runs.
        vm.onAction(SetupAction.SecureSaveFile)
        vm.onAction(SetupAction.SecureLater)
        vm.onAction(SetupAction.SecureCopy)
        assertEquals(1, effects.count { it is SetupEffect.CreateKeyFile })
        assertNotNull(vm.s.secureSheet)
        vm.onAction(SetupAction.KeyFilePicked("content://doc/1", "HEADER"))
        assertEquals(true, vm.s.secureSheet?.busy)
        assertFalse(effects.any { it is SetupEffect.GoHome || it is SetupEffect.CopyPlain })
        gate.complete(Unit)
        assertEquals(1, writer.written.size)
        assertEquals(UiText.res(L10nR.string.idSetupSecureKeySaved), snacks.single().text)
        assertNull(vm.s.secureSheet)
    }

    @Test
    fun `cancelled picker after save file releases the sheet`() = runTest(dispatcher) {
        val vm = create()
        vm.fillNew()
        vm.onAction(SetupAction.Register)
        vm.onAction(SetupAction.SecureSkip)
        vm.onAction(SetupAction.SecureSaveFile)
        vm.onAction(SetupAction.KeyFilePicked(null, "HEADER"))
        assertNull(vm.s.secureSheet)
        assertEquals(SetupStep.MEETUP, vm.s.step)
    }

    @Test
    fun `key file name is zero padded local date`() {
        // 2024-03-05T10:00:00Z
        assertEquals("Nostr-Schluessel_2024-03-05_21MeetupApp.txt", KeyFile.fileName(1_709_632_800_000, ZoneOffset.UTC))
        assertEquals("H\n\nW\n", KeyFile.content("H", "W"))
    }

    // endregion

    // region passkey

    @Test
    fun `register with passkey support goes to the passkey step and back means later`() = runTest(dispatcher) {
        passkeys.supported = true
        val vm = create()
        vm.fillNew()
        vm.onAction(SetupAction.Register)
        assertEquals(SetupStep.PASSKEY, vm.s.step)
        assertNull(vm.s.secureSheet)
        vm.onAction(SetupAction.Back)
        assertEquals(1, vm.s.secureSheet?.step)
        assertEquals(1, easyAuth.registered.size)
    }

    @Test
    fun `add passkey runs the ceremony and saves the wrap`() = runTest(dispatcher) {
        passkeys.supported = true
        val vm = create()
        vm.fillNew()
        vm.onAction(SetupAction.Register)
        vm.onAction(SetupAction.AddPasskey)
        val request = vm.s.pendingPasskey as PasskeyRequest.Register
        assertEquals("Satoshi", request.registration.userName)
        assertTrue(vm.s.busy)
        val result = PasskeyPrfResult("cred", "ff")
        vm.onAction(SetupAction.PasskeyDone(request, Result.success(result)))
        assertEquals(result, easyAuth.savedPasskey)
        assertNotNull(vm.s.secureSheet)
    }

    @Test
    fun `a ceremony cancelled with its composition releases busy and is not relaunched`() = runTest(dispatcher) {
        passkeys.supported = true
        val vm = create()
        vm.fillNew()
        vm.onAction(SetupAction.Register)
        vm.onAction(SetupAction.AddPasskey)
        val request = vm.s.pendingPasskey!!
        assertFalse(vm.s.passkeyLaunched)
        vm.onAction(SetupAction.PasskeyLaunched(request))
        assertTrue(vm.s.passkeyLaunched)
        assertTrue(vm.s.busy)
        // The route's catch for a disposed composition.
        vm.onAction(SetupAction.PasskeyDone(request, Result.failure(PasskeyPrfException(PasskeyPrfException.CANCELLED))))
        assertNull(vm.s.pendingPasskey)
        assertFalse(vm.s.passkeyLaunched)
        assertFalse(vm.s.busy)
        assertNull(vm.s.error)
        assertEquals(SetupStep.PASSKEY, vm.s.step)
        assertNull(easyAuth.savedPasskey)
    }

    @Test
    fun `step changes are ignored while busy`() = runTest(dispatcher) {
        vault.passwordWrap = "ncryptsec1old"
        val vm = create()
        vm.onAction(SetupAction.PickResume)
        vm.onAction(SetupAction.UsePasswordInstead)
        vm.onAction(SetupAction.ResumePassChanged("pw"))
        easyAuth.unlockGate = CompletableDeferred()
        vm.onAction(SetupAction.Resume)
        assertTrue(vm.s.busy)
        // TalkBack / keyboard clicks reach onAction although the list absorbs touches.
        listOf(
            SetupAction.PickExisting, SetupAction.PickNew, SetupAction.PickResume, SetupAction.OtherWay,
            SetupAction.ConnectAmber, SetupAction.MeetupLater, SetupAction.OpenMeetupPicker,
        ).forEach(vm::onAction)
        assertEquals(SetupStep.RESUME, vm.s.step)
        assertEquals(RecommendedExistingPath.BUNKER, vm.s.path)
        assertFalse(vm.s.confirmOverwrite)
        assertFalse(vm.s.showMeetupPicker)
        assertTrue(effects.isEmpty())
        easyAuth.unlockGate!!.complete(Unit)
        assertFalse(vm.s.busy)
    }

    @Test
    fun `passkey errors map to unavailable, cancel shows nothing`() = runTest(dispatcher) {
        passkeys.supported = true
        val vm = create()
        vm.fillNew()
        vm.onAction(SetupAction.Register)
        vm.onAction(SetupAction.AddPasskey)
        var request = vm.s.pendingPasskey!!
        vm.onAction(SetupAction.PasskeyDone(request, Result.failure(PasskeyPrfException(PasskeyPrfException.DOMAIN_NOT_ASSOCIATED))))
        assertEquals(UiText.res(L10nR.string.idSetupPasskeyUnavailable), vm.s.error)
        assertFalse(vm.s.busy)
        assertEquals(SetupStep.PASSKEY, vm.s.step)

        vm.onAction(SetupAction.AddPasskey)
        request = vm.s.pendingPasskey!!
        vm.onAction(SetupAction.PasskeyDone(request, Result.failure(PasskeyPrfException(PasskeyPrfException.CANCELLED))))
        assertNull(vm.s.error)

        vm.onAction(SetupAction.PasskeyLater)
        assertNotNull(vm.s.secureSheet)
    }

    // endregion

    // region resume

    @Test
    fun `resume with a local key uses local mode and finishes`() = runTest(dispatcher) {
        keyStore.hasKey = true
        profiles.current = UserProfile(nickname = "Hal", favoriteMeetupIds = listOf("2"))
        val vm = create()
        vm.onAction(SetupAction.PickResume)
        assertEquals(SetupStep.RESUME, vm.s.step)
        vm.onAction(SetupAction.Resume)
        assertEquals(1, signing.useLocalCalls)
        assertEquals(listOf<SetupEffect>(SetupEffect.GoHome), effects)
    }

    @Test
    fun `resume with password needs a password and maps every failure to wrong password`() = runTest(dispatcher) {
        vault.passwordWrap = "ncryptsec1old"
        val vm = create()
        vm.onAction(SetupAction.PickResume)
        vm.onAction(SetupAction.Resume)
        assertEquals(UiText.res(L10nR.string.idSetupResumeNeedPassword), vm.s.error)
        easyAuth.unlockFailure = IllegalStateException("no wrap")
        vm.onAction(SetupAction.ResumePassChanged("short"))
        vm.onAction(SetupAction.Resume)
        assertEquals(UiText.res(L10nR.string.idSetupResumeWrongPassword), vm.s.error)
        easyAuth.unlockFailure = null
        vm.onAction(SetupAction.Resume)
        assertEquals(listOf("short"), easyAuth.unlockedWith)
        assertEquals("", vm.s.resumePass)
        // Default nickname "Anon": the name step follows.
        assertEquals(SetupStep.NAME_ONLY, vm.s.step)
    }

    @Test
    fun `resume with passkey unlocks with the PRF secret`() = runTest(dispatcher) {
        vault.passwordWrap = "ncryptsec1old"
        vault.passkeyWrap = "ncryptsec1pk"
        val vm = create()
        vm.onAction(SetupAction.PickResume)
        vm.onAction(SetupAction.ResumeWithPasskey)
        val request = vm.s.pendingPasskey as PasskeyRequest.Unlock
        assertEquals(listOf("cred"), request.credentialIds)
        val result = PasskeyPrfResult("cred", "ff")
        vm.onAction(SetupAction.PasskeyDone(request, Result.success(result)))
        assertEquals(result, easyAuth.prfUnlocked)
        assertEquals(SetupStep.NAME_ONLY, vm.s.step)
    }

    @Test
    fun `use password instead switches the resume mode, back resets it`() = runTest(dispatcher) {
        vault.passwordWrap = "w"
        vault.passkeyWrap = "p"
        val vm = create()
        vm.onAction(SetupAction.PickResume)
        vm.onAction(SetupAction.UsePasswordInstead)
        assertTrue(vm.s.resumeUsePassword)
        vm.onAction(SetupAction.ResumePassChanged("x"))
        vm.onAction(SetupAction.Back)
        assertFalse(vm.s.resumeUsePassword)
        assertEquals("", vm.s.resumePass)
        assertEquals(SetupStep.CHOOSE, vm.s.step)
    }

    // endregion

    // region existing: import, amber, bunker, backup

    @Test
    fun `import validates input`() = runTest(dispatcher) {
        val vm = create()
        vm.onAction(SetupAction.PickExisting)
        vm.onAction(SetupAction.OtherWay)
        vm.onAction(SetupAction.ImportChanged("   "))
        vm.onAction(SetupAction.ImportKey)
        assertEquals(UiText.res(L10nR.string.idSetupImportEmpty), vm.s.error)
        vm.onAction(SetupAction.ImportChanged("ncryptsec1abc"))
        vm.onAction(SetupAction.ImportKey)
        assertEquals(UiText.res(L10nR.string.idSetupImportNeedPassword), vm.s.error)
        assertTrue(keys.imported.isEmpty())
    }

    @Test
    fun `import nsec clears old wraps, uses local mode, asks for a name`() = runTest(dispatcher) {
        vault.passwordWrap = "old"
        val vm = create()
        vm.onAction(SetupAction.PickExisting)
        vm.onAction(SetupAction.OtherWay)
        vm.onAction(SetupAction.ImportChanged(" nsec1abc "))
        vm.onAction(SetupAction.ImportKey)
        assertEquals(listOf("nsec1abc"), keys.imported)
        assertEquals(1, vault.clearAllCalls)
        assertEquals(1, signing.useLocalCalls)
        assertEquals(SetupStep.NAME_ONLY, vm.s.step)
        assertEquals("", vm.s.importKey)
    }

    @Test
    fun `import ncryptsec passes the password`() = runTest(dispatcher) {
        profiles.current = UserProfile(nickname = "Hal", homeMeetupId = "1")
        val vm = create()
        vm.onAction(SetupAction.ImportChanged("ncryptsec1abc"))
        vm.onAction(SetupAction.ImportPassChanged("pw"))
        vm.onAction(SetupAction.ImportKey)
        assertEquals(listOf("ncryptsec1abc|pw"), keys.imported)
        assertEquals(SetupEffect.GoHome, effects.last())
    }

    @Test
    fun `import errors use the localized key-format reason`() = runTest(dispatcher) {
        keys.failure = KeyFormatException("FormatException: Ungültiges Format. Der Key muss mit \"nsec1\" beginnen.", reason = IdentityError.NSEC_PREFIX)
        val vm = create()
        vm.onAction(SetupAction.ImportChanged("xyz"))
        vm.onAction(SetupAction.ImportKey)
        assertEquals(UiText.res(IdentityR.string.native_identity_key_prefix), vm.s.error)
        assertFalse(vm.s.busy)
        assertEquals(0, vault.clearAllCalls)
    }

    @Test
    fun `amber primary path connects, failure shows connect failed`() = runTest(dispatcher) {
        signing.path = RecommendedExistingPath.AMBER
        signing.amberResult = AmberConnectResult.Cancelled
        val vm = create()
        vm.onAction(SetupAction.PickExisting)
        vm.onAction(SetupAction.ConnectPrimary)
        assertEquals(UiText.res(L10nR.string.idSetupConnectFailed), vm.s.error)
        assertFalse(vm.s.busy)

        signing.amberResult = AmberConnectResult.Success("ab", "npub1")
        vm.onAction(SetupAction.ConnectPrimary)
        assertEquals(SetupStep.NAME_ONLY, vm.s.step)
    }

    @Test
    fun `amber card in more options switches the primary path`() = runTest(dispatcher) {
        profiles.current = UserProfile(nickname = "Hal")
        val vm = create()
        vm.onAction(SetupAction.PickExisting)
        vm.onAction(SetupAction.OtherWay)
        vm.onAction(SetupAction.ConnectAmber)
        assertEquals(RecommendedExistingPath.AMBER, vm.s.path)
        // Named, no meetup: the meetup step follows.
        assertEquals(SetupStep.MEETUP, vm.s.step)
    }

    @Test
    fun `bunker primary opens the sheet busy, cancel releases it`() = runTest(dispatcher) {
        val vm = create()
        vm.onAction(SetupAction.PickExisting)
        vm.onAction(SetupAction.ConnectPrimary)
        assertTrue(vm.s.showBunkerSheet)
        assertTrue(vm.s.busy)
        assertEquals(1, signing.authHandlers.size)
        vm.onAction(SetupAction.Bunker(BunkerAction.Cancel))
        assertFalse(vm.s.showBunkerSheet)
        assertFalse(vm.s.busy)
        assertNull(vm.s.error)
        assertEquals(1, signing.closedInterceptors)
    }

    @Test
    fun `bunker paste success finishes the setup`() = runTest(dispatcher) {
        profiles.current = UserProfile(nickname = "Hal", homeMeetupId = "1")
        val vm = create()
        vm.onAction(SetupAction.ConnectBunker)
        assertFalse(vm.s.busy)
        vm.onAction(SetupAction.Bunker(BunkerAction.ShowPaste))
        assertEquals(BunkerStep.PASTE, vm.bunkerState.value.step)
        vm.onAction(SetupAction.Bunker(BunkerAction.PasteChanged(" bunker://abc?relay=wss://r ")))
        vm.onAction(SetupAction.Bunker(BunkerAction.ConnectPasted))
        assertEquals(listOf("bunker://abc?relay=wss://r"), signing.bunkerUris)
        assertFalse(vm.s.showBunkerSheet)
        assertEquals(SetupEffect.GoHome, effects.last())
        // Closing the sheet restores the previous auth_url handler (spec 01 §2.5).
        assertEquals(1, signing.closedInterceptors)
        assertTrue(signing.authHandlers.isEmpty())
    }

    @Test
    fun `restore backup from more options goes home on success`() = runTest(dispatcher) {
        backup.restoreResult = true
        val vm = create()
        vm.onAction(SetupAction.RestoreBackup)
        assertEquals(listOf<SetupEffect>(SetupEffect.GoHome), effects)
    }

    // endregion

    // region name, meetup, review

    @Test
    fun `name only saves the nickname and continues to the meetup step`() = runTest(dispatcher) {
        meetups.meetups = emptyList()
        keyStore.hasKey = true
        val vm = create()
        vm.onAction(SetupAction.PickResume)
        vm.onAction(SetupAction.Resume)
        assertEquals(SetupStep.NAME_ONLY, vm.s.step)
        vm.onAction(SetupAction.NameChanged("Anon"))
        vm.onAction(SetupAction.SaveName)
        assertEquals(UiText.res(L10nR.string.idSetupNameRequired), vm.s.error)
        vm.onAction(SetupAction.NameChanged(" Hal "))
        vm.onAction(SetupAction.SaveName)
        assertEquals("Hal", profiles.current.nickname)
        assertEquals(SetupStep.MEETUP, vm.s.step)
        // Empty directory: load error, only "later" is offered.
        assertEquals(UiText.res(L10nR.string.idSetupMeetupLoadError), vm.s.error)
        vm.onAction(SetupAction.MeetupLater)
        assertEquals(SetupEffect.GoHome, effects.last())
    }

    @Test
    fun `meetup selection saves favorites with the first as home`() = runTest(dispatcher) {
        profiles.current = UserProfile(nickname = "Hal")
        keyStore.hasKey = true
        val vm = create()
        vm.onAction(SetupAction.PickResume)
        vm.onAction(SetupAction.Resume)
        assertEquals(SetupStep.MEETUP, vm.s.step)
        vm.onAction(SetupAction.SaveMeetup) // nothing selected: ignored
        assertTrue(effects.isEmpty())
        vm.onAction(SetupAction.OpenMeetupPicker)
        assertTrue(vm.s.showMeetupPicker)
        vm.onAction(SetupAction.MeetupsPicked(listOf("2", "1")))
        assertFalse(vm.s.showMeetupPicker)
        vm.onAction(SetupAction.SaveMeetup)
        assertEquals(listOf("2", "1"), profiles.current.favoriteMeetupIds)
        assertEquals("2", profiles.current.homeMeetupId)
        assertEquals(listOf<SetupEffect>(SetupEffect.GoHome), effects)
    }

    @Test
    fun `back on the meetup step goes home`() = runTest(dispatcher) {
        profiles.current = UserProfile(nickname = "Hal")
        keyStore.hasKey = true
        val vm = create()
        vm.onAction(SetupAction.PickResume)
        vm.onAction(SetupAction.Resume)
        vm.onAction(SetupAction.Back)
        assertEquals(SetupEffect.GoHome, effects.last())
    }

    @Test
    fun `meetup load failure shows the load error`() = runTest(dispatcher) {
        meetups.failure = IllegalStateException("timeout")
        profiles.current = UserProfile(nickname = "Hal")
        keyStore.hasKey = true
        val vm = create()
        vm.onAction(SetupAction.Resume)
        assertEquals(SetupStep.MEETUP, vm.s.step)
        assertEquals(UiText.res(L10nR.string.idSetupMeetupLoadError), vm.s.error)
        assertTrue(vm.s.meetups.isEmpty())
    }

    @Test
    fun `review sign-in is offered by the build flag and finishes on success`() = runTest(dispatcher) {
        review.isOffered = true
        review.onSignIn = { user, pass ->
            if (pass != "secret") throw IllegalStateException("Invalid credentials")
            profiles.current = UserProfile(nickname = user, homeMeetupId = "Berlin", isReviewDemo = true)
        }
        val vm = create()
        assertTrue(vm.s.offerReviewSignIn)
        vm.onAction(SetupAction.ReviewUserChanged("reviewer"))
        vm.onAction(SetupAction.ReviewPassChanged("nope"))
        vm.onAction(SetupAction.ReviewSignIn)
        assertEquals("Invalid credentials", vm.s.reviewError)
        assertFalse(vm.s.reviewBusy)
        vm.onAction(SetupAction.ReviewPassChanged("secret"))
        vm.onAction(SetupAction.ReviewSignIn)
        assertNull(vm.s.reviewError)
        assertEquals(listOf<SetupEffect>(SetupEffect.GoHome), effects)
    }

    // endregion

    @Test
    fun `bunker timeout and errors stay in the sheet`() = runTest(dispatcher) {
        val vm = create()
        vm.onAction(SetupAction.ConnectBunker)
        vm.onAction(SetupAction.Bunker(BunkerAction.ShowPaste))
        vm.onAction(SetupAction.Bunker(BunkerAction.PasteChanged("bunker://x")))
        signing.bunkerResult = Nip46ConnectResult.Timeout
        vm.onAction(SetupAction.Bunker(BunkerAction.ConnectPasted))
        assertEquals(UiText.res(L10nR.string.bunkerTimeout), vm.bunkerState.value.error)
        assertTrue(vm.s.showBunkerSheet)
        signing.bunkerResult = Nip46ConnectResult.Error("Der Signer hat abgelehnt: nein", Nip46RemoteException("nein"))
        vm.onAction(SetupAction.Bunker(BunkerAction.ConnectPasted))
        assertEquals(UiText.res(IdentityR.string.native_identity_bunker_rejected), vm.bunkerState.value.error)
        assertFalse(vm.bunkerState.value.busy)
    }

    @Test
    fun `bunker connect is not started twice while busy`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Nip46ConnectResult>()
        signing.bunkerGate = gate
        val vm = create()
        vm.onAction(SetupAction.ConnectBunker)
        vm.onAction(SetupAction.Bunker(BunkerAction.PasteChanged("bunker://x")))
        vm.onAction(SetupAction.Bunker(BunkerAction.ConnectPasted))
        vm.onAction(SetupAction.Bunker(BunkerAction.ConnectPasted))
        assertEquals(1, signing.bunkerUris.size)
        assertTrue(vm.bunkerState.value.busy)
        // auth_url arrives while the sheet is open: shown inline.
        signing.authHandlers.single()("https://signer.example/approve")
        assertEquals("https://signer.example/approve", vm.bunkerState.value.authUrl)
        gate.complete(Nip46ConnectResult.Timeout)
        assertFalse(vm.bunkerState.value.busy)
    }

    @Test
    fun `scanned bunker QR fills the paste field, other codes are rejected`() = runTest(dispatcher) {
        val vm = create()
        vm.onAction(SetupAction.ConnectBunker)
        vm.onAction(SetupAction.Bunker(BunkerAction.Scanned("nostr:npub1xyz")))
        assertNotNull(vm.bunkerState.value.error)
        assertEquals(BunkerStep.CHOOSE, vm.bunkerState.value.step)
        vm.onAction(SetupAction.Bunker(BunkerAction.Scanned(" BUNKER://abc ")))
        assertEquals("BUNKER://abc", vm.bunkerState.value.pasteInput)
        assertEquals(BunkerStep.PASTE, vm.bunkerState.value.step)
        assertNull(vm.bunkerState.value.error)
    }

    @Test
    fun `signer app pairing failure shows the error`() = runTest(dispatcher) {
        val vm = create()
        vm.onAction(SetupAction.ConnectBunker)
        vm.onAction(SetupAction.Bunker(BunkerAction.StartSignerApp))
        assertEquals(BunkerStep.SIGNER_APP, vm.bunkerState.value.step)
        assertEquals(UiText.res(IdentityR.string.native_identity_generic), vm.bunkerState.value.error)
        assertFalse(vm.bunkerState.value.busy)
    }

    @Test
    fun `copy and no signer app snackbars`() = runTest(dispatcher) {
        val vm = create()
        vm.onAction(SetupAction.Bunker(BunkerAction.UriCopied))
        vm.onAction(SetupAction.Bunker(BunkerAction.NoSignerApp))
        assertEquals(
            listOf(UiText.res(L10nR.string.bunkerCopied), UiText.res(L10nR.string.bunkerNoSignerApp)),
            snacks.map { it.text },
        )
    }

    @Test
    fun `message helper strips Dart type prefixes`() {
        assertEquals("x", IdentitySetupViewModel.messageOf(IllegalStateException("Exception: x")))
        assertNull(IdentitySetupViewModel.passkeyError(PasskeyPrfException(PasskeyPrfException.CANCELLED)))
        assertEquals(
            UiText.res(IdentityR.string.native_identity_generic),
            IdentitySetupViewModel.passkeyError(PasskeyPrfException(PasskeyPrfException.FAILED, "Zeitüberschreitung")),
        )
    }
}
