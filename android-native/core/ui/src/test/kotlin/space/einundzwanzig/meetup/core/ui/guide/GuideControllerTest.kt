package space.einundzwanzig.meetup.core.ui.guide

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of `test/guide_service_host_test.dart` (service part) plus the guide vectors of
 * spec 12 §10 item 10.
 */
class GuideControllerTest {

    private val demoStep = GuideStep(title = 1, body = 2)
    private val store = InMemoryTourProgressStore()

    private fun TestScope.controller() = GuideController(store, this)

    // ---- guide_service_host_test.dart: GuideService.finishTourNow ----

    @Test
    fun `finishTourNow resets the active tour synchronously`() = runTest {
        val guide = controller()
        guide.startTour(GuideTour.Portal, listOf(demoStep), force = true)
        assertTrue(guide.isActive)
        assertEquals(GuideTour.Portal, guide.activeTour)

        guide.finishTourNow()

        assertFalse(guide.isActive)
        assertNull(guide.activeTour)
        assertNull(guide.currentStep)
    }

    @Test
    fun `finishTourNow persists the tour as completed`() = runTest {
        val guide = controller()
        guide.startTour(GuideTour.Wallet, listOf(demoStep), force = true)

        guide.finishTourNow()
        advanceUntilIdle()

        assertTrue(guide.wasTourCompleted(GuideTour.Wallet))
        assertEquals(true, store.values["guide_completed_wallet"])
    }

    @Test
    fun `finishIfActive ignores other tours`() = runTest {
        val guide = controller()
        guide.startTour(GuideTour.Events, listOf(demoStep), force = true)

        guide.finishIfActive(GuideTour.Portal)

        assertTrue(guide.isActive)
        assertEquals(GuideTour.Events, guide.activeTour)
        guide.finishIfActive(GuideTour.Events)
        assertFalse(guide.isActive)
    }

    // ---- spec 12 §10 item 10 ----

    @Test
    fun `startTour is a no-op for completed tours unless forced`() = runTest {
        val guide = controller()
        store.setCompleted(GuideTour.Home, true)

        guide.startTour(GuideTour.Home, listOf(demoStep))
        assertFalse(guide.isActive)

        guide.startTour(GuideTour.Home, listOf(demoStep), force = true)
        assertTrue(guide.isActive)
        assertEquals(0, guide.current.currentStepIndex)
    }

    @Test
    fun `startTour with no steps does nothing`() = runTest {
        val guide = controller()
        guide.startTour(GuideTour.Home, emptyList(), force = true)
        assertFalse(guide.isActive)
    }

    @Test
    fun `nextStep on the last step finishes and persists`() = runTest {
        val guide = controller()
        guide.startTour(GuideTour.MyMeetups, listOf(demoStep, demoStep))
        guide.nextStep()
        assertEquals(1, guide.current.currentStepIndex)
        assertTrue(guide.current.isLastStep)

        guide.nextStep()
        advanceUntilIdle()

        assertFalse(guide.isActive)
        assertEquals(true, store.values["guide_completed_myMeetups"])
    }

    @Test
    fun `declineAllTours writes all ten tour keys and onboarding asked`() = runTest {
        val guide = controller()
        assertTrue(guide.shouldAskForOnboarding())

        guide.declineAllTours()

        assertEquals(
            setOf(
                "guide_completed_onboarding", "guide_completed_home", "guide_completed_settings",
                "guide_completed_events", "guide_completed_portal", "guide_completed_wallet",
                "guide_completed_reputation", "guide_completed_scan", "guide_completed_myMeetups",
                "guide_completed_portalArea", "guide_onboarding_asked",
            ),
            store.values.filterValues { it }.keys,
        )
        assertFalse(guide.shouldAskForOnboarding())
    }

    @Test
    fun `resetAllTours removes them again`() = runTest {
        val guide = controller()
        guide.declineAllTours()
        guide.resetAllTours()
        assertTrue(store.values.isEmpty())
        assertTrue(guide.shouldAskForOnboarding())
    }

    @Test
    fun `resetTour and markOnboardingAsked`() = runTest {
        val guide = controller()
        guide.declineAllTours()
        guide.resetTour(GuideTour.PortalArea)
        assertFalse(guide.wasTourCompleted(GuideTour.PortalArea))
        assertTrue(guide.wasTourCompleted(GuideTour.Home))
        guide.resetAllTours()
        guide.markOnboardingAsked()
        assertFalse(guide.shouldAskForOnboarding())
    }

