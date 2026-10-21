package space.einundzwanzig.meetup.data.settings.locale

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * App language selection (port of Dart `LocaleController`, spec 12 §2.6, §3.6, §14.12).
 *
 * The single source of truth is the `app_locale` preference (language code, absent = system).
 * The controller applies it as the per-app language:
 *
 * - **API 33+**: through the platform `LocaleManager`. The system recreates running activities
 *   itself. A language chosen in the Android system settings ("App languages") is adopted into
 *   `app_locale` by [syncWithSystem] at the next start.
 * - **API 26–32**: there is no platform API (and the app uses `ComponentActivity`, so AppCompat's
 *   per-app language does not apply). The activity wraps its base context with [wrapContext] in
 *   `attachBaseContext` and calls `recreate()` when [appLocale] emits a code different from the
 *   one it was created with. See the module README for the snippet.
 *
 * The settings startup task applies every later change of `app_locale` (also an app reset that
 * clears the store) automatically, so callers only need [setLocale]. The app reset should still
 * call `setLocale(null)` after clearing the store: it applies synchronously, whereas the collector
 * could be killed with the process before it runs, leaving the old platform language to be
 * re-adopted as a system-settings choice on the next start.
 */
interface LocaleController {

    /** Stored language code, `null` = follow the system. Emits on every change. */
    val appLocale: Flow<String?>

    /** `true` on API 33+, where the platform applies the language and recreates activities. */
    val isManagedByPlatform: Boolean

    /** Current stored language code (`null` = system). */
    suspend fun current(): String?

    /**
     * Stores [code] (`null` = system, Dart `setLocale(null)` removes the key) and applies it to
     * the platform right away. Returns `false` if the preference could not be written.
     */
    suspend fun setLocale(code: String?): Boolean

    /**
     * API 33+: if the per-app language was changed outside the app (system settings), copies it
     * into `app_locale` (a language the app does not support is stored as "system"); otherwise
     * does nothing. Runs at startup; `MainActivity.onCreate` should call it as well, because a
     * change in the system settings recreates the activity without restarting the process.
     * No-op below API 33.
     */
    suspend fun syncWithSystem()

    /**
     * Stored language code read synchronously, for `attachBaseContext` (runs before DI and
     * coroutines are available). Blocks on the first DataStore read; never call it elsewhere.
     */
    fun currentBlocking(): String?

    /**
     * `AppLocales.wrapContext(base, currentBlocking())`; returns [base] on API 33+. Below API 33
     * it also sets `Locale.getDefault()`; code outside the activity that needs localized
     * resources (notifications, widgets) can wrap the application context with it too.
     */
    fun wrapContext(base: Context): Context
}
