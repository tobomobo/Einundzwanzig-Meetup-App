package space.einundzwanzig.meetup.feature.wallet

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.map.LocalOsmTileRendering
import space.einundzwanzig.meetup.core.map.rememberMapSnapshotState
import space.einundzwanzig.meetup.core.map.rememberOsmCameraState
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.feature.wallet.collection.CollectionScreen
import space.einundzwanzig.meetup.feature.wallet.collection.CollectionTestTags
import space.einundzwanzig.meetup.feature.wallet.collection.CollectionUiState
import space.einundzwanzig.meetup.feature.wallet.details.BadgeDetailsAction
import space.einundzwanzig.meetup.feature.wallet.details.BadgeDetailsScreen
import space.einundzwanzig.meetup.feature.wallet.details.BadgeDetailsTestTags
import space.einundzwanzig.meetup.feature.wallet.details.BadgeDetailsUiState
import space.einundzwanzig.meetup.feature.wallet.domain.WalletLibrary
import space.einundzwanzig.meetup.feature.wallet.map.BadgeWorldMapScreen
import space.einundzwanzig.meetup.feature.wallet.map.WorldMap
import space.einundzwanzig.meetup.feature.wallet.map.WorldMapAction
import space.einundzwanzig.meetup.feature.wallet.map.WorldMapTestTags
import space.einundzwanzig.meetup.feature.wallet.map.WorldMapUiState
import space.einundzwanzig.meetup.feature.wallet.share.BadgeShareCard
import space.einundzwanzig.meetup.feature.wallet.share.ShareCard
import space.einundzwanzig.meetup.core.l10n.R as L10nR

