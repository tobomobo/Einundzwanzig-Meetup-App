package space.einundzwanzig.meetup.feature.badges.rollingqr

import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
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
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.AppTopBar
import space.einundzwanzig.meetup.core.ui.component.ConfirmDialog
import space.einundzwanzig.meetup.core.ui.component.FullScreenLoading
import space.einundzwanzig.meetup.core.ui.nostr.NpubFormat
import space.einundzwanzig.meetup.core.ui.qr.QrCode
import space.einundzwanzig.meetup.core.ui.qr.QrErrorCorrection
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.MonoStyle
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.core.ui.util.findActivity
import space.einundzwanzig.meetup.data.issuance.session.MeetupSession
import space.einundzwanzig.meetup.data.issuance.session.SessionRemaining
import space.einundzwanzig.meetup.feature.badges.R
import space.einundzwanzig.meetup.feature.badges.common.BadgeTexts
import space.einundzwanzig.meetup.feature.badges.common.Gap
import space.einundzwanzig.meetup.feature.badges.common.PrimaryActionButton
import space.einundzwanzig.meetup.feature.badges.common.ShapedIcon
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags of the rolling QR screen. */
object RollingQrTags {
    const val QR = "rollingQr.qr"
    const val COUNTDOWN = "rollingQr.countdown"
    const val END = "rollingQr.end"
    const val EMPTY = "rollingQr.empty"
}

/** Stateful entry of `RollingQr` (spec 02 §2.3). */
@Composable
fun RollingQrRoute(viewModel: RollingQrViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                RollingQrEvent.Back -> navigator.back()
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onAction(RollingQrAction.Resumed)
        onPauseOrDispose { viewModel.onAction(RollingQrAction.Paused) }
    }
    // Compatible enhancement (spec 02 §2.3): keep the screen on at full brightness while a code shows.
    KeepScreenBright(enabled = state.session != null)
    RollingQrScreen(state, viewModel::onAction)
}

@Composable
private fun KeepScreenBright(enabled: Boolean) {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val window = context.findActivity()?.window
        if (enabled) {
            view.keepScreenOn = true
            window?.let { w -> w.attributes = w.attributes.also { it.screenBrightness = 1f } }
        }
        onDispose {
            if (enabled) {
                view.keepScreenOn = false
                window?.let { w ->
                    w.attributes = w.attributes.also {
                        it.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                }
            }
        }
    }
}

