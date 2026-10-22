package space.einundzwanzig.meetup.feature.backup

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.StateFlow
import space.einundzwanzig.meetup.data.backup.RestoreError
import space.einundzwanzig.meetup.data.backup.RestoreOutcome

/**
 * Entry point for the backup flows (Dart `BackupService.createBackup` / `restoreBackup`, spec 08
 * §2.1). Inject it into a ViewModel (`:feature:home`, `:feature:settings`, `:feature:onboarding`)
 * or get it in a composable with [rememberBackupLauncher]. The dialogs, the overlay, the SAF pickers,
 * the share sheet and the snackbars are rendered by [BackupHost], mounted once at the app root.
 *
 * Both flows share one busy guard: a request while another export or import runs returns `false`
 * immediately and silently (spec 08 §1 #23).
 */
interface BackupLauncher {

    /** `true` while an export or import runs (e.g. to disable the backup buttons). */
    val busy: StateFlow<Boolean>

    /**
     * Export flow (spec 08 §2.4): password dialog → overlay → SAF "save file" → share fallback.
     *
     * @return `true` only if a file was written or a share target was chosen (or the share outcome
     *   is unobservable). The reset flow wipes the device on `true` (spec 08 §2.6).
     */
    suspend fun export(): Boolean

    /**
     * Import flow (spec 08 §2.5): file picker → password → decrypt → identity dialogs → restore.
     *
     * @return `true` on a successful restore. Reload all state after every call, also on `false`
     *   (profile, keys or proofs may already be written; spec 08 §2.1).
     */
    suspend fun restore(): Boolean

    /**
     * [export] on the application scope, for callers whose own scope ends right away (the settings
     * sheet is popped before the export starts). [onResult] runs on the main thread.
     */
    fun launchExport(onResult: (Boolean) -> Unit = {})

    /** [restore] on the application scope; see [launchExport]. */
    fun launchRestore(onResult: (Boolean) -> Unit = {})
}

/** What [BackupHost] renders. */
@Immutable
data class BackupUiState(
    val dialog: BackupDialog? = null,
    /** The blocking loading overlay (spec 08 §2.3). */
    val loading: Boolean = false,
)

/** The modal dialogs of the backup flows (spec 08 §2.2, §2.5.1, §2.5.2). */
sealed interface BackupDialog {
    /** Password dialog; [isExport] adds the confirm field and the rule checklist. */
    data class Password(val isExport: Boolean) : BackupDialog

    /** `backupReplaceTitle/Body/Confirm` (destructive). */
    data class ConfirmReplace(val existingShort: String, val backupShort: String) : BackupDialog

    /** `backupAddUnknownTitle/Body/Confirm`. */
    data class ConfirmAddUnknown(val existingShort: String) : BackupDialog
}

/** User input on the backup dialogs. */
sealed interface BackupAction {
    data class SubmitPassword(val password: String) : BackupAction
    data object CancelPassword : BackupAction
    data object Confirm : BackupAction
    data object Decline : BackupAction
}

/** One-shot snackbars of the backup flows; [BackupHost] maps them to strings. */
sealed interface BackupMessage {
    /** `badgesUnreadableBody`, floating, 8 s. */
    data object BadgesUnreadable : BackupMessage

    /** `backupError(detail)`, red. */
    data class ExportFailed(val detail: String) : BackupMessage

    /** `backupWrongPassword`, red. */
    data object WrongPassword : BackupMessage

    /** `backupImportFailed(<reason>)`, red. */
    data class ImportFailed(val reason: ImportFailure) : BackupMessage

    /** `backupMergeAdded(added)` / `backupMergeSame`, green, 4 s. */
    data class Merged(val added: Int) : BackupMessage

    /** `backupLoaded(items)`, green, 4 s. */
    data class Loaded(val outcome: RestoreOutcome.Restored) : BackupMessage
}

/** Why an import failed (argument of `backupImportFailed`). */
sealed interface ImportFailure {
    /** `backupNotValid`. */
    data object NotValid : ImportFailure

    /** `backupNotEinundzwanzig`. */
    data object NotEinundzwanzig : ImportFailure

    /** The file could not be read (provider error, not UTF-8, too large). */
    data class Unreadable(val detail: String?) : ImportFailure

    /** The restore engine failed. */
    data class Restore(val error: RestoreError) : ImportFailure

    /** Anything unexpected; [detail] is the exception text. */
    data class Unexpected(val detail: String) : ImportFailure
}
