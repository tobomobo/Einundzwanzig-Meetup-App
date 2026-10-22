package space.einundzwanzig.meetup.data.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import space.einundzwanzig.meetup.core.ui.theme.ThemeMode

/**
 * Typed view on the app settings in the shared `KeyValueStore` (spec 12 §3.6, §4). Opens no
 * storage of its own; all values are read and written under the keys in [SettingsKeys].
 *
 * Every flow reflects the store, so writes from anywhere (including an app reset that clears the
 * store) show up immediately — there is no stale cache like Dart's `HapticService._cached`
 * (spec 12 §14.6).
 *
 * Write functions return `true` once the value is durably stored (Dart `Future<bool> setX`).
 *
 * Language changes should go through [space.einundzwanzig.meetup.data.settings.locale.LocaleController],
 * which also applies them to the platform; [setAppLocale] only stores the value.
 *
 * The display currency (`fiat_currency`) belongs to `:data:mempool` (spec 13), tile layout
 * (`tile_order`, `tile_hidden`, `mig_*`) to `:feature:home`.
 */
interface SettingsRepository {

    /** Stored language code (`de`/`en`/`es`), or `null` to follow the system. Empty counts as `null`. */
    val appLocale: Flow<String?>

    /** Current value of [appLocale]. */
    suspend fun getAppLocale(): String?

    /** Stores [code]; `null` or empty removes the key (= system language, Dart `prefs.remove`). */
    suspend fun setAppLocale(code: String?): Boolean

    /**
     * Haptics on/off (`haptic_enabled`, default `true`). Hot and app-scoped so synchronous callers
     * such as `core.ui.haptics.Haptics(view) { hapticEnabled.value }` can read it per tap.
     * Until the first store read completes `value` is the default (`true`); use
     * [isHapticEnabled] when the stored value is needed for sure.
     */
    val hapticEnabled: StateFlow<Boolean>

    /** Reads `haptic_enabled` from the store (default `true`). */
    suspend fun isHapticEnabled(): Boolean

    /** Stores `haptic_enabled`. */
    suspend fun setHapticEnabled(enabled: Boolean): Boolean

    /**
     * Native-only appearance setting, default [ThemeMode.DARK] (the Flutter app was dark-only).
     * Hot and app-scoped, but until the first store read completes `value` is the default
     * ([ThemeMode.DARK]) even if the user chose another mode. The app shell must therefore keep
     * the splash screen up until `themeMode.first { it == getThemeMode() }` has returned
     * (afterwards `value` is current), otherwise the first frame can render dark and then flip.
     */
    val themeMode: StateFlow<ThemeMode>

    /** Reads the stored theme mode (unknown or absent → [ThemeMode.DARK]). */
    suspend fun getThemeMode(): ThemeMode

    /** Stores the theme mode. */
    suspend fun setThemeMode(mode: ThemeMode): Boolean

    /** Unix seconds of the last tap on the Home Nostr tile (`nostr_last_seen`, default 0). */
    suspend fun getNostrLastSeen(): Long

    /** Stores `nostr_last_seen` in unix **seconds** (not milliseconds, spec 12 §14.23). */
    suspend fun setNostrLastSeen(epochSeconds: Long): Boolean

    companion object {
        /** Default of `haptic_enabled`. */
        const val DEFAULT_HAPTIC_ENABLED: Boolean = true

        /** Default appearance. */
        val DEFAULT_THEME_MODE: ThemeMode = ThemeMode.DARK

        /** Stored string of [mode] (`dark`/`light`/`system`). */
        fun themeModeToPref(mode: ThemeMode): String = when (mode) {
            ThemeMode.DARK -> "dark"
            ThemeMode.LIGHT -> "light"
            ThemeMode.SYSTEM -> "system"
        }

        /** Parses a stored theme mode; anything unknown → [DEFAULT_THEME_MODE]. */
        fun themeModeFromPref(value: String?): ThemeMode = when (value) {
            "light" -> ThemeMode.LIGHT
            "system" -> ThemeMode.SYSTEM
            else -> DEFAULT_THEME_MODE
        }
    }
}
