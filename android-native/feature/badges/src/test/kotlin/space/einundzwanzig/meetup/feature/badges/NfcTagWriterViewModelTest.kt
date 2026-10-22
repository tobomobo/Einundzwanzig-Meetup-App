package space.einundzwanzig.meetup.feature.badges

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.issuance.nfc.NfcBadgePayload
import space.einundzwanzig.meetup.data.issuance.nfc.NfcWriteError
import space.einundzwanzig.meetup.data.meetups.MeetupService
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.feature.badges.nfc.NfcAvailability
import space.einundzwanzig.meetup.feature.badges.nfc.NfcWriteResult
import space.einundzwanzig.meetup.feature.badges.writer.HomeMeetupInfo
import space.einundzwanzig.meetup.feature.badges.writer.NfcTagWriterAction
import space.einundzwanzig.meetup.feature.badges.writer.NfcTagWriterEvent
import space.einundzwanzig.meetup.feature.badges.writer.NfcTagWriterViewModel
import space.einundzwanzig.meetup.feature.badges.writer.WriterDialog
import space.einundzwanzig.meetup.feature.badges.writer.WriterStatus

class NfcTagWriterViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val sessions = FakeRollingQrService()
    private val profiles = FakeProfiles(UserProfile(homeMeetupId = "107"))
    private val nfc = FakeNfc()
    private var directory: List<Meetup> = listOf(meetup("Würzburg", id = "107"))
    private val meetups: MeetupService = stub { name, args ->
        when (name) {
            "fetchMeetups" -> directory
            "getFallbackMeetups" -> emptyList<Meetup>()
            "resolveFavorite" -> directory.firstOrNull { it.id == args[0] }
            else -> error("unexpected $name")
        }
    }
    private val events = mutableListOf<NfcTagWriterEvent>()
    private var vm: NfcTagWriterViewModel? = null

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        vm?.viewModelScope?.cancel()
        Dispatchers.resetMain()
    }

    private fun TestScope.create(): NfcTagWriterViewModel {
        val model = NfcTagWriterViewModel(sessions, profiles, meetups, nfc).also { it.log = NoLog }
        backgroundScope.launch(dispatcher) { model.events.collect { events += it } }
        vm = model
        return model
    }

    private val payload = buildJsonObject {
        put("v", JsonPrimitive(2))
        put("t", JsonPrimitive("B"))
        put("m", JsonPrimitive("wuerzburg-de"))
    }

    @Test
    fun `home meetup is resolved with the size estimate`() = runTest(dispatcher) {
        val state = create().state.value
        assertEquals(HomeMeetupInfo.Found("Würzburg", "DE"), state.home)
        assertEquals(NfcBadgePayload.estimatedSize("Würzburg", "DE"), state.estimatedSize)
        assertEquals(WriterStatus.Ready, state.status)
    }

    @Test
    fun `missing home meetup blocks writing with a snackbar`() = runTest(dispatcher) {
        profiles.current = UserProfile()
        val model = create()
        assertEquals(HomeMeetupInfo.NoHome, model.state.value.home)
        assertEquals(WriterStatus.NoHomeMeetup, model.state.value.status)
        model.onAction(NfcTagWriterAction.CreateTag)
        assertEquals(listOf<NfcTagWriterEvent>(NfcTagWriterEvent.SelectHomeMeetup), events)
        assertFalse(model.state.value.waitingForTag)
    }

    @Test
    fun `unknown home meetup is shown as not found`() = runTest(dispatcher) {
        profiles.current = UserProfile(homeMeetupId = "999")
        val model = create()
        assertEquals(HomeMeetupInfo.NotFound("999"), model.state.value.home)
        assertEquals(WriterStatus.HomeNotFound, model.state.value.status)
        model.onAction(NfcTagWriterAction.CreateTag)
        assertEquals(listOf<NfcTagWriterEvent>(NfcTagWriterEvent.SelectHomeMeetup), events)
        assertFalse(model.state.value.waitingForTag)
        assertEquals(WriterStatus.HomeNotFound, model.state.value.status)
        assertEquals(0, sessions.basePayloadCalls)
    }

    @Test
    fun `legacy city name is matched in the directory`() = runTest(dispatcher) {
        profiles.current = UserProfile(homeMeetupId = "würzburg")
        assertEquals(HomeMeetupInfo.Found("Würzburg", "DE"), create().state.value.home)
    }

    @Test
    fun `no running session cannot be written`() = runTest(dispatcher) {
        val model = create()
        model.onAction(NfcTagWriterAction.CreateTag)
        assertEquals(WriterStatus.NoActiveSession, model.state.value.status)
        assertFalse(model.state.value.waitingForTag)
    }

    @Test
    fun `nfc unsupported or disabled shows the dialog`() = runTest(dispatcher) {
        sessions.basePayload = payload
        val model = create()
        nfc.availability = NfcAvailability.NOT_SUPPORTED
        model.onAction(NfcTagWriterAction.CreateTag)
        assertEquals(WriterDialog.NFC_UNAVAILABLE, model.state.value.dialog)
        assertEquals(WriterStatus.NoNfcDevice, model.state.value.status)
        model.onAction(NfcTagWriterAction.DismissDialog)
        nfc.availability = NfcAvailability.DISABLED
        model.onAction(NfcTagWriterAction.CreateTag)
        assertEquals(WriterDialog.NFC_DISABLED, model.state.value.dialog)
        model.onAction(NfcTagWriterAction.OpenNfcSettings)
        assertNull(model.state.value.dialog)
        assertEquals(1, nfc.settingsOpened)
    }

    @Test
    fun `writes the base payload as text record and jumps to the rolling QR after 3 s`() = runTest(dispatcher) {
        sessions.basePayload = payload
        val model = create()
        model.onAction(NfcTagWriterAction.CreateTag)
        assertEquals(WriterStatus.HoldTag, model.state.value.status)
        assertTrue(model.state.value.waitingForTag)
        val tag = FakeTag()
        model.onAction(NfcTagWriterAction.TagDiscovered(tag))
        val json = NfcBadgePayload.tagJson(payload)
        val (bytes, size) = tag.writes.single()
        assertArrayEquals(NfcBadgePayload.encodeTextPayload(json), bytes)
        assertEquals(json.length + 7, size)
        assertTrue(model.state.value.success)
        assertEquals(WriterStatus.Written(json.length), model.state.value.status)
        assertTrue(events.isEmpty())
        advanceTimeBy(3_001)
        assertEquals(listOf<NfcTagWriterEvent>(NfcTagWriterEvent.OpenRollingQr), events)
    }

    @Test
    fun `formatting a blank tag reports progress then success`() = runTest(dispatcher) {
        sessions.basePayload = payload
        val model = create()
        model.onAction(NfcTagWriterAction.CreateTag)
        model.onAction(NfcTagWriterAction.TagDiscovered(FakeTag(write = NfcWriteResult.Formatted, formats = true)))
        assertTrue(model.state.value.success)
    }

    @Test
    fun `write errors map to their status`() = runTest(dispatcher) {
        sessions.basePayload = payload
        val model = create()
        val cases = listOf(
            NfcWriteResult.ReadOnly to WriterStatus.ReadOnly,
            NfcWriteResult.NoNdef to WriterStatus.NoNdef,
            NfcWriteResult.FormatFailed to WriterStatus.FormatFailed,
            NfcWriteResult.TooSmall(320, 137) to WriterStatus.TooSmall(320, 137),
            NfcWriteResult.Failed("java.io.IOException: closed") to
                WriterStatus.WriteFailed(NfcWriteError.TAG_REMOVED_EARLY, "java.io.IOException: closed"),
            NfcWriteResult.Failed("android.nfc.TagLostException") to
                WriterStatus.WriteFailed(NfcWriteError.TAG_LOST, "android.nfc.TagLostException"),
        )
        for ((result, expected) in cases) {
            model.onAction(NfcTagWriterAction.CreateTag)
            model.onAction(NfcTagWriterAction.TagDiscovered(FakeTag(write = result)))
            assertEquals(expected, model.state.value.status)
            assertFalse(model.state.value.waitingForTag)
            assertFalse(model.state.value.success)
        }
    }

    @Test
    fun `reader start failure shows the start error`() = runTest(dispatcher) {
        sessions.basePayload = payload
        val model = create()
        model.onAction(NfcTagWriterAction.CreateTag)
        model.onAction(NfcTagWriterAction.SessionFailed("denied"))
        assertEquals(WriterStatus.StartError("denied"), model.state.value.status)
        assertFalse(model.state.value.waitingForTag)
    }

    @Test
    fun `tags are ignored before writing was started`() = runTest(dispatcher) {
        val model = create()
        val tag = FakeTag()
        model.onAction(NfcTagWriterAction.TagDiscovered(tag))
        assertTrue(tag.writes.isEmpty())
    }
}
