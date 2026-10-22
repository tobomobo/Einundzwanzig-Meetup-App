package space.einundzwanzig.meetup.core.map

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import space.einundzwanzig.meetup.core.ui.motion.ExpressiveSprings
import space.einundzwanzig.meetup.core.ui.motion.rememberReducedMotion

/** Overlay declarations inside [OsmMap]'s content lambda, drawn in declaration order. */
@Stable
class OsmMapScope internal constructor(private val camera: OsmCameraState) {

    /**
     * A composable marker whose [anchor] point sits on [position] (flutter_map `Marker`, which
     * centres by default). It pops in with an expressive spring (static with reduced motion).
     * With [onClick] it is a button with an unbounded ripple; touch targets below 48 dp are
     * enlarged by Compose automatically.
     */
    @Composable
    fun Marker(
        position: LatLng,
        modifier: Modifier = Modifier,
        anchor: MarkerAnchor = MarkerAnchor.Center,
        contentDescription: String? = null,
        onClick: (() -> Unit)? = null,
        content: @Composable () -> Unit,
    ) {
        val reducedMotion = rememberReducedMotion()
        val appear = remember { Animatable(if (reducedMotion) 1f else 0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, ExpressiveSprings.fastSpatial()) }
        val click = if (onClick != null) {
            Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false),
                role = Role.Button,
                onClick = onClick,
            )
        } else {
            Modifier
        }
        Box(
            Modifier
                .layoutId(MarkerSlot(position, anchor))
                .graphicsLayer {
                    scaleX = appear.value
                    scaleY = appear.value
                    transformOrigin = TransformOrigin(anchor.x, anchor.y)
                }
                .semantics(mergeDescendants = true) {
                    if (contentDescription != null) this.contentDescription = contentDescription
                }
                .then(click)
                .then(modifier),
        ) { content() }
    }

    /**
     * A circle with a radius in metres on the ground (flutter_map `CircleMarker(useRadiusInMeter:
     * true)`), e.g. the nearby search radius.
     */
    @Composable
    fun Circle(
        center: LatLng,
        radiusMeters: Double,
        fillColor: Color,
        strokeColor: Color,
        strokeWidth: Dp = 1.5.dp,
    ) {
        Spacer(
            Modifier.drawBehind {
                val point = camera.toScreen(center) ?: return@drawBehind
                val radius = WebMercator.metersToPixels(
                    radiusMeters,
                    center.latitude,
                    camera.position.zoom,
                    camera.tileSizePx,
                ).toFloat()
                val c = Offset(point.x.toFloat(), point.y.toFloat())
                drawCircle(fillColor, radius, c)
                if (strokeWidth > 0.dp) drawCircle(strokeColor, radius, c, style = Stroke(strokeWidth.toPx()))
            },
        )
    }
}

/** Colour filters for [MapTileStyle]. */
internal object TileFilters {
    /** Inverts lightness (negative), then rotates hue by 180° so water stays blue and parks green. */
    private val dark: ColorMatrixColorFilter by lazy {
        val invert = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        // CSS hue-rotate(180deg).
        val hue = ColorMatrix(
            floatArrayOf(
                -0.574f, 1.430f, 0.144f, 0f, 0f,
                0.426f, 0.430f, 0.144f, 0f, 0f,
                0.426f, 1.430f, -0.856f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        val dim = ColorMatrix().apply { setScale(0.85f, 0.85f, 0.85f, 1f) }
        invert.postConcat(hue)
        invert.postConcat(dim)
        ColorMatrixColorFilter(invert)
    }

    fun forStyle(style: MapTileStyle): ColorMatrixColorFilter? = when (style) {
        MapTileStyle.Standard -> null
        MapTileStyle.Dark -> dark
    }
}
