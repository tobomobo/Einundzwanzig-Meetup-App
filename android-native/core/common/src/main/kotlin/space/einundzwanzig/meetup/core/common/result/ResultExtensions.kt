package space.einundzwanzig.meetup.core.common.result

import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.describeError
import kotlin.coroutines.cancellation.CancellationException

/**
 * `runCatching` for coroutine code: like [runCatching], but a [CancellationException] is
 * rethrown instead of being captured, so cancellation keeps working. Use it in every `suspend`
 * function that returns [Result].
 *
 * Only [Exception]s are captured; JVM `Error`s (OOM, …) propagate.
 */
inline fun <T> runSuspendCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

/**
 * Logs a failure as `WARN [tag] message — Type: details` and returns the result unchanged —
 * the native replacement for Dart's `try { … } catch (e) { AppLogger.warn(tag, msg, e); }`.
 */
fun <T> Result<T>.logFailure(tag: String, message: String, log: DiagnosticLog = AppLogger): Result<T> {
    exceptionOrNull()?.let { log.warn(tag, message, it) }
    return this
}

/** Like [logFailure] but on `debug` (kept only in verbose mode) — for expected, noisy failures. */
fun <T> Result<T>.logFailureDebug(tag: String, message: String, log: DiagnosticLog = AppLogger): Result<T> {
    exceptionOrNull()?.let { log.debug(tag, "$message — ${describeError(it)}") }
    return this
}
