package space.einundzwanzig.meetup.core.ui.guide

import android.os.SystemClock
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import kotlin.math.abs
import kotlin.math.roundToInt
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/**
 * The app-wide [GuideController], provided by [GuideHost]. Reading it without a host throws
 * (the Compose counterpart of the Dart `GuideServiceHost` "accessed too early" error).
 */
val LocalGuideController = staticCompositionLocalOf<GuideController> {
    error("No GuideController provided: wrap the content in GuideHost")
}

/**
 * Root of the tour system: provides [controller] and a [GuideTargetRegistry] to [content] and
 * draws the [GuideOverlay] above it. Place it around the navigation host in the app shell.
 */
@Composable
fun GuideHost(
    controller: GuideController,
    modifier: Modifier = Modifier,
    registry: GuideTargetRegistry = remember { GuideTargetRegistry() },
    content: @Composable () -> Unit,
) {
    DisposableEffect(registry, controller) {
        registry.tapListener = { id ->
            val s = controller.current
            if (s.isVisible && s.currentStep?.targetId == id) controller.reportTargetTapped()
        }
        onDispose { registry.tapListener = null }
    }
    CompositionLocalProvider(
        LocalGuideController provides controller,
        LocalGuideTargetRegistry provides registry,
    ) {
        Box(modifier) {
            content()
            GuideOverlay(controller, registry)
        }
    }
}

/**
 * Additional overlay for content in its own window (Compose `ModalBottomSheet`, `Dialog`):
 * those windows sit above the root overlay, so tours targeting their content need an overlay
 * inside them (e.g. the settings tour inside the settings sheet). Uses the host's controller
 * and registry; without a [GuideHost] above it just shows [content].
 */
@Composable
fun GuideWindowHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val registry = LocalGuideTargetRegistry.current
    Box(modifier) {
        content()
        if (registry != null) GuideOverlay(LocalGuideController.current, registry)
    }
}

/**
 * Finishes [tour] when this composable leaves the composition, if that tour is still the
 * active one (Dart `GuideServiceHost.finishGuideTourIfActive` in `dispose`). The tour is then
 * persisted as completed in the background.
 */
@Composable
fun FinishTourOnDispose(tour: GuideTour, controller: GuideController = LocalGuideController.current) {
    val currentController by rememberUpdatedState(controller)
    DisposableEffect(tour) {
        onDispose { currentController.finishIfActive(tour) }
    }
}

/**
 * The tour start pattern of the Flutter screens: after the first frame (plus [delayMillis]),
 * if [tour] was not completed before (or [force]), start it with [steps].
 */
@Composable
fun StartTourEffect(
    tour: GuideTour,
    enabled: Boolean = true,
    force: Boolean = false,
    delayMillis: Long = 0,
    controller: GuideController = LocalGuideController.current,
    steps: () -> List<GuideStep>,
) {
    val currentSteps by rememberUpdatedState(steps)
    LaunchedEffect(tour, enabled, force) {
        if (!enabled) return@LaunchedEffect
        withFrameNanos { }
        if (delayMillis > 0) delay(delayMillis)
        controller.startTour(tour, currentSteps(), force = force)
    }
}

/**
 * Draws the current step of [controller]: dark scrim with a pulsing hole around the target,
 * touch barriers, the tooltip card and the skip chip (spec 12 §2.9, §8.9).
 *
 * The overlay only draws while its window is focused ([hostActive]); targets in another
 * window, on a non-resumed screen or under a dialog count as covered: nothing is drawn and the
 * missing-target timeout does not run.
 */
@Composable
fun GuideOverlay(
    controller: GuideController,
    registry: GuideTargetRegistry,
    modifier: Modifier = Modifier,
    hostActive: Boolean = LocalWindowInfo.current.isWindowFocused,
    clock: () -> Long = { SystemClock.uptimeMillis() },
) {
    val state by controller.state.collectAsState()
    val step = state.currentStep
    if (!state.isVisible || step == null) return
    key(state.run, state.currentStepIndex) {
        Spotlight(controller, registry, state, step, hostActive, clock, modifier)
    }
}

