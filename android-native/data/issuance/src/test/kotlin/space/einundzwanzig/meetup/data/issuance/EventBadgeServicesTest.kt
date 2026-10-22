package space.einundzwanzig.meetup.data.issuance

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.data.testing.InMemorySecureStore
import space.einundzwanzig.meetup.data.admin.model.AdminCheckResult
import space.einundzwanzig.meetup.data.badges.security.BadgeSecurity
import space.einundzwanzig.meetup.data.issuance.event.EventBadgeRight
import space.einundzwanzig.meetup.data.issuance.event.EventChainStatus
import space.einundzwanzig.meetup.data.issuance.event.EventSessionError
import space.einundzwanzig.meetup.data.issuance.internal.DefaultEventBadgeAuthService
import space.einundzwanzig.meetup.data.issuance.internal.DefaultEventBadgeChainService
import space.einundzwanzig.meetup.data.issuance.internal.DefaultEventBadgeSessionService
import space.einundzwanzig.meetup.data.issuance.internal.DefaultOrganizerSessionService
import space.einundzwanzig.meetup.data.issuance.internal.DefaultRollingQrService
import space.einundzwanzig.meetup.data.issuance.internal.SeedSource
import space.einundzwanzig.meetup.data.meetups.location.LocationResult
import space.einundzwanzig.meetup.data.meetups.location.LocationStatus
import space.einundzwanzig.meetup.data.meetups.location.Position
import java.time.LocalDate
import java.time.ZonedDateTime

/** Event-badge chain, right and helper session (spec 02 §8.8–§8.10) and the organizer start (§2.8). */
class EventBadgeServicesTest {

    private val creator = KeyPair.generate()
    private val helper = KeyPair.generate()
    private val adminOk = AdminCheckResult(true, meetup = "Wien", name = "Anna", source = AdminCheckResult.SOURCE_LOCAL_CACHE)
    private val notFound = AdminCheckResult(false, source = AdminCheckResult.SOURCE_NOT_FOUND)

    // ---- chain ----

    @Test
    fun `chain statuses`() = runTest {
        val admins = FakeAdmins(byPubkey = { if (it == creator.publicKeyHex) adminOk else notFound })
        val calendar = FakeCalendar()
        val chain = DefaultEventBadgeChainService(calendar, admins).also { it.log = quietLog() }
        val event = calendarEvent(creator.publicKeyHex, issuers = listOf(helper.publicKeyHex.uppercase()))

        assertEquals(EventChainStatus.EVENT_NOT_FOUND, chain.verify(event.address, helper.publicKeyHex, 1).status)

        calendar.event = event
        chain.verify(event.address, helper.publicKeyHex, 1).let {
            assertEquals(EventChainStatus.VERIFIED, it.status)
            assertTrue(it.ok)
            assertEquals("Anna", it.creatorName)
            assertEquals(event.title, it.eventTitle)
            assertEquals(creator.publicKeyHex, it.creatorPubkey)
            assertEquals(event.badgeImageUrl, it.badgeImageUrl)
        }
        // The creator himself may issue.
        assertTrue(chain.verify(event.address, creator.publicKeyHex, null).ok)

        calendar.event = event.copy(badgeEnabled = false)
        chain.verify(event.address, helper.publicKeyHex, 1).let {
            assertEquals(EventChainStatus.EVENT_HAS_NO_BADGE, it.status)
            assertEquals(event.badgeImageUrl, it.badgeImageUrl)
        }

        calendar.event = event.copy(issuers = emptyList())
        assertEquals(EventChainStatus.SIGNER_NOT_LISTED, chain.verify(event.address, helper.publicKeyHex, 1).status)

        calendar.event = event
        admins.byPubkey = { notFound }
        chain.verify(event.address, helper.publicKeyHex, 1).let {
            assertEquals(EventChainStatus.CREATOR_NOT_AUTHORIZED, it.status)
            assertNull(it.creatorName)
        }
        admins.byPubkey = { throw IllegalStateException("offline") }
        assertEquals(EventChainStatus.CREATOR_NOT_AUTHORIZED, chain.verify(event.address, helper.publicKeyHex, 1).status)

        // Registry name missing: meetup is the creator name.
        admins.byPubkey = { AdminCheckResult(true, meetup = "Wien", source = AdminCheckResult.SOURCE_NOSTR_RELAY) }
        assertEquals("Wien", chain.verify(event.address, helper.publicKeyHex, 1).creatorName)
    }

