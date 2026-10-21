package space.einundzwanzig.meetup.core.ui.markdown

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.text.LinkAnnotation
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme

/** Rendering checks for [MarkdownView]: link safety, list numbering, placeholders. */
@RunWith(AndroidJUnit4::class)
class MarkdownViewTest {

    @get:Rule
    val rule = createComposeRule()

    private val clicked = mutableListOf<String>()

    private fun show(markdown: String) {
        rule.setContent { EinundzwanzigTheme { MarkdownView(markdown, onLinkClick = { clicked += it }) } }
    }

    @Test
    fun `http links are followed, javascript links are silently ignored`() {
        show("Mehr [hier](https://einundzwanzig.space) oder [js](javascript:alert(1))")
        val node = rule.onNodeWithText("Mehr", substring = true)
        node.performFirstLinkClick { (it.item as? LinkAnnotation.Clickable)?.tag?.startsWith("javascript") == true }
        assertEquals(emptyList<String>(), clicked)
        node.performFirstLinkClick { (it.item as? LinkAnnotation.Clickable)?.tag == "https://einundzwanzig.space" }
        assertEquals(listOf("https://einundzwanzig.space"), clicked)
    }

    @Test
    fun `ordered lists are renumbered from one`() {
        show("5. a\n7. b")
        rule.onNodeWithText("1.").assertExists()
        rule.onNodeWithText("2.").assertExists()
        rule.onNodeWithText("5.").assertDoesNotExist()
    }

    @Test
    fun `heic images show the placeholder`() {
        show("![](https://example.com/IMG_0001.HEIC)")
        rule.onNodeWithText("🖼  Bild").assertExists()
    }

    @Test
    fun `hashtag lines vanish and headings render`() {
        show("# Titel\n#bitcoin\nText")
        rule.onNodeWithText("Titel").assertExists()
        rule.onNodeWithText("Text").assertExists()
        rule.onNodeWithText("#bitcoin", substring = true).assertDoesNotExist()
    }

    @Test
    fun `blank markdown renders nothing`() {
        show("  \n ")
        rule.onNodeWithText("", substring = true).assertDoesNotExist()
    }
}
