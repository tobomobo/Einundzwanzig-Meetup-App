package space.einundzwanzig.meetup.feature.settings.log

import space.einundzwanzig.meetup.data.migration.*

internal class FakeMigration : FlutterMigration {
    var report = MigrationReport(MigrationState.NOT_NEEDED, 0, emptySet(), emptyMap(), emptyList(), emptyList(), emptyList(), null, null)
    var retries = 0
    var abandoned = 0
    var retry: (suspend () -> MigrationReport)? = null
    override val gate = object : MigrationGate {
        override val isComplete = true
        override suspend fun await() = Unit
    }
    override suspend fun currentReport() = report
    override suspend fun ensureMigrated() = report
    override suspend fun retryNow(): MigrationReport { retries++; return retry?.invoke() ?: report }
    override suspend fun abandonPendingSecrets(): MigrationReport { abandoned++; report = report.copy(state = MigrationState.IMPORTED); return report }
    override suspend fun pendingLegacySecrets() = report.failedKeys.toSet()
    override suspend fun cleanupIfEligible() = CleanupOutcome.NothingToDo
    override suspend fun onAppReset() = Unit
}
