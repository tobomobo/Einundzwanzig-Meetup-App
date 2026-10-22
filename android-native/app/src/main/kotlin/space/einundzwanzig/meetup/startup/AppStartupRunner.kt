package space.einundzwanzig.meetup.startup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.AppStartupTask
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog

/**
 * Runs every contributed [AppStartupTask] once per process (spec 12 §2.1 steps 4–9).
 *
 * The Flutter data migration must have completed first (`:data:settings` and `:data:badgesync`
 * tasks read migrated keys), so the caller passes [awaitMigration]. Tasks are grouped by
 * [AppStartupTask.order]; groups run one after another (lowest first), tasks of a group run
 * concurrently. A failing task is logged and never stops the others.
 */
class AppStartupRunner(
    private val tasks: Set<AppStartupTask>,
    private val log: DiagnosticLog,
) {
    /** Launches the run in [scope] and returns its job. */
    fun launchIn(scope: CoroutineScope, awaitMigration: suspend () -> Unit): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) { run(awaitMigration) }

    /** Runs all groups; returns the names of the tasks that failed. */
    suspend fun run(awaitMigration: suspend () -> Unit): List<String> {
        try {
            awaitMigration()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The gate never throws by contract; if it does, still start the app's work.
            log.error(TAG, "Warten auf die Datenübernahme fehlgeschlagen", e)
        }
        val failed = mutableListOf<String>()
        tasks.groupBy { it.order }.toSortedMap().forEach { (_, group) ->
            coroutineScope {
                group.sortedBy { it.name }.forEach { task ->
                    launch {
                        try {
                            task.run()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            synchronized(failed) { failed += task.name }
                            log.error(TAG, "Start-Aufgabe ${task.name} fehlgeschlagen", e)
                        }
                    }
                }
            }
        }
        log.debug(TAG, "Start-Aufgaben fertig (${tasks.size}, Fehler: ${failed.size})")
        return failed
    }

    private companion object {
        const val TAG = "Startup"
    }
}
