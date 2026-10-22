package space.einundzwanzig.meetup.feature.news

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.feature.news.list.NewsAction
import space.einundzwanzig.meetup.feature.news.list.NewsScreen
import space.einundzwanzig.meetup.feature.news.list.NewsTestTags
import space.einundzwanzig.meetup.feature.news.list.NewsUiState
import space.einundzwanzig.meetup.core.l10n.R as L10nR

@RunWith(AndroidJUnit4::class)
class NewsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val actions = mutableListOf<NewsAction>()

    private fun show(state: NewsUiState) {
        rule.setContent { EinundzwanzigTheme { NewsScreen(state, onAction = { actions += it }) } }
    }

    @Test
    fun `loading state shows the loading text`() {
        show(NewsUiState(loading = true))
        rule.onNodeWithTag(NewsTestTags.LOADING).assertExists()
        rule.onNodeWithText(context.getString(L10nR.string.newsLoading)).assertExists()
    }

    @Test
    fun `empty state shows newsEmpty`() {
        show(NewsUiState(loading = false))
        rule.onNodeWithTag(NewsTestTags.EMPTY).assertExists()
        rule.onNodeWithText(context.getString(L10nR.string.newsEmpty)).assertExists()
    }

    @Test
    fun `cards show title, summary, author fallback and date`() {
        val noAuthor = article("b", author = "", publishedAt = 1_792_152_977)
        show(NewsUiState(loading = false, articles = listOf(article("a"), noAuthor)))
        rule.onNodeWithText("Titel a").assertIsDisplayed()
        rule.onNodeWithText("Zusammenfassung a").assertIsDisplayed()
        rule.onNodeWithTag(NewsTestTags.LIST).performScrollToNode(hasText("Einundzwanzig"))
        rule.onNodeWithText("Einundzwanzig").assertExists()
        rule.onNodeWithText(noAuthor.dateLabel()).assertExists()
    }

    @Test
    fun `tapping a card opens the article with its index`() {
        val articles = listOf(article("a"), article("b"))
        show(NewsUiState(loading = false, articles = articles))
        rule.onNodeWithTag(NewsTestTags.LIST).performScrollToNode(hasText("Titel b"))
        rule.onNodeWithText("Titel b").performClick()
        assertEquals(listOf(NewsAction.OpenArticle(1, articles[1])), actions)
    }

    @Test
    fun `action tiles open write and website links`() {
        show(NewsUiState(loading = false))
        rule.onNodeWithText(context.getString(L10nR.string.newsWriteArticle)).performClick()
        rule.onNodeWithText(context.getString(L10nR.string.newsOpenWebsite)).performClick()
        assertEquals(listOf(NewsAction.WriteArticle, NewsAction.OpenWebsite), actions)
    }

    @Test
    fun `footer names the source`() {
        show(NewsUiState(loading = false))
        rule.onNodeWithText(context.getString(L10nR.string.newsSource)).assertExists()
    }

    @Test
    fun `back button pops`() {
        show(NewsUiState(loading = false))
        rule.onNodeWithContentDescriptionText(context.getString(L10nR.string.actionBack))
        assertEquals(listOf(NewsAction.Back), actions)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithContentDescriptionText(text: String) {
        onNode(androidx.compose.ui.test.hasContentDescription(text)).performClick()
    }
}