    @Test
    fun `persisted names are the Dart enum names`() {
        assertEquals(
            listOf("onboarding", "home", "settings", "events", "portal", "wallet", "reputation", "scan", "myMeetups", "portalArea"),
            GuideTour.entries.map { it.persistName },
        )
        assertEquals("guide_completed_portalArea", TourProgressStore.completedKey(GuideTour.PortalArea))
    }

    // ---- navigation & reporting ----

    @Test
    fun `previous, pause and resume`() = runTest {
        val guide = controller()
        guide.startTour(GuideTour.Settings, listOf(demoStep, demoStep, demoStep))
        guide.previousStep()
        assertEquals(0, guide.current.currentStepIndex)
        guide.nextStep()
        guide.nextStep()
        guide.previousStep()
        assertEquals(1, guide.current.currentStepIndex)

        guide.pause()
        assertTrue(guide.current.isActive)
        assertFalse(guide.current.isVisible)
        guide.nextStep() // unpauses
        assertTrue(guide.current.isVisible)
        guide.pause()
        guide.resume()
        assertTrue(guide.current.isVisible)
    }

    @Test
    fun `target taps only advance steps that ask for it`() = runTest {
        val guide = controller()
        val tapStep = GuideStep(targetId = "a", title = 1, body = 2, advanceOnTargetTap = true)
        guide.startTour(GuideTour.Home, listOf(demoStep, tapStep, demoStep))
        guide.reportTargetTapped()
        assertEquals(0, guide.current.currentStepIndex)
        guide.nextStep()
        guide.reportTargetTapped()
        assertEquals(2, guide.current.currentStepIndex)
    }

    @Test
    fun `step completion advances only auto-advance steps that are complete`() = runTest {
        var done = false
        val guide = controller()
        val autoStep = GuideStep(title = 1, body = 2, completeWhen = { done }, autoAdvance = true)
        guide.startTour(GuideTour.Home, listOf(autoStep, demoStep))
        guide.reportStepCompleted()
        assertEquals(0, guide.current.currentStepIndex)
        assertFalse(guide.current.canAdvance)
        done = true
        assertTrue(guide.current.canAdvance)
        guide.reportStepCompleted()
        assertEquals(1, guide.current.currentStepIndex)
        guide.reportStepCompleted() // demoStep is not auto-advance
        assertEquals(1, guide.current.currentStepIndex)
    }

    @Test
    fun `skipStepIfCurrent ignores stale requests`() = runTest {
        val guide = controller()
        guide.startTour(GuideTour.Home, listOf(demoStep, demoStep, demoStep))
        val run = guide.current.run
        guide.skipStepIfCurrent(run, 0)
        assertEquals(1, guide.current.currentStepIndex)
        guide.skipStepIfCurrent(run, 0) // duplicate timeout of step 0
        assertEquals(1, guide.current.currentStepIndex)
        guide.skipStepIfCurrent(run + 1, 1) // other run
        assertEquals(1, guide.current.currentStepIndex)
    }

    @Test
    fun `finishTour keeps a tour that was started while persisting`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val slowStore = object : TourProgressStore by store {
            override suspend fun setCompleted(tour: GuideTour, completed: Boolean) {
                gate.await()
                store.setCompleted(tour, completed)
            }
        }
        val guide = GuideController(slowStore, this)
        guide.startTour(GuideTour.Home, listOf(demoStep), force = true)
        launch { guide.finishTour() }
        advanceUntilIdle()
        guide.startTour(GuideTour.Wallet, listOf(demoStep), force = true)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(GuideTour.Wallet, guide.activeTour)
        assertTrue(guide.wasTourCompleted(GuideTour.Home))
    }

    @Test
    fun `endTour persists then clears`() = runTest {
        val guide = controller()
        guide.startTour(GuideTour.Scan, listOf(demoStep, demoStep), force = true)
        guide.endTour()
        advanceUntilIdle()
        assertFalse(guide.isActive)
        assertTrue(guide.wasTourCompleted(GuideTour.Scan))
    }
}
