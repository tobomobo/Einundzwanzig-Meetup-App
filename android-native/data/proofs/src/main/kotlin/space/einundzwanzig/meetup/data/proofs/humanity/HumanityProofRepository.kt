package space.einundzwanzig.meetup.data.proofs.humanity

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.data.prefs.KeyValueEditor
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProof

/**
 * Proof of Humanity (Dart `HumanityProofService`, spec 06 §8.3): a NIP-57 zap receipt in which
 * the user is sender or recipient, fully validated locally and signed by the recipient's LNURL
 * provider. State lives in the `humanity_*` keys ([HumanityKeys]) of the shared `KeyValueStore`.
 *
 * Re-verification of a restored proof is driven by an `AppStartupTask` of this module that
 * watches `humanity_needs_reverification` for the whole process lifetime: it runs
 * [reverifyIfNeeded] at start and again whenever a backup restore (onboarding or backup screen)
 * sets the flag, like Dart's home-screen `_loadIdentityData`. Screens do not need to call
 * [reverifyIfNeeded]. All calls are main-safe (work runs on the default dispatcher).
 */
interface HumanityProofRepository {

    /** [getStatus] now and after every change of a `humanity_*` key. */
    val status: Flow<HumanityStatus>

    /** Dart `getStatus()`; `needsReverification` forces `verified = false`. */
    suspend fun getStatus(): HumanityStatus

    /**
     * Dart `checkForZaps()`: searches relays (active relays + 6 zap-heavy relays, sequential, three
     * REQs per socket, 12 s), validates candidates locally and against the recipient's LNURL
     * provider. Found → stores the proof (and, unlike Flutter, clears a pending re-verification,
     * spec 06 §14 R6). Not found → only `humanity_checked_at` is updated; a previous verification
     * stays. Cancellable; never throws except for cancellation.
     */
    suspend fun checkForZaps(): HumanityCheckResult

    /**
     * Dart `reverifyIfNeeded()`: if `humanity_needs_reverification` is set, runs [checkForZaps];
     * on success sets `humanity_verified` and removes the flag. @return `true` on success.
     */
    suspend fun reverifyIfNeeded(): Boolean

    /** Dart `reset()`: removes verified, receipt id, first-zap and checked-at keys (not the flag). */
    suspend fun reset()

    /**
     * Dart `getProofForPublishing()`: `{"verified":true,"method":"lightning_zap","first_zap_at":N}`
     * if [getStatus] is verified, else `null`. Goes into kind-30078 `humanity_proof`.
     */
    suspend fun getProofForPublishing(): JsonObject?

    /** Dart `checkForZapsByPubkey` (unused in Flutter): same search on a reduced relay list. */
    suspend fun checkForZapsByPubkey(pubkeyHex: String): Boolean

    /** Dart `verifyReceiptExists` (unused in Flutter): a verified kind-9735 event with that id exists. */
    suspend fun verifyReceiptExists(receiptEventId: String): Boolean

    /**
     * Backup restore (Dart `BackupService`, Audit 2 finding #4). `humanityAlready = additive &&
     * humanity_verified == true`. If not already verified and `backupProof.verified == true`:
     * `humanity_verified = false`, `humanity_needs_reverification = true`, plus
     * `receipt_event_id` (if not empty), `first_zap_at` (if > 0), `checked_at` (if > 0).
     * @return `true` if a pending proof was written (the restore snackbar lists "Humanity-Proof").
     */
    suspend fun restoreFromBackup(backupProof: JsonObject?, additive: Boolean): Boolean

    /**
     * Identity switch (Dart `BadgeSyncService.clearIdentityProofs` without the badge-sync key):
     * see [HumanityKeys.clearIdentityProofs]. @return `false` if the write failed.
     */
    suspend fun clearForIdentitySwitch(): Boolean
}

/** The Flutter pref keys of the humanity proof. */
object HumanityKeys {
    const val VERIFIED: String = "humanity_verified"
    const val FIRST_ZAP_AT: String = "humanity_first_zap_at"
    const val RECEIPT_ID: String = "humanity_receipt_id"
    const val CHECKED_AT: String = "humanity_checked_at"
    const val NEEDS_REVERIFICATION: String = "humanity_needs_reverification"

    /** Every humanity key. */
    val ALL: List<String> = listOf(VERIFIED, FIRST_ZAP_AT, RECEIPT_ID, CHECKED_AT, NEEDS_REVERIFICATION)

    /**
     * The identity-switch wipe of Dart `BadgeSyncService.clearIdentityProofs()` inside one
     * transaction: `humanity_verified = false`, removes the other humanity keys and
     * `platform_proofs`. `:data:badgesync` adds `badge_restore_proofs = true` in the same edit:
     *
     * ```
     * val ok = store.edit { HumanityKeys.clearIdentityProofs(this); setBool("badge_restore_proofs", true) }
     * if (!ok) throw IllegalStateException("Die Nachweise der bisherigen Identität konnten nicht abgeräumt werden.")
     * ```
     */
    fun clearIdentityProofs(editor: KeyValueEditor) {
        editor.setBool(VERIFIED, false)
        editor.remove(NEEDS_REVERIFICATION)
        editor.remove(RECEIPT_ID)
        editor.remove(FIRST_ZAP_AT)
        editor.remove(CHECKED_AT)
        editor.remove(PlatformProof.STORAGE_KEY)
    }
}
