package space.einundzwanzig.meetup.data.calendar

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.data.calendar.merge.CalendarMerger
import space.einundzwanzig.meetup.data.calendar.model.CalendarItem
import space.einundzwanzig.meetup.data.calendar.model.CalendarItemType
import space.einundzwanzig.meetup.data.calendar.model.CalendarTypeFilter
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class CalendarMergerTest {

    private val utc = ZoneOffset.UTC
    private val t = LocalDateTime.of(2026, 3, 5, 9, 7).toInstant(utc)

    private fun item(title: String, start: Instant = t, meetup: Boolean = false, course: Boolean = false, location: String = "") =
        CalendarItem(title = title, description = "", location = location, start = start, isMeetup = meetup, isCourse = course)

    @Test
    fun `dedup key vector`() {
        assertEquals("20263597|einundzwanzi", CalendarMerger.dedupKey(item("Einundzwanzig Würzburg Stammtisch"), utc))
        assertEquals("20263597|würzburg", CalendarMerger.dedupKey(item("Würzburg!"), utc))
    }

    @Test
    fun `course beats meetup beats nostr, first stays otherwise`() {
        val nostr = item("Einundzwanzig Würzburg Stammtisch")
        val course = item("Einundzwanzig Würzburg Kurs", course = true)
        assertEquals(listOf(course), CalendarMerger.merge(listOf(nostr, course), utc))
        val meetup = item("Einundzwanzig Würzburg", meetup = true)
        assertEquals(listOf(meetup), CalendarMerger.merge(listOf(nostr, meetup), utc))
        val nostr2 = item("Einundzwanzig Würzburg 2")
        assertEquals(listOf(nostr), CalendarMerger.merge(listOf(nostr, nostr2), utc))
        val later = item("Einundzwanzig Würzburg", start = t.plusSeconds(60))
        assertEquals(listOf(nostr, later), CalendarMerger.merge(listOf(later, nostr), utc))
    }

    @Test
    fun `type and location filter`() {
        val m = item("M", meetup = true, location = "Berlin")
        val n = item("N", location = "")
        val c = item("C", course = true, location = "Wien · Österreich")
        val all = listOf(m, n, c)
        assertEquals(all, CalendarMerger.filter(all, CalendarTypeFilter.ALL, ""))
        assertEquals(listOf(m), CalendarMerger.filter(all, CalendarTypeFilter.MEETUPS, ""))
        assertEquals(listOf(n), CalendarMerger.filter(all, CalendarTypeFilter.EXTERNAL, ""))
        assertEquals(listOf(c), CalendarMerger.filter(all, CalendarTypeFilter.COURSES, ""))
        assertEquals(listOf(n, c), CalendarMerger.filter(all, CalendarTypeFilter.ALL, "  ÖSTERREICH "))
        assertTrue(CalendarMerger.isFilterActive(CalendarTypeFilter.ALL, " x"))
        assertFalse(CalendarMerger.isFilterActive(CalendarTypeFilter.ALL, "  "))
        assertEquals(CalendarItemType.COURSE, c.type)
    }

    @Test
    fun `course items from portal details`() {
        val details = DartJson.decode(
            """
            [{"id":1,"name":"Bitcoin Basics","description":"D","portalLink":"https://p/c/1","events":[
              {"id":10,"from":"2026-03-10 17:00","to":"2026-03-10 19:00","venue":{"name":"Hub","city":{"name":"Wien"}},"link":"https://l"},
              {"id":10,"from":"2026-03-10 17:00"},
              {"from":"2026-03-11T17:00:00Z","venue":{"name":"","city":{"name":"Graz"}}},
              {"start":"2026-03-12 17:00","location":"Online"},
              {"id":11,"from":"2026-01-01 10:00"},
              {"id":12,"from":"bad"},
              "not a map"
            ]},
            {"title":"Zweiter","events":[{"id":10,"from":"2026-03-10 17:00"},{"from":"2026-03-13 17:00"}]},
            {"events":[{"from":"2026-03-14 17:00","venue":{"name":"Only"}}]}]
            """.trimIndent(),
        ).let { (it as kotlinx.serialization.json.JsonArray).map { e -> e as JsonObject } }
        val items = CalendarMerger.courseItems(details, Instant.parse("2026-03-01T00:00:00Z"), utc)
        assertEquals(listOf("Hub · Wien", "Graz", "Online", "", "Only"), items.map { it.location })
        assertEquals(listOf("Bitcoin Basics", "Bitcoin Basics", "Bitcoin Basics", "Zweiter", "Kurs"), items.map { it.title })
        val first = items.first()
        assertEquals(Instant.parse("2026-03-10T17:00:00Z"), first.start)
        assertEquals(Instant.parse("2026-03-10T19:00:00Z"), first.end)
        assertEquals("https://l", first.url)
        assertEquals("https://p/c/1", items[1].url)
        assertNull(items[1].end)
        assertTrue(items.all { it.isCourse && !it.isMeetup && !it.allDay })
    }

    @Test
    fun `helpers - by day, upcoming, year counts, month blanks`() {
        val a = item("A", start = Instant.parse("2026-03-05T23:30:00Z"))
        val b = item("B", start = Instant.parse("2026-04-01T10:00:00Z"))
        assertEquals(setOf(LocalDate.of(2026, 3, 5), LocalDate.of(2026, 4, 1)), CalendarMerger.byDay(listOf(a, b), utc).keys)
        assertEquals(listOf(b), CalendarMerger.upcoming(listOf(a, b), Instant.parse("2026-03-07T00:00:00Z")))
        assertEquals(mapOf(3 to 1, 4 to 1), CalendarMerger.countsPerMonth(listOf(a, b), 2026, utc))
        assertEquals(6, CalendarMerger.leadingBlanks(LocalDate.of(2026, 3, 1))) // Sunday
        assertEquals(0, CalendarMerger.leadingBlanks(LocalDate.of(2026, 6, 1))) // Monday
        val meetup = CalendarItem.fromMeetup(MeetupDate("M", "", "", t, url = "https://x"))
        assertEquals("", meetup.url)
        assertTrue(meetup.isMeetup)
    }
}
