package space.einundzwanzig.meetup.feature.meetups

import android.app.Application
import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation3.runtime.result.LocalResultEventBus
import androidx.navigation3.runtime.result.ResultEventBus
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import space.einundzwanzig.meetup.core.map.LocalOsmTileRendering
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.feature.meetups.common.LinkFailure
import space.einundzwanzig.meetup.feature.meetups.common.LinkOpener
import space.einundzwanzig.meetup.feature.meetups.common.rememberLinkOpener
import space.einundzwanzig.meetup.feature.meetups.details.MeetupDetailsRoute
import space.einundzwanzig.meetup.feature.meetups.details.MeetupDetailsTags
import space.einundzwanzig.meetup.feature.meetups.details.MeetupDetailsViewModel
import space.einundzwanzig.meetup.feature.meetups.web.CommunityPortalRoute
import space.einundzwanzig.meetup.feature.meetups.web.CommunityPortalTags
import space.einundzwanzig.meetup.feature.meetups.web.PortalWebUrls
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Failure paths of [LinkOpener]: what each screen shows when a link cannot be opened. */
@RunWith(AndroidJUnit4::class)
class LinkFailureTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefix get() = context.getString(L10nR.string.errorOpenLink)

    @Before
    fun noBrowser() {
        // No activity resolves VIEW intents: startActivity throws ActivityNotFoundException.
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).checkActivities(true)
    }

    private fun show(content: @Composable () -> Unit) = rule.setContent {
        EinundzwanzigTheme {
            CompositionLocalProvider(
                LocalNavigator provides RecordingNavigator(),
                LocalResultEventBus provides ResultEventBus(),
                LocalOsmTileRendering provides false,
            ) { content() }
        }
    }

    private fun openWith(url: String, failure: LinkFailure) {
        lateinit var opener: LinkOpener
        show {
            val host = remember { SnackbarHostState() }
            opener = rememberLinkOpener(host)
            AppSnackbarHost(host)
        }
        rule.runOnIdle { opener.open(url, failure) }
        rule.waitForIdle()
    }

    @Test
    fun `refused link shows the plain errorOpenLink snackbar`() {
        openWith("ftp://x", LinkFailure.Snackbar)
        rule.onNodeWithText(prefix).assertExists()
    }

    @Test
    fun `refused link shows errorOpenLink with the url`() {
        openWith("ftp://x", LinkFailure.SnackbarWithUrl)
        rule.onNodeWithText("$prefix: ftp://x").assertExists()
    }

    @Test
    fun `refused link stays silent`() {
        openWith("ftp://x", LinkFailure.Silent)
        rule.onNodeWithText(prefix, substring = true).assertDoesNotExist()
    }

    @Test
    fun `community portal shows the failing url`() {
        show { CommunityPortalRoute() }
        rule.onNodeWithTag(CommunityPortalTags.link(PortalWebUrls.STATUTES)).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("$prefix: ${PortalWebUrls.STATUTES}").assertExists()
    }

    private val goettingen = Meetup(
        id = "107",
        city = "Göttingen",
        country = "de",
        telegramLink = "t.me/e21_goe",
        lat = 51.5,
        lng = 9.9,
        nostrNpub = "npub1qv02xpsc3lhxxx5x7xswf88w3u7kykft9ea7t78tz7ywxf7mxs9qrxujnc",
    )

    private fun details() {
        val vm = MeetupDetailsViewModel("107", FakeMeetupService(listOf(goettingen)), FakeCalendarService(), FixedTime())
        show { MeetupDetailsRoute("107", vm) }
        rule.waitForIdle()
    }

    @Test
    fun `details link failure shows the plain errorOpenLink`() {
        details()
        rule.onNodeWithTag(MeetupDetailsTags.TELEGRAM).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText(prefix).assertExists()
    }

    @Test
    fun `details nostr failure stays silent`() {
        details()
        rule.onNodeWithTag(MeetupDetailsTags.NOSTR).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText(prefix, substring = true).assertDoesNotExist()
    }
}
