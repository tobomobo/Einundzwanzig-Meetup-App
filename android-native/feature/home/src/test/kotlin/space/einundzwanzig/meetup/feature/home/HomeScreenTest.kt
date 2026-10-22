package space.einundzwanzig.meetup.feature.home

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.feature.home.tiles.TileId
import space.einundzwanzig.meetup.feature.home.ui.HomeScreen
import space.einundzwanzig.meetup.feature.home.ui.HomeTags
import space.einundzwanzig.meetup.core.l10n.R as L10nR

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "de-w411dp-h1400dp")
class HomeScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val actions = mutableListOf<HomeAction>()

    private val base = HomeUiState(nickname = "Satoshi", scorePill = "12.4", reputationValue = "12.4", layoutLoaded = true)

    private fun show(state: HomeUiState) {
        rule.setContent { EinundzwanzigTheme { HomeScreen(state, onAction = { actions += it }) } }
    }

    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    @Test
    fun `header shows nickname, score and logo bar actions`() {
        show(base)
        rule.onNodeWithText("Satoshi").assertIsDisplayed()
        rule.onNodeWithTag(HomeTags.SCORE_PILL).assertIsDisplayed()
        rule.onNodeWithContentDescription(context.getString(R.string.native_home_score_cd, "12.4")).assertExists()
        rule.onNodeWithTag(HomeTags.REFRESH).performClick()
        rule.onNodeWithTag(HomeTags.GLOSSARY).performClick()
        rule.onNodeWithTag(HomeTags.SETTINGS).performClick()
        rule.onNodeWithTag(HomeTags.SCORE_PILL).performClick()
        assertEquals(
            listOf(HomeAction.Refresh, HomeAction.OpenGlossary, HomeAction.OpenSettings, HomeAction.OpenReputationCard),
            actions,
        )
    }

    @Test
    fun `refreshing replaces the refresh button with an indicator`() {
        show(base.copy(refreshing = true))
        rule.onNodeWithTag(HomeTags.REFRESHING).assertExists()
        rule.onNodeWithTag(HomeTags.REFRESH).assertDoesNotExist()
    }

    @Test
    fun `avatar picks a picture only without a nostr picture`() {
        show(base)
        rule.onNodeWithTag(HomeTags.AVATAR).performClick()
        assertEquals(listOf(HomeAction.PickAvatar), actions)
    }

    @Test
    fun `avatar with nostr picture is not clickable`() {
        show(base.copy(avatarUrl = "https://pic"))
        rule.onNodeWithTag(HomeTags.AVATAR).performClick()
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `no favorite shows the call to action`() {
        show(base)
        rule.onNodeWithText(str(L10nR.string.homeMeetupChoose)).assertIsDisplayed()
        rule.onNodeWithTag(HomeTags.HOME_MEETUP_CTA).performClick()
        rule.onNodeWithTag(HomeTags.HOME_MEETUP_CTA).performTouchInput { longClick() }
        assertEquals(listOf(HomeAction.SelectHomeMeetup, HomeAction.OpenCustomize), actions)
    }

    @Test
    fun `favorite card shows label, countdown, unread chat badge and disabled info`() {
        val card = FavCardUi("107", "Einundzwanzig Wien", "Wien", meetupId = null, country = "AT", daysUntil = 1, dateText = "23.10.2026")
        show(base.copy(hasHome = true, favCards = listOf(card), chatUnread = mapOf("107" to 120)))
        rule.onNodeWithText("EINUNDZWANZIG WIEN").assertIsDisplayed()
        rule.onNodeWithText(str(L10nR.string.homeMeetupTomorrow)).assertIsDisplayed()
        rule.onNodeWithText(" · 23.10.2026").assertIsDisplayed()
        rule.onNodeWithText("99+").assertIsDisplayed()
        rule.onNodeWithText("AT").assertIsDisplayed()
        rule.onNodeWithTag(HomeTags.favAction("107", "events")).performClick()
        rule.onNodeWithTag(HomeTags.favAction("107", "chat")).performClick()
        rule.onNodeWithTag(HomeTags.favAction("107", "info")).performClick()
        assertEquals(listOf(HomeAction.OpenMeetupEvents(card), HomeAction.OpenMeetupChat(card)), actions)
    }

    @Test
    fun `favorite without date and loading countdown`() {
        val card = FavCardUi("108", "Berlin", "Berlin", meetupId = "108")
        show(base.copy(hasHome = true, favCards = listOf(card)))
        rule.onNodeWithText(str(L10nR.string.homeMeetupNoDate)).assertIsDisplayed()
        rule.onNodeWithTag(HomeTags.favAction("108", "info")).performClick()
        assertEquals(listOf(HomeAction.OpenMeetupInfo(card)), actions)
    }

    @Test
    fun `countdown loading shows the wavy progress`() {
        show(base.copy(hasHome = true, favCards = listOf(FavCardUi("1", "A", "A")), countdownLoading = true))
        rule.onNodeWithTag(HomeTags.COUNTDOWN_LOADING).assertExists()
    }

    @Test
    fun `pinned tiles render and react to tap and long press`() {
        show(base.copy(countedBadges = 7, myEventsCount = 3, myEventsUnread = 2))
        rule.onNodeWithText(str(L10nR.string.tileReputationBadges, 7)).assertIsDisplayed()
        rule.onNodeWithText(str(L10nR.string.tileEventChatsUnread, 2)).assertIsDisplayed()
        rule.onNodeWithTag(HomeTags.tile("reputation")).performClick()
        rule.onNodeWithTag(HomeTags.tile("community")).performTouchInput { longClick() }
        assertEquals(listOf(HomeAction.TileTap(TileId.Reputation), HomeAction.TileLongPress(TileId.Community)), actions)
    }

    @Test
    fun `available section is collapsed until toggled`() {
        show(base)
        rule.onNodeWithTag(HomeTags.tile("news")).assertDoesNotExist()
        rule.onNodeWithTag(HomeTags.TILE_AREA).performScrollToNode(hasTestTag(HomeTags.AVAILABLE_HEADER))
        rule.onNodeWithText("6").assertExists()
        rule.onNodeWithTag(HomeTags.AVAILABLE_HEADER).performClick()
        assertEquals(listOf(HomeAction.ToggleAvailable), actions)
    }

    @Test
    fun `expanded available tiles show news counter and headline`() {
        show(base.copy(availableCollapsed = false, unreadNews = 5, latestNewsTitle = "Halving"))
        rule.onNodeWithTag(HomeTags.TILE_AREA).performScrollToNode(hasTestTag(HomeTags.tile("news")))
        rule.onNodeWithText("Halving").assertExists()
        rule.onNodeWithText("5").assertExists()
        rule.onNodeWithTag(HomeTags.tile("news")).performClick()
        assertEquals(listOf(HomeAction.TileTap(TileId.News)), actions)
    }

    @Test
    fun `edit mode shows pin button and toolbar`() {
        show(base.copy(editTileId = "reputation"))
        rule.onNodeWithTag(HomeTags.EDIT_TOOLBAR).assertExists()
        rule.onNodeWithTag(HomeTags.PIN_BUTTON).performClick()
        rule.onNodeWithTag(HomeTags.tile("reputation")).performClick()
        rule.onNodeWithText(context.getString(R.string.native_home_edit_customize)).performClick()
        rule.onNodeWithText(context.getString(R.string.native_home_edit_done)).performClick()
        assertEquals(
            listOf(
                HomeAction.TogglePin(TileId.Reputation),
                HomeAction.CancelEdit,
                HomeAction.OpenCustomize,
                HomeAction.CancelEdit,
            ),
            actions,
        )
    }

    @Test
    fun `non removable tile has no pin button`() {
        show(base.copy(editTileId = "event_chats"))
        rule.onNodeWithTag(HomeTags.PIN_BUTTON).assertDoesNotExist()
    }

    @Test
    fun `device warning can be dismissed and live session opens the rolling QR`() {
        show(base.copy(deviceCompromised = true, session = SessionUi("Wien", isEvent = true, timeLeft = "1h 02m")))
        rule.onNodeWithText(context.getString(R.string.native_home_device_warning)).assertIsDisplayed()
        rule.onNodeWithText(str(L10nR.string.statusLive)).assertIsDisplayed()
        rule.onNodeWithText("1h 02m").assertIsDisplayed()
        rule.onNodeWithTag(HomeTags.LIVE_SESSION).performClick()
        rule.onNodeWithContentDescription(context.getString(R.string.native_home_dismiss_warning)).assertExists()
        rule.onNodeWithTag(HomeTags.INTEGRITY).assertExists()
        assertEquals(listOf(HomeAction.OpenLiveSession), actions)
    }

    @Test
    fun `reset confirmation and backup offer`() {
        show(base.copy(resetStep = ResetStep.CONFIRM))
        rule.onNodeWithText(str(L10nR.string.resetTitle)).assertIsDisplayed()
        rule.onNodeWithText(str(L10nR.string.resetConfirm)).performClick()
        assertEquals(listOf(HomeAction.ResetConfirm), actions)
    }

    @Test
    fun `backup offer has create, skip and cancel`() {
        show(base.copy(resetStep = ResetStep.BACKUP_OFFER))
        rule.onNodeWithText(str(L10nR.string.resetBackupTitle)).assertIsDisplayed()
        rule.onNodeWithText(str(L10nR.string.resetBackupCreate)).performClick()
        rule.onNodeWithText(str(L10nR.string.resetBackupSkip)).performClick()
        rule.onNodeWithText(str(L10nR.string.resetCancel)).performClick()
        assertEquals(listOf(HomeAction.ResetCreateBackup, HomeAction.ResetSkipBackup, HomeAction.ResetCancel), actions)
    }

    @Test
    fun `backup done asks again and running blocks`() {
        show(base.copy(resetStep = ResetStep.BACKUP_DONE))
        rule.onNodeWithText(str(L10nR.string.resetBackupDone)).assertIsDisplayed()
        rule.onNodeWithText(str(L10nR.string.resetNowConfirm)).performClick()
        assertEquals(listOf(HomeAction.ResetNow), actions)
    }

    @Test
    fun `organizer tile only for admins`() {
        show(base.copy(organizerVisible = true))
        rule.onNodeWithTag(HomeTags.TILE_AREA).performScrollToNode(hasTestTag(HomeTags.tile("organisator")))
        rule.onNodeWithText(str(L10nR.string.tileOrganizerPanel)).assertExists()
    }
}
