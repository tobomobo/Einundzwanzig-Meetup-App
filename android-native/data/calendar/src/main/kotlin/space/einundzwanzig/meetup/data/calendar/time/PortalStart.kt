package space.einundzwanzig.meetup.data.calendar.time

import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.data.calendar.internal.two
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Start-time parsing of the public portal endpoints (Dart `MeetupCalendarService.portalStart`,
 * spec 10 §8.1). The organizer variant `portalStartUtc` lives in `:data:meetups` (`PortalTime`).
 */
object PortalStart {

    private val ZONE_SUFFIX = Regex("[+-]\\d{2}:?\\d{2}$")
    private val logged = AtomicBoolean(false)

    /**
     * Parses a portal `start` (Dart `DateTime.parse` grammar). A string WITHOUT zone designator
     * (`Z` or `±HH[:]MM` at the end) is UTC — the live portal sends UTC without marker — and its
     * milliseconds are dropped; with a zone the instant is used as is. Empty or unparsable →
     * `null`. [zone] only matters for the parse of zone-less strings (wall-clock fields) and the
     * one-time diagnostic line.
     */
    fun portalStart(raw: String, zone: ZoneId = ZoneId.systemDefault()): Instant? {
        val s = raw.dartTrim()
        if (s.isEmpty()) return null
        val logThis = logged.compareAndSet(false, true)
        val hasZone = s.endsWith("Z") || ZONE_SUFFIX.containsMatchIn(s)
        val dt = DartDateTime.tryParse(s, zone) ?: return null
        val result = if (hasZone) {
            dt.toInstant()
        } else {
            val f = dt.fields(zone)
            DartDateTime.utc(f.year, f.monthValue, f.dayOfMonth, f.hour, f.minute, f.second).toInstant()
        }
        if (logThis) {
            val local = result.atZone(zone)
            AppLogger.diag(
                "Portal",
                "start-Rohformat \"$s\" (als UTC gedeutet) -> angezeigt ${two(local.hour)}:${two(local.minute)}",
            )
        }
        return result
    }
}
