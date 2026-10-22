package space.einundzwanzig.meetup.core.ui.guide

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.max
import kotlin.math.min

/**
 * Pure geometry of the spotlight overlay (spec 12 §8.9), in pixels. All Flutter constants are
 * logical pixels and are multiplied by [density] (px per dp).
 */
internal class SpotlightGeometry(private val density: Float) {
    private fun dp(value: Float) = value * density

    /**
     * Hole for a target at [target] (overlay coordinates): inflated by [paddingPx], null if it
     * does not overlap [bounds], clamped to `[0, bounds]`, null if thinner than 1 dp.
     */
    fun holeRect(target: Rect, paddingPx: Float, bounds: Rect): Rect? {
        val inflated = target.inflate(paddingPx)
        if (!inflated.overlaps(bounds)) return null
        val clamped = Rect(
            left = max(inflated.left, 0f),
            top = max(inflated.top, 0f),
            right = min(inflated.right, bounds.right),
            bottom = min(inflated.bottom, bounds.bottom),
        )
        if (clamped.width <= dp(1f) || clamped.height <= dp(1f)) return null
        return clamped
    }

    /** Where the tooltip card goes. */
    sealed interface Placement {
        /** Max card width: `min(W − 32, 400)`. */
        val maxWidth: Float

        /** Max card height (the card scrolls inside). */
        val maxHeight: Float

        /** Centered over everything (no hole, or no room above/below it). */
        data class Centered(override val maxWidth: Float, override val maxHeight: Float) : Placement

        /** Card top at [top] (below the hole). */
        data class Below(val top: Float, override val maxWidth: Float, override val maxHeight: Float) : Placement

        /** Card bottom at [bottom] px from the bottom edge (above the hole). */
        data class Above(val bottom: Float, override val maxWidth: Float, override val maxHeight: Float) : Placement
    }

    /**
     * Tooltip placement: room above = `hole.top − statusBar − 16`, below = `H − hole.bottom −
     * max(navBar, ime) − 16`; both < 200 → centered (0.7 H); below ≥ 240 or ≥ above → below
     * (`hole.bottom + 16`, max `below − 8`); else above (`H − hole.top + 16`, max `above − 8`);
     * max height clamped to `[180, 0.8 H]`.
     */
    fun tooltipPlacement(hole: Rect?, screen: Size, paddingTop: Float, paddingBottom: Float, imeBottom: Float): Placement {
        val gap = dp(16f)
        val maxWidth = min(screen.width - dp(32f), dp(400f))
        val defaultMaxHeight = screen.height * 0.7f
        if (hole == null) return Placement.Centered(maxWidth, defaultMaxHeight)
        val blockedBottom = max(paddingBottom, imeBottom)
        val above = hole.top - paddingTop - gap
        val below = screen.height - hole.bottom - blockedBottom - gap
        val minRoom = dp(200f)
        fun clampHeight(h: Float) = h.coerceAtLeast(dp(180f)).coerceAtMost(max(dp(180f), screen.height * 0.8f))
        return when {
            above < minRoom && below < minRoom -> Placement.Centered(maxWidth, clampHeight(defaultMaxHeight))
            below >= dp(240f) || below >= above -> Placement.Below(hole.bottom + gap, maxWidth, clampHeight(below - dp(8f)))
            else -> Placement.Above(screen.height - hole.top + gap, maxWidth, clampHeight(above - dp(8f)))
        }
    }

    /**
     * Touch barriers around the hole: full-width strips above and below, side strips at hole
     * height; one full-screen barrier without a hole.
     */
    fun barriers(hole: Rect?, screen: Size): List<Rect> {
        if (hole == null) return listOf(Rect(0f, 0f, screen.width, screen.height))
        return buildList {
            if (hole.top > 0f) add(Rect(0f, 0f, screen.width, hole.top))
            if (hole.bottom < screen.height) add(Rect(0f, hole.bottom, screen.width, screen.height))
            if (hole.left > 0f) add(Rect(0f, hole.top, hole.left, hole.bottom))
            if (hole.right < screen.width) add(Rect(hole.right, hole.top, screen.width, hole.bottom))
        }
    }

