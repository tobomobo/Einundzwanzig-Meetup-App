package space.einundzwanzig.meetup.data.issuance

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.store.BadgeStoreKeys
import space.einundzwanzig.meetup.data.issuance.internal.DefaultBadgeSaveUseCase
import space.einundzwanzig.meetup.data.issuance.scan.BadgeSavedEvent
import space.einundzwanzig.meetup.data.issuance.scan.PendingBadge
import space.einundzwanzig.meetup.data.issuance.scan.SaveOutcome
import space.einundzwanzig.meetup.data.meetups.location.GpsStatus
import space.einundzwanzig.meetup.data.meetups.location.LocationMeetupResult

/** `_confirmSaveBadge` (spec 02 §2.1 steps 1–15). */
class BadgeSaveUseCaseTest {

    private val time = FakeTime()
    private val store = StoreHarness(time = time)
    private val location = FakeLocation()
    private val directory = FakeDirectory()
    private val coAttendance = FakeCoAttendance()

    private fun useCase() = DefaultBadgeSaveUseCase(store.repo, location, directory, coAttendance).also { it.log = quietLog() }

    private val signedSig = "a".repeat(128)
    private val pubkey = "b".repeat(64)

    private fun badge(name: String = "Bad Neuenahr, DE", eventId: String = "bad-neuenahr-2025-10-09", sig: String = signedSig) =
        MeetupBadge(
            id = "bad-neuenahr",
            meetupName = name,
            date = DartDateTime.now(time),
            iconPath = "assets/badge_icon.png",
            meetupEventId = eventId,
            delivery = "rolling_qr",
            sig = sig,
            adminPubkey = pubkey,
            sigVersion = if (sig.length == 128) 2 else 0,
        )

    private fun at(lat: Double, lng: Double) = LocationMeetupResult(GpsStatus.OK, lat, lng)

    @Test
    fun `within 50 km of the directory meetup is verified presence and saved`() = runTest {
        directory.meetups = listOf(meetup("107", "Bad Neuenahr", lat = 50.5453, lng = 7.1123))
        location.resolved = at(50.60, 7.20)
        val outcome = useCase().confirm(PendingBadge(badge()))
        assertTrue("$outcome", outcome is SaveOutcome.Saved)
        outcome as SaveOutcome.Saved
        assertTrue(outcome.presenceVerified)
        assertTrue(outcome.askCoAttendance)
        assertEquals(50.60, outcome.badge.lat, 0.0)
        assertEquals(7.20, outcome.badge.lng, 0.0)
        val stored = store.repo.loadBadges()
        assertEquals(1, stored.size)
        assertTrue(stored[0].presenceVerified)
    }

    @Test
    fun `measurably too far is refused with the applied radius`() = runTest {
        directory.meetups = listOf(meetup("107", "Bad Neuenahr", lat = 50.5453, lng = 7.1123))
        location.resolved = at(48.137, 11.575) // Munich
        val outcome = useCase().confirm(PendingBadge(badge()))
        assertTrue(outcome is SaveOutcome.TooFar)
        outcome as SaveOutcome.TooFar
        assertEquals(50.0, outcome.allowedKm, 0.0)
        assertTrue(outcome.distanceKm > 400)
        assertTrue(store.repo.loadBadges().isEmpty())
    }

    @Test
    fun `scanner meetup coordinates come before the directory`() = runTest {
        directory.meetups = listOf(meetup("107", "Bad Neuenahr", lat = 50.5453, lng = 7.1123))
        location.resolved = at(48.137, 11.575)
        val outcome = useCase().confirm(PendingBadge(badge()), openedForMeetupLat = 48.14, openedForMeetupLng = 11.58)
        assertTrue(outcome is SaveOutcome.Saved)
        assertEquals(0, directory.fetchCalls)
    }

    @Test
    fun `signed coordinates are a measured 5 km reference`() = runTest {
        location.resolved = at(50.0, 8.0)
        val outcome = useCase().confirm(PendingBadge(badge(), orgLat = 50.1, orgLng = 8.0))
        assertEquals(5.0, (outcome as SaveOutcome.TooFar).allowedKm, 0.0)
    }

