package space.einundzwanzig.meetup.feature.onboarding.intro

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarDurations
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.settings.locale.LocaleController
import space.einundzwanzig.meetup.feature.backup.BackupLauncher
import space.einundzwanzig.meetup.feature.onboarding.common.SnackbarMessage
import space.einundzwanzig.meetup.feature.onboarding.common.UiText

/** State of the intro screen (spec 01 §2.2). */
@Immutable
data class IntroUiState(
    /** "COMMUNITY BETRETEN" is running (profile load / waiting for the setup to return). */
    val joining: Boolean = false,
    /** A backup export or import is running (disables "BACKUP LADEN"). */
    val backupBusy: Boolean = false,
    /** Stored `app_locale` (`null` = system). */
    val locale: String? = null,
    val showLanguageDialog: Boolean = false,
)

/** User input on the intro screen. */
sealed interface IntroAction {
    data object Join : IntroAction
    data object RestoreBackup : IntroAction
    data object OpenLanguage : IntroAction
    data object DismissLanguage : IntroAction
    data class SelectLanguage(val code: String?) : IntroAction

    /** The screen is visible again (e.g. the identity setup was closed). */
    data object Resumed : IntroAction
}

/** One-shot navigation of the intro screen. */
sealed interface IntroEffect {
    data object OpenIdentitySetup : IntroEffect
    data object GoHome : IntroEffect
}

@HiltViewModel
class IntroViewModel @Inject constructor(
    private val profiles: UserProfileRepository,
    private val backup: BackupLauncher,
    private val localeController: LocaleController,
) : ViewModel() {

    private val local = MutableStateFlow(IntroUiState())

    val state: StateFlow<IntroUiState> = combine(local, backup.busy, localeController.appLocale) { s, busy, locale ->
        s.copy(backupBusy = busy, locale = locale?.takeIf { it.isNotEmpty() })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IntroUiState())

    private val _effects = Channel<IntroEffect>(Channel.BUFFERED)
    val effects: Flow<IntroEffect> = _effects.receiveAsFlow()

    private val _snackbars = Channel<SnackbarMessage>(Channel.BUFFERED)
    val snackbars: Flow<SnackbarMessage> = _snackbars.receiveAsFlow()

    /** Set while the identity setup that "COMMUNITY BETRETEN" opened is on top of the intro. */
    private var awaitingSetup = false

    fun onAction(action: IntroAction) {
        when (action) {
            IntroAction.Join -> join()
            IntroAction.RestoreBackup -> restore()
            IntroAction.OpenLanguage -> local.update { it.copy(showLanguageDialog = true) }
            IntroAction.DismissLanguage -> local.update { it.copy(showLanguageDialog = false) }
            is IntroAction.SelectLanguage -> {
                local.update { it.copy(showLanguageDialog = false) }
                viewModelScope.launch { localeController.setLocale(action.code) }
            }
            IntroAction.Resumed -> onResumed()
        }
    }

    /** Dart `_enterCommunity`. */
    private fun join() {
        if (local.value.joining) return
        local.update { it.copy(joining = true) }
        viewModelScope.launch {
            val onboarded = loadOnboarded() ?: run {
                local.update { it.copy(joining = false) }
                return@launch
            }
            if (onboarded) {
                _effects.send(IntroEffect.GoHome)
            } else {
                _snackbars.send(SnackbarMessage(UiText.res(L10nR.string.introSetIdentity), Tone.Warning, AppSnackbarDurations.Medium))
                awaitingSetup = true
                _effects.send(IntroEffect.OpenIdentitySetup)
            }
        }
    }

    /** After the pushed setup returned (it was closed): reload, go home if onboarded, else stay. */
    private fun onResumed() {
        if (!awaitingSetup) return
        awaitingSetup = false
        viewModelScope.launch {
            if (loadOnboarded() == true) {
                _effects.send(IntroEffect.GoHome)
            } else {
                local.update { it.copy(joining = false) }
            }
        }
    }

    /** Dart `_restoreAccount`: restore → app shell on success. */
    private fun restore() {
        if (local.value.joining || backup.busy.value) return
        viewModelScope.launch {
            if (backup.restore()) _effects.send(IntroEffect.GoHome)
        }
    }

    private suspend fun loadOnboarded(): Boolean? = try {
        profiles.load().isOnboarded
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLogger.error(TAG, "Profil nicht lesbar", e)
        _snackbars.send(
            SnackbarMessage(UiText.res(L10nR.string.errorGeneric, e.message ?: e.toString()), Tone.Error, AppSnackbarDurations.VeryLong),
        )
        null
    }

    private companion object {
        const val TAG = "Intro"
    }
}
