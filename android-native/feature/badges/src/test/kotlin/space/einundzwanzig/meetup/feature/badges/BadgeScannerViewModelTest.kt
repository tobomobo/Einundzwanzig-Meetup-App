package space.einundzwanzig.meetup.feature.badges

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.security.ExpiryInfo
import space.einundzwanzig.meetup.data.badges.security.VerifyFailure
import space.einundzwanzig.meetup.data.badges.security.VerifyResult
import space.einundzwanzig.meetup.data.issuance.nfc.NfcPayloadError
import space.einundzwanzig.meetup.data.issuance.scan.PendingBadge
import space.einundzwanzig.meetup.data.issuance.scan.SaveOutcome
import space.einundzwanzig.meetup.data.issuance.scan.ScanOutcome
import space.einundzwanzig.meetup.data.issuance.session.NonceStatus
import space.einundzwanzig.meetup.data.issuance.session.NonceValidation
import space.einundzwanzig.meetup.data.reputation.publish.ReputationPublisher
import space.einundzwanzig.meetup.feature.badges.nfc.NfcAvailability
import space.einundzwanzig.meetup.feature.badges.nfc.NfcReadResult
import space.einundzwanzig.meetup.feature.badges.scanner.BadgeScannerAction
import space.einundzwanzig.meetup.feature.badges.scanner.BadgeScannerEvent
import space.einundzwanzig.meetup.feature.badges.scanner.BadgeScannerViewModel
import space.einundzwanzig.meetup.feature.badges.scanner.BusyKind
import space.einundzwanzig.meetup.feature.badges.scanner.RejectReason
import space.einundzwanzig.meetup.feature.badges.scanner.ScanResultUi
import space.einundzwanzig.meetup.feature.badges.scanner.ScanStatus
import space.einundzwanzig.meetup.feature.badges.scanner.ScannerDialog
import space.einundzwanzig.meetup.feature.badges.scanner.ScannerSnackbar

class BadgeScannerViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val processor = FakeProcessor()
    private val save = FakeSaveUseCase()
    private val nfc = FakeNfc()
    private val published = mutableListOf<List<MeetupBadge>>()
    private val reputation: ReputationPublisher = stub { name, args ->
        @Suppress("UNCHECKED_CAST")
        if (name == "publishInBackground") published += args[0] as List<MeetupBadge>
        Unit
    }
    private val events = mutableListOf<BadgeScannerEvent>()
    private var vm: BadgeScannerViewModel? = null

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        vm?.viewModelScope?.cancel()
        Dispatchers.resetMain()
    }

    private fun TestScope.create(nfcEnabled: Boolean = false): BadgeScannerViewModel {
        val model = BadgeScannerViewModel(processor, save, reputation, nfc, FixedLabels, backgroundScope, nfcEnabled, NoLog)
        backgroundScope.launch(dispatcher) { model.events.collect { events += it } }
        vm = model
        return model
    }

    private val snackbars get() = events.filterIsInstance<BadgeScannerEvent.Snackbar>().map { it.kind }

    private fun BadgeScannerViewModel.scan(outcome: ScanOutcome?) {
        processor.qrResult = { outcome }
        onAction(BadgeScannerAction.QrScanned("{\"t\":\"B\"}"))
    }

    @Test
    fun `scan button opens the camera`() = runTest(dispatcher) {
        create().onAction(BadgeScannerAction.ScanQrClicked)
        assertEquals(listOf<BadgeScannerEvent>(BadgeScannerEvent.OpenCamera), events)
    }

    @Test
    fun `found badge shows the confirmation with the localized unknown-meetup label`() = runTest(dispatcher) {
        val model = create()
        model.unknownMeetupLabel = "Unknown meetup"
        val outcome = found()
        model.scan(outcome)
        assertEquals(ScanResultUi.Found(outcome.pending, outcome.info), model.state.value.result)
        assertEquals("Unknown meetup", processor.qrCalls.single().second)
        assertFalse(model.state.value.processing)
        assertTrue(BadgeScannerEvent.Haptic(true) in events)
    }

    @Test
    fun `label falls back to the injected labels`() = runTest(dispatcher) {
        create().scan(found())
        assertEquals("Unbekanntes Meetup", processor.qrCalls.single().second)
    }

    @Test
    fun `non-badge text is ignored`() = runTest(dispatcher) {
        val model = create()
        model.scan(null)
        assertNull(model.state.value.result)
        assertEquals(ScanStatus.Ready, model.state.value.status)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `review demo shows the demo dialog and issues nothing`() = runTest(dispatcher) {
        val model = create()
        model.scan(ScanOutcome.ReviewDemo)
        assertEquals(ScannerDialog.ReviewDemo, model.state.value.dialog)
        assertNull(model.state.value.result)
        model.onAction(BadgeScannerAction.DismissDialog)
        assertNull(model.state.value.dialog)
    }

    @Test
    fun `expired rolling code is rejected with an error haptic`() = runTest(dispatcher) {
        val model = create()
        val validation = NonceValidation(false, NonceStatus.EXPIRED, 42, "alt")
        model.scan(ScanOutcome.NonceRejected(validation))
        assertEquals(ScanStatus.NonceExpired(validation), model.state.value.status)
        assertTrue(BadgeScannerEvent.Haptic(false) in events)
    }

    @Test
    fun `verification failure and malformed payload show their message`() = runTest(dispatcher) {
        val model = create()
        model.scan(ScanOutcome.VerificationFailed(VerifyResult(false, 2, "", message = "Signatur ungültig!")))
        assertEquals(ScanStatus.Rejected("Signatur ungültig!"), model.state.value.status)
        model.scan(ScanOutcome.Malformed("Feld x hat falschen Typ"))
        assertEquals(ScanStatus.Rejected("Feld x hat falschen Typ"), model.state.value.status)
    }

    @Test
    fun `typed rejection reasons are passed on for localization`() = runTest(dispatcher) {
        val model = create()
        val expired = VerifyFailure.Expired(ExpiryInfo.Expired(2 * 3600 + 3 * 60))
        model.scan(ScanOutcome.VerificationFailed(VerifyResult(false, 2, "", message = "Tag abgelaufen! …", reason = expired)))
        assertEquals(ScanStatus.Rejected("Tag abgelaufen! …", RejectReason.Verify(expired)), model.state.value.status)
        model.scan(ScanOutcome.Malformed("Ungültiges Rolling-QR-Format.", rollingQrFormat = true))
        assertEquals(
            ScanStatus.Rejected("Ungültiges Rolling-QR-Format.", RejectReason.RollingQrMalformed),
            model.state.value.status,
        )
    }

    @Test
    fun `processor exception becomes a rejection instead of a crash`() = runTest(dispatcher) {
        val model = create()
        processor.qrResult = { throw IllegalStateException("boom") }
        model.onAction(BadgeScannerAction.QrScanned("x"))
        assertTrue(model.state.value.status is ScanStatus.Rejected)
        assertFalse(model.state.value.processing)
    }

    @Test
    fun `self scan stores nothing`() = runTest(dispatcher) {
        val model = create()
        model.scan(ScanOutcome.SelfScan)
        assertEquals(ScanStatus.SelfScan, model.state.value.status)
        assertNull(model.state.value.result)
    }

    @Test
    fun `already collected offers the wallet`() = runTest(dispatcher) {
        val model = create()
        model.scan(ScanOutcome.AlreadyCollected("Würzburg, DE", isEvent = false))
        assertEquals(ScanResultUi.AlreadyCollected("Würzburg, DE", false), model.state.value.result)
        model.onAction(BadgeScannerAction.OpenWallet)
        assertEquals(BadgeScannerEvent.OpenWallet, events.last())
    }

    @Test
    fun `a second scan while processing is ignored`() = runTest(dispatcher) {
        val model = create()
        processor.gate = CompletableDeferred()
        processor.qrResult = { found() }
        model.onAction(BadgeScannerAction.QrScanned("a"))
        assertTrue(model.state.value.processing)
        model.onAction(BadgeScannerAction.QrScanned("b"))
        processor.gate!!.complete(Unit)
        assertEquals(listOf("a"), processor.qrCalls.map { it.first })
    }

    @Test
    fun `saved badge publishes reputation, shows the saved snackbar and opens the wallet`() = runTest(dispatcher) {
        val model = create()
        model.scan(found())
        val collection = listOf(badge())
        save.outcomes += SaveOutcome.Saved(badge(), presenceVerified = true, askCoAttendance = false, collection = collection)
        model.onAction(BadgeScannerAction.AddToWallet)
        assertEquals(listOf(collection), published)
        assertEquals(listOf(ScannerSnackbar.SAVED), snackbars)
        assertEquals(BadgeScannerEvent.OpenWallet, events.last())
        assertNull(model.state.value.busy)
    }

    @Test
    fun `unverified presence shows its snackbar before the saved one`() = runTest(dispatcher) {
        val model = create()
        model.scan(found())
        save.outcomes += SaveOutcome.Saved(badge(), presenceVerified = false, askCoAttendance = false, collection = listOf(badge()))
        model.onAction(BadgeScannerAction.AddToWallet)
        assertEquals(listOf(ScannerSnackbar.UNVERIFIED_PRESENCE, ScannerSnackbar.SAVED), snackbars)
    }

    @Test
    fun `co-attendance opt-in yes publishes and then opens the wallet`() = runTest(dispatcher) {
        val model = create()
        model.scan(found())
        val saved = badge()
        save.outcomes += SaveOutcome.Saved(saved, presenceVerified = true, askCoAttendance = true, collection = listOf(saved))
        model.onAction(BadgeScannerAction.AddToWallet)
        assertEquals(ScannerDialog.CoAttendanceOptIn(saved), model.state.value.dialog)
        assertFalse(BadgeScannerEvent.OpenWallet in events)
        save.publishGate = CompletableDeferred()
        model.onAction(BadgeScannerAction.CoAttendanceAnswered(true))
        // The blocking indicator announces the publish, not a location check.
        assertEquals(BusyKind.PUBLISHING, model.state.value.busy)
        assertFalse(BadgeScannerEvent.OpenWallet in events)
        save.publishGate!!.complete(Unit)
        assertNull(model.state.value.busy)
        assertEquals(listOf(saved), save.published)
        assertEquals(listOf(ScannerSnackbar.SAVED, ScannerSnackbar.CO_ATTENDANCE_PUBLISHED), snackbars)
        assertEquals(BadgeScannerEvent.OpenWallet, events.last())
    }

    @Test
    fun `co-attendance publish without relays reports failure`() = runTest(dispatcher) {
        val model = create()
        model.scan(found())
        save.coAttendanceCount = 0
        save.outcomes += SaveOutcome.Saved(badge(), true, askCoAttendance = true, collection = listOf(badge()))
        model.onAction(BadgeScannerAction.AddToWallet)
        model.onAction(BadgeScannerAction.CoAttendanceAnswered(true))
        assertEquals(ScannerSnackbar.CO_ATTENDANCE_FAILED, snackbars.last())
    }

    @Test
    fun `declining or dismissing the opt-in opens the wallet without publishing`() = runTest(dispatcher) {
        val model = create()
        model.scan(found())
        save.outcomes += SaveOutcome.Saved(badge(), true, askCoAttendance = true, collection = listOf(badge()))
        model.onAction(BadgeScannerAction.AddToWallet)
        model.onAction(BadgeScannerAction.DismissDialog)
        assertTrue(save.published.isEmpty())
        assertNull(model.state.value.dialog)
        assertEquals(BadgeScannerEvent.OpenWallet, events.last())
    }

    @Test
    fun `too far keeps the badge pending and shows the dialog`() = runTest(dispatcher) {
        val model = create()
        val outcome = found()
        model.scan(outcome)
        save.outcomes += SaveOutcome.TooFar(distanceKm = 73.4, allowedKm = 50.0)
        model.onAction(BadgeScannerAction.AddToWallet)
        assertEquals(ScannerDialog.TooFar(73.4, 50.0), model.state.value.dialog)
        assertEquals(ScanResultUi.Found(outcome.pending, outcome.info), model.state.value.result)
        assertTrue(snackbars.isEmpty())
        // Retry works.
        model.onAction(BadgeScannerAction.DismissDialog)
        save.outcomes += SaveOutcome.Saved(badge(), true, false, listOf(badge()))
        model.onAction(BadgeScannerAction.AddToWallet)
        assertEquals(2, save.confirmed.size)
    }

    @Test
    fun `duplicate opens the wallet with the duplicate snackbar`() = runTest(dispatcher) {
        val model = create()
        model.scan(found())
        save.outcomes += SaveOutcome.Duplicate(badge(), presenceVerified = true)
        model.onAction(BadgeScannerAction.AddToWallet)
        assertEquals(listOf(ScannerSnackbar.DUPLICATE), snackbars)
        assertEquals(BadgeScannerEvent.OpenWallet, events.last())
        assertTrue(published.isEmpty())
    }

    @Test
    fun `kept aside switches to the aside view`() = runTest(dispatcher) {
        val model = create()
        model.scan(found())
        save.outcomes += SaveOutcome.KeptAside(badge(), presenceVerified = true)
        model.onAction(BadgeScannerAction.AddToWallet)
        assertEquals(ScanResultUi.KeptAside("Würzburg, DE"), model.state.value.result)
        assertEquals(listOf(ScannerSnackbar.HELD_ASIDE), snackbars)
    }

    @Test
    fun `save failure keeps the measured pending badge for a retry`() = runTest(dispatcher) {
        val model = create()
        model.scan(found())
        val measured = PendingBadge(badge(), orgLat = 49.8, orgLng = 9.9)
        save.outcomes += SaveOutcome.SaveFailed(measured, presenceVerified = true)
        model.onAction(BadgeScannerAction.AddToWallet)
        assertEquals(measured, (model.state.value.result as ScanResultUi.Found).pending)
        assertEquals(listOf(ScannerSnackbar.SAVE_FAILED), snackbars)
    }

    @Test
    fun `save exception shows the failure snackbar`() = runTest(dispatcher) {
        val model = create()
        model.scan(found())
        save.confirmError = IllegalStateException("disk")
        model.onAction(BadgeScannerAction.AddToWallet)
        assertEquals(listOf(ScannerSnackbar.SAVE_FAILED), snackbars)
        assertNull(model.state.value.busy)
    }

    @Test
    fun `add without a pending badge closes`() = runTest(dispatcher) {
        val model = create()
        model.scan(ScanOutcome.AlreadyCollected("X", false))
        model.onAction(BadgeScannerAction.AddToWallet)
        assertEquals(BadgeScannerEvent.Close, events.last())
    }

    @Test
    fun `nfc button is inert while the flag is off`() = runTest(dispatcher) {
        val model = create(nfcEnabled = false)
        model.onAction(BadgeScannerAction.ScanNfcClicked)
        assertEquals(ScanStatus.Ready, model.state.value.status)
        assertFalse(model.state.value.readingNfc)
    }

    @Test
    fun `nfc unsupported and disabled show their dialogs`() = runTest(dispatcher) {
        val model = create(nfcEnabled = true)
        nfc.availability = NfcAvailability.NOT_SUPPORTED
        model.onAction(BadgeScannerAction.ScanNfcClicked)
        assertEquals(ScannerDialog.NfcUnavailable, model.state.value.dialog)
        assertEquals(ScanStatus.NoNfcDevice, model.state.value.status)
        model.onAction(BadgeScannerAction.NfcDialogScanQr)
        assertNull(model.state.value.dialog)
        assertEquals(BadgeScannerEvent.OpenCamera, events.last())

        nfc.availability = NfcAvailability.DISABLED
        model.onAction(BadgeScannerAction.ScanNfcClicked)
        assertEquals(ScannerDialog.NfcDisabled, model.state.value.dialog)
        assertEquals(ScanStatus.NfcDisabledHint, model.state.value.status)
        model.onAction(BadgeScannerAction.NfcOpenSettings)
        assertEquals(1, nfc.settingsOpened)
    }

    @Test
    fun `nfc tag payload goes through the processor`() = runTest(dispatcher) {
        val model = create(nfcEnabled = true)
        model.onAction(BadgeScannerAction.ScanNfcClicked)
        assertTrue(model.state.value.readingNfc)
        assertEquals(ScanStatus.WaitingNfc, model.state.value.status)
        val outcome = found()
        processor.nfcResult = { outcome }
        model.onAction(BadgeScannerAction.NfcTagDiscovered(FakeTag()))
        assertEquals(1, processor.nfcCalls.size)
        assertFalse(model.state.value.readingNfc)
        assertEquals(ScanResultUi.Found(outcome.pending, outcome.info), model.state.value.result)
    }

    @Test
    fun `nfc tag without ndef stops reading, debounce releases after 1500 ms`() = runTest(dispatcher) {
        val model = create(nfcEnabled = true)
        model.onAction(BadgeScannerAction.ScanNfcClicked)
        val tag = FakeTag(read = NfcReadResult.NoNdef)
        model.onAction(BadgeScannerAction.NfcTagDiscovered(tag))
        assertEquals(ScanStatus.NfcRejected(NfcPayloadError.NoNdef), model.state.value.status)
        assertFalse(model.state.value.readingNfc)
        // Reading again: the debounce still blocks the next tag.
        model.onAction(BadgeScannerAction.ScanNfcClicked)
        model.onAction(BadgeScannerAction.NfcTagDiscovered(tag))
        assertEquals(1, tag.reads)
        advanceTimeBy(1501)
        model.onAction(BadgeScannerAction.NfcTagDiscovered(tag))
        assertEquals(2, tag.reads)
    }

    @Test
    fun `nfc empty tag and session errors map to their status`() = runTest(dispatcher) {
        val model = create(nfcEnabled = true)
        model.onAction(BadgeScannerAction.ScanNfcClicked)
        model.onAction(BadgeScannerAction.NfcTagDiscovered(FakeTag(read = NfcReadResult.Empty)))
        assertEquals(ScanStatus.NfcRejected(NfcPayloadError.TagEmpty), model.state.value.status)
        model.onAction(BadgeScannerAction.NfcSessionFailed("busy"))
        assertEquals(ScanStatus.NfcSessionError("busy"), model.state.value.status)
    }

    @Test
    fun `close pops`() = runTest(dispatcher) {
        create().onAction(BadgeScannerAction.Close)
        assertEquals(listOf<BadgeScannerEvent>(BadgeScannerEvent.Close), events)
    }
}