    // ---- right ----

    @Test
    fun `event badge right sources in order`() = runTest {
        val identity = FakeIdentity()
        val admins = FakeAdmins()
        val directory = FakeDirectory()
        val profile = FakeProfile()
        val auth = DefaultEventBadgeAuthService(identity, admins, directory, profile).also { it.log = quietLog() }

        profile.admin = true
        assertEquals(EventBadgeRight.NONE, auth.myRight()) // no identity
        identity.npub = ""
        assertEquals(EventBadgeRight.NONE, auth.myRight())

        identity.npub = creator.npub
        profile.admin = false
        assertEquals(EventBadgeRight.NONE, auth.myRight())
        assertFalse(auth.mayCreate())

        profile.admin = true
        assertEquals(EventBadgeRight.ADMIN_REGISTRY, auth.myRight())

        directory.myPortalMeetups = { 2 }
        assertEquals(EventBadgeRight.PORTAL_LEADER, auth.myRight())

        admins.byNpub = { if (it == creator.npub) adminOk else notFound }
        assertEquals(EventBadgeRight.ADMIN_REGISTRY, auth.myRight())
        assertTrue(auth.mayCreate())

        // Errors in one source fall through to the next.
        admins.byNpub = { throw IllegalStateException("offline") }
        directory.myPortalMeetups = { throw IllegalStateException("no token") }
        assertEquals(EventBadgeRight.ADMIN_REGISTRY, auth.myRight())
        profile.admin = false
        assertEquals(EventBadgeRight.NONE, auth.myRight())
    }

    // ---- helper session ----

    private class SessionHarness(scope: TestScope) {
        val time = FakeTime()
        val prefs = InMemoryKeyValueStore()
        val secure = InMemorySecureStore()
        val identity = FakeIdentity()
        val location = FakeLocation()
        val coAttendance = FakeCoAttendance()
        val signer = TestSigner(time = time)
        val rollingQr = DefaultRollingQrService(prefs, secure, signer, identity, FakeBlockHeights(918_000), time, SeedSource { "1".repeat(64) })
            .also { it.log = quietLog() }
        val service = DefaultEventBadgeSessionService(identity, location, rollingQr, coAttendance, time, berlinZone, scope)
            .also { it.log = quietLog() }
    }

    private fun fix(lat: Double, lng: Double) = LocationResult(LocationStatus.OK, Position(lat, lng, 10.0, FIXTURE_NOW_MILLIS))

    @Test
    fun `helper session checks issuer, window, venue and distance`() = runTest {
        val h = SessionHarness(this)
        val event = calendarEvent(creator.publicKeyHex, issuers = listOf(helper.publicKeyHex), lat = 50.0, lng = 8.0)

        assertEquals(EventSessionError.NO_IDENTITY, h.service.start(event).error)
        h.identity.pubkeyHex = KeyPair.generate().publicKeyHex
        assertEquals(EventSessionError.NOT_ISSUER, h.service.start(event).error)

        h.identity.pubkeyHex = helper.publicKeyHex
        // Former issuers cannot start a session.
        assertEquals(
            EventSessionError.NOT_ISSUER,
            h.service.start(event.copy(issuers = emptyList(), formerIssuers = mapOf(helper.publicKeyHex to Long.MAX_VALUE))).error,
        )
        assertEquals(EventSessionError.OUTSIDE_WINDOW, h.service.start(event.copy(badgeEnabled = false)).error)
        val tomorrow = event.start.plusSeconds(86_400)
        assertEquals(EventSessionError.OUTSIDE_WINDOW, h.service.start(event.copy(start = tomorrow)).error)
        assertEquals(EventSessionError.NO_EVENT_LOCATION, h.service.start(event.copy(lat = 0.0, lng = 0.0)).error)

        val statuses = mapOf(
            LocationStatus.SERVICE_DISABLED to EventSessionError.LOCATION_SERVICE_OFF,
            LocationStatus.DENIED to EventSessionError.LOCATION_DENIED,
            LocationStatus.DENIED_FOREVER to EventSessionError.LOCATION_DENIED_FOREVER,
            LocationStatus.NO_FIX to EventSessionError.LOCATION_NO_FIX,
            LocationStatus.ERROR to EventSessionError.LOCATION_UNAVAILABLE,
        )
        statuses.forEach { (status, error) ->
            h.location.current = LocationResult(status)
            assertEquals(error, h.service.start(event).error)
        }

        h.location.current = fix(50.1, 8.0) // ~11 km
        h.service.start(event).let {
            assertEquals(EventSessionError.TOO_FAR_AWAY, it.error)
            assertEquals(11.1, it.distanceKm!!, 0.2)
        }
        assertNull(h.rollingQr.loadSession())
    }

