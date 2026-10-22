package space.einundzwanzig.meetup

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.AppStartupTask
import space.einundzwanzig.meetup.core.common.ApplicationScope
import space.einundzwanzig.meetup.core.common.IoDispatcher
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.data.migration.FlutterMigration
import space.einundzwanzig.meetup.startup.AppStartupRunner
import javax.inject.Inject

/**
 * Process start (spec 12 §2.1, spec 14 §4.7):
 * logger → crash handlers → Flutter data migration (IO, application scope) → once the migration
 * gate is complete, every [AppStartupTask] (diagnostics fingerprint, locale sync, currency load,
 * badge load, badge sync, humanity re-verification, reputation publish, …).
 *
 * Implements [Configuration.Provider] so WorkManager creates the Hilt widget refresh worker.
 */
@HiltAndroidApp
class EinundzwanzigApp : Application(), Configuration.Provider {

    @Inject lateinit var flutterMigration: FlutterMigration

    @Inject lateinit var startupTasks: Set<@JvmSuppressWildcards AppStartupTask>

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject @ApplicationScope
    lateinit var appScope: CoroutineScope

    @Inject @IoDispatcher
    lateinit var io: CoroutineDispatcher

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        // Attach the log store before anything can fail, so a crash during start-up is saved.
        AppLogger.attach(this)
        AppLogger.installCrashHandler()
        super.onCreate() // Hilt injects here.

        // appScope carries AppLogger.coroutineExceptionHandler (core:common DispatchersModule).
        appScope.launch(io) { AppLogger.init(this@EinundzwanzigApp) }
        appScope.launch(io) { flutterMigration.ensureMigrated() }
        AppStartupRunner(startupTasks, AppLogger).launchIn(appScope) {
            flutterMigration.gate.await()
        }
    }
}
