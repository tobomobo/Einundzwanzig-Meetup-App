package space.einundzwanzig.meetup.data.badges.text

import space.einundzwanzig.meetup.core.common.dart.dartTrim

/**
 * Byte-exact Dart `String.trim()`, `toLowerCase()` and `toUpperCase()` (spec 02 §8.14).
 *
 * Every trim/lower/upper that feeds a key, hash, signed field or comparison must go through
 * this object instead of Kotlin `trim()` / `lowercase()` / `uppercase()` (with or without
 * `Locale.ROOT`): Java applies final-sigma and special casing (`ΟΔΟΣ` → `οδος`, `İ` → `i̇`,
 * `ß` → `SS`) and a newer Unicode table than Dart. Affected: [space.einundzwanzig.meetup.data.badges.security.MeetupKey],
 * event `m` slugs, the `nd:` identity key, co-attendance keys and the wallet filter.
 *
 * Case mapping is strictly one code point to one code point, without context or locale, using
 * the case table generated with the Dart SDK the Flutter app ships (Dart 3.13.5, 2050 entries,
 * resource `dart_case_table.txt`, SHA-256 `70a94653…ac138`). Lone surrogates stay unchanged and
 * the UTF-16 length never changes.
 */
object DartString {

    /** Resource name of the shipped Dart case table (next to this class). */
    const val CASE_TABLE_RESOURCE = "dart_case_table.txt"

    /**
     * Loads the case table (a Java resource read from the APK, tens of milliseconds the first
     * time). Call it off the main thread before the first [lower]/[upper] on a UI path; the badge
     * repository does so on its IO dispatcher with every load. Idempotent and cheap afterwards.
     */
    fun warmUp() {
        Tables.lower
    }

    /** Dart `s.trim()` (26 whitespace code units incl. U+0085 and U+FEFF; not U+001C–U+001F). */
    fun trim(s: String): String = s.dartTrim()

    /** Dart `s.toLowerCase()`. */
    fun lower(s: String): String = map(s, Tables.lower)

    /** Dart `s.toUpperCase()` (keeps `ß`, no special casing). */
    fun upper(s: String): String = map(s, Tables.upper)

    private fun map(s: String, table: Map<Int, Int>): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                val cp = Character.toCodePoint(c, s[i + 1])
                sb.appendCodePoint(table[cp] ?: cp)
                i += 2
            } else {
                // Lone surrogates are not in the table and are copied unchanged.
                sb.append((table[c.code] ?: c.code).toChar())
                i++
            }
        }
        return sb.toString()
    }

    /** Raw bytes of the shipped case table (tests assert its SHA-256). */
    fun caseTableBytes(): ByteArray =
        checkNotNull(DartString::class.java.getResourceAsStream(CASE_TABLE_RESOURCE)) {
            "Missing resource $CASE_TABLE_RESOURCE"
        }.use { it.readBytes() }

    private object Tables {
        val lower: Map<Int, Int>
        val upper: Map<Int, Int>

        init {
            val lo = HashMap<Int, Int>(2600)
            val up = HashMap<Int, Int>(2600)
            caseTableBytes().toString(Charsets.US_ASCII).lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val parts = line.split(' ')
                require(parts.size == 3) { "Malformed case table line: $line" }
                val cp = parts[0].toInt(16)
                val l = parts[1].toInt(16)
                val u = parts[2].toInt(16)
                if (l != cp) lo[cp] = l
                if (u != cp) up[cp] = u
            }
            lower = lo
            upper = up
        }
    }
}
