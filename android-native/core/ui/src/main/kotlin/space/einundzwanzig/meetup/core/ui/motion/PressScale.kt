package space.einundzwanzig.meetup.core.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.Indication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.ui.theme.Dimens
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme

/** Defaults for [pressScale] and [Pressable]. */
object PressScaleDefaults {
    /** How far the element sinks while pressed (Flutter `Pressable.scaleDownTo`). */
    const val PressedScale: Float = 0.97f
}

/**
 * Sinks the element to [pressedScale] while [interactionSource] reports a press and springs
 * back on release (Material 3 Expressive fast-spatial spring instead of Flutter's
 * 100/80 ms cubic curves). Stays still when the system "remove animations" setting is on.
 *
 * Use with the same [MutableInteractionSource] that is passed to `clickable`.
 */
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = PressScaleDefaults.PressedScale,
    enabled: Boolean = true,
    animationSpec: AnimationSpec<Float> = ExpressiveSprings.fastSpatial(),
): Modifier = this then PressScaleElement(interactionSource, pressedScale, enabled, animationSpec)

private data class PressScaleElement(
    val interactionSource: InteractionSource,
    val pressedScale: Float,
    val enabled: Boolean,
    val animationSpec: AnimationSpec<Float>,
) : ModifierNodeElement<PressScaleNode>() {
    override fun create() = PressScaleNode(interactionSource, pressedScale, enabled, animationSpec)

    override fun update(node: PressScaleNode) {
        node.update(interactionSource, pressedScale, enabled, animationSpec)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "pressScale"
        properties["pressedScale"] = pressedScale
        properties["enabled"] = enabled
    }
}

private class PressScaleNode(
    private var interactionSource: InteractionSource,
    private var pressedScale: Float,
    private var enabled: Boolean,
    private var animationSpec: AnimationSpec<Float>,
) : Modifier.Node(), LayoutModifierNode, CompositionLocalConsumerModifierNode {

    private val scale = Animatable(1f)
    private val presses = mutableListOf<PressInteraction.Press>()
    private var collectJob: Job? = null

    override fun onAttach() = collect()

    override fun onDetach() {
        presses.clear()
        collectJob = null
    }

    fun update(source: InteractionSource, scaleTo: Float, isEnabled: Boolean, spec: AnimationSpec<Float>) {
        pressedScale = scaleTo
        enabled = isEnabled
        animationSpec = spec
        if (source != interactionSource) {
            interactionSource = source
            presses.clear()
            if (isAttached) collect()
        }
        if (isAttached) animateToTarget()
    }

    private fun collect() {
        collectJob?.cancel()
        collectJob = coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses += interaction
                    is PressInteraction.Release -> presses -= interaction.press
                    is PressInteraction.Cancel -> presses -= interaction.press
                }
                animateToTarget()
            }
        }
    }

    private fun animateToTarget() {
        // Read at press time: the system setting can change while the screen is open.
        val reducedMotion = isReducedMotion(currentValueOf(LocalContext))
        val target = if (enabled && !reducedMotion && presses.isNotEmpty()) pressedScale else 1f
        if (scale.targetValue == target) return
        coroutineScope.launch { scale.animateTo(target, animationSpec) }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                val s = scale.value
                scaleX = s
                scaleY = s
            }
        }
    }
}

/**
 * Port of Flutter's `Pressable`: a tap target that sinks slightly while pressed.
 *
 * Only claims the gestures it is given: without [onLongClick] a long press stays free for an
 * outer handler (the dashboard edit mode relies on this). Without any callback the content
 * is rendered as is. No ripple by default (parity); pass `ripple()` as [indication] for
 * Material feedback.
 */
@Composable
fun Pressable(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    pressedScale: Float = PressScaleDefaults.PressedScale,
    indication: Indication? = null,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    if (onClick == null && onLongClick == null) {
        Box(modifier, content = content)
        return
    }
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .pressScale(interactionSource, pressedScale, enabled)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = indication,
                enabled = enabled,
                onClickLabel = onClickLabel,
                role = role,
                onLongClick = onLongClick,
                hapticFeedbackEnabled = false,
                onClick = onClick ?: {},
            ),
        content = content,
    )
}

@Preview(name = "Pressable", backgroundColor = 0xFF0C0C0E, showBackground = true)
@Composable
private fun PressablePreview() {
    EinundzwanzigTheme {
        Pressable(onClick = {}, modifier = Modifier.padding(16.dp)) {
            Surface(shape = Dimens.TileShape, color = MaterialTheme.colorScheme.surfaceContainer) {
                Text("Drück mich", Modifier.padding(24.dp))
            }
        }
    }
}
