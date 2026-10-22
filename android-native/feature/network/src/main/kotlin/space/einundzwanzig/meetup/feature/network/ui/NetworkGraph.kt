package space.einundzwanzig.meetup.feature.network.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.einundzwanzig.meetup.core.ui.haptics.rememberHaptics
import space.einundzwanzig.meetup.core.ui.motion.rememberReducedMotion
import space.einundzwanzig.meetup.core.ui.theme.Dimens
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.socialgraph.coattendance.MyNetwork
import space.einundzwanzig.meetup.feature.network.NetworkPresentation
import space.einundzwanzig.meetup.feature.network.NetworkPresentation.TreeNode
import space.einundzwanzig.meetup.feature.network.R
import kotlin.math.min
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/**
 * Graph card: the tree around "Du" (Dart `_graphCard` + `_TreePainter`). Tapping a node gives a
 * light haptic and opens its detail; while a detail is open its path is highlighted.
 */
@Composable
internal fun GraphCard(
    net: MyNetwork,
    names: Map<String, String>,
    highlight: String?,
    onNodeTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(colors.surfaceContainerHigh, colors.surface)))
            .border(Dimens.Hairline, colors.outlineVariant, shape),
    ) {
        TreeCanvas(net, names, highlight, onNodeTap)
        Row(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
            val d1 = net.degree1Count
            if (d1 > NetworkPresentation.GRAPH_DIRECT) {
                Text(
                    stringResource(L10nR.string.mnGraphExcerpt, min(d1, NetworkPresentation.GRAPH_DIRECT), d1),
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                stringResource(L10nR.string.mnTapShowsPath),
                fontSize = 12.sp,
                color = MaterialTheme.extendedColors.textTertiary,
            )
        }
    }
}

@Composable
private fun TreeCanvas(
    net: MyNetwork,
    names: Map<String, String>,
    highlight: String?,
    onNodeTap: (String) -> Unit,
) {
    val density = LocalDensity.current.density
    val haptics = rememberHaptics()
    val reduced = rememberReducedMotion()
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulseState = transition.animateFloat(
        initialValue = 0f,
        targetValue = if (reduced) 0f else 1f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val highlightSet = remember(net, highlight) {
        if (highlight == null) emptySet() else net.pathTo(highlight).toSet()
    }
    val measurer = rememberTextMeasurer()
    val palette = GraphPalette(
        d1 = degreeColor(1),
        d2 = degreeColor(2),
        d3 = degreeColor(3),
        me = MaterialTheme.colorScheme.primary,
        onMe = MaterialTheme.colorScheme.onPrimary,
        nodeFill = MaterialTheme.colorScheme.surfaceContainer,
        text = MaterialTheme.colorScheme.onSurface,
        dot = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val you = stringResource(L10nR.string.mnYou)
    val description = pluralStringResource(R.plurals.native_network_graph_description, net.degree1Count, net.degree1Count)
    val tap by rememberUpdatedState(onNodeTap)
    val latestNet by rememberUpdatedState(net)
    val latestNames by rememberUpdatedState(names)
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(300.dp)
            .testTag("graph")
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    // Same layout as drawn, computed from the current size so a tap never depends
                    // on a previous draw pass.
                    val nodes = NetworkPresentation.layoutTree(
                        latestNet, latestNames, size.width.toFloat(), size.height.toFloat(), density,
                    )
                    val hit = NetworkPresentation.hitTest(nodes, pos.x, pos.y, 24.dp.toPx())
                    if (hit != null) {
                        haptics.light()
                        tap(hit.npub)
                    }
                }
            }
            .drawWithCache {
                val nodes = NetworkPresentation.layoutTree(net, names, size.width, size.height, density)
                onDrawBehind {
                    drawTree(nodes, highlightSet, pulseState.value, palette, measurer, you, density)
                }
            },
    )
}

