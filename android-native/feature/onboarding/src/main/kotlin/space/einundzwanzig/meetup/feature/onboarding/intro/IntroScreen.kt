package space.einundzwanzig.meetup.feature.onboarding.intro

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.selection.selectable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.navigation.IdentitySetup
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.TopLevelDestination
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.LocalAppSnackbarHostState
import space.einundzwanzig.meetup.core.ui.motion.rememberReducedMotion
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.ThemeMode
import space.einundzwanzig.meetup.data.settings.locale.AppLocales
import space.einundzwanzig.meetup.feature.onboarding.R
import space.einundzwanzig.meetup.feature.onboarding.common.GradientButton
import space.einundzwanzig.meetup.feature.onboarding.common.OnboardingBackground
import space.einundzwanzig.meetup.feature.onboarding.common.SnackbarEffect
import space.einundzwanzig.meetup.feature.onboarding.common.primaryGradient

/** Stateful `Intro` entry (spec 01 §2.2). */
@Composable
fun IntroRoute(viewModel: IntroViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val appHost = LocalAppSnackbarHostState.current
    val localHost = remember { SnackbarHostState() }
    // App-level host first: the "set your identity" snackbar must survive the push of the setup.
    SnackbarEffect(viewModel.snackbars, appHost ?: localHost)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                IntroEffect.OpenIdentitySetup -> navigator.navigate(IdentitySetup)
                IntroEffect.GoHome -> navigator.resetTo(TopLevelDestination.start)
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onAction(IntroAction.Resumed)
        onPauseOrDispose {}
    }
    IntroScreen(state = state, onAction = viewModel::onAction, snackbarHostState = if (appHost == null) localHost else null)
}

private val EaseOutExpo = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/**
 * Intro screen: background photo, language chip, staged logo / tagline / buttons entrance.
 * Insets are not resized for the keyboard (Flutter `resizeToAvoidBottomInset = false`).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun IntroScreen(state: IntroUiState, onAction: (IntroAction) -> Unit, snackbarHostState: SnackbarHostState? = null) {
    val reduced = rememberReducedMotion()
    val logo = remember { Animatable(if (reduced) 1f else 0f) }
    val tagline = remember { Animatable(if (reduced) 1f else 0f) }
    val buttons = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        coroutineScope {
            async { delay(400); logo.animateTo(1f, tween(800, easing = EaseOutExpo)) }
            async { delay(1100); tagline.animateTo(1f, tween(600)) }
            async { delay(1500); buttons.animateTo(1f, tween(500, easing = EaseOutExpo)) }
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { snackbarHostState?.let { AppSnackbarHost(it) } },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { _ ->
        OnboardingBackground(subtle = false) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painterResource(R.drawable.onboarding_intro_logo),
                        contentDescription = stringResource(R.string.native_onboarding_logo),
                        modifier = Modifier
                            .widthIn(max = 390.dp)
                            .fillMaxWidth()
                            .offset(x = (-16).dp)
                            .graphicsLayer {
                                alpha = logo.value
                                translationY = (1f - logo.value) * 0.3f * size.height
                            },
                    )
                    Spacer(Modifier.height(28.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(tagline.value)) {
                        Text(
                            stringResource(L10nR.string.introTagline),
                            fontSize = 12.sp,
                            letterSpacing = 3.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.size(width = 40.dp, height = 2.dp).background(primaryGradient(), RoundedCornerShape(1.dp)))
                    }
                    Spacer(Modifier.height(56.dp))
                    Column(
                        Modifier
                            .padding(horizontal = 40.dp)
                            .graphicsLayer {
                                alpha = buttons.value
                                translationY = (1f - buttons.value) * 0.2f * size.height
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        GradientButton(
                            label = stringResource(L10nR.string.introJoin),
                            onClick = { onAction(IntroAction.Join) },
                            busy = state.joining,
                            height = 56.dp,
                            radius = 16.dp,
                            fontSize = 15.sp,
                            letterSpacing = 1.sp,
                            glow = true,
                            modifier = Modifier.testTag(IntroTags.JOIN),
                        )
                        Spacer(Modifier.height(20.dp))
                        TextButton(
                            onClick = { onAction(IntroAction.RestoreBackup) },
                            enabled = !state.joining && !state.backupBusy,
                            modifier = Modifier.heightIn(min = 48.dp).testTag(IntroTags.RESTORE),
                        ) {
                            Icon(
                                Icons.Rounded.Restore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(L10nR.string.introLoadBackup),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
                // Drawn last so the scrollable column never covers it on short screens.
                LanguageChip(
                    code = state.locale,
                    onClick = { onAction(IntroAction.OpenLanguage) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 4.dp),
                )
            }
        }
    }
    if (state.showLanguageDialog) {
        LanguageDialog(
            selected = state.locale,
            onSelect = { onAction(IntroAction.SelectLanguage(it)) },
            onDismiss = { onAction(IntroAction.DismissLanguage) },
        )
    }
}

@Composable
private fun LanguageChip(code: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.native_onboarding_language_chip, AppLocales.displayName(code))
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics { contentDescription = label }
            .testTag(IntroTags.LANGUAGE_CHIP),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(AppLocales.flagFor(code), fontSize = 16.sp)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

/** Language dialog (spec 01 §2.2): System / Deutsch / English / Español. */
@Composable
fun LanguageDialog(selected: String?, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    val options: List<String?> = listOf(null) + AppLocales.SUPPORTED
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.testTag(IntroTags.LANGUAGE_DIALOG)) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(L10nR.string.settingsLanguageChoose), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                options.forEach { code ->
                    val active = code == selected
                    val primary = MaterialTheme.colorScheme.primary
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .background(if (active) primary.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent)
                            .selectable(selected = active, role = Role.RadioButton) { onSelect(code) }
                            .semantics { this.selected = active }
                            .padding(horizontal = 20.dp)
                            .testTag(IntroTags.language(code)),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(AppLocales.flagFor(code), fontSize = 20.sp)
                        Text(
                            AppLocales.displayName(code),
                            modifier = Modifier.weight(1f),
                            color = if (active) primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        )
                        if (active) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = stringResource(R.string.native_onboarding_selected),
                                tint = primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Test tags of the intro screen. */
object IntroTags {
    const val JOIN = "intro_join"
    const val RESTORE = "intro_restore"
    const val LANGUAGE_CHIP = "intro_language_chip"
    const val LANGUAGE_DIALOG = "intro_language_dialog"
    fun language(code: String?) = "intro_language_${code ?: "system"}"
}

@Preview
@Composable
private fun IntroPreview() {
    EinundzwanzigTheme { IntroScreen(IntroUiState(locale = "de"), onAction = {}) }
}

@Preview
@Composable
private fun IntroLightPreview() {
    EinundzwanzigTheme(themeMode = ThemeMode.LIGHT) { IntroScreen(IntroUiState(locale = "de"), onAction = {}) }
}

@Preview
@Composable
private fun IntroLanguagePreview() {
    EinundzwanzigTheme { IntroScreen(IntroUiState(joining = true, showLanguageDialog = true), onAction = {}) }
}
