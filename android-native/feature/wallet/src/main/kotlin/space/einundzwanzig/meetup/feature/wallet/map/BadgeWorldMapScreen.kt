package space.einundzwanzig.meetup.feature.wallet.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.PublicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.map.BitcoinBadgeMarker
import space.einundzwanzig.meetup.core.map.LatLng
import space.einundzwanzig.meetup.core.map.MapSnapshotState
import space.einundzwanzig.meetup.core.map.OsmCameraState
import space.einundzwanzig.meetup.core.map.OsmMap
import space.einundzwanzig.meetup.core.map.mapSnapshotArea
import space.einundzwanzig.meetup.core.map.rememberMapSnapshotState
import space.einundzwanzig.meetup.core.map.rememberOsmCameraState
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.AppTopBar
import space.einundzwanzig.meetup.core.ui.component.FullScreenLoading
import space.einundzwanzig.meetup.core.ui.share.ShareHelper
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.feature.wallet.PreviewData
import space.einundzwanzig.meetup.feature.wallet.R
import space.einundzwanzig.meetup.feature.wallet.domain.BadgeFormat
import space.einundzwanzig.meetup.feature.wallet.domain.WalletLibrary
import space.einundzwanzig.meetup.core.l10n.R as L10nR

internal object WorldMapTestTags {
    const val EMPTY = "worldmap.empty"
    const val MAP = "worldmap.map"
    const val STATS = "worldmap.stats"
    const val INFO = "worldmap.info"
    const val SHARE_BUTTON = "worldmap.shareButton"
}

/** World map constants (spec 03 §2.4, §8.9). */
internal object WorldMap {
    const val SINGLE_ZOOM = 11.0
    const val MULTI_ZOOM = 4.0
    const val FIT_MAX_ZOOM = 12.0
    val FitPadding = 60.dp

    fun point(badge: MeetupBadge): LatLng = LatLng(badge.lat, badge.lng)

    fun fileName(nowMillis: Long): String = "badge_worldmap_$nowMillis.png"
}

/** Stateful badge world map with share as image (spec 03 §2.4). */
@Composable
fun BadgeWorldMapRoute() {
    WorldMapRouteContent(hiltViewModel())
}

@Composable
internal fun WorldMapRouteContent(viewModel: WorldMapViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = state.located.firstOrNull()
    val camera = rememberOsmCameraState(
        initialTarget = first?.let(WorldMap::point) ?: LatLng.DachCenter,
        initialZoom = if (state.located.size == 1) WorldMap.SINGLE_ZOOM else WorldMap.MULTI_ZOOM,
    )
    val snapshot = rememberMapSnapshotState()

    BadgeWorldMapScreen(
        state = state,
        cameraState = camera,
        snapshotState = snapshot,
        onAction = { action ->
            when (action) {
                WorldMapAction.Back -> navigator.back()
                is WorldMapAction.Select -> viewModel.select(action.badge)
                WorldMapAction.Share -> if (viewModel.beginShare()) {
                    val count = state.located.size
                    scope.launch {
                        try {
                            camera.awaitTilesLoaded()
                            val bitmap = snapshot.capture(
                                pixelRatio = MapSnapshotState.WORLD_MAP_PIXEL_RATIO,
                                settleMillis = MapSnapshotState.WORLD_MAP_SETTLE_MS,
                            ) ?: return@launch
                            val shared = ShareHelper.shareImage(
                                context = context,
                                bitmap = bitmap,
                                fileName = WorldMap.fileName(System.currentTimeMillis()),
                                text = context.getString(L10nR.string.mapShareText, count.toString()),
                            )
                            // Silent as in Flutter, but keep a diagnostic entry.
                            if (!shared) AppLogger.warn("Wallet", "Weltkarte teilen: kein Ziel zum Teilen")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Errors are silent in Flutter; keep a diagnostic entry.
                            AppLogger.warn("Wallet", "Weltkarte teilen fehlgeschlagen", e)
                        } finally {
                            viewModel.endShare()
                        }
                    }
                }
            }
        },
    )
}

