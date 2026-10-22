package space.einundzwanzig.meetup.feature.settings.sheet

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.ui.guide.GuideController
import space.einundzwanzig.meetup.core.ui.guide.GuideTour
import space.einundzwanzig.meetup.core.ui.theme.ThemeMode
import space.einundzwanzig.meetup.data.mempool.currency.CurrencyRepository
import space.einundzwanzig.meetup.data.settings.SettingsRepository
import space.einundzwanzig.meetup.data.settings.locale.LocaleController
import space.einundzwanzig.meetup.feature.settings.AppVersionSource
import javax.inject.Inject

/** What the settings sheet shows (spec 12 §2.6). */
@Immutable
data class SettingsSheetUiState(
    /** Stored language code, `null` = system. */
    val locale: String? = null,
    /** Display currency code (`EUR` default). */
    val currency: String = "EUR",
    val hapticEnabled: Boolean = true,
    /** Native-only appearance choice (`native_theme_mode`). */
    val themeMode: ThemeMode = ThemeMode.DARK,
    /** `versionName`; empty hides the footer text. */
    val appVersion: String = "",
)

/** One-shot effects for the sheet host. */
sealed interface SettingsSheetEvent {
    /** All tours were reset: show `settingsGuideReset` (orange). */
    data object ToursReset : SettingsSheetEvent
}

/**
 * State of the settings sheet. Scoped to the hosting destination (Home), so it outlives the
 * sheet: the "Tour wiederholen" snackbar arrives after the sheet has closed, as in Flutter.
 */
@HiltViewModel
class SettingsSheetViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val localeController: LocaleController,
    private val currency: CurrencyRepository,
    private val guide: GuideController,
    appVersion: AppVersionSource,
) : ViewModel() {

    private val version = MutableStateFlow(appVersion.versionName())
    private val events = Channel<SettingsSheetEvent>(Channel.BUFFERED)

    /** One-shot events ([SettingsSheetEvent]). */
    val uiEvents: Flow<SettingsSheetEvent> = events.receiveAsFlow()

    /**
     * The stored language, read eagerly from creation on: the ViewModel lives with the host
     * (Home), so the first DataStore read is done long before the sheet opens and the language
     * row never flashes "System" for a chosen language.
     */
    private val locale: StateFlow<String?> =
        localeController.appLocale.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val uiState: StateFlow<SettingsSheetUiState> = combine(
        locale,
        currency.current,
        settings.hapticEnabled,
        settings.themeMode,
        version,
    ) { locale, cur, haptic, theme, v ->
        SettingsSheetUiState(locale, cur, haptic, theme, v)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SettingsSheetUiState(
            locale = locale.value,
            currency = currency.current.value,
            hapticEnabled = settings.hapticEnabled.value,
            themeMode = settings.themeMode.value,
            appVersion = version.value,
        ),
    )

    /** Language dialog choice; `null` = system. Applies immediately (per-app language). */
    fun setLocale(code: String?) {
        viewModelScope.launch {
            if (!localeController.setLocale(code)) AppLogger.warn("Settings", "Sprache konnte nicht gespeichert werden")
        }
    }

    /** Currency dialog choice (`CurrencyService.set`). */
    fun setCurrency(code: String) {
        viewModelScope.launch { currency.set(code) }
    }

    /** Haptics switch (`haptic_enabled`); the shell's `LocalHapticsEnabled` follows the flow. */
    fun setHapticEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (!settings.setHapticEnabled(enabled)) AppLogger.warn("Settings", "Haptik-Einstellung nicht gespeichert")
        }
    }

    /** Native appearance toggle. */
    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            if (!settings.setThemeMode(mode)) AppLogger.warn("Settings", "Erscheinungsbild nicht gespeichert")
        }
    }

    /**
     * "Tour wiederholen": reset every tour, then report [SettingsSheetEvent.ToursReset].
     *
     * The row can be tapped during the settings tour (its last step spotlights it). That tour is
     * finished first (persisted, then cleared), so the sheet's later `finishIfActive` is a no-op
     * and no "settings tour completed" write can land after the reset (Flutter's `whenComplete`
     * updates its prefs cache before `resetAllTours` runs).
     */
    fun restartTours() {
        viewModelScope.launch {
            if (guide.activeTour == GuideTour.Settings) guide.finishTour()
            guide.resetAllTours()
            events.send(SettingsSheetEvent.ToursReset)
        }
    }
}
