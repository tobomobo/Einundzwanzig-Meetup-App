package space.einundzwanzig.meetup.feature.onboarding.splash

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.LockReset
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.navigation.Intro
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.TopLevelDestination
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.motion.rememberReducedMotion
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.feature.onboarding.R

/** Stateful `Splash` entry: runs the session check and resets the stack to Intro or the shell. */
@Composable
fun SplashRoute(viewModel: SplashViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    LaunchedEffect(state) {
        val ready = state as? SplashUiState.Ready ?: return@LaunchedEffect
        when (ready.destination) {
            SplashDestination.INTRO -> navigator.resetTo(Intro)
            SplashDestination.HOME -> navigator.resetTo(TopLevelDestination.start)
        }
    }
    SplashScreen(state = state, onRetry = viewModel::retry)
}

/** Splash hold screen (spec 01 §2.1, spec 12 §2.3): glowing bolt + expressive loading indicator. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SplashScreen(state: SplashUiState, onRetry: () -> Unit) {
    val reducedMotion = rememberReducedMotion()
    val fade = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(Unit) { fade.animateTo(1f, tween(durationMillis = 800, easing = EaseOut)) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .safeDrawingPadding()
                .alpha(fade.value),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = state is SplashUiState.StorageUnavailable,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                label = "splashState",
            ) { unavailable ->
                if (unavailable) StorageUnavailable(onRetry) else BoltLoading(reducedMotion)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BoltLoading(reducedMotion: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val rotation = if (reducedMotion) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "splashGlow")
        val r by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing), RepeatMode.Restart),
            label = "glowRotation",
        )
        r
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.testTag(SplashTags.LOADING)) {
        Box(contentAlignment = Alignment.Center) {
            // Orange glow (Flutter BoxShadow cOrange@0.2, blur 40).
            Box(
                Modifier
                    .size(168.dp)
                    .background(Brush.radialGradient(listOf(primary.copy(alpha = 0.22f), primary.copy(alpha = 0f))), CircleShape),
            )
            Box(
                Modifier
                    .size(88.dp)
                    .rotate(rotation)
                    .background(primary.copy(alpha = 0.14f), MaterialShapes.Cookie9Sided.toShape()),
            )
            Icon(
                Icons.Rounded.Bolt,
                contentDescription = stringResource(R.string.native_onboarding_logo),
                tint = primary,
                modifier = Modifier.size(56.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        AppLoadingIndicator(size = 40.dp, contentDescription = stringResource(R.string.native_onboarding_loading))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StorageUnavailable(onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(32.dp).testTag(SplashTags.STORAGE_ERROR),
    ) {
        Box(
            Modifier
                .size(88.dp)
                .background(MaterialTheme.colorScheme.errorContainer, MaterialShapes.Cookie4Sided.toShape()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.LockReset, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.native_onboarding_storage_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.native_onboarding_storage_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onRetry,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.heightIn(min = 52.dp).testTag(SplashTags.RETRY),
        ) {
            Text(stringResource(L10nR.string.actionRetry))
        }
    }
}

/** Test tags of the splash. */
object SplashTags {
    const val LOADING = "splash_loading"
    const val STORAGE_ERROR = "splash_storage_error"
    const val RETRY = "splash_retry"
}

@Preview
@Composable
private fun SplashPreview() {
    EinundzwanzigTheme { SplashScreen(SplashUiState.Checking, onRetry = {}) }
}

@Preview
@Composable
private fun SplashErrorPreview() {
    EinundzwanzigTheme { SplashScreen(SplashUiState.StorageUnavailable, onRetry = {}) }
}
