package space.einundzwanzig.meetup.core.data.secure

/**
 * Secret string storage with `flutter_secure_storage` semantics (string key -> string value).
 *
 * Production values are encrypted with a non-exportable Android Keystore AES-256-GCM key and
 * kept in the DataStore file `secure_prefs`. **Key names are the flutter_secure_storage key
 * names** (`nostr_nsec_key`, `badge_encryption_key`, ...), so the spec key tables apply verbatim.
 *
 * Error model (nothing here ever throws for storage or Keystore problems):
 * - [read] returns `null` for an absent **and** for an unreadable entry (corrupt ciphertext,
 *   lost or invalidated Keystore key, Keystore failure). The failure is logged.
 * - [readChecked] tells these cases apart. **Use it whenever `null` would make you create a new
 *   secret** (identity key, badge key, pending key): Dart ran with `resetOnError = false`, so a
 *   read error threw and the caller kept its hands off the entry instead of overwriting it.
 *   Overwriting a temporarily unreadable key destroys every blob encrypted with it.
 * - [write], [delete] and [deleteAll] return `false` on failure (Dart: threw a
 *   `PlatformException`). A Dart `try { write } catch { return null }` becomes
 *   `if (!write(...)) return null`.
 * - [readAll] skips (and logs) unreadable entries.
 * - [containsKey] and [readAll] are lenient: a storage failure reads as "no entry" / "empty".
 *   Use [containsKeyChecked] / [readAllChecked] wherever that difference matters (backup export,
 *   migration verification, "was it really deleted?" checks).
 * - The `secure_prefs` file is never reset on corruption: a corrupt file makes every read
 *   [SecureRead.Unreadable] ([SecureRead.Unreadable.Reason.STORAGE_CORRUPT]) and every write
 *   fail, so no caller mistakes lost secrets for absent ones.
 *
 * Values are never logged. Use `InMemorySecureStore` (package `testing`) in unit tests.
 */
interface SecureStore {

    /** The value of [key], or `null` if absent or unreadable (see [readChecked]). */
    suspend fun read(key: String): String? = (readChecked(key) as? SecureRead.Present)?.value

    /** Reads [key] and reports whether it is present, absent or present-but-unreadable. */
    suspend fun readChecked(key: String): SecureRead

    /** Stores [value] under [key], replacing any previous value. `false` if it was not stored. */
    suspend fun write(key: String, value: String): Boolean

    /**
     * Stores all [entries] in one atomic transaction: either every entry is written or none is
     * (`false`). Use it for secrets that only make sense together (`nostr_nsec_key` +
     * `nostr_npub_key` + `nostr_priv_hex`) and for bulk imports (Flutter migration, restore).
     */
    suspend fun writeAll(entries: Map<String, String>): Boolean

    /** Deletes [key]. Deleting an absent key succeeds. `false` if the entry could not be deleted. */
    suspend fun delete(key: String): Boolean

    /** Deletes every entry. `false` if nothing was deleted because the write failed. */
    suspend fun deleteAll(): Boolean

    /**
     * Whether an entry exists for [key], readable or not (Dart `containsKey`); a failure of the
     * storage itself fails the [Result] instead of answering `false`.
     */
    suspend fun containsKeyChecked(key: String): Result<Boolean>

    /**
     * Every readable entry, sorted by key name. Entries that exist but cannot be decrypted are
     * skipped and logged (check them with [readChecked]); a failure of the storage itself
     * fails the [Result] instead of answering with an empty map.
     */
    suspend fun readAllChecked(): Result<Map<String, String>>

    /**
     * Lenient [containsKeyChecked]: a storage failure reads as `false`. Only for callers where
     * "cannot tell" may be treated like "no entry".
     */
    suspend fun containsKey(key: String): Boolean = containsKeyChecked(key).getOrDefault(false)

    /**
     * Lenient [readAllChecked]: a storage failure reads as an empty map. Only for callers where
     * "cannot read" may be treated like "nothing stored" (e.g. Dart `_candidateKeys`, which
     * ignored read errors).
     */
    suspend fun readAll(): Map<String, String> = readAllChecked().getOrDefault(emptyMap())
}

/** Outcome of [SecureStore.readChecked]. */
sealed interface SecureRead {
    /** The entry exists and was decrypted. [toString] never reveals the value. */
    data class Present(val value: String) : SecureRead {
        override fun toString(): String = "Present(<redacted>)"
    }

    /** There is no entry for the key. Creating a new secret is safe. */
    data object Absent : SecureRead

    /**
     * The entry exists, or may exist, but could not be read. **Do not overwrite it with a freshly
     * generated secret.** [reason] tells a transient failure (retry later) from a permanent one
     * (the value is lost for good; recovery needs a user decision or a backup).
     */
    data class Unreadable(val cause: Throwable, val reason: Reason) : SecureRead {
        /** `true` if retrying cannot help: the stored value will never decrypt again. */
        val isPermanent: Boolean get() = reason.isPermanent

        /** Why an entry could not be read. */
        enum class Reason(
            /** See [Unreadable.isPermanent]. */
            val isPermanent: Boolean,
        ) {
            /** The storage file could not be read (I/O error). Transient; whether the key exists is unknown. */
            STORAGE_IO(isPermanent = false),

            /** The storage file is corrupt (a copy was kept for support). Whether the key existed is unknown. */
            STORAGE_CORRUPT(isPermanent = true),

            /** The Keystore failed or was not available (IPC error, early boot, provider failure). Transient. */
            KEYSTORE_UNAVAILABLE(isPermanent = false),

            /** The Keystore key is gone or permanently invalidated; nothing it encrypted can be read again. */
            KEY_MISSING(isPermanent = true),

            /** The ciphertext does not authenticate: tampered data or a replaced Keystore key. */
            AUTHENTICATION_FAILED(isPermanent = true),

            /** The stored value is not a well-formed ciphertext (bad Base64, too short, not UTF-8). */
            MALFORMED(isPermanent = true),
        }
    }
}
