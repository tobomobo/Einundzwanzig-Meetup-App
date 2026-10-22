package space.einundzwanzig.meetup.feature.home

import androidx.compose.ui.unit.dp
import space.einundzwanzig.meetup.core.ui.guide.GuideStep
import space.einundzwanzig.meetup.feature.home.tiles.TileId
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/**
 * The dashboard tour (port of `lib/tours/home_tour.dart`, spec 12 §2.9.1): 21 steps.
 *
 * Anchors inside Home are attached by this module. The bottom-bar anchors ([HOME_TAB],
 * [BADGE_TAB], [NEARBY_TAB], [SCAN_FAB], [EVENTS_TAB]) belong to the app shell, which must put
 * `Modifier.guideAnchor(HomeTour.BADGE_TAB)` etc. on its bar items; without them those steps
 * are skipped after their wait timeout. The dead tiles (`trust_score`, `portal_connect`) are never
 * drawn, so their steps always skip (as in Flutter); tiles in the collapsed "Verfügbar" section
 * are not composed and skip after 220 ms.
 */
object HomeTour {
    const val GLOSSARY = "home.glossary"
    const val SETTINGS = "home.settings"
    const val HOME_MEETUP = "home.homeMeetup"
    const val CUSTOMIZE = "home.customize"
    const val TRUST_SCORE = "home.tile.trust_score"
    const val PORTAL_CONNECT = "home.tile.portal_connect"

    /** Bottom-bar anchors (attached by the app shell). */
    const val HOME_TAB = "shell.homeTab"
    const val BADGE_TAB = "shell.badgeTab"
    const val NEARBY_TAB = "shell.nearbyTab"
    const val SCAN_FAB = "shell.scanFab"
    const val EVENTS_TAB = "shell.eventsTab"

    /** Wait for tiles that may be hidden or collapsed (`_optionalTile`). */
    const val OPTIONAL_TILE_MS: Long = 220

    /** Readiness poll before the tour starts: 20 × 500 ms (spec 12 §2.4 `_checkHomeTour`). */
    const val POLL_ATTEMPTS: Int = 20
    const val POLL_INTERVAL_MS: Long = 500

    /** Anchor id of a dashboard tile (`_tourKeys[tile.id]`), or `null` for tiles without a step. */
    fun tileAnchor(tile: TileId): String? = when (tile) {
        TileId.TrustScore -> TRUST_SCORE
        TileId.Reputation -> "home.tile.reputation"
        TileId.Community -> "home.tile.community"
        TileId.TrustNetwork -> "home.tile.wot"
        TileId.Events -> "home.tile.events"
        TileId.Shoutout -> "home.tile.shoutout"
        TileId.Podcast -> "home.tile.podcast"
        TileId.PortalConnect -> PORTAL_CONNECT
        TileId.Converter -> "home.tile.umrechner"
        TileId.BtcDashboard -> "home.tile.bitcoin"
        TileId.News -> "home.tile.news"
        TileId.Portal -> "home.tile.myMeetups"
        else -> null
    }

    private fun tile(t: TileId, title: Int, body: Int) =
        GuideStep(tileAnchor(t), title, body, waitTimeoutMillis = OPTIONAL_TILE_MS)

    /** The 21 steps in Flutter order ("Schritt X von 21" counts skipped steps too). */
    fun steps(): List<GuideStep> = listOf(
        GuideStep(GLOSSARY, L10nR.string.guideHomeGlossaryTitle, L10nR.string.guideHomeGlossaryBody, scrollIntoView = false),
        tile(TileId.TrustScore, L10nR.string.guideHomeTrustScoreTitle, L10nR.string.guideHomeTrustScoreBody),
        GuideStep(HOME_MEETUP, L10nR.string.guideHomeMeetupTitle, L10nR.string.guideHomeMeetupBody),
        tile(TileId.Reputation, L10nR.string.guideHomeReputationTitle, L10nR.string.guideHomeReputationBody),
        tile(TileId.Community, L10nR.string.guideHomeCommunityTitle, L10nR.string.guideHomeCommunityBody),
        tile(TileId.TrustNetwork, L10nR.string.guideHomeWotTitle, L10nR.string.guideHomeWotBody),
        tile(TileId.Events, L10nR.string.guideHomeEventsTitle, L10nR.string.guideHomeEventsBody),
        tile(TileId.Shoutout, L10nR.string.guideHomeShoutoutTitle, L10nR.string.guideHomeShoutoutBody),
        tile(TileId.Podcast, L10nR.string.guideHomePodcastTitle, L10nR.string.guideHomePodcastBody),
        GuideStep(null, L10nR.string.guideHomeMoreTitle, L10nR.string.guideHomeMoreBody),
        tile(TileId.PortalConnect, L10nR.string.guideHomePortalConnectTitle, L10nR.string.guideHomePortalConnectBody),
        tile(TileId.Converter, L10nR.string.guideHomeUmrechnerTitle, L10nR.string.guideHomeUmrechnerBody),
        tile(TileId.BtcDashboard, L10nR.string.guideHomeBitcoinTitle, L10nR.string.guideHomeBitcoinBody),
        tile(TileId.News, L10nR.string.guideHomeNewsTitle, L10nR.string.guideHomeNewsBody),
        tile(TileId.Portal, L10nR.string.guideHomeMyMeetupsTitle, L10nR.string.guideHomeMyMeetupsBody),
        GuideStep(
            CUSTOMIZE,
            L10nR.string.guideHomeCustomizeTitle,
            L10nR.string.guideHomeCustomizeBody,
            waitTimeoutMillis = OPTIONAL_TILE_MS,
        ),
        GuideStep(BADGE_TAB, L10nR.string.guideHomeBadgeWalletTitle, L10nR.string.guideHomeBadgeWalletBody, scrollIntoView = false),
        GuideStep(NEARBY_TAB, L10nR.string.guideHomeNearbyTitle, L10nR.string.guideHomeNearbyBody, scrollIntoView = false),
        GuideStep(
            SCAN_FAB,
            L10nR.string.guideHomeScanTitle,
            L10nR.string.guideHomeScanBody,
            scrollIntoView = false,
            holePadding = 6.dp,
            holeRadius = 40.dp,
        ),
        GuideStep(EVENTS_TAB, L10nR.string.guideHomeEventsTabTitle, L10nR.string.guideHomeEventsTabBody, scrollIntoView = false),
        GuideStep(
            SETTINGS,
            L10nR.string.guideHomeSettingsTitle,
            L10nR.string.guideHomeSettingsBody,
            hint = L10nR.string.guideHomeSettingsBackupHint,
            scrollIntoView = false,
        ),
    )
}
