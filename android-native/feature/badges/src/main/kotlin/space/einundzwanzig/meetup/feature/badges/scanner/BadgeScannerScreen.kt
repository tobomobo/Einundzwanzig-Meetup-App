@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package space.einundzwanzig.meetup.feature.badges.scanner

import android.content.res.Resources
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.MilitaryTech
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.dart.toStringAsFixed
import space.einundzwanzig.meetup.core.navigation.BadgeQrCamera
import space.einundzwanzig.meetup.core.navigation.BadgeWallet
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.NavResultEffect
import space.einundzwanzig.meetup.core.navigation.NavResults
import space.einundzwanzig.meetup.core.ui.component.AppContainedLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.AppMediumTopBar
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarController
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarControllerEffect
import space.einundzwanzig.meetup.core.ui.component.LocalAppSnackbarController
import space.einundzwanzig.meetup.core.ui.component.InfoBanner
import space.einundzwanzig.meetup.core.ui.component.LocalAppSnackbarHostState
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.core.ui.haptics.rememberHaptics
import space.einundzwanzig.meetup.core.ui.theme.Dimens
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.security.ExpiryInfo
import space.einundzwanzig.meetup.data.identity.review.AppReviewDemo
import space.einundzwanzig.meetup.data.issuance.nfc.NfcPayloadError
import space.einundzwanzig.meetup.data.issuance.scan.AdminCheckInfo
import space.einundzwanzig.meetup.data.issuance.scan.ClaimInfo
import space.einundzwanzig.meetup.data.issuance.scan.FoundInfo
import space.einundzwanzig.meetup.data.issuance.scan.PendingBadge
import space.einundzwanzig.meetup.data.issuance.session.NonceStatus
import space.einundzwanzig.meetup.data.issuance.session.NonceValidation
import space.einundzwanzig.meetup.feature.badges.R
import space.einundzwanzig.meetup.feature.badges.common.Gap
import space.einundzwanzig.meetup.feature.badges.common.NfcStateDialog
import space.einundzwanzig.meetup.feature.badges.common.PrimaryActionButton
import space.einundzwanzig.meetup.feature.badges.common.PulsingCircle
import space.einundzwanzig.meetup.feature.badges.common.BadgeTexts
import space.einundzwanzig.meetup.feature.badges.common.ResourceScanStrings
import space.einundzwanzig.meetup.feature.badges.common.ShapedIcon
import space.einundzwanzig.meetup.feature.badges.nfc.NfcReaderModeEffect
import space.einundzwanzig.meetup.feature.badges.nfc.openNfcSettings
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags of the scanner. */
object BadgeScannerTags {
    const val SCAN_QR = "badgeScanner.scanQr"
    const val SCAN_NFC = "badgeScanner.scanNfc"
    const val STATUS = "badgeScanner.status"
    const val CONFIRMATION = "badgeScanner.confirmation"
    const val ADD = "badgeScanner.add"
    const val OPEN_WALLET = "badgeScanner.openWallet"
    const val CLOSE = "badgeScanner.close"
    const val SAVING = "badgeScanner.saving"
}

/**
 * Stateful entry of `BadgeScanner` (spec 02 §2.1). Snackbars go to the app-lifetime
 * [LocalAppSnackbarController] (Flutter's root messenger), so they survive the `replaceTop` to
 * the wallet and activity recreation. Without one (tests, previews) a composition-scoped
 * controller feeds the app or local host; messages still queued when the scanner leaves are then
 * dropped, including the late co-attendance result.
 */