    @Test
    fun `no location means an unverified badge, not a refusal`() = runTest {
        directory.meetups = listOf(meetup("107", "Bad Neuenahr", lat = 50.5453, lng = 7.1123))
        location.resolved = LocationMeetupResult(GpsStatus.DENIED)
        val outcome = useCase().confirm(PendingBadge(badge())) as SaveOutcome.Saved
        assertFalse(outcome.presenceVerified)
        assertEquals(0.0, outcome.badge.lat, 0.0)
        assertFalse(store.repo.loadBadges()[0].presenceVerified)
    }

    @Test
    fun `no reference means an unverified badge`() = runTest {
        location.resolved = at(50.0, 8.0)
        directory.fallback = listOf(meetup("m_b", "Berlin", lat = 52.5, lng = 13.4))
        val outcome = useCase().confirm(PendingBadge(badge())) as SaveOutcome.Saved
        assertFalse(outcome.presenceVerified)
        assertEquals(1, directory.fetchCalls)
    }

    @Test
    fun `bundled list is the fallback reference when the portal is empty`() = runTest {
        location.resolved = at(52.52, 13.40)
        directory.fallback = listOf(meetup("m_b", "Berlin", lat = 52.5, lng = 13.4))
        val outcome = useCase().confirm(PendingBadge(badge(name = "Berlin, DE", eventId = "berlin-2025-10-09"))) as SaveOutcome.Saved
        assertTrue(outcome.presenceVerified)
    }

    @Test
    fun `duplicate of the same event is not stored twice, organizer markers do not count`() = runTest {
        store.repo.saveBadges(listOf(badge().copyWith(id = "org-bad-neuenahr-2025-10-09", isOrganizer = true, sig = "")))
        assertTrue(useCase().confirm(PendingBadge(badge())) is SaveOutcome.Saved)
        val dup = useCase().confirm(PendingBadge(badge()))
        assertTrue(dup is SaveOutcome.Duplicate)
        assertEquals(2, store.repo.loadBadges().size)
    }

    @Test
    fun `unreadable wallet keeps the scan aside`() = runTest {
        store.prefs.setString(BadgeStoreKeys.PRIMARY, "AAAA:BBBB")
        store.repo.loadBadges()
        val outcome = useCase().confirm(PendingBadge(badge()))
        assertTrue("$outcome", outcome is SaveOutcome.KeptAside)
        assertTrue(store.prefs.entries.value[BadgeStoreKeys.PENDING] is PrefValue.StringValue)
        assertEquals("AAAA:BBBB", store.prefs.getString(BadgeStoreKeys.PRIMARY))
        assertTrue(coAttendance.published.isEmpty())
    }

    @Test
    fun `nothing stored anywhere keeps the badge pending`() = runTest {
        store.prefs.setString(BadgeStoreKeys.PRIMARY, "AAAA:BBBB")
        store.repo.loadBadges()
        store.secure.failWrite = { true }
        val outcome = useCase().confirm(PendingBadge(badge()))
        assertTrue("$outcome", outcome is SaveOutcome.SaveFailed)
        assertEquals("bad-neuenahr", (outcome as SaveOutcome.SaveFailed).pending.badge.id)
    }

    @Test
    fun `saved outcome and event carry the stored collection for the reputation publisher`() = runTest {
        val uc = useCase()
        val events = mutableListOf<BadgeSavedEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { uc.badgeSaved.collect { events += it } }
        val outcome = uc.confirm(PendingBadge(badge())) as SaveOutcome.Saved
        job.cancel()
        assertEquals(1, events.size)
        assertSame(outcome.badge, events[0].badge)
        assertEquals(store.repo.badges.value, events[0].collection)
        // The reputation hook data travels with the outcome itself (no collector needed).
        assertEquals(store.repo.badges.value, outcome.collection)
        assertTrue(outcome.collection.any { it.id == outcome.badge.id })
    }

    @Test
    fun `co-attendance is offered only for nostr-signed badges with an event id`() = runTest {
        assertFalse((useCase().confirm(PendingBadge(badge(eventId = ""))) as SaveOutcome.Saved).askCoAttendance)
        assertFalse(
            (useCase().confirm(PendingBadge(badge(name = "Wien, AT", eventId = "wien-2025-10-09", sig = "short"))) as SaveOutcome.Saved)
                .askCoAttendance,
        )
        val uc = useCase()
        val b = badge()
        assertEquals(2, uc.publishCoAttendance(b))
        assertSame(b, coAttendance.published.first())
    }
}
