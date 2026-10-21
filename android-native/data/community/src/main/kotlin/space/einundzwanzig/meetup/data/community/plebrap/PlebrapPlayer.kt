package space.einundzwanzig.meetup.data.community.plebrap

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app-wide PlebRap player (port of the `PlebrapAudio` singleton, spec 11 §2.11). The full
 * player screen and the dashboard mini player observe and control the same instance; playback
 * keeps running after leaving the screen. Unlike Flutter, playback runs in
 * [PlebrapPlaybackService] (Media3 `MediaSessionService`) with a media notification and system
 * media controls (lock screen, Bluetooth).
 *
 * Playlist = all [PlebSongs.all] in order, no repeat. Broken tracks are skipped automatically
 * ([loadErrors] increments). Every track is downloaded completely to a local cache on first play
 * (the plebrap.de server has neither range requests nor Content-Length, so a streamed track is
 * not seekable); when the download finishes and the player still cannot seek, the track is
 * reloaded from the cache at the same position. Second play needs no network.
 *
 * All functions are main-safe.
 */
interface PlebrapPlayer {
    /** Index into [PlebSongs.all] of the current track; `null` until something was chosen. */
    val index: StateFlow<Int?>

    /** `true` while a chosen track is loading (spinner on the play button). */
    val loading: StateFlow<Boolean>

    /** Monotonic count of track load errors; show `prLoadError` whenever it changes while visible. */
    val loadErrors: StateFlow<Int>

    /** Download progress of the current track, `0.0..1.0`. `0.0` also means "unknown" (indeterminate). */
    val cacheProgress: StateFlow<Float>

    /** Play intent (just_audio `playing`): `true` after play, `false` after pause, also while buffering. */
    val isPlaying: StateFlow<Boolean>

    /** Duration of the current track in ms; `null` while unknown. */
    val durationMs: StateFlow<Long?>

    /**
     * `true` when the current track can seek right now: duration known **and** the source is
     * seekable. Drives the seek slider (enabled only when `true`) and the caching hint (shown while
     * `false` and a track is current). Stricter than `durationMs != null`: a streamed MP3 can report
     * a duration from its header while the source still cannot seek until it is cached.
     */
    val seekable: StateFlow<Boolean>

    /** Playback position of the current track in ms, sampled while collected (~5 Hz). */
    val positionMs: Flow<Long>

    /** The current song, or `null` (`PlebrapAudio.current`). */
    val current: PlebSong? get() = index.value?.let { PlebSongs.all.getOrNull(it) }

    /** Plays track [i] (`playIndex`); sets up the playlist on first use. Out-of-range indices are ignored. */
    suspend fun playIndex(i: Int)

    /** Play/pause; starts track 0 if nothing was chosen yet. */
    suspend fun toggle()

    /** Next track and play, if there is one (no wrap-around). */
    suspend fun next()

    /** Previous track and play, if there is one (always the previous item, never "restart current"). */
    suspend fun prev()

    /** Seeks within the current track (slider `onChangeEnd`). */
    suspend fun seekTo(positionMs: Long)
}
