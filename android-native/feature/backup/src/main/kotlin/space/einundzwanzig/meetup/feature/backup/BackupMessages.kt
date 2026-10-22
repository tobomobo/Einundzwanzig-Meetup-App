package space.einundzwanzig.meetup.feature.backup

import android.content.res.Resources
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarDurations
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.data.backup.RestoreError
import space.einundzwanzig.meetup.data.backup.RestoreOutcome
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** A snackbar ready to show: text, tone and display time (spec 08 §1 #12–#21). */
data class BackupSnackbar(val text: String, val tone: Tone, val durationMillis: Long)

/** Maps [BackupMessage]s to localized snackbars. */
object BackupMessages {

    /** Flutter's default `SnackBar` duration (4 s), used by every backup snackbar but one. */
    const val DEFAULT_DURATION_MILLIS: Long = 4_000

    fun snackbar(resources: Resources, message: BackupMessage): BackupSnackbar = when (message) {
        BackupMessage.BadgesUnreadable -> BackupSnackbar(
            resources.getString(L10nR.string.badgesUnreadableBody),
            Tone.Warning,
            AppSnackbarDurations.ExtraLong,
        )
        is BackupMessage.ExportFailed -> error(resources.getString(L10nR.string.backupError, message.detail))
        BackupMessage.WrongPassword -> error(resources.getString(L10nR.string.backupWrongPassword))
        is BackupMessage.ImportFailed ->
            error(resources.getString(L10nR.string.backupImportFailed, failureText(resources, message.reason)))
        is BackupMessage.Merged -> success(
            if (message.added > 0) {
                resources.getString(L10nR.string.backupMergeAdded, message.added)
            } else {
                resources.getString(L10nR.string.backupMergeSame)
            },
        )
        is BackupMessage.Loaded ->
            success(resources.getString(L10nR.string.backupLoaded, restoredItems(resources, message.outcome)))
    }

    /**
     * "Profil, N Badges[, Nostr-Key][, M Plattform-Proofs][, Humanity-Proof]" (spec 08 §1 #20).
     * Unlike Flutter ("1 Badges"), a count of 1 uses the singular (README "Deviations").
     */
    fun restoredItems(resources: Resources, outcome: RestoreOutcome.Restored): String = buildList {
        add(resources.getString(R.string.native_backupItemProfile))
        add(resources.getQuantityString(R.plurals.native_backupItemBadges, outcome.badges, outcome.badges))
        if (outcome.nostrKey) add(resources.getString(R.string.native_backupItemNostrKey))
        outcome.platformProofs?.let { add(resources.getQuantityString(R.plurals.native_backupItemPlatformProofs, it, it)) }
        if (outcome.humanity) add(resources.getString(R.string.native_backupItemHumanity))
    }.joinToString(", ")

    /** The `msg` argument of `backupImportFailed`. */
    fun failureText(resources: Resources, failure: ImportFailure): String = when (failure) {
        ImportFailure.NotValid -> resources.getString(L10nR.string.backupNotValid)
        ImportFailure.NotEinundzwanzig -> resources.getString(L10nR.string.backupNotEinundzwanzig)
        is ImportFailure.Unreadable -> resources.getString(R.string.native_backupErrUnreadable)
        // Technical exception text, not translated (README "Deviations"); localized fallback if empty.
        is ImportFailure.Unexpected -> failure.detail.ifBlank { resources.getString(R.string.native_backupErrUnreadable) }
        is ImportFailure.Restore -> restoreErrorText(resources, failure.error)
    }

    /** Localized [RestoreError] (spec 08 §2.7 `backupErr*`). */
    fun restoreErrorText(resources: Resources, error: RestoreError): String = when (error) {
        RestoreError.SwitchPending -> resources.getString(R.string.native_backupErrSwitchPending)
        RestoreError.HoldNotPersisted -> resources.getString(R.string.native_backupErrHoldNotPersisted)
        RestoreError.ClearNotMarked -> resources.getString(R.string.native_backupErrClearNotMarked)
        RestoreError.ProofsNotCleared -> resources.getString(R.string.native_backupErrProofsNotCleared)
        is RestoreError.StageFailed -> resources.getString(R.string.native_backupErrStageFailed, error.count)
        RestoreError.KeyUnreadable -> resources.getString(R.string.native_backupErrKeyUnreadable)
        RestoreError.KeyNotTaken -> resources.getString(R.string.native_backupErrKeyNotTaken)
        is RestoreError.BadgesNotSaved -> resources.getString(R.string.native_backupErrBadgesNotSaved, error.count)
        // The technical detail is logged by the coordinator; the user sees the localized text.
        is RestoreError.InvalidContent -> resources.getString(L10nR.string.backupNotValid)
        // Technical exception text (storage, signer), not translated (README "Deviations").
        is RestoreError.Other -> error.message.ifBlank { resources.getString(R.string.native_backupErrUnreadable) }
    }

    private fun error(text: String) = BackupSnackbar(text, Tone.Error, DEFAULT_DURATION_MILLIS)

    private fun success(text: String) = BackupSnackbar(text, Tone.Success, DEFAULT_DURATION_MILLIS)
}
