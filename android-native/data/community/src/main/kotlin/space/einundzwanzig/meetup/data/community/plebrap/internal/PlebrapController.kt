package space.einundzwanzig.meetup.data.community.plebrap.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.describeError
import space.einundzwanzig.meetup.data.community.plebrap.PlebSong
import space.einundzwanzig.meetup.data.community.plebrap.PlebSongs

/**
 * Player logic of `PlebrapAudio` (spec 11 §2.11), independent of Media3 so it can be unit-tested.
 * Confined to the backend's application thread: every method and listener callback runs there,
 * and [scope] must dispatch on it (main).
 */
internal class PlebrapController(
    private val backend: PlaybackBackend,
    private val cache: AudioCache,
    private val scope: CoroutineScope,
    private val log: DiagnosticLog,
    private val songs: List<PlebSong> = PlebSongs.all,
) : PlaybackBackend.Listener {

    private val _index = MutableStateFlow<Int?>(null)
    private val _loading = MutableStateFlow(false)
    private val _loadErrors = MutableStateFlow(0)
    private val _cacheProgress = MutableStateFlow(0f)
    private val _isPlaying = MutableStateFlow(false)
    private val _durationMs = MutableStateFlow<Long?>(null)
    private val _seekable = MutableStateFlow(false)

    val index: StateFlow<Int?> = _index.asStateFlow()
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    val loadErrors: StateFlow<Int> = _loadErrors.asStateFlow()
    val cacheProgress: StateFlow<Float> = _cacheProgress.asStateFlow()
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    val durationMs: StateFlow<Long?> = _durationMs.asStateFlow()
    val seekable: StateFlow<Boolean> = _seekable.asStateFlow()

    val positionMs: Long get() = backend.positionMs

    private val urls = songs.map { it.url }

    /** The player re-reports the same index while reloading from the cache; do not re-watch then. */
    private var reloading = false
    private var watchJob: Job? = null

    /**
     * `true` once the watched track is known to be completely cached (download finished or cached
     * before). Stays `false` after a failed download, so the next play command retries it.
     */
    private var cacheSettled = false

    init {
        backend.listener = this
    }

    fun playIndex(i: Int) {
        if (i !in songs.indices) return
        _index.value = i
        _loading.value = true
        try {
            if (!backend.hasPlaylist) {
                backend.setPlaylist(urls, i, 0L)
            } else {
                backend.seekTo(i, 0L)
            }
            backend.play()
        } catch (e: Exception) {
            log.diag(TAG, "Song lädt nicht (${songs[i].title}): ${describeError(e)}")
            _loading.value = false
            _loadErrors.value++
            return
        }
        retryCacheIfNeeded()
    }

    fun toggle() {
        if (_index.value == null) {
            playIndex(0)
            return
        }
        if (backend.playWhenReady) {
            backend.pause()
        } else {
            backend.play()
            retryCacheIfNeeded()
        }
    }

    fun next() {
        if (backend.hasNext) {
            backend.seekToNext()
            backend.play()
        }
    }

    fun prev() {
        if (backend.hasPrevious) {
            backend.seekToPrevious()
            backend.play()
        }
    }

    fun seekTo(positionMs: Long) {
        if (backend.hasPlaylist) backend.seekTo(positionMs.coerceAtLeast(0L))
    }

    // ── Backend events ───────────────────────────────────────────────────────────────────

    override fun onIndexChanged(index: Int) {
        // Take the shown title from the PLAYER so display and playback cannot drift apart on auto-advance.
        _index.value = index
        if (!reloading) watchCache(index)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean) {
        _isPlaying.value = playWhenReady
    }

    override fun onDurationChanged(durationMs: Long?) {
        if (_durationMs.value == durationMs) return
        _durationMs.value = durationMs
        // For diagnostics: if this stays "unbekannt", the slider is locked because of the source.
        val title = backend.currentIndex?.let { songs.getOrNull(it)?.title } ?: "?"
        log.diag(TAG, "$title: Dauer ${durationMs?.let { "${it / 1000} s" } ?: "unbekannt"}.")
    }

    override fun onSeekableChanged(seekable: Boolean) {
        _seekable.value = seekable
    }

    override fun onReady() {
        _loading.value = false
    }

    override fun onError(error: Throwable) {
        // Skip broken tracks instead of ending playback.
        val title = backend.currentIndex?.let { songs.getOrNull(it)?.title } ?: "?"
        log.diag(TAG, "Titel lädt nicht ($title): ${describeError(error)} — springe weiter.")
        _loading.value = false
        _loadErrors.value++
        if (backend.hasNext) {
            backend.seekToNext()
            backend.play()
        }
    }

    // ── Cache ────────────────────────────────────────────────────────────────────────────

    /**
     * Follows the download of track [i]. When it is complete but the player still cannot seek
     * (duration unknown / not seekable), the track is reloaded at the current position — now
     * from the cache, so seeking works. Happens once per track.
     */
    private fun watchCache(i: Int) {
        watchJob?.cancel()
        watchJob = null
        cacheSettled = false
        if (i !in songs.indices) return
        val url = songs[i].url
        _cacheProgress.value = 0f
        watchJob = scope.launch {
            try {
                if (cache.isFullyCached(url)) {
                    // Opened from the cache already: seekable from the start, nothing to watch.
                    _cacheProgress.value = 1f
                    cacheSettled = true
                    return@launch
                }
                cache.download(url).collect { _cacheProgress.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.diag(TAG, "Zwischenspeichern fehlgeschlagen (${songs[i].title}): ${describeError(e)}")
                return@launch
            }
            _cacheProgress.value = 1f
            cacheSettled = true
            if (backend.currentIndex != i) return@launch
            if (backend.durationMs != null && backend.isCurrentSeekable) return@launch
            reloadAt(i)
        }
    }

    /**
     * A play command on the track that is already current fires no index change, so a download
     * that failed earlier (network drop) would never be retried; restart it here.
     */
    private fun retryCacheIfNeeded() {
        val i = backend.currentIndex ?: return
        if (cacheSettled || watchJob?.isActive == true) return
        watchCache(i)
    }

    private fun reloadAt(i: Int) {
        if (reloading) return
        reloading = true
        try {
            val pos = backend.positionMs
            val wasPlaying = backend.playWhenReady
            backend.setPlaylist(urls, i, pos)
            log.diag(TAG, "${songs[i].title} vollständig geladen — jetzt springbar.")
            if (wasPlaying) backend.play()
        } catch (e: Exception) {
            log.diag(TAG, "Neuladen aus dem Speicher fehlgeschlagen: ${describeError(e)}")
        } finally {
            reloading = false
        }
    }

    private companion object {
        const val TAG = "PlebRap"
    }
}
