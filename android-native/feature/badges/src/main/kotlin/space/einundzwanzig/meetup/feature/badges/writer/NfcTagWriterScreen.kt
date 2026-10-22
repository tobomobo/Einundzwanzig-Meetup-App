@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package space.einundzwanzig.meetup.feature.badges.writer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.RollingQr
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.AppMediumTopBar
import space.einundzwanzig.meetup.core.ui.component.LocalAppSnackbarHostState
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.core.ui.component.showAppSnackbar
import space.einundzwanzig.meetup.core.ui.haptics.rememberHaptics
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.issuance.nfc.NfcBadgePayload
import space.einundzwanzig.meetup.data.issuance.nfc.NfcWriteError
import space.einundzwanzig.meetup.feature.badges.R
import space.einundzwanzig.meetup.feature.badges.common.Gap
import space.einundzwanzig.meetup.feature.badges.common.NfcStateDialog
import space.einundzwanzig.meetup.feature.badges.common.PrimaryActionButton
import space.einundzwanzig.meetup.feature.badges.common.PulsingCircle
import space.einundzwanzig.meetup.feature.badges.common.ShapedIcon
import space.einundzwanzig.meetup.feature.badges.nfc.NfcReaderModeEffect
import space.einundzwanzig.meetup.feature.badges.nfc.openNfcSettings
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags of the writer. */
object NfcTagWriterTags {
    const val CREATE = "nfcWriter.create"
    const val STATUS = "nfcWriter.status"
    const val SUCCESS = "nfcWriter.success"
}

/** Stateful entry of `NfcTagWriter` (spec 02 §2.4). */
@Composable
fun NfcTagWriterRoute(viewModel: NfcTagWriterViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val appHost = LocalAppSnackbarHostState.current
    val localHost = remember { SnackbarHostState() }
    val host = appHost ?: localHost
    val context = LocalContext.current
    val res = context.resources
    val haptics = rememberHaptics()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                NfcTagWriterEvent.SelectHomeMeetup -> host.showAppSnackbar(
                    res.getString(L10nR.string.nwSelectHomeMeetup),
                    Tone.Error,
                )
                NfcTagWriterEvent.OpenRollingQr -> navigator.navigateFromRoot(RollingQr)
                NfcTagWriterEvent.Back -> navigator.back()
                NfcTagWriterEvent.OpenNfcSettings -> openNfcSettings(context)
            }
        }
    }
    LaunchedEffect(state.success) { if (state.success) haptics.success() }
    NfcReaderModeEffect(
        active = state.waitingForTag,
        onTag = { viewModel.onAction(NfcTagWriterAction.TagDiscovered(it)) },
        onError = { viewModel.onAction(NfcTagWriterAction.SessionFailed(it)) },
    )
    NfcTagWriterScreen(
        state = state,
        onAction = viewModel::onAction,
        snackbarHost = { if (appHost == null) AppSnackbarHost(localHost) },
    )
}

/** Stateless writer: instructions and boxes, or the success view. */
@Composable
fun NfcTagWriterScreen(
    state: NfcTagWriterUiState,
    onAction: (NfcTagWriterAction) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHost: @Composable () -> Unit = {},
) {
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppMediumTopBar(
                title = stringResource(L10nR.string.writerCreateMeetupTag),
                onBack = { onAction(NfcTagWriterAction.Back) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    scrolledContainerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = onPrimary,
                    navigationIconContentColor = onPrimary,
                ),
            )
        },
        snackbarHost = snackbarHost,
    ) { padding ->
        AnimatedContent(
            targetState = state.success,
            transitionSpec = {
                (fadeIn(spring(stiffness = 380f)) + scaleIn(spring(dampingRatio = 0.6f, stiffness = 380f), 0.85f))
                    .togetherWith(fadeOut(spring(stiffness = 1600f)))
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            label = "writer",
        ) { success ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (success) SuccessContent(state) else WriterContent(state, onAction)
            }
        }
    }
    when (state.dialog) {
        null -> Unit
        WriterDialog.NFC_UNAVAILABLE -> NfcStateDialog(
            unsupported = true,
            body = stringResource(L10nR.string.verifyNoNfcLong) + stringResource(L10nR.string.writerUseRollingQr) +
                stringResource(L10nR.string.writerForYourMeetup),
            onDismiss = { onAction(NfcTagWriterAction.DismissDialog) },
        )
        WriterDialog.NFC_DISABLED -> NfcStateDialog(
            unsupported = false,
            body = stringResource(L10nR.string.nfcEnableHint) + stringResource(L10nR.string.writerToWriteTag) +
                stringResource(L10nR.string.nfcSettingsAndroid),
            onDismiss = { onAction(NfcTagWriterAction.DismissDialog) },
            extraLabel = stringResource(L10nR.string.nfcOpenSettings),
            onExtra = { onAction(NfcTagWriterAction.OpenNfcSettings) },
        )
    }
}

