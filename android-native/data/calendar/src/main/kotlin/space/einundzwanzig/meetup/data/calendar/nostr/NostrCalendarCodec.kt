package space.einundzwanzig.meetup.data.calendar.nostr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.dart.toStringAsFixed
import space.einundzwanzig.meetup.core.crypto.CryptoRandom
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.data.calendar.internal.dartLower
import space.einundzwanzig.meetup.data.calendar.internal.dartToString
import space.einundzwanzig.meetup.data.calendar.internal.dartTryParseDouble
import space.einundzwanzig.meetup.data.calendar.internal.dartTryParseInt
import space.einundzwanzig.meetup.data.calendar.internal.isDartNull
import space.einundzwanzig.meetup.data.calendar.internal.two
import space.einundzwanzig.meetup.data.calendar.model.CalendarEventDraft
import space.einundzwanzig.meetup.data.calendar.model.NostrCalendarEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * Parser and tag builder of NIP-52 calendar events, byte-compatible with
 * `lib/services/calendar_event_service.dart` (spec 10 §3.2, §6.3–§6.5). Pure; no I/O.
 */
object NostrCalendarCodec {

    /** Parsed event address `<kind>:<author>:<dTag>`. */
    data class Address(val kind: Int, val author: String, val dTag: String)

    /**
     * Dart `NostrCalendarEvent.fromEvent` on an already **verified** event object. Returns `null`
     * for other kinds, a missing title (`title`, else `name`) or start, an unparsable start and
     * any malformed structure (never throws). Cancelled events are returned (callers decide).
     *
     * Date-only starts (kind 31922) are read like Dart `DateTime.tryParse` in [zone].
     */
    fun parse(e: JsonObject, zone: ZoneId = ZoneId.systemDefault()): NostrCalendarEvent? = try {
        parseOrThrow(e, zone)
    } catch (_: Exception) {
        null
    }

    private fun parseOrThrow(e: JsonObject, zone: ZoneId): NostrCalendarEvent? {
        val kindEl = e["kind"]
        val kind: Long = if (kindEl.isDartNull) 0 else kindEl.dartIntOrNull() ?: return null // `as int?` TypeError
        if (kind != NostrCalendarEvent.KIND_TIME.toLong() && kind != NostrCalendarEvent.KIND_DATE.toLong()) return null
        val allDay = kind == NostrCalendarEvent.KIND_DATE.toLong()

        val tagsEl = e["tags"]
        val tags: List<List<String>> = if (tagsEl.isDartNull) {
            emptyList()
        } else {
            (tagsEl as? JsonArray ?: return null).map { t -> (t as? JsonArray ?: return null).map { it.dartToString() } }
        }
        fun tagVal(key: String): String {
            val t = tags.firstOrNull { it.isNotEmpty() && it[0] == key } ?: return ""
            return if (t.size >= 2) t[1] else ""
        }

        val title = tagVal("title").ifEmpty { tagVal("name") }
        val startRaw = tagVal("start")
        if (title.isEmpty() || startRaw.isEmpty()) return null

        var start: Instant? = null
        var end: Instant? = null
        var startDate: LocalDate? = null
        var endDate: LocalDate? = null
        if (allDay) {
            DartDateTime.tryParse(startRaw, zone)?.let {
                start = it.toInstant()
                startDate = it.fields(zone).toLocalDate()
            }
            val endRaw = tagVal("end")
            if (endRaw.isNotEmpty()) {
                DartDateTime.tryParse(endRaw, zone)?.let {
                    end = it.toInstant()
                    endDate = it.fields(zone).toLocalDate()
                }
            }
        } else {
            dartTryParseInt(startRaw)?.let { start = unixToInstant(it) }
            dartTryParseInt(tagVal("end"))?.let { end = unixToInstant(it) }
        }
        val startInstant = start ?: return null

        val geo = tags.firstOrNull { it.isNotEmpty() && it[0] == "g" }
        var lat = 0.0
        var lng = 0.0
        if (geo != null && geo.size >= 3) {
            lat = dartTryParseDouble(geo[1]) ?: 0.0
            lng = dartTryParseDouble(geo[2]) ?: 0.0
        }

        val issuers = tags
            .filter { it.size >= 4 && it[0] == "p" && it[3] == ROLE_ISSUER }
            .map { it[1] }
            .filter { it.length == HEX_KEY_LENGTH }
        val former = LinkedHashMap<String, Long>()
        for (x in tags) {
            if (x.size >= 5 && x[0] == "p" && x[3] == ROLE_FORMER_ISSUER && x[1].length == HEX_KEY_LENGTH) {
                val until = dartTryParseInt(x[4]) ?: continue
                former[x[1]] = until
            }
        }

        return NostrCalendarEvent(
            id = e.stringOrEmpty("id"),
            pubkey = e.stringOrEmpty("pubkey"),
            dTag = tagVal("d"),
            title = title,
            description = e.stringOrEmpty("content"),
            location = tagVal("location"),
            start = startInstant,
            end = end,
            allDay = allDay,
            kind = kind.toInt(),
            startDate = startDate,
            endDate = endDate,
            fromApp = tagVal("client") == NostrCalendarEvent.CLIENT,
            status = tagVal("status"),
            badgeEnabled = tagVal("badge").dartLower() == "true",
            badgeImageUrl = tagVal("badge_image"),
            lat = lat,
            lng = lng,
            issuers = issuers,
            formerIssuers = former,
            createdAt = e["created_at"].dartIntOrNull() ?: 0,
        )
    }

