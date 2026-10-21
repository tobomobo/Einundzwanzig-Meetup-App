package space.einundzwanzig.meetup.data.calendar.merge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.data.calendar.internal.dartLower
import space.einundzwanzig.meetup.data.calendar.internal.dartToString
import space.einundzwanzig.meetup.data.calendar.internal.isDartNull
import space.einundzwanzig.meetup.data.calendar.model.CalendarItem
import space.einundzwanzig.meetup.data.calendar.model.CalendarTypeFilter
import space.einundzwanzig.meetup.data.calendar.time.PortalStart
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Event-calendar merge, de-duplication and filtering (Dart `EventCalendarScreen._load`, spec 10 §8.6). */
object CalendarMerger {

    private val NORM = Regex("[^a-z0-9äöü]")

    /** Course fallback name (Dart literal). */
    const val DEFAULT_COURSE_NAME: String = "Kurs"

    /**
     * Course rows from portal course details (`GET /courses/{id}`): for every `events[]` map with a
     * parseable `from ?? start` ([PortalStart]) not before [now] − 6 h. Duplicates (key
     * `e<id>`, or `<name>@<start ISO>` without id) are skipped across ALL courses. Location =
     * `venue.name` (+ ` · ` + `venue.city.name`), else `location`. URL = `link ?? portalLink`.
     *
     * Deviation: `to` is parsed with [PortalStart] (zone-less = UTC) instead of Dart's local
     * `DateTime.tryParse` (spec 10 §8.6 / §14 item 3).
     */
    fun courseItems(details: List<JsonObject>, now: Instant, zone: ZoneId = ZoneId.systemDefault()): List<CalendarItem> {
        val cutoff = now.minus(Duration.ofHours(6))
        val seen = HashSet<String>()
        val out = ArrayList<CalendarItem>()
        for (c in details) {
            val cname = c.str("name") ?: c.str("title") ?: DEFAULT_COURSE_NAME
            val cdesc = c.str("description") ?: ""
            val cportal = c.str("portalLink") ?: ""
            val events = c["events"] as? JsonArray ?: continue
            for (evEl in events) {
                val ev = evEl as? JsonObject ?: continue
                val start = PortalStart.portalStart(ev.str("from") ?: ev.str("start") ?: "", zone) ?: continue
                if (start.isBefore(cutoff)) continue
                val id = ev["id"].takeUnless { it.isDartNull }
                val key = if (id != null) {
                    "e${id.dartToString()}"
                } else {
                    "$cname@${DartDateTime.fromInstant(start).toIso8601String(zone)}"
                }
                if (!seen.add(key)) continue
                val venue = ev["venue"] as? JsonObject
                val venueName = venue?.str("name") ?: ""
                val city = ((venue?.get("city") as? JsonObject)?.str("name")) ?: ""
                val location = when {
                    venueName.isNotEmpty() -> if (city.isNotEmpty()) "$venueName · $city" else venueName
                    city.isNotEmpty() -> city
                    else -> ev.str("location") ?: ""
                }
                out += CalendarItem(
                    title = cname,
                    description = cdesc,
                    location = location,
                    start = start,
                    end = ev.str("to")?.let { PortalStart.portalStart(it, zone) },
                    allDay = false,
                    isMeetup = false,
                    isCourse = true,
                    url = ev.str("link") ?: cportal,
                )
            }
        }
        return out
    }

    private fun JsonObject.str(key: String): String? = this[key].takeUnless { it.isDartNull }?.dartToString()

    /**
     * Dedup key `<y><m><d><h><min>|<first 12 chars of the normalized title>` with UNPADDED local
     * fields; normalized = lowercase without anything but `a-z0-9äöü`.
     */
    fun dedupKey(item: CalendarItem, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = item.start.atZone(zone)
        val tkey = item.title.dartLower().replace(NORM, "")
        val tShort = if (tkey.length > 12) tkey.substring(0, 12) else tkey
        return "${t.year}${t.monthValue}${t.dayOfMonth}${t.hour}${t.minute}|$tShort"
    }

    private fun rank(e: CalendarItem): Int = if (e.isCourse) 0 else if (e.isMeetup) 1 else 2

    /**
     * Sorted by start, cross-source de-duplicated (per key the first item stays unless a later
     * one has a strictly better rank: course > meetup > Nostr), sorted again.
     */
    fun merge(items: List<CalendarItem>, zone: ZoneId = ZoneId.systemDefault()): List<CalendarItem> {
        val byKey = LinkedHashMap<String, CalendarItem>()
        for (it in items.sortedBy { it.start }) {
            val key = dedupKey(it, zone)
            val existing = byKey[key]
            if (existing == null || rank(it) < rank(existing)) byKey[key] = it
        }
        return byKey.values.sortedBy { it.start }
    }

    /**
     * Type filter AND location filter (`q = query.trim().lowercase()`; empty `q` keeps all; items
     * with an empty trimmed location are always kept).
     */
    fun filter(items: List<CalendarItem>, type: CalendarTypeFilter, locationQuery: String): List<CalendarItem> {
        val q = locationQuery.dartTrim().dartLower()
        return items.filter { e ->
            val typeOk = when (type) {
                CalendarTypeFilter.ALL -> true
                CalendarTypeFilter.MEETUPS -> e.isMeetup
                CalendarTypeFilter.EXTERNAL -> !e.isMeetup && !e.isCourse
                CalendarTypeFilter.COURSES -> e.isCourse
            }
            typeOk && (q.isEmpty() || e.location.dartTrim().isEmpty() || e.location.dartLower().contains(q))
        }
    }

    /** Filter active (clear button visible): type ≠ ALL or a non-blank query. */
    fun isFilterActive(type: CalendarTypeFilter, locationQuery: String): Boolean =
        type != CalendarTypeFilter.ALL || locationQuery.dartTrim().isNotEmpty()

    /** Items grouped by local start date (multi-day all-day events only on their start day). */
    fun byDay(items: List<CalendarItem>, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, List<CalendarItem>> =
        items.groupBy { it.day(zone) }

    /** List view: items starting after [now] − 1 day. */
    fun upcoming(items: List<CalendarItem>, now: Instant): List<CalendarItem> {
        val from = now.minus(Duration.ofDays(1))
        return items.filter { it.start.isAfter(from) }
    }

    /** Year view: number of items per month (1..12) of [year]. */
    fun countsPerMonth(items: List<CalendarItem>, year: Int, zone: ZoneId = ZoneId.systemDefault()): Map<Int, Int> =
        items.map { it.day(zone) }.filter { it.year == year }.groupingBy { it.monthValue }.eachCount()

    /** Month grid: leading blank cells before the 1st (Monday-first week). */
    fun leadingBlanks(firstOfMonth: LocalDate): Int = (firstOfMonth.dayOfWeek.value + 6) % 7
}
