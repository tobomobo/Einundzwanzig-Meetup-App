package space.einundzwanzig.meetup.core.common.json

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.dart.toDartString
import java.math.BigInteger

/**
 * JSON writer that produces exactly the text of Dart's `jsonEncode` and
 * `JsonEncoder.withIndent(indent)`. Use it for everything that is hashed, signed, compared or
 * read by the Flutter app (event ids, canonical badge content, QR payloads, backup files).
 *
 * **Strings** (Dart `writeStringContent`): `"` → `\"`, `\` → `\\`, U+0008/09/0A/0C/0D →
 * `\b \t \n \f \r`, other code units below U+0020 → `\u00xx` (lowercase hex), lone surrogates
 * → `\udxxx`; everything else raw (no `\/`, U+007F, U+2028/2029 and non-ASCII unescaped).
 *
 * **Numbers**: an int prints in decimal, a double like Dart's `double.toString()` (`1.0`,
 * `0.0001`, `1e-7`, `1e+21`, `-0.0`). NaN and infinities throw, like Dart. A
 * [JsonPrimitive] is classified the way Dart's `jsonDecode` would read its literal: an integer
 * literal that fits 64 bits is an int (`-0` → `0`), anything else (fraction, exponent, larger
 * integers) is a double. So `1.0` stays `1.0`, `1e2` becomes `100.0`, and
 * `JsonPrimitive(4.0)` prints `4.0` while `JsonPrimitive(4)` prints `4`.
 *
 * **Values** accepted by [encode]: `null`, [String], [Boolean], [Int]/[Long]/[Short]/[Byte],
 * [BigInteger] within 64 bits, [Double]/[Float], [Map] with String keys (iteration order is
 * kept), [Iterable], arrays, and any kotlinx [JsonElement].
 */
object DartJson {

    /** Dart `jsonEncode(value)`: compact, no whitespace. */
    fun encode(value: Any?): String = StringBuilder().also { write(it, value, null, 0) }.toString()

    /**
     * Dart `JsonEncoder.withIndent(indent).convert(value)`: one entry per line, `": "` between
     * key and value, empty containers as `[]`/`{}`, no trailing newline.
     */
    fun encodePretty(value: Any?, indent: String = "  "): String =
        StringBuilder().also { write(it, value, indent, 0) }.toString()

    /**
     * `BadgeSecurity.canonicalJsonEncode`: sorts the **top-level** keys by UTF-16 code units
     * (Dart `List<String>.sort()`, same as Kotlin `String.compareTo`), keeps nested maps in their
     * own order, then [encode]s. Accepts a [JsonObject] as well.
     */
    fun canonicalJsonEncode(data: Map<String, Any?>): String {
        val sorted = LinkedHashMap<String, Any?>(data.size)
        for (key in data.keys.sorted()) sorted[key] = data[key]
        return encode(sorted)
    }

    /** A Dart JSON string literal including the quotes, e.g. `"a\"b"`. */
    fun quote(value: String): String = StringBuilder(value.length + 2).also { writeString(it, value) }.toString()

    /** Appends [value] as a Dart JSON string literal (with quotes) to [out]. */
    fun writeString(out: StringBuilder, value: String) {
        out.append('"')
        val length = value.length
        var offset = 0
        for (i in 0 until length) {
            val c = value[i].code
            if (c > BACKSLASH) {
                if (c >= SURROGATE_MIN && isLoneSurrogate(value, i)) {
                    if (i > offset) out.append(value, offset, i)
                    offset = i + 1
                    out.append("\\ud")
                        .append(HEX[(c shr 8) and 0xF])
                        .append(HEX[(c shr 4) and 0xF])
                        .append(HEX[c and 0xF])
                }
                continue
            }
            if (c < 0x20) {
                if (i > offset) out.append(value, offset, i)
                offset = i + 1
                out.append('\\')
                when (c) {
                    0x08 -> out.append('b')
                    0x09 -> out.append('t')
                    0x0A -> out.append('n')
                    0x0C -> out.append('f')
                    0x0D -> out.append('r')
                    else -> out.append("u00").append(HEX[(c shr 4) and 0xF]).append(HEX[c and 0xF])
                }
            } else if (c == QUOTE || c == BACKSLASH) {
                if (i > offset) out.append(value, offset, i)
                offset = i + 1
                out.append('\\').append(c.toChar())
            }
        }
        if (offset < length) out.append(value, offset, length)
        out.append('"')
    }

