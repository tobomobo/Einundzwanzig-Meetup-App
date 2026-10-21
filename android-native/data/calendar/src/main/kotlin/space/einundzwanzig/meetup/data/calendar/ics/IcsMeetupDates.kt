package space.einundzwanzig.meetup.data.calendar.ics

import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate
import space.einundzwanzig.meetup.data.calendar.recurrence.RecurrenceExpander
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Maps the portal iCal feed to [MeetupDate]s (Dart `CalendarEvent.fromMap` + `fetchMeetups`, spec 10 §8.2). */
object IcsMeetupDates {

    private val NON_DIGITS = Regex("[^0-9]")

    /**
     * Dart `CalendarEvent.fromMap`. `DTSTART` (TZID ignored): ending in `Z`/`z` → UTC, else
     * device-local wall time in [zone]; a DATE value (8 digits) gets 19:00; missing/short →
     * [now] + 365 days. Description: literal `\n` → newline, then every backslash removed.
     * Title fallback `Meetup`, location fallback `Ort unbekannt`, URL `""`.
     */
    fun fromComponent(component: IcsComponent, now: Instant, zone: ZoneId = ZoneId.systemDefault()): MeetupDate {
        var start = now.plus(Duration.ofDays(365))
        val raw = component.date("dtstart")?.dt ?: component.string("dtstart") ?: ""
        if (raw.isNotEmpty()) {
            runCatching {
                val trimmed = raw.dartTrim()
                val isUtc = trimmed.endsWith("Z") || trimmed.endsWith("z")
                val s = raw.replace(NON_DIGITS, "")
                if (s.length >= 8) {
                    val y = s.substring(0, 4).toInt()
                    val m = s.substring(4, 6).toInt()
                    val d = s.substring(6, 8).toInt()
                    var h = 19
                    var min = 0
                    if (s.length >= 12) {
                        h = s.substring(8, 10).toInt()
                        min = s.substring(10, 12).toInt()
                    }
                    start = if (isUtc) {
                        DartDateTime.utc(y, m, d, h, min).toInstant()
                    } else {
                        DartDateTime.local(y, m, d, h, min, zone = zone).toInstant()
                    }
                }
            }
        }
        val rawDesc = component.string("description") ?: ""
        val cleanDesc = rawDesc.replace("\\n", "\n").replace("\\", "")
        return MeetupDate(
            title = component.string("summary") ?: MeetupDate.DEFAULT_TITLE,
            description = cleanDesc,
            location = component.string("location") ?: MeetupDate.UNKNOWN_LOCATION,
            start = start,
            url = "",
        )
    }

    /**
     * Dart `fetchMeetups` after the download: parse, map every `VEVENT`, expand its `RRULE`
     * ([RecurrenceExpander]) and sort by start (stable). No past filter. A document the parser
     * rejects gives `[]`.
     */
    fun fromIcs(ics: String, now: Instant, zone: ZoneId = ZoneId.systemDefault()): List<MeetupDate> {
        val parsed = try {
            IcsParser.parse(ics)
        } catch (_: IcsFormatException) {
            return emptyList()
        }
        val out = ArrayList<MeetupDate>()
        for (c in parsed.components) {
            if (c.type != "VEVENT") continue
            val base = fromComponent(c, now, zone)
            out += RecurrenceExpander.expand(base, c.string("rrule"), now, zone)
        }
        return out.sortedBy { it.start }
    }
}
