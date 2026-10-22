package space.einundzwanzig.meetup.data.identity.keys

/**
 * Identities that `:data:migration` could only park under recovery names (spec 14 §4.8:
 * `legacy_esp/…`, `legacy_backup/…`, `legacy_recovered/…`), for example the key of an earlier
 * Flutter identity found in a stale storage backend. They are never used automatically (spec 14
 * R5): the identity area shows a one-time notice that offers restore ([adopt]) or export
 * ([exportNsec], behind device authentication and `FLAG_SECURE`) and then [discard]s the copy.
 * The full reset deletes them via `IdentityRepository.wipeAll()`.
 */
interface IdentityRecovery {
    /**
     * The recoverable identities, including overflow slots of each [RecoverySource]. Identities equal to the
     * active local key are not listed. Empty if nothing was parked.
     *
     * @throws space.einundzwanzig.meetup.data.identity.SecureStorageUnreadableException if the
     *   secure store cannot be listed.
     */
    suspend fun candidates(): List<RecoveryCandidate>

    /**
     * Makes [candidate] the local key (overwrites the current local key, like an nsec import) and
     * removes its recovery copy. Confirm with the user first. Afterwards follow spec 01 §8.2 like
     * every import: `LocalKeyVault.clearAll()` + `SigningService.useLocalMode()`.
     *
     * @param password needed only if the candidate has no plain key, just a password wrap
     *   ([RecoveryCandidate.needsPassword]).
     * @throws space.einundzwanzig.meetup.data.identity.KeyFormatException if the copy holds no
     *   usable key or the password is missing.
     * @throws space.einundzwanzig.meetup.data.identity.KeyOperationException on a wrong password
     *   or insufficient memory.
     */
    suspend fun adopt(candidate: RecoveryCandidate, password: String? = null): GeneratedKeys

    /**
     * The `nsec1…` of [candidate] for an export (only after device authentication).
     *
     * @throws space.einundzwanzig.meetup.data.identity.KeyFormatException if it has no plain key.
     */
    suspend fun exportNsec(candidate: RecoveryCandidate): String

    /** Exports a parked password wrap without decrypting it; device authentication is required. */
    suspend fun exportNcryptsec(candidate: RecoveryCandidate): String =
        throw space.einundzwanzig.meetup.data.identity.KeyFormatException("No encrypted recovery key")

    /** Deletes only [candidate]'s entries, preserving other identities in the same source/slot (the user dismissed the notice). */
    suspend fun discard(candidate: RecoveryCandidate)

    /**
     * Deletes every identity recovery entry of every source, including unreadable ones (part of
     * the full reset). Badge keys under recovery names are left to the badge domain.
     *
     * @throws space.einundzwanzig.meetup.data.identity.IdentityStorageException if a delete failed
     *   (the remaining deletes still ran).
     */
    suspend fun discardAll()
}

/** Where a recovery copy came from (spec 14 §4.8). */
enum class RecoverySource(
    /** The secure-store name prefix. */
    val prefix: String,
) {
    /** Old `EncryptedSharedPreferences` value that matched no identity the Flutter prefs still referenced. */
    ESP("legacy_esp"),

    /** A `_BACKUP` copy that differed from the main entry. */
    BACKUP("legacy_backup"),

    /** A Flutter value found on a retry when a native value already existed. */
    RECOVERED("legacy_recovered"),
}

/**
 * One parked identity. Holds no secret.
 *
 * @property npub the identity (derived from the private key when present).
 * @property hasPrivateKey `true` if a plain nsec / private key is stored: [IdentityRecovery.adopt]
 *   and [IdentityRecovery.exportNsec] work without a password.
 * @property needsPassword only a password wrap (`ncryptsec`) is stored: adopt needs its password.
 */
data class RecoveryCandidate(
    val source: RecoverySource,
    val npub: String,
    val hasPrivateKey: Boolean,
    val needsPassword: Boolean,
    /** 1 is the original name, 2..16 are overflow names ending in #n. */
    val slot: Int = 1,
)
