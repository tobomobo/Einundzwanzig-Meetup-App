package space.einundzwanzig.meetup.data.settings

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.ui.guide.GuideController
import space.einundzwanzig.meetup.core.ui.guide.GuideStep
import space.einundzwanzig.meetup.core.ui.guide.GuideTour
import space.einundzwanzig.meetup.data.settings.internal.KeyValueTourProgressStore

/**
 * Persistence half of `test/guide_service_host_test.dart` (against the real KeyValueStore keys)
 * plus spec 12 §10 item 10. The widget/dispose half lives in `:core:ui` `GuideHostTest`.
 */
class TourProgressStoreTest {

    private val prefs = InMemoryKeyValueStore()
    private val store = KeyValueTourProgressStore(prefs)
    private val demoStep = GuideStep(title = 1, body = 2)

    private fun TestScope.guide() = GuideController(store, this)

    private val allTourKeys = listOf(
        "guide_completed_onboarding",
        "guide_completed_home",
        "guide_completed_settings",
        "guide_completed_events",
        "guide_completed_portal",
        "guide_completed_wallet",
        "guide_completed_reputation",
        "guide_completed_scan",
        "guide_completed_myMeetups",
        "guide_completed_portalArea",
    )

    // ---- guide_service_host_test.dart ----

    @Test
    fun `finishTourNow resets the active tour synchronously`() = runTest {
        val guide = guide()
        guide.startTour(GuideTour.Portal, listOf(demoStep), force = true)
        assertTrue(guide.isActive)
        assertEquals(GuideTour.Portal, guide.activeTour)

        guide.finishTourNow()

        assertFalse(guide.isActive)
        assertNull(guide.activeTour)
        assertNull(guide.currentStep)
    }

    @Test
    fun `finishTourNow marks the tour completed (persistence)`() = runTest {
        val guide = guide()
        guide.startTour(GuideTour.Wallet, listOf(demoStep), force = true)

        guide.finishTourNow()
        advanceUntilIdle()

        assertTrue(guide.wasTourCompleted(GuideTour.Wallet))
        assertEquals(PrefValue.BoolValue(true), prefs.entries.value["guide_completed_wallet"])
    }

    @Test
    fun `finishIfActive ignores other tours`() = runTest {
        val guide = guide()
        guide.startTour(GuideTour.Events, listOf(demoStep), force = true)

        guide.finishIfActive(GuideTour.Portal)
        advanceUntilIdle()

        assertTrue(guide.isActive)
        assertEquals(GuideTour.Events, guide.activeTour)
        assertFalse("guide_completed_portal" in prefs.entries.value)
    }

    // ---- spec 12 §10 item 10 ----

    @Test
    fun `startTour is a no-op for a completed tour unless forced`() = runTest {
        prefs.setBool("guide_completed_home", true)
        val guide = guide()

        guide.startTour(GuideTour.Home, listOf(demoStep))
        assertFalse(guide.isActive)

        guide.startTour(GuideTour.Home, listOf(demoStep), force = true)
        assertTrue(guide.isActive)
    }

    @Test
    fun `nextStep on the last step finishes and persists`() = runTest {
        val guide = guide()
        guide.startTour(GuideTour.MyMeetups, listOf(demoStep, demoStep))
        guide.nextStep()
        assertTrue(guide.isActive)
        guide.nextStep()
        advanceUntilIdle()

        assertFalse(guide.isActive)
        assertEquals(PrefValue.BoolValue(true), prefs.entries.value["guide_completed_myMeetups"])
    }

    @Test
    fun `declineAllTours writes all ten tour keys and the onboarding flag`() = runTest {
        val guide = guide()
        assertTrue(guide.shouldAskForOnboarding())

        guide.declineAllTours()

        val expected = (allTourKeys + "guide_onboarding_asked").associateWith { PrefValue.BoolValue(true) }
        assertEquals(expected, prefs.entries.value)
        assertFalse(guide.shouldAskForOnboarding())
    }

    @Test
    fun `resetAllTours removes the keys`() = runTest {
        prefs.setString("app_locale", "en")
        val guide = guide()
        guide.declineAllTours()

        guide.resetAllTours()

        assertEquals(mapOf("app_locale" to PrefValue.StringValue("en")), prefs.entries.value)
        GuideTour.entries.forEach { assertFalse(guide.wasTourCompleted(it)) }
        assertTrue(guide.shouldAskForOnboarding())
    }

    @Test
    fun `resetTour removes only that key`() = runTest {
        val guide = guide()
        guide.declineAllTours()
        guide.resetTour(GuideTour.PortalArea)

        assertFalse("guide_completed_portalArea" in prefs.entries.value)
        assertTrue(guide.wasTourCompleted(GuideTour.Home))
    }

    @Test
    fun `flags migrated from Flutter are honoured`() = runTest {
        val migrated = InMemoryKeyValueStore(
            mapOf(
                "guide_completed_portalArea" to PrefValue.BoolValue(true),
                "guide_onboarding_asked" to PrefValue.BoolValue(true),
            ),
        )
        val s = KeyValueTourProgressStore(migrated)
        assertTrue(s.isCompleted(GuideTour.PortalArea))
        assertFalse(s.isCompleted(GuideTour.Home))
        assertTrue(s.isOnboardingAsked())
    }

    @Test
    fun `a value of another type reads as not completed`() = runTest {
        prefs.setString("guide_completed_home", "true")
        assertFalse(store.isCompleted(GuideTour.Home))
    }
}
