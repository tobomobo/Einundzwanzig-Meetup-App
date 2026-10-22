package space.einundzwanzig.meetup.feature.community

import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import space.einundzwanzig.meetup.feature.community.plebrap.PlebrapAction
import space.einundzwanzig.meetup.feature.community.plebrap.PlebrapUiState
import space.einundzwanzig.meetup.feature.community.plebrap.PlebrapViewModel
import space.einundzwanzig.meetup.feature.community.plebrap.formatPlaybackTime

class PlebrapViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val player = FakePlebrapPlayer()

    @Test
    fun `state mirrors the player`() = runTest {
        val vm = PlebrapViewModel(player)
        vm.state.test {
            assertEquals(null, awaitItem().index)
            player.index.value = 3
            player.isPlaying.value = true
            player.durationMs.value = 120_000
            player.seekable.value = true
            player.position.value = 61_000
            val s = expectMostRecentItem()
            assertEquals(3, s.index)
            assertTrue(s.playing)
            assertEquals(120_000, s.sliderMaxMs)
            assertEquals(61_000, s.positionMs)
            assertFalse(s.showCaching)
        }
    }

    @Test
    fun `commands are delegated`() {
        val vm = PlebrapViewModel(player)
        vm.onAction(PlebrapAction.PlayIndex(5))
        vm.onAction(PlebrapAction.Toggle)
        vm.onAction(PlebrapAction.Next)
        vm.onAction(PlebrapAction.Prev)
        vm.onAction(PlebrapAction.SeekTo(42))
        vm.onAction(PlebrapAction.OpenV4v)
        assertEquals(listOf("play:5", "toggle", "next", "prev", "seek:42"), player.commands)
    }

    @Test
    fun `load errors before opening are ignored, later changes signal once each`() = runTest {
        player.loadErrors.value = 4
        val vm = PlebrapViewModel(player)
        vm.loadErrorEvents.test {
            expectNoEvents()
            player.loadErrors.value = 5
            awaitItem()
            player.loadErrors.value = 6
            awaitItem()
        }
    }

    @Test
    fun `errors while the screen is in the background do not show after returning`() = runTest {
        val vm = PlebrapViewModel(player)
        // STARTED: the route collects.
        vm.loadErrorEvents.test {
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        // STOPPED: no collector while the error count changes.
        player.loadErrors.value = 1
        // STARTED again: the error from the background is not replayed, new ones still show.
        vm.loadErrorEvents.test {
            expectNoEvents()
            player.loadErrors.value = 2
            awaitItem()
        }
    }

    @Test
    fun `resubscribing keeps the last position instead of flashing zero`() = runTest {
        player.index.value = 0
        val vm = PlebrapViewModel(player)
        vm.state.test {
            player.position.value = 30_000
            assertEquals(30_000, expectMostRecentItem().positionMs)
            cancelAndIgnoreRemainingEvents()
        }
        // Let WhileSubscribed(5 s) stop the upstream, as when the screen stays in the background.
        advanceTimeBy(6_000)
        val positions = mutableListOf<Long>()
        vm.state.test {
            positions += awaitItem().positionMs
            positions += cancelAndConsumeRemainingEvents()
                .filterIsInstance<app.cash.turbine.Event.Item<PlebrapUiState>>()
                .map { it.value.positionMs }
        }
        assertTrue(positions.none { it == 0L })
    }

    @Test
    fun `prev and next enablement follows the index bounds`() {
        assertFalse(PlebrapUiState().canPrev)
        assertFalse(PlebrapUiState().canNext)
        assertFalse(PlebrapUiState(index = 0).canPrev)
        assertTrue(PlebrapUiState(index = 0).canNext)
        assertTrue(PlebrapUiState(index = 40).canPrev)
        assertFalse(PlebrapUiState(index = 40).canNext)
    }

    @Test
    fun `slider and caching hint depend on seekable`() {
        val streaming = PlebrapUiState(index = 1, durationMs = 100_000, seekable = false)
        assertEquals(0, streaming.sliderMaxMs)
        assertTrue(streaming.showCaching)
        assertFalse(PlebrapUiState(index = null).showCaching)
    }

    @Test
    fun `time format is minutes and padded seconds`() {
        assertEquals("0:00", formatPlaybackTime(0))
        assertEquals("1:05", formatPlaybackTime(65_999))
        assertEquals("12:00", formatPlaybackTime(720_000))
    }

    @Test
    fun `initial state is taken from the player synchronously`() = runTest {
        player.index.value = 2
        player.loading.value = true
        val vm = PlebrapViewModel(player)
        assertEquals(2, vm.state.value.index)
        assertTrue(vm.state.value.loading)
        assertEquals(2, vm.state.first().index)
    }
}
