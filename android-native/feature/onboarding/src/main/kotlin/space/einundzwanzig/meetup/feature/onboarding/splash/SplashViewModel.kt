package space.einundzwanzig.meetup.feature.onboarding.splash

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.data.identity.keys.SecureKeyStore
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.migration.MigrationGate

/** Where the splash sends the user (spec 01 §2.1, spec 12 §2.3). */
enum class SplashDestination { INTRO, HOME }

/** State of the splash / session check. */
@Immutable
sealed interface SplashUiState {
    /** Migration, key-store check and profile load are running (plus the 800 ms minimum). */
    data object Checking : SplashUiState

    /** The routing decision. */
    data class Ready(val destination: SplashDestination) : SplashUiState

    /**
     * The secure store could not be read. Never route to onboarding then: that would create a new
     * identity over the unreadable one (`:data:identity` README, spec 01 §9).
     */
    data object StorageUnavailable : SplashUiState
}

/** Pure routing rule: `isOnboarded = hasCustomNickname || isNostrVerified || isAdminVerified`. */
object SplashRouting {
    fun destinationFor(profile: UserProfile): SplashDestination =
        if (profile.isOnboarded) SplashDestination.HOME else SplashDestination.INTRO
}

/**
 * Session check of the splash (Dart `SplashScreen._checkSession`): wait for the Flutter storage
 * import (bounded, spec 14 §4), `SecureKeyStore.ensureMigrated()`, load the profile, hold for
 * [MIN_SPLASH_MILLIS], then decide Intro vs. app shell.
 *
 * Exactly one instance may exist per start: `:app` may keep the system splash on screen while
 * [isChecking] is `true`, but then it must hand that same instance to
 * `onboardingEntries(splashViewModel = …)` (see there).
 */
@HiltViewModel
class SplashViewModel @Inject constructor(
    private val migrationGate: MigrationGate,
    private val secureKeyStore: SecureKeyStore,
    private val profiles: UserProfileRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<SplashUiState>(SplashUiState.Checking)
    val state: StateFlow<SplashUiState> = _state.asStateFlow()

    /** `true` while the session check runs (for `installSplashScreen().setKeepOnScreenCondition`). */
    val isChecking: Boolean get() = _state.value is SplashUiState.Checking

    private var job: Job? = null

    init {
        check()
    }

    /** "Erneut versuchen" after [SplashUiState.StorageUnavailable]. */
    fun retry() {
        if (_state.value is SplashUiState.Checking) return
        check()
    }

    private fun check() {
        job?.cancel()
        _state.value = SplashUiState.Checking
        job = viewModelScope.launch {
            // A hanging keystore binder call must not keep the splash forever; pending secrets stay
            // protected by the data layer's unreadable mapping (:data:migration README).
            if (withTimeoutOrNull(MIGRATION_TIMEOUT) { migrationGate.await() } == null) {
                AppLogger.warn(TAG, "Datenübernahme nicht rechtzeitig fertig, Start geht weiter")
            }
            val destination = try {
                secureKeyStore.ensureMigrated()
                SplashRouting.destinationFor(profiles.load())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.error(TAG, "Sitzungsprüfung fehlgeschlagen", e)
                null
            }
            delay(MIN_SPLASH_MILLIS)
            _state.value = if (destination == null) SplashUiState.StorageUnavailable else SplashUiState.Ready(destination)
        }
    }

    companion object {
        /** Dart `Future.delayed(800 ms)` after the profile load. */
        const val MIN_SPLASH_MILLIS: Long = 800
        val MIGRATION_TIMEOUT = 20.seconds
        private const val TAG = "Splash"
    }
}
