package space.einundzwanzig.meetup.feature.profile.edit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.navigation.HumanityProof
import space.einundzwanzig.meetup.core.navigation.PlatformProofs
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.data.identity.AmberConnectResult
import space.einundzwanzig.meetup.data.identity.Nip46ConnectResult
import space.einundzwanzig.meetup.data.identity.SigningMode
import space.einundzwanzig.meetup.data.identity.keys.StoredKeys
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityStatus
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProof
import space.einundzwanzig.meetup.feature.profile.bunker.BunkerAction
import space.einundzwanzig.meetup.feature.profile.common.SnackbarMessage
import space.einundzwanzig.meetup.feature.profile.common.UiText
import space.einundzwanzig.meetup.feature.profile.testing.FakeAuthenticator
import space.einundzwanzig.meetup.feature.profile.testing.FakeHumanity
import space.einundzwanzig.meetup.feature.profile.testing.FakeKeyService
import space.einundzwanzig.meetup.feature.profile.testing.FakeKeyStore
import space.einundzwanzig.meetup.feature.profile.testing.FakeMeetupService
import space.einundzwanzig.meetup.feature.profile.testing.FakePictureImporter
import space.einundzwanzig.meetup.feature.profile.testing.FakePlatformProofs
import space.einundzwanzig.meetup.feature.profile.testing.FakeProfileService
import space.einundzwanzig.meetup.feature.profile.testing.FakeProfiles
import space.einundzwanzig.meetup.feature.profile.testing.FakeSigningService
import space.einundzwanzig.meetup.feature.profile.testing.FakeVault
import space.einundzwanzig.meetup.feature.profile.testing.NPUB_A
import space.einundzwanzig.meetup.feature.profile.testing.NPUB_B
import space.einundzwanzig.meetup.feature.profile.testing.NSEC_A
import space.einundzwanzig.meetup.feature.profile.testing.meetup

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileEditViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val profiles = FakeProfiles()
    private val signing = FakeSigningService()
    private val keyStore = FakeKeyStore()
    private val keyService = FakeKeyService(keyStore)
    private val vault = FakeVault()
    private val meetups = FakeMeetupService(listOf(meetup("12", "München"), meetup("107", "Göttingen"), meetup("5", "Berlin")))
    private val platformProofs = FakePlatformProofs()
    private val humanity = FakeHumanity()
    private val profileService = FakeProfileService()
    private val pictures = FakePictureImporter()

    private val snackbars = mutableListOf<SnackbarMessage>()
    private val effects = mutableListOf<ProfileEditEffect>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(): ProfileEditViewModel {
        val vm = ProfileEditViewModel(
            profiles, signing, keyStore, keyService, vault, meetups, platformProofs, humanity,
            profileService, FakeAuthenticator(), pictures, backgroundScope,
        )
        vm.encodeDispatcher = dispatcher
        vm.ncryptsecEncoder = { priv, pw -> "ncryptsec1-$pw-${priv.take(4)}" }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.snackbars.collect { snackbars += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        return vm
    }

    private fun SnackbarMessage.resId(): Int = (text as UiText.Res).id
    private fun snackIds() = snackbars.map { (it.text as? UiText.Res)?.id }

    private fun withLocalKey() {
        keyStore.keys = StoredKeys(NSEC_A, NPUB_A)
        keyStore.privHex = "1".repeat(64)
        signing.npubValue = NPUB_A
    }

    // ------------------------------------------------------------------ load

    @Test
    fun `load fills the edit form, sorts meetups by city and falls back to home as favourite`() = runTest(dispatcher) {
        profiles.current = UserProfile(nickname = "Satoshi", homeMeetupId = "107", nostrNpub = NPUB_B)
        val s = vm().state.value
        assertFalse(s.isLoading)
        assertTrue(s.isEditing)
        assertEquals("Satoshi", s.nickname)
        assertEquals("107", s.selectedHome)
        assertEquals("Göttingen", s.homeLabel)
        assertEquals(listOf("107"), s.favorites)
        assertEquals(listOf("Berlin", "Göttingen", "München"), s.meetups.map { it.city })
        // No signer npub → the stored profile npub.
        assertEquals(NPUB_B, s.npub)
        assertFalse(s.hasNostrKey)
    }

    @Test
    fun `verified organizer starts read-only and unlocks after the warning`() = runTest(dispatcher) {
        profiles.current = UserProfile(nickname = "Org", isAdminVerified = true)
        val vm = vm()
        assertFalse(vm.state.value.isEditing)
        vm.onAction(ProfileEditAction.RequestEdit)
        assertEquals(ProfileDialog.UnlockWarning, vm.state.value.dialog)
        vm.onAction(ProfileEditAction.ConfirmEdit)
        assertTrue(vm.state.value.isEditing)
        assertNull(vm.state.value.dialog)
    }

    @Test
    fun `load error shows the error state and retry recovers`() = runTest(dispatcher) {
        profiles.loadError = IllegalStateException("Speicher kaputt")
        val vm = vm()
        assertEquals("Speicher kaputt", vm.state.value.loadError)
        profiles.loadError = null
        vm.onAction(ProfileEditAction.Retry)
        advanceUntilIdle()
        assertNull(vm.state.value.loadError)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `proof tiles follow the proof stores`() = runTest(dispatcher) {
        val vm = vm()
        assertEquals(0, vm.state.value.platformProofCount)
        platformProofs.stored.value = listOf(PlatformProof("telegram", "x", "a".repeat(128), 1))
        humanity.current.value = HumanityStatus(verified = true)
        advanceUntilIdle()
        assertEquals(1, vm.state.value.platformProofCount)
        assertTrue(vm.state.value.humanityVerified)
        vm.onAction(ProfileEditAction.OpenPlatformProofs)
        vm.onAction(ProfileEditAction.OpenHumanityProof)
        assertEquals(
            listOf(ProfileEditEffect.Navigate(PlatformProofs), ProfileEditEffect.Navigate(HumanityProof)),
            effects,
        )
    }

    // ------------------------------------------------------------------ form + save

    @Test
    fun `nickname validator`() {
        assertEquals(NicknameError.REQUIRED, ProfileEditUiState.validateNickname("   "))
        assertEquals(NicknameError.ANON, ProfileEditUiState.validateNickname(" AnOn "))
        assertEquals(NicknameError.TOO_SHORT, ProfileEditUiState.validateNickname("x"))
        assertNull(ProfileEditUiState.validateNickname("xy"))
    }

    @Test
    fun `save with missing fields shows profileFillIn with both fields and does not save`() = runTest(dispatcher) {
        val vm = vm()
        vm.onAction(ProfileEditAction.Save)
        advanceUntilIdle()
        assertTrue(profiles.saved.isEmpty())
        assertTrue(vm.state.value.homeMissing)
        assertTrue(vm.state.value.showValidation)
        assertEquals(NicknameError.ANON, vm.state.value.nicknameError) // default nickname "Anon"
        val msg = snackbars.single()
        assertEquals(L10nR.string.profileFillIn, msg.resId())
        assertEquals(Tone.Error, msg.tone)
        val fields = (msg.text as UiText.Res).args.single() as UiText.Concat
        assertEquals(3, fields.parts.size) // nickname, " und ", home meetup
        assertEquals(L10nR.string.profileNickname, (fields.parts[0] as UiText.Res).id)
        assertEquals(L10nR.string.profileHomeMeetupDash, (fields.parts[2] as UiText.Res).id)
    }

    @Test
    fun `picker result sets favourites and home, empty selection flags the home meetup`() = runTest(dispatcher) {
        val vm = vm()
        vm.onAction(ProfileEditAction.OpenMeetupPicker)
        assertEquals(ProfileDialog.MeetupPicker, vm.state.value.dialog)
        vm.onAction(ProfileEditAction.MeetupPickerDone(listOf("12", "107")))
        assertEquals("12", vm.state.value.selectedHome)
        assertEquals("München", vm.state.value.homeLabel)
        assertEquals(listOf("12", "107"), vm.state.value.favorites)
        assertFalse(vm.state.value.homeMissing)
        assertNull(vm.state.value.dialog)
        vm.onAction(ProfileEditAction.OpenMeetupPicker)
        vm.onAction(ProfileEditAction.MeetupPickerDone(emptyList()))
        assertEquals("", vm.state.value.selectedHome)
        assertTrue(vm.state.value.homeMissing)
    }

    @Test
    fun `save builds a fresh profile, drops the verified status and resets to home`() = runTest(dispatcher) {
        withLocalKey()
        profiles.current = UserProfile(
            nickname = "Old", fullName = "Full", telegramHandle = "tg", twitterHandle = "tw",
            isAdminVerified = true, adminViaPortal = true, homeMeetupId = "5",
        )
        val vm = vm()
        vm.onAction(ProfileEditAction.ConfirmEdit)
        vm.onAction(ProfileEditAction.NicknameChanged("  Neo  "))
        vm.onAction(ProfileEditAction.MeetupPickerDone(listOf("107", "12")))
        vm.onAction(ProfileEditAction.Save)
        advanceUntilIdle()
        val saved = profiles.saved.single()
        assertEquals("Neo", saved.nickname)
        assertEquals("Full", saved.fullName)
        assertEquals("tg", saved.telegramHandle)
        assertEquals("tw", saved.twitterHandle)
        assertEquals("107", saved.homeMeetupId)
        assertEquals(listOf("107", "12"), saved.favoriteMeetupIds)
        assertEquals(NPUB_A, saved.nostrNpub)
        assertFalse(saved.isAdminVerified)
        assertFalse(saved.adminViaPortal)
        assertTrue(saved.adminViaVouch) // old isAdmin survives as vouch only
        assertTrue(saved.hasNostrKey)
        assertTrue(saved.isNostrVerified)
        assertEquals(listOf<ProfileEditEffect>(ProfileEditEffect.ResetToHome), effects)
    }

    // ------------------------------------------------------------------ key creation / nsec

    @Test
    fun `create key asks education first, then generates, switches to local and shows the nsec after auth`() = runTest(dispatcher) {
        val vm = vm()
        vm.onAction(ProfileEditAction.CreateKey)
        assertEquals(ProfileDialog.KeyEducation, vm.state.value.dialog)
        vm.onAction(ProfileEditAction.KeyEducationResult(false))
        assertNull(vm.state.value.dialog)
        assertNull(keyStore.keys)

        vm.onAction(ProfileEditAction.CreateKey)
        vm.onAction(ProfileEditAction.KeyEducationResult(true))
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.hasNostrKey)
        assertTrue(s.isLocalMode)
        assertEquals(NPUB_A, s.npub)
        assertEquals(1, signing.useLocalCalls)
        assertEquals(1, vault.clearCalls)
        assertEquals(AuthPurpose.SHOW_NEW_KEY, s.pendingAuth)
        vm.onAction(ProfileEditAction.AuthResult(AuthPurpose.SHOW_NEW_KEY, ok = true))
        assertEquals(ProfileDialog.NsecBackup(NSEC_A), vm.state.value.dialog)
        assertNull(vm.state.value.pendingAuth)
        vm.onAction(ProfileEditAction.NsecCopied)
        assertEquals(L10nR.string.profileNsecCopied, snackbars.last().resId())
    }

    @Test
    fun `failed device auth after creation keeps the key but hides the nsec`() = runTest(dispatcher) {
        val vm = vm()
        vm.onAction(ProfileEditAction.KeyEducationResult(true))
        advanceUntilIdle()
        vm.onAction(ProfileEditAction.AuthResult(AuthPurpose.SHOW_NEW_KEY, ok = false))
        assertNull(vm.state.value.dialog)
        assertTrue(vm.state.value.hasNostrKey)
    }

    @Test
    fun `show nsec needs confirmation and device auth`() = runTest(dispatcher) {
        withLocalKey()
        val vm = vm()
        vm.onAction(ProfileEditAction.ShowNsec)
        advanceUntilIdle()
        assertEquals(ProfileDialog.ShowNsecConfirm, vm.state.value.dialog)
        vm.onAction(ProfileEditAction.ShowNsecConfirmed)
        assertEquals(AuthPurpose.SHOW_NSEC, vm.state.value.pendingAuth)
        vm.onAction(ProfileEditAction.AuthResult(AuthPurpose.SHOW_NSEC, ok = true))
        assertEquals(ProfileDialog.NsecBackup(NSEC_A), vm.state.value.dialog)
    }

    @Test
    fun `import nsec switches to the local key and closes the dialog`() = runTest(dispatcher) {
        signing.mode = SigningMode.AMBER
        signing.npubValue = NPUB_B
        val vm = vm()
        vm.onAction(ProfileEditAction.OpenImportNsec)
        vm.onAction(ProfileEditAction.ImportNsec("   "))
        advanceUntilIdle()
        assertEquals(ProfileDialog.ImportNsec, vm.state.value.dialog) // empty → no-op
        vm.onAction(ProfileEditAction.ImportNsec("  $NSEC_A "))
        advanceUntilIdle()
        assertNull(vm.state.value.dialog)
        assertEquals(SigningMode.LOCAL, vm.state.value.signingMode)
        assertEquals(NPUB_A, vm.state.value.npub)
        assertEquals(1, vault.clearCalls)
        assertEquals(L10nR.string.profileKeyImported, snackbars.last().resId())
        assertEquals(Tone.Success, snackbars.last().tone)
    }

    @Test
    fun `invalid nsec keeps the import dialog open and shows the raw error`() = runTest(dispatcher) {
        keyService.importError = IllegalArgumentException("Ungültiger nsec Key: x")
        val vm = vm()
        vm.onAction(ProfileEditAction.OpenImportNsec)
        vm.onAction(ProfileEditAction.ImportNsec("nsec1bad"))
        advanceUntilIdle()
        assertEquals(ProfileDialog.ImportNsec, vm.state.value.dialog)
        val msg = snackbars.last()
        assertEquals(UiText.Raw("Ungültiger nsec Key: x"), msg.text)
        assertEquals(Tone.Error, msg.tone)
        assertTrue(msg.dismissable)
    }

    @Test
    fun `import nsec ignores a second tap while running and is not blocked by a key generation`() = runTest(dispatcher) {
        val vm = vm()
        val gate = CompletableDeferred<Unit>()
        keyService.importGate = gate
        vm.onAction(ProfileEditAction.OpenImportNsec)
        vm.onAction(ProfileEditAction.ImportNsec(NSEC_A))
        advanceUntilIdle()
        assertTrue(vm.state.value.isImportingNsec)
        vm.onAction(ProfileEditAction.ImportNsec(NSEC_A))
        advanceUntilIdle()
        assertEquals(1, keyService.importCalls)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.isImportingNsec)
        assertEquals(1, vault.clearCalls)
        assertEquals(1, snackIds().count { it == L10nR.string.profileKeyImported })

        // A running Amber connect (isGeneratingKey) does not swallow the import.
        keyService.importGate = null
        val amberGate = CompletableDeferred<AmberConnectResult>()
        signing.amberGate = amberGate
        vm.onAction(ProfileEditAction.ConnectAmber)
        vm.onAction(ProfileEditAction.SwitchSignerConfirmed(SignerTarget.AMBER))
        advanceUntilIdle()
        assertTrue(vm.state.value.isGeneratingKey)
        vm.onAction(ProfileEditAction.OpenImportNsec)
        vm.onAction(ProfileEditAction.ImportNsec(NSEC_A))
        advanceUntilIdle()
        assertEquals(2, keyService.importCalls)
        amberGate.complete(AmberConnectResult.Cancelled)
        advanceUntilIdle()
    }

    @Test
    fun `save ignores a second tap while saving`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        profiles.saveGate = gate
        val vm = vm()
        vm.onAction(ProfileEditAction.NicknameChanged("Neo"))
        vm.onAction(ProfileEditAction.MeetupPickerDone(listOf("12")))
        vm.onAction(ProfileEditAction.Save)
        advanceUntilIdle()
        assertTrue(vm.state.value.isSaving)
        vm.onAction(ProfileEditAction.Save)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, profiles.saved.size)
        assertEquals(listOf<ProfileEditEffect>(ProfileEditEffect.ResetToHome), effects)
        assertFalse(vm.state.value.isSaving)
    }

    @Test
    fun `missing FragmentActivity denies the auth with an error snackbar`() = runTest(dispatcher) {
        withLocalKey()
        val vm = vm()
        vm.onAction(ProfileEditAction.ExportEncrypted)
        assertEquals(AuthPurpose.EXPORT, vm.state.value.pendingAuth)
        vm.onAction(ProfileEditAction.AuthUnavailable(AuthPurpose.EXPORT))
        advanceUntilIdle()
        assertNull(vm.state.value.pendingAuth)
        assertFalse(vm.state.value.isExporting)
        assertNull(vm.state.value.dialog)
        assertEquals(space.einundzwanzig.meetup.feature.profile.R.string.native_auth_unavailable, snackbars.last().resId())
        assertEquals(Tone.Error, snackbars.last().tone)
    }

    // ------------------------------------------------------------------ ncryptsec export

    @Test
    fun `export uses the vault wrap when it matches the npub`() = runTest(dispatcher) {
        withLocalKey()
        vault.wrap = "ncryptsec1vault"
        vault.wrapNpub = NPUB_A
        val vm = vm()
        vm.onAction(ProfileEditAction.ExportEncrypted)
        assertTrue(vm.state.value.isExporting)
        assertEquals(AuthPurpose.EXPORT, vm.state.value.pendingAuth)
        vm.onAction(ProfileEditAction.ExportEncrypted) // locked: ignored
        vm.onAction(ProfileEditAction.AuthResult(AuthPurpose.EXPORT, ok = true))
        advanceUntilIdle()
        assertEquals(ProfileDialog.NcryptsecSheet("ncryptsec1vault", fromVault = true), vm.state.value.dialog)
        assertFalse(vm.state.value.isExporting)
        vm.onAction(ProfileEditAction.NcryptsecCopied)
        assertNull(vm.state.value.dialog)
        assertEquals(L10nR.string.keyExportCopied, snackbars.last().resId())
    }

    @Test
    fun `export with a new password encrypts after the password dialog`() = runTest(dispatcher) {
        withLocalKey()
        vault.wrap = "ncryptsec1vault"
        vault.wrapNpub = NPUB_B // other identity → no fast path
        val vm = vm()
        vm.onAction(ProfileEditAction.ExportEncrypted)
        vm.onAction(ProfileEditAction.AuthResult(AuthPurpose.EXPORT, ok = true))
        advanceUntilIdle()
        assertEquals(ProfileDialog.ExportPassword, vm.state.value.dialog)
        vm.onAction(ProfileEditAction.ExportPassword("pw"))
        advanceUntilIdle()
        assertEquals(ProfileDialog.NcryptsecSheet("ncryptsec1-pw-1111", fromVault = false), vm.state.value.dialog)
        assertFalse(vm.state.value.isExporting)
        assertEquals("ncryptsec1vault", vault.wrap) // never stored
    }

    @Test
    fun `other password from the vault sheet reruns the export with a forced password`() = runTest(dispatcher) {
        withLocalKey()
        vault.wrap = "w"
        vault.wrapNpub = NPUB_A
        val vm = vm()
        vm.onAction(ProfileEditAction.ExportEncrypted)
        vm.onAction(ProfileEditAction.AuthResult(AuthPurpose.EXPORT, ok = true))
        advanceUntilIdle()
        vm.onAction(ProfileEditAction.ExportOtherPassword)
        assertEquals(AuthPurpose.EXPORT_NEW_PASSWORD, vm.state.value.pendingAuth)
        vm.onAction(ProfileEditAction.AuthResult(AuthPurpose.EXPORT_NEW_PASSWORD, ok = true))
        advanceUntilIdle()
        assertEquals(ProfileDialog.ExportPassword, vm.state.value.dialog)
        vm.onAction(ProfileEditAction.DismissDialog)
        assertFalse(vm.state.value.isExporting)
    }

    @Test
    fun `export aborts on failed auth and reports a missing key`() = runTest(dispatcher) {
        val vm = vm()
        vm.onAction(ProfileEditAction.ExportEncrypted)
        vm.onAction(ProfileEditAction.AuthResult(AuthPurpose.EXPORT, ok = false))
        assertFalse(vm.state.value.isExporting)
        assertTrue(snackbars.isEmpty())
        vm.onAction(ProfileEditAction.ExportEncrypted)
        vm.onAction(ProfileEditAction.AuthResult(AuthPurpose.EXPORT, ok = true))
        advanceUntilIdle()
        assertEquals(L10nR.string.keyExportNoKey, snackbars.single().resId())
        assertFalse(vm.state.value.isExporting)
    }

    // ------------------------------------------------------------------ signers

    @Test
    fun `amber over an existing identity asks first and warns about the identity change`() = runTest(dispatcher) {
        withLocalKey()
        val vm = vm()
        vm.onAction(ProfileEditAction.ConnectAmber)
        assertEquals(ProfileDialog.SwitchSignerConfirm(SignerTarget.AMBER), vm.state.value.dialog)
        vm.onAction(ProfileEditAction.SwitchSignerConfirmed(SignerTarget.AMBER))
        advanceUntilIdle()
        assertEquals(SigningMode.AMBER, vm.state.value.signingMode)
        assertEquals(NPUB_B, vm.state.value.npub)
        assertFalse(vm.state.value.isGeneratingKey)
        assertEquals(listOf(L10nR.string.profileAmberConnected, L10nR.string.profileIdentityChanged), snackIds())
        assertEquals(Tone.Warning, snackbars.last().tone)
    }

    @Test
    fun `amber missing opens the install dialog, cancelled shows a snackbar, error is red`() = runTest(dispatcher) {
        val vm = vm()
        signing.amberResult = AmberConnectResult.Missing
        vm.onAction(ProfileEditAction.ConnectAmber) // no identity → no confirmation
        advanceUntilIdle()
        assertEquals(ProfileDialog.AmberNotFound, vm.state.value.dialog)
        vm.onAction(ProfileEditAction.DismissDialog)
        signing.amberResult = AmberConnectResult.Cancelled
        vm.onAction(ProfileEditAction.ConnectAmber)
        advanceUntilIdle()
        assertEquals(L10nR.string.profileAmberAborted, snackbars.last().resId())
        signing.amberResult = AmberConnectResult.Error("kaputt")
        vm.onAction(ProfileEditAction.ConnectAmber)
        advanceUntilIdle()
        assertEquals(UiText.Res(L10nR.string.errorAmber, listOf("kaputt")), snackbars.last().text)
        assertEquals(Tone.Error, snackbars.last().tone)
    }

    @Test
    fun `bunker paste flow connects and closes the sheet`() = runTest(dispatcher) {
        val vm = vm()
        vm.onAction(ProfileEditAction.ConnectBunker)
        assertEquals(ProfileDialog.BunkerConnect, vm.state.value.dialog)
        assertEquals(1, signing.interceptors)
        vm.onAction(ProfileEditAction.Bunker(BunkerAction.ShowPaste))
        vm.onAction(ProfileEditAction.Bunker(BunkerAction.PasteChanged(" bunker://abc?relay=wss://r ")))
        vm.onAction(ProfileEditAction.Bunker(BunkerAction.ConnectPasted))
        assertTrue(vm.bunker.state.value.busy)
        advanceUntilIdle()
        assertEquals(listOf("bunker://abc?relay=wss://r"), signing.connectedUris)
        assertNull(vm.state.value.dialog)
        assertEquals(SigningMode.NIP46, vm.state.value.signingMode)
        assertTrue(vm.state.value.canSign)
        assertEquals(1, signing.closedInterceptors)
        assertEquals(L10nR.string.bunkerConnected, snackbars.last().resId())
    }

    @Test
    fun `bunker timeout shows the error inline and keeps the sheet open`() = runTest(dispatcher) {
        signing.bunkerResult = Nip46ConnectResult.Timeout
        val vm = vm()
        vm.onAction(ProfileEditAction.ConnectBunker)
        vm.onAction(ProfileEditAction.Bunker(BunkerAction.ShowPaste))
        vm.onAction(ProfileEditAction.Bunker(BunkerAction.PasteChanged("bunker://x")))
        vm.onAction(ProfileEditAction.Bunker(BunkerAction.ConnectPasted))
        advanceUntilIdle()
        assertEquals(ProfileDialog.BunkerConnect, vm.state.value.dialog)
        assertFalse(vm.bunker.state.value.busy)
        assertEquals(UiText.Res(L10nR.string.bunkerTimeout), vm.bunker.state.value.error)
        vm.onAction(ProfileEditAction.DismissDialog)
        assertNull(vm.state.value.dialog)
        assertEquals(1, signing.closedInterceptors)
    }

    @Test
    fun `signer app pairing failure is shown in the sheet`() = runTest(dispatcher) {
        val vm = vm()
        vm.onAction(ProfileEditAction.ConnectBunker)
        vm.onAction(ProfileEditAction.Bunker(BunkerAction.StartSignerApp))
        advanceUntilIdle()
        assertEquals(UiText.Raw("Keine Relays"), vm.bunker.state.value.error)
        assertFalse(vm.bunker.state.value.busy)
    }

    @Test
    fun `disconnect asks, calls the active signer and reloads`() = runTest(dispatcher) {
        signing.mode = SigningMode.NIP46
        signing.npubValue = NPUB_B
        val vm = vm()
        assertTrue(vm.state.value.canDisconnect)
        vm.onAction(ProfileEditAction.Disconnect)
        assertEquals(ProfileDialog.DisconnectConfirm, vm.state.value.dialog)
        vm.onAction(ProfileEditAction.DisconnectConfirmed)
        advanceUntilIdle()
        assertEquals(listOf("nip46"), signing.disconnects)
        assertEquals(SigningMode.LOCAL, vm.state.value.signingMode)
        assertEquals(L10nR.string.profileDisconnectDone, snackbars.last().resId())
    }

    @Test
    fun `bunker check reports alive and dead`() = runTest(dispatcher) {
        signing.mode = SigningMode.NIP46
        val vm = vm()
        vm.onAction(ProfileEditAction.CheckBunker)
        assertTrue(vm.state.value.isCheckingBunker)
        advanceUntilIdle()
        assertFalse(vm.state.value.isCheckingBunker)
        assertEquals(L10nR.string.bunkerAlive, snackbars.last().resId())
        signing.sessionAlive = false
        vm.onAction(ProfileEditAction.CheckBunker)
        advanceUntilIdle()
        assertEquals(L10nR.string.bunkerDead, snackbars.last().resId())
        assertTrue(snackbars.last().dismissable)
    }

    @Test
    fun `signer options re-offer the active type when the app cannot sign`() {
        val nip46 = ProfileEditUiState(signingMode = SigningMode.NIP46, canSign = true)
        assertFalse(nip46.offerBunker)
        assertTrue(nip46.offerAmber)
        assertTrue(nip46.canDisconnect)
        val stuck = nip46.copy(canSign = false)
        assertTrue(stuck.offerBunker)
        val local = ProfileEditUiState(hasLocalKey = true, canSign = true)
        assertTrue(local.isLocalMode)
        assertFalse(local.canDisconnect)
        // Mixed state: local key present but Amber active → no NSEC/export buttons.
        assertFalse(ProfileEditUiState(hasLocalKey = true, signingMode = SigningMode.AMBER).isLocalMode)
    }

    @Test
    fun `copy npub copies plainly and shows the orange snackbar`() = runTest(dispatcher) {
        withLocalKey()
        val vm = vm()
        vm.onAction(ProfileEditAction.CopyNpub)
        assertEquals(listOf<ProfileEditEffect>(ProfileEditEffect.CopyPlain(NPUB_A)), effects)
        assertEquals(L10nR.string.profileNpubCopied, snackbars.single().resId())
        assertEquals(Tone.Warning, snackbars.single().tone)
    }

    // ------------------------------------------------------------------ avatar

    @Test
    fun `picked avatar is stored as the local picture`() = runTest(dispatcher) {
        val vm = vm()
        vm.onAction(ProfileEditAction.PickAvatar)
        assertEquals(listOf<ProfileEditEffect>(ProfileEditEffect.PickImage), effects)
        vm.onAction(ProfileEditAction.AvatarPicked("content://media/1"))
        advanceUntilIdle()
        assertEquals("/files/profile_picture/local.jpg", vm.state.value.localPicture)
        assertEquals(listOf("content://media/1"), pictures.imported)
        assertFalse(vm.state.value.isImportingPicture)
    }

    @Test
    fun `avatar import failure shows homeImageLoadError`() = runTest(dispatcher) {
        pictures.error = java.io.IOException("kein Bild")
        val vm = vm()
        vm.onAction(ProfileEditAction.AvatarPicked("content://x"))
        advanceUntilIdle()
        assertNull(vm.state.value.localPicture)
        assertEquals(L10nR.string.homeImageLoadError, snackbars.single().resId())
    }
}
