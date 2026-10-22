package space.einundzwanzig.meetup.feature.reputation.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import space.einundzwanzig.meetup.core.ui.share.ComposableCapturer
import space.einundzwanzig.meetup.core.ui.share.ShareHelper

/** Share subject of both reputation images (brand literal, spec 04 §2.1.1, §2.2). */
internal const val SHARE_SUBJECT = "Einundzwanzig Reputation"

/** File name of the QR card PNG (spec 04 §2.1.1). */
internal const val QR_SHARE_FILE = "einundzwanzig_reputation.png"

/** File name of the profile card PNG (spec 04 §2.2). */
internal const val CARD_SHARE_FILE = "einundzwanzig_reputation_card.png"

/** Pixel ratio of the shared PNGs (Flutter `toImage(pixelRatio: 3.0)`). */
private const val SHARE_PIXEL_RATIO = 3f

/**
 * Renders [content] offscreen at [width] × [height] dp with pixel ratio 3, writes it to
 * `cacheDir/share/[fileName]` and opens the share sheet (FileProvider URI, read grant,
 * subject and text; spec 04 §2.1.1 steps 2–3, S-12).
 *
 * @throws Exception when capturing or writing fails; callers show the error snackbar.
 */
internal suspend fun shareCapturedCard(
    context: Context,
    capturer: ComposableCapturer,
    width: Dp,
    height: Dp,
    fileName: String,
    text: String,
    content: @Composable () -> Unit,
): Boolean {
    val bitmap = capturer.capture(width, height, pixelRatio = SHARE_PIXEL_RATIO, content = content)
    return try {
        ShareHelper.shareImage(context, bitmap, fileName, subject = SHARE_SUBJECT, text = text)
    } finally {
        bitmap.recycle()
    }
}