private data class GraphPalette(
    val d1: androidx.compose.ui.graphics.Color,
    val d2: androidx.compose.ui.graphics.Color,
    val d3: androidx.compose.ui.graphics.Color,
    val me: androidx.compose.ui.graphics.Color,
    val onMe: androidx.compose.ui.graphics.Color,
    val nodeFill: androidx.compose.ui.graphics.Color,
    val text: androidx.compose.ui.graphics.Color,
    val dot: androidx.compose.ui.graphics.Color,
) {
    fun of(degree: Int) = when (degree) {
        1 -> d1
        2 -> d2
        else -> d3
    }
}

private fun DrawScope.drawTree(
    nodes: List<TreeNode>,
    highlight: Set<String>,
    pulse: Float,
    p: GraphPalette,
    measurer: TextMeasurer,
    you: String,
    density: Float,
) {
    if (nodes.isEmpty()) return
    val dimming = highlight.isNotEmpty()
    fun on(n: TreeNode) = !dimming || n.npub in highlight

    // 1. Lines, deeper degrees first.
    for (deg in intArrayOf(3, 2, 1)) {
        for (n in nodes) {
            if (n.degree != deg || n.fromX == null || n.fromY == null) continue
            val active = on(n)
            val width = when (deg) {
                1 -> 1.6f
                2 -> 1.2f
                else -> 1.0f
            }
            val base = when (deg) {
                1 -> 0.6f
                2 -> 0.5f
                else -> 0.4f
            }
            drawLine(
                color = p.of(deg).copy(alpha = if (dimming) (if (active) 1f else 0.12f) else base),
                start = Offset(n.fromX, n.fromY),
                end = Offset(n.x, n.y),
                strokeWidth = (if (dimming && active) 3f else width) * density,
                cap = StrokeCap.Round,
            )
        }
    }

    // 2. Nodes.
    val target = NetworkPresentation.targetNpub(nodes, highlight)
    for (deg in intArrayOf(3, 2, 1)) {
        for (n in nodes) {
            if (n.degree != deg) continue
            val active = on(n)
            val color = p.of(deg)
            val alpha = if (dimming && !active) 0.18f else 1f
            val isTarget = dimming && active && n.npub == target
            val c = Offset(n.x, n.y)
            if (isTarget) drawCircle(color.copy(alpha = 0.25f), n.radius + 8 * density, c)
            if (deg == 1) {
                drawCircle(p.nodeFill.copy(alpha = alpha), n.radius, c)
                drawCircle(
                    color.copy(alpha = alpha),
                    n.radius,
                    c,
                    style = Stroke(width = (if (dimming && active) 2.5f else 2f) * density),
                )
                if (n.initial.isNotEmpty()) {
                    drawCentered(
                        measurer,
                        n.initial,
                        c,
                        TextStyle(color = p.text.copy(alpha = alpha), fontSize = 13.sp, fontWeight = FontWeight.Bold),
                    )
                } else {
                    drawCircle(p.dot.copy(alpha = alpha), 3 * density, c)
                }
            } else {
                val r = if (isTarget) n.radius + 3 * density else n.radius
                drawCircle(color.copy(alpha = alpha), r, c)
                if (isTarget) drawCircle(p.text, r, c, style = Stroke(width = 2 * density))
            }
        }
    }

    // 3. "Du" in the centre with a pulsing glow.
    val me = nodes.first()
    val mc = Offset(me.x, me.y)
    val glowR = me.radius + (6 + pulse * 3) * density
    drawCircle(
        Brush.radialGradient(
            0f to p.me.copy(alpha = 0.14f + pulse * 0.08f),
            0.7f to p.me.copy(alpha = (0.14f + pulse * 0.08f) * 0.6f),
            1f to p.me.copy(alpha = 0f),
            center = mc,
            radius = glowR + 6 * density,
        ),
        glowR + 6 * density,
        mc,
    )
    drawCircle(p.me, me.radius, mc)
    drawCentered(measurer, you, mc, TextStyle(color = p.onMe, fontSize = 14.sp, fontWeight = FontWeight.Bold))
}

private fun DrawScope.drawCentered(measurer: TextMeasurer, text: String, center: Offset, style: TextStyle) {
    val layout = measurer.measure(text, style)
    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}
