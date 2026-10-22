package space.einundzwanzig.meetup.feature.home.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import space.einundzwanzig.meetup.core.ui.guide.guideAnchor
import space.einundzwanzig.meetup.core.ui.guide.guideScrollContainer
import space.einundzwanzig.meetup.core.ui.haptics.rememberHaptics
import space.einundzwanzig.meetup.core.ui.motion.Pressable
import space.einundzwanzig.meetup.core.ui.theme.Dimens
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.feature.home.HomeAction
import space.einundzwanzig.meetup.feature.home.HomeTour
import space.einundzwanzig.meetup.feature.home.HomeUiState
import space.einundzwanzig.meetup.feature.home.R
import space.einundzwanzig.meetup.feature.home.tiles.TileId
import space.einundzwanzig.meetup.feature.home.tiles.TileLayoutEngine
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Drag state of the edit mode: the dragged tile, its offset and the tile under the finger. */
private class DragState {
    var offset by mutableStateOf(Offset.Zero)
    var dragging by mutableStateOf(false)
    var hover by mutableStateOf<TileId?>(null)
}

/**
 * The tile area below the home-meetup card (spec 12 §2.5.1 item 9): the pinned block fills the
 * remaining height (rows share it, ≥ 120 dp each), the "Verfügbar" header peeks in at the bottom,
 * pull-to-refresh works even when the content is shorter than the screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TileArea(
    state: HomeUiState,
    maxHeightDp: Float,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pinnedRows = state.pinnedRows
    val availableRows = state.availableRows
    val pinnedHeight = TileLayoutEngine.pinnedHeight(maxHeightDp, availableRows.isNotEmpty())
    val rowHeight = TileLayoutEngine.rowHeight(pinnedHeight, pinnedRows.size)
    val bounds = remember { mutableStateMapOf<TileId, Rect>() }
    val drag = remember { DragState() }
    val scroll = rememberScrollState()
    val pullState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = state.gestureRefreshing,
        onRefresh = { onAction(HomeAction.PullRefresh) },
        state = pullState,
        modifier = modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(
                state = pullState,
                isRefreshing = state.gestureRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .guideScrollContainer(scroll)
                .verticalScroll(scroll)
                .testTag(HomeTags.TILE_AREA),
        ) {
            pinnedRows.forEachIndexed { i, row ->
                if (i > 0) Spacer(Modifier.height(TileLayoutEngine.GAP.dp))
                TileRow(row, pinned = true, height = rowHeight, state, drag, bounds, onAction)
            }
            if (availableRows.isNotEmpty()) {
                Spacer(Modifier.height(TileLayoutEngine.GAP.dp))
                AvailableHeader(
                    count = availableRows.size,
                    collapsed = state.availableCollapsed,
                    onToggle = { onAction(HomeAction.ToggleAvailable) },
                    onLongPress = { onAction(HomeAction.OpenCustomize) },
                )
                AnimatedVisibility(
                    visible = !state.availableCollapsed || state.editTileId != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column {
                        availableRows.forEachIndexed { i, row ->
                            if (i > 0) Spacer(Modifier.height(TileLayoutEngine.GAP.dp))
                            TileRow(row, pinned = false, height = TileLayoutEngine.AVAILABLE_ROW_HEIGHT, state, drag, bounds, onAction)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TileRow(
    row: List<TileId>,
    pinned: Boolean,
    height: Float,
    state: HomeUiState,
    drag: DragState,
    bounds: MutableMap<TileId, Rect>,
    onAction: (HomeAction) -> Unit,
) {
    val selectedHere = row.any { it.id == state.editTileId }
    Row(
        Modifier
            .fillMaxWidth()
            .height(height.dp)
            .zIndex(if (selectedHere) 1f else 0f)
            .alpha(if (pinned) 1f else 0.8f),
    ) {
        row.forEachIndexed { j, tile ->
            if (j > 0) Spacer(Modifier.width(TileLayoutEngine.GAP.dp))
            DisposableEffect(tile) { onDispose { bounds.remove(tile) } }
            Box(
                Modifier
                    .weight(tile.span.toFloat())
                    .fillMaxHeight()
                    .onGloballyPositioned { bounds[tile] = it.boundsInRoot() },
            ) {
                EditableTile(tile, pinned, state, drag, bounds, onAction)
            }
        }
    }
}

/** `_wrapEditable`: long press selects; the selected tile can be pinned/unpinned and dragged onto another tile. */
@Composable
private fun EditableTile(
    tile: TileId,
    pinned: Boolean,
    state: HomeUiState,
    drag: DragState,
    bounds: Map<TileId, Rect>,
    onAction: (HomeAction) -> Unit,
) {
    val editing = state.editTileId != null
    val selected = state.editTileId == tile.id
    val primary = MaterialTheme.colorScheme.primary
    val anchor = HomeTour.tileAnchor(tile)
    val baseModifier = Modifier
        .fillMaxSize()
        .then(if (anchor != null) Modifier.guideAnchor(anchor) else Modifier)
        .testTag(HomeTags.tile(tile.id))
    if (selected) {
        val currentBounds by rememberUpdatedState(bounds)
        val label = stringResource(R.string.native_home_edit_tile, tileLabel(tile))
        val others = state.pinnedRows.flatten() + state.availableRows.flatten() - tile
        val moveLabels = others.map { it to stringResource(R.string.native_home_move_before, tileLabel(it)) }
        Box(Modifier.fillMaxSize()) {
            if (drag.dragging) DottedPlaceholder(Modifier.fillMaxSize())
            Box(
                baseModifier
                    .graphicsLayer {
                        translationX = drag.offset.x
                        translationY = drag.offset.y
                        val lifted = if (drag.dragging) 1.03f else 1f
                        scaleX = lifted
                        scaleY = lifted
                        alpha = if (drag.dragging) 0.9f else 1f
                    }
                    .shadow(14.dp, Dimens.TileShape, ambientColor = primary, spotColor = primary)
                    .border(2.dp, primary, Dimens.TileShape)
                    .clip(Dimens.TileShape)
                    .semantics {
                        stateDescription = label
                        customActions = moveLabels.map { (target, text) ->
                            CustomAccessibilityAction(text) {
                                onAction(HomeAction.MoveTile(tile, target))
                                true
                            }
                        }
                    }
                    .pointerInput(tile) {
                        detectDragGestures(
                            onDragStart = {
                                drag.dragging = true
                                drag.offset = Offset.Zero
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                drag.offset += amount
                                val origin = currentBounds[tile] ?: return@detectDragGestures
                                // The pointer node sits in the layer translated by the previous offset.
                                val finger = origin.topLeft + (drag.offset - amount) + change.position
                                drag.hover = currentBounds.entries
                                    .firstOrNull { (id, r) -> id != tile && r.contains(finger) }
                                    ?.key
                            },
                            onDragEnd = {
                                val target = drag.hover
                                drag.dragging = false
                                drag.offset = Offset.Zero
                                drag.hover = null
                                if (target != null) onAction(HomeAction.MoveTile(tile, target))
                            },
                            onDragCancel = {
                                drag.dragging = false
                                drag.offset = Offset.Zero
                                drag.hover = null
                            },
                        )
                    },
            ) {
                DashboardTile(tile, pinned, state, onClick = { onAction(HomeAction.CancelEdit) }, onLongClick = null, onAction)
                Icon(
                    Icons.Rounded.OpenWith,
                    null,
                    Modifier.align(Alignment.TopStart).padding(9.dp).size(15.dp),
                    tint = primary,
                )
            }
            if (tile.removable && !drag.dragging) {
                PinButton(
                    pinned = pinned,
                    onClick = { onAction(HomeAction.TogglePin(tile)) },
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-9).dp),
                )
            }
        }
    } else {
        val hovered = editing && drag.hover == tile
        Box(
            baseModifier
                .then(if (hovered) Modifier.border(1.5.dp, primary.copy(alpha = 0.7f), Dimens.TileShape) else Modifier)
                .alpha(if (hovered) 0.6f else 1f),
        ) {
            DashboardTile(
                tile,
                pinned,
                state,
                onClick = { onAction(HomeAction.TileTap(tile)) }.takeIf { tile != TileId.PlebRap },
                onLongClick = if (editing) null else ({ onAction(HomeAction.TileLongPress(tile)) }),
                onAction = onAction,
            )
        }
    }
}

