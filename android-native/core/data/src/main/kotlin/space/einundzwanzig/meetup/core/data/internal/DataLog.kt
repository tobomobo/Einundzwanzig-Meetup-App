package space.einundzwanzig.meetup.core.data.internal

import space.einundzwanzig.meetup.core.common.log.AppLogger

/**
 * Logging seam of `:core:data`: everything goes to the in-app diagnostic log ([AppLogger], with
 * secret redaction and a logcat echo in debug builds), so storage failures (corrupt files,
 * Keystore errors, unreadable secrets) appear in the exportable "Diagnose-Log".
 *
 * Messages must never contain stored values (keys, nsec, seeds, tokens); key *names* are fine.
 * For exceptions whose message may quote stored input (e.g. `SerializationException`), log only
 * the exception type, not the exception.
 */
internal object DataLog {
    fun w(tag: String, message: String, error: Throwable? = null) = AppLogger.warn(tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) = AppLogger.error(tag, message, error)
}
