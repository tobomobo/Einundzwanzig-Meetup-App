package space.einundzwanzig.meetup.feature.news

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.LinkAnnotation
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.Navigator
import space.einundzwanzig.meetup.core.navigation.NewsArticleDetails
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.news.NewsArticles
import space.einundzwanzig.meetup.feature.news.detail.ArticleDetailAction
import space.einundzwanzig.meetup.feature.news.detail.ArticleDetailRoute
import space.einundzwanzig.meetup.feature.news.detail.ArticleDetailTestTags
import space.einundzwanzig.meetup.feature.news.detail.ArticleDetailViewModel
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Route-level flows of spec 11 §2.4: share fallback, wallet handoff, Markdown links. */
@RunWith(AndroidJUnit4::class)
class ArticleDetailRouteTest {

    @get:Rule
    val rule = createComposeRule()

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val a = article("a", title = "Halving")
    private val store = NewsArticleStore().apply { update(listOf(a)) }
    private val navigator = RecordingNavigator()

    /** A context where no activity can be started: no share target, no `lightning:` wallet. */
    private class NoTargetContext(base: Context) : ContextWrapper(base) {
        override fun startActivity(intent: Intent?) = throw ActivityNotFoundException("none")
        override fun startActivity(intent: Intent?, options: Bundle?) = throw ActivityNotFoundException("none")
    }

    private fun show(content: String? = "## Text"): ArticleDetailViewModel {
        val vm = ArticleDetailViewModel(
            NewsArticleDetails(a.id, 0),
            store,
            FakeNewsService(content = content),
            FakeReactions(),
            FakeZaps(),
            AppLogger,
        )
        rule.setContent {
            val base = LocalContext.current
            val context = remember(base) { NoTargetContext(base) }
            CompositionLocalProvider(LocalContext provides context, LocalNavigator provides navigator) {
                EinundzwanzigTheme { ArticleDetailRoute(NewsArticleDetails(a.id, 0), vm) }
            }
        }
        return vm
    }

    @Test
    fun `share without a target copies the text and shows newsShareCopied`() {
        show()
        rule.onNodeWithTag(ArticleDetailTestTags.SHARE).performScrollTo().performClick()
        rule.waitForIdle()
        val clip = app.getSystemService(ClipboardManager::class.java).primaryClip
        assertEquals(NewsArticles.shareText(a), clip?.getItemAt(0)?.text?.toString())
        rule.onNodeWithText(app.getString(L10nR.string.newsShareCopied)).assertExists()
    }

    @Test
    fun `invoice without a wallet opens the copy sheet`() {
        val vm = show()
        rule.runOnIdle { vm.onAction(ArticleDetailAction.ZapAmount(21)) }
        rule.waitForIdle()
        rule.onNodeWithTag(ArticleDetailTestTags.INVOICE_SHEET).assertExists()
        rule.onNodeWithText("lnbc1invoice").assertExists()
    }

    @Test
    fun `markdown links open through the navigator`() {
        show(content = "Mehr [hier](https://einundzwanzig.space)")
        rule.onNodeWithText("Mehr", substring = true)
            .performFirstLinkClick { (it.item as? LinkAnnotation.Clickable)?.tag == "https://einundzwanzig.space" }
        rule.waitForIdle()
        assertEquals(listOf("https://einundzwanzig.space"), navigator.openedUrls)
    }
}

private class RecordingNavigator : Navigator {
    val openedUrls = mutableListOf<String>()
    override fun navigate(key: NavKey) = Unit
    override fun replaceTop(key: NavKey) = Unit
    override fun back() = Unit
    override fun backTo(key: NavKey, inclusive: Boolean) = Unit
    override fun resetTo(key: NavKey) = Unit
    override fun navigateFromRoot(key: NavKey) = Unit
    override fun openUrl(url: String) {
        openedUrls += url
    }
}
