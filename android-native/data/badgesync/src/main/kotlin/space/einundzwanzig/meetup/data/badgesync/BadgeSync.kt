package space.einundzwanzig.meetup.data.badgesync

/**
 * Background badge sync over Nostr (Dart `BadgeSyncService`, spec 08 §1 B, §5, §6, §8.11, §8.12).
 *
 * The collection is stored as up to [BadgeSyncFormat.MAX_CHUNKS] addressable kind-30078 events
 * (`d = badges-<i>`), each NIP-44 v2 encrypted to the user's own pubkey. Every run pulls and
 * merges first (union per event identity), then publishes only changed chunks. It never
 * publishes an empty list, never overwrites an unreadable wallet, and publishes nothing while a
 * backup restore holds the identity switch ([beginRestoreHold]). There is no UI; failures are
 * logged and retried through the persisted dirty flag.
 *
 * The `@Singleton` is started by its own `AppStartupTask` (contributed by this module): it
 * collects `BadgeRepository.savedEvents` and `SigningService.signerReady`, runs [init] (one full
 * run), and calls [retryIfDirty] whenever the app returns to the foreground.
 * Callers outside this module only use the restore-hold API (`:data:backup`).
 */
interface BadgeSync {

    /**
     * Dart `init()`: connects badge saves and "signer ready" to [trigger] (idempotent) and
     * triggers one run so a second device sees the collection without scanning.
     */
    fun init()

    /**
     * Dart `trigger()`: starts a run in the background; never throws. A run that fails on
     * relays/pull/push sets `badge_sync_dirty`. While a restore is in progress the trigger is
     * remembered and replayed once by [endRestore].
     */
    fun trigger()

    /**
     * Dart `retryIfDirty()`: runs [syncNow] if the last run failed; never throws. Native: a no-op
     * while a run is already busy (that run pulls and pushes everything and sets dirty itself).
     */
    suspend fun retryIfDirty()

    /**
     * Dart `syncNow()`: one pull+push, single-flight. A call while a run is busy only schedules
     * one more pass of the running loop and returns `true`.
     *
     * @return `false` if relays, pull or push failed (the caller marks dirty); `true` when done,
     *   or when there is no signer, NIP-44 is refused, or a restore hold is active.
     * @throws Exception from the signer while encrypting/signing a chunk (the trigger marks dirty).
     */
    suspend fun syncNow(): Boolean

    // ---------------------------------------------------------------- restore hold (:data:backup)

    /**
     * Suspends the sync while a backup is imported and persists the hold
     * (`badge_restore_hold/from/expect`), so a crash in between publishes nothing.
     * Settles a leftover hold first ([settleRestore]). If a switch is still pending, only a
     * replacing import ([replace] = `true`) may continue; it keeps the ORIGINAL `from` and the
     * staged buffer.
     *
     * @param fromNpub the active identity before the import (trimmed, lower-cased).
     * @param expectNpubs the identities the backup may switch to (empty = any).
     * @throws RestoreHoldException if a switch is pending and [replace] is `false`, or the hold
     *   could not be persisted.
     */
    suspend fun beginRestoreHold(fromNpub: String = "", expectNpubs: List<String> = emptyList(), replace: Boolean = false)

    /**
     * The identity owning the active collection while a switch is pending (after settling),
     * or `null` if no hold is set (then the keystore is the truth).
     */
    suspend fun pendingFrom(): String?

    /**
     * End of an import (success or not): lifts the suspension, settles the hold, and replays
     * one trigger that arrived meanwhile. Call it in `finally` after [beginRestoreHold]. Runs
     * `NonCancellable`, so it completes even when the importing coroutine was cancelled.
     */
    suspend fun endRestore()

    /**
     * Resolves a hold, also after a crash mid-switch. If the keystore now carries an expected
     * identity (and a local private key matches it), the staged buffer becomes active (old
     * collection archived as `replaced`), a pending empty-replace clears the wallet, the old
     * identity's proofs are cleared and the chunk hashes are forgotten. If nothing switched, the
     * buffer is discarded. If the switch is not yet readable, everything stays. Does nothing
     * while an import is running in this process.
     */
    suspend fun settleRestore()

    /**
     * An empty backup of another identity: the old collection must be archived once the switch
     * is proven. Discards an older staged buffer first.
     *
     * @throws RestoreHoldException if the mark could not be stored.
     */
    suspend fun markRestoreClear()

    /** The empty-replace mark is done (the old collection is archived). */
    suspend fun clearRestoreMark()

    /**
     * Clears the proofs of the previous identity (`humanity_*`, `platform_proofs`) and records
     * that in `badge_restore_proofs`, so settling does not wipe the proofs a restore writes next.
     *
     * @throws RestoreHoldException if a write failed.
     */
    suspend fun clearIdentityProofs()

    /** Forgets `badge_sync_chunk_hashes`, `badge_sync_last_gen` and `badge_sync_dirty`. */
    suspend fun forgetLocalState()

    /** A switch happened only if a non-empty npub other than [from] is readable now. */
    fun identitySwitched(from: String, now: String): Boolean

    companion object {
        /**
         * The dedicated sync relays (independent of the user's relay settings), in this order.
         * Reads ask all of them in parallel; a chunk is published to the first that accepts it.
         */
        val SYNC_RELAYS: List<String> = listOf(
            "wss://nos.lol",
            "wss://relay.primal.net",
            "wss://nostr.mom",
            "wss://relay.snort.social",
        )
    }
}

/** A restore-hold step failed; [message] is the Flutter German text. */
class RestoreHoldException(message: String) : IllegalStateException(message)

/** Flutter pref keys of the badge sync (spec 08 §4.1), all in the shared `KeyValueStore`. */
object BadgeSyncKeys {
    /** bool: the last relay run failed → retry on start/resume. */
    const val DIRTY: String = "badge_sync_dirty"

    /** string list: `contentHash` per chunk index of the last push. */
    const val CHUNK_HASHES: String = "badge_sync_chunk_hashes"

    /** int: lower bound for the next generation. */
    const val LAST_GEN: String = "badge_sync_last_gen"

    /** bool: identity switch in progress; nothing is published while `true`. */
    const val RESTORE_HOLD: String = "badge_restore_hold"

    /** string: identity owning the active collection before the switch (lower-case npub or ""). */
    const val RESTORE_FROM: String = "badge_restore_from"

    /** string list (legacy: string): identities the backup may switch to. */
    const val RESTORE_EXPECT: String = "badge_restore_expect"

    /** bool: the old identity's proofs are already cleared. */
    const val RESTORE_PROOFS: String = "badge_restore_proofs"

    /** bool: empty replace pending (archive + clear the old collection once switched). */
    const val RESTORE_CLEAR: String = "badge_restore_clear"
}
