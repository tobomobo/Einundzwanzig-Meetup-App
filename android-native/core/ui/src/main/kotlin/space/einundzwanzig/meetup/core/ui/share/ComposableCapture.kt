package space.einundzwanzig.meetup.core.ui.share

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.ui.util.findActivity
import kotlin.math.roundToInt

/**
 * Renders composables offscreen into a [Bitmap] (Flutter: `RepaintBoundary.toImage`), e.g. the
 * 360 × 560 badge share card or the reputation card at pixel ratio 3.
 *
 * The content is composed in a temporary, invisible view attached to the activity window and
 * inherits the caller's composition locals (theme, strings, profile source). Inside it
 * [LocalDensity] is `pixelRatio` (so `width` dp become `width · pixelRatio` px) and
 * [LocalAllowHardwareBitmaps] is false (software canvas).
 *
 * Obtain one with [rememberComposableCapturer].
 */
class ComposableCapturer internal constructor(
    private val activity: Activity,
    private val parentContext: CompositionContext,
) {
    /**
     * Composes [content] at [width] × [height] dp, waits for it to settle and draws it.
     *
     * @param pixelRatio output pixels per dp (Flutter `toImage(pixelRatio: 3.0)`).
     * @param settleMillis extra time after the first frames for async content (network
     *   images). Pre-load images (Coil `ImageLoader.execute`) for deterministic results.
     */
    suspend fun capture(
        width: Dp,
        height: Dp,
        pixelRatio: Float = 3f,
        settleMillis: Long = DEFAULT_SETTLE_MS,
        content: @Composable () -> Unit,
    ): Bitmap = withContext(Dispatchers.Main) {
        val widthPx = (width.value * pixelRatio).roundToInt().coerceAtLeast(1)
        val heightPx = (height.value * pixelRatio).roundToInt().coerceAtLeast(1)
        val root = activity.window.decorView as ViewGroup
        val view = ComposeView(activity).apply {
            setParentCompositionContext(parentContext)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(pixelRatio, fontScale = 1f),
                    LocalAllowHardwareBitmaps provides false,
                    content = content,
                )
            }
        }
        root.addView(view, FrameLayout.LayoutParams(widthPx, heightPx))
        try {
            repeat(SETTLE_FRAMES) { awaitFrame() }
            if (settleMillis > 0) delay(settleMillis)
            awaitFrame()
            view.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, widthPx, heightPx)
            val bitmap = createBitmap(widthPx, heightPx)
            view.draw(Canvas(bitmap))
            bitmap
        } finally {
            root.removeView(view)
        }
    }

    private companion object {
        const val DEFAULT_SETTLE_MS = 350L
        const val SETTLE_FRAMES = 3
    }
}

/**
 * A [ComposableCapturer] bound to the current activity and composition.
 *
 * @throws IllegalStateException if not hosted in an activity.
 */
@Composable
fun rememberComposableCapturer(): ComposableCapturer {
    val context = LocalContext.current
    val parent = rememberCompositionContext()
    return remember(context, parent) {
        val activity = checkNotNull(context.findActivity()) { "ComposableCapturer needs an Activity" }
        ComposableCapturer(activity, parent)
    }
}
