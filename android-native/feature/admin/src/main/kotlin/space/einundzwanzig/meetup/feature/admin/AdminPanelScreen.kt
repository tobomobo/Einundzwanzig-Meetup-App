package space.einundzwanzig.meetup.feature.admin

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.ui.component.AppLargeTopBar
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.LoadingPlaceholder
import space.einundzwanzig.meetup.core.ui.component.LocalAppSnackbarHostState
import space.einundzwanzig.meetup.core.ui.component.Tile
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.core.ui.component.showAppSnackbar
import space.einundzwanzig.meetup.core.ui.motion.rememberReducedMotion
import space.einundzwanzig.meetup.core.ui.nostr.NpubChip
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.MonoStyle
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Stateful entry of the organizer panel (NavKey `AdminPanel`, spec 05 §2.2). */
@Composable
fun AdminPanelRoute(viewModel: AdminPanelViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val appHost = LocalAppSnackbarHostState.current
    val localHost = remember { SnackbarHostState() }
    val host = appHost ?: localHost
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is AdminPanelEvent.Navigate -> navigator.navigate(event.key)
                AdminPanelEvent.NavigateBack -> navigator.back()
                // Flutter: SnackBar(wotErrorShort(e), red), default 4 s.
                is AdminPanelEvent.Error -> host.showAppSnackbar(
                    message = context.getString(L10nR.string.wotErrorShort, event.message),
                    tone = Tone.Error,
                    durationMillis = ERROR_SNACKBAR_MILLIS,
                )
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onAction(AdminPanelAction.Resumed)
        onPauseOrDispose { }
    }

    AdminPanelScreen(
        state = state,
        onAction = viewModel::onAction,
        snackbarHost = { if (appHost == null) AppSnackbarHost(localHost) },
    )
}