    /**
     * Dart `jsonDecode(text)`: parses JSON text into a [JsonElement]. Number literals are kept
     * verbatim, so int vs double survives a decode/[encode] round trip exactly like in Dart.
     *
     * As strict as Dart's `_JsonParser`: unquoted words (`abc`, `True`, `NaN`, `'x'`), malformed
     * numbers (`01`, `+1`, `.5`, `1.`), raw control characters (below U+0020) inside strings,
     * trailing commas, comments and trailing garbage are all rejected. Out-of-range numbers such
     * as `1e400` are accepted (Dart reads them as `Infinity`; only [encode] refuses them).
     * One deliberate difference: nesting deeper than kotlinx' default `maxNestingDepth` is
     * rejected (Dart has no limit; no payload of the app nests more than a few levels).
     *
     * @throws DartJsonFormatException (a [SerializationException]) for any text Dart's
     *   `jsonDecode` rejects with a `FormatException`. The message never contains [text].
     */
    fun decode(text: String): JsonElement {
        rejectControlCharsInStrings(text)
        val element = try {
            PARSER.parseToJsonElement(text)
        } catch (e: IllegalArgumentException) { // includes kotlinx' SerializationException
            // kotlinx appends the input ("JSON input: …") to its message; keep only the diagnosis.
            val reason = e.message.orEmpty().substringBefore("\nJSON input").substringBefore('\n').trim()
            throw DartJsonFormatException(if (reason.isEmpty()) "Invalid JSON" else "Invalid JSON: $reason")
        }
        requireDartLiterals(element)
        return element
    }

