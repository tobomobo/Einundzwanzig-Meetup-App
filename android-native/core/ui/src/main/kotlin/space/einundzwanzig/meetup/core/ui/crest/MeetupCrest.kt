package space.einundzwanzig.meetup.core.ui.crest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import space.einundzwanzig.meetup.core.ui.badge.BadgeArt
import space.einundzwanzig.meetup.core.ui.share.LocalAllowHardwareBitmaps
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import kotlin.math.roundToInt

/** Geometry of [MeetupCrestWatermark] (pure, see spec 03 §2.5). */
object CrestWatermarkGeometry {
    /** Horizontal part of Flutter `Alignment(0.15, -0.25)`. */
    const val ALIGN_X = 0.15f

    /** Vertical part of Flutter `Alignment(0.15, -0.25)`. */
    const val ALIGN_Y = -0.25f

    /** Radial fade radius as a fraction of the image side. */
    const val FADE_RADIUS = 0.62f

    /** The fade stays opaque up to this gradient stop, then runs out to transparent at 1. */
    const val FADE_SOLID_STOP = 0.55f

    /** Side of the square image box for a parent of [parentWidth] (`W × widthFactor`). */
    fun side(parentWidth: Float, widthFactor: Float): Float = parentWidth * widthFactor

    /**
     * Top-left of the image box: `left = (W − side)(1 + 0.15) / 2`, `top = (H − side)(1 − 0.25) / 2`.
     * May be negative; the parent clips the overflow.
     */
    fun offset(parentWidth: Float, parentHeight: Float, side: Float): Offset = Offset(
        x = (parentWidth - side) * (1 + ALIGN_X) / 2f,
        y = (parentHeight - side) * (1 + ALIGN_Y) / 2f,
    )
}

/**
 * Data seam for [MeetupCrestWatermark] and [MeetupCrestFace]: the portal logo registry lives in
 * `:data:meetups` (`CrestRepository`) and is provided once by the app shell through
 * [LocalMeetupCrestSource]. The UI here never talks to the portal itself.
 */
interface MeetupCrestSource {
    /**
     * The absolute crest URL for a meetup (spec 03 §2.5 URL resolution): [imageUrl] trimmed and
     * absolutized if non-blank, else `absoluteImageUrl(logoFor(city))` with
     * `city = meetupName.split(',').first().trim()`; null/empty when nothing is known (also for
     * an empty city). Re-emits when the logo registry changes, so faces and watermarks update as
     * soon as the logos arrive.
     */
    fun crestUrl(meetupName: String, imageUrl: String?): Flow<String?>

    /**
     * Triggers the process-wide single logo fetch if the registry is still empty (at most one
     * in flight; Flutter `CrestRepository.ensureLoaded()` behind `ensureLogos`). Must not throw.
     */
    fun ensureLoaded()

    companion object {
        /** No registry: only an explicit image URL is shown (unchanged, not absolutized). */
        val None: MeetupCrestSource = object : MeetupCrestSource {
            override fun crestUrl(meetupName: String, imageUrl: String?): Flow<String?> =
                flowOf(imageUrl?.trim()?.takeIf { it.isNotEmpty() })

            override fun ensureLoaded() = Unit
        }
    }
}

/** The app-wide [MeetupCrestSource]; [MeetupCrestSource.None] until the app provides one. */
val LocalMeetupCrestSource = staticCompositionLocalOf { MeetupCrestSource.None }

@Composable
private fun rememberCrestUrl(meetupName: String, imageUrl: String?): String? {
    val source = LocalMeetupCrestSource.current
    val flow = remember(source, meetupName, imageUrl) { source.crestUrl(meetupName, imageUrl) }
    return flow.collectAsState(initial = null).value
}

/**
 * The meetup crest as a soft watermark behind the generative art (port of
 * `MeetupCrestWatermark`, spec 03 §2.5). Resolves the URL through [LocalMeetupCrestSource]
 * and, on first composition, triggers the shared logo fetch when [imageUrl] is blank
 * (`ensureLogos`). Rendering: see [CrestWatermarkImage].
 *
 * | usage | opacity | widthFactor |
 * |---|---|---|
 * | wallet card | 0.20 | 1.05 |
 * | compact card | 0.17 | 0.95 |
 * | collection card (52 × 52) | 1.0 | 1.0 |
 * | share card | 0.26 | 1.05 |
 */
@Composable
fun MeetupCrestWatermark(
    meetupName: String,
    modifier: Modifier = Modifier,
    opacity: Float = 0.20f,
    widthFactor: Float = 1.05f,
    imageUrl: String? = null,
) {
    val source = LocalMeetupCrestSource.current
    LaunchedEffect(source, imageUrl.isNullOrBlank()) {
        if (imageUrl.isNullOrBlank()) source.ensureLoaded()
    }
    CrestWatermarkImage(rememberCrestUrl(meetupName, imageUrl), modifier, opacity, widthFactor)
}

