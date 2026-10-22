package space.einundzwanzig.meetup.data.identity.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.common.DefaultDispatcher
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.url.SafeUrl
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.data.identity.AmberConnectResult
import space.einundzwanzig.meetup.data.identity.IdentityStorageException
import space.einundzwanzig.meetup.data.identity.Nip07ConnectResult
import space.einundzwanzig.meetup.data.identity.Nip46ConnectResult
import space.einundzwanzig.meetup.data.identity.Nip46Pairing
import space.einundzwanzig.meetup.data.identity.RecommendedExistingPath
import space.einundzwanzig.meetup.data.identity.SigningError
import space.einundzwanzig.meetup.data.identity.SigningException
import space.einundzwanzig.meetup.data.identity.SigningMode
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.identity.keys.IdentityKeys
import space.einundzwanzig.meetup.data.identity.keys.SecureKeyStore
import space.einundzwanzig.meetup.data.identity.nip46.BunkerPointer
import space.einundzwanzig.meetup.data.identity.nip46.Nip46Client
import space.einundzwanzig.meetup.data.identity.nip46.Nip46ClientMetadata
import space.einundzwanzig.meetup.data.identity.nip46.Nip46Exception
import space.einundzwanzig.meetup.data.identity.nip46.Nip46TimeoutException
import space.einundzwanzig.meetup.data.identity.nip55.Nip55Transport
import space.einundzwanzig.meetup.data.identity.signer.AmberNostrSigner
import space.einundzwanzig.meetup.data.identity.signer.LocalNostrSigner
import space.einundzwanzig.meetup.data.identity.signer.Nip07NostrSigner
import space.einundzwanzig.meetup.data.identity.signer.Nip46NostrSigner
import space.einundzwanzig.meetup.data.identity.signer.NostrSigner
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.TimeSource as KotlinTimeSource

/** Creates NIP-46 clients; a seam for tests (fake relays). */
internal fun interface Nip46ClientFactory {
    fun create(
        clientSecretKeyHex: String,
        relays: List<String>,
        remoteSignerPubkey: String?,
        onAuthUrl: (String) -> Unit,
    ): Nip46Client
}