    /** Area reserved for the skip chip: `(W − 172, statusBar + 8, 160 × 44)`. */
    fun skipChipRect(screenWidth: Float, statusBarTop: Float): Rect {
        val top = statusBarTop + dp(8f)
        return Rect(screenWidth - dp(172f), top, screenWidth - dp(12f), top + dp(44f))
    }

    /** The skip chip hides when it would cover the hole. */
    fun isSkipChipVisible(hole: Rect?, chip: Rect): Boolean = hole == null || !hole.overlaps(chip)
}

/** What the overlay found for the current step's target. */
internal data class TargetMeasurement(
    /** An anchor with this id is attached somewhere. */
    val anchorPresent: Boolean,
    /** Hole in overlay coordinates, null if not measurable / off screen / covered. */
    val hole: Rect?,
    /** The target exists but must not be spotlighted now (another window or screen on top). */
    val covered: Boolean,
) {
    companion object {
        /** No anchor registered for the target (or the step has none). */
        val Missing = TargetMeasurement(anchorPresent = false, hole = null, covered = false)
    }
}

/**
 * Per-step polling state machine of the overlay (`_GuideSpotlightState._tick`), pure and
 * clock-injected so it can be unit tested.
 */
internal class SpotlightTicker(private val step: GuideStep, startMillis: Long) {
    var hole: Rect? = null
        private set
    var complete: Boolean = false
        private set
    var timedOut: Boolean = false
        private set
    var covered: Boolean = false
        private set

    private var waitingSince = startMillis
    private var didScroll = false
    private var firstTick = true
    private var skipRequested = false
    private var advanceScheduled = false

    /** Result of one [tick]. */
    data class Actions(
        /** Bring the target into view now. */
        val scrollIntoView: Boolean,
        /** Visible state ([hole], [complete], [timedOut], [covered]) changed. */
        val changed: Boolean,
        /** The target stayed missing past the timeout: skip the step (once). */
        val skip: Boolean,
        /** Schedule `reportStepCompleted()` after the 700 ms "done" pause (once). */
        val scheduleAutoAdvance: Boolean,
    )

    /** Evaluates one poll at [nowMillis]. */
    fun tick(measurement: TargetMeasurement, nowMillis: Long, isComplete: Boolean = step.isComplete): Actions {
        val rect = measurement.hole
        // First tick mirrors Flutter's post-frame scroll; afterwards scroll once when the
        // target becomes measurable (e.g. inside a sheet that is still opening).
        val scroll = step.scrollIntoView && ((rect != null && !didScroll) || (firstTick && measurement.anchorPresent))
        if (rect != null) didScroll = true
        firstTick = false

        // No waiting time runs while the target is covered or visible.
        if (measurement.covered || rect != null) waitingSince = nowMillis
        val nowTimedOut = nowMillis - waitingSince > step.waitTimeoutMillis

        val changed = rect != hole || isComplete != complete || nowTimedOut != timedOut || measurement.covered != covered
        hole = rect
        complete = isComplete
        timedOut = nowTimedOut
        covered = measurement.covered

        if (step.targetId != null && rect == null && !measurement.covered && nowTimedOut && !skipRequested) {
            skipRequested = true
            return Actions(scroll, changed, skip = true, scheduleAutoAdvance = false)
        }
        val schedule = isComplete && step.autoAdvance && !advanceScheduled
        if (schedule) advanceScheduled = true
        return Actions(scroll, changed, skip = false, scheduleAutoAdvance = schedule)
    }

    companion object {
        /** Poll interval (Flutter: 40 ms). */
        const val POLL_INTERVAL_MS: Long = 40

        /** Pause showing "Erledigt" before an automatic advance. */
        const val AUTO_ADVANCE_DELAY_MS: Long = 700
    }
}