private class CoordinatesHolder {
    var value: LayoutCoordinates? = null
}

@Composable
private fun Spotlight(
    controller: GuideController,
    registry: GuideTargetRegistry,
    state: GuideState,
    step: GuideStep,
    hostActive: Boolean,
    clock: () -> Long,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val geometry = remember(density) { SpotlightGeometry(density.density) }
    val host = remember { CoordinatesHolder() }
    val ticker = remember { SpotlightTicker(step, clock()) }
    var hole by remember { mutableStateOf<Rect?>(null) }
    var complete by remember { mutableStateOf(step.isComplete) }
    val currentHostActive by rememberUpdatedState(hostActive)
    val holePaddingPx = with(density) { step.holePadding.toPx() }
    val scrollMarginPx = with(density) { 24.dp.toPx() }
    val flingScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withFrameNanos { } // first measurement right after the first frame
        while (isActive) {
            val measurement = measureTarget(registry, step, host.value, currentHostActive, geometry, holePaddingPx)
            val actions = ticker.tick(measurement, clock())
            if (actions.changed) {
                hole = ticker.hole
                complete = ticker.complete
            }
            if (actions.scrollIntoView) {
                step.targetId?.let(registry::anchor)?.let { anchor -> launch { anchor.bringIntoView(scrollMarginPx) } }
            }
            if (actions.skip) {
                controller.skipStepIfCurrent(state.run, state.currentStepIndex)
                return@LaunchedEffect
            }
            if (actions.scheduleAutoAdvance) {
                launch {
                    delay(SpotlightTicker.AUTO_ADVANCE_DELAY_MS)
                    controller.reportStepCompleted()
                }
            }
            delay(SpotlightTicker.POLL_INTERVAL_MS)
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { host.value = it },
    ) {
        // A step with a target only draws once the hole sits; never collapse to 0 x 0 so the
        // overlay can keep measuring.
        val currentHole = hole
        if (!hostActive || (step.targetId != null && currentHole == null)) return@BoxWithConstraints
        val screen = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val scrollable = step.targetId?.let(registry::anchor)?.scrollable

        SpotlightScrim(currentHole, with(density) { step.holeRadius.toPx() })

        if (step.blockOutside) {
            geometry.barriers(currentHole, screen).forEach { rect ->
                Box(
                    Modifier
                        .placeAt(rect)
                        .pointerInput(scrollable) { blockAndForwardDrags(scrollable, flingScope) },
                )
            }
        }
        if (currentHole != null && !step.interactiveTarget) {
            Box(Modifier.placeAt(currentHole).pointerInput(Unit) { swallowAll() })
        }

        TooltipPositioner(geometry, currentHole, screen) {
            TooltipCard(controller, state, step, complete)
        }
        SkipChip(controller, geometry, currentHole, screen)
    }
}

private fun measureTarget(
    registry: GuideTargetRegistry,
    step: GuideStep,
    host: LayoutCoordinates?,
    hostActive: Boolean,
    geometry: SpotlightGeometry,
    paddingPx: Float,
): TargetMeasurement {
    val id = step.targetId ?: return TargetMeasurement.Missing
    val anchor = registry.anchor(id) ?: return TargetMeasurement.Missing
    val coordinates = anchor.coordinates ?: return TargetMeasurement.Missing
    val covered = TargetMeasurement(anchorPresent = true, hole = null, covered = true)
    if (host == null || !host.isAttached) return covered
    // Another window (dialog/sheet with its own overlay), a covered window or a paused screen.
    if (coordinates.findRootCoordinates() !== host.findRootCoordinates()) return covered
    if (!hostActive || !anchor.isResumed) return covered
    val bounds = Rect(Offset.Zero, host.size.toSize())
    val target = host.localBoundingBoxOf(coordinates, clipBounds = false)
    return TargetMeasurement(anchorPresent = true, hole = geometry.holeRect(target, paddingPx, bounds), covered = false)
}

