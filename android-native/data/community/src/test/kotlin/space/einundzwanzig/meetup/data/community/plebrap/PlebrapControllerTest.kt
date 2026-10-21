package space.einundzwanzig.meetup.data.community.plebrap

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.data.community.plebrap.internal.AudioCache
import space.einundzwanzig.meetup.data.community.plebrap.internal.PlaybackBackend
import space.einundzwanzig.meetup.data.community.plebrap.internal.PlebrapController
import java.io.IOException

/** `PlebrapAudio` logic (spec 11 §2.11) against a fake player and a fake download cache. */
class PlebrapControllerTest {

    /** Synchronous player fake that reports events like ExoPlayer on its application thread. */
    private class FakeBackend(private val size: Int) : PlaybackBackend {
        override var listener: PlaybackBackend.Listener? = null
        val calls = mutableListOf<String>()
        var items: List<String> = emptyList()
        override var currentIndex: Int? = null
        override var playWhenReady = false
        override var durationMs: Long? = null
        override var isCurrentSeekable = false
        override var positionMs = 0L
        var failSetPlaylist = false

        override val hasPlaylist get() = items.isNotEmpty()
        override val hasNext get() = currentIndex != null && currentIndex!! < size - 1
        override val hasPrevious get() = currentIndex != null && currentIndex!! > 0

        override fun setPlaylist(urls: List<String>, startIndex: Int, startPositionMs: Long) {
            if (failSetPlaylist) throw IOException("boom")
            calls += "setPlaylist($startIndex,$startPositionMs)"
            items = urls
            positionMs = startPositionMs
            moveTo(startIndex, force = true)
        }

        override fun seekTo(index: Int, positionMs: Long) {
            calls += "seekTo($index,$positionMs)"
            this.positionMs = positionMs
            moveTo(index)
        }

        override fun seekTo(positionMs: Long) {
            calls += "seek($positionMs)"
            this.positionMs = positionMs
        }

        override fun seekToNext() {
            calls += "next"
            moveTo(currentIndex!! + 1)
        }

        override fun seekToPrevious() {
            calls += "prev"
            moveTo(currentIndex!! - 1)
        }

        override fun play() {
            calls += "play"
            if (!playWhenReady) {
                playWhenReady = true
                listener?.onPlayWhenReadyChanged(true)
            }
        }

        override fun pause() {
            calls += "pause"
            if (playWhenReady) {
                playWhenReady = false
                listener?.onPlayWhenReadyChanged(false)
            }
        }

        private fun moveTo(i: Int, force: Boolean = false) {
            val changed = i != currentIndex
            currentIndex = i
            if (changed || force) {
                durationMs = null
                isCurrentSeekable = false
                listener?.onIndexChanged(i)
            }
        }

        /** Simulates the player finishing a track by itself. */
        fun autoAdvance() = moveTo(currentIndex!! + 1)
    }

    private class FakeCache : AudioCache {
        val cached = mutableSetOf<String>()
        private val sinks = mutableMapOf<String, (Float) -> Unit>()
        private val completions = mutableMapOf<String, CompletableDeferred<Unit>>()
        val started = mutableListOf<String>()
        val cancelled = mutableListOf<String>()

        override suspend fun isFullyCached(url: String) = url in cached

        override fun download(url: String): Flow<Float> = channelFlow {
            started += url
            val done = CompletableDeferred<Unit>().also { completions[url] = it }
            sinks[url] = { p -> trySend(p) }
            try {
                done.await()
            } catch (e: CancellationException) {
                cancelled += url
                throw e
            }
            cached += url
        }

        fun progress(url: String, p: Float) = sinks.getValue(url)(p)
        fun complete(url: String) = completions.getValue(url).complete(Unit)
        fun fail(url: String) = completions.getValue(url).completeExceptionally(IOException("offline"))
    }

    private val songs = PlebSongs.all
    private val dispatcher = StandardTestDispatcher()
    private val controllerJob = SupervisorJob()
    private val backend = FakeBackend(songs.size)
    private val cache = FakeCache()
    private val controller = PlebrapController(backend, cache, CoroutineScope(dispatcher + controllerJob), AppLogger, songs)

    private fun url(i: Int) = songs[i].url

