package space.einundzwanzig.meetup.data.badges.store

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.data.secure.SecureStore
import space.einundzwanzig.meetup.data.badges.internal.EncryptedBadgeRepository
import space.einundzwanzig.meetup.data.badges.model.FormerCollection
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge

/**
 * The encrypted badge wallet (Dart `MeetupBadge.loadBadges/saveBadges/...`, spec 02 §4.2),
 * a 1:1 port of the store algorithms that protect users from data loss: AES-256-GCM blob in the
 * shared prefs plus a second copy in the secure store, two backup slots, an archive of former
 * collections, a separately encrypted "kept aside" slot while the wallet is unreadable, staging
 * for identity switches, and a reset marker. Storage keys are Flutter's ([BadgeStoreKeys]).
 *
 * **Never destroys data on read errors**: a key read failure never creates a new key, unknown
 * key values are moved aside, unreadable blobs stay in place / in backup slots / in the archive,
 * clearing archives instead of deleting, only [resetAll] deletes.
 *
 * All operations are serialized (one at a time) and run on the IO dispatcher.
 */
interface BadgeRepository {

    /**
     * The in-memory collection (Dart global `myBadges`): set by [loadBadges] and every successful
     * save/clear/promote/merge/reset. Empty until the first load.
     */
    val badges: StateFlow<List<MeetupBadge>>

    /**
     * Dart `MeetupBadge.badgesUnreadable`: a badge file exists but no key opens it. An empty
     * [badges] list is then NOT an empty wallet; never offer actions that write an empty list.
     */
    val badgesUnreadable: StateFlow<Boolean>

    /**
     * Fires after every successful save, clear, promote and merge (replaces Dart
     * `onBadgesSaved`); `:data:badgesync` collects it to trigger the relay sync.
     */
    val savedEvents: SharedFlow<Unit>

    /** Loads (and, where Dart does, repairs/unites) the collection; `[]` + [badgesUnreadable] if nothing opens. */
    suspend fun loadBadges(): List<MeetupBadge>

    /**
     * Stores [badges] (united with any kept-aside badges). An empty list **clears by archiving**.
     *
     * @param replaceUnreadable a non-empty list may replace an unreadable file (the old file moves
     *   to the backup chain).
     * @param discardLocked an empty list may clear even an unreadable file (explicit reset of the
     *   collection; it still lands in the archive).
     * @return `false` if nothing was written (unreadable wallet, no key, archive or prefs failure).
     */
    suspend fun saveBadges(
        badges: List<MeetupBadge>,
        replaceUnreadable: Boolean = false,
        discardLocked: Boolean = false,
    ): Boolean

    /**
     * Keeps a scanned badge aside (encrypted with its own key) while the wallet is unreadable;
     * it is merged on the next successful read. Dedups by `id` like Dart.
     *
     * @return `false` if the badge is stored nowhere (the caller must tell the user).
     */
    suspend fun keepAside(badge: MeetupBadge): Boolean

    /** Encrypts [badges] into the identity-switch staging slot; the active file is untouched. */
    suspend fun stageIncoming(badges: List<MeetupBadge>): Boolean

    /**
     * Makes the staged collection active; everything the device still has of the old one goes
     * into the archive as `replaced` (labelled with [formerNpub]). Crash-safe and idempotent.
     */
    suspend fun promoteIncoming(formerNpub: String = ""): Boolean

    /** Drops the staging slot. */
    suspend fun discardIncoming()

    /** Archived collections, newest first (`badges == null` if no key opens one). */
    suspend fun formerCollections(): List<FormerCollection>

    /**
     * Unites the archived collection at stored [index] ([FormerCollection.index]) with the active
     * one. The archive entry stays. `false` if unreadable, the wallet is unreadable, or not saved.
     */
    suspend fun mergeFormer(index: Int): Boolean

    /** The app reset: deletes everything in both stores, including archive and staging. */
    suspend fun resetAll()

    companion object {
        /**
         * A repository on explicit stores, e.g. `InMemoryKeyValueStore`/`InMemorySecureStore` in
         * tests of other modules. Production code injects the `@Singleton` instead.
         */
        fun create(
            prefs: KeyValueStore,
            secure: SecureStore,
            timeSource: TimeSource,
            dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
        ): BadgeRepository = EncryptedBadgeRepository(prefs, secure, timeSource, dispatcher)
    }
}

/** Flutter storage keys of the badge store (spec 02 §4.1). */
object BadgeStoreKeys {
    /** Prefs: primary blob `b64(iv):b64(ct‖tag)`. Also the secure-store key of its second copy. */
    const val PRIMARY = "badges_encrypted"

    /** Prefs: first backup slot. */
    const val PRESERVED = "badges_encrypted_preserved"

    /** Prefs: second (older) backup slot. */
    const val OLDER = "badges_encrypted_preserved_older"

    /** Prefs: legacy plaintext string list (read and migrated). */
    const val LEGACY = "badges"

    /** Prefs: kept-aside badges, encrypted with [PENDING_KEY]. */
    const val PENDING = "badges_pending_encrypted"

    /** Prefs: legacy plaintext pending list (PR 84 test builds). */
    const val LEGACY_PENDING = "badges_pending"

    /** Prefs: identity-switch staging slot (Dart `MeetupBadge.incomingPrefsKey`). */
    const val INCOMING = "badges_encrypted_incoming"

    /** Prefs + secure store: archive JSON list (Dart `MeetupBadge.formerPrefsKey`). */
    const val FORMER = "badges_former"

    /** Secure: the AES-256 badge key (64 hex). */
    const val KEY = "badge_encryption_key"

    /** Secure: migration copy of the key written by flutter_secure_storage. */
    const val KEY_BACKUP = "badge_encryption_key_BACKUP"

    /** Secure: a non-hex value found under [KEY], moved aside on a deliberate replace. */
    const val KEY_SUSPECT = "badge_encryption_key_suspect"

    /** Secure: key of the kept-aside slot. */
    const val PENDING_KEY = "badge_pending_key"

    /** Secure: moved-aside invalid pending key. */
    const val PENDING_KEY_SUSPECT = "badge_pending_key_suspect"

    /** Secure: `"1"` while a reset could not delete the secure copies. */
    const val RESET_PENDING = "badge_reset_pending"
}
