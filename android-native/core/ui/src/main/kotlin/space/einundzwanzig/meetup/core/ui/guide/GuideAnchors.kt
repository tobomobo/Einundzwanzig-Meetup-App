package space.einundzwanzig.meetup.core.ui.guide

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.modifier.ModifierLocalModifierNode
import androidx.compose.ui.modifier.modifierLocalMapOf
import androidx.compose.ui.modifier.modifierLocalOf
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.relocation.bringIntoView
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Registry of guide targets ("anchors") of one app. Targets register through
 * [Modifier.guideAnchor]; overlays look them up by id. Provide one instance with
 * [LocalGuideTargetRegistry] (done by [GuideHost]). Main thread only.
 */
class GuideTargetRegistry {
    private val anchors = LinkedHashMap<String, MutableList<GuideAnchorHandle>>()
    internal var tapListener: ((String) -> Unit)? = null

    /** Whether an anchor with [id] is currently attached. */
    fun isRegistered(id: String): Boolean = !anchors[id].isNullOrEmpty()

    /** The most recently attached anchor for [id]. */
    internal fun anchor(id: String): GuideAnchorHandle? = anchors[id]?.lastOrNull()

    internal fun register(id: String, handle: GuideAnchorHandle) {
        anchors.getOrPut(id) { mutableListOf() } += handle
    }

    internal fun unregister(id: String, handle: GuideAnchorHandle) {
        val list = anchors[id] ?: return
        list -= handle
        if (list.isEmpty()) anchors.remove(id)
    }

    internal fun reportTap(id: String) {
        tapListener?.invoke(id)
    }
}

/** The registry anchors register with; null outside of a [GuideHost] (anchors are inert). */
val LocalGuideTargetRegistry = staticCompositionLocalOf<GuideTargetRegistry?> { null }

/** What the overlay needs from an anchor. */
internal interface GuideAnchorHandle {
    /** Layout coordinates of the anchored element, null before the first placement. */
    val coordinates: LayoutCoordinates?

    /** False while the anchor's screen is not resumed (another screen is on top). */
    val isResumed: Boolean

    /** Scroll container to forward barrier drags to. */
    val scrollable: ScrollableState?

    /** Scrolls all enclosing scroll containers so the anchor plus [marginPx] is visible. */
    suspend fun bringIntoView(marginPx: Float)
}

/**
 * Marks this element as the spotlight target [id] of guide tours (Flutter `GlobalKey`
 * targets). The element reports its position continuously; taps on it are observed (not
 * consumed) for steps with `advanceOnTargetTap`.
 *
 * @param scrollable the scroll container barrier drags are forwarded to; defaults to the
 *   nearest [guideScrollContainer] ancestor.
 */
fun Modifier.guideAnchor(id: String, scrollable: ScrollableState? = null): Modifier =
    this then GuideAnchorElement(id, scrollable)

/**
 * Declares [state] as the scroll container of all guide anchors inside this element, so that
 * vertical drags on the tour's blocked area still scroll the list (Flutter forwarded them to
 * the target's `Scrollable`).
 */
fun Modifier.guideScrollContainer(state: ScrollableState): Modifier = this then GuideScrollContainerElement(state)

private val ModifierLocalGuideScrollable = modifierLocalOf<ScrollableState?> { null }

private data class GuideScrollContainerElement(val state: ScrollableState) : ModifierNodeElement<GuideScrollContainerNode>() {
    override fun create() = GuideScrollContainerNode(state)
    override fun update(node: GuideScrollContainerNode) {
        node.update(state)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "guideScrollContainer"
    }
}

private class GuideScrollContainerNode(state: ScrollableState) : Modifier.Node(), ModifierLocalModifierNode {
    override val providedValues = modifierLocalMapOf(ModifierLocalGuideScrollable to state)

    fun update(state: ScrollableState) {
        provide(ModifierLocalGuideScrollable, state)
    }
}

private data class GuideAnchorElement(val id: String, val scrollable: ScrollableState?) : ModifierNodeElement<GuideAnchorNode>() {
    override fun create() = GuideAnchorNode(id, scrollable)
    override fun update(node: GuideAnchorNode) {
        node.update(id, scrollable)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "guideAnchor"
        properties["id"] = id
    }
}

private class GuideAnchorNode(
    private var id: String,
    private var explicitScrollable: ScrollableState?,
) : Modifier.Node(),
    GlobalPositionAwareModifierNode,
    CompositionLocalConsumerModifierNode,
    PointerInputModifierNode,
    ModifierLocalModifierNode,
    ObserverModifierNode,
    GuideAnchorHandle {

    private var registry: GuideTargetRegistry? = null
    private var layoutCoordinates: LayoutCoordinates? = null

    override val coordinates: LayoutCoordinates? get() = layoutCoordinates?.takeIf { isAttached && it.isAttached }

    override val isResumed: Boolean
        get() = isAttached && currentValueOf(LocalLifecycleOwner).lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    override val scrollable: ScrollableState?
        get() = explicitScrollable ?: if (isAttached) ModifierLocalGuideScrollable.current else null

    override fun onAttach() = onObservedReadsChanged()

    /** (Re-)registers with the registry provided at this position; follows provider changes. */
    override fun onObservedReadsChanged() {
        observeReads {
            val provided = currentValueOf(LocalGuideTargetRegistry)
            if (provided !== registry) {
                registry?.unregister(id, this)
                registry = provided
                provided?.register(id, this)
            }
        }
    }

    override fun onDetach() {
        registry?.unregister(id, this)
        registry = null
        layoutCoordinates = null
    }

    fun update(newId: String, newScrollable: ScrollableState?) {
        explicitScrollable = newScrollable
        if (newId != id) {
            registry?.unregister(id, this)
            id = newId
            registry?.register(id, this)
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        layoutCoordinates = coordinates
    }

    override suspend fun bringIntoView(marginPx: Float) {
        if (!isAttached) return
        bringIntoView {
            val size = layoutCoordinates?.size ?: IntSize.Zero
            Rect(0f, -marginPx, size.width.toFloat(), size.height + marginPx)
        }
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Main || pointerEvent.type != PointerEventType.Release) return
        if (pointerEvent.changes.any { it.pressed }) return
        val inside = pointerEvent.changes.any { change ->
            val p = change.position
            p.x >= 0f && p.y >= 0f && p.x <= bounds.width && p.y <= bounds.height
        }
        if (inside) registry?.reportTap(id)
    }

    override fun onCancelPointerInput() = Unit
}
