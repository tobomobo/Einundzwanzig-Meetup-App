package space.einundzwanzig.meetup.core.ui.util

import space.einundzwanzig.meetup.core.common.log.AppLogger

/**
 * Logging seam for :core:ui (UI-level failures such as "no app can open this link").
 * Writes to the app's diagnostic log ([AppLogger], redacted). Never pass secrets.
 */
internal object UiLog {
    /** Logs a warning to the diagnostic log. */
    fun warn(tag: String, message: String, error: Throwable? = null) = AppLogger.warn(tag, message, error)
}
