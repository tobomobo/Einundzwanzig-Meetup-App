package space.einundzwanzig.meetup.data.backup

import kotlinx.coroutines.flow.StateFlow

/**
 * The one busy guard shared by export and import (Dart `BackupService.busy`, spec 08 §9 #13 and
 * flow step 1 of §2.4/§2.5). Every caller (home settings, home reset, intro, identity setup) runs
 * its whole flow inside [tryRun]: export from the overlay to the written file, import from the file
 * read through password, decryption and [RestoreEngine.restore]. A request while another one runs
 * is ignored silently (spec 08 §1 #23).
 *
 * Inject it (app-wide singleton); it lives here because features may not depend on each other.
 */
interface BackupSession {

    /** `true` while a flow runs inside [tryRun] (e.g. to disable the backup buttons). */
    val busy: StateFlow<Boolean>

    /**
     * Runs [block] unless another backup flow is running.
     *
     * @return the result of [block], or `null` if busy (then [block] did not run). Exceptions and
     *   cancellation of [block] propagate; the guard is released on every exit.
     */
    suspend fun <T : Any> tryRun(block: suspend () -> T): T?
}
