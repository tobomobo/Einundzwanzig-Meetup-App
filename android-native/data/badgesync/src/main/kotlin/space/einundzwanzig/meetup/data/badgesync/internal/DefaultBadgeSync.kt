package space.einundzwanzig.meetup.data.badgesync.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.ApplicationScope
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.core.common.json.dartStringOrNull
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.describeError
import space.einundzwanzig.meetup.core.crypto.DartBase64
import space.einundzwanzig.meetup.core.crypto.Keys
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.nostr.EventGuard
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayPublishOptions
import space.einundzwanzig.meetup.core.nostr.relay.RelayQueryOptions
import space.einundzwanzig.meetup.core.nostr.relay.RelayQueryStatus
import space.einundzwanzig.meetup.core.nostr.relay.SubscriptionIds
import space.einundzwanzig.meetup.data.badges.model.BadgeIdentity
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.badges.store.BadgeStoreKeys
import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.badgesync.BadgeSync
import space.einundzwanzig.meetup.data.badgesync.BadgeSyncCodec
import space.einundzwanzig.meetup.data.badgesync.BadgeSyncFormat
import space.einundzwanzig.meetup.data.badgesync.BadgeSyncKeys
import space.einundzwanzig.meetup.data.badgesync.RestoreHoldException
import space.einundzwanzig.meetup.data.identity.SigningException
import space.einundzwanzig.meetup.data.identity.SigningMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Port of Dart `BadgeSyncService` (spec 08 §8.11, §8.12). The Dart statics (`_busy`, `_rerun`,
 * `_epoch`, `_suspended`, `_pendingWhileSuspended`) are instance state of this singleton; a new
 * instance on the same stores behaves like a restarted process (Dart `debugResetProcess`).
 *
 * Threading: the in-memory flags are guarded by [lock] (Dart is single-threaded; here triggers
 * come from several coroutines). [settleRestore] runs one at a time ([settleMutex]).
 */
