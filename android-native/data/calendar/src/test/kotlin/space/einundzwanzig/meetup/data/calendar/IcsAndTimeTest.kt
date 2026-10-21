package space.einundzwanzig.meetup.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.data.calendar.ics.IcsComponent
import space.einundzwanzig.meetup.data.calendar.ics.IcsDateTime
import space.einundzwanzig.meetup.data.calendar.ics.IcsFormatException
import space.einundzwanzig.meetup.data.calendar.ics.IcsMeetupDates
import space.einundzwanzig.meetup.data.calendar.ics.IcsParser
import space.einundzwanzig.meetup.data.calendar.time.PortalStart
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Spec 10 §8.1/§8.2 vectors (device zone Europe/Berlin). */
class IcsAndTimeTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun localHm(i: Instant?): String = i!!.atZone(berlin).let { "%02d:%02d".format(it.hour, it.minute) }

    @Test
    fun `portalStart vectors`() {
        assertEquals("18:00", localHm(PortalStart.portalStart("2026-07-21 16:00", berlin)))
        assertEquals("18:00", localHm(PortalStart.portalStart("2026-07-21T16:00:00Z", berlin)))
        assertEquals("16:00", localHm(PortalStart.portalStart("2026-07-21T16:00:00+02:00", berlin)))
        assertEquals("19:00", localHm(PortalStart.portalStart("2026-01-15 18:00:00", berlin)))
        assertEquals("18:00", localHm(PortalStart.portalStart("  2026-07-21T16:00:00.789  ", berlin)))
        assertEquals(Instant.parse("2026-07-21T16:00:00Z"), PortalStart.portalStart("2026-07-21T16:00:00.789", berlin))
        assertNull(PortalStart.portalStart("", berlin))
        assertNull(PortalStart.portalStart("garbage", berlin))
    }

    private fun comp(dtstart: String?) = IcsComponent("VEVENT", buildMap { if (dtstart != null) put("dtstart", IcsDateTime(dtstart)) })

    @Test
    fun `ICS DTSTART vectors`() {
        assertEquals("18:30", localHm(IcsMeetupDates.fromComponent(comp("20260721T163000Z"), now, berlin).start))
        assertEquals("18:30", localHm(IcsMeetupDates.fromComponent(comp("20260721T183000"), now, berlin).start))
        assertEquals("19:00", localHm(IcsMeetupDates.fromComponent(comp("20260721"), now, berlin).start))
        assertEquals(now.plus(Duration.ofDays(365)), IcsMeetupDates.fromComponent(comp(null), now, berlin).start)
        assertEquals(now.plus(Duration.ofDays(365)), IcsMeetupDates.fromComponent(comp("2026"), now, berlin).start)
    }

    @Test
    fun `ICS field fallbacks and description cleaning`() {
        val d = IcsMeetupDates.fromComponent(
            IcsComponent("VEVENT", mapOf("description" to "Zeile 1\\nZeile 2 \\; ok\\\\")),
            now,
            berlin,
        )
        assertEquals("Meetup", d.title)
        assertEquals("Ort unbekannt", d.location)
        assertEquals("Zeile 1\nZeile 2 ; ok", d.description)
        assertEquals("", d.url)
    }

    @Test
    fun `ICS parser - envelope, folding, escaping, continuation, nested components`() {
        val ics = listOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//portal//DE",
            "X-WR-CALNAME:Einundzwanzig",
            "BEGIN:VEVENT",
            "SUMMARY:Einundzwanzig Berlin",
            "DESCRIPTION:Erste Zeile",
            " fortgesetzt",
            "Fortsetzung ohne Doppelpunkt",
            "LOCATION:Bar\\, Berlin",
            "DTSTART;TZID=Europe/Berlin:20260721T183000",
            "RRULE:FREQ=WEEKLY;COUNT=2",
            "BEGIN:VALARM",
            "ACTION:DISPLAY",
            "END:VALARM",
            "END:VEVENT",
            "END:VCALENDAR",
            "",
            "",
        ).joinToString("\r\n")
        val result = IcsParser.parse(ics)
        assertEquals("2.0", result.head["version"])
        assertEquals(listOf("VALARM", "VEVENT"), result.components.map { it.type })
        val ev = result.components[1]
        assertEquals("Einundzwanzig Berlin", ev.string("summary"))
        assertEquals("Erste ZeilefortgesetztFortsetzung ohne Doppelpunkt", ev.string("description"))
        assertEquals("Bar, Berlin", ev.string("location"))
        assertEquals(IcsDateTime("20260721T183000", "Europe/Berlin"), ev.date("dtstart"))
        assertEquals("FREQ=WEEKLY;COUNT=2", ev.string("rrule"))

        val dates = IcsMeetupDates.fromIcs(ics, now, berlin)
        assertEquals(2, dates.size)
        assertEquals("18:30", localHm(dates[0].start))
        assertEquals(Duration.ofDays(7), Duration.between(dates[0].start, dates[1].start))
    }

    @Test
    fun `ICS parser rejects invalid envelopes`() {
        fun fails(s: String) = assertTrue(runCatching { IcsParser.parse(s) }.exceptionOrNull() is IcsFormatException)
        fails("")
        fails("BEGIN:VEVENT\nEND:VCALENDAR")
        fails("BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:x")
        fails("BEGIN:VCALENDAR\nPRODID:x\nEND:VCALENDAR")
        fails("BEGIN:VCALENDAR\nVERSION:2.0\nEND:VCALENDAR")
        fails("BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:x\nBEGIN:VEVENT\nGEO:a;b\nEND:VEVENT\nEND:VCALENDAR")
        assertEquals(emptyList<Any>(), IcsMeetupDates.fromIcs("garbage", now, berlin))
    }

    @Test
    fun `ICS dates are sorted and not past-filtered`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:x
            BEGIN:VEVENT
            SUMMARY:B
            DTSTART:20260301T180000Z
            END:VEVENT
            BEGIN:VEVENT
            SUMMARY:A
            DTSTART:20200101T180000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        assertEquals(listOf("A", "B"), IcsMeetupDates.fromIcs(ics, now, berlin).map { it.title })
    }
}
