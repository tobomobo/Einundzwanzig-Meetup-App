package space.einundzwanzig.meetup.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import space.einundzwanzig.meetup.core.ui.R

val Rajdhani = FontFamily(
    Font(R.font.rajdhani_regular, FontWeight.Normal),
    Font(R.font.rajdhani_medium, FontWeight.Medium),
    Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
    Font(R.font.rajdhani_bold, FontWeight.Bold),
    Font(R.font.rajdhani_bold, FontWeight.ExtraBold),
)

/** Monospace face for npubs, hashes, sats amounts. */
val Inconsolata = FontFamily(
    Font(R.font.inconsolata, FontWeight.Normal),
    Font(R.font.inconsolata, FontWeight.Medium),
    Font(R.font.inconsolata, FontWeight.Bold),
)

private val base = Typography()

private fun TextStyle.brand(weight: FontWeight? = null) =
    copy(fontFamily = Rajdhani, fontWeight = weight ?: fontWeight)

/**
 * Material 3 type scale in Rajdhani. The slots the Flutter theme defines (`lib/theme.dart`,
 * spec 12 §2.11) use its exact sizes, weights, letter spacing and line heights so screens look
 * the same in both apps: displayLarge 34/w700/-0.5/1.1, displayMedium 26/w700/-0.3/1.15,
 * titleLarge 18/w700, titleMedium 15/w600, bodyLarge 15/1.5, bodyMedium 13/1.5,
 * bodySmall 11/1.4, labelSmall 10/w600/1.0. Colours are not baked in (Flutter's bodyMedium
 * secondary / bodySmall tertiary): pass `onSurfaceVariant` / `extendedColors.textTertiary`.
 * The remaining slots keep the M3 baseline in Rajdhani, nudged up ~1 sp because Rajdhani
 * runs small.
 */
internal val BrandTypography = Typography(
    displayLarge = base.displayLarge.brand(FontWeight.Bold)
        .copy(fontSize = 34.sp, letterSpacing = (-0.5).sp, lineHeight = 37.4.sp),
    displayMedium = base.displayMedium.brand(FontWeight.Bold)
        .copy(fontSize = 26.sp, letterSpacing = (-0.3).sp, lineHeight = 29.9.sp),
    displaySmall = base.displaySmall.brand(FontWeight.Bold),
    headlineLarge = base.headlineLarge.brand(FontWeight.Bold),
    headlineMedium = base.headlineMedium.brand(FontWeight.Bold),
    headlineSmall = base.headlineSmall.brand(FontWeight.Bold),
    titleLarge = base.titleLarge.brand(FontWeight.Bold).copy(fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    titleMedium = base.titleMedium.brand(FontWeight.SemiBold).copy(fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    titleSmall = base.titleSmall.brand(FontWeight.SemiBold).copy(fontSize = 15.sp),
    bodyLarge = base.bodyLarge.brand(FontWeight.Normal).copy(fontSize = 15.sp, lineHeight = 22.5.sp, letterSpacing = 0.sp),
    bodyMedium = base.bodyMedium.brand(FontWeight.Normal).copy(fontSize = 13.sp, lineHeight = 19.5.sp, letterSpacing = 0.sp),
    bodySmall = base.bodySmall.brand(FontWeight.Normal).copy(fontSize = 11.sp, lineHeight = 15.4.sp, letterSpacing = 0.sp),
    labelLarge = base.labelLarge.brand(FontWeight.Bold).copy(fontSize = 15.sp),
    labelMedium = base.labelMedium.brand(FontWeight.SemiBold).copy(fontSize = 13.sp),
    labelSmall = base.labelSmall.brand(FontWeight.SemiBold).copy(fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.sp),
)

/** Inconsolata style for npubs, hashes and sats amounts (Flutter `fontMono`). */
val MonoStyle = TextStyle(fontFamily = Inconsolata, fontSize = 14.sp)
