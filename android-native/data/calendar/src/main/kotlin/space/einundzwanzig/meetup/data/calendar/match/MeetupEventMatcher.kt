package space.einundzwanzig.meetup.data.calendar.match

import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.data.calendar.internal.dartLower
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate
import space.einundzwanzig.meetup.data.meetups.MeetupService
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import java.io.ByteArrayOutputStream

/**
 * Conservative ownership of meetup dates (Dart `MeetupEventMatcher`, spec 10 §8.4): which
 * [Meetup] a [MeetupDate] belongs to, used by Home favorites, the dates list and RSVPs.
 */
object MeetupEventMatcher {

    private const val PORTAL_HOST = "portal.einundzwanzig.space"
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")
    private val GENERIC_TERMS = setOf("bitcoin", "meetup", "einundzwanzig", "stammtisch")

    /**
     * The meetup slug of a portal meetup page (`https://portal.einundzwanzig.space/<lang>/meetup/<slug>`),
     * else `""`. Scheme http/https, host case-insensitive; the FIRST non-empty path segment
     * `meetup` must be the second to last (query, fragment, trailing slash and locale segment are
     * ignored). Segments are percent-decoded like Dart `Uri.pathSegments`.
     */
    fun portalIdentity(value: String): String {
        val s = value.dartTrim()
        val scheme = SCHEME.find(s) ?: return ""
        if (scheme.groupValues[1].lowercase() !in setOf("http", "https")) return ""
        val rest = s.substring(scheme.range.last + 1)
        if (!rest.startsWith("//")) return ""
        val afterSlashes = rest.substring(2)
        val authorityEnd = afterSlashes.indexOfFirst { it == '/' || it == '?' || it == '#' }
            .let { if (it < 0) afterSlashes.length else it }
        val authority = afterSlashes.substring(0, authorityEnd)
        val hostPort = authority.substringAfterLast('@')
        val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
        if (host.lowercase() != PORTAL_HOST) return ""
        val pathAndMore = afterSlashes.substring(authorityEnd)
        val pathEnd = pathAndMore.indexOfFirst { it == '?' || it == '#' }.let { if (it < 0) pathAndMore.length else it }
        val parts = pathAndMore.substring(0, pathEnd).split('/').map(::percentDecode).filter { it.isNotEmpty() }
        val index = parts.indexOf("meetup")
        if (index < 0 || index != parts.size - 2) return ""
        return parts.last()
    }

    /**
     * The meetup [event] belongs to, or `null` when unknown or ambiguous:
     * 1. a non-empty `meetupId` must match exactly one meetup ID (never falls through);
     * 2. else a non-empty `meetupPortalLink` must match exactly one meetup's portal identity;
     * 3. else meetups whose trimmed name equals the trimmed title (case-insensitive) — if any,
     *    exactly one;
     * 4. else exactly one meetup whose city [matchesCity].
     */
    fun resolve(event: MeetupDate, meetups: List<Meetup>): Meetup? {
        fun unique(list: List<Meetup>): Meetup? = list.singleOrNull()
        if (event.meetupId.isNotEmpty()) return unique(meetups.filter { it.id == event.meetupId })
        if (event.meetupPortalLink.isNotEmpty()) {
            val identity = portalIdentity(event.meetupPortalLink)
            if (identity.isEmpty()) return null
            return unique(meetups.filter { portalIdentity(it.portalLink) == identity })
        }
        val title = event.title.dartTrim().dartLower()
        val named = meetups.filter { it.name.dartTrim().isNotEmpty() && it.name.dartTrim().dartLower() == title }
        if (named.isNotEmpty()) return unique(named)
        return unique(meetups.filter { matchesCity(event, it.city) })
    }

    /**
     * Legacy/ICS match of [city] in `"$title $location"`: only for dates without ID or portal
     * link; the term must have ≥ 3 characters, not be a generic word, and stand between Unicode
     * letter/digit boundaries (`ÜSachsen` and `Frankfurter` do not match `Sachsen`/`Frankfurt`).
     */
    fun matchesCity(event: MeetupDate, city: String): Boolean {
        if (event.meetupId.isNotEmpty() || event.meetupPortalLink.isNotEmpty()) return false
        val term = city.dartTrim().dartLower()
        if (term.length < 3 || term in GENERIC_TERMS) return false
        val hay = "${event.title} ${event.location}".dartLower()
        return Regex("(^|[^\\p{L}\\p{N}])" + Regex.escape(term) + "($|[^\\p{L}\\p{N}])").containsMatchIn(hay)
    }

    /**
     * Home's favorite match (`_eventMatchesFavorite`): a resolvable favorite [key] matches when
     * [resolve] gives the same meetup ID; an unresolvable key (legacy city) falls back to
     * [matchesCity].
     */
    fun matchesFavorite(event: MeetupDate, key: String, meetupService: MeetupService): Boolean {
        val m = meetupService.resolveFavorite(key)
        return if (m != null) resolve(event, meetupService.cached)?.id == m.id else matchesCity(event, key)
    }

    /** Lenient percent-decoding: valid `%XX` runs as UTF-8, anything else verbatim. */
    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '%' && i + 2 < s.length && isHex(s[i + 1]) && isHex(s[i + 2])) {
                val bytes = ByteArrayOutputStream()
                while (i + 2 < s.length && s[i] == '%' && isHex(s[i + 1]) && isHex(s[i + 2])) {
                    bytes.write(s.substring(i + 1, i + 3).toInt(16))
                    i += 3
                }
                out.append(bytes.toByteArray().decodeToString())
            } else {
                out.append(s[i])
                i++
            }
        }
        return out.toString()
    }

    private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
}
