package space.einundzwanzig.meetup.core.common

/**
 * Work that runs once per process start, after the first frame is requested.
 * Contribute implementations with Hilt:
 *
 * ```
 * @Binds @IntoSet abstract fun badgeSync(task: BadgeSyncStartupTask): AppStartupTask
 * ```
 *
 * Tasks are grouped by [order]; groups run one after another (lowest first), tasks inside a
 * group run concurrently in the application scope. A failing task is logged and does not
 * affect the others. The Flutter data migration is NOT a startup task: the app awaits it
 * behind the splash screen before any repository is read.
 */
interface AppStartupTask {
    val name: String
    val order: Int get() = 100
    suspend fun run()
}