private val GuideAccentStrokeMin = 0.55f
private val ScrimAlpha = 0.85f

@Composable
private fun SpotlightScrim(hole: Rect?, radiusPx: Float) {
    val accent = MaterialTheme.colorScheme.primary
    val pulse: State<Float> = rememberInfiniteTransition(label = "guidePulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "guidePulseValue",
    )
    Canvas(Modifier.fillMaxSize()) {
        val path = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            if (hole != null) addRoundRect(RoundRect(hole, CornerRadius(radiusPx)))
        }
        drawPath(path, Color.Black.copy(alpha = ScrimAlpha))
        if (hole != null) {
            val p = pulse.value
            drawRoundRect(
                color = accent.copy(alpha = GuideAccentStrokeMin + (1f - GuideAccentStrokeMin) * p),
                topLeft = hole.topLeft,
                size = hole.size,
                cornerRadius = CornerRadius(radiusPx),
                style = Stroke(width = (2f + p) * density),
            )
        }
    }
}

/** Positions an element exactly on [rect] (overlay pixels). */
private fun Modifier.placeAt(rect: Rect): Modifier = this
    .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
    .layout { measurable, _ ->
        val width = rect.width.roundToInt().coerceAtLeast(0)
        val height = rect.height.roundToInt().coerceAtLeast(0)
        val placeable = measurable.measure(Constraints.fixed(width, height))
        layout(width, height) { placeable.place(0, 0) }
    }

/** Swallows taps; vertical drags scroll [scrollable] (Flutter `_forwardDrag` / `_forwardFling`). */
private suspend fun PointerInputScope.blockAndForwardDrags(scrollable: ScrollableState?, scope: CoroutineScope) {
    val flingThreshold = 200.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        val tracker = VelocityTracker()
        tracker.addPointerInputChange(down)
        val afterSlop = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
            change.consume()
            tracker.addPointerInputChange(change)
            scrollable?.dispatchRawDelta(-overSlop)
        }
        if (afterSlop != null) {
            verticalDrag(afterSlop.id) { change ->
                tracker.addPointerInputChange(change)
                scrollable?.dispatchRawDelta(-change.positionChange().y)
                change.consume()
            }
            val velocity = tracker.calculateVelocity().y
            if (scrollable != null && abs(velocity) >= flingThreshold) {
                scope.launch {
                    scrollable.animateScrollBy(-velocity * 0.25f, tween(320, easing = EaseOutCubic))
                }
            }
        }
    }
}

private val EaseOutCubic = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

private suspend fun PointerInputScope.swallowAll() {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false).consume()
        do {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

@Composable
private fun TooltipPositioner(
    geometry: SpotlightGeometry,
    hole: Rect?,
    screen: Size,
    card: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val placement = geometry.tooltipPlacement(
        hole = hole,
        screen = screen,
        paddingTop = insets.getTop(density).toFloat(),
        paddingBottom = insets.getBottom(density).toFloat(),
        imeBottom = WindowInsets.ime.getBottom(density).toFloat(),
    )
    val maxWidth = with(density) { placement.maxWidth.toDp() }
    val maxHeight = with(density) { placement.maxHeight.toDp() }
    val cardSize = Modifier
        .widthIn(max = maxWidth)
        .fillMaxWidth()
        .heightIn(max = maxHeight)
    Box(Modifier.fillMaxSize()) {
        // Position first, then constrain and scroll: a scroll container clips its content, so
        // an offset applied inside it would cut the card off.
        val positioned = when (placement) {
            is SpotlightGeometry.Placement.Centered -> Modifier.align(Alignment.Center)
            is SpotlightGeometry.Placement.Below -> Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, placement.top.roundToInt()) }
            is SpotlightGeometry.Placement.Above -> Modifier
                .align(Alignment.BottomCenter)
                .offset { IntOffset(0, -placement.bottom.roundToInt()) }
        }
        Box(positioned.then(cardSize).verticalScroll(rememberScrollState())) { card() }
    }
}