@Composable
fun BadgeScannerRoute(viewModel: BadgeScannerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val appHost = LocalAppSnackbarHostState.current
    val localHost = remember { SnackbarHostState() }
    val controller = LocalAppSnackbarController.current ?: remember { AppSnackbarController() }.also {
        AppSnackbarControllerEffect(it, appHost ?: localHost)
    }
    val context = LocalContext.current
    val res = context.resources
    val haptics = rememberHaptics()
    val unknownLabel = stringResource(L10nR.string.verifyUnknownMeetup)

    NavResultEffect(NavResults.BadgeQrScanned) { raw ->
        viewModel.onAction(BadgeScannerAction.QrScanned(raw, unknownLabel))
    }

    LaunchedEffect(viewModel, controller) {
        viewModel.events.collect { event ->
            when (event) {
                BadgeScannerEvent.OpenCamera -> navigator.navigate(BadgeQrCamera)
                BadgeScannerEvent.OpenWallet -> navigator.replaceTop(BadgeWallet)
                BadgeScannerEvent.Close -> navigator.back()
                BadgeScannerEvent.OpenNfcSettings -> openNfcSettings(context)
                is BadgeScannerEvent.Haptic -> if (event.success) haptics.success() else haptics.error()
                is BadgeScannerEvent.Snackbar -> controller.show(snackbarSpec(res, event.kind))
            }
        }
    }

    if (state.nfcEnabled) {
        NfcReaderModeEffect(
            active = state.readingNfc,
            onTag = { viewModel.onAction(BadgeScannerAction.NfcTagDiscovered(it, unknownLabel)) },
            onError = { viewModel.onAction(BadgeScannerAction.NfcSessionFailed(it)) },
        )
    }

    BadgeScannerScreen(
        state = state,
        onAction = { action ->
            if (action is BadgeScannerAction.CoAttendanceAnswered && action.publish) {
                // Resolve both texts now: the result arrives after this screen (and maybe the
                // activity) is gone, so the sink keeps only strings and the app-lifetime queue.
                val published = snackbarSpec(res, ScannerSnackbar.CO_ATTENDANCE_PUBLISHED)
                val failed = snackbarSpec(res, ScannerSnackbar.CO_ATTENDANCE_FAILED)
                val sink = ScannerResultSink { kind ->
                    controller.show(if (kind == ScannerSnackbar.CO_ATTENDANCE_PUBLISHED) published else failed)
                }
                viewModel.onAction(action.copy(resultSink = sink))
            } else {
                viewModel.onAction(action)
            }
        },
        snackbarHost = { if (appHost == null) AppSnackbarHost(localHost) },
    )
}

private fun AppSnackbarController.show(spec: SnackbarSpec) = show(spec.message, spec.tone, spec.durationMillis)

