package space.einundzwanzig.meetup.data.meetups

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.data.testing.InMemorySecureStore
import space.einundzwanzig.meetup.data.meetups.internal.MeetupServiceImpl
import space.einundzwanzig.meetup.data.meetups.internal.PortalApiServiceImpl
import java.time.LocalDate
import java.time.ZoneId

/**
 * Data-layer half of the opt-in live test of `test/meetup_portal_identity_test.dart`
 * ("live portal: Südniedersachsen events resolve to 107, never Sachsen", spec 09 §10 item 2):
 * against the real portal, group 107 is "Einundzwanzig Südniedersachsen" and every public event of
 * that group carries meetup id 107 and its own portal link, never the "Einundzwanzig Sachsen" one.
 *
 * The `MeetupEventMatcher.resolve(e, groups).id == "107"` half needs `:data:calendar` (which
 * depends on this module) and belongs to its `MeetupCalendarServiceTest`.
 *
 * Skipped unless `LIVE_PORTAL_TEST` is set (environment or `-PlivePortalTest`, forwarded by
 * this module's `build.gradle.kts`); no network in the regression suite.
 */
class LivePortalTest {

    @Before
    fun optIn() {
        assumeTrue(
            "LIVE_PORTAL_TEST not set",
            System.getProperty("LIVE_PORTAL_TEST") != null || System.getenv("LIVE_PORTAL_TEST") != null,
        )
    }

    @Test(timeout = 60_000)
    fun `live portal - Suedniedersachsen events resolve to 107, never Sachsen`() = runBlocking {
        val client = OkHttpClient()
        val groups = MeetupServiceImpl(client, testLog()).fetchMeetups()
        val group = groups.single { it.id == "107" }
        assertEquals(NAME, group.name)
        assertTrue(group.portalLink, group.portalLink.endsWith("/meetup/einundzwanzig-suedniedersachsen"))

        val api = PortalApiServiceImpl(
            client, InMemorySecureStore(), InMemoryKeyValueStore(), FakeSigner().service, FakeTime(), testLog(),
        )
        val events = api.getAllMeetupEvents(LocalDate.now(ZoneId.systemDefault()))
        val southern = events.filter { (it["meetup.name"] as? JsonPrimitive)?.content == NAME }
        assertTrue("no live $NAME events", southern.isNotEmpty())
        for (e in southern) {
            assertEquals(e.toString(), "107", (e["meetup_id"] as JsonPrimitive).content)
            val link = (e["meetup.portalLink"] as? JsonPrimitive)?.content.orEmpty()
            assertTrue(link, link.endsWith("/meetup/einundzwanzig-suedniedersachsen"))
        }
        println("Verified ${southern.size} live $NAME event(s): ${southern.joinToString { (it["id"] as JsonPrimitive).content }}")
    }

    private companion object {
        const val NAME = "Einundzwanzig Südniedersachsen"
    }
}
