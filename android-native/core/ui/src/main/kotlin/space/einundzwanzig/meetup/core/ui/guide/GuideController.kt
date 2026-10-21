package space.einundzwanzig.meetup.core.ui.guide

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Snapshot of the running tour (Dart `GuideService` state, spec 12 §3.4). */
@Immutable
data class GuideState(
    val activeTour: GuideTour? = null,
    val steps: List<GuideStep> = emptyList(),
    val currentStepIndex: Int = 0,
    val isActive: Boolean = false,
    /** Tour keeps running but is not drawn (e.g. during a navigation). */
    val isPaused: Boolean = false,
    /** Increments with every start; distinguishes two runs of the same tour. */
    val run: Long = 0,
) {
    /** Active and not paused. */
    val isVisible: Boolean get() = isActive && !isPaused

    /** The current step, or null when nothing runs. */
    val currentStep: GuideStep? get() = steps.getOrNull(currentStepIndex)

    /** Number of steps, skipped ones included ("Schritt X von N"). */
    val totalSteps: Int get() = steps.size

    /** The back button is hidden on the first step. */
    val isFirstStep: Boolean get() = currentStepIndex == 0

    /** "Weiter" finishes the tour on the last step. */
    val isLastStep: Boolean get() = steps.isNotEmpty() && currentStepIndex == steps.size - 1

    /** May "Weiter" be pressed? */
    val canAdvance: Boolean get() = currentStep?.isComplete ?: false
}

/**
 * Spotlight tour state machine and persistence (port of `lib/services/guide_service.dart`).
 * One app-wide instance (the app provides it via DI and [LocalGuideController]); call it from
 * the main thread.
 *
 * @param store persisted "tour completed" flags (data module).
 * @param scope long-lived scope for background persistence ([finishTourNow]), typically the
 *   application scope.
 */
class GuideController(
    private val store: TourProgressStore,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(GuideState())
    private var runCounter = 0L

    /** Observable tour state. */
    val state: StateFlow<GuideState> = mutableState.asStateFlow()

    /** Current snapshot of [state]. */
    val current: GuideState get() = mutableState.value

    /** A tour is running (possibly paused). */
    val isActive: Boolean get() = current.isActive

    /** The running tour, or null. */
    val activeTour: GuideTour? get() = current.activeTour

    /** The current step of the running tour, or null. */
    val currentStep: GuideStep? get() = current.currentStep

    /** Was [tour] completed (or declined) before? */
    suspend fun wasTourCompleted(tour: GuideTour): Boolean = store.isCompleted(tour)

    /** True until the first-run question ("Tour starten?") was answered. */
    suspend fun shouldAskForOnboarding(): Boolean = !store.isOnboardingAsked()

    /** Remembers that the first-run question was answered. */
    suspend fun markOnboardingAsked() = store.setOnboardingAsked(true)

    /**
     * "Nein, danke" on the first-run question switches off **all** tours: every tour is marked
     * completed and the question is not asked again. "Restart tour" in the settings undoes
     * exactly these marks ([resetAllTours]).
     */
    suspend fun declineAllTours() {
        GuideTour.entries.forEach { store.setCompleted(it, true) }
        store.setOnboardingAsked(true)
    }

    /** Forgets that [tour] was completed. */
    suspend fun resetTour(tour: GuideTour) = store.setCompleted(tour, false)

    /** Forgets all tours and the first-run question. */
    suspend fun resetAllTours() {
        GuideTour.entries.forEach { store.setCompleted(it, false) }
        store.setOnboardingAsked(false)
    }

    /**
     * Starts [tour] at its first step. No-op for an empty step list, and for a completed tour
     * unless [force] (restart from the settings).
     */
    suspend fun startTour(tour: GuideTour, steps: List<GuideStep>, force: Boolean = false) {
        if (steps.isEmpty()) return
        if (!force && wasTourCompleted(tour)) return
        mutableState.value = GuideState(
            activeTour = tour,
            steps = steps.toList(),
            currentStepIndex = 0,
            isActive = true,
            isPaused = false,
            run = ++runCounter,
        )
    }

    /** Next step; on the last step the tour finishes (and is persisted as completed). */
    fun nextStep() {
        val s = current
        if (!s.isActive) return
        if (s.currentStepIndex < s.steps.size - 1) {
            mutableState.value = s.copy(currentStepIndex = s.currentStepIndex + 1, isPaused = false)
        } else {
            scope.launch { finishTour() }
        }
    }

    /** Previous step (no-op on the first). */
    fun previousStep() {
        val s = current
        if (!s.isActive || s.currentStepIndex == 0) return
        mutableState.value = s.copy(currentStepIndex = s.currentStepIndex - 1, isPaused = false)
    }

    /** Skips the current step without ending the tour (target permanently missing). */
    fun skipStep() = nextStep()

    /**
     * Skips only if step [index] of run [run] is still current; the overlay uses this so a
     * late or duplicate timeout can never skip a step the user already reached.
     */
    fun skipStepIfCurrent(run: Long, index: Int) {
        val s = current
        if (s.isActive && s.run == run && s.currentStepIndex == index) nextStep()
    }

    /** The user tapped the spotlighted target; only steps with `advanceOnTargetTap` react. */
    fun reportTargetTapped() {
        val step = currentStep ?: return
        if (!step.advanceOnTargetTap) return
        nextStep()
    }

    /** The step's condition is met; advances only for `autoAdvance` steps. */
    fun reportStepCompleted() {
        val step = currentStep ?: return
        if (!step.autoAdvance || !step.isComplete) return
        nextStep()
    }

    /** Keeps the tour but stops drawing it. */
    fun pause() {
        val s = current
        if (!s.isActive || s.isPaused) return
        mutableState.value = s.copy(isPaused = true)
    }

    /** Draws a paused tour again. */
    fun resume() {
        val s = current
        if (!s.isActive || !s.isPaused) return
        mutableState.value = s.copy(isPaused = false)
    }

    /** Ends the tour after persisting it as completed. */
    suspend fun finishTour() {
        val s = current
        val tour = s.activeTour
        if (tour != null) store.setCompleted(tour, true)
        // Do not clear a tour that was started while we were persisting.
        if (current.run == s.run) clear()
    }

    /**
     * Ends the tour from UI callbacks ("Tour beenden", "Überspringen"): persists, then clears,
     * in the controller's scope so it survives the overlay leaving the composition.
     */
    fun endTour() {
        scope.launch { finishTour() }
    }

    /**
     * Ends the tour synchronously (screen disposal); persistence runs in the background.
     */
    fun finishTourNow() {
        val tour = current.activeTour
        if (tour != null) scope.launch { store.setCompleted(tour, true) }
        clear()
    }

    /** [finishTourNow] if [tour] is the active tour (Dart `finishGuideTourIfActive`). */
    fun finishIfActive(tour: GuideTour) {
        if (current.activeTour == tour) finishTourNow()
    }

    private fun clear() {
        mutableState.value = GuideState(run = current.run)
    }
}