/** `_tile`: pinned (orange gradient) or available (plain card) container with watermark. */
@Composable
internal fun DashboardTile(
    tile: TileId,
    pinned: Boolean,
    state: HomeUiState,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val look = tileLook(tile, state)
    val haptics = rememberHaptics()
    val primary = MaterialTheme.colorScheme.primary
    val container = if (pinned) {
        Modifier
            .shadow(6.dp, Dimens.TileShape, ambientColor = primary.copy(alpha = 0.05f), spotColor = primary.copy(alpha = 0.05f))
            .background(pinnedBrush(), Dimens.TileShape)
            .border(1.dp, primary.copy(alpha = 0.30f), Dimens.TileShape)
    } else {
        Modifier
            .background(MaterialTheme.colorScheme.surfaceContainer, Dimens.TileShape)
            .border(Dimens.Hairline, MaterialTheme.colorScheme.outlineVariant, Dimens.TileShape)
    }
    Pressable(
        onClick = onClick?.let { click -> { haptics.light(); click() } },
        onLongClick = onLongClick?.let { lp -> { haptics.medium(); lp() } },
        modifier = modifier.fillMaxSize().then(container).clip(Dimens.TileShape),
    ) {
        look.watermark?.let {
            Icon(
                it,
                null,
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 18.dp, y = 18.dp)
                    .size(WatermarkIconSize),
                tint = look.accent.copy(alpha = 0.10f),
            )
        }
        look.watermarkRes?.let {
            Image(
                painterResource(it),
                null,
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 12.dp, y = 12.dp)
                    .size(WatermarkAssetSize)
                    .alpha(0.13f),
            )
        }
        Box(Modifier.fillMaxSize().padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 18.dp)) {
            TileBody(tile, state, onAction)
        }
    }
}

