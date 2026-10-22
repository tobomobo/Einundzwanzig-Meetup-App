package space.einundzwanzig.meetup.feature.settings.log

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import space.einundzwanzig.meetup.data.migration.MigrationState
import space.einundzwanzig.meetup.feature.settings.*

class MigrationDiagnosticsTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun abandonRequiresConfirmationAndRetryIsSingleFlight() = runTest {
        val migration = FakeMigration()
        migration.report = migration.report.copy(state = MigrationState.IMPORTED_WITH_ERRORS, failedKeys = listOf("nostr_nsec_key"))
        val vm = DiagnosticLogViewModel(FakeDiagnosticLog(), FakeNip44Probe(), migration)
        val collector = CoroutineScope(main.dispatcher).launch { vm.uiState.collect {} }
        vm.onAction(DiagnosticLogAction.ConfirmAbandonMigration)
        assertEquals(0, migration.abandoned)
        vm.onAction(DiagnosticLogAction.RequestAbandonMigration)
        assertTrue(vm.uiState.value.migration.confirmAbandon)
        vm.onAction(DiagnosticLogAction.DismissAbandonMigration)
        assertEquals(0, migration.abandoned)
        val release = CompletableDeferred<Unit>()
        migration.retry = { release.await(); migration.report }
        vm.onAction(DiagnosticLogAction.RetryMigration)
        vm.onAction(DiagnosticLogAction.RetryMigration)
        assertEquals(1, migration.retries)
        assertTrue(vm.uiState.value.migration.busy)
        release.complete(Unit)
        vm.onAction(DiagnosticLogAction.RequestAbandonMigration)
        vm.onAction(DiagnosticLogAction.ConfirmAbandonMigration)
        assertEquals(1, migration.abandoned)
        assertEquals(MigrationState.IMPORTED, vm.uiState.value.migration.report?.state)
        collector.cancel()
    }
}
