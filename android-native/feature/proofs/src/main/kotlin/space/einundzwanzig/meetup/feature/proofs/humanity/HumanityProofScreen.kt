package space.einundzwanzig.meetup.feature.proofs.humanity

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.ui.component.AppLargeTopBar
import space.einundzwanzig.meetup.core.ui.component.FullScreenLoading
import space.einundzwanzig.meetup.core.ui.motion.rememberReducedMotion
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityOutcome
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityStatus
import space.einundzwanzig.meetup.data.proofs.humanity.LastChecked
import space.einundzwanzig.meetup.feature.proofs.R
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags of the humanity proof screen. */
internal object HumanityProofTags {
    const val CHECK_BUTTON = "hum_check_button"
    const val RESULT_BOX = "hum_result_box"
}

/** Stateful entry of [HumanityProof][space.einundzwanzig.meetup.core.navigation.HumanityProof] (spec 06 §2.2). */
@Composable
internal fun HumanityProofRoute(viewModel: HumanityProofViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    // Recompute "Gerade eben" / "Vor N Min." when the user returns to the screen.
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshClock()
        onPauseOrDispose {}
    }
    HumanityProofScreen(state = state, onAction = viewModel::onAction, onBack = navigator::back)
}

/** Stateless humanity proof screen (spec 06 §2.2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HumanityProofScreen(
    state: HumanityProofUiState,
    onAction: (HumanityProofAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppLargeTopBar(
                title = stringResource(L10nR.string.humTitle),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        if (state.isLoading) {
            FullScreenLoading(Modifier.padding(padding))
        } else {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                StatusCard(verified = state.verified, firstZapDate = state.firstZapDate)
                Spacer(Modifier.height(24.dp))
                ExplanationCard()
                Spacer(Modifier.height(24.dp))
                ActionSection(state = state, onCheck = { onAction(HumanityProofAction.Check) })
            }
        }
    }
}

/** Amber of the Flutter screen, mapped to the brand gold (tertiary). */
@Composable
private fun amber(): Color = MaterialTheme.colorScheme.tertiary

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusCard(verified: Boolean, firstZapDate: String) {
    val success = MaterialTheme.extendedColors.success
    val accent by animateColorAsState(if (verified) success else amber(), label = "statusAccent")
    val reducedMotion = rememberReducedMotion()
    val rotation by animateFloatAsState(
        targetValue = if (verified || reducedMotion) 0f else 22.5f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 200f),
        label = "statusShapeRotation",
    )
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .border(2.dp, accent.copy(alpha = if (verified) 0.4f else 0.3f), shape)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(80.dp)) {
            Box(
                Modifier
                    .size(80.dp)
                    .rotate(rotation)
                    .background(
                        accent.copy(alpha = 0.12f),
                        if (verified) MaterialShapes.Cookie9Sided.toShape() else MaterialShapes.Sunny.toShape(),
                    ),
            )
            AnimatedContent(
                targetState = verified,
                transitionSpec = { (scaleIn() + fadeIn()) togetherWith fadeOut() },
                label = "statusIcon",
            ) { isVerified ->
                Icon(
                    imageVector = if (isVerified) Icons.Rounded.VerifiedUser else Icons.Rounded.PersonSearch,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(38.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(if (verified) L10nR.string.humVerified else L10nR.string.humNotVerified),
            color = accent,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp),
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(10.dp))
        val body = if (verified) {
            joinPieces(
                stringResource(L10nR.string.humPaidOn, firstZapDate),
                stringResource(L10nR.string.humProofInEvent1),
                stringResource(L10nR.string.humReputationSaved),
            )
        } else {
            joinPieces(
                stringResource(L10nR.string.humIntro1),
                stringResource(L10nR.string.humIntro2),
                stringResource(L10nR.string.humIntro3),
            )
        }
        Text(
            text = body,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
        )
        AnimatedVisibility(verified, enter = expandVertically() + fadeIn(), exit = fadeOut()) {
            Row(
                modifier = Modifier
                    .padding(top = 16.dp)
                    .background(success.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Bolt, contentDescription = null, tint = amber(), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(L10nR.string.humLightningActive),
                    color = MaterialTheme.extendedColors.onSuccessContainer,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

/**
 * The ARB pieces carry their own trailing spaces, which Android resources may trim; join them
 * with exactly one space so the sentence reads the same either way.
 */
internal fun joinPieces(vararg pieces: String): String = pieces.joinToString(" ") { it.trim() }

@Composable
private fun ExplanationCard() {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(L10nR.string.humHowTitle),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp),
            modifier = Modifier.semantics { heading() },
        )
        StepRow(1, stringResource(L10nR.string.humStep1), stringResource(L10nR.string.humStepInstruction))
        StepRow(2, stringResource(L10nR.string.humStep2), stringResource(L10nR.string.humCryptoProof))
        StepRow(3, stringResource(L10nR.string.humStep3), stringResource(L10nR.string.humCheckInstruction))
        StepRow(4, stringResource(L10nR.string.humVerifiedSub), stringResource(L10nR.string.humProofPrivacy))
        val hintShape = RoundedCornerShape(10.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(amber().copy(alpha = 0.06f), hintShape)
                .border(1.dp, amber().copy(alpha = 0.2f), hintShape)
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Rounded.Shield, contentDescription = null, tint = amber(), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = joinPieces(
                    stringResource(L10nR.string.humExplain1),
                    stringResource(L10nR.string.humExplain2),
                    stringResource(L10nR.string.humExplain3),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun StepRow(number: Int, title: String, subtitle: String) {
    val primary = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(primary.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                color = primary,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp, fontWeight = FontWeight.ExtraBold),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionSection(state: HumanityProofUiState, onCheck: () -> Unit) {
    val verified = state.verified
    val containerColor = if (verified) MaterialTheme.colorScheme.surfaceContainerHighest else amber()
    val contentColor = if (verified) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onTertiary
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = onCheck,
            enabled = !state.isChecking,
            shapes = ButtonDefaults.shapes(),
            colors = ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor,
                disabledContainerColor = amber().copy(alpha = 0.5f),
                disabledContentColor = MaterialTheme.colorScheme.onTertiary,
            ),
            contentPadding = ButtonDefaults.contentPaddingFor(56.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag(HumanityProofTags.CHECK_BUTTON),
        ) {
            when {
                state.isChecking -> {
                    LoadingIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onTertiary)
                    Spacer(Modifier.width(10.dp))
                    ButtonLabel(stringResource(L10nR.string.humSearchingRelays))
                }
                verified -> {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    ButtonLabel(stringResource(L10nR.string.humCheckAgain))
                }
                else -> {
                    Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    ButtonLabel(stringResource(L10nR.string.humCheckNow))
                }
            }
        }
        state.lastChecked?.let { lastChecked ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(L10nR.string.humLastCheck, lastChecked.localized()),
                color = MaterialTheme.extendedColors.textTertiary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Keep the last outcome so the box fades out instead of vanishing when a new check
        // clears the result.
        var shownResult by remember { mutableStateOf(state.result) }
        LaunchedEffect(state.result) {
            if (state.result != null) shownResult = state.result
        }
        AnimatedVisibility(
            visible = state.result != null,
            enter = expandVertically() + fadeIn(),
            exit = fadeOut(),
        ) {
            (state.result ?: shownResult)?.let { ResultBox(it) }
        }
    }
}

@Composable
private fun ButtonLabel(text: String) {
    Text(text, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
}

@Composable
private fun ResultBox(outcome: HumanityOutcome) {
    val success = outcome == HumanityOutcome.FOUND
    val accent = if (success) MaterialTheme.extendedColors.success else amber()
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.08f), shape)
            .border(1.dp, accent.copy(alpha = 0.3f), shape)
            .padding(14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(HumanityProofTags.RESULT_BOX),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (success) Icons.Rounded.CheckCircle else Icons.Outlined.Info,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = outcome.localized(),
            color = if (success) MaterialTheme.extendedColors.onSuccessContainer else accent,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
        )
    }
}

@Composable
private fun HumanityOutcome.localized(): String = stringResource(
    when (this) {
        HumanityOutcome.FOUND -> R.string.native_humFound
        HumanityOutcome.NOT_FOUND -> R.string.native_humNotFound
        HumanityOutcome.NO_KEY -> R.string.native_humNoKey
        HumanityOutcome.INVALID_NPUB -> R.string.native_humInvalidNpub
        HumanityOutcome.ERROR -> R.string.native_humError
    },
)

@Composable
private fun LastChecked.localized(): String = when (this) {
    LastChecked.Never -> german // never shown: the line needs lastCheckedAt > 0
    LastChecked.JustNow -> stringResource(R.string.native_humLastJustNow)
    is LastChecked.MinutesAgo -> stringResource(R.string.native_humLastMinutes, minutes.toInt())
    is LastChecked.HoursAgo -> stringResource(R.string.native_humLastHours, hours.toInt())
    is LastChecked.OnDate -> date
}

@Preview(name = "Humanity, not verified", heightDp = 1200)
@Composable
private fun HumanityNotVerifiedPreview() {
    EinundzwanzigTheme {
        HumanityProofScreen(
            state = HumanityProofUiState(
                isLoading = false,
                status = HumanityStatus(verified = false, lastCheckedAt = 1),
                lastChecked = LastChecked.MinutesAgo(5),
                result = HumanityOutcome.NOT_FOUND,
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview(name = "Humanity, verified", heightDp = 1200)
@Composable
private fun HumanityVerifiedPreview() {
    EinundzwanzigTheme {
        HumanityProofScreen(
            state = HumanityProofUiState(
                isLoading = false,
                status = HumanityStatus(verified = true, firstZapAt = 1_746_230_400, lastCheckedAt = 1),
                firstZapDate = "3.5.2025",
                lastChecked = LastChecked.JustNow,
                result = HumanityOutcome.FOUND,
            ),
            onAction = {},
            onBack = {},
        )
    }
}
