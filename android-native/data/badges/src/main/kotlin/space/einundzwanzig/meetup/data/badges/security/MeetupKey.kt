package space.einundzwanzig.meetup.data.badges.security

import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.data.badges.text.DartString

/**
 * Result of [MeetupKey.parse].
 *
 * @property slug city slug, e.g. `bad-neuenahr` (basis of the network key).
 * @property country upper-case ISO code (`DE`) or `""`.
 */
data class ParsedMeetupId(val slug: String, val country: String)

/**
 * The signed meetup id `m` of a badge: `<city-slug>-<iso2>` or just `<city-slug>` (Dart
 * `MeetupKey`, spec 02 §8.2). One rule for organizer and participant. Case mapping and trimming
 * use [DartString]: the slug is signed and becomes the co-attendance d-tag.
 */
object MeetupKey {

    /** `trim().toLowerCase().replaceAll(' ', '-')` (only ASCII space is replaced). */
    fun slug(name: String): String = DartString.lower(DartString.trim(name)).replace(' ', '-')

    /** Slug plus lower-case country if [country] is a valid ISO code, else the slug alone. */
    fun compose(slug: String, country: String): String {
        val c = DartString.upper(DartString.trim(country))
        if (slug.isEmpty()) return slug
        if (!isIsoCountry(c)) return slug
        return "$slug-${DartString.lower(c)}"
    }

    /** Splits off the last `-xx` only if it is a real ISO 3166 code (`bad-neuenahr` stays whole). */
    fun parse(m: String): ParsedMeetupId {
        val v = DartString.trim(m)
        val i = v.lastIndexOf('-')
        if (i <= 0 || i == v.length - 1) return ParsedMeetupId(v, "")
        val last = DartString.upper(v.substring(i + 1))
        if (!isIsoCountry(last)) return ParsedMeetupId(v, "")
        return ParsedMeetupId(v.substring(0, i), last)
    }

    /**
     * Display name from a slug: `bad-neuenahr` → `Bad Neuenahr`. The first UTF-16 code unit of
     * each word is upper-cased with Dart's table (a lone high surrogate stays unchanged).
     */
    fun displayFromSlug(slug: String): String = slug.split('-')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { w -> DartString.upper(w.substring(0, 1)) + w.substring(1) }

    /** `"$slug-${date.toIso8601String().substring(0, 10)}"` (network key stem of a meetup day). */
    fun eventId(slug: String, date: DartDateTime): String = "$slug-${date.toIso8601String().substring(0, 10)}"

    /** `code.length == 2 && ISO3166.contains(upper(code))` (249 codes; `UK` is not one, `GB` is). */
    fun isIsoCountry(code: String): Boolean = code.length == 2 && ISO_3166.contains(DartString.upper(code))

    private val ISO_3166: Set<String> = """
        AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY
        BZ CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ FK
        FM FO FR GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN IO IQ IR
        IS IT JE JM JO JP KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK
        ML MM MN MO MP MQ MR MS MT MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM
        PN PR PS PT PW PY QA RE RO RS RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD TF
        TG TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS YE YT ZA ZM ZW
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    /** Number of ISO codes (for tests). */
    internal val isoCountryCount: Int get() = ISO_3166.size
}
