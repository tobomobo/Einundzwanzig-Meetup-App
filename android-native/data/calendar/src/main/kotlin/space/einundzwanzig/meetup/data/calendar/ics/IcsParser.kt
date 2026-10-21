package space.einundzwanzig.meetup.data.calendar.ics

import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.data.calendar.internal.dartUpper

/** A date property value (`DTSTART`, `DTEND`, …): the raw value and its `TZID` parameter. */
data class IcsDateTime(val dt: String, val tzid: String? = null)

/** A property the package stores as a structured value; [value] is the raw text. */
data class IcsStructured(val value: String, val params: Map<String, String> = emptyMap())

/**
 * One component (`VEVENT`, `VALARM`, …) as `icalendar_parser` 2.1.0 represents it: [type] plus
 * property values keyed by the package's names (`summary`, `description`, `location`, `rrule`,
 * `uid`, `url`, … as [String]; `dtstart`, `dtend`, `dtstamp`, `created`, `lastModified`,
 * `completed`, `due` as [IcsDateTime]).
 */
data class IcsComponent(val type: String, val values: Map<String, Any>) {
    /** A string property, or `null`. */
    fun string(name: String): String? = values[name] as? String

    /** A date property, or `null`. */
    fun date(name: String): IcsDateTime? = values[name] as? IcsDateTime
}

/** Thrown for a document `icalendar_parser` rejects (envelope, VERSION/PRODID). */
class IcsFormatException(message: String) : Exception(message)

/**
 * Port of the subset of `icalendar_parser` 2.1.0 (`ICalendar.fromString`) the app relies on
 * (spec 10 §8.2), including its quirks:
 * - lines split on `\r\n`, `\n` or `\r`; trailing empty lines dropped; every line is trimmed;
 * - folding: following lines that start with a space are appended (trimmed); TAB folding is not
 *   supported;
 * - first line `BEGIN:VCALENDAR`, last line `END:VCALENDAR`, and `VERSION`/`PRODID` required;
 * - name = text before the first `:` up to the first `;` (rest are `K=V` params); value = the rest
 *   with `\,` → `,`; only the package's known property names are stored;
 * - a line whose name part has no `;` and is not upper case (or has no `:` at all) is appended to
 *   the last stored string property (continuation quirk);
 * - nested components are emitted as separate components when they end.
 */
object IcsParser {

    private val LINE_BREAK = Regex("\r\n|\n|\r")

    /** Property name → stored key and kind (the package's `_objects` table). */
    private val DATE_FIELDS = mapOf(
        "DTSTART" to "dtstart", "DTEND" to "dtend", "DTSTAMP" to "dtstamp", "LAST-MODIFIED" to "lastModified",
        "COMPLETED" to "completed", "DUE" to "due", "CREATED" to "created",
    )
    private val STRING_FIELDS = mapOf(
        "TRIGGER" to "trigger", "UID" to "uid", "SUMMARY" to "summary", "DESCRIPTION" to "description",
        "LOCATION" to "location", "URL" to "url", "ACTION" to "action", "SEQUENCE" to "sequence",
        "REPEAT" to "repeat", "CLASS" to "class", "VERSION" to "version", "PRODID" to "prodid",
        "CALSCALE" to "calscale", "METHOD" to "method", "RRULE" to "rrule",
    )

    /**
     * Properties the package stores as non-strings (enums, maps, lists). They are kept as
     * [IcsStructured] so the continuation quirk skips them, like in Dart.
     */
    private val STRUCTURED_FIELDS = mapOf(
        "STATUS" to "status", "TRANSP" to "transp", "ORGANIZER" to "organizer", "GEO" to "geo",
        "CATEGORIES" to "categories", "ATTENDEE" to "attendee", "EXDATE" to "exdate",
    )
    private val DART_NUM = Regex("""[+-]?(NaN|Infinity|(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?|0[xX][0-9a-fA-F]+)""")

    /** Dart `RegExp(r'/\\n/g')` of `generateSimpleParamFunction` (matches the literal text `/\n/g`). */
    private const val ODD_NEWLINE_PATTERN = "/\\n/g"

    /** Result: header properties and the components in the order they ended. */
    data class Result(val head: Map<String, Any>, val components: List<IcsComponent>)