/** Stateless scanner: scanner view or confirmation view, plus dialogs. */
@Composable
fun BadgeScannerScreen(
    state: BadgeScannerUiState,
    onAction: (BadgeScannerAction) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHost: @Composable () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppMediumTopBar(
                title = stringResource(L10nR.string.verifyScanBadge),
                onBack = { onAction(BadgeScannerAction.Close) },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = snackbarHost,
    ) { padding ->
        AnimatedContent(
            targetState = state.result,
            contentKey = { it == null },
            transitionSpec = {
                (fadeIn(spring(stiffness = 380f)) + scaleIn(spring(dampingRatio = 0.7f, stiffness = 380f), 0.92f))
                    .togetherWith(fadeOut(spring(stiffness = 1600f)))
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            label = "scannerContent",
        ) { result ->
            if (result == null) ScannerView(state, onAction) else ConfirmationView(result, onAction)
        }
    }
    ScannerDialogs(state, onAction)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScannerView(state: BadgeScannerUiState, onAction: (BadgeScannerAction) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PulsingCircle(if (state.nfcEnabled) Icons.Rounded.Nfc else Icons.Rounded.QrCodeScanner)
        Gap(40.dp)
        Text(
            text = stringResource(L10nR.string.verifyScanBadge),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
            modifier = Modifier.semantics { heading() },
        )
        Gap(12.dp)
        Text(
            text = stringResource(L10nR.string.verifyScanInstruction),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Gap(36.dp)
        val enabled = !state.processing
        if (state.nfcEnabled) {
            Button(
                onClick = { onAction(BadgeScannerAction.ScanNfcClicked) },
                enabled = enabled,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier
                    .widthIn(min = 250.dp)
                    .heightIn(min = 56.dp)
                    .testTag(BadgeScannerTags.SCAN_NFC),
            ) {
                Icon(Icons.Rounded.Nfc, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(L10nR.string.verifyScanNfc), fontWeight = FontWeight.Bold)
            }
            Gap(16.dp)
        }
        OutlinedButton(
            onClick = { onAction(BadgeScannerAction.ScanQrClicked) },
            enabled = enabled,
            shapes = ButtonDefaults.shapes(),
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.tertiary),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.tertiary),
            modifier = Modifier
                .widthIn(min = 250.dp)
                .heightIn(min = 56.dp)
                .testTag(BadgeScannerTags.SCAN_QR),
        ) {
            Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(L10nR.string.verifyScanQrCaps), fontWeight = FontWeight.Bold)
        }
        Gap(28.dp)
        if (state.processing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppLoadingIndicator(size = 28.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.native_scanner_checking),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val isError = state.status.isError()
            Text(
                text = scanStatusText(state.status),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .testTag(BadgeScannerTags.STATUS)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

private fun ScanStatus.isError(): Boolean = when (this) {
    ScanStatus.Ready, ScanStatus.CheckingNfc, ScanStatus.WaitingNfc -> false
    else -> true
}

/** The status line text of [status] (`_statusText`). */
@Composable
internal fun scanStatusText(status: ScanStatus): String = when (status) {
    ScanStatus.Ready -> stringResource(L10nR.string.verifyReadyToScan)
    ScanStatus.CheckingNfc -> stringResource(L10nR.string.verifyCheckingNfc)
    ScanStatus.WaitingNfc -> stringResource(L10nR.string.verifyWaitingNfc)
    ScanStatus.NoNfcDevice -> stringResource(L10nR.string.verifyNoNfcDevice)
    ScanStatus.NfcDisabledHint -> stringResource(L10nR.string.nfcDisabledHint)
    is ScanStatus.NonceExpired -> stringResource(L10nR.string.verifyErrQrExpired, nonceMessage(status.validation))
    is ScanStatus.Rejected -> stringResource(L10nR.string.verifyErrPrefix, rejectText(status))
    is ScanStatus.NfcRejected -> when (val e = status.error) {
        NfcPayloadError.NoNdef -> stringResource(L10nR.string.verifyErrNoNdef)
        NfcPayloadError.TagEmpty -> stringResource(L10nR.string.verifyErrTagEmpty)
        NfcPayloadError.PayloadEmpty -> stringResource(L10nR.string.verifyErrPayloadEmpty)
        NfcPayloadError.InvalidFormat -> stringResource(L10nR.string.verifyErrInvalidFormat)
        is NfcPayloadError.InvalidTag -> stringResource(L10nR.string.verifyErrInvalidTag, e.detail)
        is NfcPayloadError.ReadError -> stringResource(L10nR.string.verifyErrReadError, e.detail)
    }
    is ScanStatus.NfcSessionError -> stringResource(L10nR.string.verifyErrNfcError, status.detail)
    ScanStatus.SelfScan ->
        "✗ " + stringResource(L10nR.string.verifyCantSelfBadge) + stringResource(L10nR.string.verifyAskScan)
}

@Composable
private fun rejectText(status: ScanStatus.Rejected): String = when (val reason = status.reason) {
    null -> status.message
    is RejectReason.Verify -> BadgeTexts.verifyFailure(LocalContext.current.resources, reason.failure)
    RejectReason.RollingQrMalformed -> stringResource(R.string.native_nonce_malformed)
}

@Composable
private fun nonceMessage(validation: NonceValidation): String = when (validation.status) {
    NonceStatus.EXPIRED -> stringResource(R.string.native_nonce_expired, validation.ageSeconds.toInt())
    NonceStatus.MALFORMED -> stringResource(R.string.native_nonce_malformed)
    else -> validation.message
}

@Composable
private fun ConfirmationView(result: ScanResultUi, onAction: (BadgeScannerAction) -> Unit) {
    val res = LocalContext.current.resources
    val found = result as? ScanResultUi.Found
    val accent = when {
        found == null -> MaterialTheme.colorScheme.onSurfaceVariant
        found.info.unknownSigner -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.extendedColors.success
    }
    val icon = when {
        found == null -> Icons.Rounded.Inventory2
        found.info.unknownSigner -> Icons.Rounded.WarningAmber
        else -> Icons.Rounded.MilitaryTech
    }
    val title = stringResource(
        if (result is ScanResultUi.AlreadyCollected) L10nR.string.verifyAlreadyCollected else L10nR.string.verifyBadgeFound,
    )
    val message = remember(result, res) { confirmationText(res, result) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, top = 48.dp, end = 24.dp, bottom = 40.dp)
            .testTag(BadgeScannerTags.CONFIRMATION),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShapedIcon(icon, accent)
        Gap(20.dp)
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.6.sp,
            ),
            color = accent,
            modifier = Modifier.semantics { heading() },
        )
        Gap(20.dp)
        Surface(
            shape = Dimens.TileShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(Dimens.Hairline, accent.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                modifier = Modifier.padding(18.dp),
            )
        }
        Gap(28.dp)
        if (found == null) {
            PrimaryActionButton(
                text = stringResource(L10nR.string.verifyOpenWallet),
                icon = Icons.Rounded.AccountBalanceWallet,
                onClick = { onAction(BadgeScannerAction.OpenWallet) },
                height = 52.dp,
                modifier = Modifier.testTag(BadgeScannerTags.OPEN_WALLET),
            )
        } else {
            PrimaryActionButton(
                text = stringResource(L10nR.string.verifyAddToWallet),
                icon = Icons.Rounded.AddCircle,
                onClick = { onAction(BadgeScannerAction.AddToWallet) },
                height = 52.dp,
                modifier = Modifier.testTag(BadgeScannerTags.ADD),
            )
        }
        Gap(12.dp)
        OutlinedButton(
            onClick = { onAction(BadgeScannerAction.Close) },
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag(BadgeScannerTags.CLOSE),
        ) {
            Text(stringResource(if (found == null) L10nR.string.verifyClose else L10nR.string.dialogCancel))
        }
    }
}

