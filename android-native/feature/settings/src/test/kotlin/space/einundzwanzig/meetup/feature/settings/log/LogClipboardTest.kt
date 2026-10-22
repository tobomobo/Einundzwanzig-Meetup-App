package space.einundzwanzig.meetup.feature.settings.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Clipping and failure handling of the log clipboard copy (Binder limit). */
class LogClipboardTest {

    @Test
    fun `a small log is copied unchanged`() {
        val text = "12:00:00  INFO   System: App 1.7.2"
        assertSame(text, LogClipboard.clipText(text))
    }

    @Test
    fun `a huge log keeps its newest lines, cut at a line start, within the budget`() {
        val line = "y".repeat(3_999)
        val text = List(800) { "$it $line" }.joinToString("\n")
        assertTrue(text.toByteArray().size > 2 * LogClipboard.MAX_CLIP_BYTES)
        val clip = LogClipboard.clipText(text)
        assertTrue(clip.toByteArray().size <= LogClipboard.MAX_CLIP_BYTES)
        assertTrue(text.endsWith(clip))
        assertTrue(text.contains("\n$clip"))
        assertTrue(clip.startsWith("${800 - clip.lines().size} "))
    }

    @Test
    fun `without a line break the cut never splits a character`() {
        val text = "ä".repeat(100)
        val clip = LogClipboard.clipText(text, maxBytes = 51)
        assertEquals("ä".repeat(25), clip)
    }
}