    @Test
    fun `helper session runs until local midnight after the last day and records the helper`() = runTest {
        val h = SessionHarness(this)
        h.identity.pubkeyHex = helper.publicKeyHex
        h.location.current = fix(50.01, 8.0)
        val end = ZonedDateTime.of(2025, 10, 10, 18, 0, 0, 0, BERLIN).toInstant()
        val event = calendarEvent(creator.publicKeyHex, issuers = listOf(helper.publicKeyHex), end = end)

        val result = h.service.start(event)
        assertTrue(result.ok)
        val session = result.session!!
        val midnight = LocalDate.of(2025, 10, 11).atStartOfDay(BERLIN).toEpochSecond()
        assertEquals(midnight, session.expiresAt)
        assertEquals(BadgeSecurity.eventMeetupId(event.address, event.title), session.meetupId)
        assertEquals(event.title, session.meetupName)
        assertEquals("", session.meetupCountry)
        assertEquals(50.0, session.lat, 0.0) // venue, not the helper's position
        assertEquals(918_000, session.blockHeight)
        // Flutter replay bound: x = now + 4 h; re-signed by the rolling QR until midnight.
        assertEquals(session.startedAt + 4 * 3600, h.rollingQr.getBasePayload()!!.getValue("x").jsonPrimitive.long)

        advanceUntilIdle()
        assertEquals(listOf(Triple(event.title, 918_000L, true)), h.coAttendance.markers)

        // Signing failure → SESSION_FAILED.
        h.rollingQr.endSession()
        h.signer.canSign = false
        assertEquals(EventSessionError.SESSION_FAILED, h.service.start(event).error)
    }

    // ---- organizer ----

    @Test
    fun `organizer start replaces the session with the composed id and records the marker`() = runTest {
        val time = FakeTime()
        val prefs = InMemoryKeyValueStore()
        val secure = InMemorySecureStore()
        val coAttendance = FakeCoAttendance()
        val rollingQr = DefaultRollingQrService(
            prefs, secure, TestSigner(time = time), FakeIdentity(npub = creator.npub), FakeBlockHeights(918_000), time,
            SeedSource { "2".repeat(64) },
        ).also { it.log = quietLog() }
        rollingQr.getOrCreateSession("old", "Old", "", 1)
        val service = DefaultOrganizerSessionService(rollingQr, coAttendance, time).also { it.log = quietLog() }

        val result = service.startMeetupSession("Bad Neuenahr", "de", lat = 50.5, lng = 7.1)
        assertEquals("bad-neuenahr-de", result.session.meetupId)
        assertEquals("Bad Neuenahr", result.session.meetupName)
        assertEquals("de", result.session.meetupCountry)
        assertEquals(918_000, result.session.blockHeight)
        assertEquals(listOf(Triple("Bad Neuenahr", 918_000L, false)), coAttendance.markers)
        assertTrue(result.marker!!.isOrganizer)

        // Free text without a valid country keeps the bare slug.
        assertEquals("mein-stammtisch", service.startMeetupSession("Mein Stammtisch", "").session.meetupId)
        assertEquals("london", service.startMeetupSession("London", "UK").session.meetupId)
    }
}
