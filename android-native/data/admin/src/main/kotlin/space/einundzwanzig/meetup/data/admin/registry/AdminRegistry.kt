package space.einundzwanzig.meetup.data.admin.registry

import kotlinx.coroutines.CoroutineScope
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.data.admin.internal.DefaultAdminRegistry
import space.einundzwanzig.meetup.data.admin.model.AdminCheckResult
import space.einundzwanzig.meetup.data.admin.model.AdminEntry
import space.einundzwanzig.meetup.data.admin.model.AdminsBySigner
import space.einundzwanzig.meetup.data.admin.signing.AdminSigner
import java.time.ZoneId
import kotlin.time.Duration

/**
 * Organizer registry (Dart `AdminRegistry`, spec 05 §8.4): signed kind-30078
 * `d=einundzwanzig-admins` lists from the super admin (bootstrap) or from every known admin
 * (after the sunset), cached for offline lookups in the shared prefs.
 *
 * Security properties kept from Flutter: legacy organic entries (`Organic (N Badges)`) are
 * dropped on every read/save path (K1); only queried **signers** count towards the sunset
 * (Audit-2 Fund 6); a failed lookup is [AdminCheckResult.SOURCE_UNAVAILABLE], never "unknown".
 */
interface AdminRegistry {

    /**
     * Dart `checkAdmin(userNpub)`: `no_key` → super admin (bootstrap only) → cache hit
     * (`local_cache`, starts a background refresh) → blocking relay fetch (`nostr_relay`) →
     * `unavailable` if no relay answered with a non-empty list, else `not_found`.
     */
    suspend fun checkAdmin(userNpub: String): AdminCheckResult

    /** Dart `checkAdminByPubkey(hex)`: `no_key` for `""`, `invalid_key` if not encodable, else [checkAdmin]. */
    suspend fun checkAdminByPubkey(pubkeyHex: String): AdminCheckResult

    /**
     * Dart `fetchFromRelays()`: queries the 4 hard-coded relays sequentially until one answers
     * for a queried signer. Returns the filtered list (merged with the cache after the sunset),
     * or `null` if no relay was usable. Records the contributing signers for the sunset count.
     * Does **not** write the cache.
     */
    suspend fun fetchFromRelays(): List<AdminEntry>?

    /** Dart `isSunsetActive()` incl. the one-time K1 reset and the tamper check. */
    suspend fun isSunsetActive(): Boolean

    /** Dart `getAdminList()`: the organic-filtered cache, `[]` if absent or unreadable. */
    suspend fun getAdminList(): List<AdminEntry>

    /** Dart `removeFromCache(npub)` (portal revocation). Errors are ignored. */
    suspend fun removeFromCache(npub: String)

    /**
     * Dart `addAdmin(entry)` (backup restore). Duplicates by npub are ignored silently;
     * `addedAt <= 0` becomes now (unix seconds).
     *
     * @throws IllegalStateException for a legacy organic name (Dart `StateError`).
     * @throws AdminRegistryException `Ungültiger npub.` for an invalid npub.
     */
    suspend fun addAdmin(entry: AdminEntry)

    /** Dart `removeAdmin(npub)`: removes and saves (timestamp updated). */
    suspend fun removeAdmin(npub: String)

    /** Dart `forceRefresh()`: fetch + save + sunset check; the list size, or `-1` if no relay answered. */
    suspend fun forceRefresh(): Int

    /** Dart `cacheAge()`: now − `admin_registry_timestamp`, or `null`. */
    suspend fun cacheAge(): Duration?

    /** Dart `clearCache()`: removes `admin_registry_cache` and its timestamp. */
    suspend fun clearCache()

    // ---- Legacy personal vouches (no UI in Flutter today; spec 05 §8.4.8) ----

    /** Dart `getMyVouches()`: `my_personal_vouches`, `[]` if missing or unreadable. */
    suspend fun getMyVouches(): List<AdminEntry>

    /**
     * Dart `addVouch(entry)` (stored with `addedAt = now` in seconds).
     *
     * @throws AdminRegistryException `Du bürgst bereits für diesen npub.` / `Ungültiger npub.` /
     *   `Du kannst nicht für dich selbst bürgen.`
     */
    suspend fun addVouch(entry: AdminEntry)

    /** Dart `removeVouch(npub)`. */
    suspend fun removeVouch(npub: String)

    /**
     * Dart `recoverMyVouchesFromRelays()`: the own published list from the first answering
     * relay; count restored, `0` if the relay had none (local data kept), `-1` if no relay answered
     * or there is no usable own npub.
     */
    suspend fun recoverMyVouchesFromRelays(): Int

    /**
     * Dart `revokeAllVouches()`: clears the local list and publishes an empty list.
     *
     * @throws AdminRegistryException without signer or if no relay confirmed.
     */
    suspend fun revokeAllVouches(): String

    /**
     * Dart `createAndPublishAdminListEvent()`: signs kind 30078 with the own vouches and publishes
     * it sequentially, waiting for `OK`. Returns `{"sent_to": N, "event": {…}}`.
     *
     * @throws AdminRegistryException without signer, or
     *   `Event konnte an keinen Relay gesendet werden.\n…` if no relay confirmed.
     */
    suspend fun createAndPublishAdminListEvent(): String

    /** Dart `createAdminListEvent()`: the signed event JSON, not published. */
    suspend fun createAdminListEvent(): String

    companion object {
        /**
         * Builds a registry without Hilt (tests of other modules).
         *
         * @param relayFetch replaces the per-relay query (Dart `relayFetchOverride`): receives the
         *   relay URL and the queried author hex keys and returns the raw lists per signer, or
         *   `null` for "no answer". The filtering behind it stays active.
         * @param backgroundScope where the fire-and-forget cache refresh runs.
         */
        fun create(
            store: KeyValueStore,
            relayClient: RelayClient,
            signer: AdminSigner,
            timeSource: TimeSource,
            backgroundScope: CoroutineScope,
            relayFetch: AdminRelayFetch? = null,
            zone: () -> ZoneId = { ZoneId.systemDefault() },
            log: DiagnosticLog = AppLogger,
        ): AdminRegistry = DefaultAdminRegistry(store, relayClient, signer, timeSource, backgroundScope, relayFetch, zone, log)
    }
}

/** Per-relay admin-list query (Dart `_fetchFromSingleRelay` signature), replaceable in tests. */
fun interface AdminRelayFetch {
    /** Raw entries per signer from [relayUrl] for [authorsHex], or `null` for "no usable answer". */
    suspend fun fetch(relayUrl: String, authorsHex: List<String>): AdminsBySigner?
}

/** Domain error with Flutter's German message (Dart `Exception('…')`). */
class AdminRegistryException(message: String) : Exception(message)
