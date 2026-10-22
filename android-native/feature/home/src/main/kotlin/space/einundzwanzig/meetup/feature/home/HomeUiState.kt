package space.einundzwanzig.meetup.feature.home

import androidx.compose.runtime.Immutable
import space.einundzwanzig.meetup.feature.home.tiles.TileId
import space.einundzwanzig.meetup.feature.home.tiles.TileLayoutEngine

/** One favorite card of the home-meetup carousel (spec 12 §2.5.5). */
@Immutable
data class FavCardUi(
    /** Stored favorite: portal id or legacy city name. */
    val key: String,
    val label: String,
    val city: String,
    /** Portal meetup id for details/calendar; `null` when the meetup cannot be resolved (Info disabled). */
    val meetupId: String? = null,
    val country: String = "DE",
    val crestUrl: String = "",
    /** Calendar days until the next date (`null` = no date planned). */
    val daysUntil: Long? = null,
    /** `d.m.yyyy` of the next date. */
    val dateText: String = "",
)

/** Live rolling-QR session card (spec 12 §2.5.4). */
@Immutable
data class SessionUi(val meetupName: String, val isEvent: Boolean, val timeLeft: String = "")

/** Mini player tile (spec 12 §2.5.8 `plebrap`). */
@Immutable
data class PlebrapUi(val title: String? = null, val artist: String? = null, val playing: Boolean = false, val loading: Boolean = false)

/** SatoshiDuell tile counts (spec 12 §8.10). */
@Immutable
data class DuellUi(val myTurn: Int = 0, val waiting: Int = 0, val lobby: Int = 0) {
    val count: Int get() = myTurn + lobby
}

/** BTC tile (spec 12 §2.5.8 `_BtcDashboardTileContent`). */
@Immutable
data class BtcUi(
    /** Block height text (`95.000`) or `––` without data. */
    val blockText: String = "––",
    val live: Boolean = false,
    /** Formatted price in the display currency, `null` while unknown. */
    val priceText: String? = null,
)

/** Steps of the reset flow (spec 12 §2.5.12). */
enum class ResetStep { NONE, CONFIRM, BACKUP_OFFER, BACKUP_DONE, RUNNING }

/** Whole dashboard state. */
@Immutable
data class HomeUiState(
    val nickname: String = "",
    val avatarLocal: String? = null,
    val avatarUrl: String? = null,
    /** Score pill text, `toStringAsFixed(1)` (`0.0` when badges are unreadable). */
    val scorePill: String = "0.0",
    /** Reputation tile value, `—` when badges are unreadable. */
    val reputationValue: String = "—",
    val countedBadges: Int = 0,
    val badgesUnreadable: Boolean = false,
    val deviceCompromised: Boolean = false,
    val integrityDismissed: Boolean = false,
    val session: SessionUi? = null,
    val hasHome: Boolean = false,
    val favCards: List<FavCardUi> = emptyList(),
    /** Bumped whenever the carousel is recomputed, so the pager jumps back to page 0. */
    val favEpoch: Int = 0,
    val countdownLoading: Boolean = false,
    val chatUnread: Map<String, Int> = emptyMap(),
    val myEventsCount: Int = 0,
    val myEventsUnread: Int = 0,
    val unreadNews: Int = 0,
    val latestNewsTitle: String? = null,
    val eventsToday: Int = 0,
    val nostrHasNew: Boolean = false,
    val organizerVisible: Boolean = false,
    val tileOrder: List<String> = TileLayoutEngine.DEFAULT_ORDER,
    val hiddenTiles: Set<String> = TileLayoutEngine.DEFAULT_HIDDEN,
    val layoutLoaded: Boolean = false,
    val availableCollapsed: Boolean = true,
    val editTileId: String? = null,
    val refreshing: Boolean = false,
    val gestureRefreshing: Boolean = false,
    val btc: BtcUi = BtcUi(),
    /** Converter tile value (`1 € = 1.779 sats`), `null` → `tileConverter`. */
    val converterText: String? = null,
    val duell: DuellUi = DuellUi(),
    val plebrap: PlebrapUi = PlebrapUi(),
    val resetStep: ResetStep = ResetStep.NONE,
) {
    /** `visible()` of the tile definitions (only the organizer tile is conditional). */
    fun isTileVisible(tile: TileId): Boolean = tile != TileId.Organisator || organizerVisible

    val pinnedRows: List<List<TileId>>
        get() = TileLayoutEngine.packRows(tileOrder, hiddenTiles, pinned = true, isVisible = ::isTileVisible)

    val availableRows: List<List<TileId>>
        get() = TileLayoutEngine.packRows(tileOrder, hiddenTiles, pinned = false, isVisible = ::isTileVisible)

    val showIntegrityWarning: Boolean get() = deviceCompromised && !integrityDismissed
}

/** User actions of the dashboard. */
sealed interface HomeAction {
    data object Refresh : HomeAction
    data object PullRefresh : HomeAction
    data object OpenGlossary : HomeAction
    data object OpenSettings : HomeAction
    data object OpenReputationCard : HomeAction
    data object PickAvatar : HomeAction
    data object DismissIntegrityWarning : HomeAction
    data object OpenLiveSession : HomeAction
    data object SelectHomeMeetup : HomeAction
    data object OpenCustomize : HomeAction
    data class OpenMeetupEvents(val card: FavCardUi) : HomeAction
    data class OpenMeetupChat(val card: FavCardUi) : HomeAction
    data class OpenMeetupInfo(val card: FavCardUi) : HomeAction

    /** Tap on a dashboard tile (the tile's own navigation). */
    data class TileTap(val tile: TileId) : HomeAction
    data object PlebrapToggle : HomeAction
    data object PlebrapNext : HomeAction
    data object PlebrapLibrary : HomeAction

    data class TileLongPress(val tile: TileId) : HomeAction
    data object CancelEdit : HomeAction
    data class TogglePin(val tile: TileId) : HomeAction
    data class MoveTile(val dragged: TileId, val target: TileId) : HomeAction
    data object ToggleAvailable : HomeAction

    data object ResetConfirm : HomeAction
    data object ResetCreateBackup : HomeAction
    data object ResetSkipBackup : HomeAction
    data object ResetNow : HomeAction
    data object ResetCancel : HomeAction
}
