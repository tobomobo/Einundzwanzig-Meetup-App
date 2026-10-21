package space.einundzwanzig.meetup.data.news

import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.dart.dartTrim

/**
 * The tolerant regex RSS parser of `NewsService._parseRss` / `_parseDate` (spec 11 §8.3, §8.4).
 * Deliberately not an XML parser: behaviour on malformed feeds must match the Flutter app.
 *
 * The patterns spell out ASCII classes instead of `\b`/`\w`/`\d`/`\s` and case-insensitive
 * flags, because Android's ICU regex engine treats those as Unicode-aware while Dart (JavaScript
 * semantics) does not. Results are identical on the JVM and on Android.
 */
object RssParser {

    /** Title used when an item has none. */
    const val UNTITLED: String = "(ohne Titel)"

    // JavaScript `\s` (Dart RegExp): ASCII whitespace, NBSP, Unicode spaces, line/paragraph separators, BOM.
    private const val JS_SPACE = "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"

    // Dart `\b` after a literal word character: the next character is not a word character.
    private const val WORD_END = "(?![A-Za-z0-9_])"

    private val ITEM_RE = Regex("<${ci("item")}$WORD_END[^>]*>(.*?)</${ci("item")}>", RegexOption.DOT_MATCHES_ALL)
    private val ENCLOSURE_RE = Regex("<${ci("enclosure")}[^>]*${ci("url")}=\"([^\"]+)\"")
    private val IMG_RE = Regex("<${ci("img")}[^>]*${ci("src")}=\"([^\"]+)\"")
    private val CDATA_RE = Regex("<!\\[${ci("CDATA")}\\[")
    private val TAG_RE = Regex("<[^>]+>")
    private val SPACES_RE = Regex("$JS_SPACE+")
    private val DATE_RE = Regex(
        "([0-9]{1,2})$JS_SPACE+([A-Za-z0-9_]{3})[A-Za-z0-9_]*$JS_SPACE+([0-9]{4})$JS_SPACE+([0-9]{2}):([0-9]{2})(?::([0-9]{2}))?",
    )
    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )
    private val extractCache = HashMap<String, Regex>()

    /**
     * Parses every `<item>` of [xml] in feed order. Items with neither title nor description are
     * skipped. [nowSeconds] is the fallback date for items without a parseable `pubDate`.
     */
    fun parse(xml: String, nowSeconds: Long): List<NewsArticle> {
        val items = ArrayList<NewsArticle>()
        for (m in ITEM_RE.findAll(xml)) {
            val block = m.groupValues[1]
            val title = clean(extract(block, "title"))
            val link = clean(extract(block, "link"))
            val guid = clean(extract(block, "guid"))
            val creator = extract(block, "dc:creator")
            val author = clean(creator.ifEmpty { extract(block, "author") })
            val descRaw = extract(block, "description")
            val contentRaw = extract(block, "content:encoded").ifEmpty { descRaw }
            val pubDate = extract(block, "pubDate")

            val image = ENCLOSURE_RE.find(block)?.groupValues?.get(1)
                ?: IMG_RE.find(contentRaw)?.groupValues?.get(1)
                ?: ""

            if (title.isEmpty() && descRaw.isEmpty()) continue

            items += NewsArticle(
                id = guid.ifEmpty { link },
                author = author,
                title = title.ifEmpty { UNTITLED },
                summary = stripHtml(clean(descRaw)).dartTrim(),
                image = image,
                content = clean(contentRaw),
                publishedAt = parseDate(pubDate, nowSeconds),
                link = link.ifEmpty { guid },
            )
        }
        return items
    }

    /**
     * Dart `_parseDate`: `"<d> <mon…> <yyyy> <HH>:<mm>[:<ss>]"` anywhere in the lower-cased
     * string, read as **UTC** (the timezone suffix is ignored on purpose, spec 11 §14 risk 8).
     * Unknown month → January. Empty, unmatched or invalid input → [nowSeconds].
     */
    fun parseDate(s: String, nowSeconds: Long): Long {
        if (s.isEmpty()) return nowSeconds
        try {
            val m = DATE_RE.find(dartAsciiLower(s)) ?: return nowSeconds
            val g = m.groupValues
            val day = g[1].toInt()
            val mon = MONTHS[g[2]] ?: 1
            val year = g[3].toInt()
            val hour = g[4].toInt()
            val min = g[5].toInt()
            val sec = g[6].ifEmpty { "0" }.toIntOrNull() ?: 0
            return Math.floorDiv(DartDateTime.utc(year, mon, day, hour, min, sec).millisecondsSinceEpoch, 1000L)
        } catch (_: Exception) {
            return nowSeconds
        }
    }

    /** Dart `_clean`: trim, drop `<![CDATA[` (any case) and `]]>`, decode 7 entities in order (`&amp;` first), trim. */
    fun clean(s: String): String {
        var out = s.dartTrim()
        out = CDATA_RE.replace(out, "")
        out = out.replace("]]>", "")
        out = out
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
        return out.dartTrim()
    }

    /** Dart `_stripHtml`: every tag → `" "`, then whitespace runs → `" "` (not trimmed). */
    fun stripHtml(s: String): String = SPACES_RE.replace(TAG_RE.replace(s, " "), " ")

    /** Dart `_extract`: group 1 of the first `<tag …>(.*?)</tag>` (dotAll, case-insensitive), or `""`. */
    internal fun extract(block: String, tag: String): String {
        val re = synchronized(extractCache) {
            extractCache.getOrPut(tag) {
                Regex("<${ci(tag)}$WORD_END[^>]*>(.*?)</${ci(tag)}>", RegexOption.DOT_MATCHES_ALL)
            }
        }
        return re.find(block)?.groupValues?.get(1) ?: ""
    }

    /** A literal pattern matching [literal] case-insensitively for ASCII letters only (Dart non-unicode `caseSensitive: false`). */
    private fun ci(literal: String): String = buildString {
        for (c in literal) {
            when (c) {
                in 'a'..'z', in 'A'..'Z' -> append('[').append(c.lowercaseChar()).append(c.uppercaseChar()).append(']')
                else -> append(Regex.escape(c.toString()))
            }
        }
    }

    /** Lower-cases ASCII letters only; the date pattern only matches ASCII, so other characters are irrelevant. */
    private fun dartAsciiLower(s: String): String = buildString(s.length) {
        for (c in s) append(if (c in 'A'..'Z') c + 32 else c)
    }
}