/** The confirmation card text of [result]. */
internal fun confirmationText(res: Resources, result: ScanResultUi): String = when (result) {
    is ScanResultUi.Found -> result.info.message(ResourceScanStrings(res))
    is ScanResultUi.AlreadyCollected -> res.getString(
        if (result.isEvent) L10nR.string.verifyAlreadyEvent else L10nR.string.verifyAlreadyToday,
        result.fullName,
    )
    is ScanResultUi.KeptAside -> res.getString(L10nR.string.badgesHeldAside)
}

@Composable
private fun ScannerDialogs(state: BadgeScannerUiState, onAction: (BadgeScannerAction) -> Unit) {
    val dismiss = { onAction(BadgeScannerAction.DismissDialog) }
    when (val dialog = state.dialog) {
        null -> Unit
        ScannerDialog.ReviewDemo -> AlertDialog(
            onDismissRequest = dismiss,
            icon = { Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = MaterialTheme.extendedColors.success) },
            title = { Text(AppReviewDemo.SUCCESS_TITLE) },
            text = { Text(AppReviewDemo.SUCCESS_BODY, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(L10nR.string.actionOk)) } },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
        is ScannerDialog.TooFar -> AlertDialog(
            onDismissRequest = dismiss,
            icon = { Icon(Icons.Rounded.LocationOff, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(L10nR.string.gpsTooFar)) },
            text = {
                Text(
                    stringResource(
                        L10nR.string.gpsTooFarSub,
                        dialog.distanceKm.toStringAsFixed(1),
                        dialog.allowedKm.toStringAsFixed(0),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(L10nR.string.actionOk)) } },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
        is ScannerDialog.CoAttendanceOptIn -> CoAttendanceDialog(onAction)
        ScannerDialog.NfcUnavailable -> NfcStateDialog(
            unsupported = true,
            body = stringResource(L10nR.string.verifyNoNfcLong) + stringResource(L10nR.string.verifyUseQrInstead) +
                stringResource(L10nR.string.verifyToGetBadge),
            onDismiss = dismiss,
            extraLabel = stringResource(L10nR.string.verifyScanQrCaps),
            onExtra = { onAction(BadgeScannerAction.NfcDialogScanQr) },
        )
        ScannerDialog.NfcDisabled -> NfcStateDialog(
            unsupported = false,
            body = stringResource(L10nR.string.nfcEnableHint) + stringResource(L10nR.string.verifyToGetBadge) +
                "\n\n" + stringResource(L10nR.string.nfcSettingsAndroid),
            onDismiss = dismiss,
            extraLabel = stringResource(L10nR.string.nfcOpenSettings),
            onExtra = { onAction(BadgeScannerAction.NfcOpenSettings) },
        )
    }
    state.busy?.let { BusyDialog(it) }
}