    /** Dart `DateTime.fromMillisecondsSinceEpoch(s * 1000)`, which throws outside ±8.64e15 ms. */
    private fun unixToInstant(seconds: Long): Instant {
        require(abs(seconds) <= DartDateTime.MAX_MILLIS / 1000) { "start out of range" }
        return Instant.ofEpochMilli(seconds * 1000)
    }

    private fun JsonObject.stringOrEmpty(key: String): String = this[key].takeUnless { it.isDartNull }?.dartToString() ?: ""

    /**
     * The tags and kind of a calendar event exactly as Dart `publishEvent` builds them (§6.3):
     * `d, title, start, [end], [location], client` followed by the badge tags (`badge`,
     * `badge_image`, `g` with `toStringAsFixed(6)`, `p … issuer`, `p … former_issuer <unix>`).
     */
    fun buildTags(draft: CalendarEventDraft, dTag: String): Pair<Int, List<List<String>>> {
        val badgeTags = buildList {
            if (draft.badgeEnabled) {
                add(listOf("badge", "true"))
                val image = draft.badgeImageUrl.dartTrim()
                if (image.isNotEmpty()) add(listOf("badge_image", image))
                if (draft.lat != 0.0 || draft.lng != 0.0) {
                    add(listOf("g", draft.lat.toStringAsFixed(6), draft.lng.toStringAsFixed(6)))
                }
                for (hex in draft.issuers) if (hex.length == HEX_KEY_LENGTH) add(listOf("p", hex, "", ROLE_ISSUER))
                for ((hex, until) in draft.formerIssuers) {
                    if (hex.length == HEX_KEY_LENGTH) add(listOf("p", hex, "", ROLE_FORMER_ISSUER, until.toString()))
                }
            }
        }
        val location = draft.location.dartTrim()
        val kind: Int
        val startValue: String
        val endValue: String?
        if (draft.allDay) {
            kind = NostrCalendarEvent.KIND_DATE
            startValue = ymdPadded(draft.start.toLocalDate())
            endValue = draft.end?.let { ymdPadded(it.toLocalDate()) }
        } else {
            kind = NostrCalendarEvent.KIND_TIME
            startValue = unixSeconds(draft.start.toInstant()).toString()
            endValue = draft.end?.let { unixSeconds(it.toInstant()).toString() }
        }
        val tags = buildList {
            add(listOf("d", dTag))
            add(listOf("title", draft.title))
            add(listOf("start", startValue))
            if (endValue != null) add(listOf("end", endValue))
            if (location.isNotEmpty()) add(listOf("location", location))
            add(listOf("client", NostrCalendarEvent.CLIENT))
            addAll(badgeTags)
        }
        return kind to tags
    }

    /**
     * Tags of the replacement that cancels [event] (§6.5): `d, title, start, status=cancelled,
     * client`. The all-day date is NOT year-padded here (Dart `_ymd`). Content = the unchanged
     * (untrimmed) description, kind = `event.kind`.
     */
    fun cancelTags(event: NostrCalendarEvent, zone: ZoneId = ZoneId.systemDefault()): List<List<String>> = listOf(
        listOf("d", event.dTag),
        listOf("title", event.title),
        listOf(
            "start",
            if (event.allDay) ymdUnpadded(event.day(zone)) else unixSeconds(event.start).toString(),
        ),
        listOf("status", NostrCalendarEvent.STATUS_CANCELLED),
        listOf("client", NostrCalendarEvent.CLIENT),
    )

    /** NIP-09 deletion request tags (kind 5, content `Termin abgesagt`): `e`, `a`, `k`. */
    fun deletionTags(event: NostrCalendarEvent): List<List<String>> = listOf(
        listOf("e", event.id),
        listOf("a", event.address),
        listOf("k", event.kind.toString()),
    )

    /**
     * Dart `fetchByAddress` address check: ≥ 3 `:`-parts, int kind, 64-char author (lowercased),
     * non-empty d-tag (the rest, re-joined with `:`); else `null`.
     */
    fun parseAddress(address: String): Address? {
        val parts = address.split(':')
        if (parts.size < 3) return null
        val kind = dartTryParseInt(parts[0]) ?: return null
        if (kind !in Int.MIN_VALUE..Int.MAX_VALUE) return null
        val author = parts[1].dartLower()
        val dTag = parts.subList(2, parts.size).joinToString(":")
        if (author.length != HEX_KEY_LENGTH || dTag.isEmpty()) return null
        return Address(kind.toInt(), author, dTag)
    }

    /** A fresh d-tag: 16 bytes from the CSPRNG as 32 lowercase hex characters. */
    fun newDTag(): String = CryptoRandom.hex(16)

    /** Dart `dt.toUtc().millisecondsSinceEpoch ~/ 1000` (truncating division). */
    fun unixSeconds(instant: Instant): Long = instant.toEpochMilli() / 1000

    /** `YYYY-MM-DD` with the year padded to 4 digits (publish). */
    fun ymdPadded(d: LocalDate): String = "${d.year.toString().padStart(4, '0')}-${two(d.monthValue)}-${two(d.dayOfMonth)}"

    /** `Y-MM-DD` without year padding (cancel; identical for years ≥ 1000). */
    fun ymdUnpadded(d: LocalDate): String = "${d.year}-${two(d.monthValue)}-${two(d.dayOfMonth)}"

    /** Role of a current badge issuer in `["p", hex, "", role]`. */
    const val ROLE_ISSUER: String = "issuer"

    /** Role of a removed issuer in `["p", hex, "", role, unix]`. */
    const val ROLE_FORMER_ISSUER: String = "former_issuer"

    private const val HEX_KEY_LENGTH = 64
}