@Composable
private fun TooltipCard(controller: GuideController, state: GuideState, step: GuideStep, complete: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val onCard = scheme.onSurface
    // "Erledigt" only where there was something to do.
    val hintDone = step.completeWhen != null && complete
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainerHighest,
        border = BorderStroke(2.dp, scheme.primary),
        shadowElevation = 8.dp,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Lightbulb, contentDescription = null, tint = scheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(step.title),
                    style = MaterialTheme.typography.titleLarge,
                    color = onCard,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(L10nR.string.guideStepOf, state.currentStepIndex + 1, state.totalSteps),
                color = onCard.copy(alpha = 0.38f),
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(step.body),
                style = MaterialTheme.typography.bodyMedium,
                color = onCard.copy(alpha = 0.7f),
            )
            step.hint?.let { hint ->
                Spacer(Modifier.height(14.dp))
                val doneColor = MaterialTheme.extendedColors.success
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        if (hintDone) Icons.Rounded.CheckCircle else Icons.Rounded.TouchApp,
                        contentDescription = null,
                        tint = if (hintDone) doneColor else scheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(if (hintDone) L10nR.string.guideStepDone else hint),
                        color = if (hintDone) doneColor else scheme.primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!state.isFirstStep) {
                    TextButton(onClick = controller::previousStep) {
                        Text(stringResource(L10nR.string.guideBack), color = onCard.copy(alpha = 0.54f))
                    }
                }
                TextButton(onClick = controller::endTour) {
                    Text(stringResource(L10nR.string.guideFinishTour), color = onCard.copy(alpha = 0.54f))
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = controller::nextStep,
                    enabled = complete,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.primary,
                        contentColor = scheme.onPrimary,
                        disabledContainerColor = onCard.copy(alpha = 0.12f),
                        disabledContentColor = onCard.copy(alpha = 0.38f),
                    ),
                ) {
                    Text(stringResource(L10nR.string.actionContinue))
                }
            }
        }
    }
}

@Composable
private fun SkipChip(controller: GuideController, geometry: SpotlightGeometry, hole: Rect?, screen: Size) {
    val density = LocalDensity.current
    val statusTop = WindowInsets.systemBars.union(WindowInsets.displayCutout).getTop(density).toFloat()
    if (!geometry.isSkipChipVisible(hole, geometry.skipChipRect(screen.width, statusTop))) return
    Box(Modifier.fillMaxSize()) {
        Surface(
            onClick = controller::endTour,
            shape = RoundedCornerShape(20.dp),
            color = Color.Black.copy(alpha = 0.55f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = with(density) { statusTop.toDp() } + 8.dp, end = 12.dp),
        ) {
            Text(
                stringResource(L10nR.string.guideSkip),
                color = Color.White.copy(alpha = 0.7f),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Preview(name = "GuideOverlay", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun GuideOverlayPreview() {
    EinundzwanzigTheme {
        val scope = rememberCoroutineScope()
        val controller = remember(scope) { GuideController(InMemoryTourProgressStore(), scope) }
        LaunchedEffect(controller) {
            controller.startTour(
                GuideTour.Home,
                listOf(
                    GuideStep(
                        targetId = "preview.target",
                        title = L10nR.string.guideHomeGlossaryTitle,
                        body = L10nR.string.guideHomeGlossaryBody,
                        hint = L10nR.string.guideHomeSettingsBackupHint,
                    ),
                ),
                force = true,
            )
        }
        GuideHost(controller) {
            Box(Modifier.fillMaxSize()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .padding(top = 120.dp, start = 24.dp)
                        .size(140.dp, 80.dp)
                        .guideAnchor("preview.target"),
                ) {
                    Text("Glossar", Modifier.padding(16.dp))
                }
            }
        }
    }
}
