package space.einundzwanzig.meetup.data.identity.nip46

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.dartNumOrNull
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.crypto.CryptoRandom
import space.einundzwanzig.meetup.core.crypto.DartUtf8
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.Keys
import space.einundzwanzig.meetup.core.crypto.Nip44
import space.einundzwanzig.meetup.data.identity.internal.dartToString
import space.einundzwanzig.meetup.data.identity.internal.dartToStringOrEmpty
import kotlin.time.Duration
import kotlin.time.TimeSource as KotlinTimeSource

/**
 * NIP-46 remote-signer client (port of `lib/services/nip46/nip46_client.dart`, spec 01 §6.2).
 *
 * The app talks to the signer with its **own random session key** ([clientSecretKeyHex]), never
 * with the user's key. Requests are kind-24133 events with NIP-44 content, sent to **every** open
 * relay; the first valid answer wins. A dead relay never fails the others.
 *
 * Thread-safe: frames arrive on transport threads, RPCs run in any coroutine.
 *
 * @param clientSecretKeyHex the app-side session key (64 hex). Never exposed again: it must
 *   only live in the secure store (spec 01 §9).
 * @param relays the signer's relays (non-empty).
 * @param remoteSignerPubkey the paired signer (hex or npub); `null` for a `nostrconnect://`
 *   pairing that has not completed yet.
 * @param onAuthUrl called when the signer demands a browser approval (`auth_url`); the request
 *   stays pending. Runs on the relay transport thread: do not block, hop to Main for UI work.
 * @param timeouts deadlines; [Nip46Timeouts.uniform] for tests.
 * @param openRelay relay transport.
 * @param timeSource wall clock (`since` of the subscription, `created_at`, relay cooldown).
 * @throws Nip46Exception for an empty relay list or an invalid session key.
 */
