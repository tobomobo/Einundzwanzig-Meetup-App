package space.einundzwanzig.meetup.core.ui.guide

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.ui.guide.SpotlightGeometry.Placement

/** Spotlight algorithm of `_GuideSpotlightState` (spec 12 §8.9) at density 1 and 2. */
class SpotlightTest {

    private val g1 = SpotlightGeometry(1f)
    private val screen = Size(400f, 800f)
    private val bounds = Rect(0f, 0f, 400f, 800f)

    // ---- hole ----

    @Test
    fun `hole is inflated by the padding`() {
        assertEquals(Rect(90f, 190f, 210f, 260f), g1.holeRect(Rect(100f, 200f, 200f, 250f), 10f, bounds))
    }

    @Test
    fun `hole is clamped to the overlay`() {
        assertEquals(Rect(0f, 0f, 60f, 40f), g1.holeRect(Rect(-50f, -50f, 50f, 30f), 10f, bounds))
        assertEquals(Rect(350f, 790f, 400f, 800f), g1.holeRect(Rect(360f, 800f, 450f, 900f), 10f, bounds))
    }

    @Test
    fun `hole outside the overlay or thinner than one dp is null`() {
        assertNull(g1.holeRect(Rect(0f, 900f, 100f, 950f), 10f, bounds))
        assertNull(g1.holeRect(Rect(-100f, 10f, -10f, 20f), 10f, bounds)) // touches only the edge
        assertNull(g1.holeRect(Rect(100f, -11f, 200f, -9.5f), 10f, bounds)) // 0.5 px tall after clamping
        assertNull(SpotlightGeometry(2f).holeRect(Rect(100f, -11f, 200f, -9f), 10f, bounds)) // 1 px < 1 dp at 2x
    }

    // ---- tooltip placement ----

    @Test
    fun `no hole centers the card at 70 percent height`() {
        assertEquals(Placement.Centered(368f, 560f), g1.tooltipPlacement(null, screen, 24f, 16f, 0f))
    }

    @Test
    fun `card goes below when there is room`() {
        // below = 800 - 300 - 16 - 16 = 468 >= 240
        val p = g1.tooltipPlacement(Rect(10f, 200f, 390f, 300f), screen, 24f, 16f, 0f)
        assertEquals(Placement.Below(top = 316f, maxWidth = 368f, maxHeight = 460f), p)
    }

    @Test
    fun `card goes above when below is small and above is larger`() {
        // above = 600 - 24 - 16 = 560, below = 800 - 650 - 16 - 16 = 118
        val p = g1.tooltipPlacement(Rect(10f, 600f, 390f, 650f), screen, 24f, 16f, 0f)
        assertEquals(Placement.Above(bottom = 216f, maxWidth = 368f, maxHeight = 552f), p)
    }

    @Test
    fun `ime counts as blocked bottom`() {
        // with keyboard 400: below = 800 - 300 - 400 - 16 = 84, above = 300 - 24 - 16 = 260 -> above
        val p = g1.tooltipPlacement(Rect(10f, 300f, 390f, 300f + 0.5f), screen, 24f, 16f, 400f)
        assertTrue(p is Placement.Above)
        assertEquals(800f - 300f + 16f, (p as Placement.Above).bottom)
        assertEquals(252f, p.maxHeight)
    }

    @Test
    fun `huge hole puts the card centered over it`() {
        // above = 100 - 24 - 16 = 60 < 200, below = 800 - 700 - 16 - 16 = 68 < 200
        val p = g1.tooltipPlacement(Rect(0f, 100f, 400f, 700f), screen, 24f, 16f, 0f)
        assertEquals(Placement.Centered(368f, 560f), p)
    }

    @Test
    fun `max height is clamped to 80 percent`() {
        // above = 530 - 24 - 16 = 490, below = 800 - 540 - 32 = 228 -> above, 490 - 8
        val above = g1.tooltipPlacement(Rect(0f, 530f, 400f, 540f), screen, 24f, 16f, 0f) as Placement.Above
        assertEquals(482f, above.maxHeight)
        // below exactly 240 -> below, 240 - 8
        val below = g1.tooltipPlacement(Rect(0f, 500f, 400f, 528f), screen, 24f, 16f, 0f) as Placement.Below
        assertEquals(232f, below.maxHeight)
        // hole at the very top: below - 8 = 750 > 0.8 H = 640
        val top = g1.tooltipPlacement(Rect(0f, 0f, 400f, 10f), screen, 24f, 16f, 0f) as Placement.Below
        assertEquals(640f, top.maxHeight)
    }

    @Test
    fun `max width is capped at 400 dp`() {
        val wide = g1.tooltipPlacement(null, Size(1000f, 800f), 0f, 0f, 0f)
        assertEquals(400f, wide.maxWidth)
        val dense = SpotlightGeometry(2.5f).tooltipPlacement(null, Size(1080f, 2400f), 0f, 0f, 0f)
        assertEquals(1000f, dense.maxWidth)
    }

    // ---- barriers ----

    @Test
    fun `barriers surround the hole`() {
        val hole = Rect(50f, 100f, 350f, 200f)
        assertEquals(
            listOf(
                Rect(0f, 0f, 400f, 100f),
                Rect(0f, 200f, 400f, 800f),
                Rect(0f, 100f, 50f, 200f),
                Rect(350f, 100f, 400f, 200f),
            ),
            g1.barriers(hole, screen),
        )
    }