    @Test
    fun toggleWithoutSelectionStartsFirstSong() = test {
        assertNull(controller.index.value)
        controller.toggle()
        assertEquals(listOf("setPlaylist(0,0)", "play"), backend.calls)
        assertEquals(0, controller.index.value)
        assertTrue(controller.isPlaying.value)
        assertTrue(controller.loading.value)
        backend.listener!!.onReady()
        assertFalse(controller.loading.value)
    }

    @Test
    fun playIndexSetsPlaylistOnceThenSeeks() = test {
        controller.playIndex(5)
        controller.playIndex(7)
        assertEquals(listOf("setPlaylist(5,0)", "play", "seekTo(7,0)", "play"), backend.calls)
        assertEquals(7, controller.index.value)
    }

    @Test
    fun playIndexIgnoresOutOfRange() = test {
        controller.playIndex(41)
        controller.playIndex(-1)
        assertTrue(backend.calls.isEmpty())
        assertNull(controller.index.value)
    }

    @Test
    fun playIndexFailureCountsLoadError() = test {
        backend.failSetPlaylist = true
        controller.playIndex(2)
        assertEquals(1, controller.loadErrors.value)
        assertFalse(controller.loading.value)
        assertEquals(2, controller.index.value)
    }

    @Test
    fun togglePausesAndResumes() = test {
        controller.playIndex(0)
        controller.toggle()
        assertFalse(controller.isPlaying.value)
        controller.toggle()
        assertTrue(controller.isPlaying.value)
        assertEquals(listOf("setPlaylist(0,0)", "play", "pause", "play"), backend.calls)
    }

    @Test
    fun nextAndPrevDoNotWrap() = test {
        controller.next()
        controller.prev()
        assertTrue(backend.calls.isEmpty())

        controller.playIndex(0)
        backend.calls.clear()
        controller.prev()
        assertTrue(backend.calls.isEmpty())
        controller.next()
        assertEquals(1, controller.index.value)
        assertEquals(listOf("next", "play"), backend.calls)
        controller.prev()
        assertEquals(0, controller.index.value)

        controller.playIndex(40)
        backend.calls.clear()
        controller.next()
        assertTrue(backend.calls.isEmpty())
        assertEquals(40, controller.index.value)
    }

    @Test
    fun indexFollowsThePlayerOnAutoAdvance() = test {
        controller.playIndex(3)
        backend.autoAdvance()
        assertEquals(4, controller.index.value)
    }

    @Test
    fun brokenTrackIsSkipped() = test {
        controller.playIndex(10)
        backend.calls.clear()
        backend.listener!!.onError(IOException("404"))
        assertEquals(1, controller.loadErrors.value)
        assertEquals(11, controller.index.value)
        assertEquals(listOf("next", "play"), backend.calls)
    }

