package space.einundzwanzig.meetup.feature.news

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.news.ArticleLikes
import space.einundzwanzig.meetup.data.news.NewsLinks
import space.einundzwanzig.meetup.feature.news.detail.ArticleDetailAction
import space.einundzwanzig.meetup.feature.news.detail.ArticleDetailScreen
import space.einundzwanzig.meetup.feature.news.detail.ArticleDetailTestTags
import space.einundzwanzig.meetup.feature.news.detail.ArticleDetailUiState
import space.einundzwanzig.meetup.core.l10n.R as L10nR

@RunWith(AndroidJUnit4::class)
class ArticleDetailScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val actions = mutableListOf<ArticleDetailAction>()
    private val a = article("a", title = "Halving")

    private fun loaded(
        fullContent: String? = "## Abschnitt\n\nText mit **fett**.",
        likes: ArticleLikes = ArticleLikes.EMPTY,
        likesLoaded: Boolean = true,
        article: space.einundzwanzig.meetup.data.news.NewsArticle = a,
    ) = ArticleDetailUiState(
        resolving = false,
        article = article,
        contentLoading = false,
        fullContent = fullContent,
        likes = likes,
        likesLoaded = likesLoaded,
    )

    private fun show(state: ArticleDetailUiState) {
        rule.setContent { EinundzwanzigTheme { ArticleDetailScreen(state, onAction = { actions += it }) } }
    }

    @Test
    fun `while loading shows summary and indicator`() {
        show(ArticleDetailUiState(resolving = false, article = a, contentLoading = true))
        rule.onNodeWithText("Halving").assertExists()
        rule.onNodeWithText("Zusammenfassung a").assertExists()
        rule.onNodeWithTag(ArticleDetailTestTags.CONTENT_LOADING).assertExists()
    }

    @Test
    fun `full text renders as markdown`() {
        show(loaded())
        rule.onNodeWithText("Abschnitt").assertExists()
        rule.onNodeWithText("Zusammenfassung a").assertDoesNotExist()
    }

    @Test
    fun `without full text the website button opens the article url`() {
        show(loaded(fullContent = null))
        rule.onNodeWithText("Zusammenfassung a").assertExists()
        rule.onNodeWithText("Auf der Webseite lesen").performScrollTo().performClick()
        assertEquals(listOf(ArticleDetailAction.OpenUrl(a.link)), actions)
    }

    @Test
    fun `non-url link falls back to the news website`() {
        show(loaded(fullContent = "", article = article("g", link = "30023:abc:g")))
        rule.onNodeWithTag(ArticleDetailTestTags.READ_ON_WEBSITE).performScrollTo().performClick()
        assertEquals(listOf(ArticleDetailAction.OpenUrl(NewsLinks.WEBSITE_URL)), actions)
    }

    @Test
    fun `like label shows count once loaded`() {
        show(loaded(likes = ArticleLikes(7, false)))
        rule.onNodeWithText("7").assertExists()
        rule.onNodeWithText(context.getString(L10nR.string.newsLike)).assertDoesNotExist()
    }

    @Test
    fun `like label falls back to newsLike and sends Like`() {
        show(loaded(likesLoaded = false))
        rule.onNodeWithText(context.getString(L10nR.string.newsLike)).performScrollTo().performClick()
        assertEquals(listOf<ArticleDetailAction>(ArticleDetailAction.Like), actions)
    }

    @Test
    fun `like button is disabled while liking`() {
        show(loaded().copy(liking = true))
        rule.onNodeWithTag(ArticleDetailTestTags.LIKE).assertIsNotEnabled()
    }

    @Test
    fun `liked article shows the filled heart in the checked state`() {
        show(loaded(likes = ArticleLikes(4, true)))
        rule.onNodeWithTag(ArticleDetailTestTags.LIKE).assertIsOn()
        rule.onNodeWithContentDescription(context.getString(R.string.native_news_liked)).assertExists()
    }

    @Test
    fun `not liked article shows the outline heart`() {
        show(loaded(likes = ArticleLikes(4, false)))
        rule.onNodeWithTag(ArticleDetailTestTags.LIKE).assertIsOff()
        rule.onNodeWithContentDescription(context.getString(R.string.native_news_liked)).assertDoesNotExist()
    }

    @Test
    fun `zap is disabled for an article without nip-23 author key`() {
        show(loaded(article = article("l", id = "https://media.einundzwanzig.space/a/l")))
        rule.onNodeWithTag(ArticleDetailTestTags.ZAP).assertIsNotEnabled()
        rule.onNodeWithTag(ArticleDetailTestTags.SHARE).assertIsEnabled()
    }

    @Test
    fun `zap and share buttons send their actions`() {
        show(loaded())
        rule.onNodeWithTag(ArticleDetailTestTags.ZAP).performScrollTo().performClick()
        rule.onNodeWithTag(ArticleDetailTestTags.SHARE).performScrollTo().performClick()
        assertEquals(listOf(ArticleDetailAction.Zap, ArticleDetailAction.Share), actions)
    }

    @Test
    fun `zap amount sheet offers the four amounts`() {
        show(loaded().copy(zapSheetVisible = true))
        rule.onNodeWithText(context.getString(L10nR.string.newsZapTitle)).assertExists()
        listOf("21 sat", "210 sat", "2100 sat", "21000 sat").forEach {
            rule.onNode(hasText(it)).assertExists()
        }
        rule.onNodeWithText("210 sat").performClick()
        rule.waitForIdle()
        assertEquals(listOf<ArticleDetailAction>(ArticleDetailAction.ZapAmount(210)), actions)
    }

    @Test
    fun `no wallet sheet shows the invoice and copies it`() {
        show(loaded().copy(fallbackInvoice = "lnbc21n1invoice"))
        rule.onNodeWithText(context.getString(L10nR.string.newsZapNoWallet)).assertExists()
        rule.onNodeWithText("lnbc21n1invoice").assertExists()
        rule.onNodeWithText(context.getString(L10nR.string.newsZapCopyInvoice)).performClick()
        rule.waitForIdle()
        assertEquals(listOf<ArticleDetailAction>(ArticleDetailAction.CopyInvoice("lnbc21n1invoice")), actions)
    }

    @Test
    fun `unknown article shows not found with back action`() {
        show(ArticleDetailUiState(resolving = false, article = null))
        rule.onNodeWithText(context.getString(R.string.native_news_article_not_found_title)).assertExists()
        rule.onNodeWithText(context.getString(L10nR.string.actionBack)).performClick()
        assertEquals(listOf<ArticleDetailAction>(ArticleDetailAction.Back), actions)
    }
}
