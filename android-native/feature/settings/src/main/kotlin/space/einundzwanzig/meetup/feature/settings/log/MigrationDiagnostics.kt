package space.einundzwanzig.meetup.feature.settings.log

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.data.migration.MigrationState
import space.einundzwanzig.meetup.feature.settings.R

@Composable
internal fun MigrationDiagnostics(state: MigrationDiagnosticState, onAction: (DiagnosticLogAction) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(R.string.native_migration_title))
            }
            val report = state.report
            if (expanded || report?.state == MigrationState.IMPORTED_WITH_ERRORS || state.failed) {
                Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    report?.let {
                        Text(stringResource(when (it.state) {
                            MigrationState.NOT_STARTED -> R.string.native_migration_not_started
                            MigrationState.NOT_NEEDED -> R.string.native_migration_not_needed
                            MigrationState.IMPORTED -> R.string.native_migration_imported
                            MigrationState.IMPORTED_WITH_ERRORS -> R.string.native_migration_pending
                            MigrationState.CLEANED -> R.string.native_migration_cleaned
                        }))
                        Text(stringResource(R.string.native_migration_attempts, it.attempts))
                        if (it.failedKeys.isNotEmpty()) Text(stringResource(R.string.native_migration_failed_keys, it.failedKeys.joinToString("\n")))
                        if (it.recovered.isNotEmpty()) Text(stringResource(R.string.native_migration_recovered, it.recovered.joinToString("\n")))
                        if (it.warnings.isNotEmpty()) Text(stringResource(R.string.native_migration_warnings, it.warnings.joinToString("\n")))
                    }
                    if (state.failed) Text(stringResource(R.string.native_migration_error), color = MaterialTheme.colorScheme.error)
                }
                if (state.busy) AppLoadingIndicator(size = 24.dp)
                if (report?.state != MigrationState.NOT_NEEDED && report?.state != MigrationState.CLEANED) {
                    FilledTonalButton(enabled = !state.busy, onClick = { onAction(DiagnosticLogAction.RetryMigration) }) {
                        Text(stringResource(R.string.native_migration_retry))
                    }
                }
                if (report?.state == MigrationState.IMPORTED_WITH_ERRORS) {
                    TextButton(enabled = !state.busy, onClick = { onAction(DiagnosticLogAction.RequestAbandonMigration) }) {
                        Text(stringResource(R.string.native_migration_abandon), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    if (state.confirmAbandon) AlertDialog(
        onDismissRequest = { onAction(DiagnosticLogAction.DismissAbandonMigration) },
        title = { Text(stringResource(R.string.native_migration_abandon)) },
        text = { Text(stringResource(R.string.native_migration_abandon_confirm)) },
        confirmButton = {
            TextButton(onClick = { onAction(DiagnosticLogAction.ConfirmAbandonMigration) }) {
                Text(stringResource(R.string.native_migration_abandon), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(DiagnosticLogAction.DismissAbandonMigration) }) { Text(stringResource(R.string.native_migration_cancel)) }
        },
    )
}
