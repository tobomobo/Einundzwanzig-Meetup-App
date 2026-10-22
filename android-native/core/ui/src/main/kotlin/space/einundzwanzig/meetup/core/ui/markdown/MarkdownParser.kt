package space.einundzwanzig.meetup.core.ui.markdown

import space.einundzwanzig.meetup.core.common.dart.dartTrim

/** A block produced by [MarkdownParser.parse]. */
sealed interface MdBlock {
    /** `#`, `##` or `###` heading; [level] is 1..3. */
    data class Heading(val level: Int, val text: String) : MdBlock

    /** Consecutive text lines joined with a single space. */
    data class Paragraph(val text: String) : MdBlock

    /** Consecutive `> ` lines joined with a single space. */
    data class Quote(val text: String) : MdBlock

    /** `-`/`*` bullets or `1.` numbered items (renumbered 1..n when rendered). */
    data class ListBlock(val ordered: Boolean, val items: List<String>) : MdBlock

    /** Fenced code block (language tag ignored), raw lines joined with `\n`. */
    data class Code(val code: String) : MdBlock

    /** Image line or data-URI image; [url] is the raw (untrimmed) target. */
    data class Image(val url: String) : MdBlock

    /** `---`, `***` or `___`. */
    data object Rule : MdBlock
}

/** An inline run produced by [MarkdownParser.inline]. */
sealed interface MdSpan {
    /** Unformatted text. */
    data class Plain(val text: String) : MdSpan

    /** `**bold**`. */
    data class Bold(val text: String) : MdSpan

    /** `*italic*`. */
    data class Italic(val text: String) : MdSpan

    /** `` `code` ``. */
    data class Code(val text: String) : MdSpan

    /** `[label](url)`; the URL is opened only if it is http(s) (see `SafeUrl`). */
    data class Link(val label: String, val url: String) : MdSpan
}

/**
 * The compact, dependency-free Markdown parser of the Flutter app
 * (`lib/widgets/markdown_view.dart`), reproduced exactly (spec 11 §8.6). Not CommonMark:
 * lines starting with `#` that are not `# `/`## `/`### ` vanish (hashtags, `####`), only
 * three heading levels, no nesting, `_` emphasis unsupported. These quirks hide raw base64
 * and hashtags in Nostr articles and are relied upon.
 */
object MarkdownParser {
    private val allImages = Regex("""!\[[^\]]*\]\(([^)]+)\)""")
    private val singleImageLine = Regex("""^!\[[^\]]*\]\((.+)\)$""", RegexOption.DOT_MATCHES_ALL)
    private val dataUriImage = Regex("""!\[[^\]]*\]\((data:image/[^)]+)\)""")
    private val bullet = Regex("^[-*] ")
    private val ordered = Regex("""^\d+\. """)
    private val inlinePattern = Regex(
        """(\*\*([^*]+)\*\*)|(\*([^*]+)\*)|(`([^`]+)`)|(\[([^\]]+)\]\(([^)]+)\))""",
    )

    /** Spaceless lines longer than this are raw base64 garbage and are skipped. */
    private const val MAX_SPACELESS = 200

    /** Splits [src] into blocks. Never loops forever (progress guarantee). */
    fun parse(src: String): List<MdBlock> {
        val lines = src.replace("\r\n", "\n").split("\n")
        val blocks = mutableListOf<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].dartTrim()

            if (trimmed.isEmpty()) {
                i++
                continue
            }

