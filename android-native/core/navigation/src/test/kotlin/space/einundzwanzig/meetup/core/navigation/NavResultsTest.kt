package space.einundzwanzig.meetup.core.navigation

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation3.runtime.result.ResultEventBus
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavResultsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `result sent before the consumer is composed is delivered once`() {
        val bus = ResultEventBus()
        val received = mutableStateListOf<String>()
        bus.sendResult(NavResults.NpubScanned, "npub1x")

        composeRule.setContent {
            NavResultEffect(NavResults.NpubScanned, bus) { received += it }
        }
        composeRule.waitForIdle()

        assertEquals(listOf("npub1x"), received.toList())
    }

    @Test
    fun `results are routed by key`() {
        val bus = ResultEventBus()
        val npubs = mutableStateListOf<String>()
        val picks = mutableStateListOf<PickedLocation>()

        composeRule.setContent {
            NavResultEffect(NavResults.NpubScanned, bus) { npubs += it }
            NavResultEffect(NavResults.LocationPicked, bus) { picks += it }
        }
        composeRule.runOnIdle {
            bus.sendResult(NavResults.LocationPicked, PickedLocation(49.79245, 9.93296))
            bus.sendResult(NavResults.NpubScanned, "npub1y")
        }
        composeRule.waitForIdle()

        assertEquals(listOf("npub1y"), npubs.toList())
        assertEquals(listOf(PickedLocation(49.79245, 9.93296)), picks.toList())
    }
}