/** Stateless world map screen. */
@Composable
internal fun BadgeWorldMapScreen(
    state: WorldMapUiState,
    onAction: (WorldMapAction) -> Unit,
    cameraState: OsmCameraState,
    snapshotState: MapSnapshotState,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = stringResource(L10nR.string.mapTitle),
                onBack = { onAction(WorldMapAction.Back) },
                actions = {
                    if (state.located.isNotEmpty()) {
                        IconButton(onClick = { onAction(WorldMapAction.Share) }, enabled = !state.sharing) {
                            if (state.sharing) {
                                AppLoadingIndicator(size = 24.dp)
                            } else {
                                Icon(Icons.Rounded.IosShare, contentDescription = stringResource(L10nR.string.mapShareButton))
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            !state.loaded -> FullScreenLoading(Modifier.padding(padding), message = stringResource(L10nR.string.loading))
            state.located.isEmpty() -> MapEmpty(Modifier.padding(padding))
            else -> MapContent(state, onAction, cameraState, snapshotState, Modifier.padding(padding))
        }
    }
}

@Composable
private fun MapEmpty(modifier: Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(40.dp)
            .testTag(WorldMapTestTags.EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.PublicOff, contentDescription = null, tint = MaterialTheme.extendedColors.textTertiary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(L10nR.string.mapEmpty),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(L10nR.string.mapEmptySub),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 1.5.em,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MapContent(
    state: WorldMapUiState,
    onAction: (WorldMapAction) -> Unit,
    cameraState: OsmCameraState,
    snapshotState: MapSnapshotState,
    modifier: Modifier,
) {
    // Fit once (spec 03 §2.4): survives recomposition, rotation and process death.
    var didFit by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (didFit) return@LaunchedEffect
        val points = state.located.map(WorldMap::point)
        // Only a completed (or failed) fit counts: a cancellation (rotation, leaving during the
        // first layout) must leave didFit false so the restored screen fits again.
        try {
            if (points.size == 1) {
                cameraState.move(points.first(), WorldMap.SINGLE_ZOOM)
            } else {
                cameraState.fitPoints(points, WorldMap.FitPadding, maxZoom = WorldMap.FIT_MAX_ZOOM)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.warn("Wallet", "Weltkarte nicht eingepasst", e)
        }
        didFit = true
    }
    Column(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .mapSnapshotArea(snapshotState),
        ) {
            OsmMap(
                cameraState = cameraState,
                modifier = Modifier.fillMaxSize().testTag(WorldMapTestTags.MAP),
                contentDescription = stringResource(L10nR.string.mapTitle),
            ) {
                state.located.forEach { badge ->
                    Marker(
                        position = WorldMap.point(badge),
                        contentDescription = "${badge.meetupName}, ${BadgeFormat.dateShort(badge.date)}",
                        onClick = { onAction(WorldMapAction.Select(badge)) },
                    ) { BitcoinBadgeMarker() }
                }
            }
            StatsBar(state, Modifier.align(Alignment.TopCenter).padding(12.dp))
            SelectedInfo(
                selected = state.selected,
                onClose = { onAction(WorldMapAction.Select(null)) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 40.dp),
            )
        }
        Button(
            onClick = { onAction(WorldMapAction.Share) },
            enabled = !state.sharing,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp)
                .height(56.dp)
                .testTag(WorldMapTestTags.SHARE_BUTTON),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            Icon(Icons.Rounded.IosShare, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(L10nR.string.mapShareButton), fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun SelectedInfo(selected: MeetupBadge?, onClose: () -> Unit, modifier: Modifier) {
    AnimatedVisibility(
        visible = selected != null,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
    ) {
        // Keeps the last badge while the exit animation runs.
        var last by remember { mutableStateOf(selected) }
        if (selected != null) last = selected
        last?.let { InfoCard(it, onClose) }
    }
}

@Composable
private fun StatsBar(state: WorldMapUiState, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.background.copy(alpha = 0.85f))
            .border(0.5.dp, colors.primary.copy(alpha = 0.3f), shape)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(WorldMapTestTags.STATS),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Stat(state.located.size, stringResource(L10nR.string.mapStatMeetups))
        Box(Modifier.width(0.5.dp).height(32.dp).background(colors.outlineVariant))
        Stat(state.cityCount, stringResource(L10nR.string.mapStatCities))
    }
}

@Composable
private fun Stat(value: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", color = MaterialTheme.colorScheme.primary, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun InfoCard(badge: MeetupBadge, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .shadow(12.dp, shape)
            .clip(shape)
            .background(colors.surfaceContainer)
            .border(0.5.dp, colors.primary.copy(alpha = 0.4f), shape)
            .padding(14.dp)
            .testTag(WorldMapTestTags.INFO),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).background(colors.primary.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Bolt, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(badge.meetupName, color = colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(BadgeFormat.dateShort(badge.date), color = MaterialTheme.extendedColors.textTertiary, fontSize = 12.sp)
        }
        IconButton(onClick = onClose) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = stringResource(R.string.native_wallet_close_info),
                tint = MaterialTheme.extendedColors.textTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Preview(widthDp = 380, heightDp = 760)
@Composable
private fun BadgeWorldMapScreenPreview() {
    val located = WalletLibrary.located(PreviewData.badges())
    EinundzwanzigTheme {
        BadgeWorldMapScreen(
            state = WorldMapUiState(loaded = true, located = located, cityCount = WalletLibrary.cityCount(located), selected = located.first()),
            onAction = {},
            cameraState = rememberOsmCameraState(WorldMap.point(located.first()), WorldMap.MULTI_ZOOM),
            snapshotState = rememberMapSnapshotState(),
        )
    }
}
