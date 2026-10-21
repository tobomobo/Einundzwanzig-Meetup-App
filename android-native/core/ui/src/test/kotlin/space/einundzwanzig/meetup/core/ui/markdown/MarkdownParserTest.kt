package space.einundzwanzig.meetup.core.ui.markdown

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.ui.markdown.MdBlock.Code
import space.einundzwanzig.meetup.core.ui.markdown.MdBlock.Heading
import space.einundzwanzig.meetup.core.ui.markdown.MdBlock.Image
import space.einundzwanzig.meetup.core.ui.markdown.MdBlock.ListBlock
import space.einundzwanzig.meetup.core.ui.markdown.MdBlock.Paragraph
import space.einundzwanzig.meetup.core.ui.markdown.MdBlock.Quote
import space.einundzwanzig.meetup.core.ui.markdown.MdBlock.Rule

/** Golden tests for the exact Flutter block algorithm (spec 11 §8.6, §10.4). */
class MarkdownParserTest {

    private fun parse(vararg lines: String) = MarkdownParser.parse(lines.joinToString("\n"))

    @Test
    fun `headings one to three`() {
        assertEquals(
            listOf(Heading(1, "Eins"), Heading(2, "Zwei"), Heading(3, "Drei")),
            parse("# Eins", "## Zwei", "### Drei"),
        )
    }

    @Test
    fun `hashtags and h4 are dropped`() {
        assertEquals(listOf(Paragraph("Text")), parse("#bitcoin", "#### Vier", "", "Text"))
        assertEquals(emptyList<MdBlock>(), parse("#nostr #bitcoin"))
    }

    @Test
    fun `heading line ends a paragraph and hashtag line ends it too`() {
        assertEquals(listOf(Paragraph("a b")), parse("a", "b", "#tag", "c").take(1))
        assertEquals(listOf(Paragraph("a b"), Paragraph("c")), parse("a", "b", "#tag", "c"))
    }

    @Test
    fun `fenced code keeps raw lines and ignores the language`() {
        assertEquals(
            listOf(Code("  val x = 1\n\nprintln(x)"), Paragraph("nach")),
            parse("```kotlin", "  val x = 1", "", "println(x)", "```", "nach"),
        )
    }

    @Test
    fun `unclosed fence runs to the end`() {
        assertEquals(listOf(Code("a\nb")), parse("```", "a", "b"))
    }

    @Test
    fun `horizontal rules`() {
        assertEquals(listOf(Rule, Rule, Rule), parse("---", "***", "___"))
    }

    @Test
    fun `rule directly after text joins the paragraph`() {
        assertEquals(listOf(Paragraph("Text ---")), parse("Text", "---"))
    }

    @Test
    fun `quote lines merge with a space`() {
        assertEquals(listOf(Quote("eins zwei"), Paragraph("drei")), parse("> eins", "  > zwei", "", "drei"))
    }

    @Test
    fun `quote marker without space is plain text`() {
        assertEquals(listOf(Paragraph(">kein Zitat")), parse(">kein Zitat"))
    }

    @Test
    fun `bullets with dash and star`() {
        assertEquals(listOf(ListBlock(false, listOf("a", "b", "c"))), parse("- a", "* b", "- c"))
    }

    @Test
    fun `ordered list keeps items and is renumbered when rendered`() {
        assertEquals(listOf(ListBlock(true, listOf("a", "b"))), parse("5. a", "7. b"))
    }

    @Test
    fun `list ends at a non list line`() {
        assertEquals(listOf(ListBlock(false, listOf("a")), Paragraph("text")), parse("- a", "text"))
    }

    @Test
    fun `image only line with two images`() {
        assertEquals(
            listOf(Image("https://a/1.png"), Image("https://a/2.png")),
            parse("![eins](https://a/1.png) ![](https://a/2.png)"),
        )
    }

    @Test
    fun `single image whose url contains a parenthesis`() {
        assertEquals(listOf(Image("https://a/b_(c).png")), parse("![x](https://a/b_(c).png)"))
    }

    @Test
    fun `data uri inside a paragraph splits it and drops the rest of its line`() {
        val uri = "data:image/png;base64,iVBORw0KGgo="
        assertEquals(
            listOf(Paragraph("vorher"), Image(uri), Paragraph("nachher")),
            parse("vorher", "Text ![bild]($uri) Rest", "nachher"),
        )
    }

    @Test
    fun `spaceless line over 200 characters is skipped`() {
        val garbage = "A".repeat(250)
        assertEquals(listOf(Paragraph("a b")), parse("a", garbage, "b"))
        assertEquals(listOf(Paragraph("x")), parse(garbage, "", "x"))
        // exactly 200 is kept
        assertEquals(listOf(Paragraph("B".repeat(200))), parse("B".repeat(200)))
    }