/** Stateless organizer panel: status row, session section, info box and the dialog layer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminPanelScreen(
    state: AdminPanelUiState,
    onAction: (AdminPanelAction) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHost: @Composable () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppLargeTopBar(
                title = stringResource(L10nR.string.apOrganizer),
                onBack = { onAction(AdminPanelAction.Back) },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = snackbarHost,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 40.dp)),
        ) {
            StatusRow(state)
            Spacer(Modifier.height(20.dp))
            Spacer(Modifier.height(32.dp))
            SectionLabel(stringResource(L10nR.string.apMeetupSession))
            Spacer(Modifier.height(12.dp))
            SessionSection(state, onAction)
            Spacer(Modifier.height(16.dp))
            if (state.isSuperAdmin) Spacer(Modifier.height(16.dp))
            Spacer(Modifier.height(16.dp))
            HowItWorksBox()
        }
    }
    AdminDialogs(state, onAction)
}

@Composable
private fun StatusRow(state: AdminPanelUiState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Rounded.Verified,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(if (state.viaTrustScore) L10nR.string.apViaTrustScore else L10nR.string.apOrganizer),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (state.adminNpub.isNotEmpty()) {
            NpubChip(
                npub = state.adminNpub,
                style = MonoStyle.copy(fontSize = 11.sp, color = MaterialTheme.extendedColors.textTertiary),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
        color = MaterialTheme.extendedColors.textTertiary,
        modifier = Modifier.semantics { heading() },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SessionSection(state: AdminPanelUiState, onAction: (AdminPanelAction) -> Unit) {
    val key = when {
        !state.sessionChecked -> 0
        state.session != null -> 1
        else -> 2
    }
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = key,
        transitionSpec = {
            (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.96f)) togetherWith
                fadeOut(motion.defaultEffectsSpec())
        },
        label = "session",
    ) { target ->
        when (target) {
            0 -> LoadingPlaceholder(
                height = 96.dp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)),
            )
            1 -> state.session?.let { ActiveSessionCard(it, onAction) }
            else -> StartMeetupTile(onClick = { onAction(AdminPanelAction.StartClicked) })
        }
    }
}

@Composable
private fun ActiveSessionCard(session: ActiveSessionUi, onAction: (AdminPanelAction) -> Unit) {
    val success = MaterialTheme.extendedColors.success
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, success.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .padding(16.dp)
            .testTag(AdminPanelTags.ACTIVE_SESSION),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics(mergeDescendants = true) {},
        ) {
            PulsingDot(success)
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(L10nR.string.apSessionRunning),
                color = success,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = session.timeLeft,
                style = MonoStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                // No live region: the text changes every second and TalkBack would announce it
                // continuously. The header row is merged so it is read as one item on focus.
                modifier = Modifier.testTag(AdminPanelTags.COUNTDOWN),
            )
        }
        if (session.meetupName.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = session.meetupName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onAction(AdminPanelAction.OpenActiveMeetup) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = success,
                contentColor = MaterialTheme.extendedColors.onSuccess,
            ),
        ) {
            Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(L10nR.string.apOpenActiveMeetup), fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = { onAction(AdminPanelAction.EndClicked) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(L10nR.string.apEndMeetupEarly), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun PulsingDot(color: Color) {
    val reduced = rememberReducedMotion()
    val scale = if (reduced) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "dot")
        val s by transition.animateFloat(
            initialValue = 0.8f,
            targetValue = 1.25f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "dotScale",
        )
        s
    }
    Box(
        Modifier
            .size(8.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(color),
    )
}

/** The Flutter admin tile (`_buildAdminTile`) for "MEETUP STARTEN", with an expressive shape. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StartMeetupTile(onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Tile(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        onClickLabel = stringResource(L10nR.string.apStartMeetup),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .testTag(AdminPanelTags.START_TILE),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(MaterialShapes.Cookie9Sided.toShape())
                    .background(primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, tint = primary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(L10nR.string.apStartMeetup),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(L10nR.string.apGeneratesProof),
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 14.sp),
                    color = MaterialTheme.extendedColors.textTertiary,
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.extendedColors.textTertiary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun HowItWorksBox() {
    val primary = MaterialTheme.colorScheme.primary
    val gold = MaterialTheme.extendedColors.community
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(primary.copy(alpha = 0.10f))
            .border(1.dp, primary.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Info, contentDescription = null, tint = primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(L10nR.string.apHowItWorks),
                color = primary,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.5.sp),
                modifier = Modifier.semantics { heading() },
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(L10nR.string.apHowStep1) + stringResource(L10nR.string.apHowStep2) +
                stringResource(L10nR.string.apHowStep3) + stringResource(L10nR.string.apHowStep4),
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(gold.copy(alpha = 0.10f))
                .border(1.dp, gold.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.Handshake, contentDescription = null, tint = gold, modifier = Modifier.size(18.dp))
            Column {
                Text(
                    stringResource(L10nR.string.apCrossConfirmTitle),
                    color = gold,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = 12.5.sp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(L10nR.string.apCrossConfirmBody),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Test tags of the panel. */
object AdminPanelTags {
    const val ACTIVE_SESSION = "admin_active_session"
    const val COUNTDOWN = "admin_countdown"
    const val START_TILE = "admin_start_tile"
    const val BUSY = "admin_busy"
    const val PICKER_SEARCH = "admin_picker_search"
    const val MANUAL_NAME = "admin_manual_name"
}

private const val ERROR_SNACKBAR_MILLIS = 4_000L

@Preview(name = "Admin panel – idle", backgroundColor = 0xFF0C0C0E, showBackground = true, heightDp = 900)
@Composable
private fun AdminPanelIdlePreview() {
    EinundzwanzigTheme {
        AdminPanelScreen(
            state = AdminPanelUiState(
                adminNpub = "npub1dzu6ceugmm7xqs2drhwl5jdyhwrhc3ezgr0w6tmmvqzjmn5ac0ysl4qd9z",
                sessionChecked = true,
            ),
            onAction = {},
        )
    }
}

@Preview(name = "Admin panel – running", backgroundColor = 0xFF0C0C0E, showBackground = true, heightDp = 900)
@Composable
private fun AdminPanelRunningPreview() {
    EinundzwanzigTheme {
        AdminPanelScreen(
            state = AdminPanelUiState(
                adminNpub = "npub1dzu6ceugmm7xqs2drhwl5jdyhwrhc3ezgr0w6tmmvqzjmn5ac0ysl4qd9z",
                viaTrustScore = true,
                sessionChecked = true,
                session = ActiveSessionUi("Würzburg", 0, "3h 07m 42s"),
            ),
            onAction = {},
        )
    }
}
