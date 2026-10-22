package space.einundzwanzig.meetup.feature.onboarding.common

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.motion.pressScale
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.feature.onboarding.R

/** Flutter `gradientOrange` (`#F7931A → #E07810`), derived from the theme's primary. */
@Composable
fun primaryGradient(): Brush {
    val primary = MaterialTheme.colorScheme.primary
    return Brush.linearGradient(listOf(primary, lerp(primary, MaterialTheme.colorScheme.scrim, 0.1f)))
}

/**
 * Brand primary button: gradient when enabled (also while [busy], only the click is removed), flat
 * surface + tertiary label when disabled, and an expressive loading indicator instead of the label
 * while [busy] (spec 01 §2.2, §2.3.2).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GradientButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    height: Dp = 52.dp,
    radius: Dp = 14.dp,
    fontSize: TextUnit = 15.sp,
    letterSpacing: TextUnit = 0.5.sp,
    glow: Boolean = false,
) {
    val active = enabled && !busy
    val shape = RoundedCornerShape(radius)
    val interaction = remember { MutableInteractionSource() }
    val primary = MaterialTheme.colorScheme.primary
    val loadingLabel = stringResource(R.string.native_onboarding_loading)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .pressScale(interaction, enabled = active)
            .then(if (glow && active) Modifier.shadow(20.dp, shape, ambientColor = primary, spotColor = primary) else Modifier)
            .clip(shape)
            .then(
                // Busy keeps the gradient (only the click is removed) so the indicator stays visible.
                if (enabled) {
                    Modifier.background(primaryGradient())
                } else {
                    Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)
                },
            )
            .clickable(interactionSource = interaction, indication = null, enabled = active, role = Role.Button, onClick = onClick)
            .then(
                if (busy) {
                    Modifier.semantics {
                        contentDescription = label
                        stateDescription = loadingLabel
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = busy,
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
            label = "gradientButtonBusy",
        ) { isBusy ->
            if (isBusy) {
                AppLoadingIndicator(size = 32.dp, color = if (enabled) MaterialTheme.colorScheme.onPrimary else primary)
            } else {
                Text(
                    label,
                    color = if (active) MaterialTheme.colorScheme.onPrimary else onboardingTertiary(),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = fontSize,
                    letterSpacing = letterSpacing,
                )
            }
        }
    }
}

/** `cTextTertiary`. */
@Composable
fun onboardingTertiary(): Color = MaterialTheme.extendedColors.textTertiary

/**
 * The intro / identity-setup background (spec 01 §2.2, §2.3.2): sharp photo, a brightened and
 * blurred watermark layer cross-faded in on form steps ([subtle]), a background-coloured veil
 * (stronger in light mode) and the orange top
 * glow. Blur needs API 31; below that the brightened layer is used unblurred.
 */
@Composable
fun OnboardingBackground(subtle: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    val spec = tween<Float>(450, easing = FastOutSlowInEasing)
    val watermark by animateFloatAsState(if (subtle) 1f else 0f, spec, label = "watermark")
    val background = MaterialTheme.colorScheme.background
    // The veil follows the scheme: in light mode the photo becomes a light watermark so the
    // theme-following foreground (onSurface, onSurfaceVariant) stays readable.
    val light = background.luminance() > 0.5f
    val veil by animateFloatAsState(
        when {
            light && subtle -> 0.78f
            light -> 0.72f
            subtle -> 0.55f
            else -> 0.52f
        },
        spec,
        label = "veil",
    )
    val glow by animateFloatAsState(if (subtle) 0.7f else 1f, spec, label = "glow")
    val painter = painterResource(R.drawable.onboarding_intro_background)
    val brighten = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(2.2f, 2.2f, 2.2f, 1f) }) }
    val primary = MaterialTheme.colorScheme.primary
    Box(modifier.fillMaxSize().background(background)) {
        Image(painter, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (watermark > 0f) {
            Image(
                painter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = brighten,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(watermark)
                    .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(4.dp) else Modifier),
            )
        }
        Box(Modifier.fillMaxSize().background(background.copy(alpha = veil)))
        Box(
            Modifier
                .fillMaxWidth()
                .height(500.dp)
                .offset(y = (-80).dp)
                .alpha(glow)
                .background(Brush.radialGradient(listOf(primary.copy(alpha = 0.08f), Color.Transparent))),
        )
        content()
    }
}