    /** Dart's string scanner fails on every raw character below U+0020 inside a string literal. */
    private fun rejectControlCharsInStrings(text: String) {
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (!inString) {
                if (c == '"') inString = true
            } else if (c == '\\') {
                i++ // the escaped character is validated by the parser
            } else if (c == '"') {
                inString = false
            } else if (c.code < 0x20) {
                throw DartJsonFormatException("Control character in string", offset = i)
            }
            i++
        }
    }

    /**
     * kotlinx keeps unquoted words as non-string [JsonPrimitive]s; Dart only knows `true`,
     * `false`, `null` and the JSON number grammar. Iterative, so deeply nested input cannot
     * overflow the stack.
     */
    private fun requireDartLiterals(root: JsonElement) {
        val pending = ArrayDeque<JsonElement>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            when (val element = pending.removeLast()) {
                is JsonObject -> pending.addAll(element.values)
                is JsonArray -> pending.addAll(element)
                is JsonNull -> Unit
                is JsonPrimitive -> {
                    if (element.isString) continue
                    val content = element.content
                    if (content != "true" && content != "false" && !NUMBER_LITERAL.matches(content)) {
                        throw DartJsonFormatException("Unexpected character")
                    }
                }
            }
        }
    }

    /**
     * How Dart's `jsonDecode` would type [primitive]: [DartNumber.Int], [DartNumber.Double], or
     * `null` for strings, booleans and `null`.
     *
     * @throws IllegalArgumentException if the literal is not a JSON number.
     */
    fun numberOf(primitive: JsonPrimitive): DartNumber? {
        if (primitive is JsonNull || primitive.isString) return null
        val content = primitive.content
        if (content == "true" || content == "false") return null
        if (INT_LITERAL.matches(content)) {
            return content.toLongOrNull()?.let { DartNumber.Int(it) } ?: DartNumber.Double(content.toDouble())
        }
        if (NUMBER_LITERAL.matches(content) || content in NON_FINITE) return DartNumber.Double(content.toDouble())
        throw IllegalArgumentException("Not a JSON number literal: $content")
    }

    private fun write(out: StringBuilder, value: Any?, indent: String?, level: Int) {
        when (value) {
            null, JsonNull -> out.append("null")
            is String -> writeString(out, value)
            is Boolean -> out.append(value)
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            is BigInteger -> {
                require(value.bitLength() < 64) { unsupported(value) }
                out.append(value.toString())
            }
            is Double -> writeDouble(out, value)
            is Float -> writeDouble(out, value.toDouble())
            is JsonPrimitive -> writePrimitive(out, value)
            is Map<*, *> -> writeMap(out, value, indent, level)
            is Iterable<*> -> writeList(out, value.toList(), indent, level)
            is Array<*> -> writeList(out, value.asList(), indent, level)
            is IntArray -> writeList(out, value.asList(), indent, level)
            is LongArray -> writeList(out, value.asList(), indent, level)
            is DoubleArray -> writeList(out, value.asList(), indent, level)
            is BooleanArray -> writeList(out, value.asList(), indent, level)
            else -> throw IllegalArgumentException(unsupported(value))
        }
    }

    private fun writePrimitive(out: StringBuilder, value: JsonPrimitive) {
        if (value.isString) return writeString(out, value.content)
        when (val number = numberOf(value)) {
            null -> out.append(value.content) // true / false
            is DartNumber.Int -> out.append(number.value)
            is DartNumber.Double -> writeDouble(out, number.value)
        }
    }

    private fun writeDouble(out: StringBuilder, value: Double) {
        require(!value.isNaN() && !value.isInfinite()) { unsupported(value) }
        out.append(value.toDartString())
    }

    private fun writeList(out: StringBuilder, list: List<*>, indent: String?, level: Int) {
        if (list.isEmpty()) {
            out.append("[]")
            return
        }
        out.append('[')
        list.forEachIndexed { i, item ->
            if (i > 0) out.append(',')
            newline(out, indent, level + 1)
            write(out, item, indent, level + 1)
        }
        newline(out, indent, level)
        out.append(']')
    }

    private fun writeMap(out: StringBuilder, map: Map<*, *>, indent: String?, level: Int) {
        if (map.isEmpty()) {
            out.append("{}")
            return
        }
        out.append('{')
        var first = true
        for ((key, item) in map) {
            require(key is String) { "Map keys must be strings: $key" }
            if (!first) out.append(',')
            first = false
            newline(out, indent, level + 1)
            writeString(out, key)
            out.append(if (indent == null) ":" else ": ")
            write(out, item, indent, level + 1)
        }
        newline(out, indent, level)
        out.append('}')
    }

    private fun newline(out: StringBuilder, indent: String?, level: Int) {
        if (indent == null) return
        out.append('\n')
        repeat(level) { out.append(indent) }
    }

    private fun isLoneSurrogate(s: String, i: Int): Boolean {
        val c = s[i]
        return when {
            Character.isHighSurrogate(c) -> !(i + 1 < s.length && Character.isLowSurrogate(s[i + 1]))
            Character.isLowSurrogate(c) -> !(i > 0 && Character.isHighSurrogate(s[i - 1]))
            else -> false
        }
    }

    private fun unsupported(value: Any?): String =
        "Converting object to an encodable object failed: ${value?.let { it::class.simpleName }} $value"

    private const val QUOTE = '"'.code
    private const val BACKSLASH = '\\'.code
    private const val SURROGATE_MIN = 0xD800
    private const val HEX = "0123456789abcdef"
    private val INT_LITERAL = Regex("""-?(?:0|[1-9][0-9]*)""")
    private val NUMBER_LITERAL = Regex("""-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?""")
    private val NON_FINITE = setOf("NaN", "Infinity", "-Infinity")
    private val PARSER = Json { isLenient = false }
}

/** A JSON number typed the way Dart's `jsonDecode` types it. */
sealed interface DartNumber {
    /** Converts to a double like Dart's `num.toDouble()`. */
    fun toDouble(): kotlin.Double

    /** Dart `int`. */
    data class Int(val value: Long) : DartNumber {
        override fun toDouble(): kotlin.Double = value.toDouble()
    }

    /** Dart `double`. */
    data class Double(val value: kotlin.Double) : DartNumber {
        override fun toDouble(): kotlin.Double = value
    }
}

/**
 * Thrown by [DartJson.decode] for text that Dart's `jsonDecode` rejects with a `FormatException`.
 * A [SerializationException] (and therefore an [IllegalArgumentException]), so callers that catch
 * either keep working. The message deliberately omits the input, which may hold secrets
 * (QR payloads, backups).
 *
 * @property offset index of the offending character in the input, or `-1` if unknown.
 */
class DartJsonFormatException(
    message: String,
    val offset: Int = -1,
    cause: Throwable? = null,
) : SerializationException(if (offset >= 0) "$message (at character ${offset + 1})" else message, cause)
