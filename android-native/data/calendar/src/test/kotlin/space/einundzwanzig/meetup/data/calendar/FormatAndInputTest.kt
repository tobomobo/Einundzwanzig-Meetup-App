package space.einundzwanzig.meetup.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.data.calendar.format.CalendarFormat
import space.einundzwanzig.meetup.data.calendar.format.IssuerInput
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate
import space.einundzwanzig.meetup.data.calendar.portal.PortalRsvp
import space.einundzwanzig.meetup.data.calendar.widget.WidgetFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Details-sheet texts of spec 10 §2.2 (when line, share text, links), issuer input, portal RSVP
 * map and widget formatting vectors (spec 10 §10, fixture `widget_formatting`). The assertions of
 * `event_details_sheet_test.dart` are UI-only and belong to Compose UI tests in `:feature:events`.
 */
class FormatAndInputTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val start = Instant.parse("2026-03-05T08:07:00Z") // 09:07 CET

    @Test
    fun `details sheet texts`() {
        val `when` = CalendarFormat.meetupWhen(start, berlin)
        assertEquals("05.03.2026 · 09:07", `when`)
        assertEquals("T · $`when`\nOrt\nhttps://x", CalendarFormat.shareText("T", `when`, "Ort", "https://x"))
        assertEquals("T · $`when`", CalendarFormat.shareText("T", `when`, "", ""))
        assertEquals("5.3.2026 · 09:07", CalendarFormat.myMeetupWhen(start, berlin))
        assertEquals("5.3.2026", CalendarFormat.shortDate(LocalDate.of(2026, 3, 5)))
        assertEquals("05.03.2026  09:07", CalendarFormat.editorDate(start, false, berlin))
        assertEquals("05.03.2026", CalendarFormat.editorDate(start, true, berlin))
        assertEquals("48.20820, -0.00000", CalendarFormat.coordinates(48.2082, -0.000001))
    }

    @Test
    fun `links are distinct, in order and stop at delimiters`() {
        assertEquals(
            listOf("https://a.b/c", "http://x.y", "https://z"),
            CalendarFormat.links("Siehe (https://a.b/c) und http://x.y, https://a.b/c", "[https://z]"),
        )
        assertEquals(emptyList<String>(), CalendarFormat.links("ftp://nope", ""))
        // Dart `\s` (JS semantics) also stops at Unicode spaces and the BOM.
        assertEquals(listOf("https://a.b"), CalendarFormat.links("https://a.b\u00A0mehr", ""))
        assertEquals(listOf("https://c.d", "https://e.f"), CalendarFormat.links("https://c.d\u3000x https://e.f\uFEFFy", ""))
    }

    @Test
    fun `my events helpers`() {
        val now = Instant.parse("2026-03-05T12:00:00Z")
        assertEquals(CalendarFormat.Proximity.NOW, CalendarFormat.proximity(now.plusSeconds(23 * 3600), now))
        assertEquals(CalendarFormat.Proximity.SOON, CalendarFormat.proximity(now.plusSeconds(3 * 86400 + 10), now))
        assertEquals(CalendarFormat.Proximity.LATER, CalendarFormat.proximity(now.plusSeconds(4 * 86400), now))
        assertEquals("99", CalendarFormat.unreadBadge(99))
        assertEquals("99+", CalendarFormat.unreadBadge(100))
        assertEquals("npub1abcde…uvwxyz", CalendarFormat.shortNpub("npub1abcdefghijklmnopqrstuvwxyz"))
        assertEquals("npub1short", CalendarFormat.shortNpub("npub1short"))
        assertEquals("?", CalendarFormat.initial(""))
        assertEquals("Ä", CalendarFormat.initial("änne"))
    }

    @Test
    fun `issuer editor rows`() {
        val hex = "ab".repeat(32)
        val npub = Nip19.encodeNpub(hex)
        assertEquals(hex, IssuerInput.toHex("  $npub "))
        assertEquals(hex, IssuerInput.toHex(hex.uppercase()))
        assertNull(IssuerInput.toHex("npub1garbage"))
        assertNull(IssuerInput.toHex("zz".repeat(32)))
        assertNull(IssuerInput.toHex(""))
        assertFalse(IssuerInput.isRowInvalid("   "))
        assertTrue(IssuerInput.isRowInvalid("x"))
        assertEquals(listOf(hex), IssuerInput.issuersOf(listOf(npub, "", hex, "bad")))
    }

    @Test
    fun `issuer edit sheet`() {
        val creator = "cd".repeat(32)
        val hex = "ab".repeat(32)
        assertEquals(IssuerInput.AddResult.Added(hex), IssuerInput.addHelper(Nip19.encodeNpub(hex), creator, emptyList()))
        assertEquals(IssuerInput.AddResult.Duplicate, IssuerInput.addHelper(hex.uppercase(), creator, listOf(hex)))
        assertEquals(IssuerInput.AddResult.Duplicate, IssuerInput.addHelper(creator.uppercase(), creator, emptyList()))
        assertEquals(IssuerInput.AddResult.Invalid, IssuerInput.addHelper("npub1xyz", creator, emptyList()))
        assertEquals(IssuerInput.AddResult.Invalid, IssuerInput.addHelper("g".repeat(64), creator, emptyList()))
        assertEquals(IssuerInput.AddResult.Invalid, IssuerInput.addHelper("ab", creator, emptyList()))
        assertEquals(listOf(hex), IssuerInput.initialHelpers(listOf(hex.uppercase(), creator), creator.uppercase()))
    }

    @Test
    fun `portal RSVP map readers`() {
        assertEquals(4, PortalRsvp.count(jsonObjectOf("count" to 4, "total" to 9)))
        assertEquals(-1, PortalRsvp.count(jsonObjectOf("attendees" to "4")))
        assertEquals(-1, PortalRsvp.count(null))
        assertEquals(2, PortalRsvp.might(jsonObjectOf("might_attendees" to 2)))
        assertEquals("maybe", PortalRsvp.status(jsonObjectOf("status" to "maybe")))
        assertEquals("", PortalRsvp.status(jsonObjectOf("status" to null)))
        assertEquals("", PortalRsvp.status(null))
        assertEquals("1", PortalRsvp.status(jsonObjectOf("status" to 1)))
        assertEquals("true", PortalRsvp.status(jsonObjectOf("status" to true)))
        val initial = PortalRsvp.initial(MeetupDate("T", "", "", Instant.EPOCH, "", attendees = 3))
        assertEquals(3, PortalRsvp.count(initial))
        assertEquals(0, PortalRsvp.might(initial))
        val merged = PortalRsvp.withStatus(PortalRsvp.merge(initial, jsonObjectOf("attendees" to 5)), "attending")
        assertEquals(5, PortalRsvp.count(merged))
        assertEquals("attending", PortalRsvp.status(merged))
        assertEquals("none", PortalRsvp.toggleTarget("attending", "attending"))
        assertEquals("maybe", PortalRsvp.toggleTarget("attending", "maybe"))
    }

    @Test
    fun `widget formatting vectors`() {
        val ints = listOf(0L to "0", 7L to "7", 999L to "999", 1000L to "1.000", 95000L to "95.000", 921345L to "921.345", 19937512L to "19.937.512", 1234567L to "1.234.567")
        for ((v, s) in ints) assertEquals(s, WidgetFormat.groupInt(v))
        assertEquals("Heute", WidgetFormat.germanCountdown(-1))
        assertEquals("Heute", WidgetFormat.germanCountdown(0))
        assertEquals("Morgen", WidgetFormat.germanCountdown(1))
        assertEquals("in 2 Tagen", WidgetFormat.germanCountdown(2))
        val today = LocalDate.of(2026, 3, 5)
        assertEquals(0, WidgetFormat.daysUntil(Instant.parse("2026-03-05T22:59:00Z"), today, berlin))
        assertEquals(1, WidgetFormat.daysUntil(Instant.parse("2026-03-05T23:00:00Z"), today, berlin))
        assertEquals(5, WidgetFormat.daysUntil(Instant.parse("2026-03-10T08:00:00Z"), today, berlin))
        assertEquals(WidgetFormat.Countdown.InDays(5), WidgetFormat.countdown(5))
    }
}
