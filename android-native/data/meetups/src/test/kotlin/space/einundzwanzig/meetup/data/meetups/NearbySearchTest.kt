package space.einundzwanzig.meetup.data.meetups

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.data.meetups.internal.MeetupServiceImpl
import space.einundzwanzig.meetup.data.meetups.internal.NearbyMeetupServiceImpl
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.data.meetups.nearby.DateMode
import space.einundzwanzig.meetup.data.meetups.nearby.MeetupEventInfo
import space.einundzwanzig.meetup.data.meetups.nearby.MeetupEventsSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Optional

class NearbySearchTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val now = LocalDateTime.of(2026, 7, 20, 12, 0).atZone(zone).toInstant()

    private fun m(id: String, city: String, lat: Double, lng: Double) =
        Meetup(id = id, city = city, country = "DE", telegramLink = "", lat = lat, lng = lng)

    private fun ev(title: String, at: LocalDateTime, location: String = "") =
        MeetupEventInfo(title = title, location = location, start = at.atZone(zone).toInstant())

    private val berlin = m("b", "Berlin", 52.5200, 13.4050)
    private val potsdam = m("p", "Potsdam", 52.3906, 13.0645)
    private val hamburg = m("h", "Hamburg", 53.5511, 9.9937)
    private val noCoords = m("x", "Nirgendwo", 0.0, 0.0)

    private fun search(
        events: List<MeetupEventInfo>,
        mode: DateMode,
        from: LocalDate? = null,
        to: LocalDate? = null,
        radius: Double = 50.0,
        meetups: List<Meetup> = listOf(hamburg, potsdam, berlin, noCoords),
    ) = NearbyMeetupServiceImpl.searchIn(meetups, events, 52.52, 13.405, radius, mode, from, to, now, zone)

    @Test
    fun `radius filter and distance sort`() {
        val r = search(emptyList(), DateMode.ANY)
        assertEquals(listOf("b", "p"), r.map { it.meetup.id })
        assertEquals(0.0, r[0].distanceKm!!, 0.0)
        assertEquals(listOf("b", "p", "h"), search(emptyList(), DateMode.ANY, radius = 1000.0).map { it.meetup.id })
    }

    @Test
    fun `ANY includes meetups without dates and dates after now minus 12h`() {
        val events = listOf(
            ev("Berlin Meetup", LocalDateTime.of(2026, 7, 20, 0, 30)), // 11.5 h ago
            ev("Berlin Meetup", LocalDateTime.of(2026, 7, 19, 23, 59)), // > 12 h ago
            ev("Berlin Meetup", LocalDateTime.of(2026, 7, 22, 19, 0)),
        )
        val r = search(events, DateMode.ANY)
        val b = r.first { it.meetup.id == "b" }
        assertEquals(2, b.allEvents.size)
        assertEquals(LocalDateTime.of(2026, 7, 20, 0, 30).atZone(zone).toInstant(), b.nextEvent?.start)
        assertNull(r.first { it.meetup.id == "p" }.nextEvent)
    }

    @Test
    fun `SINGLE_DAY excludes meetups without a matching date`() {
        val events = listOf(ev("Treffen", LocalDateTime.of(2026, 7, 22, 19, 0), "Kneipe, Potsdam"))
        val r = search(events, DateMode.SINGLE_DAY, LocalDate.of(2026, 7, 22))
        assertEquals(listOf("p"), r.map { it.meetup.id })
        assertTrue(search(events, DateMode.SINGLE_DAY, LocalDate.of(2026, 7, 23)).isEmpty())
        assertTrue(search(events, DateMode.SINGLE_DAY, null).isEmpty())
    }

    @Test
    fun `RANGE is inclusive of first and last second`() {
        val events = listOf(
            ev("Berlin A", LocalDateTime.of(2026, 7, 21, 0, 0, 0)),
            ev("Berlin B", LocalDateTime.of(2026, 7, 23, 23, 59, 59)),
            ev("Berlin C", LocalDateTime.of(2026, 7, 24, 0, 0, 0)),
            ev("Berlin D", LocalDateTime.of(2026, 7, 20, 23, 59, 59)),
        )
        val r = search(events, DateMode.RANGE, LocalDate.of(2026, 7, 21), LocalDate.of(2026, 7, 23))
        assertEquals(listOf("Berlin A", "Berlin B"), r.single().allEvents.map { it.title })
        assertTrue(search(events, DateMode.RANGE, LocalDate.of(2026, 7, 21), null).isEmpty())
    }

    @Test
    fun `dates are deduplicated by title start and location`() {
        val at = LocalDateTime.of(2026, 7, 22, 19, 0)
        val events = listOf(ev("Berlin", at, "X"), ev("Berlin", at, "X"), ev("Berlin", at, "Y"))
        assertEquals(2, search(events, DateMode.ANY).first { it.meetup.id == "b" }.allEvents.size)
    }

    @Test
    fun `location starting with a comma matches every meetup`() {
        val events = listOf(ev("Irgendwas", LocalDateTime.of(2026, 7, 22, 19, 0), ", X"))
        val r = search(events, DateMode.SINGLE_DAY, LocalDate.of(2026, 7, 22))
        assertEquals(listOf("b", "p"), r.map { it.meetup.id })
    }

    @Test
    fun `city contains the first location part`() {
        val events = listOf(ev("Stammtisch", LocalDateTime.of(2026, 7, 22, 19, 0), "berlin, Café"))
        assertEquals(listOf("b"), search(events, DateMode.SINGLE_DAY, LocalDate.of(2026, 7, 22)).map { it.meetup.id })
    }

    @Test
    fun `preload dedupes by city, falls back to the bundled list and caches`() = runTest {
        FakeServer().use { server ->
            var body = """[{"id":1,"city":"Würzburg","latitude":49.79,"longitude":9.95},
                {"id":2,"city":"würzburg ","latitude":49.8,"longitude":9.9},{"id":3,"city":"","latitude":1,"longitude":1},
                {"id":4,"city":"","latitude":1,"longitude":1}]"""
            server.respond = { _, _ -> json(body) }
            var eventLoads = 0
            val event = MeetupEventInfo(title = "Würzburg Stammtisch", location = "Würzburg", start = Instant.parse("2030-01-01T18:00:00Z"))
            val source = MeetupEventsSource { eventLoads++; listOf(event) }
            val nearby = NearbyMeetupServiceImpl(MeetupServiceImpl(server.client, testLog(), testLogos()), Optional.of(source), FakeTime(), testLog())
            val r = nearby.search(49.79, 9.95, 100.0, DateMode.ANY)
            assertEquals(listOf("1"), r.map { it.meetup.id })
            assertEquals(1, eventLoads)
            nearby.search(49.79, 9.95, 100.0, DateMode.ANY)
            assertEquals(1, eventLoads) // cached
            assertEquals(listOf("1", "3", "4"), NearbyMeetupServiceImpl.dedupeByCity(MeetupServiceImpl(server.client, testLog(), testLogos()).fetchMeetups()).map { it.id })

            body = "[]"
            nearby.preload(forceReload = true)
            assertEquals(listOf("m_b"), nearby.search(52.52, 13.405, 10.0, DateMode.ANY).map { it.meetup.id })
        }
    }

    @Test
    fun `offline fallback and empty dates are retried on the next preload`() = runTest {
        FakeServer().use { server ->
            var online = false
            server.respond = { _, _ ->
                if (online) json("""[{"id":1,"city":"Würzburg","latitude":49.79,"longitude":9.95}]""") else status(503, "")
            }
            var eventLoads = 0
            val event = MeetupEventInfo(title = "Würzburg Stammtisch", location = "Würzburg", start = Instant.parse("2030-01-01T18:00:00Z"))
            val source = MeetupEventsSource { eventLoads++; if (online) listOf(event) else emptyList() }
            val nearby = NearbyMeetupServiceImpl(MeetupServiceImpl(server.client, testLog(), testLogos()), Optional.of(source), FakeTime(), testLog())

            // Offline: the bundled list (Berlin is m_b) and no dates.
            assertEquals(listOf("m_b"), nearby.search(52.52, 13.405, 10.0, DateMode.ANY).map { it.meetup.id })
            assertEquals(1, eventLoads)

            // Connectivity returns: the next search retries both parts.
            online = true
            val r = nearby.search(49.79, 9.95, 100.0, DateMode.ANY)
            assertEquals(listOf("1"), r.map { it.meetup.id })
            assertEquals(event, r.single().nextEvent)
            assertEquals(2, eventLoads)

            // Both answers are real now: cached for the process.
            val requests = server.seen.size
            nearby.search(49.79, 9.95, 100.0, DateMode.ANY)
            assertEquals(2, eventLoads)
            assertEquals(requests, server.seen.size)
        }
    }
}
