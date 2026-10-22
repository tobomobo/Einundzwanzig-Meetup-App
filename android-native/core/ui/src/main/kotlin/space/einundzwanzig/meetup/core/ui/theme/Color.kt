package space.einundzwanzig.meetup.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Brand palette carried over from the Flutter app (lib/theme.dart). */
object BrandColors {
    val Orange = Color(0xFFF7931A)
    val OrangeLight = Color(0xFFFFB347)
    val OrangeDim = Color(0xFF3D2500)
    val OrangeDeep = Color(0xFFE07810)
    val Gold = Color(0xFFD4A017)
    val GoldDim = Color(0xFF3D2E00)
    val Course = Color(0xFF9AA0AA)
    val Purple = Color(0xFF9B45E8)
    val Nostr = Color(0xFF8B5CF6)
    val PurpleLight = Color(0xFFBE96F2)
    val DuellGold = Color(0xFFFFC93C)
    val Green = Color(0xFF22C55E)
    val Red = Color(0xFFE53935)

    val Dark = Color(0xFF0C0C0E)
    val Surface = Color(0xFF0F0F11)
    val Card = Color(0xFF131315)
    val CardHover = Color(0xFF1A1A1D)
    val Border = Color(0xFF1C1C22)
    val TileBorder = Color(0xFF202028)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFF9CA0A8)
    val TextTertiary = Color(0xFF868A94)
}

/**
 * Semantic colors that Material's ColorScheme has no slot for. Read via
 * MaterialTheme.extendedColors so light and dark themes stay consistent.
 */
@Immutable
data class ExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val community: Color,
    val communityContainer: Color,
    val nostr: Color,
    val nostrOnTile: Color,
    val course: Color,
    val textTertiary: Color,
    val bitcoin: Color,
    /** Flutter `cPurple` accent (Podcast tiles). */
    val purple: Color,
    /** SatoshiDuell logo gold (`0xFFFFC93C`). */
    val duellGold: Color,
)

internal val DarkExtendedColors = ExtendedColors(
    success = BrandColors.Green,
    onSuccess = Color(0xFF00210B),
    successContainer = Color(0xFF0E3A1E),
    onSuccessContainer = Color(0xFFB4F1C4),
    community = BrandColors.Gold,
    communityContainer = BrandColors.GoldDim,
    nostr = BrandColors.Nostr,
    nostrOnTile = BrandColors.PurpleLight,
    course = BrandColors.Course,
    textTertiary = BrandColors.TextTertiary,
    bitcoin = BrandColors.Orange,
    purple = BrandColors.Purple,
    duellGold = BrandColors.DuellGold,
)

internal val LightExtendedColors = ExtendedColors(
    success = Color(0xFF15803D),
    onSuccess = Color.White,
    successContainer = Color(0xFFC8F2D4),
    onSuccessContainer = Color(0xFF002110),
    community = Color(0xFF8A6500),
    communityContainer = Color(0xFFFFE08C),
    nostr = Color(0xFF6D3FD6),
    nostrOnTile = Color(0xFF5A2FC0),
    course = Color(0xFF5F646D),
    textTertiary = Color(0xFF5E626B),
    bitcoin = Color(0xFFB85F00),
    purple = Color(0xFF7B2CBF),
    duellGold = Color(0xFF8F6A00),
)

val LocalExtendedColors = staticCompositionLocalOf { DarkExtendedColors }

/** The brand dark scheme: near-black surfaces, Bitcoin orange as the single accent. */
internal val BrandDarkColorScheme: ColorScheme = darkColorScheme(
    primary = BrandColors.Orange,
    onPrimary = Color(0xFF1F1000),
    primaryContainer = Color(0xFF5A3300),
    onPrimaryContainer = Color(0xFFFFDCBE),
    inversePrimary = Color(0xFF8F4E00),
    secondary = BrandColors.OrangeLight,
    onSecondary = Color(0xFF2B1700),
    secondaryContainer = BrandColors.OrangeDim,
    onSecondaryContainer = Color(0xFFFFDDB3),
    tertiary = BrandColors.Gold,
    onTertiary = Color(0xFF261A00),
    tertiaryContainer = BrandColors.GoldDim,
    onTertiaryContainer = Color(0xFFFFE08C),
    background = BrandColors.Dark,
    onBackground = BrandColors.TextPrimary,
    surface = BrandColors.Dark,
    onSurface = BrandColors.TextPrimary,
    surfaceVariant = BrandColors.CardHover,
    onSurfaceVariant = BrandColors.TextSecondary,
    surfaceTint = BrandColors.Orange,
    inverseSurface = Color(0xFFE6E1DC),
    inverseOnSurface = Color(0xFF1C1B1A),
    error = Color(0xFFFF6B66),
    onError = Color(0xFF410002),
    errorContainer = Color(0xFF5C1210),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF3A3A44),
    outlineVariant = BrandColors.TileBorder,
    scrim = Color.Black,
    surfaceBright = Color(0xFF2A2A2E),
    surfaceDim = BrandColors.Dark,
    surfaceContainerLowest = Color(0xFF080809),
    surfaceContainerLow = BrandColors.Surface,
    surfaceContainer = BrandColors.Card,
    surfaceContainerHigh = BrandColors.CardHover,
    surfaceContainerHighest = Color(0xFF222226),
)

internal val BrandLightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF8F4E00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCBE),
    onPrimaryContainer = Color(0xFF2E1500),
    inversePrimary = BrandColors.Orange,
    secondary = Color(0xFF735A3F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDDB3),
    onSecondaryContainer = Color(0xFF29180A),
    tertiary = Color(0xFF6B5E00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF6E388),
    onTertiaryContainer = Color(0xFF201C00),
    background = Color(0xFFFFF8F4),
    onBackground = Color(0xFF201A16),
    surface = Color(0xFFFFF8F4),
    onSurface = Color(0xFF201A16),
    surfaceVariant = Color(0xFFF3DFD1),
    onSurfaceVariant = Color(0xFF52443A),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF857468),
    outlineVariant = Color(0xFFD7C3B6),
    surfaceBright = Color(0xFFFFF8F4),
    surfaceDim = Color(0xFFE4D8D0),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFEF1E9),
    surfaceContainer = Color(0xFFF8ECE3),
    surfaceContainerHigh = Color(0xFFF2E6DD),
    surfaceContainerHighest = Color(0xFFECE0D8),
)
