package space.einundzwanzig.meetup.data.calendar.time

import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.data.calendar.internal.two
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
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
     * `null`. Zone-less strings are read without the device zone, so DST gaps cannot shift them.
     * [zone] is used for parsing zoned strings and the one-time diagnostic line (logged through
     * the [AppLogger] facade, which is the same instance Hilt injects as `DiagnosticLog`).
     */
    fun portalStart(raw: String, zone: ZoneId = ZoneId.systemDefault()): Instant? {
        val s = raw.dartTrim()
        if (s.isEmpty()) return null
        val logThis = logged.compareAndSet(false, true)
        val hasZone = s.endsWith("Z") || ZONE_SUFFIX.containsMatchIn(s)
        // Zone-less strings are parsed in UTC so a wall-clock time inside a DST gap of the device
        // zone is not shifted before it is reinterpreted as UTC (Dart shifts it; deviation).
        val dt = DartDateTime.tryParse(s, if (hasZone) zone else ZoneOffset.UTC) ?: return null
        val result = if (hasZone) {
            dt.toInstant()
        } else {
            val f = dt.fields(ZoneOffset.UTC)
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
