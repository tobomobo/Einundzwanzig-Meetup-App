package space.einundzwanzig.meetup.data.backup.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import space.einundzwanzig.meetup.data.backup.BackupSession
import javax.inject.Inject
import javax.inject.Singleton

/** [BackupSession] on a non-reentrant [Mutex.tryLock]. */
@Singleton
internal class DefaultBackupSession @Inject constructor() : BackupSession {
    private val mutex = Mutex()
    private val state = MutableStateFlow(false)

    override val busy: StateFlow<Boolean> = state.asStateFlow()

    override suspend fun <T : Any> tryRun(block: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        state.value = true
        try {
            return block()
        } finally {
            state.value = false
            mutex.unlock()
        }
    }
}
