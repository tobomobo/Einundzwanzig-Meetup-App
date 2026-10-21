package space.einundzwanzig.meetup.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate
import space.einundzwanzig.meetup.data.calendar.recurrence.RecurrenceExpander
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Spec 10 §10 recurrence vectors (zone UTC, now = 2026-01-01T00:00). */
class RecurrenceExpanderTest {

    private val utc = ZoneOffset.UTC
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun base(y: Int, m: Int, d: Int, h: Int, min: Int, zone: ZoneId = utc) = MeetupDate(
        title = "Meetup", description = "", location = "", url = "",
        start = LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant(),
        meetupId = "7", meetupPortalLink = "https://p", portalEventId = 3,
    )

    private fun days(list: List<MeetupDate>, zone: ZoneId = utc) = list.map { it.start.atZone(zone).toLocalDate().toString().substring(5) }

    private fun expand(b: MeetupDate, rule: String?, zone: ZoneId = utc) = RecurrenceExpander.expand(b, rule, now, zone)

    @Test
    fun weekly() {
        assertEquals(listOf("01-06", "01-13", "01-20", "01-27"), days(expand(base(2026, 1, 6, 19, 0), "FREQ=WEEKLY;COUNT=4")))
    }

    @Test
    fun `weekly with interval and byday`() {
        assertEquals(
            listOf("01-05", "01-08", "01-19", "01-22", "02-02"),
            days(expand(base(2026, 1, 5, 18, 30), "RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TH;COUNT=5")),
        )
    }

    @Test
    fun `monthly nth and last weekday`() {
        assertEquals(listOf("01-15", "02-19", "03-19"), days(expand(base(2026, 1, 15, 19, 0), "FREQ=MONTHLY;BYDAY=3TH;COUNT=3")))
        assertEquals(listOf("01-30", "02-27", "03-27"), days(expand(base(2026, 1, 30, 19, 0), "FREQ=MONTHLY;BYDAY=-1FR;COUNT=3")))
    }

    @Test
    fun `monthly same day skips short months`() {
        assertEquals(listOf("01-31", "03-31", "05-31"), days(expand(base(2026, 1, 31, 19, 0), "FREQ=MONTHLY;COUNT=3")))
    }

    @Test
    fun `count 1 quirk and until`() {
        assertEquals(listOf("01-06", "01-13"), days(expand(base(2026, 1, 6, 19, 0), "FREQ=WEEKLY;COUNT=1")))
        assertEquals(listOf("01-06", "01-13", "01-20"), days(expand(base(2026, 1, 6, 19, 0), "FREQ=WEEKLY;UNTIL=20260120T000000Z")))
    }

    @Test
    fun `unknown, lowercase and empty rules keep the base`() {
        val b = base(2026, 1, 6, 19, 0)
        assertEquals(listOf(b), expand(b, "FREQ=DAILY"))
        assertEquals(listOf(b), expand(b, "FREQ=weekly"))
        assertEquals(listOf(b), expand(b, ""))
        assertEquals(listOf(b), expand(b, null))
        assertEquals(listOf("01-06", "01-13"), days(expand(b, "freq=WEEKLY;interval=0;count=2")))
    }

    @Test
    fun `occurrences clone every field and respect the horizon and cap`() {
        val list = expand(base(2026, 1, 6, 19, 0), "FREQ=WEEKLY")
        assertEquals(52, list.size) // base + 51 Tuesdays up to now + 366 days (2026-12-29)
        list.forEach {
            assertEquals("7", it.meetupId)
            assertEquals(3, it.portalEventId)
            assertEquals("https://p", it.meetupPortalLink)
        }
        val old = RecurrenceExpander.expand(base(2020, 1, 6, 19, 0), "FREQ=WEEKLY", now, utc)
        assertEquals(RecurrenceExpander.MAX_OCCURRENCES, old.size)
    }

    @Test
    fun `weekly keeps the wall-clock time across DST`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val list = RecurrenceExpander.expand(base(2026, 3, 24, 19, 0, berlin), "FREQ=WEEKLY;COUNT=2", now, berlin)
        assertEquals(listOf(19, 19), list.map { it.start.atZone(berlin).hour })
    }
}