            if (trimmed.startsWith("```")) {
                val buf = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].dartTrim().startsWith("```")) {
                    buf += lines[i]
                    i++
                }
                if (i < lines.size) i++ // closing fence
                blocks += MdBlock.Code(buf.joinToString("\n"))
                continue
            }

            if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
                blocks += MdBlock.Rule
                i++
                continue
            }

            when {
                trimmed.startsWith("### ") -> {
                    blocks += MdBlock.Heading(3, trimmed.substring(4)); i++; continue
                }
                trimmed.startsWith("## ") -> {
                    blocks += MdBlock.Heading(2, trimmed.substring(3)); i++; continue
                }
                trimmed.startsWith("# ") -> {
                    blocks += MdBlock.Heading(1, trimmed.substring(2)); i++; continue
                }
            }

            // Image-only line, possibly several images in a row.
            val images = allImages.findAll(trimmed).toList()
            if (images.isNotEmpty() && trimmed.replace(allImages, "").dartTrim().isEmpty()) {
                images.forEach { blocks += MdBlock.Image(it.groupValues[1]) }
                i++
                continue
            }
            // Single image whose URL contains ')' (greedy), e.g. long data URIs.
            singleImageLine.find(trimmed)?.let {
                blocks += MdBlock.Image(it.groupValues[1])
                i++
                continue
            }

            if (trimmed.length > MAX_SPACELESS && !trimmed.contains(' ')) {
                i++
                continue
            }

            if (trimmed.startsWith("> ")) {
                val buf = mutableListOf<String>()
                while (i < lines.size && lines[i].dartTrim().startsWith("> ")) {
                    buf += lines[i].dartTrim().substring(2)
                    i++
                }
                blocks += MdBlock.Quote(buf.joinToString(" "))
                continue
            }

            if (bullet.containsMatchIn(trimmed)) {
                val items = mutableListOf<String>()
                while (i < lines.size && bullet.containsMatchIn(lines[i].dartTrim())) {
                    items += lines[i].dartTrim().substring(2)
                    i++
                }
                blocks += MdBlock.ListBlock(ordered = false, items = items)
                continue
            }

            if (ordered.containsMatchIn(trimmed)) {
                val items = mutableListOf<String>()
                while (i < lines.size && ordered.containsMatchIn(lines[i].dartTrim())) {
                    items += lines[i].dartTrim().replaceFirst(ordered, "")
                    i++
                }
                blocks += MdBlock.ListBlock(ordered = true, items = items)
                continue
            }

            // Paragraph until the next blank line or block start.
            val buf = mutableListOf<String>()
            while (i < lines.size && isParagraphLine(lines[i].dartTrim())) {
                val lt = lines[i].dartTrim()
                val inlineImage = dataUriImage.find(lt)
                if (inlineImage != null) {
                    if (buf.isNotEmpty()) {
                        blocks += MdBlock.Paragraph(buf.joinToString(" "))
                        buf.clear()
                    }
                    blocks += MdBlock.Image(inlineImage.groupValues[1])
                    i++
                    continue
                }
                if (lt.length > MAX_SPACELESS && !lt.contains(' ')) {
                    i++
                    continue
                }
                buf += lt
                i++
            }
            if (buf.isNotEmpty()) {
                blocks += MdBlock.Paragraph(buf.joinToString(" "))
            } else {
                i++ // progress guarantee: a line nobody consumed must not stall the loop
            }
        }
        return blocks
    }

    private fun isParagraphLine(t: String): Boolean =
        t.isNotEmpty() &&
            !t.startsWith("#") &&
            !t.startsWith("> ") &&
            !t.startsWith("```") &&
            !bullet.containsMatchIn(t) &&
            !ordered.containsMatchIn(t)

    /** Inline formatting: `**bold**`, `*italic*`, `` `code` ``, `[text](url)`; left to right, no nesting. */
    fun inline(text: String): List<MdSpan> {
        val spans = mutableListOf<MdSpan>()
        var last = 0
        for (m in inlinePattern.findAll(text)) {
            if (m.range.first > last) spans += MdSpan.Plain(text.substring(last, m.range.first))
            val g = m.groups
            when {
                g[2] != null -> spans += MdSpan.Bold(g[2]!!.value)
                g[4] != null -> spans += MdSpan.Italic(g[4]!!.value)
                g[6] != null -> spans += MdSpan.Code(g[6]!!.value)
                g[8] != null -> spans += MdSpan.Link(g[8]!!.value, g[9]!!.value)
            }
            last = m.range.last + 1
        }
        if (last < text.length) spans += MdSpan.Plain(text.substring(last))
        if (spans.isEmpty()) spans += MdSpan.Plain(text)
        return spans
    }

    /**
     * Readable plain text for the render-error fallback: strips image syntax, embedded data
     * URIs, the characters `#*`>`, spaceless lines over 200 characters and collapses 3+
     * newlines.
     */
    fun plainFallback(src: String): String {
        var s = src
        s = s.replace(Regex("""!\[[^\]]*\]\([^)]*\)"""), "")
        s = s.replace(Regex("""data:image/[A-Za-z0-9;,+/=._-]+"""), "")
        s = s.replace(Regex("[#*`>]"), "")
        s = s.split("\n").filterNot { l -> l.dartTrim().length > MAX_SPACELESS && !l.dartTrim().contains(' ') }.joinToString("\n")
        s = s.replace(Regex("\n{3,}"), "\n\n")
        return s.dartTrim()
    }
}
