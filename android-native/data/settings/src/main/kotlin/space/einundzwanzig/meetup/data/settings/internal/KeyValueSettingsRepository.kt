package space.einundzwanzig.meetup.data.settings.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import space.einundzwanzig.meetup.core.common.ApplicationScope
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.ui.theme.ThemeMode
import space.einundzwanzig.meetup.data.settings.SettingsKeys
import space.einundzwanzig.meetup.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/** [SettingsRepository] on the shared [KeyValueStore]. */
@Singleton
internal class KeyValueSettingsRepository @Inject constructor(
    private val store: KeyValueStore,
    @param:ApplicationScope private val scope: CoroutineScope,
) : SettingsRepository {

    override val appLocale: Flow<String?> =
        store.observeString(SettingsKeys.APP_LOCALE).map { it.normalizedLocale() }.distinctUntilChanged()

    override suspend fun getAppLocale(): String? = store.getString(SettingsKeys.APP_LOCALE).normalizedLocale()

    override suspend fun setAppLocale(code: String?): Boolean {
        val ok = if (code.isNullOrEmpty()) {
            store.remove(SettingsKeys.APP_LOCALE)
        } else {
            store.setString(SettingsKeys.APP_LOCALE, code)
        }
        if (!ok) AppLogger.warn(TAG, "Sprache konnte nicht gespeichert werden")
        return ok
    }

    override val hapticEnabled: StateFlow<Boolean> =
        store.observeBool(SettingsKeys.HAPTIC_ENABLED)
            .map { it ?: SettingsRepository.DEFAULT_HAPTIC_ENABLED }
            .stateIn(scope, SharingStarted.Eagerly, SettingsRepository.DEFAULT_HAPTIC_ENABLED)

    override suspend fun isHapticEnabled(): Boolean =
        store.getBool(SettingsKeys.HAPTIC_ENABLED) ?: SettingsRepository.DEFAULT_HAPTIC_ENABLED

    override suspend fun setHapticEnabled(enabled: Boolean): Boolean =
        store.setBool(SettingsKeys.HAPTIC_ENABLED, enabled).also { ok ->
            if (!ok) AppLogger.warn(TAG, "Haptik-Einstellung konnte nicht gespeichert werden")
        }

    override val themeMode: StateFlow<ThemeMode> =
        store.observeString(SettingsKeys.NATIVE_THEME_MODE)
            .map(SettingsRepository::themeModeFromPref)
            .stateIn(scope, SharingStarted.Eagerly, SettingsRepository.DEFAULT_THEME_MODE)

    override suspend fun getThemeMode(): ThemeMode =
        SettingsRepository.themeModeFromPref(store.getString(SettingsKeys.NATIVE_THEME_MODE))

    override suspend fun setThemeMode(mode: ThemeMode): Boolean =
        store.setString(SettingsKeys.NATIVE_THEME_MODE, SettingsRepository.themeModeToPref(mode)).also { ok ->
            if (!ok) AppLogger.warn(TAG, "Darstellung konnte nicht gespeichert werden")
        }

    override suspend fun getNostrLastSeen(): Long = store.getInt(SettingsKeys.NOSTR_LAST_SEEN) ?: 0L

    override suspend fun setNostrLastSeen(epochSeconds: Long): Boolean =
        store.setInt(SettingsKeys.NOSTR_LAST_SEEN, epochSeconds)

    private companion object {
        const val TAG = "Settings"
    }
}

/** Dart `LocaleController.load`: an empty stored code means "system". */
internal fun String?.normalizedLocale(): String? = this?.takeIf { it.isNotEmpty() }
