package space.einundzwanzig.meetup.data.chat.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.core.nostr.relay.Nip42
import space.einundzwanzig.meetup.core.nostr.relay.RelayAuthenticator
import space.einundzwanzig.meetup.core.nostr.relay.RelayFrames
import space.einundzwanzig.meetup.core.nostr.relay.RelaySocket
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.Duration

/** Signs an event: `(kind, tags, content) → signed event` (Dart `SigningService.signEvent`). */
internal typealias EventSigner = suspend (kind: Int, tags: List<List<String>>, content: String) -> NostrEvent

/**
 * The NIP-42 handshake state machine of the group relay (spec 11 §8.2, Dart
 * `ChatService.authenticateSocket`). One instance per socket.
 *
 * - Only `["OK", <authEventId>, true]` authenticates; there is no grace period.
 * - The same challenge twice starts no second signer prompt; a **changed** challenge during the
 *   initial handshake aborts it (no parallel prompt).
 * - Late signer results or errors after an abort are dropped silently (the signing coroutines are
 *   cancelled when the socket closes, so they neither send nor log).
 * - After success, later challenges are answered on the same socket (renewal); a newer renewal
 *   supersedes a pending one, and a renewal `OK` never changes the authentication state.
 * - Every other decoded array is forwarded to the caller's `onMessage`, also after this returns.
 *
 * Signing runs on the dispatcher of the calling coroutine (virtual time in tests).
 *
 * @param timeout deadline for the whole handshake (Dart `timeout:`; 8 s local key, NIP-46 sign
 *   timeout + 20 s, 25 s Amber).
 */
internal class Nip42Authenticator(
    private val relayUrl: String,
    private val timeout: Duration,
    private val sign: EventSigner,
    private val log: DiagnosticLog = AppLogger,
) : RelayAuthenticator {

    private val lock = Any()
    private val ready = CompletableDeferred<Boolean>()
    private var authEventId: String? = null
    private var activeChallenge: String? = null
    private var authenticated = false
    private var renewalChallenge: String? = null
    private var renewalEventId: String? = null
    private var renewalEpoch = 0

    override suspend fun authenticate(socket: RelaySocket, onMessage: (JsonArray) -> Unit): RelaySocket? {
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor] ?: Dispatchers.Default
        // Signing outlives this call (renewals) but never the socket. The handler is the last line
        // for a non-Exception Throwable of the signer (Dart `catch (e)` caught everything): fail
        // the handshake instead of crashing the app through the uncaught-exception handler.
        val failed = CoroutineExceptionHandler { _, t ->
            log.warn(TAG, "Anmeldung konnte nicht signiert werden", t)
            ready.complete(false)
        }
        val signScope = CoroutineScope(SupervisorJob() + dispatcher + failed)
        socket.invokeOnClose { signScope.cancel() }

        socket.listen(
            onFrame = { frame -> onFrame(frame, socket, signScope, onMessage) },
            onDone = { ready.complete(false) },
        )

        val ok = try {
            withTimeoutOrNull(timeout) { ready.await() } ?: run {
                log.warn(TAG, "Anmeldung nicht rechtzeitig fertig (${timeout.inWholeSeconds} s).")
                if (ready.complete(false)) false else ready.getCompleted()
            }
        } catch (e: CancellationException) {
            ready.complete(false)
            socket.close()
            throw e
        }
        if (!ok) {
            socket.close()
            return null
        }
        return socket
    }

    private fun onFrame(frame: String, socket: RelaySocket, scope: CoroutineScope, onMessage: (JsonArray) -> Unit) {
        val msg = try {
            DartJson.decode(frame) as? JsonArray ?: return
        } catch (_: Exception) {
            return
        }
        if (msg.isEmpty()) return
        val type = msg.stringAt(0)

        // --- AUTH challenge ---
        val challenge = msg.stringAt(1)
        if (type == "AUTH" && msg.size >= 2 && challenge != null) {
            onChallenge(challenge, socket, scope)
            return
        }

        if (type == "OK" && msg.size >= 3) {
            val id = msg.stringAt(1)
            val consumed = synchronized(lock) {
                when {
                    // A renewal OK never changes the authentication that already succeeded.
                    renewalEventId != null && id == renewalEventId -> true
                    authEventId != null && id == authEventId -> {
                        val accepted = msg.isTrue(2)
                        log.debug(
                            TAG,
                            "Anmeldung ${if (accepted) "angenommen" else "abgelehnt: ${if (msg.size >= 4) msg[3].dartToStringOrNull() else ""}"}",
                        )
                        if (!ready.isCompleted) {
                            authenticated = accepted
                            ready.complete(accepted)
                        }
                        true
                    }
                    else -> false
                }
            }
            if (consumed) return
        }

        onMessage(msg)
    }

    private fun onChallenge(challenge: String, socket: RelaySocket, scope: CoroutineScope) {
        val renewalGeneration: Int
        synchronized(lock) {
            if (!ready.isCompleted) {
                if (activeChallenge == challenge) return
                if (activeChallenge != null) {
                    // A changed challenge invalidates the pending signature: abort without a
                    // parallel signer prompt.
                    ready.complete(false)
                    return
                }
                activeChallenge = challenge
                renewalGeneration = INITIAL
            } else {
                // After the first OK the subscription stays open: answer later challenges here.
                if (!authenticated) return
                if (renewalChallenge == challenge) return
                renewalChallenge = challenge
                renewalGeneration = ++renewalEpoch
            }
        }
        scope.launch { signAndSend(challenge, renewalGeneration, socket) }
    }

    private suspend fun signAndSend(challenge: String, generation: Int, socket: RelaySocket) {
        val signed = try {
            sign(Nip42.KIND_CLIENT_AUTH, Nip42.authTags(relayUrl, challenge), "")
        } catch (e: Exception) {
            // Only a real cancellation (socket closed) ends silently; a signer that leaks its own
            // CancellationException (e.g. a TimeoutCancellationException of an inner withTimeout)
            // is a signing failure like any other (Dart `catch (e)`).
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            val report = synchronized(lock) {
                if (generation == INITIAL) {
                    !ready.isCompleted
                } else {
                    if (generation != renewalEpoch) return
                    if (renewalChallenge == challenge) renewalChallenge = null
                    true
                }
            }
            if (!report) return
            log.warn(TAG, "Anmeldung konnte nicht signiert werden", e)
            if (generation == INITIAL) ready.complete(false)
            return
        }
        synchronized(lock) {
            if (generation == INITIAL) {
                if (ready.isCompleted) return
                authEventId = signed.id
            } else {
                if (generation != renewalEpoch) return
                renewalEventId = signed.id
            }
        }
        socket.send(RelayFrames.auth(signed))
    }

    private fun JsonArray.stringAt(i: Int): String? = (getOrNull(i) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonArray.isTrue(i: Int): Boolean =
        (getOrNull(i) as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true

    private companion object {
        const val TAG = "Chat"

        /** Generation marker of the initial handshake (renewals count from 1). */
        const val INITIAL = 0
    }
}
