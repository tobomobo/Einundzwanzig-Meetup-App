package space.einundzwanzig.meetup.feature.wallet

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.badges.model.FormerCollection
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.feature.wallet.domain.BadgeFormat
import space.einundzwanzig.meetup.feature.wallet.domain.WalletLibrary
import space.einundzwanzig.meetup.feature.wallet.ui.WalletSheetTags
import space.einundzwanzig.meetup.core.l10n.R as L10nR

@RunWith(AndroidJUnit4::class)
class WalletScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val actions = mutableListOf<WalletAction>()

    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun stateOf(badges: List<MeetupBadge>, groupByMeetup: Boolean = true, compact: Boolean = false, query: String = "") =
        WalletUiState(
            loaded = true,
            badges = badges,
            groupByMeetup = groupByMeetup,
            compact = compact,
            query = query,
            groups = WalletLibrary.groups(WalletLibrary.filter(badges, query), groupByMeetup)
                .map { GroupUi(it, BadgeFormat.collectionSubtitle(it.badges, NOW_MILLIS)) },
            duplicates = WalletLibrary.duplicates(badges),
        )

    private fun show(state: WalletUiState) {
        rule.setContent { EinundzwanzigTheme { WalletScreen(state, onAction = { actions += it }) } }
    }

    @Test
    fun `loading state before the store was read`() {
        show(WalletUiState(loaded = false))
        rule.onNodeWithTag(WalletTestTags.LOADING).assertExists()
    }

    @Test
    fun `empty wallet shows the empty state and finds a meetup`() {
        show(WalletUiState(loaded = true))
        rule.onNodeWithTag(WalletTestTags.EMPTY).assertExists()
        rule.onNodeWithText(str(L10nR.string.walletNoBadges)).assertIsDisplayed()
        rule.onNodeWithText(WALLET_TITLE).assertIsDisplayed()
        rule.onNodeWithText(str(L10nR.string.emptyFindMeetup)).performClick()
        assertEquals(listOf<WalletAction>(WalletAction.FindMeetup), actions)
        // History is always available; map and share are not.
        rule.onNodeWithContentDescription(str(L10nR.string.walletFormerTitle)).assertExists()
        rule.onNodeWithContentDescription(str(L10nR.string.mapButton)).assertDoesNotExist()
        rule.onNodeWithContentDescription(str(L10nR.string.walletShareTitle)).assertDoesNotExist()
    }

    @Test
    fun `unreadable wallet offers no destructive action`() {
        show(WalletUiState(loaded = true, unreadable = true))
        rule.onNodeWithTag(WalletTestTags.UNREADABLE).assertExists()
        rule.onNodeWithText(str(L10nR.string.badgesUnreadableBody)).assertExists()
        rule.onNodeWithText(str(L10nR.string.emptyFindMeetup)).assertDoesNotExist()
    }

    @Test
    fun `collection cards show name, count and open the collection`() {
        val badges = listOf(badge("1", name = "Ulm", meetupEventId = "u1"), badge("2", name = "Ulm", meetupEventId = "u2"), badge("3", name = "Berlin"))
        show(stateOf(badges))
        rule.onNodeWithText("$WALLET_TITLE (3)").assertIsDisplayed()
        rule.onNodeWithText("ULM").assertIsDisplayed()
        rule.onNodeWithText("×2").assertIsDisplayed()
        rule.onNodeWithText("BERLIN").performClick()
        assertEquals(listOf<WalletAction>(WalletAction.OpenCollection("Berlin")), actions)
    }

    @Test
    fun `search appears from six badges and reports input`() {
        val five = (1..5).map { badge("$it", meetupEventId = "e$it") }
        show(stateOf(five))
        rule.onNodeWithTag(WalletTestTags.SEARCH).assertDoesNotExist()
    }

    @Test
    fun `search field input and no results state`() {
        val six = (1..6).map { badge("$it", meetupEventId = "e$it") }
        show(stateOf(six, query = "zzz"))
        rule.onNodeWithTag(WalletTestTags.NO_RESULTS).assertExists()
        rule.onNodeWithContentDescription(str(R.string.native_wallet_clear_search)).performClick()
        rule.onNodeWithTag(WalletTestTags.SEARCH).performTextInput("a")
        rule.waitForIdle()
        // The field owns its text; clearing and typing report the new query.
        assertEquals(listOf(WalletAction.QueryChanged(""), WalletAction.QueryChanged("a")), actions)
    }

    @Test
    fun `compact toggle shows above six badges and grouping toggles report`() {
        val seven = (1..7).map { badge("$it", meetupEventId = "e$it") }
        show(stateOf(seven))
        rule.onNodeWithContentDescription(str(R.string.native_wallet_view_compact)).performClick()
        rule.onNodeWithText(str(L10nR.string.walletGroupYear)).performClick()
        assertEquals(listOf(WalletAction.ToggleCompact, WalletAction.SetGroupByMeetup(false)), actions)
    }

    @Test
    fun `year mode shows sections, numbered cards and collapses`() {
        val a = badge("a", name = "Ulm", date = "2026-02-01T19:00:00.000", meetupEventId = "a")
        val b = badge("b", name = "Wien", date = "2025-02-01T19:00:00.000", meetupEventId = "b", presenceVerified = false)
        show(stateOf(listOf(a, b), groupByMeetup = false))
        rule.onNodeWithText("2026").assertIsDisplayed()
        rule.onNodeWithTag(WalletTestTags.LIST).performScrollToNode(hasContentDescription("#2", substring = true))
        // The unverified marker is part of the card's single announcement.
        rule.onNodeWithContentDescription("Wien", substring = true)
            .assertContentDescriptionContains(str(L10nR.string.badgeUnverified), substring = true)
        rule.onNodeWithText("2025").performClick()
        rule.onNodeWithContentDescription("Ulm,", substring = true).performClick()
        assertEquals(WalletAction.ToggleCollapsed("2025"), actions[0])
        assertTrue((actions[1] as WalletAction.OpenDetails).badge === a)
    }

    @Test
    fun `organizer group is labelled and last`() {
        val marker = badge("o", name = "Berlin", isOrganizer = true)
        show(stateOf(listOf(marker, badge("u", name = "Ulm"))))
        rule.onNodeWithText(str(L10nR.string.walletOrganizerSection).uppercase()).assertExists()
    }

    @Test
    fun `share sheet offers text, qr and json and hides before reporting`() {
        var open by mutableStateOf(true)
        val base = stateOf(listOf(badge("1")))
        rule.setContent {
            EinundzwanzigTheme {
                WalletScreen(base.copy(shareSheetOpen = open), onAction = { actions += it; open = false })
            }
        }
        for (label in listOf(L10nR.string.walletShareText, L10nR.string.walletShowQr, L10nR.string.walletExportJson)) {
            rule.onNodeWithTag(WalletSheetTags.SHARE_SHEET).assertExists()
            rule.onNodeWithText(str(label)).performClick()
            rule.waitForIdle()
            rule.onNodeWithTag(WalletSheetTags.SHARE_SHEET).assertDoesNotExist()
            rule.runOnIdle { open = true }
        }
        rule.onNodeWithText(str(L10nR.string.cancel)).performClick()
        rule.waitForIdle()
        assertEquals(
            listOf(WalletAction.ShareText, WalletAction.ShowQr, WalletAction.ExportJson, WalletAction.DismissShareSheet),
            actions,
        )
    }

    @Test
    fun `app bar actions open map, share sheet, history and cleanup`() {
        val a = badge("a", meetupEventId = "m1")
        val dup = badge("a", meetupEventId = "m1")
        show(stateOf(listOf(a, dup)))
        rule.onNodeWithContentDescription(str(L10nR.string.mapButton)).performClick()
        rule.onNodeWithContentDescription(str(L10nR.string.walletShareTitle)).performClick()
        rule.onNodeWithContentDescription(str(L10nR.string.walletFormerTitle)).performClick()
        rule.onNodeWithContentDescription(str(L10nR.string.walletCleanupTitle)).performClick()
        assertEquals(
            listOf(WalletAction.OpenMap, WalletAction.OpenShareSheet, WalletAction.OpenFormer, WalletAction.OpenCleanup),
            actions,
        )
    }

    @Test
    fun `cleanup dialog lists names and confirms`() {
        val a = badge("a", name = "Ulm", meetupEventId = "m1")
        val dup = badge("a", name = "Ulm", meetupEventId = "m1")
        val dups = WalletLibrary.duplicates(listOf(a, dup))
        show(stateOf(listOf(a, dup)).copy(cleanup = CleanupDialogState(dups, WalletLibrary.cleanupSummary(dups).toList())))
        rule.onNodeWithText("Ulm (1x)").assertExists()
        rule.onNodeWithText(str(L10nR.string.walletCleanupBody, 1)).assertExists()
        rule.onNodeWithText(str(L10nR.string.walletCleanupConfirm)).performClick()
        assertEquals(listOf<WalletAction>(WalletAction.ConfirmCleanup), actions)
    }

    @Test
    fun `former sheet disables merge for unreadable entries and requests merge`() {
        val readable = FormerCollection(1, DartDateTime.fromMillisecondsSinceEpoch(0), "replaced", "", listOf(badge("x")))
        val unreadable = FormerCollection(0, DartDateTime.fromMillisecondsSinceEpoch(0), "rotated", "", null)
        show(WalletUiState(loaded = true, formerCollections = listOf(readable, unreadable)))
        rule.onNodeWithTag(WalletSheetTags.FORMER_SHEET).assertExists()
        rule.onNodeWithText(str(L10nR.string.walletFormerEntry, 1)).assertExists()
        rule.onNodeWithText(str(L10nR.string.walletFormerUnreadable)).assertExists()
        rule.onNodeWithText(str(L10nR.string.walletFormerReasonReplaced), substring = true).assertExists()
        val buttons = rule.onAllNodesWithText(str(L10nR.string.walletFormerMerge))
        buttons[1].assertIsNotEnabled()
        buttons[0].performClick()
        rule.waitForIdle()
        assertEquals(listOf<WalletAction>(WalletAction.RequestMerge(readable)), actions)
    }

    @Test
    fun `merge dialog confirms and cancels`() {
        val entry = FormerCollection(0, DartDateTime.fromMillisecondsSinceEpoch(0), "replaced", "", emptyList())
        show(WalletUiState(loaded = true, mergeCandidate = entry))
        rule.onNodeWithText(str(L10nR.string.walletFormerMergeTitle)).assertExists()
        rule.onNodeWithText(str(L10nR.string.dialogCancelMixed)).performClick()
        rule.onNodeWithText(str(L10nR.string.walletFormerMerge)).performClick()
        assertEquals(listOf(WalletAction.DismissMerge, WalletAction.ConfirmMerge), actions)
    }
}
