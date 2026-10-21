package space.einundzwanzig.meetup.data.meetups

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonArrayOf
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.data.testing.InMemorySecureStore
import space.einundzwanzig.meetup.data.meetups.internal.MeetupServiceImpl
import space.einundzwanzig.meetup.data.meetups.internal.PortalApiServiceImpl
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Port of the data-layer half of `test/meetup_portal_identity_test.dart`: the meetup list keeps the
 * slug-derived portal link of group 107 and the public events endpoint (queried for three dates)
 * yields exactly one event with its own link. The `MeetupDate` conversion and
 * `MeetupEventMatcher.resolve(e, groups).id == "107"` are asserted in `:data:calendar`, which owns
 * both. The data-layer half of the live variant (`LIVE_PORTAL_TEST`) is [LivePortalTest]; its
 * matcher half belongs to `:data:calendar`.
 */
class MeetupPortalIdentityTest {

    private val server = FakeServer()

    @After
    fun tearDown() = server.close()

    @Test
    fun `portal ingestion retains meetup link separately from event URL`() = runTest {
        val portalLink = "https://portal.einundzwanzig.space/de/meetup/einundzwanzig-suedniedersachsen"
        val start = Instant.now().plus(10, ChronoUnit.DAYS).toString()
        server.respond = { _, path ->
            if (path == "/api/mobile/meetups") {
                json(
                    DartJson.encode(
                        jsonArrayOf(
                            mapOf(
                                "id" to 107,
                                "name" to "Einundzwanzig Südniedersachsen",
                                "city" to "Göttingen",
                                "slug" to "einundzwanzig-suedniedersachsen",
                            ),
                            mapOf(
                                "id" to 32,
                                "name" to "Einundzwanzig Sachsen",
                                "city" to "Sachsen",
                                "portalLink" to "https://portal.einundzwanzig.space/de/meetup/einundzwanzig-sachsen",
                            ),
                        ),
                    ),
                )
            } else {
                assertTrue(path.startsWith("/api/meetup-events/"))
                json(
                    DartJson.encode(
                        jsonObjectOf(
                            "data" to listOf(
                                mapOf(
                                    "id" to 3869,
                                    "start" to start,
                                    "meetup.name" to "Einundzwanzig Südniedersachsen",
                                    "meetup.portalLink" to portalLink,
                                    "meetup.city" to "Göttingen",
                                    "location" to "Dinx Diner",
                                    "link" to "https://example.com/event",
                                ),
                            ),
                        ),
                    ),
                )
            }
        }

        val groups = MeetupServiceImpl(server.client, testLog()).fetchMeetups()
        assertEquals(portalLink, groups.first { it.id == "107" }.portalLink)
        assertEquals("https://portal.einundzwanzig.space/de/meetup/einundzwanzig-sachsen", groups.first { it.id == "32" }.portalLink)

        val api = PortalApiServiceImpl(
            server.client, InMemorySecureStore(), InMemoryKeyValueStore(), FakeSigner().service, FakeTime(), testLog(),
        )
        val events = api.getAllMeetupEvents(LocalDate.now())
        assertEquals(1, events.size) // deduplicated by id across the three date requests
        assertEquals(4, server.seen.size)
        val e = events.single()
        assertEquals("3869", e["id"]!!.jsonPrimitive.content)
        assertEquals(portalLink, e["meetup.portalLink"]!!.jsonPrimitive.content)
        assertEquals("https://example.com/event", e["link"]!!.jsonPrimitive.content)
    }
}