    /**
     * Parses [ics]. Throws [IcsFormatException] where the package throws (callers fall back to an
     * empty list, like Dart's `fetchMeetups`).
     */
    fun parse(ics: String): Result {
        val lines = splitLines(ics).toMutableList()
        while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        if (lines.isEmpty()) throw IcsFormatException("empty calendar")
        if (lines.first().dartTrim() != "BEGIN:VCALENDAR") throw IcsFormatException("The first line must be BEGIN:VCALENDAR")
        if (lines.last().dartTrim() != "END:VCALENDAR") throw IcsFormatException("The last line must be END:VCALENDAR")

        val head = LinkedHashMap<String, Any>()
        val data = ArrayList<IcsComponent>()
        val open = ArrayList<Pair<String, LinkedHashMap<String, Any>>>()
        // `lastEvent` of the package: null → head data is used for properties.
        var current: LinkedHashMap<String, Any>? = LinkedHashMap() // the package starts with an orphan {}
        var currentType: String? = null
        var currentName: String? = null

        var i = 0
        while (i < lines.size) {
            val line = StringBuilder(lines[i].dartTrim())
            while (i + 1 < lines.size && lines[i + 1].startsWith(" ")) {
                i++
                line.append(lines[i].dartTrim())
            }
            i++
            val text = line.toString()
            val dataLine = text.split(':')
            val first = dataLine[0]
            if ((dataLine.size < 2 && !first.contains(';')) || (first.dartUpper() != first && !first.contains(';'))) {
                val target = current
                if (target != null && text.isNotEmpty() && currentName != null) {
                    val content = target[currentName] as? String ?: continue
                    target[currentName!!] = content + text
                }
                continue
            }
            val nameParts = first.split(';')
            val name = nameParts[0]
            val params = LinkedHashMap<String, String>()
            for (p in nameParts.drop(1)) {
                val kv = p.split('=')
                if (kv.size == 2) params[kv[0]] = kv[1]
            }
            val value = dataLine.drop(1).joinToString(":").replace("\\,", ",")

            when {
                name == "BEGIN" -> {
                    currentName = "begin"
                    if (value == "VCALENDAR") {
                        current = null
                        currentType = null
                    } else {
                        val component = LinkedHashMap<String, Any>()
                        open += value to component
                        current = component
                        currentType = value
                    }
                }
                name == "END" -> {
                    currentName = null
                    if (value != "VCALENDAR") {
                        val closing = current
                        if (closing != null) {
                            val idx = open.indexOfFirst { it.second === closing }
                            val type = if (idx >= 0) open[idx].first else currentType ?: ""
                            data += IcsComponent(type, closing.toMap())
                            if (idx >= 0) open.removeAt(idx)
                        }
                        val parent = open.lastOrNull()
                        current = parent?.second
                        currentType = parent?.first
                    }
                }
                name in DATE_FIELDS -> {
                    currentName = name.lowercase()
                    val target = current ?: head.also { current = it }
                    target[DATE_FIELDS.getValue(name)] = IcsDateTime(value, params["TZID"])
                }
                name in STRING_FIELDS -> {
                    currentName = name.lowercase()
                    val target = current ?: head.also { current = it }
                    target[STRING_FIELDS.getValue(name)] = value.replace(ODD_NEWLINE_PATTERN, "\n")
                }
                name in STRUCTURED_FIELDS -> {
                    currentName = name.lowercase()
                    val target = current ?: head.also { current = it }
                    if (name == "GEO") {
                        // Dart `num.parse` on both halves throws for garbage → the whole parse fails.
                        val pos = value.split(';')
                        if (pos.size != 2) continue
                        if (pos.any { !DART_NUM.matches(it.dartTrim()) }) throw IcsFormatException("Invalid GEO: $value")
                    }
                    target[STRUCTURED_FIELDS.getValue(name)] = IcsStructured(value, params)
                }
                else -> Unit // unknown property: ignored, currentName unchanged (package behaviour)
            }
        }
        if (!head.containsKey("version")) throw IcsFormatException("The body is missing the property VERSION")
        if (!head.containsKey("prodid")) throw IcsFormatException("The body is missing the property PRODID")
        return Result(head, data)
    }

    /** Dart `LineSplitter.convert`: no trailing empty element for a final line break. */
    private fun splitLines(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        val parts = s.split(LINE_BREAK)
        return if (parts.last().isEmpty()) parts.dropLast(1) else parts
    }
}