@Singleton
internal class DefaultBadgeSync @Inject constructor(
    private val store: KeyValueStore,
    private val badges: BadgeRepository,
    private val identity: SyncIdentity,
    private val relays: RelayClient,
    private val proofs: IdentityProofsWiper,
    private val time: TimeSource,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val log: DiagnosticLog,
) : BadgeSync {

    private val lock = Any()
    private var busy = false
    private var rerun = false
    private var suspended = false
    private var pendingWhileSuspended = false

    /** Incremented by every [beginRestoreHold]: a run that read before it never writes after it. */
    private val epoch = AtomicInteger(0)
    private val settleMutex = Mutex()
    private val hooksInstalled = AtomicBoolean(false)

    /** Number of [syncNow] passes started (Dart `runs`), for tests. */
    val runs = AtomicInteger(0)

    // ------------------------------------------------------------------ engine

    override fun init() {
        if (hooksInstalled.compareAndSet(false, true)) {
            // UNDISPATCHED: subscribed before init() returns, so no save in between is missed.
            scope.launch(start = CoroutineStart.UNDISPATCHED) { badges.savedEvents.collect { trigger() } }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { identity.signerReady.collect { trigger() } }
        }
        trigger()
    }

    override fun trigger() {
        synchronized(lock) {
            if (suspended) {
                pendingWhileSuspended = true
                return
            }
        }
        scope.launch {
            try {
                // false: relays, pull or push failed. No signer / refused NIP-44 set nothing,
                // otherwise every wake-up would ask for the permission again.
                if (!syncNow()) store.setBool(BadgeSyncKeys.DIRTY, true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.setBool(BadgeSyncKeys.DIRTY, true)
                log.debug(TAG, "Sync fehlgeschlagen: ${describeError(e)}")
            }
        }
    }

    override suspend fun retryIfDirty() {
        if (store.getBool(BadgeSyncKeys.DIRTY) != true) return
        // A pass already running does a full pull + push and sets dirty from its own outcome;
        // forcing a rerun (start-up trigger + retry, launch ON_START) would only repeat it.
        if (synchronized(lock) { busy }) return
        try {
            syncNow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug(TAG, "Sync-Retry fehlgeschlagen: ${describeError(e)}")
        }
    }

    override suspend fun syncNow(): Boolean {
        synchronized(lock) {
            if (busy) {
                rerun = true
                return true
            }
            busy = true
        }
        runs.incrementAndGet()
        try {
            while (true) {
                synchronized(lock) { rerun = false }
                val attempt = syncOnce()
                // A save or a signer that became ready during this pass: the follow-up counts.
                if (rerunRequested()) continue
                val result = when (attempt) {
                    SyncAttempt.NIP44_BLOCKED -> {
                        // The probe already asked the signer; clear dirty so the next wake-up
                        // does not ask again.
                        store.setBool(BadgeSyncKeys.DIRTY, false)
                        true
                    }
                    SyncAttempt.NO_SIGNER, SyncAttempt.HELD, SyncAttempt.OK -> true
                    SyncAttempt.FAILED -> false
                }
                if (finishUnlessRerun()) return result
            }
        } catch (e: Throwable) {
            synchronized(lock) { busy = false }
            throw e
        }
    }

    private fun rerunRequested(): Boolean = synchronized(lock) { rerun }

    /** Ends the run (busy = false) unless a rerun was requested; atomic, so no trigger is lost. */
    private fun finishUnlessRerun(): Boolean = synchronized(lock) {
        if (rerun) {
            false
        } else {
            busy = false
            true
        }
    }

    private suspend fun syncOnce(): SyncAttempt {
        settleRestore()
        if (blocked()) return SyncAttempt.HELD
        val epoch0 = epoch.get()

        val me = identity.pubkeyHex()
        if (me.isNullOrEmpty()) return SyncAttempt.NO_SIGNER

        // A signer without NIP-44 keeps the sync off instead of showing an error: the backup
        // file is the way then. canNip44 catches by itself; the branches stay in case it throws.
        try {
            if (!identity.canNip44()) {
                log.warn(TAG, "NIP-44-Probe nicht erfolgreich — Ursache siehe Nip44Probe.")
                return SyncAttempt.NIP44_BLOCKED
            }
        } catch (e: UnsupportedOperationException) {
            log.warn(TAG, "NIP-44-Probe nicht erfolgreich — Ursache siehe Nip44Probe.")
            return SyncAttempt.NIP44_BLOCKED
        } catch (e: SigningException) {
            log.debug(TAG, "NIP-44-Probe abgelehnt — Sync bleibt aus: ${describeError(e)}")
            return SyncAttempt.NIP44_BLOCKED
        }

        // null: no relay answered, or the local file is unreadable and the relays have no copy.
        val merged = pull(me, epoch0) ?: return SyncAttempt.FAILED
        // The run read before an identity switch began: its state belongs to the old identity.
        if (epoch0 != epoch.get()) return SyncAttempt.FAILED
        if (blocked()) return SyncAttempt.HELD
        val pushed = push(me, merged, epoch0)
        // An identity switch began meanwhile (forgetLocalState may already have run): never
        // write the old identity's state over it. FAILED lets trigger() mark dirty.
        if (epoch0 != epoch.get()) return SyncAttempt.FAILED
        // Dirty stays if a chunk was confirmed by no relay.
        store.setBool(BadgeSyncKeys.DIRTY, !pushed)
        return if (pushed) SyncAttempt.OK else SyncAttempt.FAILED
    }

    /**
     * Reads the relays and unites them with the local file per event identity. `null`: no
     * relay reachable or nothing storable; the run then counts as failed and is retried.
     */
    private suspend fun pull(me: String, epoch0: Int): List<MeetupBadge>? {
        val remote = fetchRemote(me, epoch0)
        if (remote == null) {
            log.debug(TAG, "Kein Relay erreichbar, Sync wird nachgeholt")
            return null
        }
        // Read again after waiting: a scan in between must be part of the union.
        if (epoch0 != epoch.get() || blocked()) return null
        val local = badges.loadBadges()
        if (badges.badgesUnreadable.value) {
            if (remote.isEmpty()) {
                log.warn(TAG, "Lokale Badges nicht lesbar, auf den Relays liegt keine Kopie")
                return null
            }
            // The unreadable file is preserved by the repository (backup chain).
            if (!badges.saveBadges(remote, replaceUnreadable = true)) return null
            return badges.loadBadges()
        }
        val merged = BadgeSyncFormat.merge(local, remote)
        if (BadgeIdentity.sameBadges(merged, local)) return local
        if (epoch0 != epoch.get() || blocked()) return null
        // Native: atomic read-modify-write, so a scan saved since loadBadges() is united too.
        var result: List<MeetupBadge>? = null
        var aborted = false
        val stored = badges.update { current ->
            // The update may have waited on the repository lock behind a switch: re-check here
            // (non-suspending) so the old identity's remote badges never reach the new collection.
            if (epoch0 != epoch.get() || synchronized(lock) { suspended }) {
                aborted = true
                return@update null
            }
            val united = if (current === local) merged else BadgeSyncFormat.merge(current, remote)
            if (BadgeIdentity.sameBadges(united, current)) {
                result = current
                null
            } else {
                result = united
                united
            }
        }
        return if (stored && !aborted) result else null
    }

    /**
     * Fetches every chunk from all sync relays in parallel, decrypts what it can and returns all
     * badges (duplicates included; the merge dedups). `null` if no relay sent EOSE.
     */
    private suspend fun fetchRemote(me: String, epoch0: Int): List<MeetupBadge>? {
        val filter = jsonObjectOf(
            "kinds" to listOf(BadgeSyncFormat.KIND),
            "authors" to listOf(me),
            "#d" to BadgeSyncFormat.D_TAGS,
        )
        val perRelay = coroutineScope {
            BadgeSync.SYNC_RELAYS.map { url -> async { fetchFromRelay(url, filter) } }.awaitAll()
        }
        if (perRelay.all { it == null }) return null

        // Per d index the newest event across all relays wins (ties: first relay in list order).
        // Insertion order like a Dart map (indices first seen, relays in list order): the union keeps
        // the first of two fully equal variants, so the order must match Flutter's.
        val byIndex = LinkedHashMap<Int, JsonObject>()
        for (fromRelay in perRelay) {
            for ((index, event) in fromRelay ?: continue) {
                val existing = byIndex[index]
                if (existing == null || createdAt(event) > createdAt(existing)) byIndex[index] = event
            }
        }

        val result = mutableListOf<MeetupBadge>()
        var newestGen = 0L
        for (event in byIndex.values) {
            val content = event["content"].dartStringOrNull() ?: ""
            if (content.isEmpty()) continue
            try {
                val plain = identity.nip44Decrypt(me, content)
                // The content is base64(gzip(envelope)), not the envelope itself.
                val env = BadgeSyncFormat.parseEnvelope(BadgeSyncCodec.inflate(DartBase64.decode(plain)))
                result += env.badges
                if (env.generation > newestGen) newestGen = env.generation
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug(TAG, "Chunk nicht lesbar: ${describeError(e)}")
            }
        }
        // The generation floor belongs to the identity that was read; not after a switch began.
        if (newestGen > 0 && epoch0 == epoch.get()) store.setInt(BadgeSyncKeys.LAST_GEN, newestGen)
        return result
    }

    /** Newest verified chunk event per index of one relay, or `null` if it did not answer with EOSE. */
    private suspend fun fetchFromRelay(url: String, filter: JsonObject): Map<Int, JsonObject>? {
        val options = RelayQueryOptions(
            connectTimeout = RELAY_TIMEOUT,
            responseTimeout = RELAY_TIMEOUT,
            subscriptionId = SubscriptionIds.secure("badgesync"),
            logTag = TAG,
        )
        val res = relays.query(url, listOf(filter), options)
        if (res.status != RelayQueryStatus.EOSE) {
            val reason = res.error?.let { describeError(it) } ?: res.status.name
            log.debug(TAG, "Lesen von $url fehlgeschlagen: $reason")
            return null
        }
        val now = time.nowSeconds()
        val out = LinkedHashMap<Int, JsonObject>() // arrival order, as in Dart
        for (event in res.events) {
            // Verified and filter-matched by RelayClient; a future-dated event cannot win forever.
            val created = EventGuard.replaceableCreatedAt(event, now) ?: continue
            val tags = event["tags"] as? JsonArray ?: continue
            for (tag in tags) {
                if (tag !is JsonArray || tag.size < 2) continue
                if ((tag[0] as? JsonPrimitive)?.takeIf { it.isString }?.content != "d") continue
                val name = (tag[1] as? JsonPrimitive)?.content ?: continue
                if (!name.startsWith("badges-")) continue
                val index = dartIntTryParse(name.substring(7)) ?: continue
                if (index < 0 || index >= BadgeSyncFormat.MAX_CHUNKS) continue
                val existing = out[index.toInt()]
                if (existing == null || created > createdAt(existing)) out[index.toInt()] = event
            }
        }
        return out
    }

    /**
     * Builds chunks from [merged], encrypts and publishes only those whose content changed.
     * `false` if a changed chunk reached no relay (the sync stays dirty).
     */
    private suspend fun push(me: String, merged: List<MeetupBadge>, epoch0: Int): Boolean {
        if (epoch0 != epoch.get() || blocked()) return false
        if (merged.isEmpty()) return true // never publish an empty list (reset path)

        // More chunks than readers query: write nothing rather than unreadable events.
        val wouldBe = BadgeSyncFormat.chunkBadges(merged) { it.length }
        if (wouldBe.size > BadgeSyncFormat.MAX_CHUNKS) {
            log.warn(
                TAG,
                "${wouldBe.size} Chunks nötig, gelesen werden nur ${BadgeSyncFormat.MAX_CHUNKS} — Sync bleibt aus",
            )
            return false
        }

        val generation = (time.nowMillis() / 1000).coerceIn(store.getInt(BadgeSyncKeys.LAST_GEN) ?: 0L, 1L shl 62)
        // Chunk boundary at the NIP-44 payload, not at the plaintext.
        val plaintexts = BadgeSyncFormat.plaintexts(merged, generation) {
            BadgeSyncFormat.payloadCharsForGzip(BadgeSyncCodec.deflate(it).size)
        }
        val oldHashes = store.getStringList(BadgeSyncKeys.CHUNK_HASHES) ?: emptyList()

        val newHashes = mutableListOf<String>()
        var ok = true
        for ((i, plain) in plaintexts.withIndex()) {
            if (epoch0 != epoch.get() || blocked()) return false
            // Content only, not the generation: otherwise every start republishes every chunk.
            val hash = BadgeSyncFormat.contentHash(plain)
            if (i < oldHashes.size && oldHashes[i] == hash) {
                newHashes += hash
                continue
            }
            val sealed = identity.nip44Encrypt(me, DartBase64.encode(BadgeSyncCodec.deflate(plain)))
            val signed = identity.signEvent(
                kind = BadgeSyncFormat.KIND,
                tags = listOf(listOf("d", BadgeSyncFormat.dTag(i)), listOf("client", BadgeSyncFormat.CLIENT_TAG)),
                content = sealed,
            )
            var accepted = false
            for (url in BadgeSync.SYNC_RELAYS) {
                val res = relays.publish(url, signed, PUBLISH_OPTIONS)
                if (res.accepted) {
                    accepted = true
                    break
                }
                log.debug(TAG, "Veröffentlichen auf $url fehlgeschlagen: ${res.error?.let { describeError(it) } ?: res.status.name}")
            }
            if (accepted) {
                newHashes += hash
            } else {
                log.warn(TAG, "Chunk $i auf keinem Relay bestätigt")
                ok = false
                // Native fix (spec 08 §14 risk 6): keep the previous hash so the dirty retry
                // publishes this chunk again (Dart stored the new hash and skipped it).
                newHashes += oldHashes.getOrElse(i) { "" }
            }
        }
        // A switch that began during the last publish (forgetLocalState may have run) must not
        // get the old identity's hashes back, or the new identity would skip "unchanged" chunks.
        if (epoch0 != epoch.get() || blocked()) return false
        store.edit {
            setStringList(BadgeSyncKeys.CHUNK_HASHES, newHashes)
            setInt(BadgeSyncKeys.LAST_GEN, generation)
        }
        return ok
    }

    // ------------------------------------------------------------------ restore hold

    override suspend fun beginRestoreHold(fromNpub: String, expectNpubs: List<String>, replace: Boolean) {
        epoch.incrementAndGet()
        // Settle, check and persist under the settle mutex, and raise the in-memory flag before
        // the hold is written: a concurrent settle (a trigger, retryIfDirty, the resume hook or the
        // run started by the empty-replace save inside this settle) must never see the fresh hold
        // without the flag and remove it again as "nothing switched".
        settleMutex.withLock {
            if (!synchronized(lock) { suspended }) settleLocked()
            val pending = isHeld()
            var from = norm(fromNpub)
            if (pending) {
                if (!replace) {
                    throw RestoreHoldException(
                        "Ein Identitätswechsel ist noch nicht abgeschlossen. Lade das Backup der neuen Identität noch einmal.",
                    )
                }
                // Keep the ORIGINAL owner and the staged buffer until the new one replaces it.
                from = norm(store.getString(BadgeSyncKeys.RESTORE_FROM) ?: "")
            } else {
                badges.discardIncoming()
                store.remove(BadgeSyncKeys.RESTORE_CLEAR)
            }
            val expect = LinkedHashSet<String>()
            for (n in expectNpubs) {
                val trimmed = DartString.trim(n)
                if (trimmed.isNotEmpty()) expect += DartString.lower(trimmed)
            }
            val wasSuspended = synchronized(lock) { suspended.also { suspended = true } }
            try {
                // One transaction: either the whole hold is persisted or nothing (Dart wrote three
                // keys and removed the hold again if one write failed).
                val ok = store.edit {
                    setString(BadgeSyncKeys.RESTORE_FROM, from)
                    setStringList(BadgeSyncKeys.RESTORE_EXPECT, expect.toList())
                    setBool(BadgeSyncKeys.RESTORE_HOLD, true)
                }
                if (!ok) throw RestoreHoldException("Der Wechsel konnte nicht festgehalten werden.")
            } catch (e: Throwable) {
                synchronized(lock) { suspended = wasSuspended }
                throw e
            }
        }
        // A run that started during the settle above (and read the epoch after the first bump)
        // can never write either.
        epoch.incrementAndGet()
    }

    override suspend fun pendingFrom(): String? {
        settleRestore()
        if (!isHeld()) return null
        return norm(store.getString(BadgeSyncKeys.RESTORE_FROM) ?: "")
    }

    override suspend fun endRestore() {
        // Runs in the import's `finally`, possibly in a cancelled coroutine: without NonCancellable
        // the settle could throw CancellationException and the queued trigger would be lost.
        withContext(NonCancellable) {
            synchronized(lock) { suspended = false }
            settleRestore()
            val replay = synchronized(lock) { pendingWhileSuspended.also { pendingWhileSuspended = false } }
            if (replay) trigger()
        }
    }

    override fun identitySwitched(from: String, now: String): Boolean {
        val current = norm(now)
        if (current.isEmpty()) return false
        return current != norm(from)
    }

    override suspend fun markRestoreClear() {
        // A buffer from an earlier attempt would otherwise become the new collection.
        badges.discardIncoming()
        if (!store.setBool(BadgeSyncKeys.RESTORE_CLEAR, true)) {
            throw RestoreHoldException("Die leere Sammlung konnte nicht vorgemerkt werden.")
        }
    }

    override suspend fun clearRestoreMark() {
        store.remove(BadgeSyncKeys.RESTORE_CLEAR)
    }

    override suspend fun clearIdentityProofs() {
        if (!proofs.clear() || !store.setBool(BadgeSyncKeys.RESTORE_PROOFS, true)) {
            throw RestoreHoldException("Die Nachweise der bisherigen Identität konnten nicht abgeräumt werden.")
        }
    }

    override suspend fun forgetLocalState() {
        store.edit {
            remove(BadgeSyncKeys.CHUNK_HASHES)
            remove(BadgeSyncKeys.LAST_GEN)
            remove(BadgeSyncKeys.DIRTY)
        }
    }

    override suspend fun settleRestore() {
        if (synchronized(lock) { suspended }) return // an import in THIS process owns the buffer
        settleMutex.withLock { settleLocked() }
    }

    private suspend fun settleLocked() {
        if (synchronized(lock) { suspended }) return
        if (!isHeld()) return
        val from = norm(store.getString(BadgeSyncKeys.RESTORE_FROM) ?: "")
        val now = norm(identity.npub() ?: "")
        val expect = expectedNpubs()
        val incoming = store.getString(BadgeStoreKeys.INCOMING)
        val staged = incoming != null && incoming.contains(':')
        var clear = store.getBool(BadgeSyncKeys.RESTORE_CLEAR) == true
        var switched = identitySwitched(from, now) && (expect.isEmpty() || now in expect)
        if (switched && !localKeyAgrees(now)) switched = false
        // No npub readable, not yet a backup identity, or the private key does not match the
        // npub: buffer and hold stay.
        val waiting = from.isNotEmpty() && (staged || clear) &&
            (now.isEmpty() || (identitySwitched(from, now) && !switched))
        if (waiting) {
            log.warn(TAG, "Identität noch nicht die aus dem Backup, Riegel bleibt")
            return
        }
        if (staged) {
            if (switched) {
                // If the archive did not take the old collection it is still the active file:
                // hold and buffer stay, otherwise the next run would publish it under the new key.
                if (!badges.promoteIncoming(formerNpub = from)) {
                    log.warn(TAG, "Puffer nicht übernommen, Riegel bleibt bis zum nächsten Versuch")
                    return
                }
            } else {
                badges.discardIncoming()
            }
            // The buffer is the new collection; a clear mark next to it would throw it away.
            if (clear) {
                store.remove(BadgeSyncKeys.RESTORE_CLEAR)
                clear = false
            }
        }
        if (clear) {
            if (!switched) {
                store.remove(BadgeSyncKeys.RESTORE_CLEAR)
            } else if (!badges.saveBadges(emptyList(), discardLocked = true)) {
                log.warn(TAG, "Leere Sammlung nicht übernommen, Riegel bleibt")
                return
            } else {
                store.remove(BadgeSyncKeys.RESTORE_CLEAR)
            }
        }
        if (switched && store.getBool(BadgeSyncKeys.RESTORE_PROOFS) != true) clearIdentityProofs()
        if (switched) forgetLocalState()
        store.edit {
            remove(BadgeSyncKeys.RESTORE_HOLD)
            remove(BadgeSyncKeys.RESTORE_FROM)
            remove(BadgeSyncKeys.RESTORE_EXPECT)
            remove(BadgeSyncKeys.RESTORE_PROOFS)
        }
    }

    /** `badge_restore_expect`: a list, or a legacy non-empty string. */
    private suspend fun expectedNpubs(): List<String> = when (val raw = store.get(BadgeSyncKeys.RESTORE_EXPECT)) {
        is PrefValue.StringListValue -> raw.value.map { norm(it) }
        is PrefValue.StringValue -> if (DartString.trim(raw.value).isNotEmpty()) listOf(norm(raw.value)) else emptyList()
        else -> emptyList()
    }

    /** Local key: the npub alone is not enough; a stored private key must belong to it. */
    private suspend fun localKeyAgrees(now: String): Boolean {
        if (identity.mode() != SigningMode.LOCAL) return true
        val priv = norm(identity.localPrivHex() ?: "")
        if (priv.isEmpty()) return true
        return try {
            norm(Nip19.encodeNpub(Keys.pubHexFromPriv(priv))) == now
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun isHeld(): Boolean = store.getBool(BadgeSyncKeys.RESTORE_HOLD) == true

    /** No relay write: an import in this process is running, or a hold is persisted. */
    private suspend fun blocked(): Boolean = synchronized(lock) { suspended } || isHeld()

    private enum class SyncAttempt { OK, NO_SIGNER, NIP44_BLOCKED, HELD, FAILED }

    private companion object {
        const val TAG = "BadgeSync"
        val RELAY_TIMEOUT = 8.seconds
        val PUBLISH_OPTIONS = RelayPublishOptions(connectTimeout = 5.seconds, ackTimeout = 8.seconds)

        /** Dart `x.trim().toLowerCase()`. */
        fun norm(s: String): String = DartString.lower(DartString.trim(s))

        /** Dart `event['created_at'] as int? ?? 0`. */
        fun createdAt(event: JsonObject): Long = event["created_at"].dartIntOrNull() ?: 0L

        /**
         * Dart `int.tryParse(s)` (radix 10 or `0x` hex, optional sign, surrounding whitespace
         * ignored); `null` for anything else or values outside 64 bits.
         */
        fun dartIntTryParse(s: String): Long? {
            val t = s.dartTrim()
            if (t.isEmpty()) return null
            val negative = t[0] == '-'
            val body = if (t[0] == '-' || t[0] == '+') t.substring(1) else t
            val magnitude = if (body.startsWith("0x") || body.startsWith("0X")) {
                body.substring(2).takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' || c.lowercaseChar() in 'a'..'f' } }
                    ?.toLongOrNull(16)
            } else {
                body.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toLongOrNull()
            } ?: return null
            return if (negative) -magnitude else magnitude
        }
    }
}
