package space.einundzwanzig.meetup.data.migration.legacy

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException

/**
 * Read-only access to the app's `shared_prefs` XML files.
 *
 * The Flutter files are parsed directly instead of through `Context.getSharedPreferences`, which
 * would cache an instance for the process and, when an interrupted write left a `.bak` file,
 * rename that file over the original (a write). Android's own loader prefers the `.bak` file
 * (the last complete version) in that case, and so does [read].
 */
internal class SharedPrefsFiles(private val dir: File) {

    constructor(context: Context) : this(sharedPrefsDir(context))

    /** `shared_prefs/<name>.xml`. */
    fun file(name: String): File = File(dir, "$name.xml")

    /** `shared_prefs/<name>.xml.bak`. */
    fun backupFile(name: String): File = File(dir, "$name.xml.bak")

    /** Whether the file or its interrupted-write backup exists (never creates anything). */
    fun exists(name: String): Boolean = file(name).isFile || backupFile(name).isFile

    /** Every file name (without `.xml`/`.xml.bak`) in `shared_prefs/` that matches [filter]. */
    fun names(filter: (String) -> Boolean): Set<String> =
        dir.listFiles().orEmpty().mapNotNull { f ->
            when {
                f.name.endsWith(".xml.bak") -> f.name.removeSuffix(".xml.bak")
                f.name.endsWith(".xml") -> f.name.removeSuffix(".xml")
                else -> null
            }
        }.filter(filter).toSet()

    /**
     * The entries of `<name>.xml` with the Android `SharedPreferences` value types (`String`,
     * `Boolean`, `Int`, `Long`, `Float`, `Set<String>`). Missing file → empty map.
     *
     * @throws IOException if the file exists but cannot be read or parsed.
     */
    fun read(name: String): Map<String, Any> {
        val source = backupFile(name).takeIf { it.isFile } ?: file(name).takeIf { it.isFile } ?: return emptyMap()
        return try {
            source.inputStream().use { parse(it) }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Unparsable shared_prefs file ${source.name}: ${e.javaClass.simpleName}")
        }
    }

    internal companion object {
        fun sharedPrefsDir(context: Context): File = File(context.dataDir, "shared_prefs")

        /** Parses the `XmlUtils.writeMapXml` format used by `SharedPreferencesImpl`. */
        fun parse(input: java.io.InputStream): Map<String, Any> {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, "UTF-8")
            val out = LinkedHashMap<String, Any>()
            var event = parser.eventType
            while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) return out
            if (parser.name != "map") throw IOException("Root element is not <map>")
            while (true) {
                event = parser.next()
                if (event == XmlPullParser.END_DOCUMENT) break
                if (event == XmlPullParser.END_TAG && parser.depth == 1) break
                if (event != XmlPullParser.START_TAG) continue
                val tag = parser.name
                val name = parser.getAttributeValue(null, "name")
                val value: Any? = when (tag) {
                    "string" -> parser.nextText()
                    "boolean" -> parser.getAttributeValue(null, "value")?.toBooleanStrict().also { skip(parser) }
                    "int" -> parser.getAttributeValue(null, "value")?.toInt().also { skip(parser) }
                    "long" -> parser.getAttributeValue(null, "value")?.toLong().also { skip(parser) }
                    "float" -> parser.getAttributeValue(null, "value")?.toFloat().also { skip(parser) }
                    "set" -> readSet(parser)
                    else -> null.also { skip(parser) } // <null/> and unknown tags carry no value
                }
                if (name != null && value != null) out[name] = value
            }
            return out
        }

        private fun readSet(parser: XmlPullParser): Set<String> {
            val set = LinkedHashSet<String>()
            val depth = parser.depth
            while (true) {
                val e = parser.next()
                if (e == XmlPullParser.END_DOCUMENT) throw IOException("Unterminated <set>")
                if (e == XmlPullParser.END_TAG && parser.depth == depth) return set
                if (e == XmlPullParser.START_TAG) {
                    if (parser.name == "string") set += parser.nextText() else skip(parser)
                }
            }
        }

        /** Skips to the END_TAG of the current START_TAG. */
        private fun skip(parser: XmlPullParser) {
            if (parser.eventType != XmlPullParser.START_TAG) return
            var depth = 1
            while (depth > 0) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> depth++
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.END_DOCUMENT -> throw IOException("Unexpected end of document")
                }
            }
        }
    }
}