@RunWith(AndroidJUnit4::class)
class OtherScreensTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    // region Collection

    @Test
    fun `collection shows title, count and numbered cards`() {
        val older = badge("1", name = "Ulm", date = "2025-01-01T19:00:00.000", meetupEventId = "u1")
        val newer = badge("2", name = "Ulm", date = "2026-01-01T19:00:00.000", meetupEventId = "u2")
        val all = listOf(older, badge("3", name = "Berlin"), newer)
        var opened: MeetupBadge? = null
        rule.setContent {
            EinundzwanzigTheme {
                CollectionScreen(
                    CollectionUiState("Ulm", loaded = true, badges = WalletLibrary.collection(all, "Ulm", ""), all = all),
                    onBack = {},
                    onOpenBadge = { opened = it },
                )
            }
        }
        rule.onAllNodesWithText("ULM").onFirst().assertIsDisplayed()
        rule.onNodeWithText(str(L10nR.string.walletCollectionCount, 2)).assertIsDisplayed()
        rule.onNodeWithText("#3").assertExists()
        rule.onNodeWithText("#1").performClick()
        assertSame(older, opened)
    }

    @Test
    fun `collection of a vanished group shows the empty state`() {
        rule.setContent {
            EinundzwanzigTheme { CollectionScreen(CollectionUiState("Gone", loaded = true), onBack = {}, onOpenBadge = {}) }
        }
        rule.onNodeWithTag(CollectionTestTags.EMPTY).assertExists()
    }

    // endregion

    // region Details

    private fun showDetails(badge: MeetupBadge?, actions: MutableList<BadgeDetailsAction> = mutableListOf()) {
        rule.setContent {
            EinundzwanzigTheme {
                BadgeDetailsScreen(
                    BadgeDetailsUiState(loaded = true, badge = badge, hash = badge?.verificationHash().orEmpty()),
                    onAction = { actions += it },
                )
            }
        }
    }

    @Test
    fun `details shows hero, sections and the hash`() {
        val b = badge(
            "badge-001",
            name = "Aschaffenburg",
            date = "2025-03-15T00:00:00.000Z",
            block = 875000,
            sigVersion = 2,
            sigId = "c".repeat(64),
            claimTimestamp = 1_700_000_000,
        )
        showDetails(b)
        rule.onNodeWithText("₿").assertExists()
        rule.onNodeWithText("Block 875.000").assertExists()
        rule.onNodeWithText(str(L10nR.string.badgeProofOfAttendance)).assertExists()
        rule.onNodeWithText("15.03.2025").assertExists()
        rule.onNodeWithText(str(L10nR.string.badgeRollingQr)).assertExists()
        rule.onNodeWithTag(BadgeDetailsTestTags.CONTENT).performScrollToNode(hasText(str(L10nR.string.badgeSchnorrSig)))
        rule.onNodeWithText(str(L10nR.string.badgeNotBound)).assertExists()
        rule.onNodeWithText("c".repeat(12) + "…" + "c".repeat(8)).assertExists()
        rule.onNodeWithTag(BadgeDetailsTestTags.CONTENT).performScrollToNode(hasText("48dd2de552fae3a0"))
        rule.onNodeWithText("48dd2de552fae3a0").assertExists()
    }

    @Test
    fun `legacy hmac badge is orange legacy, organizer gets the organizer record`() {
        showDetails(badge("x", sigVersion = 1, isOrganizer = true, delivery = "organizer", presenceVerified = false, isRetroactive = true))
        rule.onNodeWithText(str(L10nR.string.badgeOrganizerTitle)).assertExists()
        rule.onNodeWithText("organizer").assertExists()
        rule.onNodeWithTag(BadgeDetailsTestTags.CONTENT).performScrollToNode(hasText("HMAC (Legacy v1)"))
        rule.onNodeWithTag(BadgeDetailsTestTags.CONTENT).performScrollToNode(hasText(str(L10nR.string.badgeClaimedLater)))
        rule.onNodeWithTag(BadgeDetailsTestTags.CONTENT).performScrollToNode(hasText(str(L10nR.string.badgeUnverifiedInfo)))
    }

    @Test
    fun `details actions copy the hash, share and close`() {
        val actions = mutableListOf<BadgeDetailsAction>()
        showDetails(badge("a"), actions)
        rule.onNodeWithContentDescription(str(L10nR.string.badgeShare)).performClick()
        rule.onNodeWithTag(BadgeDetailsTestTags.CONTENT).performScrollToNode(hasText(str(L10nR.string.badgeClose)))
        rule.onNodeWithTag(BadgeDetailsTestTags.HASH).performClick()
        rule.onNodeWithText(str(L10nR.string.badgeShareCaps)).performClick()
        rule.onNodeWithText(str(L10nR.string.badgeClose)).performClick()
        assertEquals(
            listOf(BadgeDetailsAction.Share, BadgeDetailsAction.CopyHash, BadgeDetailsAction.Share, BadgeDetailsAction.Back),
            actions,
        )
    }

    @Test
    fun `missing badge shows not found`() {
        showDetails(null)
        rule.onNodeWithTag(BadgeDetailsTestTags.NOT_FOUND).assertExists()
    }

    // endregion

    // region Share card

    @Test
    fun `share card shows chip, date, footer rows and promo`() {
        val b = badge("e", name = "Bitcoin Event", date = "2025-03-15T00:00:00.000Z", block = 875000, isEvent = true, claimTimestamp = 1_700_000_000)
        rule.setContent { EinundzwanzigTheme { BadgeShareCard(b) } }
        rule.onNodeWithText("EVENT-BADGE").assertExists()
        rule.onNodeWithText("BITCOIN EVENT").assertExists()
        rule.onNodeWithText("15.03.2025").assertExists()
        rule.onNodeWithText("875.000").assertExists()
        rule.onNodeWithText(b.verificationHash()).assertExists()
        rule.onNodeWithText(ShareCard.APP_STORE_URL).assertExists()
        rule.onNodeWithText(str(L10nR.string.shareCardPromo)).assertExists()
        rule.onNodeWithText(str(L10nR.string.shareCardCollectedBy) + "  ").assertDoesNotExist()
    }

    @Test
    fun `meetup share card without block hides the block row`() {
        rule.setContent { EinundzwanzigTheme { BadgeShareCard(badge("m", block = 0)) } }
        rule.onNodeWithText("MEETUP-BADGE").assertExists()
        rule.onNodeWithText(str(L10nR.string.shareCardBlock) + "  ").assertDoesNotExist()
    }

    // endregion

    // region World map

    private fun showMap(state: WorldMapUiState, actions: MutableList<WorldMapAction> = mutableListOf()) {
        rule.setContent {
            CompositionLocalProvider(LocalOsmTileRendering provides false) {
                EinundzwanzigTheme {
                    BadgeWorldMapScreen(
                        state = state,
                        onAction = { actions += it },
                        cameraState = rememberOsmCameraState(state.located.firstOrNull()?.let(WorldMap::point) ?: space.einundzwanzig.meetup.core.map.LatLng.DachCenter, 4.0),
                        snapshotState = rememberMapSnapshotState(),
                    )
                }
            }
        }
    }

    @Test
    fun `world map without located badges shows the empty state`() {
        showMap(WorldMapUiState(loaded = true))
        rule.onNodeWithTag(WorldMapTestTags.EMPTY).assertExists()
        rule.onNodeWithText(str(L10nR.string.mapEmpty)).assertExists()
        rule.onNodeWithContentDescription(str(L10nR.string.mapShareButton)).assertDoesNotExist()
    }

    @Test
    fun `world map shows stats, selects a pin and shares`() {
        val ulm = badge("1", name = "Ulm", lat = 48.4, lng = 10.0, date = "2026-05-02T19:00:00.000")
        val wien = badge("2", name = "Wien", lat = 48.2, lng = 16.37)
        val located = listOf(ulm, wien)
        val actions = mutableListOf<WorldMapAction>()
        showMap(WorldMapUiState(loaded = true, located = located, cityCount = 2), actions)
        rule.onNodeWithTag(WorldMapTestTags.STATS).assertExists()
        rule.onNodeWithText(str(L10nR.string.mapStatCities)).assertExists()
        rule.onNodeWithContentDescription("Ulm, 2.5.2026").performClick()
        rule.onNodeWithTag(WorldMapTestTags.SHARE_BUTTON).performClick()
        rule.onNodeWithContentDescription(str(L10nR.string.mapShareButton)).performClick()
        assertEquals(listOf(WorldMapAction.Select(ulm), WorldMapAction.Share, WorldMapAction.Share), actions)
    }

    @Test
    fun `info card shows the selected badge and closes`() {
        val ulm = badge("1", name = "Ulm", lat = 48.4, lng = 10.0, date = "2026-05-02T19:00:00.000")
        val actions = mutableListOf<WorldMapAction>()
        showMap(WorldMapUiState(loaded = true, located = listOf(ulm), cityCount = 1, selected = ulm), actions)
        rule.onNodeWithTag(WorldMapTestTags.INFO).assertExists()
        rule.onNodeWithText("2.5.2026").assertExists()
        rule.onNodeWithContentDescription(str(R.string.native_wallet_close_info)).performClick()
        assertEquals(listOf<WorldMapAction>(WorldMapAction.Select(null)), actions)
    }

    // endregion
}
