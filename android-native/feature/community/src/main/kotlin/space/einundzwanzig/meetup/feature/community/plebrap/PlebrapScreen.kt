package space.einundzwanzig.meetup.feature.community.plebrap

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import space.einundzwanzig.meetup.core.common.dart.dartRound
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.AppTopBar
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.core.ui.component.showAppSnackbar
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.MonoStyle
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.community.CommunityLinks
import space.einundzwanzig.meetup.data.community.plebrap.PlebSong
import space.einundzwanzig.meetup.data.community.plebrap.PlebSongs
import space.einundzwanzig.meetup.feature.community.R
import space.einundzwanzig.meetup.feature.community.common.ObserveAsEvents
import space.einundzwanzig.meetup.feature.community.v4v.FLUTTER_SNACKBAR_MS
import space.einundzwanzig.meetup.core.l10n.R as L10nR

internal object PlebrapTestTags {
    const val LIST = "plebrapList"
    const val PLAY = "plebrapPlay"
    const val PREV = "plebrapPrev"
    const val NEXT = "plebrapNext"
    const val SLIDER = "plebrapSlider"
    const val CACHING = "plebrapCaching"
    const val PLAY_LOADING = "plebrapPlayLoading"
    fun track(i: Int) = "plebrapTrack$i"
}

