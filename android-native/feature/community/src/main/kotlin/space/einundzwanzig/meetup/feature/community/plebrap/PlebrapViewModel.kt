package space.einundzwanzig.meetup.feature.community.plebrap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.data.community.plebrap.PlebSong
import space.einundzwanzig.meetup.data.community.plebrap.PlebSongs
import space.einundzwanzig.meetup.data.community.plebrap.PlebrapPlayer
import javax.inject.Inject

/** Player screen state (spec 11 §2.11). */
data class PlebrapUiState(
    val index: Int? = null,
    val loading: Boolean = false,
    val playing: Boolean = false,
    val durationMs: Long? = null,
    val seekable: Boolean = false,
    val cacheProgress: Float = 0f,
    val positionMs: Long = 0,
) {
    val current: PlebSong? get() = index?.let { PlebSongs.all.getOrNull(it) }

    /** `prev` enabled iff `index != null && index > 0`. */
    val canPrev: Boolean get() = index != null && index > 0

    /** `next` enabled iff `index != null && index < 40` (last index). */
    val canNext: Boolean get() = index != null && index < PlebSongs.lastIndex

    /** Slider max; `> 0` only while the track can seek (see `PlebrapPlayer.seekable`). */
    val sliderMaxMs: Long get() = if (seekable) durationMs ?: 0 else 0

    /** Caching hint under the disabled slider: shown while a song is current but not seekable. */
    val showCaching: Boolean get() = sliderMaxMs <= 0 && current != null
}

sealed interface PlebrapAction {
    data class PlayIndex(val index: Int) : PlebrapAction
    data object Toggle : PlebrapAction
    data object Next : PlebrapAction
    data object Prev : PlebrapAction
    data class SeekTo(val positionMs: Long) : PlebrapAction
    data object OpenV4v : PlebrapAction
}

@HiltViewModel
class PlebrapViewModel @Inject constructor(
    private val player: PlebrapPlayer,
) : ViewModel() {

    @Volatile
    private var lastPositionMs: Long = 0L

    private data class Core(val index: Int?, val loading: Boolean, val playing: Boolean)
    private data class Track(val durationMs: Long?, val seekable: Boolean, val cacheProgress: Float)

    val state: StateFlow<PlebrapUiState> = combine(
        combine(player.index, player.loading, player.isPlaying, ::Core),
        combine(player.durationMs, player.seekable, player.cacheProgress, ::Track),
        // Seed with the last known position so a re-subscription does not flash 0:00.
        player.positionMs.onStart { emit(lastPositionMs) }.onEach { lastPositionMs = it },
    ) { core, track, position ->
        PlebrapUiState(
            index = core.index,
            loading = core.loading,
            playing = core.playing,
            durationMs = track.durationMs,
            seekable = track.seekable,
            cacheProgress = track.cacheProgress,
            positionMs = position,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PlebrapUiState(
            index = player.index.value,
            loading = player.loading.value,
            playing = player.isPlaying.value,
            durationMs = player.durationMs.value,
            seekable = player.seekable.value,
            cacheProgress = player.cacheProgress.value,
        ),
    )

    /**
     * Emits once for every change of `loadErrors` while it is collected (the route collects only while
     * STARTED) → `prLoadError` snackbar. Each collection skips the replayed current value, so errors
     * that happened before the screen opened or while it was in the background never show late.
     */
    val loadErrorEvents: Flow<Unit> = player.loadErrors
        .drop(1)
        .map { }

    fun onAction(action: PlebrapAction) {
        viewModelScope.launch {
            when (action) {
                is PlebrapAction.PlayIndex -> player.playIndex(action.index)
                PlebrapAction.Toggle -> player.toggle()
                PlebrapAction.Next -> player.next()
                PlebrapAction.Prev -> player.prev()
                is PlebrapAction.SeekTo -> player.seekTo(action.positionMs)
                PlebrapAction.OpenV4v -> Unit // handled by the route
            }
        }
    }
}

/** `fmt(d) = "${d.inMinutes}:${(d.inSeconds % 60).pad2}"`. */
internal fun formatPlaybackTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
