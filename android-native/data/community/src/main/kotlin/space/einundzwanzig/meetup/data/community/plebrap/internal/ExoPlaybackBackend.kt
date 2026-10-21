package space.einundzwanzig.meetup.data.community.plebrap.internal

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import space.einundzwanzig.meetup.data.community.plebrap.PlebSong
import space.einundzwanzig.meetup.data.community.plebrap.PlebSongs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PlaybackBackend] on one process-wide [ExoPlayer] (never released, like Flutter's
 * `PlebrapAudio.player`). The same [player] is wrapped by the `MediaSession` of
 * `PlebrapPlaybackService`, so notification / headset commands act on it directly and are seen
 * through the listener. Main thread only.
 */
@OptIn(UnstableApi::class)
@Singleton
internal class ExoPlaybackBackend @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cache: Media3AudioCache,
) : PlaybackBackend {

    override var listener: PlaybackBackend.Listener? = null

    private val songsByUrl: Map<String, Pair<Int, PlebSong>> =
        PlebSongs.all.withIndex().associate { (i, s) -> s.url to (i to s) }

    /** The player; created on first access (must be the main thread). */
    val player: ExoPlayer by lazy {
        check(Looper.myLooper() == Looper.getMainLooper()) { "PlebRap player must be created on the main thread" }
        ExoPlayer.Builder(context)
            .setLooper(Looper.getMainLooper())
            .setMediaSourceFactory(DefaultMediaSourceFactory(cache.playerDataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_OFF
                addListener(events)
            }
    }

    private val events = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (player.mediaItemCount > 0) listener?.onIndexChanged(player.currentMediaItemIndex)
            reportDuration()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            listener?.onPlayWhenReadyChanged(this@ExoPlaybackBackend.playWhenReady)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) listener?.onReady()
            // Entering/leaving ENDED changes the effective play intent without a playWhenReady change.
            listener?.onPlayWhenReadyChanged(this@ExoPlaybackBackend.playWhenReady)
            reportDuration()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            reportDuration()
        }

        override fun onPlayerError(error: PlaybackException) {
            listener?.onError(error)
        }
    }

    /** Reports duration and seekability; both change together (timeline, state, item). */
    private fun reportDuration() {
        val duration = durationMs
        listener?.onDurationChanged(duration)
        listener?.onSeekableChanged(duration != null && player.isCurrentMediaItemSeekable)
    }

    override val hasPlaylist: Boolean get() = player.mediaItemCount > 0

    override val currentIndex: Int? get() = if (player.mediaItemCount > 0) player.currentMediaItemIndex else null

    /**
     * Effective play intent: `playWhenReady`, but `false` once the playlist has ENDED (ExoPlayer
     * keeps `playWhenReady = true` there), so the UI shows "play" and the next toggle restarts.
     */
    override val playWhenReady: Boolean
        get() = player.playWhenReady && player.playbackState != Player.STATE_ENDED

    override val durationMs: Long? get() = player.duration.takeIf { it != C.TIME_UNSET && it >= 0 }

    override val isCurrentSeekable: Boolean get() = player.isCurrentMediaItemSeekable

    override val positionMs: Long get() = player.currentPosition

    override val hasNext: Boolean get() = player.hasNextMediaItem()

    override val hasPrevious: Boolean get() = player.hasPreviousMediaItem()

    override fun setPlaylist(urls: List<String>, startIndex: Int, startPositionMs: Long) {
        player.setMediaItems(urls.map(::mediaItem), startIndex, startPositionMs)
        player.prepare()
    }

    override fun seekTo(index: Int, positionMs: Long) {
        player.seekTo(index, positionMs)
        prepareIfIdle()
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    override fun seekToNext() {
        player.seekToNextMediaItem()
        prepareIfIdle()
    }

    override fun seekToPrevious() {
        player.seekToPreviousMediaItem()
        prepareIfIdle()
    }

    /**
     * Same semantics as the notification's play button (`Util.handlePlayButtonAction`): prepares an
     * IDLE player and, in ENDED, seeks to the start of the current item first — a bare
     * `player.play()` is a no-op there.
     */
    override fun play() {
        if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition(player.currentMediaItemIndex)
        prepareIfIdle()
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    /** After an error the player is IDLE and must be prepared again before it plays. */
    private fun prepareIfIdle() {
        if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) player.prepare()
    }

    /** URI from the raw string (no re-encoding); metadata for the notification / system UI. */
    private fun mediaItem(url: String): MediaItem {
        val (index, song) = songsByUrl[url] ?: return MediaItem.fromUri(url)
        return MediaItem.Builder()
            .setMediaId("plebrap:$index")
            .setUri(Uri.parse(url))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .setArtworkUri(Uri.parse(song.cover))
                    .setTrackNumber(index + 1)
                    .setTotalTrackCount(PlebSongs.all.size)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build(),
            )
            .build()
    }
}
