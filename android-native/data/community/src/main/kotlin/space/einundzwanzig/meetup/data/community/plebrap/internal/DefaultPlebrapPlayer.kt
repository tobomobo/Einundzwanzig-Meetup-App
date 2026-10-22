package space.einundzwanzig.meetup.data.community.plebrap.internal

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.describeError
import space.einundzwanzig.meetup.data.community.plebrap.PlebrapPlaybackService
import space.einundzwanzig.meetup.data.community.plebrap.PlebrapPlayer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PlebrapPlayer] = [PlebrapController] on the [ExoPlaybackBackend], plus the binding to
 * [PlebrapPlaybackService] (a `MediaController` connected on first use) so Media3 shows the media
 * notification and runs playback as a foreground service.
 */
@Singleton
internal class DefaultPlebrapPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backend: ExoPlaybackBackend,
    cache: Media3AudioCache,
    private val log: DiagnosticLog,
) : PlebrapPlayer {

    private val main = Dispatchers.Main.immediate

    private val scope = CoroutineScope(
        SupervisorJob() + main + CoroutineExceptionHandler { _, e -> log.warn(TAG, "Unerwarteter Fehler", e) },
    )

    private val controller = PlebrapController(backend, cache, scope, log)

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Pending or connected controller of [PlebrapPlaybackService]; main thread only. */
    private var serviceConnection: ListenableFuture<MediaController>? = null

    /** The player the service's MediaSession wraps (main thread). */
    val sessionPlayer: Player get() = backend.player

    override val index: StateFlow<Int?> get() = controller.index
    override val loading: StateFlow<Boolean> get() = controller.loading
    override val loadErrors: StateFlow<Int> get() = controller.loadErrors
    override val cacheProgress: StateFlow<Float> get() = controller.cacheProgress
    override val isPlaying: StateFlow<Boolean> get() = controller.isPlaying
    override val durationMs: StateFlow<Long?> get() = controller.durationMs
    override val seekable: StateFlow<Boolean> get() = controller.seekable

    override val positionMs: Flow<Long> = flow {
        while (true) {
            emit(if (controller.index.value == null) 0L else controller.positionMs)
            delay(POSITION_INTERVAL_MS)
        }
    }.distinctUntilChanged().flowOn(main)

    override suspend fun playIndex(i: Int) = onMain {
        ensureService()
        controller.playIndex(i)
    }

    override suspend fun toggle() = onMain {
        ensureService()
        controller.toggle()
    }

    override suspend fun next() = onMain {
        ensureService()
        controller.next()
    }

    override suspend fun prev() = onMain {
        ensureService()
        controller.prev()
    }

    override suspend fun seekTo(positionMs: Long) = onMain {
        ensureService()
        controller.seekTo(positionMs)
    }

    private suspend inline fun onMain(crossinline block: () -> Unit) = withContext(main) { block() }

    /**
     * Binds the playback service if no controller is connected (or connecting). Media3 then
     * creates the session, posts the notification while playing and promotes the service to the
     * foreground. A failed connection or a later disconnect (session released in the service's
     * `onDestroy`) clears the binding, so the next command binds again. Main thread only.
     */
    private fun ensureService() {
        if (serviceConnection != null) return
        val token = SessionToken(context, ComponentName(context, PlebrapPlaybackService::class.java))
        lateinit var future: ListenableFuture<MediaController>
        future = MediaController.Builder(context, token)
            .setApplicationLooper(Looper.getMainLooper())
            .setListener(
                object : MediaController.Listener {
                    override fun onDisconnected(controller: MediaController) {
                        log.diag(TAG, "Wiedergabedienst getrennt.")
                        if (serviceConnection === future) serviceConnection = null
                    }
                },
            )
            .buildAsync()
        serviceConnection = future
        future.addListener(
            {
                try {
                    future.get()
                } catch (e: Exception) {
                    log.diag(TAG, "Wiedergabedienst nicht verbunden: ${describeError(e)}")
                    if (serviceConnection === future) serviceConnection = null
                }
            },
            { r -> mainHandler.post(r) },
        )
    }

    private companion object {
        const val TAG = "PlebRap"
        const val POSITION_INTERVAL_MS = 200L
    }
}