/**
 * The crest as the subject itself (port of `MeetupCrestFace`), e.g. in the badge-details
 * medallion. Resolves the URL through [LocalMeetupCrestSource] (own [imageUrl] first, then the
 * city crest) but does **not** trigger the logo fetch, like Flutter. Shows [fallback] when no
 * URL is known, while loading and on error.
 */
@Composable
fun MeetupCrestFace(
    meetupName: String,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    fallback: @Composable () -> Unit,
) {
    CrestFaceImage(rememberCrestUrl(meetupName, imageUrl), modifier, contentDescription, fallback)
}

/**
 * Pure rendering of the crest watermark for an already resolved [imageUrl].
 *
 * Fills its parent (W × H) and draws a `side = W · widthFactor` square image, `Fit`, aligned
 * at (0.15, −0.25), with [opacity] and a radial fade to transparent at the edges. Nothing is
 * drawn while loading, on error or for a blank URL — never a broken-image box.
 *
 * | usage | opacity | widthFactor |
 * |---|---|---|
 * | wallet card | 0.20 | 1.05 |
 * | compact card | 0.17 | 0.95 |
 * | collection card | 1.0 | 1.0 |
 * | share card | 0.26 | 1.05 |
 */
@Composable
fun CrestWatermarkImage(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    opacity: Float = 0.20f,
    widthFactor: Float = 1.05f,
) {
    val url = imageUrl?.trim().orEmpty()
    if (url.isEmpty()) return
    val context = LocalContext.current
    val allowHardware = LocalAllowHardwareBitmaps.current
    val request = remember(url, allowHardware) {
        ImageRequest.Builder(context).data(url).allowHardware(allowHardware).build()
    }
    Layout(
        content = {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        alpha = opacity
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.radialGradient(
                                0f to Color.White,
                                CrestWatermarkGeometry.FADE_SOLID_STOP to Color.White,
                                1f to Color.Transparent,
                                center = center,
                                radius = CrestWatermarkGeometry.FADE_RADIUS * size.minDimension,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            )
        },
        modifier = modifier
            .fillMaxSize()
            .clipToBounds(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth.toFloat()
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else width
        val side = CrestWatermarkGeometry.side(width, widthFactor).roundToInt().coerceAtLeast(0)
        val placeable = measurables.single().measure(Constraints.fixed(side, side))
        val offset = CrestWatermarkGeometry.offset(width, height, side.toFloat())
        layout(constraints.maxWidth, height.roundToInt()) {
            placeable.place(offset.x.roundToInt(), offset.y.roundToInt())
        }
    }
}

/**
 * Pure rendering of the crest face for an already resolved absolute [imageUrl]: [fallback]
 * when it is blank, while loading and on error; the image fits the box.
 */
@Composable
fun CrestFaceImage(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    fallback: @Composable () -> Unit,
) {
    val url = imageUrl?.trim().orEmpty()
    if (url.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { fallback() }
        return
    }
    val context = LocalContext.current
    val allowHardware = LocalAllowHardwareBitmaps.current
    val request = remember(url, allowHardware) {
        ImageRequest.Builder(context).data(url).allowHardware(allowHardware).build()
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier,
        loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { fallback() } },
        error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { fallback() } },
    )
}

/** Up to two initials of a meetup name ("Bad Kissingen, DE" → "BK", "Wien" → "WI"). */
fun crestInitials(meetupName: String): String {
    val city = meetupName.substringBefore(',').trim()
    val words = city.split(Regex("[\\s-]+")).filter { it.isNotEmpty() }
    val initials = when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2)
        else -> words.take(2).joinToString("") { it.take(1) }
    }
    return initials.uppercase()
}

/** Monogram fallback for [MeetupCrestFace]: initials on a tinted container. */
@Composable
fun CrestMonogram(meetupName: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            crestInitials(meetupName),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 20.sp,
        )
    }
}

@Preview(name = "MeetupCrest", backgroundColor = 0xFF0C0C0E, showBackground = true)
@Composable
private fun MeetupCrestPreview() {
    EinundzwanzigTheme {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(140.dp, 180.dp)
                    .clip(RoundedCornerShape(10.dp)),
            ) {
                BadgeArt("Aschaffenburg:875000", Modifier.fillMaxSize())
                MeetupCrestWatermark("Aschaffenburg, DE", imageUrl = "https://portal.einundzwanzig.space/storage/logo.png")
            }
            MeetupCrestFace("Bad Kissingen, DE", imageUrl = null, modifier = Modifier.size(64.dp)) {
                CrestMonogram("Bad Kissingen, DE", Modifier.size(64.dp))
            }
        }
    }
}