class Nip46Client(
    private val clientSecretKeyHex: String,
    relays: List<String>,
    remoteSignerPubkey: String? = null,
    private val onAuthUrl: ((String) -> Unit)? = null,
    private val timeouts: Nip46Timeouts = Nip46Timeouts(),
    private val openRelay: Nip46RelayOpener,
    private val timeSource: TimeSource,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** The relays of this session (unmodifiable). */
    val relays: List<String> = relays.toList()

    /** BIP-340 x-only pubkey of [clientSecretKeyHex]. */
    val clientPubkeyHex: String = derivePubkey(clientSecretKeyHex)

    private val keyPair: KeyPair = KeyPair.fromPrivateKeyHex(clientSecretKeyHex)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private val subId = "nip46-${CryptoRandom.hex(8)}"

    // --- state, guarded by [lock] ---
    private val links = LinkedHashMap<String, Nip46RelayLink>()

    /** Links that are subscribing (listen + REQ) but not yet usable for requests. */
    private val opening = HashMap<String, Nip46RelayLink>()
    private val relayCooldownUntil = HashMap<String, Long>()
    private val pending = HashMap<String, CompletableDeferred<JsonElement?>>()
    private val waitingForApproval = HashSet<String>()
    private val publishes = HashMap<String, Publish>()
    private var remote: String? = remoteSignerPubkey?.let { BunkerPointer.normalizePubkey(it) }
    private var cachedKey: ByteArray? = null
    private var cachedKeyFor: String? = null
    private var connecting: Deferred<Unit>? = null
    private var pairing: CompletableDeferred<String>? = null
    private var pairingSecret: String? = null
    private var undecryptable = 0
    private var foreignSender = 0
    private var closed = false

    init {
        if (this.relays.isEmpty()) throw Nip46Exception("Für den Remote-Signer ist kein Relay angegeben.")
    }

    /** The paired signer's pubkey (hex), or `null` while a pairing is pending. */
    val remoteSignerPubkey: String? get() = synchronized(lock) { remote }

    /** `true` once a signer is known. */
    val isPaired: Boolean get() = remoteSignerPubkey != null

    // ---------- RPC methods ----------

    /** Registers the app with the signer; always 4 params `[signer, secret|"", perms, metadata]`. */
    suspend fun connect(secret: String? = null) {
        val signer = remoteSignerPubkey ?: throw Nip46Exception("Noch kein Signer gekoppelt.")
        rpc(
            "connect",
            listOf(signer, secret ?: "", REQUESTED_PERMS, DartJson.encode(Nip46ClientMetadata.values)),
            timeouts.connect,
        )
    }

    /** The **user's** pubkey (hex); npub answers are accepted. */
    suspend fun getPublicKey(): String {
        val result = rpc("get_public_key", emptyList(), timeouts.connect)
        return BunkerPointer.normalizePubkey(result.dartToString())
    }

    /**
     * Has [unsigned] (keys `pubkey, created_at, kind, tags, content`) signed and returns the
     * signer's event object (string or object answer).
     */
    suspend fun signEvent(unsigned: JsonObject): JsonObject {
        val result = rpc("sign_event", listOf(DartJson.encode(unsigned)), timeouts.sign)
        return decodeSigned(result) ?: throw Nip46Exception("Der Signer hat kein lesbares Event zurückgegeben.")
    }

    /** NIP-44 encryption by the signer to [pubkeyHex]. */
    suspend fun nip44Encrypt(pubkeyHex: String, plaintext: String): String =
        rpc("nip44_encrypt", listOf(pubkeyHex, plaintext), timeouts.sign).dartToString()

    /** NIP-44 decryption by the signer from [pubkeyHex]. */
    suspend fun nip44Decrypt(pubkeyHex: String, payload: String): String =
        rpc("nip44_decrypt", listOf(pubkeyHex, payload), timeouts.sign).dartToString()

    /** `true` on `pong`; any [Nip46Exception] (timeout, closed, …) reads as `false`. */
    suspend fun ping(): Boolean = try {
        rpc("ping", emptyList(), timeouts.ping).dartToString() == "pong"
    } catch (e: Nip46Exception) {
        false
    }

    /**
     * Waits until a signer answers the `nostrconnect://` URI with [secret]; returns its pubkey.
     * Returns immediately if already paired.
     *
     * @throws Nip46TimeoutException (`method = "pairing"`) after [timeout].
     */
    suspend fun awaitPairing(secret: String, timeout: Duration = timeouts.pairing): String {
        remoteSignerPubkey?.let { return it }
        val deferred = CompletableDeferred<String>()
        // Registered before the relays open: an echo that arrives while the other relays are
        // still connecting must not be dropped, and close() must be able to fail it.
        synchronized(lock) {
            if (closed) throw Nip46Exception("Kopplung wurde abgebrochen.")
            pairing = deferred
            pairingSecret = secret
        }
        try {
            remoteSignerPubkey?.let { return it }
            ensureConnected()
            return withTimeout(timeout) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw Nip46TimeoutException("pairing", timeout, synchronized(lock) { undecryptable })
        } finally {
            synchronized(lock) {
                if (pairing === deferred) {
                    pairing = null
                    pairingSecret = null
                }
            }
        }
    }

    /**
     * Closes every relay link, fails pending requests (`Verbindung zum Signer wurde geschlossen.`)
     * and a pending pairing (`Kopplung wurde abgebrochen.`). Every later RPC throws.
     */
    suspend fun close() {
        val toClose: List<Nip46RelayLink>
        val toFail: List<CompletableDeferred<JsonElement?>>
        val pairingToFail: CompletableDeferred<String>?
        synchronized(lock) {
            closed = true
            toClose = links.values.toList() + opening.values
            links.clear()
            opening.clear()
            toFail = pending.values.toList()
            pending.clear()
            publishes.clear()
            pairingToFail = pairing
        }
        for (link in toClose) {
            try {
                link.close()
            } catch (_: Exception) {
            }
        }
        toFail.forEach { it.completeExceptionally(Nip46Exception("Verbindung zum Signer wurde geschlossen.")) }
        pairingToFail?.completeExceptionally(Nip46Exception("Kopplung wurde abgebrochen."))
        scope.cancel()
    }

    // ---------- transport ----------

    private suspend fun rpc(method: String, params: List<String>, timeout: Duration): JsonElement? {
        if (synchronized(lock) { closed }) throw Nip46Exception("Die Signer-Sitzung ist geschlossen.")
        val signer = remoteSignerPubkey ?: throw Nip46Exception("Noch kein Signer gekoppelt.")
        ensureConnected()

        val id = CryptoRandom.hex(8)
        val payload = DartJson.encode(jsonObjectOf("id" to id, "method" to method, "params" to params))
        val (frame, eventId) = buildFrame(signer, payload)
        val isNip44 = method == "nip44_decrypt" || method == "nip44_encrypt"

        val deferred = CompletableDeferred<JsonElement?>()
        val publish = Publish(id)
        val targets = synchronized(lock) {
            pending[id] = deferred
            publishes[eventId] = publish
            // Relay order of the session (deterministic, like the Dart map filled in relay order).
            links.entries.sortedBy { relays.indexOf(it.key) }.map { it.key to it.value }
        }
        val mark = KotlinTimeSource.Monotonic.markNow()
        if (isNip44) {
            AppLogger.info(
                "Nip46Transport",
                "$method: ${DartUtf8.encode(frame).size} Rahmen-Bytes, ${targets.size} Relay-Verbindungen.",
            )
        }

        try {
            for ((url, link) in targets) {
                synchronized(lock) { publish.destinations += url }
                try {
                    link.send(frame)
                } catch (e: Exception) {
                    synchronized(lock) { publish.destinations -= url }
                    AppLogger.warn("Nip46", "Senden an ein Relay fehlgeschlagen", e)
                }
            }
            val rejectedByAll = synchronized(lock) {
                publish.sendingDone = true
                if (publish.destinations.isEmpty()) {
                    pending.remove(id)
                    throw Nip46Exception("Kein Relay des Signers ist erreichbar.")
                }
                allRejectedLocked(publish)
            }
            if (rejectedByAll != null) failRejected(publish, rejectedByAll)

            try {
                val response = withTimeout(timeout) { deferred.await() }
                if (isNip44) {
                    AppLogger.info("Nip46Transport", "$method: Antwort nach ${mark.elapsedNow().inWholeMilliseconds} ms.")
                }
                return response
            } catch (e: TimeoutCancellationException) {
                val waited = mark.elapsedNow()
                val (undec, foreign, waiting) = synchronized(lock) {
                    pending.remove(id)
                    Triple(undecryptable, foreignSender, id in waitingForApproval)
                }
                val (accepted, rejected, acceptedRelays, sent) = synchronized(lock) {
                    PublishStats(publish.accepted, publish.rejected.size, publish.acceptedRelays.toList(), publish.destinations.size)
                }
                AppLogger.warn(
                    "Nip46Transport",
                    "$method: Timeout; Relay-Annahme bestätigt: $accepted; abgewiesen: $rejected/$sent; " +
                        "angenommene Relays: ${if (acceptedRelays.isEmpty()) "keine" else acceptedRelays.joinToString(", ")}; " +
                        "Freigabe angekündigt: $waiting.",
                )
                AppLogger.warn(
                    "Nip46",
                    "$method: keine Antwort nach ${waited.inWholeMilliseconds} ms " +
                        "(unlesbar: $undec, fremder Absender: $foreign)",
                )
                throw Nip46TimeoutException(method, waited, undec, foreign, waiting)
            }
        } finally {
            synchronized(lock) {
                pending.remove(id)
                publishes.remove(eventId)
                waitingForApproval.remove(id)
            }
        }
    }

    private data class PublishStats(val accepted: Boolean, val rejected: Int, val acceptedRelays: List<String>, val sent: Int)

    private fun conversationKey(pubkey: String): ByteArray {
        synchronized(lock) {
            if (cachedKeyFor == pubkey) return cachedKey!!
        }
        val key = Nip44.conversationKey(clientSecretKeyHex, pubkey)
        synchronized(lock) {
            // Only the paired signer is cached: relay spam must not grow an unbounded cache.
            if (pubkey == remote) {
                cachedKey = key
                cachedKeyFor = pubkey
            }
        }
        return key
    }

    /** Returns the `["EVENT", …]` frame and the event id (needed to map relay `OK`s). */
    private fun buildFrame(signer: String, payload: String): Pair<String, String> {
        val content = Nip44.encrypt(payload, conversationKey(signer))
        val event = EventIds.sign(keyPair, timeSource.nowSeconds(), EVENT_KIND, listOf(listOf("p", signer)), content)
        return event.toEventMessage() to event.id
    }

    /** Opens the links once; concurrent callers share the same attempt. */
    private suspend fun ensureConnected() {
        val attempt = synchronized(lock) {
            if (closed) throw Nip46Exception("Die Signer-Sitzung ist geschlossen.")
            if (links.isNotEmpty()) return
            connecting ?: scope.async { openAll() }.also { connecting = it }
        }
        try {
            attempt.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The shared attempt was cancelled by close(), not this caller: report a closed session.
            currentCoroutineContext().ensureActive()
            throw Nip46Exception("Die Signer-Sitzung ist geschlossen.")
        } finally {
            synchronized(lock) { if (connecting === attempt) connecting = null }
        }
    }

    private suspend fun openAll() {
        coroutineScope { relays.map { url -> async { openLink(url) } }.awaitAll() }
        if (synchronized(lock) { links.isEmpty() }) {
            throw Nip46Exception("Kein Relay des Signers ist erreichbar. Ist das Gerät online?")
        }
    }

    /** Opens one relay; failures stay here (a dead relay must not take the others down). */
    private suspend fun openLink(url: String) {
        val until = synchronized(lock) { relayCooldownUntil[url] }
        if (until != null && timeSource.nowMillis() < until) return
        try {
            val link = withTimeout(timeouts.relayOpen) { openRelay.open(url) }
            val isClosed = synchronized(lock) {
                if (!closed) opening[url] = link
                closed
            }
            if (isClosed) {
                link.close()
                return
            }
            try {
                link.listen(
                    onFrame = { data ->
                        if (synchronized(lock) { links[url] === link || opening[url] === link }) onMessage(url, data)
                    },
                    onDone = { error -> dropLink(url, link, closeSocket = error != null) },
                )
                // `since` keeps stored answers out; the tolerance covers a skewed device clock.
                link.send(
                    DartJson.encode(
                        listOf(
                            "REQ",
                            subId,
                            linkedMapOf(
                                "kinds" to listOf(EVENT_KIND),
                                "#p" to listOf(clientPubkeyHex),
                                "since" to timeSource.nowSeconds() - 60,
                            ),
                        ),
                    ),
                )
            } catch (e: Exception) {
                synchronized(lock) { if (opening[url] === link) opening.remove(url) }
                closeQuietly(link)
                throw e
            }
            // Only a subscribed link takes requests (a concurrent rpc must never publish on a
            // link without subscription). A link that ended or a closed client is discarded.
            val registered = synchronized(lock) {
                val stillOpening = opening[url] === link
                if (stillOpening) opening.remove(url)
                if (stillOpening && !closed) {
                    links[url] = link
                    true
                } else {
                    false
                }
            }
            if (!registered) closeQuietly(link)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException && e !is TimeoutCancellationException) throw e
            AppLogger.warn("Nip46", "Relay des Signers nicht erreichbar: $url", e)
        }
    }

    /**
     * Removes [url]'s link. A late `onDone` of an old socket must not remove a newer link for the
     * same URL ([expected] identity check). The socket is closed asynchronously.
     *
     * With [expected] `null` (relay `CLOSED`, cooldown) a link that is still subscribing is dropped
     * as well: its subscription is dead, so [openLink] must not register it as usable.
     */
    private fun dropLink(url: String, expected: Nip46RelayLink?, closeSocket: Boolean = true) {
        val link = synchronized(lock) {
            val subscribing = opening[url]
            if (subscribing != null && (expected == null || subscribing === expected)) {
                // Ended while subscribing: openLink sees it is gone, discards and closes it.
                opening.remove(url)
                return
            }
            if (expected != null && links[url] !== expected) return
            links.remove(url) ?: return
        }
        if (!closeSocket) return
        scope.launch {
            try {
                link.close()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun closeQuietly(link: Nip46RelayLink) {
        try {
            link.close()
        } catch (_: Exception) {
        }
    }

    private fun onMessage(url: String, data: String) {
        try {
            val decoded = DartJson.decode(data)
            if (decoded !is JsonArray || decoded.isEmpty()) return
            val type = (decoded[0] as? JsonPrimitive)?.takeIf { it.isString }?.content

            if (type == "OK") {
                handleRelayAck(url, decoded)
                return
            }
            if (type == "CLOSED") {
                AppLogger.warn(
                    "Nip46",
                    "Ein Relay hat das Abo beendet: ${if (decoded.size > 2) decoded[2].dartToString() else "ohne Angabe"}",
                )
                dropLink(url, expected = null)
                return
            }
            if (decoded.size < 3 || type != "EVENT") return
            val event = decoded[2] as? JsonObject ?: return
            if (event["kind"].dartNumOrNull() != EVENT_KIND.toDouble()) return

            val sender = event["pubkey"].dartToStringOrEmpty()
            if (sender.length != 64) return

            synchronized(lock) {
                val paired = remote
                if (paired != null && sender != paired) {
                    foreignSender++
                    return
                }
            }
            // The event signature is deliberately not checked: authenticity follows from the
            // ECDH conversation key; a replayed answer carries a stale id.
            val key = conversationKey(sender)
            val plain = Nip44.decrypt(event["content"].dartToStringOrEmpty(), key)
            val payload = DartJson.decode(plain) as? JsonObject ?: return
            dispatch(sender, payload)
        } catch (e: Exception) {
            // Deliberately silent: this subscription also sees events not meant for this session.
            // The counter is reported with the timeout, where it is the diagnosis.
            synchronized(lock) { undecryptable++ }
        }
    }

    /** `["OK", <event-id>, <bool>, "<reason>"]`; fails the request only when **all** relays rejected. */
    private fun handleRelayAck(url: String, message: JsonArray) {
        if (message.size < 3) return
        var dropForCooldown = false
        val failure: Pair<Publish, String>? = synchronized(lock) {
            val publish = publishes[message[1].dartToString()] ?: return
            if (url !in publish.destinations) return
            if ((message[2] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true) {
                publish.acceptedRelays += url
                AppLogger.debug("Nip46Transport", "Anfrage von $url angenommen.")
                return
            }
            if (url in publish.acceptedRelays) return
            val reason = if (message.size > 3) message[3].dartToString() else ""
            publish.rejected[url] = reason
            AppLogger.warn("Nip46Transport", "$url hat die Anfrage abgelehnt: $reason")
            if (reason.startsWith("blocked:") || reason.startsWith("banned:") || reason.startsWith("rate-limited:")) {
                relayCooldownUntil[url] = timeSource.nowMillis() + timeouts.relayCooldown.inWholeMilliseconds
                dropForCooldown = true
            }
            allRejectedLocked(publish)?.let { publish to it }
        }
        if (dropForCooldown) dropLink(url, expected = null)
        failure?.let { (publish, reason) -> failRejected(publish, reason) }
    }

    /** The reason to fail with if every relay rejected [publish], otherwise `null`. Caller holds [lock]. */
    private fun allRejectedLocked(publish: Publish): String? {
        if (!publish.sendingDone || publish.accepted || publish.rejected.size < publish.destinations.size) return null
        if (publish.destinations.isEmpty()) return null
        return publish.rejected.values.lastOrNull()?.takeIf { it.isNotEmpty() } ?: "ohne Angabe"
    }

    private fun failRejected(publish: Publish, reason: String) {
        val deferred = synchronized(lock) { pending.remove(publish.requestId) } ?: return
        if (deferred.isCompleted) return
        AppLogger.warn("Nip46", "Alle ${publish.destinations.size} Relays haben abgelehnt: $reason")
        deferred.completeExceptionally(Nip46RelayRejectedException(reason))
    }

    private fun dispatch(sender: String, payload: JsonObject) {
        val id = payload["id"]?.takeUnless { it is JsonNull }?.dartToString() ?: ""
        val result = payload["result"]?.takeUnless { it is JsonNull }
        val error = payload["error"]?.takeUnless { it is JsonNull }?.dartToString() ?: ""
        val resultText = result?.dartToString()

        val completed: CompletableDeferred<String>?
        val authUrl: String?
        val target: CompletableDeferred<JsonElement?>?
        synchronized(lock) {
            val p = pairing
            val secret = pairingSecret
            if (p != null && secret != null && resultText == secret) {
                remote = sender
                completed = p
                authUrl = null
                target = null
            } else {
                completed = null
                if (!pending.containsKey(id)) return
                if (resultText == "auth_url") {
                    // Interim answer: the real one follows with the same id, keep it pending.
                    waitingForApproval += id
                    authUrl = error.ifEmpty { null }
                    target = null
                } else {
                    authUrl = null
                    target = pending.remove(id)
                }
            }
        }
        if (completed != null) {
            completed.complete(sender)
            AppLogger.security("Nip46", "Signer gekoppelt: ${sender.substring(0, 12)}…")
            return
        }
        if (authUrl != null) {
            onAuthUrl?.invoke(authUrl)
            return
        }
        val deferred = target ?: return
        if (deferred.isCompleted) return
        if (error.isNotEmpty()) {
            deferred.completeExceptionally(Nip46RemoteException(error))
        } else {
            deferred.complete(result)
        }
    }

    private class Publish(val requestId: String) {
        val destinations = LinkedHashSet<String>()
        val acceptedRelays = LinkedHashSet<String>()
        val rejected = LinkedHashMap<String, String>()
        var sendingDone = false
        val accepted: Boolean get() = acceptedRelays.isNotEmpty()
    }

    companion object {
        /** NIP-46 event kind. */
        const val EVENT_KIND: Int = 24133

        /**
         * Kinds whose signing permission is requested on connect (every kind the app signs on
         * app start / regularly). Unchanged from Dart: extending it only affects new pairings.
         */
        val SIGNED_KINDS: List<Int> = listOf(21000, 21001, 21002, 21003, 22242, 30021, 30078, 30079, 31922, 31923)

        /** `get_public_key,nip44_encrypt,nip44_decrypt,sign_event:<kind>…` (exact Dart string). */
        val REQUESTED_PERMS: String =
            (listOf("get_public_key", "nip44_encrypt", "nip44_decrypt") + SIGNED_KINDS.map { "sign_event:$it" })
                .joinToString(",")

        /** A new app-side session key (never the user's key). */
        fun generateClientKey(): String = Keys.generatePrivHex()

        /** One-time pairing secret, 16 hex characters. */
        fun generateSecret(): String = CryptoRandom.hex(8)

        private fun derivePubkey(privkeyHex: String): String {
            if (privkeyHex.length != 64) throw Nip46Exception("Der Sitzungsschlüssel der App ist ungültig.")
            return try {
                Keys.pubHexFromPriv(privkeyHex)
            } catch (e: Exception) {
                throw Nip46Exception("Der Sitzungsschlüssel der App ist ungültig.")
            }
        }

        private fun decodeSigned(result: JsonElement?): JsonObject? = when (result) {
            is JsonObject -> result
            is JsonPrimitive -> if (result.isString) {
                runCatching { DartJson.decode(result.content) as? JsonObject }.getOrNull()
            } else {
                null
            }
            else -> null
        }
    }
}
