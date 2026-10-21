package space.einundzwanzig.meetup.data.meetups.portal

import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.log.AppLogger
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean

/** Time conversions of the organizer endpoints (spec 09 §8.12). */
object PortalTime {

    private val loggedUtcFormat = AtomicBoolean(false)

    /**
     * Dart `MeetupCalendarService.portalStartUtc`: the organizer endpoint `/api/my-meetup-events`
     * returns the raw DB time in UTC WITHOUT a zone (`"2026-07-21 16:00"` = 16:00 UTC = 18:00 CEST).
     *
     * Trimmed; empty or unparsable (Dart `DateTime.tryParse` grammar) → `null`. With a zone
     * designator the instant is used as is; otherwise the wall-clock fields are read as UTC
     * (milliseconds dropped). The result is in [zone]. The first raw value per process is logged.
     */
    fun portalStartUtc(raw: String, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime? {
        val s = raw.dartTrim()
        if (s.isEmpty()) return null
        if (loggedUtcFormat.compareAndSet(false, true)) AppLogger.diag("Portal", "my-meetup-events Rohformat: \"$s\"")
        val dt = DartDateTime.tryParse(s, zone) ?: return null
        if (dt.isUtc) return dt.toInstant().atZone(zone)
        val f = dt.fields(zone)
        return DartDateTime.utc(f.year, f.monthValue, f.dayOfMonth, f.hour, f.minute, f.second)
            .toInstant().atZone(zone)
    }

    /**
     * The `start`/`recurrence_end_date` value sent to the portal: Dart `toUtc().toIso8601String()`,
     * i.e. `"YYYY-MM-DDTHH:mm:ss.SSSZ"` (microseconds appended only when non-zero, like Dart).
     *
     * Native fix of the Flutter editor round-trip bug: prefill the editor with [portalStartUtc]
     * and send `toPortalIso(start)`, so saving without changes keeps the time.
     */
    fun toPortalIso(instant: Instant): String = DartDateTime.fromInstant(instant, isUtc = true).toIso8601String()

    /** [toPortalIso] for a zoned value. */
    fun toPortalIso(time: ZonedDateTime): String = toPortalIso(time.toInstant())
}
