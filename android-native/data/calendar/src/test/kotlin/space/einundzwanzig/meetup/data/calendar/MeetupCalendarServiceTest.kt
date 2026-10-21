package space.einundzwanzig.meetup.data.calendar

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.data.calendar.internal.MeetupCalendarServiceImpl
import space.einundzwanzig.meetup.data.calendar.match.MeetupEventMatcher
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.data.meetups.portal.PortalApiService
import space.einundzwanzig.meetup.data.meetups.portal.PortalLogoRegistry
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Port of the calendar half of `test/meetup_portal_identity_test.dart` plus the iCal fallback.
 * The directory half (`/api/mobile/meetups` → `Meetup`) is ported in `:data:meetups`.
 */
class MeetupCalendarServiceTest {

    private val server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    private var icsBody: String = ""
    private var icsStatus = 200

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(icsStatus).body(icsBody).build()
        }
    }

    private val http: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val base = "http://127.0.0.1:${server.port}".toHttpUrl()
        val orig = chain.request().url
        chain.proceed(chain.request().newBuilder().url(base.newBuilder().encodedPath(orig.encodedPath).build()).build())
    }.build()

    @After
    fun tearDown() = server.close()

    private class Logos : PortalLogoRegistry {
        val map = LinkedHashMap<String, String>()
        override val logos: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap())
        override fun put(name: String, logo: String) {
            map[name] = logo
        }
        override fun seedFrom(meetups: List<Meetup>) = Unit
        override suspend fun ensureLoaded() = Unit
        override fun logoFor(titleOrCity: String): String = ""
    }

    private fun portal(events: () -> List<JsonObject>): PortalApiService = Proxy.newProxyInstance(
        PortalApiService::class.java.classLoader,
        arrayOf(PortalApiService::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getAllMeetupEvents" -> events()
            "toString" -> "FakePortal"
            "hashCode" -> 0
            "equals" -> false
            else -> throw UnsupportedOperationException(method.name)
        }
    } as PortalApiService

    private val time = FakeTime()
    private val log = testLog().apply { setVerbose(true) }

    private fun service(events: () -> List<JsonObject>, logos: Logos = Logos(), scheduler: TestCoroutineScheduler) =
        MeetupCalendarServiceImpl(portal(events), logos, http, time, log, UnconfinedTestDispatcher(scheduler))

    @Test
    fun `portal ingestion retains meetup link separately from event URL`() = runTest {
        val portalLink = "https://portal.einundzwanzig.space/de/meetup/einundzwanzig-suedniedersachsen"
        val start = Instant.ofEpochMilli(time.now).plus(10, ChronoUnit.DAYS)
        // The portal returns the same date for each of the three month requests; PortalApiService
        // merges by id, so the calendar sees it once.
        val raw = DartJson.decode(
            """
            {"id":3869,"start":"$start","meetup.name":"Einundzwanzig Südniedersachsen",
             "meetup.portalLink":"$portalLink","meetup.city":"Göttingen","location":"Dinx Diner",
             "link":"https://example.com/event","meetup.logo":"/storage/s.png","attendees":4}
            """.trimIndent(),
        ) as JsonObject
        val logos = Logos()
        val events = service({ listOf(raw) }, logos, testScheduler).fetchPortalFirst()
        assertEquals(1, events.size)
        val e = events.single()
        assertEquals("", e.meetupId)
        assertEquals(portalLink, e.meetupPortalLink)
        assertEquals("https://example.com/event", e.url)
        assertEquals(3869, e.portalEventId)
        assertEquals(4, e.attendees)
        assertEquals(mapOf("Einundzwanzig Südniedersachsen" to "/storage/s.png"), logos.map)
        val groups = listOf(
            Meetup(
                id = "107", name = "Einundzwanzig Südniedersachsen", city = "Göttingen", country = "DE", telegramLink = "",
                lat = 0.0, lng = 0.0, portalLink = "https://portal.einundzwanzig.space/de/meetup/einundzwanzig-suedniedersachsen",
            ),
            Meetup(
                id = "32", name = "Einundzwanzig Sachsen", city = "Sachsen", country = "DE", telegramLink = "",
                lat = 0.0, lng = 0.0, portalLink = "https://portal.einundzwanzig.space/de/meetup/einundzwanzig-sachsen",
            ),
        )
        assertEquals("107", MeetupEventMatcher.resolve(e, groups)?.id)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `nested meetup map, cutoff and fallbacks`() = runTest {
        val now = Instant.ofEpochMilli(time.now)
        val raw = DartJson.decode(
            """
            [{"id":1,"start":"${now.minus(5, ChronoUnit.HOURS)}","meetup":{"name":"  ","id":42,"portalLink":"https://p/x"}},
             {"id":2,"start":"${now.minus(7, ChronoUnit.HOURS)}","meetup.name":"Alt"},
             {"id":"3","start":"${now.plus(1, ChronoUnit.DAYS)}","meetup":{"name":null},"meetup.name":"Flach","description":null},
             {"start":""}]
            """.trimIndent(),
        ) as JsonArray
        val events = MeetupCalendarService.mapPortalEvents(raw.map { it as JsonObject }, now)
        assertEquals(listOf("Meetup", "Flach"), events.map { it.title })
        assertEquals("42", events[0].meetupId)
        assertEquals("https://p/x", events[0].url)
        assertEquals(null, events[1].portalEventId)
        assertEquals("", events[1].description)
    }

    @Test
    fun `empty portal falls back to the iCal feed`() = runBlocking {
        // Real I/O: runTest's virtual clock would fire the 15 s call timeout immediately.
        val scheduler = TestCoroutineScheduler()
        icsBody = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:x\r\nBEGIN:VEVENT\r\nSUMMARY:ICS Meetup\r\n" +
            "DTSTART:20270101T180000Z\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val events = service({ emptyList() }, scheduler = scheduler).fetchPortalFirst()
        assertEquals(log.entries.value.joinToString { it.message }, listOf("ICS Meetup"), events.map { it.title })
        assertEquals("/stream-calendar", server.takeRequest().url.encodedPath)

        val failing = service({ throw IllegalStateException("down") }, scheduler = scheduler).fetchPortalFirst()
        assertEquals(1, failing.size)

        icsStatus = 500
        assertTrue(service({ emptyList() }, scheduler = scheduler).fetchIcs().isEmpty())
        icsStatus = 200
        icsBody = "kein Kalender"
        assertTrue(service({ emptyList() }, scheduler = scheduler).fetchIcs().isEmpty())
        Unit
    }
}
