package space.einundzwanzig.meetup.feature.meetups

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.map.LatLng
import space.einundzwanzig.meetup.core.map.LocalOsmTileRendering
import space.einundzwanzig.meetup.core.map.rememberLocationPickerCameraState
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.feature.meetups.details.MeetupDetailsAction
import space.einundzwanzig.meetup.feature.meetups.details.MeetupDetailsScreen
import space.einundzwanzig.meetup.feature.meetups.details.MeetupDetailsTags
import space.einundzwanzig.meetup.feature.meetups.details.MeetupDetailsUiState
import space.einundzwanzig.meetup.feature.meetups.picker.LocationPickerAction
import space.einundzwanzig.meetup.feature.meetups.picker.LocationPickerScreen
import space.einundzwanzig.meetup.feature.meetups.picker.LocationPickerTags
import space.einundzwanzig.meetup.feature.meetups.picker.LocationPickerUiState
import space.einundzwanzig.meetup.feature.meetups.selection.MeetupSelectionAction
import space.einundzwanzig.meetup.feature.meetups.selection.MeetupSelectionScreen
import space.einundzwanzig.meetup.feature.meetups.selection.MeetupSelectionTags
import space.einundzwanzig.meetup.feature.meetups.selection.MeetupSelectionUiState
import space.einundzwanzig.meetup.feature.meetups.web.CommunityPortalScreen
import space.einundzwanzig.meetup.feature.meetups.web.CommunityPortalTags
import space.einundzwanzig.meetup.feature.meetups.web.PortalWebUrls
import java.time.Instant
import java.time.ZoneId
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Robolectric UI tests of the details, favorites picker, location picker and portal web hub. */
@RunWith(AndroidJUnit4::class)
class MeetupScreensTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun show(content: @Composable () -> Unit) = rule.setContent {
        EinundzwanzigTheme { CompositionLocalProvider(LocalOsmTileRendering provides false) { content() } }
    }

    // ---- Meetup details -----------------------------------------------------------------------

    private val zone = ZoneId.of("Europe/Berlin")
    private val goettingen = Meetup(
        id = "107",
        name = "Einundzwanzig Südniedersachsen",
        city = "Göttingen",
        country = "de",
        telegramLink = "t.me/e21_goe",
        lat = 51.5413,
        lng = 9.9,
        twitterUsername = "@e21goe",
        nostrNpub = " npub1qv02xpsc3lhxxx5x7xswf88w3u7kykft9ea7t78tz7ywxf7mxs9qrxujnc",
    )
    private val date = MeetupDate("Stammtisch Göttingen", "Bitcoin & Bier", "Dinx Diner", Instant.parse("2026-07-21T16:00:00Z"), "")

    @Test
    fun `details show header, dates and links`() {
        val actions = mutableListOf<MeetupDetailsAction>()
        val state = MeetupDetailsUiState(resolving = false, meetup = goettingen, datesLoading = false, dates = listOf(date), zone = zone)
        show { MeetupDetailsScreen(state, SnackbarHostState()) { actions += it } }
        rule.onNodeWithText("GÖTTINGEN").assertExists()
        rule.onNodeWithText("DE").assertExists()
        rule.onNodeWithText("Di, 21.07. 18:00").assertExists()
        rule.onNodeWithText("Dinx Diner").assertExists()
        rule.onNodeWithText("@@e21goe").assertExists()
        rule.onNodeWithTag(MeetupDetailsTags.TELEGRAM).performScrollTo().performClick()
        rule.onNodeWithTag(MeetupDetailsTags.TWITTER).performScrollTo().performClick()
        rule.onNodeWithTag(MeetupDetailsTags.NOSTR).performScrollTo().performClick()
        rule.onNodeWithText("51.5413, 9.9000").performScrollTo().assertExists()
        rule.onNodeWithTag(MeetupDetailsTags.ROUTE).performScrollTo().performClick()
        rule.onNodeWithContentDescription(s(R.string.native_meetups_open_community_link)).performClick()
        rule.onNodeWithTag(MeetupDetailsTags.date(0)).performScrollTo().performClick()
        assertEquals(
            listOf(
                MeetupDetailsAction.OpenLink("https://t.me/e21_goe"),
                MeetupDetailsAction.OpenLink("https://twitter.com/e21goe"),
                MeetupDetailsAction.OpenNostr(goettingen.nostrNpub),
                MeetupDetailsAction.OpenLink("https://www.google.com/maps/search/?api=1&query=51.5413,9.9"),
                MeetupDetailsAction.OpenLink("https://t.me/e21_goe"),
                MeetupDetailsAction.OpenDate(0),
            ),
            actions,
        )
    }

    @Test
    fun `date sheet shows time with Uhr and description`() {
        val state = MeetupDetailsUiState(
            resolving = false,
            meetup = goettingen,
            datesLoading = false,
            dates = listOf(date),
            openDate = 0,
            zone = zone,
        )
        show { MeetupDetailsScreen(state, SnackbarHostState()) {} }
        rule.onNodeWithTag(MeetupDetailsTags.SHEET).assertExists()
        rule.onNodeWithText("21.07.2026, 18:00 Uhr").assertExists()
        rule.onNodeWithText("Bitcoin & Bier").assertExists()
        rule.onNodeWithText(s(L10nR.string.sectionDescription)).assertExists()
    }

    @Test
    fun `details empty dates and not found`() {
        show {
            MeetupDetailsScreen(
                MeetupDetailsUiState(resolving = false, meetup = goettingen.copy(telegramLink = ""), datesLoading = false),
                SnackbarHostState(),
            ) {}
        }
        rule.onNodeWithText(s(L10nR.string.meetupNoDatesCal)).assertExists()
        rule.onNodeWithContentDescription(s(R.string.native_meetups_open_community_link)).assertDoesNotExist()
    }

    @Test
    fun `unknown meetup shows not found`() {
        show { MeetupDetailsScreen(MeetupDetailsUiState(resolving = false), SnackbarHostState()) {} }
        rule.onNodeWithText(s(R.string.native_meetups_not_found_title)).assertExists()
    }

    // ---- Favorites picker ---------------------------------------------------------------------

    private val meetups = listOf(
        meetup("107", "Göttingen", "Einundzwanzig Südniedersachsen"),
        meetup("5", "Wien", country = "AT"),
    )

    @Test
    fun `selection shows count, rows and the save label`() {
        val actions = mutableListOf<MeetupSelectionAction>()
        show {
            MeetupSelectionScreen(MeetupSelectionUiState(loading = false, meetups = meetups, selected = listOf("107")), SnackbarHostState()) {
                actions += it
            }
        }
        rule.onNodeWithText(s(L10nR.string.msSelectMeetup)).assertExists()
        rule.onNodeWithTag(MeetupSelectionTags.COUNT).assertTextEquals("1")
        rule.onNodeWithText("GÖTTINGEN").assertExists()
        rule.onNodeWithText("Einundzwanzig Südniedersachsen").assertExists()
        rule.onNodeWithText(s(L10nR.string.msFavoritesHint)).assertExists()
        rule.onNodeWithText(s(L10nR.string.msSaveFavorites, 1)).assertExists()
        rule.onNodeWithTag(MeetupSelectionTags.row("5")).performClick()
        rule.onNodeWithTag(MeetupSelectionTags.SEARCH).performTextInput("wi")
        rule.onNodeWithTag(MeetupSelectionTags.SAVE).performClick()
        assertEquals(
            listOf(MeetupSelectionAction.Toggle("5"), MeetupSelectionAction.QueryChanged("wi"), MeetupSelectionAction.Save),
            actions,
        )
    }

    @Test
    fun `selection without favorites offers saving none`() {
        show { MeetupSelectionScreen(MeetupSelectionUiState(loading = false, meetups = meetups), SnackbarHostState()) {} }
        rule.onNodeWithText(s(L10nR.string.msSaveNone)).assertExists()
    }

    @Test
    fun `selection load failure offers retry and no save`() {
        val actions = mutableListOf<MeetupSelectionAction>()
        show { MeetupSelectionScreen(MeetupSelectionUiState(loading = false, loadFailed = true), SnackbarHostState()) { actions += it } }
        rule.onNodeWithText(s(R.string.native_meetups_load_failed)).assertExists()
        rule.onNodeWithTag(MeetupSelectionTags.SAVE).assertDoesNotExist()
        rule.onNodeWithText(s(L10nR.string.actionRetry)).performClick()
        assertEquals(listOf<MeetupSelectionAction>(MeetupSelectionAction.Retry), actions)
    }

    // ---- Location picker ----------------------------------------------------------------------

    @Test
    fun `picker without pick shows the hint and a disabled confirm`() {
        show {
            LocationPickerScreen(LocationPickerUiState(), rememberLocationPickerCameraState(null), SnackbarHostState()) {}
        }
        rule.onNodeWithText(s(L10nR.string.locPickTitle)).assertExists()
        rule.onNodeWithText(s(L10nR.string.locPickHint)).assertExists()
        rule.onNodeWithTag(LocationPickerTags.CONFIRM).assertIsNotEnabled()
        rule.onNodeWithTag(LocationPickerTags.COORDS).assertDoesNotExist()
    }

    @Test
    fun `picker with pick shows coordinates and confirms`() {
        val actions = mutableListOf<LocationPickerAction>()
        val pick = LatLng(48.1351, 11.582)
        show {
            LocationPickerScreen(LocationPickerUiState(pick = pick), rememberLocationPickerCameraState(pick), SnackbarHostState()) {
                actions += it
            }
        }
        rule.onNodeWithText(s(L10nR.string.locPickHintDone)).assertExists()
        rule.onNodeWithText("48.13510, 11.58200").assertExists()
        rule.onNodeWithTag(LocationPickerTags.CONFIRM).assertIsEnabled().performClick()
        rule.onNodeWithContentDescription(s(L10nR.string.locPickJumpToMe)).performClick()
        assertEquals(listOf(LocationPickerAction.Confirm, LocationPickerAction.JumpToMe), actions)
    }

    @Test
    fun `tapping the map picks a point`() {
        val actions = mutableListOf<LocationPickerAction>()
        show {
            LocationPickerScreen(LocationPickerUiState(), rememberLocationPickerCameraState(null), SnackbarHostState()) { actions += it }
        }
        rule.onNodeWithTag(LocationPickerTags.MAP).performClick()
        assertEquals(1, actions.count { it is LocationPickerAction.Pick })
    }

    // ---- Portal web hub -----------------------------------------------------------------------

    @Test
    fun `portal web hub opens its links`() {
        val opened = mutableListOf<String>()
        var back = 0
        show { CommunityPortalScreen(SnackbarHostState(), onBack = { back++ }, onOpen = { opened += it }) }
        rule.onNodeWithText(s(L10nR.string.portalQuickAccess)).assertExists()
        rule.onNodeWithText(s(L10nR.string.portalMeetupMap)).performClick()
        rule.onNodeWithContentDescription(s(L10nR.string.portalOpen)).performClick()
        rule.onNodeWithTag(CommunityPortalTags.link(PortalWebUrls.STATUTES)).performScrollTo().performClick()
        rule.onNodeWithTag(CommunityPortalTags.link(PortalWebUrls.YOUTUBE)).performScrollTo().performClick()
        rule.onNodeWithTag(CommunityPortalTags.link(PortalWebUrls.NOSTR)).performScrollTo().performClick()
        rule.onNodeWithTag(CommunityPortalTags.link(PortalWebUrls.SITE)).performScrollTo().performClick()
        rule.onNodeWithText(s(L10nR.string.portalTagline)).assertExists()
        rule.onNodeWithContentDescription(s(L10nR.string.actionBack)).performClick()
        assertEquals(
            listOf(
                PortalWebUrls.MEETUPS,
                PortalWebUrls.PORTAL,
                PortalWebUrls.STATUTES,
                PortalWebUrls.YOUTUBE,
                PortalWebUrls.NOSTR,
                PortalWebUrls.SITE,
            ),
            opened,
        )
        assertEquals(1, back)
    }
}
