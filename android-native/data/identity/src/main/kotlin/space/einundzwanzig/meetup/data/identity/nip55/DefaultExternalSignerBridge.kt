package space.einundzwanzig.meetup.data.identity.nip55

import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import space.einundzwanzig.meetup.core.common.log.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

/** Activity result of a foreground request. */
internal data class ExternalSignerResult(val resultCode: Int, val data: Intent?)

/**
 * One foreground request at a time. A request that the host already launched stays reserved
 * until its activity result (or launch failure) arrives, even if the waiting coroutine was
 * cancelled meanwhile: Amber is still in front and will answer **that** request. Only then can
 * the next request be published, so a late answer can never complete a newer request (Flutter
 * kept `pendingResult` until `onActivityResult` for the same reason).
 */
@Singleton
internal class DefaultExternalSignerBridge @Inject constructor() : ExternalSignerBridge {

    private class Request(val id: Long, val deferred: CompletableDeferred<ExternalSignerResult>) {
        var launched = false
        var abandoned = false
    }

    private val lock = Any()
    private val pending = MutableStateFlow<ExternalSignerLaunch?>(null)
    private var current: Request? = null
    private var nextId = 1L

    override val pendingLaunch: StateFlow<ExternalSignerLaunch?> = pending.asStateFlow()

    /**
     * Publishes [intent] for the host and suspends until its result.
     *
     * @throws Nip55Exception `busy` if another request is running (including an abandoned one
     *   whose signer activity has not returned yet), `launch_failed` if the host could not start it.
     */
    suspend fun launch(intent: Intent): ExternalSignerResult {
        val request = synchronized(lock) {
            if (current != null) throw Nip55Exception("busy", "Eine Signer-Anfrage läuft bereits")
            Request(nextId++, CompletableDeferred()).also {
                current = it
                pending.value = ExternalSignerLaunch(it.id, intent)
            }
        }
        try {
            return request.deferred.await()
        } catch (e: CancellationException) {
            synchronized(lock) {
                if (current === request) {
                    if (request.launched) {
                        // The signer activity is in front and will answer this request: stay
                        // reserved until onResult/onLaunchFailed.
                        request.abandoned = true
                    } else {
                        release(request)
                    }
                }
            }
            throw e
        }
    }

    /** Clears [request] if it is the current one. Caller holds [lock]. */
    private fun release(request: Request) {
        if (current !== request) return
        current = null
        if (pending.value?.id == request.id) pending.value = null
    }

    override fun onLaunched(id: Long): Boolean = synchronized(lock) {
        val request = current
        val launch = pending.value
        if (request == null || request.id != id || launch == null || launch.id != id || request.launched) {
            false
        } else {
            request.launched = true
            pending.value = launch.copy(launched = true)
            true
        }
    }

    override fun onResult(id: Long, resultCode: Int, data: Intent?) {
        val request = synchronized(lock) {
            val request = current
            if (request == null || request.id != id) {
                AppLogger.debug("AmberSigner", "Verspätete Signer-Antwort ohne passende Anfrage verworfen.")
                return
            }
            release(request)
            request
        }
        if (request.abandoned) {
            AppLogger.debug("AmberSigner", "Antwort auf eine abgebrochene Signer-Anfrage verworfen.")
        }
        request.deferred.complete(ExternalSignerResult(resultCode, data))
    }

    override fun onLaunchFailed(id: Long, error: Throwable) {
        val request = synchronized(lock) {
            val request = current
            if (request == null || request.id != id) return
            release(request)
            request
        }
        request.deferred.completeExceptionally(Nip55Exception("launch_failed", error.message))
    }
}
