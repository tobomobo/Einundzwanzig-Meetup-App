package space.einundzwanzig.meetup.data.community.plebrap.internal

import kotlinx.coroutines.flow.Flow

/**
 * The parts of a playlist player that [PlebrapController] needs. Production: [ExoPlaybackBackend]
 * (Media3 ExoPlayer). Must be used on its application thread (main).
 */
internal interface PlaybackBackend {
    /** Receives player events; set once by the controller. */
    var listener: Listener?

    /** `true` once a playlist was handed to the player. */
    val hasPlaylist: Boolean

    /** Current item index, `null` without playlist. */
    val currentIndex: Int?

    /** Play intent (`playWhenReady`; `false` once the playlist has ended). */
    val playWhenReady: Boolean

    /** Duration of the current item in ms, `null` if unknown. */
    val durationMs: Long?

    /** Whether the current item supports seeking. */
    val isCurrentSeekable: Boolean

    /** Position in the current item in ms. */
    val positionMs: Long

    val hasNext: Boolean
    val hasPrevious: Boolean

    /** Replaces the playlist with [urls] starting at [startIndex]/[startPositionMs] and prepares it. */
    fun setPlaylist(urls: List<String>, startIndex: Int, startPositionMs: Long)

    /** Seeks to [index]/[positionMs] (prepares again if the player is idle after an error). */
    fun seekTo(index: Int, positionMs: Long)

    /** Seeks within the current item. */
    fun seekTo(positionMs: Long)

    fun seekToNext()
    fun seekToPrevious()

    /** Starts/resumes playback (prepares again if idle; restarts the current item if ended). */
    fun play()
    fun pause()

    /** Player events, delivered on the application thread. */
    interface Listener {
        /** The current item changed (auto-advance, seek, new playlist). */
        fun onIndexChanged(index: Int)

        /** Play intent changed (also from the notification / headset, or the playlist ending). */
        fun onPlayWhenReadyChanged(playWhenReady: Boolean)

        /** Duration of the current item became known/changed (`null` = unknown). */
        fun onDurationChanged(durationMs: Long?)

        /** Whether the current item can seek now (duration known and the source is seekable). */
        fun onSeekableChanged(seekable: Boolean)

        /** The player is ready to play the current item. */
        fun onReady()

        /** The current item failed to load/play. */
        fun onError(error: Throwable)
    }
}

/** Download cache for the audio files (Media3 `SimpleCache` in production). */
internal interface AudioCache {
    /** `true` if [url] is completely in the cache (length known and all bytes cached). */
    suspend fun isFullyCached(url: String): Boolean

    /**
     * Downloads [url] completely into the cache. Emits progress `0.0..1.0` (`0.0` while the total
     * size is unknown) and completes when the file is cached. Throws on download failure;
     * cancelling the collector stops the download.
     */
    fun download(url: String): Flow<Float>
}
