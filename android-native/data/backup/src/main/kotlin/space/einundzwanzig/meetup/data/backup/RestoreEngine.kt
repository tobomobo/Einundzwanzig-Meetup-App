package space.einundzwanzig.meetup.data.backup

/**
 * Applies a decrypted backup to the device (Dart `BackupService.restoreBackup` from step 7 on,
 * spec 08 §2.5, §8.2–§8.10): identity decision and confirmation, the crash-safe badge-sync restore
 * hold, profile, badges (merge / stage + promote / plain write), Nostr keys and external signers,
 * admin registry, platform proofs and the humanity proof (pending re-verification only).
 *
 * Callers must reload their state after every attempt, also a failed one: profile, keys or proofs
 * may already be written when a later write fails (Flutter parity). Malformed content is rejected
 * before anything is written.
 *
 * Run it inside [BackupSession.tryRun] together with the file read and decryption. The engine
 * additionally serializes concurrent calls itself.
 */
interface RestoreEngine {

    /**
     * Runs the import. [confirm] is asked (at most once) before anything is written when the backup
     * belongs to another identity ([RestoreConfirmation.Replace]) or has none while the device has
     * one ([RestoreConfirmation.AddUnknown]); `false` → [RestoreOutcome.Cancelled].
     *
     * Never throws except for cancellation; errors become [RestoreOutcome.Failed].
     *
     * Cancellation: until [confirm] has returned `true` (or no confirmation is needed), cancelling the
     * caller aborts without writing anything. After that the write phase runs to the end under
     * `NonCancellable` (like Flutter, which could not be interrupted past the dialogs), so a
     * `viewModelScope` that dies mid-import cannot leave a half-switched identity behind.
     */
    suspend fun restore(
        document: BackupDocument,
        confirm: suspend (RestoreConfirmation) -> Boolean,
    ): RestoreOutcome
}

/** A confirmation the UI must show before [RestoreEngine.restore] continues. */
sealed interface RestoreConfirmation {
    /** Full npub of the device identity (or of the identity whose switch is still pending). */
    val existingNpub: String

    /** [existingNpub] shortened like `NostrService.shortenNpub`. */
    val existingShort: String get() = RestorePolicy.shortenNpub(existingNpub)

    /**
     * `backupReplaceTitle` / `backupReplaceBody(existingShort, backupShort)` / `backupReplaceConfirm`
     * (red). Destructive: the old collection is archived ("Frühere Sammlungen").
     */
    data class Replace(override val existingNpub: String, val backupNpub: String) : RestoreConfirmation {
        /** Shortened backup npub, `"?"` if empty. */
        val backupShort: String get() = if (backupNpub.isEmpty()) "?" else RestorePolicy.shortenNpub(backupNpub)
    }

    /** `backupAddUnknownTitle` / `backupAddUnknownBody(existingShort)` / `backupAddUnknownConfirm`. */
    data class AddUnknown(override val existingNpub: String) : RestoreConfirmation
}

/** Result of [RestoreEngine.restore]. */
sealed interface RestoreOutcome {
    /** The user declined a confirmation; nothing was written. */
    data object Cancelled : RestoreOutcome

    /**
     * Additive import (same identity / backup without identity): `backupMergeAdded(added)` if
     * [added] > 0, else `backupMergeSame` (green, 4 s).
     */
    data class Merged(val added: Int) : RestoreOutcome

    /**
     * Fresh or replacing import: `backupLoaded(items)` with items "Profil, N Badges[, Nostr-Key]
     * [, M Plattform-Proofs][, Humanity-Proof]" (native string keys `backupItem*`).
     *
     * @property badges number of badge entries in the file.
     * @property nostrKey the file carried a local key (`version >= 2 && nostr.has_key`).
     * @property platformProofs number of valid platform-proof entries successfully written, `null` if none.
     * @property humanity the file carried a verified humanity proof (now pending re-verification).
     */
    data class Restored(
        val badges: Int,
        val nostrKey: Boolean,
        val platformProofs: Int?,
        val humanity: Boolean,
    ) : RestoreOutcome {
        /** The German item list exactly as Flutter builds it (fallback for the snackbar). */
        val germanItems: String
            get() = buildList {
                add("Profil")
                add("$badges Badges")
                if (nostrKey) add("Nostr-Key")
                if (platformProofs != null) add("$platformProofs Plattform-Proofs")
                if (humanity) add("Humanity-Proof")
            }.joinToString(", ")
    }

    /** The import failed; show `backupImportFailed(<localized error>)` in red. */
    data class Failed(val error: RestoreError) : RestoreOutcome
}

/**
 * Why an import failed. [german] is the exact Flutter text (hard-coded German in Dart); features map
 * the typed cases to the suggested native string keys of spec 08 §2.7.
 */
sealed interface RestoreError {
    /** The Flutter (German) message. */
    val german: String

    /** `backupErrSwitchPending`: an earlier identity switch is unfinished and this is no replace. */
    data object SwitchPending : RestoreError {
        override val german: String =
            "Ein Identitätswechsel ist noch nicht abgeschlossen. Lade das Backup der neuen Identität noch einmal."
    }

    /** `backupErrHoldNotPersisted`. */
    data object HoldNotPersisted : RestoreError {
        override val german: String = "Der Wechsel konnte nicht festgehalten werden."
    }

    /** `backupErrClearNotMarked`. */
    data object ClearNotMarked : RestoreError {
        override val german: String = "Die leere Sammlung konnte nicht vorgemerkt werden."
    }

    /** `backupErrProofsNotCleared`. */
    data object ProofsNotCleared : RestoreError {
        override val german: String = "Die Nachweise der bisherigen Identität konnten nicht abgeräumt werden."
    }

    /** Proof persistence failed after other sections may already have been applied. */
    data object ProofsNotSaved : RestoreError {
        override val german: String =
            "Einige Nachweise aus dem Backup konnten nicht gespeichert werden. Bereits übernommene Daten bleiben erhalten. Lade das Backup erneut."
    }

    /** `backupErrStageFailed(count)`. */
    data class StageFailed(val count: Int) : RestoreError {
        override val german: String get() = "$count Badges aus dem Backup konnten nicht vorbereitet werden."
    }

    /** `backupErrKeyUnreadable`: after the key restore no npub is readable. */
    data object KeyUnreadable : RestoreError {
        override val german: String =
            "Der Schlüssel aus dem Backup ist gerade nicht lesbar, die Sammlung bleibt unverändert."
    }

    /** `backupErrKeyNotTaken`: the keystore does not carry a backup identity after the key restore. */
    data object KeyNotTaken : RestoreError {
        override val german: String =
            "Der Schlüssel aus dem Backup wurde nicht übernommen, die Sammlung bleibt unverändert."
    }

    /** `backupErrBadgesNotSaved(count)`: everything else was applied. */
    data class BadgesNotSaved(val count: Int) : RestoreError {
        override val german: String
            get() = "$count Badges aus dem Backup konnten nicht gespeichert werden, der Rest wurde übernommen."
    }

    /** A field or badge has a shape Flutter's importer rejects; [detail] is technical. */
    data class InvalidContent(val detail: String) : RestoreError {
        override val german: String get() = detail
    }

    /** Anything else (storage, signer); [message] is the exception text. */
    data class Other(val message: String) : RestoreError {
        override val german: String get() = message
    }
}