    @Test
    fun `edge holes skip empty barriers and no hole blocks everything`() {
        assertEquals(listOf(Rect(0f, 50f, 400f, 800f)), g1.barriers(Rect(0f, 0f, 400f, 50f), screen))
        assertEquals(listOf(bounds), g1.barriers(null, screen))
    }

    // ---- skip chip ----

    @Test
    fun `skip chip hides when it would cover the hole`() {
        val chip = g1.skipChipRect(400f, 24f)
        assertEquals(Rect(228f, 32f, 388f, 76f), chip)
        assertTrue(g1.isSkipChipVisible(null, chip))
        assertTrue(g1.isSkipChipVisible(Rect(0f, 100f, 400f, 200f), chip))
        assertFalse(g1.isSkipChipVisible(Rect(300f, 40f, 390f, 90f), chip))
    }

    // ---- ticker ----

    private fun step(
        target: String? = "t",
        timeout: Long = 6_000,
        complete: (() -> Boolean)? = null,
        auto: Boolean = false,
        scroll: Boolean = true,
    ) = GuideStep(
        targetId = target,
        title = 1,
        body = 2,
        completeWhen = complete,
        autoAdvance = auto,
        waitTimeoutMillis = timeout,
        scrollIntoView = scroll,
    )

    private val visible = TargetMeasurement(true, Rect(0f, 0f, 10f, 10f), covered = false)
    private val coveredTarget = TargetMeasurement(true, null, covered = true)
    private val offscreen = TargetMeasurement(true, null, covered = false)

    @Test
    fun `missing target is skipped once after the timeout`() {
        val ticker = SpotlightTicker(step(timeout = 220), startMillis = 0)
        assertFalse(ticker.tick(TargetMeasurement.Missing, 200).skip)
        assertFalse(ticker.tick(TargetMeasurement.Missing, 220).skip) // not "> timeout" yet
        assertTrue(ticker.tick(TargetMeasurement.Missing, 221).skip)
        assertFalse(ticker.tick(TargetMeasurement.Missing, 500).skip)
    }

    @Test
    fun `covered or visible targets reset the wait`() {
        val ticker = SpotlightTicker(step(timeout = 100), startMillis = 0)
        ticker.tick(coveredTarget, 90)
        assertFalse(ticker.tick(TargetMeasurement.Missing, 150).skip)
        ticker.tick(visible, 180)
        assertFalse(ticker.tick(TargetMeasurement.Missing, 280).skip)
        assertTrue(ticker.tick(TargetMeasurement.Missing, 281).skip)
    }

    @Test
    fun `covered target never times out`() {
        val ticker = SpotlightTicker(step(timeout = 100), startMillis = 0)
        assertFalse(ticker.tick(coveredTarget, 10_000).skip)
        assertTrue(ticker.covered)
        assertNull(ticker.hole)
    }

    @Test
    fun `steps without target never skip`() {
        val ticker = SpotlightTicker(step(target = null, timeout = 1), startMillis = 0)
        assertFalse(ticker.tick(TargetMeasurement.Missing, 10_000).skip)
    }

    @Test
    fun `scroll on first tick when the anchor exists and once when it becomes visible`() {
        val ticker = SpotlightTicker(step(), startMillis = 0)
        assertTrue(ticker.tick(offscreen, 0).scrollIntoView)
        assertFalse(ticker.tick(offscreen, 40).scrollIntoView)
        assertTrue(ticker.tick(visible, 80).scrollIntoView)
        assertFalse(ticker.tick(visible, 120).scrollIntoView)
        assertFalse(SpotlightTicker(step(scroll = false), 0).tick(visible, 0).scrollIntoView)
        assertFalse(SpotlightTicker(step(), 0).tick(TargetMeasurement.Missing, 0).scrollIntoView)
    }

    @Test
    fun `auto advance is scheduled once when complete`() {
        var done = false
        val ticker = SpotlightTicker(step(complete = { done }, auto = true), startMillis = 0)
        assertFalse(ticker.tick(visible, 0).scheduleAutoAdvance)
        done = true
        assertTrue(ticker.tick(visible, 40).scheduleAutoAdvance)
        assertFalse(ticker.tick(visible, 80).scheduleAutoAdvance)
    }

    @Test
    fun `changed flag reports visible state changes`() {
        val ticker = SpotlightTicker(step(), startMillis = 0)
        assertTrue(ticker.tick(visible, 0).changed)
        assertFalse(ticker.tick(visible, 40).changed)
        assertTrue(ticker.tick(coveredTarget, 80).changed)
        assertTrue(ticker.complete)
    }

    @Test
    fun `step completion semantics`() {
        assertTrue(step().isComplete)
        assertFalse(step().requiresAction)
        assertTrue(step(complete = { false }).requiresAction)
        assertFalse(step(complete = { false }).isComplete)
        assertTrue(step(complete = { error("state unreachable") }).isComplete)
        assertTrue(GuideStep(title = 1, body = 2, advanceOnTargetTap = true).requiresAction)
    }
}
