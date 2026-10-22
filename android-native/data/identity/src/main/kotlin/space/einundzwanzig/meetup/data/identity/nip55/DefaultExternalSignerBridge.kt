package space.einundzwanzig.meetup.data.identity.nip55

import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import space.einundzwanzig.meetup.core.common.log.AppLogger
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
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
 *
 * Request ids start at a random value per process, so an activity result that a recreated
 * Activity delivers for a request of a dead process never matches a new request; results are
 * also only accepted for a request the host actually launched.
 *
 * A request is only published while a host is attached ([attachHost]); without one, [launch]
 * waits at most [HOST_WAIT_MS] (configuration change) and then fails with `cancelled`. The same
 * applies to a published request that the host has not launched yet: if every host detaches and
 * none returns within [HOST_WAIT_MS], the request is released and fails with `cancelled`. So a
 * background caller never holds the single slot indefinitely, and Amber never pops up out of
 * context when the Activity comes back later.
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
    private val hosts = MutableStateFlow(0)

    // Random 62-bit start: never 0 (the host's "nothing launched" value), never repeats across processes.
    private var nextId = (SecureRandom().nextLong() ushr 2) + 1

    override val pendingLaunch: StateFlow<ExternalSignerLaunch?> = pending.asStateFlow()

    override fun attachHost(): AutoCloseable {
        hosts.update { it + 1 }
        val closed = AtomicBoolean(false)
        return AutoCloseable { if (closed.compareAndSet(false, true)) hosts.update { it - 1 } }
    }

    /**
     * Publishes [intent] for the host and suspends until its result.
     *
     * @throws Nip55Exception `busy` if another request is running (including an abandoned one
     *   whose signer activity has not returned yet), `launch_failed` if the host could not start it,
     *   `cancelled` if no host attached within [HOST_WAIT_MS] (before publishing, or while the
     *   published request was not launched yet).
     */
    suspend fun launch(intent: Intent): ExternalSignerResult {
        if (hosts.value <= 0) {
            withTimeoutOrNull(HOST_WAIT_MS) { hosts.first { it > 0 } }
                ?: throw Nip55Exception("cancelled", "Keine Oberfläche für die Signer-Anfrage")
        }
        val request = synchronized(lock) {
            if (current != null) throw Nip55Exception("busy", "Eine Signer-Anfrage läuft bereits")
            Request(nextId++, CompletableDeferred()).also {
                current = it
                pending.value = ExternalSignerLaunch(it.id, intent)
            }
        }
        try {
            return awaitResult(request)
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

    /**
     * Awaits [request]'s result while a watchdog releases it with `cancelled` if all hosts are
     * gone for [HOST_WAIT_MS] before the request was launched (a host that detaches right after
     * publishing, e.g. the Activity stopping, would otherwise strand the request and the slot).
     */
    private suspend fun awaitResult(request: Request): ExternalSignerResult = coroutineScope {
        val watchdog = launch {
            while (true) {
                hosts.first { it <= 0 }
                if (withTimeoutOrNull(HOST_WAIT_MS) { hosts.first { it > 0 } } != null) continue
                val orphaned = synchronized(lock) {
                    (current === request && !request.launched).also { if (it) release(request) }
                }
                if (orphaned) {
                    AppLogger.debug("AmberSigner", "Signer-Anfrage ohne Oberfläche verworfen.")
                    request.deferred.completeExceptionally(
                        Nip55Exception("cancelled", "Keine Oberfläche für die Signer-Anfrage"),
                    )
                }
                return@launch
            }
        }
        try {
            request.deferred.await()
        } finally {
            watchdog.cancel()
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
            if (request == null || request.id != id || !request.launched) {
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

    internal companion object {
        /** How long [launch] waits for a host (configuration change) before failing with `cancelled`. */
        const val HOST_WAIT_MS = 5_000L
    }
}