@Composable
private fun CoAttendanceDialog(onAction: (BadgeScannerAction) -> Unit) {
    val accent = MaterialTheme.colorScheme.tertiary
    AlertDialog(
        onDismissRequest = { onAction(BadgeScannerAction.DismissDialog) },
        icon = { Icon(Icons.Rounded.Hub, contentDescription = null, tint = accent) },
        title = { Text(stringResource(L10nR.string.caOptInTitle)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(L10nR.string.caOptInBody), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Gap(14.dp)
                InfoBanner(text = stringResource(L10nR.string.caOptInPrivacy), tone = Tone.Warning)
            }
        },
        confirmButton = {
            Button(
                onClick = { onAction(BadgeScannerAction.CoAttendanceAnswered(true)) },
                colors = ButtonDefaults.buttonColors(containerColor = accent),
            ) { Text(stringResource(L10nR.string.caOptInYes), fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = { onAction(BadgeScannerAction.CoAttendanceAnswered(false)) }) {
                Text(stringResource(L10nR.string.caOptInNo), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
}

/** Non-dismissible blocking indicator while locating/saving or publishing (Flutter's spinner dialog). */
@Composable
private fun BusyDialog(kind: BusyKind) {
    val label = when (kind) {
        BusyKind.LOCATING -> stringResource(R.string.native_scanner_locating)
    }
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Box(
            Modifier
                .testTag(BadgeScannerTags.SAVING)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            AppContainedLoadingIndicator(size = 72.dp)
        }
    }
}

internal data class SnackbarSpec(val message: String, val tone: Tone, val durationMillis: Long)

/** Message, colour and duration of each save snackbar (spec 02 §2.1 steps 8–14). */
internal fun snackbarSpec(res: Resources, kind: ScannerSnackbar): SnackbarSpec = when (kind) {
    ScannerSnackbar.UNVERIFIED_PRESENCE -> SnackbarSpec(res.getString(L10nR.string.badgeUnverifiedInfo), Tone.Neutral, 5_000)
    ScannerSnackbar.HELD_ASIDE -> SnackbarSpec(res.getString(L10nR.string.badgesHeldAside), Tone.Neutral, 8_000)
    ScannerSnackbar.SAVE_FAILED -> SnackbarSpec(res.getString(L10nR.string.badgeSaveFailed), Tone.Error, 8_000)
    ScannerSnackbar.DUPLICATE -> SnackbarSpec(res.getString(L10nR.string.verifyBadgeDuplicate), Tone.Warning, 4_000)
    ScannerSnackbar.SAVED -> SnackbarSpec(res.getString(L10nR.string.verifyBadgeSaved), Tone.Success, 4_000)
    ScannerSnackbar.CO_ATTENDANCE_PUBLISHED -> SnackbarSpec(res.getString(L10nR.string.caPublished), Tone.Success, 3_000)
    ScannerSnackbar.CO_ATTENDANCE_FAILED -> SnackbarSpec(res.getString(L10nR.string.caPublishFailed), Tone.Error, 6_000)
}

// ---------------------------------------------------------------------------------------------
// Previews

private val previewInfo = FoundInfo(
    fullName = "Würzburg, DE",
    blockHeight = 915_000,
    verifiedBy = "npub1abcdefgh...12345678",
    sigVersion = 2,
    claim = ClaimInfo.Bound,
    adminCheck = AdminCheckInfo.KnownOrganizer("Satoshi"),
    expiry = ExpiryInfo.Valid(3 * 3600 + 12 * 60),
    unknownSigner = false,
)

internal val previewPending = PendingBadge(
    MeetupBadge(id = "wuerzburg-de", meetupName = "Würzburg, DE", date = DartDateTime(0, false), iconPath = ""),
)

@Preview(name = "Scanner", backgroundColor = 0xFF0C0C0E, showBackground = true)
@Composable
private fun BadgeScannerPreview() {
    EinundzwanzigTheme {
        BadgeScannerScreen(BadgeScannerUiState(status = ScanStatus.SelfScan), onAction = {})
    }
}

@Preview(name = "Found", backgroundColor = 0xFF0C0C0E, showBackground = true, heightDp = 800)
@Composable
private fun BadgeFoundPreview() {
    EinundzwanzigTheme {
        BadgeScannerScreen(BadgeScannerUiState(result = ScanResultUi.Found(previewPending, previewInfo)), onAction = {})
    }
}
