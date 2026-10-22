package space.einundzwanzig.meetup.core.ui.guide

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/**
 * Port of the widget part of `test/guide_service_host_test.dart` plus overlay behaviour
 * (spotlight hole lets taps through, barriers block, missing targets are skipped).
 */
@RunWith(AndroidJUnit4::class)
class GuideHostTest {

    @get:Rule
    val rule = createComposeRule()

    private val store = InMemoryTourProgressStore()
    private val controller = GuideController(store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
    private val demoStep = GuideStep(title = L10nR.string.guideHomeGlossaryTitle, body = L10nR.string.guideHomeGlossaryBody)

    /** Minimal host: starts the tour after the first frame, finishes it on disposal. */
    @Composable
    private fun TourHostScreen(tour: GuideTour) {
        StartTourEffect(tour, force = true) { listOf(demoStep) }
        FinishTourOnDispose(tour)
    }

    @Composable
    private fun DisposeOnlyHost(tourToFinish: GuideTour) {
        FinishTourOnDispose(tourToFinish)
    }

    @Test
    fun `dispose finishes the active tour without errors`() {
        var show by mutableStateOf(true)
        rule.setContent {
            EinundzwanzigTheme {
                GuideHost(controller) { if (show) TourHostScreen(GuideTour.Portal) }
            }
        }
        rule.waitForIdle()
        assertTrue(controller.isActive)

        show = false
        rule.waitForIdle()

        assertFalse(controller.isActive)
        rule.waitUntil(2_000) { store.values["guide_completed_portal"] == true }
    }

    @Test
    fun `finishTourOnDispose ignores other tours`() {
        runBlocking { controller.startTour(GuideTour.Events, listOf(demoStep), force = true) }
        var show by mutableStateOf(true)
        rule.setContent {
            EinundzwanzigTheme {
                CompositionLocalProvider(LocalGuideController provides controller) {
                    if (show) DisposeOnlyHost(GuideTour.Portal)
                }
            }
        }
        rule.waitForIdle()
        show = false
        rule.waitForIdle()

        assertTrue(controller.isActive)
        assertEquals(GuideTour.Events, controller.activeTour)
    }

    @Test(expected = IllegalStateException::class)
    fun `guide controller without host throws`() {
        rule.setContent { LocalGuideController.current }
    }

    // ---- overlay ----

    private var outsideClicks = 0
    private var targetClicks = 0

    /** Root host with the overlay forced active (window focus is not simulated). */
    private fun setTourContent() {
        rule.setContent {
            EinundzwanzigTheme {
                val registry = remember { GuideTargetRegistry() }
                CompositionLocalProvider(LocalGuideController provides controller, LocalGuideTargetRegistry provides registry) {
                    registry.tapListener = { id -> if (controller.currentStep?.targetId == id) controller.reportTargetTapped() }
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(16.dp)) {
                            Spacer(Modifier.height(80.dp))
                            Text(
                                "Ziel",
                                Modifier
                                    .size(120.dp, 48.dp)
                                    .guideAnchor("home.target")
                                    .clickable { targetClicks++ },
                            )
                            Spacer(Modifier.height(160.dp))
                            Text("Außen", Modifier.size(120.dp, 48.dp).clickable { outsideClicks++ })
                        }
                        GuideOverlay(controller, registry, hostActive = true, clock = { rule.mainClock.currentTime })
                    }
                }
            }
        }
    }

    private fun start(vararg steps: GuideStep) = runBlocking { controller.startTour(GuideTour.Home, steps.toList(), force = true) }

    @Test
    fun `tooltip shows title, progress and advances with Weiter`() {
        start(
            GuideStep(targetId = "home.target", title = L10nR.string.guideHomeGlossaryTitle, body = L10nR.string.guideHomeGlossaryBody),
            GuideStep(title = L10nR.string.guideHomeMoreTitle, body = L10nR.string.guideHomeMoreBody),
        )
        setTourContent()
        rule.mainClock.advanceTimeBy(200)
        rule.onNodeWithText("Schritt 1 von 2").assertExists()
        rule.onNodeWithText("Weiter").performClick()
        rule.waitForIdle()
        assertEquals(1, controller.current.currentStepIndex)
        rule.mainClock.advanceTimeBy(200)
        rule.onNodeWithText("Schritt 2 von 2").assertExists()
        rule.onNodeWithText("Zurück").performClick()
        assertEquals(0, controller.current.currentStepIndex)
    }

    @Test
    fun `hole lets taps through to the target, barriers block the rest`() {
        start(
            GuideStep(
                targetId = "home.target",
                title = L10nR.string.guideHomeGlossaryTitle,
                body = L10nR.string.guideHomeGlossaryBody,
                advanceOnTargetTap = true,
            ),
            GuideStep(title = L10nR.string.guideHomeMoreTitle, body = L10nR.string.guideHomeMoreBody),
        )
        setTourContent()
        rule.mainClock.advanceTimeBy(200)

        rule.onNodeWithText("Außen").performClick()
        assertEquals(0, outsideClicks)

        rule.onNodeWithText("Ziel").performClick()
        rule.waitForIdle()
        assertEquals(1, targetClicks)
        assertEquals(1, controller.current.currentStepIndex)
    }

    @Test
    fun `missing target is skipped after its timeout`() {
        start(
            GuideStep(targetId = "gone", title = L10nR.string.guideHomeGlossaryTitle, body = L10nR.string.guideHomeGlossaryBody, waitTimeoutMillis = 220),
            GuideStep(targetId = "home.target", title = L10nR.string.guideHomeMoreTitle, body = L10nR.string.guideHomeMoreBody),
        )
        setTourContent()
        rule.mainClock.advanceTimeBy(100)
        assertEquals(0, controller.current.currentStepIndex)
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        assertEquals(1, controller.current.currentStepIndex)
    }

    @Test
    fun `finish tour button persists and ends the tour`() {
        start(GuideStep(title = L10nR.string.guideHomeMoreTitle, body = L10nR.string.guideHomeMoreBody))
        setTourContent()
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText("Tour beenden").performClick()
        rule.waitForIdle()
        assertFalse(controller.isActive)
        assertEquals(true, store.values["guide_completed_home"])
    }
}
