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
 * Signing runs on the dispatcher of the calling coroutine (virtual time in tests) while frames
 * arrive on the transport thread. All state, every completion of the handshake and the AUTH send
 * itself happen under one lock, so an abort or a newer renewal can never interleave between
 * "still current?" and the send (Dart was single-threaded and got this for free).
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
            // Complete first, log only if this ended the handshake: at most one WARN per handshake.
            if (abort()) log.warn(TAG, "Anmeldung konnte nicht signiert werden", t)
        }
        val signScope = CoroutineScope(SupervisorJob() + dispatcher + failed)
        socket.invokeOnClose { signScope.cancel() }

        socket.listen(
            onFrame = { frame -> onFrame(frame, socket, signScope, onMessage) },
            onDone = { abort() },
        )

        val ok = try {
            withTimeoutOrNull(timeout) { ready.await() } ?: run {
                // Only the path that actually completes [ready] logs (a signer failure may race).
                if (abort()) {
                    log.warn(TAG, "Anmeldung nicht rechtzeitig fertig (${timeout.inWholeSeconds} s).")
                    false
                } else {
                    ready.getCompleted()
                }
            }
        } catch (e: CancellationException) {
            abort()
            socket.close()
            throw e
        }
        if (!ok) {
            socket.close()
            return null
        }
        return socket
    }

    /**
     * Ends the initial handshake as failed, under [lock]: [signAndSend] decides "still current"
     * and sends its AUTH frame in one critical section, so after this returns no AUTH frame of the
     * aborted handshake can go out (spec 11 §8.2). `true` if this call ended the handshake.
     */
    private fun abort(): Boolean = synchronized(lock) { ready.complete(false) }

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
            // Decided under the lock, logged outside it (this runs on the transport thread).
            val authAnswer: Boolean?
            val consumed = synchronized(lock) {
                when {
                    // A renewal OK never changes the authentication that already succeeded.
                    renewalEventId != null && id == renewalEventId -> {
                        authAnswer = null
                        true
                    }
                    authEventId != null && id == authEventId -> {
                        val accepted = msg.isTrue(2)
                        authAnswer = accepted
                        if (!ready.isCompleted) {
                            authenticated = accepted
                            ready.complete(accepted)
                        }
                        true
                    }
                    else -> {
                        authAnswer = null
                        false
                    }
                }
            }
            if (authAnswer != null) {
                log.debug(
                    TAG,
                    "Anmeldung ${if (authAnswer) "angenommen" else "abgelehnt: ${if (msg.size >= 4) msg[3].dartToStringOrNull() else ""}"}",
                )
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
            // Decided atomically: the initial handshake reports only if this call completed [ready]
            // (the deadline or a changed challenge may have ended it first).
            val report = synchronized(lock) {
                if (generation == INITIAL) {
                    ready.complete(false)
                } else {
                    if (generation != renewalEpoch) return
                    if (renewalChallenge == challenge) renewalChallenge = null
                    true
                }
            }
            if (!report) return
            log.warn(TAG, "Anmeldung konnte nicht signiert werden", e)
            return
        }
        val frame = RelayFrames.auth(signed)
        // Check, record and send atomically: an abort (deadline, changed challenge, socket end) or
        // a newer renewal challenge on another thread either happens before (nothing is sent) or
        // after the frame went out. RelaySocket.send only enqueues and never blocks, and the
        // socket invokes no listener while holding its own lock, so sending here cannot deadlock.
        synchronized(lock) {
            if (generation == INITIAL) {
                if (ready.isCompleted) return
                authEventId = signed.id
            } else {
                if (generation != renewalEpoch) return
                renewalEventId = signed.id
            }
            socket.send(frame)
        }
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