@Singleton
internal class DefaultSigningService @Inject constructor(
    private val prefs: KeyValueStore,
    private val keyStore: SecureKeyStore,
    private val relayConfig: RelayConfig,
    private val nip55: Nip55Transport,
    private val clientFactory: Nip46ClientFactory,
    private val timeSource: TimeSource,
    @param:DefaultDispatcher private val cpu: CoroutineDispatcher,
    private val identitySwitch: IdentitySwitchBarrier? = null,
) : SigningService {

    private val readyFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    // replay = 1: an approval that arrives without a collector waits for the UI (acknowledgeAuthUrl clears it).
    private val authUrlFlow = MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = 7,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val authUrlLock = Any()
    private val authHandlers = ArrayList<(String) -> Unit>()

    /** The held NIP-46 session (process lifetime). */
    @Volatile
    private var nip46: Nip46Client? = null
    private val sessionMutex = Mutex()

    override val signerReady: SharedFlow<Unit> = readyFlow.asSharedFlow()
    override val authUrlEvents: SharedFlow<String> = authUrlFlow.asSharedFlow()

    override fun interceptAuthUrls(handler: (String) -> Unit): AutoCloseable {
        synchronized(authHandlers) { authHandlers += handler }
        return AutoCloseable { synchronized(authHandlers) { authHandlers.remove(handler) } }
    }

    private fun onAuthUrl(url: String) {
        val handler = synchronized(authHandlers) { authHandlers.lastOrNull() }
        if (handler != null) {
            try {
                handler(url)
                return
            } catch (e: Exception) {
                // Feature code on the relay reader thread: never let it swallow the prompt.
                AppLogger.warn("Nip46", "auth_url-Handler fehlgeschlagen (Host: ${SafeUrl.hostForLog(url)})", e)
            }
        }
        if (authUrlFlow.subscriptionCount.value == 0) {
            AppLogger.info(
                "Nip46",
                "Freigabe-Aufforderung des Signers wartet auf die Oberfläche (Host: ${SafeUrl.hostForLog(url)})",
            )
        }
        synchronized(authUrlLock) { authUrlFlow.tryEmit(url) }
    }

    override fun acknowledgeAuthUrl(url: String) {
        synchronized(authUrlLock) {
            if (authUrlFlow.replayCache.lastOrNull() == url) authUrlFlow.resetReplayCache()
        }
    }

    private fun noteSignerReady() {
        readyFlow.tryEmit(Unit)
    }

    // =============== mode ===============

    override suspend fun getMode(): SigningMode = SigningMode.fromStored(prefs.getString(IdentityKeys.SIGNING_MODE))

    private suspend fun setMode(mode: SigningMode) {
        // Leaving nip46 clears the session at once (no zombie subscriptions, no stale session key).
        if (mode != SigningMode.NIP46) clearNip46Artifacts()
        prefs.setStringOrThrow(IdentityKeys.SIGNING_MODE, mode.stored)
    }

    override suspend fun isExternalSigner(): Boolean = getMode().isExternal

    override suspend fun recommendedExistingPath(): RecommendedExistingPath =
        if (isAmberInstalled()) RecommendedExistingPath.AMBER else RecommendedExistingPath.BUNKER

    override fun isAmberInstalled(): Boolean = try {
        nip55.isInstalled()
    } catch (e: Exception) {
        AppLogger.warn("AmberSigner", "isAppInstalled fehlgeschlagen: $e")
        false
    }

    override suspend fun nip07Available(): Boolean = false

    // =============== signer resolution ===============

    override suspend fun activeSigner(): NostrSigner = when (getMode()) {
        SigningMode.AMBER -> {
            val hex = prefs.getString(IdentityKeys.AMBER_PUBKEY_HEX)
            if (hex.isNullOrEmpty()) {
                throw SigningException(
                    "Amber-Modus aktiv, aber kein verbundener Schlüssel. Bitte Amber erneut verbinden.",
                    error = SigningError.ModeNoKey(SigningMode.AMBER),
                )
            }
            AmberNostrSigner(hex, nip55, timeSource)
        }
        SigningMode.NIP07 -> {
            val hex = prefs.getString(IdentityKeys.NIP07_PUBKEY_HEX)
            if (hex.isNullOrEmpty()) {
                throw SigningException(
                    "Erweiterungs-Modus aktiv, aber kein verbundener Schlüssel. Bitte die Browsererweiterung erneut verbinden.",
                    error = SigningError.ModeNoKey(SigningMode.NIP07),
                )
            }
            Nip07NostrSigner(hex)
        }
        SigningMode.NIP46 -> {
            val hex = prefs.getString(IdentityKeys.NIP46_PUBKEY_HEX)
            if (hex.isNullOrEmpty()) {
                throw SigningException(
                    "Bunker-Modus aktiv, aber kein verbundener Schlüssel. Bitte den Signer erneut verbinden.",
                    error = SigningError.ModeNoKey(SigningMode.NIP46),
                )
            }
            Nip46NostrSigner(hex, ::nip46Session, timeSource)
        }
        SigningMode.LOCAL -> LocalNostrSigner(keyStore, timeSource, cpu)
    }

    override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String): NostrEvent =
        activeSigner().signEvent(kind, tags, content)

    override suspend fun pubkeyHex(): String? = activeSigner().pubkeyHex()

    override suspend fun nip44Encrypt(peerHex: String, plaintext: String): String =
        activeSigner().nip44Encrypt(peerHex, plaintext)

    override suspend fun nip44Decrypt(peerHex: String, payload: String): String =
        activeSigner().nip44Decrypt(peerHex, payload)

    override suspend fun canNip44(): Boolean = canNip44(null)

    /** [canNip44] with an injectable signer (Dart `debugSigner`). */
    internal suspend fun canNip44(debugSigner: NostrSigner?): Boolean {
        var step = "Signer laden"
        val mark = KotlinTimeSource.Monotonic.markNow()
        return try {
            val signer = debugSigner ?: activeSigner()
            step = "Identität lesen"
            val me = signer.pubkeyHex()
            if (me.isNullOrEmpty()) {
                AppLogger.warn("Nip44Probe", "Keine aktive Identität; Probe nicht möglich.")
                return false
            }
            AppLogger.info("Nip44Probe", "Probe gestartet (${signer::class.simpleName}).")
            step = "nip44_encrypt"
            val sealed = signer.nip44Encrypt(me, NIP44_PROBE)
            step = "nip44_decrypt"
            val opened = signer.nip44Decrypt(me, sealed)
            if (opened != NIP44_PROBE) {
                AppLogger.warn(
                    "Nip44Probe",
                    "Entschlüsselte Probe stimmt nicht überein (${mark.elapsedNow().inWholeMilliseconds} ms).",
                )
                return false
            }
            AppLogger.info(
                "Nip44Probe",
                "Ver- und Entschlüsselung erfolgreich (${mark.elapsedNow().inWholeMilliseconds} ms).",
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never log payloads, keys or pubkeys; SigningException carries the translated cause.
            val reason = if (e is SigningException) e.message else e::class.simpleName
            AppLogger.warn(
                "Nip44Probe",
                "Probe bei $step fehlgeschlagen (${mark.elapsedNow().inWholeMilliseconds} ms): $reason. " +
                    "Das belegt keine fehlende NIP-44-Unterstützung.",
            )
            false
        }
    }

    override suspend fun npub(): String? = when (getMode()) {
        SigningMode.AMBER -> prefs.getString(IdentityKeys.AMBER_NPUB)
        SigningMode.NIP07 -> prefs.getString(IdentityKeys.NIP07_NPUB)
        SigningMode.NIP46 -> prefs.getString(IdentityKeys.NIP46_NPUB)
        SigningMode.LOCAL -> keyStore.getNpub()
    }

    override suspend fun canSign(): Boolean = when (getMode()) {
        SigningMode.AMBER -> !prefs.getString(IdentityKeys.AMBER_PUBKEY_HEX).isNullOrEmpty()
        SigningMode.NIP07 -> !prefs.getString(IdentityKeys.NIP07_PUBKEY_HEX).isNullOrEmpty() && nip07Available()
        // Deliberately without ping: canSign() is on hot paths (app start).
        SigningMode.NIP46 -> !prefs.getString(IdentityKeys.NIP46_PUBKEY_HEX).isNullOrEmpty() &&
            !prefs.getString(IdentityKeys.NIP46_BUNKER_URI).isNullOrEmpty() &&
            !keyStore.getNip46ClientKey().isNullOrEmpty()
        SigningMode.LOCAL -> !keyStore.getPrivHex().isNullOrEmpty()
    }

    // =============== Amber ===============

    override suspend fun connectAmber(): AmberConnectResult {
        val result = AmberNostrSigner.connect(nip55)
        if (result is AmberConnectResult.Success) {
            identitySwitch?.beforeChange(result.npub)
            writePrefs(IdentityKeys.AMBER_PUBKEY_HEX to result.pubkeyHex, IdentityKeys.AMBER_NPUB to result.npub)
            setMode(SigningMode.AMBER)
            AppLogger.security("SigningService", "Amber verbunden: ${short(result.npub)} — nsec bleibt in Amber.")
            noteSignerReady()
        }
        return result
    }

    override suspend fun useLocalMode() {
        identitySwitch?.beforeChange(keyStore.getNpub())
        setMode(SigningMode.LOCAL)
        noteSignerReady()
    }

    override suspend fun restoreAmber(npub: String) {
        val hex = Nip19.decodeNpub(npub.dartTrim())
        identitySwitch?.beforeChange(npub.dartTrim())
        writePrefs(IdentityKeys.AMBER_PUBKEY_HEX to hex, IdentityKeys.AMBER_NPUB to npub.dartTrim())
        setMode(SigningMode.AMBER)
        noteSignerReady()
    }

    override suspend fun disconnectAmber() {
        identitySwitch?.beforeChange(keyStore.getNpub())
        prefs.removeOrThrow(IdentityKeys.AMBER_PUBKEY_HEX)
        prefs.removeOrThrow(IdentityKeys.AMBER_NPUB)
        setMode(SigningMode.LOCAL)
    }

    // =============== NIP-07 (web only) ===============

    override suspend fun connectNip07(): Nip07ConnectResult = Nip07ConnectResult.Missing

    override suspend fun restoreNip07(npub: String) {
        val hex = Nip19.decodeNpub(npub.dartTrim())
        identitySwitch?.beforeChange(npub.dartTrim())
        writePrefs(IdentityKeys.NIP07_PUBKEY_HEX to hex, IdentityKeys.NIP07_NPUB to npub.dartTrim())
        setMode(SigningMode.NIP07)
        noteSignerReady()
    }

    override suspend fun disconnectNip07() {
        identitySwitch?.beforeChange(keyStore.getNpub())
        prefs.removeOrThrow(IdentityKeys.NIP07_PUBKEY_HEX)
        prefs.removeOrThrow(IdentityKeys.NIP07_NPUB)
        setMode(SigningMode.LOCAL)
    }

    // =============== NIP-46 ===============

    /** The held session; rehydrated once from the stored key + bunker URI (shared by concurrent callers). */
    private suspend fun nip46Session(): Nip46Client {
        nip46?.let { return it }
        return sessionMutex.withLock {
            nip46?.let { return@withLock it }
            val clientKey = keyStore.getNip46ClientKey()
            val uri = prefs.getString(IdentityKeys.NIP46_BUNKER_URI).orEmpty()
            if (clientKey.isNullOrEmpty() || uri.isEmpty()) {
                throw SigningException(
                    "Es ist keine Signer-Sitzung gespeichert. Bitte den Signer erneut verbinden.",
                    error = SigningError.NoBunkerSession,
                )
            }
            val pointer = BunkerPointer.parse(uri)
            clientFactory.create(clientKey, pointer.relays, pointer.remoteSignerPubkey, ::onAuthUrl)
                .also { nip46 = it }
        }
    }

    /**
     * Runs [block] (storage changes of the session) under [sessionMutex] together with the swap
     * of the held client to [next], so [nip46Session] can never rehydrate a session from
     * half-written or half-deleted state. The old client is closed afterwards, outside the lock
     * and even if the caller was cancelled (no zombie subscriptions).
     */
    private suspend fun <T> replaceNip46Session(next: Nip46Client?, block: suspend () -> T): T {
        var old: Nip46Client? = null
        try {
            return sessionMutex.withLock {
                old = nip46
                nip46 = null
                val value = block()
                nip46 = next
                value
            }
        } finally {
            // Also after a failed block: the old session is gone either way (nip46 is null).
            old?.takeIf { it !== next }?.let { closeQuietly(it) }
        }
    }

    /** Closes the session, deletes the session key and the bunker prefs. Idempotent; no mode change. */
    private suspend fun clearNip46Artifacts() {
        val ok = replaceNip46Session(null) {
            try {
                keyStore.deleteNip46ClientKey()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.warn("SigningService", "NIP-46-Sitzungsschlüssel konnte nicht gelöscht werden", e)
            }
            prefs.edit {
                remove(IdentityKeys.NIP46_PUBKEY_HEX)
                remove(IdentityKeys.NIP46_NPUB)
                remove(IdentityKeys.NIP46_BUNKER_URI)
            }
        }
        if (!ok) throw IdentityStorageException("Die Signer-Sitzung konnte nicht entfernt werden.")
    }

    /** Closes [client] without letting a failure or the caller's cancellation skip it. */
    private suspend fun closeQuietly(client: Nip46Client) {
        withContext(NonCancellable) {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    override suspend fun connectNip46Bunker(bunkerUri: String): Nip46ConnectResult {
        var client: Nip46Client? = null
        return try {
            val pointer = BunkerPointer.parse(bunkerUri)
            val clientKey = Nip46Client.generateClientKey()
            client = clientFactory.create(clientKey, pointer.relays, pointer.remoteSignerPubkey, ::onAuthUrl)
            client.connect(pointer.secret)
            val userPubkeyHex = client.getPublicKey()
            // Persisted without the one-time secret; adopted as the live session atomically.
            persistNip46Session(client, clientKey, userPubkeyHex, pointer.toBunkerUri())
            val npub = Nip19.encodeNpub(userPubkeyHex)
            AppLogger.security("SigningService", "Remote-Signer verbunden: ${short(npub)} — der Schlüssel bleibt im Signer.")
            noteSignerReady()
            Nip46ConnectResult.Success(userPubkeyHex, npub)
        } catch (e: CancellationException) {
            client?.let { closeIfNotAdopted(it) }
            throw e
        } catch (e: Nip46TimeoutException) {
            client?.let { closeIfNotAdopted(it) }
            Nip46ConnectResult.Timeout
        } catch (e: Nip46Exception) {
            client?.let { closeIfNotAdopted(it) }
            Nip46ConnectResult.Error(e.message, e)
        } catch (e: Exception) {
            client?.let { closeIfNotAdopted(it) }
            Nip46ConnectResult.Error(e.toString(), e)
        }
    }

    override suspend fun startNip46Pairing(): Nip46Pairing {
        val relays = nip46PairingRelays()
        val clientKey = Nip46Client.generateClientKey()
        val secret = Nip46Client.generateSecret()
        val client = clientFactory.create(clientKey, relays, null, ::onAuthUrl)
        return Nip46Pairing(
            uri = BunkerPointer.buildNostrConnectUri(
                clientPubkeyHex = client.clientPubkeyHex,
                relays = relays,
                secret = secret,
                perms = Nip46Client.REQUESTED_PERMS,
                appName = Nip46ClientMetadata.NAME,
                appUrl = Nip46ClientMetadata.URL,
                appImage = Nip46ClientMetadata.IMAGE,
            ),
            secret = secret,
            clientSecretKeyHex = clientKey,
            relays = relays,
            client = client,
        )
    }

    override suspend fun completeNip46Pairing(pairing: Nip46Pairing): Nip46ConnectResult = try {
        val signerPubkey = pairing.client.awaitPairing(pairing.secret)
        val userPubkeyHex = pairing.client.getPublicKey()
        // Always stored as bunker:// — one restore path.
        val pointer = BunkerPointer(signerPubkey, pairing.relays)
        persistNip46Session(pairing.client, pairing.clientSecretKeyHex, userPubkeyHex, pointer.toBunkerUri())
        // The client is the live session now: a later pairing.cancel() must not close it.
        pairing.adopted.set(true)
        val npub = Nip19.encodeNpub(userPubkeyHex)
        AppLogger.security("SigningService", "Remote-Signer gekoppelt: ${short(npub)} — der Schlüssel bleibt im Signer.")
        noteSignerReady()
        Nip46ConnectResult.Success(userPubkeyHex, npub)
    } catch (e: CancellationException) {
        closeIfNotAdopted(pairing.client)
        throw e
    } catch (e: Nip46TimeoutException) {
        closeIfNotAdopted(pairing.client)
        Nip46ConnectResult.Timeout
    } catch (e: Nip46Exception) {
        closeIfNotAdopted(pairing.client)
        Nip46ConnectResult.Error(e.message, e)
    } catch (e: Exception) {
        closeIfNotAdopted(pairing.client)
        Nip46ConnectResult.Error(e.toString(), e)
    }

    /** Failure cleanup: closes [client] unless it already became the live session. */
    private suspend fun closeIfNotAdopted(client: Nip46Client) {
        if (nip46 !== client) closeQuietly(client)
    }

    /**
     * Stores key + prefs and adopts [client] as the live session in one locked step, then sets the
     * mode. If the mode cannot be stored, the adopted session is torn down again (client closed,
     * session key and `nip46_*` prefs removed), so a failure never leaves an orphan session behind
     * a LOCAL/AMBER mode.
     */
    private suspend fun persistNip46Session(
        client: Nip46Client,
        clientSecretKeyHex: String,
        userPubkeyHex: String,
        bunkerUri: String,
    ) {
        identitySwitch?.beforeChange(Nip19.encodeNpub(userPubkeyHex))
        replaceNip46Session(client) {
            writeNip46Session(clientSecretKeyHex, userPubkeyHex, bunkerUri)
        }
        try {
            // Not interruptible: a cancelled caller must not leave the mode half-switched.
            withContext(NonCancellable) { setMode(SigningMode.NIP46) }
        } catch (e: Exception) {
            withContext(NonCancellable) {
                try {
                    clearNip46Artifacts()
                } catch (cleanup: Exception) {
                    e.addSuppressed(cleanup)
                    AppLogger.warn("SigningService", "Signer-Sitzung konnte nach Fehler nicht entfernt werden", cleanup)
                }
            }
            throw e
        }
    }

    override suspend fun nip46PairingRelays(): List<String> {
        val chosen = relayConfig.getActiveRelays().take(3)
        return chosen.ifEmpty { RelayConfig.DEFAULT_RELAYS.take(2) }
    }

    override suspend fun nip46SessionAlive(): Boolean {
        if (getMode() != SigningMode.NIP46) return false
        return try {
            nip46Session().ping()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun nip46SessionForBackup(): Map<String, String>? {
        val uri = prefs.getString(IdentityKeys.NIP46_BUNKER_URI).orEmpty()
        return if (uri.isEmpty()) null else mapOf("bunker_uri" to uri)
    }

    override suspend fun restoreNip46(npub: String, bunkerUri: String, clientSecretKeyHex: String?) {
        val hex = Nip19.decodeNpub(npub.dartTrim())
        // Throws on garbage before anything is stored.
        BunkerPointer.parse(bunkerUri)
        identitySwitch?.beforeChange(npub.dartTrim())
        val clientKey = clientSecretKeyHex?.dartTrim().orEmpty()
        replaceNip46Session(null) {
            writeNip46Session(clientKey.ifEmpty { null }, hex, bunkerUri.dartTrim())
        }
        setMode(SigningMode.NIP46)
        noteSignerReady()
    }

    override suspend fun disconnectNip46() {
        identitySwitch?.beforeChange(keyStore.getNpub())
        clearNip46Artifacts()
        setMode(SigningMode.LOCAL)
    }

    /**
     * Writes the session key ([clientKey] `null` deletes it) and the bunker prefs as one unit: if
     * the prefs cannot be written, the previous key is put back, so key and prefs always describe
     * the same session. Caller holds [sessionMutex] (inside [replaceNip46Session]).
     */
    private suspend fun writeNip46Session(clientKey: String?, userPubkeyHex: String, bunkerUri: String) {
        // An unreadable old key must not block a new session; rolling back then means deleting.
        val previousKey = try {
            keyStore.getNip46ClientKey()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (clientKey == null) keyStore.deleteNip46ClientKey() else keyStore.saveNip46ClientKey(clientKey)
        try {
            writePrefs(
                IdentityKeys.NIP46_PUBKEY_HEX to userPubkeyHex,
                IdentityKeys.NIP46_NPUB to Nip19.encodeNpub(userPubkeyHex),
                IdentityKeys.NIP46_BUNKER_URI to bunkerUri,
            )
        } catch (e: Exception) {
            try {
                withContext(NonCancellable) {
                    if (previousKey == null) keyStore.deleteNip46ClientKey() else keyStore.saveNip46ClientKey(previousKey)
                }
            } catch (rollback: Exception) {
                e.addSuppressed(rollback)
                AppLogger.warn("SigningService", "NIP-46-Sitzungsschlüssel konnte nicht zurückgesetzt werden", rollback)
            }
            throw e
        }
    }

    private suspend fun writePrefs(vararg entries: Pair<String, String>) {
        val ok = prefs.edit { entries.forEach { (k, v) -> setString(k, v) } }
        if (!ok) throw IdentityStorageException("Einstellungen ${entries.joinToString { it.first }} konnten nicht gespeichert werden.")
    }

    private companion object {
        const val NIP44_PROBE = "e21-nip44-probe"

        fun short(npub: String): String =
            if (npub.length < 16) npub else "${npub.substring(0, 12)}…${npub.substring(npub.length - 4)}"
    }
}
