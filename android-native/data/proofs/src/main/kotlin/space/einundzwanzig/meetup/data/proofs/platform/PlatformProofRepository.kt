package space.einundzwanzig.meetup.data.proofs.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement

/** Why [PlatformProofRepository.createProof] failed. */
enum class ProofCreateError {
    /** No npub or the signer cannot sign ("Kein Schlüssel vorhanden", `proofErrNoKey`). */
    NO_KEY,

    /** The trimmed username is empty ("Benutzername darf nicht leer sein", `ppErrEmptyUsername`). */
    EMPTY_USERNAME,

    /** Signer rejection, timeout, storage error or an identity switch during signing ("Fehler: $e", `proofErrGeneric`). */
    SIGNER_OR_UNKNOWN,
}

/** Outcome of [PlatformProofRepository.createProof] (Dart `ProofCreateResult`). */
sealed interface ProofCreateResult {

    /** Dart `success`. */
    val success: Boolean

    /** The German service message of the Flutter app (shown raw on failure). */
    val message: String

    /** The proof was signed and stored. [verifyString] is generated but never shown in Flutter. */
    data class Success(val verifyString: String, val proof: PlatformProof) : ProofCreateResult {
        override val success: Boolean get() = true
        override val message: String get() = "Verify-String erstellt ✓"
    }

    /**
     * Creation failed. [detail] is the exception text for [ProofCreateError.SIGNER_OR_UNKNOWN]
     * (for a signer rejection the signer's reason, e.g. "Permission denied for sign_event kind:21003").
     */
    data class Failure(val error: ProofCreateError, val detail: String? = null) : ProofCreateResult {
        override val success: Boolean get() = false
        override val message: String
            get() = when (error) {
                ProofCreateError.NO_KEY -> "Kein Schlüssel vorhanden"
                ProofCreateError.EMPTY_USERNAME -> "Benutzername darf nicht leer sein"
                ProofCreateError.SIGNER_OR_UNKNOWN -> "Fehler: ${detail.orEmpty()}"
            }
    }
}

/**
 * Locally stored platform proofs (Dart `PlatformProofService` storage + creation, spec 06 §8.1)
 * under the Flutter key [PlatformProof.STORAGE_KEY] (`platform_proofs`) of the shared
 * `KeyValueStore`.
 *
 * Storage deviates from Dart only for broken data (spec 06 §14 R9): entries are parsed one by one
 * (a malformed entry is skipped instead of emptying the whole list), and entries this repository
 * did not touch are written back verbatim (unknown keys and malformed entries survive).
 * Every read-modify-write runs in one atomic `KeyValueStore.edit` transaction, so it cannot
 * interleave with `HumanityKeys.clearIdentityProofs` (identity switch, spec 06 §14 R20).
 * Commits also compare the durable identity generation and its intended npub inside that transaction. Native
 * ownership metadata is checked by all readers and publishers; legacy proof JSON and backup
 * entries retain their original format and are adopted by the current identity.
 * All calls are main-safe (work runs on the default dispatcher).
 *
 * After [createProof]/[removeProof] the caller republishes the reputation event
 * (`:data:reputation`, `publish(badges, getProofsForPublishing(), force = true)`).
 */
interface PlatformProofRepository {

    /** The parsed proofs in stored order; emits on every change of the key. */
    val proofs: Flow<List<PlatformProof>>

    /** Dart `getSavedProofs()`: the parsed proofs in stored order (empty if none). */
    suspend fun getSavedProofs(): List<PlatformProof>

    /**
     * Dart `createProof` (spec 06 §8.1): signs kind 21003 (tags
     * `[["t","platform_proof"],["platform",p],["username",u]]`, canonical JSON content), never
     * publishes it, and stores the proof (replacing an entry with the same platform, appended at
     * the end). [customPlatformName] (trimmed by the caller, only for `other`) becomes the id via
     * [PlatformCatalog.customPlatformId]. Never throws except for cancellation.
     */
    suspend fun createProof(platformId: String, username: String, customPlatformName: String? = null): ProofCreateResult

    /**
     * Dart `removeProof`: removes every entry whose `platform` is a string equal to [platformId]
     * (a non-string `platform` never matches, like [PlatformProof.fromStorageJson] never reads it).
     * @return `false` if the write failed (the stored list is unchanged).
     */
    suspend fun removeProof(platformId: String): Boolean

    /** Dart `getProofsForPublishing()`: `platform → proof` in stored order (last one wins on duplicates). */
    suspend fun getProofsForPublishing(): LinkedHashMap<String, PlatformProof>

    /**
     * Backup restore, replace mode: filters [validated] with [PlatformProofBackup.restorable]
     * (only entries with a 128-hex `proof_sig`, Audit 2 finding #4; enforced here, so callers that
     * filter already are unaffected) and writes the rest verbatim. If nothing is left, the device
     * list is kept and the call returns `true` without writing.
     * @return `false` if the write failed.
     */
    suspend fun replaceAllRaw(validated: List<JsonElement>): Boolean

    /**
     * Backup restore, additive mode: [PlatformProofBackup.union] of the device list and the
     * [PlatformProofBackup.restorable] entries of [incoming] (device wins per lowercased
     * platform), written only if the union is not empty.
     * A stored value that is not a JSON list is kept untouched (Dart's `jsonDecode(raw) as List`
     * threw before writing) and the call returns `false`.
     * @return `false` if the write failed or the device value is unreadable.
     */
    suspend fun unionRaw(incoming: List<JsonElement>): Boolean

    /** Removes the current identity's key. @return `false` if the write failed or was fenced. */
    suspend fun clear(): Boolean
}
