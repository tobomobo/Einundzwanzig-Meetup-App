package space.einundzwanzig.meetup.data.settings

/**
 * Preference keys owned by the settings domain (spec 12 §4), in the shared `KeyValueStore`.
 *
 * Flutter keys keep their exact Dart names (the migration copies them without the `flutter.`
 * prefix). Keys that exist only in the native app carry a `native_` prefix so they can never
 * collide with a Flutter key.
 */
object SettingsKeys {
    /** Language code `de`/`en`/`es`; absent = follow the system (Dart `LocaleController`). */
    const val APP_LOCALE: String = "app_locale"

    /** Haptic feedback on/off, default `true` (Dart `HapticService`, settings sheet switch). */
    const val HAPTIC_ENABLED: String = "haptic_enabled"

    /** Unix **seconds** of the last tap on the Home Nostr tile, default 0 (Dart `home_screen.dart`). */
    const val NOSTR_LAST_SEEN: String = "nostr_last_seen"

    /** Prefix of the per-tour completion flags (`guide_completed_<GuideTour.persistName>`). */
    const val GUIDE_COMPLETED_PREFIX: String = "guide_completed_"

    /** "First-run tour question was asked" flag. */
    const val GUIDE_ONBOARDING_ASKED: String = "guide_onboarding_asked"

    /** Native only: appearance `dark` | `light` | `system`; absent = `dark` (brand default). */
    const val NATIVE_THEME_MODE: String = "native_theme_mode"

    /**
     * Native only (API 33+): the per-app language tag last handed to `LocaleManager` (`""` = system).
     * Lets the app tell a change made in the Android system settings apart from its own writes.
     */
    const val NATIVE_APPLIED_LOCALE: String = "native_applied_locale"
}