    @Test
    fun brokenLastTrackStops() = test {
        controller.playIndex(40)
        backend.calls.clear()
        backend.listener!!.onError(IOException("404"))
        assertEquals(1, controller.loadErrors.value)
        assertEquals(40, controller.index.value)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun completedDownloadReloadsUnseekableTrackAtSamePosition() = test {
        controller.playIndex(4)
        runCurrent()
        assertEquals(listOf(url(4)), cache.started)
        cache.progress(url(4), 0.5f)
        runCurrent()
        assertEquals(0.5f, controller.cacheProgress.value)

        backend.positionMs = 61_000
        backend.calls.clear()
        cache.complete(url(4))
        advanceUntilIdle()

        assertEquals(1f, controller.cacheProgress.value)
        assertEquals(listOf("setPlaylist(4,61000)", "play"), backend.calls)
        // The reload re-reports index 4 but must not start another watch.
        assertEquals(listOf(url(4)), cache.started)
        assertEquals(4, controller.index.value)
    }

    @Test
    fun reloadKeepsPausedState() = test {
        controller.playIndex(4)
        runCurrent()
        controller.toggle() // pause
        backend.calls.clear()
        cache.complete(url(4))
        advanceUntilIdle()
        assertEquals(listOf("setPlaylist(4,0)"), backend.calls)
        assertFalse(controller.isPlaying.value)
    }

    @Test
    fun noReloadWhenAlreadySeekable() = test {
        controller.playIndex(4)
        runCurrent()
        backend.durationMs = 180_000
        backend.isCurrentSeekable = true
        backend.calls.clear()
        cache.complete(url(4))
        advanceUntilIdle()
        assertTrue(backend.calls.isEmpty())
        assertEquals(1f, controller.cacheProgress.value)
    }

    @Test
    fun noReloadWhenTrackChangedMeanwhileAndOldDownloadIsCancelled() = test {
        controller.playIndex(4)
        runCurrent()
        controller.next()
        runCurrent()
        assertEquals(listOf(url(4)), cache.cancelled)
        assertEquals(listOf(url(4), url(5)), cache.started)
        assertEquals(0f, controller.cacheProgress.value)
    }

    @Test
    fun alreadyCachedTrackNeedsNoDownload() = test {
        cache.cached += url(9)
        controller.playIndex(9)
        advanceUntilIdle()
        assertTrue(cache.started.isEmpty())
        assertEquals(1f, controller.cacheProgress.value)
        assertEquals(listOf("setPlaylist(9,0)", "play"), backend.calls)
    }

    @Test
    fun failedDownloadLeavesPlaybackAlone() = test {
        controller.playIndex(4)
        runCurrent()
        backend.calls.clear()
        cache.fail(url(4))
        advanceUntilIdle()
        assertTrue(backend.calls.isEmpty())
        assertEquals(0, controller.loadErrors.value)
    }

    @Test
    fun failedDownloadIsRetriedByPlayIndexOnSameTrack() = test {
        controller.playIndex(4)
        runCurrent()
        cache.fail(url(4))
        advanceUntilIdle()
        assertEquals(listOf(url(4)), cache.started)

        // Same index: the player fires no index change, the controller restarts the download itself.
        controller.playIndex(4)
        runCurrent()
        assertEquals(listOf(url(4), url(4)), cache.started)

        // While that download runs, another play command does not start a third one.
        controller.playIndex(4)
        runCurrent()
        assertEquals(2, cache.started.size)

        backend.calls.clear()
        cache.complete(url(4))
        advanceUntilIdle()
        assertEquals(1f, controller.cacheProgress.value)
        assertEquals(listOf("setPlaylist(4,0)", "play"), backend.calls)
    }

    @Test
    fun failedDownloadIsRetriedOnResume() = test {
        controller.playIndex(4)
        runCurrent()
        cache.fail(url(4))
        advanceUntilIdle()
        controller.toggle() // pause: no retry
        runCurrent()
        assertEquals(1, cache.started.size)
        controller.toggle() // resume
        runCurrent()
        assertEquals(listOf(url(4), url(4)), cache.started)
    }

    @Test
    fun settledTrackIsNotDownloadedAgain() = test {
        cache.cached += url(9)
        controller.playIndex(9)
        advanceUntilIdle()
        controller.playIndex(9)
        controller.toggle()
        controller.toggle()
        advanceUntilIdle()
        assertTrue(cache.started.isEmpty())

        controller.playIndex(4)
        runCurrent()
        cache.complete(url(4))
        advanceUntilIdle()
        controller.playIndex(4)
        advanceUntilIdle()
        assertEquals(listOf(url(4)), cache.started)
    }

    @Test
    fun seekableFollowsTheBackend() = test {
        assertFalse(controller.seekable.value)
        controller.playIndex(0)
        backend.listener!!.onSeekableChanged(true)
        assertTrue(controller.seekable.value)
        backend.listener!!.onSeekableChanged(false)
        assertFalse(controller.seekable.value)
    }

    @Test
    fun durationAndSeek() = test {
        controller.seekTo(1000)
        assertTrue(backend.calls.isEmpty())
        controller.playIndex(0)
        backend.listener!!.onDurationChanged(200_000)
        assertEquals(200_000L, controller.durationMs.value)
        controller.seekTo(-5)
        controller.seekTo(90_000)
        assertEquals(listOf("setPlaylist(0,0)", "play", "seek(0)", "seek(90000)"), backend.calls)
        backend.listener!!.onDurationChanged(null)
        assertNull(controller.durationMs.value)
    }

    @Test
    fun notificationPauseIsReflected() = test {
        controller.playIndex(0)
        backend.listener!!.onPlayWhenReadyChanged(false)
        assertFalse(controller.isPlaying.value)
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            block()
        } finally {
            controllerJob.cancel()
        }
    }
}