/** Stateless rolling QR screen: loading, active session or the empty state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RollingQrScreen(
    state: RollingQrUiState,
    onAction: (RollingQrAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = stringResource(L10nR.string.rqTitle),
                onBack = { onAction(RollingQrAction.Back) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = onPrimary,
                    navigationIconContentColor = onPrimary,
                    actionIconContentColor = onPrimary,
                ),
                actions = {
                    if (state.session != null) {
                        IconButton(
                            onClick = { onAction(RollingQrAction.EndClicked) },
                            modifier = Modifier.testTag(RollingQrTags.END),
                        ) {
                            Icon(Icons.Rounded.StopCircle, contentDescription = stringResource(L10nR.string.rqEndSession))
                        }
                    }
                },
            )
        },
    ) { padding ->
        val content = when {
            state.loading -> 0
            state.session != null -> 1
            else -> 2
        }
        AnimatedContent(
            targetState = content,
            transitionSpec = { fadeIn(spring(stiffness = 380f)).togetherWith(fadeOut(spring(stiffness = 1600f))) },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            label = "rollingQr",
        ) { which ->
            when (which) {
                0 -> FullScreenLoading()
                1 -> state.session?.let { ActiveSession(state, it) }
                else -> EmptySession(onBack = { onAction(RollingQrAction.Back) })
            }
        }
    }
    if (state.confirmEnd) {
        val remaining = BadgeTexts.remaining(LocalContext.current.resources, state.remaining)
        ConfirmDialog(
            title = stringResource(L10nR.string.rqEndSessionQ),
            text = stringResource(L10nR.string.rqRemainingTime, remaining) + stringResource(L10nR.string.rqEndSessionBody),
            confirmLabel = stringResource(L10nR.string.rqEnd),
            dismissLabel = stringResource(L10nR.string.apCancel),
            onConfirm = { onAction(RollingQrAction.EndConfirmed) },
            onDismiss = { onAction(RollingQrAction.EndDismissed) },
            destructive = true,
            icon = Icons.Rounded.StopCircle,
        )
    }
}

@Composable
private fun ActiveSession(state: RollingQrUiState, session: MeetupSession) {
    val res = LocalContext.current.resources
    val remaining = BadgeTexts.remaining(res, state.remaining)
    Column(Modifier.fillMaxSize()) {
        SessionBar(meetupInfo = "📍 " + state.meetupInfo, remaining = remaining)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            QrCard(state.qr)
            Gap(24.dp)
            Countdown(state.secondsLeft)
        }
        Footer(blockHeight = session.blockHeight, npub = state.npub)
    }
}

@Composable
private fun SessionBar(meetupInfo: String, remaining: String) {
    val primary = MaterialTheme.colorScheme.primary
    val success = MaterialTheme.extendedColors.success
    Row(
        Modifier
            .fillMaxWidth()
            .background(primary.copy(alpha = 0.15f))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                meetupInfo,
                color = primary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
            )
            Text(
                stringResource(L10nR.string.rqSessionRemaining, remaining),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(
            Modifier
                .background(success.copy(alpha = 0.18f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(success, CircleShape),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(L10nR.string.rqActive),
                color = success,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun QrCard(qr: String?) {
    val primary = MaterialTheme.colorScheme.primary
    // Scanners need dark modules on a light quiet zone in both themes (theme token, fixed light).
    val qrBackground = MaterialTheme.extendedColors.qrBackground
    val qrModule = MaterialTheme.extendedColors.qrModule
    val description = stringResource(R.string.native_rq_qr_description)
    Box(
        Modifier
            .dropShadow(RoundedCornerShape(16.dp), Shadow(radius = 28.dp, color = primary.copy(alpha = 0.35f)))
            .background(qrBackground, RoundedCornerShape(16.dp))
            .padding(16.dp)
            .testTag(RollingQrTags.QR)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = qr,
            transitionSpec = { fadeIn(spring(stiffness = 1600f)).togetherWith(fadeOut(spring(stiffness = 1600f))) },
            label = "qr",
        ) { code ->
            if (code == null) {
                Box(Modifier.size(QR_SIZE), contentAlignment = Alignment.Center) { AppLoadingIndicator() }
            } else {
                QrCode(
                    content = code,
                    size = QR_SIZE,
                    errorCorrection = QrErrorCorrection.M,
                    backgroundColor = qrBackground,
                    moduleColor = qrModule,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Countdown(secondsLeft: Int) {
    val urgent = secondsLeft <= 3
    val color by animateColorAsState(
        if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        label = "countdownColor",
    )
    val progress by animateFloatAsState(secondsLeft / 10f, spring(stiffness = 380f), label = "countdown")
    Box(
        Modifier
            .size(60.dp)
            .testTag(RollingQrTags.COUNTDOWN),
        contentAlignment = Alignment.Center,
    ) {
        CircularWavyProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(60.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
        Text(
            "$secondsLeft",
            color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
        )
    }
    Gap(8.dp)
    Text(
        stringResource(if (urgent) L10nR.string.rqCodeRenewing else L10nR.string.rqNextCodeIn),
        color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun Footer(blockHeight: Long, npub: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.native_rq_block, blockHeight.toString()),
                style = MonoStyle.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (npub.isNotEmpty()) {
                Text(
                    NpubFormat.shortenNpub(npub),
                    style = MonoStyle.copy(fontSize = 11.sp),
                    color = MaterialTheme.extendedColors.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun EmptySession(onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp)
            .testTag(RollingQrTags.EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ShapedIcon(Icons.Rounded.ErrorOutline, MaterialTheme.colorScheme.error)
        Gap(24.dp)
        Text(
            stringResource(L10nR.string.rqNoActiveSession),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Gap(12.dp)
        Text(
            stringResource(L10nR.string.rqNoSessionBody),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Gap(32.dp)
        PrimaryActionButton(
            text = stringResource(L10nR.string.rqBackToAdmin),
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            onClick = onBack,
        )
    }
}

private val QR_SIZE = 260.dp

private val previewSession = MeetupSession(
    seed = "00".repeat(32),
    startedAt = 0,
    expiresAt = 4 * 3600,
    meetupId = "wuerzburg-de",
    meetupName = "Würzburg",
    meetupCountry = "DE",
    blockHeight = 915_000,
    pubkey = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsx9zf3",
)

@Preview(name = "Rolling QR", backgroundColor = 0xFF0C0C0E, showBackground = true, heightDp = 800)
@Composable
private fun RollingQrPreview() {
    EinundzwanzigTheme {
        RollingQrScreen(
            RollingQrUiState(
                loading = false,
                session = previewSession,
                qr = "{\"v\":2,\"t\":\"B\",\"m\":\"wuerzburg-de\"}",
                secondsLeft = 3,
                remaining = SessionRemaining.HoursMinutes(3, 12),
                npub = previewSession.pubkey,
            ),
            onAction = {},
        )
    }
}

@Preview(name = "No session", backgroundColor = 0xFF0C0C0E, showBackground = true)
@Composable
private fun RollingQrEmptyPreview() {
    EinundzwanzigTheme { RollingQrScreen(RollingQrUiState(loading = false), onAction = {}) }
}