@Composable
private fun WriterContent(state: NfcTagWriterUiState, onAction: (NfcTagWriterAction) -> Unit) {
    PulsingCircle(Icons.Rounded.Nfc, minScale = 1f, maxScale = 1.2f, size = 160.dp)
    Gap(36.dp)
    Text(
        stringResource(L10nR.string.writerMeetupTag),
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
        modifier = Modifier.semantics { heading() },
    )
    Gap(10.dp)
    Text(
        stringResource(L10nR.string.writerHoldTagInstruction),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Gap(24.dp)
    val homeText = when (val home = state.home) {
        HomeMeetupInfo.Loading -> null
        HomeMeetupInfo.NoHome -> stringResource(L10nR.string.writerSelectHomeFirst)
        is HomeMeetupInfo.NotFound -> stringResource(R.string.native_writer_home_not_found, home.id)
        is HomeMeetupInfo.Found -> stringResource(R.string.native_meetup_pin_country, home.city, home.country)
    }
    InfoBox(
        icon = Icons.Rounded.Storefront,
        accent = MaterialTheme.colorScheme.primary,
        title = stringResource(L10nR.string.writerYourHomeMeetup),
        body = homeText,
    )
    if (state.estimatedSize > 0) {
        Gap(10.dp)
        InfoBox(
            icon = Icons.Rounded.Memory,
            accent = MaterialTheme.extendedColors.success,
            title = stringResource(L10nR.string.writerFitsNtag215, state.estimatedSize),
            body = null,
        )
    }
    Gap(10.dp)
    InfoBox(
        icon = Icons.Rounded.Schedule,
        accent = MaterialTheme.colorScheme.tertiary,
        title = stringResource(L10nR.string.writerValidity),
        body = stringResource(L10nR.string.writerCanOverwrite),
    )
    Gap(28.dp)
    PrimaryActionButton(
        text = stringResource(L10nR.string.writerCreateTag),
        icon = Icons.Rounded.Nfc,
        onClick = { onAction(NfcTagWriterAction.CreateTag) },
        enabled = !state.waitingForTag && state.home != HomeMeetupInfo.Loading,
        height = 60.dp,
        modifier = Modifier
            .widthIn(max = 320.dp)
            .testTag(NfcTagWriterTags.CREATE),
    )
    Gap(20.dp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (state.waitingForTag || state.status == WriterStatus.LoadingSession || state.status == WriterStatus.Formatting) {
            AppLoadingIndicator(size = 24.dp, contentDescription = stringResource(R.string.native_writer_writing))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            writerStatusText(state.status),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .testTag(NfcTagWriterTags.STATUS)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun SuccessContent(state: NfcTagWriterUiState) {
    val success = MaterialTheme.extendedColors.success
    ShapedIcon(Icons.Rounded.CheckCircle, success, size = 100.dp, modifier = Modifier.testTag(NfcTagWriterTags.SUCCESS))
    Gap(24.dp)
    Text(
        stringResource(L10nR.string.writerSuccess),
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        color = success,
        modifier = Modifier.semantics { heading() },
    )
    Gap(16.dp)
    Text(
        writerStatusText(state.status),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag(NfcTagWriterTags.STATUS),
    )
    Gap(20.dp)
    AppLoadingIndicator(size = 32.dp)
}

@Composable
private fun InfoBox(icon: ImageVector, accent: Color, title: String, body: String?) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            if (body != null) {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** The status line of [status]; errors carry Flutter's `"❌ "` prefix. */
@Composable
internal fun writerStatusText(status: WriterStatus): String = when (status) {
    WriterStatus.Ready -> stringResource(L10nR.string.writerReadyToWrite)
    WriterStatus.NoHomeMeetup -> stringResource(L10nR.string.writerNoHomeMeetup)
    WriterStatus.HomeNotFound -> stringResource(L10nR.string.writerHomeMeetupNotFound)
    WriterStatus.LoadingSession -> stringResource(L10nR.string.writerLoadingSession)
    WriterStatus.NoActiveSession -> stringResource(L10nR.string.writerNoActiveSession)
    WriterStatus.NoNfcDevice -> stringResource(L10nR.string.writerNoNfcDevice)
    WriterStatus.NfcDisabledHint -> stringResource(L10nR.string.nfcDisabledHint)
    WriterStatus.HoldTag -> stringResource(L10nR.string.writerHoldTag)
    WriterStatus.Formatting -> stringResource(L10nR.string.writerFormatting)
    WriterStatus.FormatFailed -> "❌ " + stringResource(L10nR.string.writerFormatFailed)
    WriterStatus.NoNdef -> "❌ " + stringResource(L10nR.string.writerNoNdef)
    WriterStatus.ReadOnly -> "❌ " + stringResource(L10nR.string.writerTagReadOnly)
    is WriterStatus.TooSmall -> "❌ " + stringResource(L10nR.string.writerTagTooSmall, status.actual, status.max) +
        stringResource(L10nR.string.writerUseNtag215)
    is WriterStatus.WriteFailed -> "❌ " + when (status.error) {
        NfcWriteError.TAG_REMOVED_EARLY -> stringResource(L10nR.string.writerTagRemovedEarly)
        NfcWriteError.TAG_LOST -> stringResource(L10nR.string.writerTagLost)
        NfcWriteError.OTHER -> status.raw
    }
    is WriterStatus.StartError -> stringResource(L10nR.string.writerStartError, status.detail)
    is WriterStatus.Written -> stringResource(L10nR.string.writerTagWritten) +
        stringResource(L10nR.string.writerCompactSize, status.size) +
        stringResource(L10nR.string.writerValidHours, NfcBadgePayload.VALID_HOURS) +
        stringResource(L10nR.string.writerJumpToQr)
}

@Preview(name = "NFC writer", backgroundColor = 0xFF0C0C0E, showBackground = true, heightDp = 860)
@Composable
private fun NfcTagWriterPreview() {
    EinundzwanzigTheme {
        NfcTagWriterScreen(
            NfcTagWriterUiState(home = HomeMeetupInfo.Found("Würzburg", "DE"), estimatedSize = 312),
            onAction = {},
        )
    }
}

@Preview(name = "NFC writer success", backgroundColor = 0xFF0C0C0E, showBackground = true)
@Composable
private fun NfcTagWriterSuccessPreview() {
    EinundzwanzigTheme {
        NfcTagWriterScreen(NfcTagWriterUiState(success = true, status = WriterStatus.Written(301)), onAction = {})
    }
}