@Composable
fun PlebrapRoute(viewModel: PlebrapViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val loadError = stringResource(L10nR.string.prLoadError)
    ObserveAsEvents(viewModel.loadErrorEvents) {
        snackbar.showAppSnackbar(loadError, tone = Tone.Error, durationMillis = FLUTTER_SNACKBAR_MS)
    }
    PlebrapScreen(
        state = state,
        onBack = navigator::back,
        snackbarHostState = snackbar,
        onAction = { action ->
            if (action == PlebrapAction.OpenV4v) navigator.openUrl(CommunityLinks.PLEBRAP_V4V) else viewModel.onAction(action)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlebrapScreen(
    state: PlebrapUiState,
    onAction: (PlebrapAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = "PlebRap",
                onBack = onBack,
                scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
                actions = {
                    TextButton(onClick = { onAction(PlebrapAction.OpenV4v) }) {
                        Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(L10nR.string.prV4V))
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            NowPlayingCard(state, onAction, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp))
            TrackList(activeIndex = state.index, onPlay = { onAction(PlebrapAction.PlayIndex(it)) })
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NowPlayingCard(state: PlebrapUiState, onAction: (PlebrapAction) -> Unit, modifier: Modifier = Modifier) {
    val song = state.current
    val orange = MaterialTheme.colorScheme.primary
    val ext = MaterialTheme.extendedColors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box {
            if (song != null) {
                AsyncImage(
                    model = song.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 20.dp, y = 30.dp)
                        .size(180.dp)
                        .alpha(0.10f),
                )
            }
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CoverThumb(song)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        AnimatedContent(
                            targetState = song,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "nowPlaying",
                        ) { s ->
                            Column {
                                Text(
                                    s?.title ?: stringResource(L10nR.string.prPickSong),
                                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    s?.artist ?: PlebSongs.FALLBACK_ARTIST,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                SeekBar(state, onSeek = { onAction(PlebrapAction.SeekTo(it)) })
                AnimatedVisibility(visible = state.showCaching) {
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp).testTag(PlebrapTestTags.CACHING)) {
                        val p = state.cacheProgress
                        if (p > 0f) {
                            LinearWavyProgressIndicator(
                                progress = { p },
                                modifier = Modifier.fillMaxWidth(),
                                color = orange,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            )
                        } else {
                            LinearWavyProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = orange,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            )
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            stringResource(L10nR.string.prCaching, (p.toDouble() * 100).dartRound().toInt()),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = ext.textTertiary,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Controls(state, onAction)
            }
        }
    }
}

@Composable
private fun CoverThumb(song: PlebSong?) {
    val orange = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.size(52.dp).clip(shape).background(orange.copy(alpha = 0.12f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = orange, modifier = Modifier.size(24.dp))
        if (song != null) {
            AsyncImage(
                model = song.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(state: PlebrapUiState, onSeek: (Long) -> Unit) {
    val max = state.sliderMaxMs
    // dragMs: the thumb follows the finger, the seek only happens on release (onChangeEnd).
    var dragMs by remember { mutableStateOf<Long?>(null) }
    val shownMs = if (max > 0) (dragMs ?: state.positionMs).coerceIn(0, max) else 0L
    val seekLabel = stringResource(R.string.native_community_seek)
    Slider(
        value = if (max > 0) shownMs.toFloat() else 0f,
        onValueChange = { dragMs = it.toLong() },
        onValueChangeFinished = {
            dragMs?.let(onSeek)
            dragMs = null
        },
        valueRange = 0f..(if (max > 0) max.toFloat() else 1f),
        enabled = max > 0,
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PlebrapTestTags.SLIDER)
            .semantics { contentDescription = seekLabel },
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        val style = MonoStyle.copy(fontSize = 11.sp)
        Text(formatPlaybackTime(dragMs ?: state.positionMs), style = style, color = MaterialTheme.extendedColors.textTertiary)
        Spacer(Modifier.weight(1f))
        Text(formatPlaybackTime(state.durationMs ?: 0), style = style, color = MaterialTheme.extendedColors.textTertiary)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Controls(state: PlebrapUiState, onAction: (PlebrapAction) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { onAction(PlebrapAction.Prev) },
            enabled = state.canPrev,
            modifier = Modifier.size(52.dp).testTag(PlebrapTestTags.PREV),
        ) {
            Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.native_community_previous), Modifier.size(30.dp))
        }
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = { onAction(PlebrapAction.Toggle) },
            modifier = Modifier
                .size(IconButtonDefaults.mediumContainerSize(IconButtonDefaults.IconButtonWidthOption.Wide))
                .testTag(PlebrapTestTags.PLAY),
            shapes = IconButtonDefaults.shapes(),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            if (state.loading) {
                AppLoadingIndicator(
                    size = 32.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag(PlebrapTestTags.PLAY_LOADING),
                )
            } else {
                Icon(
                    if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(
                        if (state.playing) R.string.native_community_pause else R.string.native_community_play,
                    ),
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = { onAction(PlebrapAction.Next) },
            enabled = state.canNext,
            modifier = Modifier.size(52.dp).testTag(PlebrapTestTags.NEXT),
        ) {
            Icon(Icons.Rounded.SkipNext, stringResource(R.string.native_community_next), Modifier.size(30.dp))
        }
    }
}

@Composable
private fun TrackList(activeIndex: Int?, onPlay: (Int) -> Unit) {
    val orange = MaterialTheme.colorScheme.primary
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(PlebrapTestTags.LIST),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PlebSongs.albums.forEach { album ->
            item(key = "album-$album") {
                Row(
                    Modifier.padding(top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Album, contentDescription = null, tint = orange, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        album.uppercase(),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                        ),
                        color = orange,
                    )
                    Spacer(Modifier.width(10.dp))
                    HorizontalDivider(Modifier.weight(1f), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            items(PlebSongs.byAlbum[album].orEmpty(), key = { "track-${it.index}" }) { (i, song) ->
                TrackRow(song, active = i == activeIndex, onClick = { onPlay(i) }, modifier = Modifier.testTag(PlebrapTestTags.track(i)))
            }
        }
    }
}

@Composable
private fun TrackRow(song: PlebSong, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val orange = MaterialTheme.colorScheme.primary
    val ext = MaterialTheme.extendedColors
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().semantics { selected = active },
        shape = RoundedCornerShape(10.dp),
        color = if (active) orange.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainer,
        border = if (active) BorderStroke(1.dp, orange.copy(alpha = 0.5f)) else BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (active) Icons.Rounded.Equalizer else Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = if (active) orange else ext.textTertiary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                    color = if (active) orange else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    song.artist,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = ext.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Preview
@Composable
private fun PlebrapScreenPreview() {
    EinundzwanzigTheme {
        PlebrapScreen(
            state = PlebrapUiState(index = 2, playing = true, durationMs = 200_000, seekable = true, positionMs = 61_000),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PlebrapScreenCachingPreview() {
    EinundzwanzigTheme {
        PlebrapScreen(state = PlebrapUiState(index = 0, loading = true, cacheProgress = 0.42f), onAction = {}, onBack = {})
    }
}