    @Test
    fun `crlf line endings`() {
        assertEquals(listOf(Heading(1, "T"), Paragraph("a b")), MarkdownParser.parse("# T\r\na\r\nb\r\n"))
    }

    @Test
    fun `blank input gives no blocks`() {
        assertEquals(emptyList<MdBlock>(), MarkdownParser.parse("\n  \n\t"))
    }

    @Test
    fun `dart trim strips bom and unicode spaces`() {
        assertEquals("x", "\uFEFF\u00A0 x\u3000\n".dartTrim())
        assertEquals("\u001Cx", "\u001Cx".dartTrim())
        assertEquals(listOf(Heading(1, "BOM")), MarkdownParser.parse("\uFEFF# BOM"))
    }

    @Test
    fun `inline formatting`() {
        assertEquals(
            listOf(
                MdSpan.Bold("b"), MdSpan.Plain(" "), MdSpan.Italic("i"), MdSpan.Plain(" "),
                MdSpan.Code("c"), MdSpan.Plain(" "), MdSpan.Link("l", "https://x"),
            ),
            MarkdownParser.inline("**b** *i* `c` [l](https://x)"),
        )
    }

    @Test
    fun `inline image renders as bang plus link, empty alt stays raw`() {
        assertEquals(listOf(MdSpan.Plain("!"), MdSpan.Link("alt", "https://i/x.png")), MarkdownParser.inline("![alt](https://i/x.png)"))
        assertEquals(listOf(MdSpan.Plain("![](https://i/x.png)")), MarkdownParser.inline("![](https://i/x.png)"))
    }

    @Test
    fun `inline without markup and empty text`() {
        assertEquals(listOf(MdSpan.Plain("a ** b")), MarkdownParser.inline("a ** b"))
        assertEquals(listOf(MdSpan.Plain("")), MarkdownParser.inline(""))
        assertEquals(
            listOf(MdSpan.Plain("x "), MdSpan.Link("js", "javascript:alert(1"), MdSpan.Plain(")")),
            MarkdownParser.inline("x [js](javascript:alert(1))"),
        )
    }

    @Test
    fun `plain fallback`() {
        val src = "# Titel\n![x](https://a/b.png)\n**fett** `code` > zitat\n\n\n\n" + "Z".repeat(201) + "\nEnde data:image/png;base64,AAAA"
        assertEquals("Titel\n\nfett code  zitat\n\nEnde", MarkdownParser.plainFallback(src))
    }

    @Test
    fun `image classification`() {
        assertEquals(MarkdownImages.Kind.Network, MarkdownImages.classify(" https://a/b.png "))
        assertEquals(MarkdownImages.Kind.Network, MarkdownImages.classify("HTTP://a/b.png"))
        assertEquals(MarkdownImages.Kind.Placeholder, MarkdownImages.classify("https://a/IMG_1.HEIC"))
        assertEquals(MarkdownImages.Kind.Placeholder, MarkdownImages.classify("https://a/x.heif?w=1"))
        assertEquals(MarkdownImages.Kind.None, MarkdownImages.classify("javascript:alert(1)"))
        assertEquals(MarkdownImages.Kind.None, MarkdownImages.classify("file:///etc/passwd"))
        assertEquals(MarkdownImages.Kind.None, MarkdownImages.classify("/relative.png"))
        assertEquals(MarkdownImages.Kind.None, MarkdownImages.classify(""))
        assertEquals(MarkdownImages.Kind.DataUri, MarkdownImages.classify("data:image/png;base64,AAAA"))
        assertEquals(MarkdownImages.Kind.None, MarkdownImages.classify("data:image/png;base64"))
        val huge = "data:image/png;base64," + "A".repeat(MarkdownImages.MAX_DATA_URI_BASE64 + 1)
        assertEquals(MarkdownImages.Kind.Placeholder, MarkdownImages.classify(huge))
    }

    @Test
    fun `data uri decoding accepts both alphabets and missing padding`() {
        assertArrayEquals(byteArrayOf(-5, -1), MarkdownImages.decodeDataUri("data:image/png;base64,+/8="))
        assertArrayEquals(byteArrayOf(-5, -1), MarkdownImages.decodeDataUri("data:image/png;base64,-_8"))
        assertNull(MarkdownImages.decodeDataUri("data:image/png;base64,***"))
        assertNull(MarkdownImages.decodeDataUri("data:image/png"))
    }
}