@Composable
private fun PinButton(pinned: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val label = stringResource(if (pinned) R.string.native_home_unpin else R.string.native_home_pin)
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(HomeTags.PIN_BUTTON),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(if (pinned) primary else MaterialTheme.colorScheme.surfaceContainer)
                .border(1.5.dp, primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
                null,
                Modifier.size(15.dp),
                tint = if (pinned) MaterialTheme.colorScheme.onPrimary else primary,
            )
        }
    }
}

/** Slot of the dragged tile (Flutter `DottedPlaceholder`, solid border despite the name). */
@Composable
private fun DottedPlaceholder(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .background(primary.copy(alpha = 0.04f), Dimens.TileShape)
            .border(1.dp, primary.copy(alpha = 0.45f), Dimens.TileShape),
    )
}

/** "VERFÜGBAR · n" header: tap toggles, long press opens the customize sheet. */
@Composable
private fun AvailableHeader(count: Int, collapsed: Boolean, onToggle: () -> Unit, onLongPress: () -> Unit) {
    val tertiary = MaterialTheme.extendedColors.textTertiary
    val stateLabel = stringResource(if (collapsed) R.string.native_home_expand else R.string.native_home_collapse)
    Pressable(
        onClick = onToggle,
        onLongClick = onLongPress,
        onClickLabel = stateLabel,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .guideAnchor(HomeTour.CUSTOMIZE)
            .testTag(HomeTags.AVAILABLE_HEADER),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.CenterStart)
                .padding(start = 2.dp, top = 2.dp, end = 2.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(L10nR.string.tilesAvailable).uppercase(), style = style, color = tertiary)
            Spacer(Modifier.width(7.dp))
            Text("$count", style = style, color = tertiary)
            Spacer(Modifier.weight(1f))
            Icon(if (collapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, null, Modifier.size(18.dp), tint = tertiary)
        }
    }
}

/** Label of a tile for the customize sheet and accessibility. */
@Composable
internal fun tileLabel(tile: TileId): String = stringResource(
    when (tile) {
        TileId.TrustScore -> R.string.native_home_tile_trust_score
        TileId.HomeMeetup -> R.string.native_home_tile_home_meetup
        TileId.EventChats -> R.string.native_home_tile_event_chats
        TileId.Reputation -> R.string.native_home_tile_reputation
        TileId.Community -> R.string.native_home_tile_community
        TileId.TrustNetwork -> R.string.native_home_tile_trust_network
        TileId.Events -> R.string.native_home_tile_events
        TileId.Shoutout -> R.string.native_home_tile_shoutout
        TileId.Podcast -> R.string.native_home_tile_podcast
        TileId.SatoshiDuell -> R.string.native_home_tile_satoshiduell
        TileId.PortalArea -> R.string.native_home_tile_portal_area
        TileId.PlebRap -> R.string.native_home_tile_plebrap
        TileId.Nostr -> R.string.native_home_tile_nostr
        TileId.PortalConnect -> R.string.native_home_tile_portal_connect
        TileId.Converter -> R.string.native_home_tile_converter
        TileId.BtcDashboard -> R.string.native_home_tile_btc_dashboard
        TileId.News -> R.string.native_home_tile_news
        TileId.Portal -> R.string.native_home_tile_portal
        TileId.Organisator -> R.string.native_home_tile_organisator
    },
)
