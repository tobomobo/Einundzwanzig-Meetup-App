package space.einundzwanzig.meetup.feature.reputation.scan

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.camera.QrImageResult
import space.einundzwanzig.meetup.core.camera.QrScanner
import space.einundzwanzig.meetup.core.camera.QrScannerState
import space.einundzwanzig.meetup.core.camera.rememberQrImagePicker
import space.einundzwanzig.meetup.core.camera.rememberQrScannerState
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.ReputationScanResult
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.AppTopBar
import space.einundzwanzig.meetup.core.ui.component.LocalAppSnackbarHostState
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.core.ui.component.showAppSnackbar
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.elevationShadow
import space.einundzwanzig.meetup.data.identity.review.AppReviewDemo
import space.einundzwanzig.meetup.feature.reputation.common.cyanColor
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags of the reputation scanner. */
internal object ReputationScannerTags {
    const val GALLERY_BUTTON = "rep_scan_gallery"
    const val GALLERY_ACTION = "rep_scan_gallery_action"
    const val HINT = "rep_scan_hint"
    const val DEMO_DIALOG = "rep_scan_demo"
}

/** UI state of the scanner: `isScanned` blocks further hits (Dart `_isScanned`). */
@Immutable
internal data class ReputationScannerUiState(
    val isScanned: Boolean = false,
    val showDemoDialog: Boolean = false,
    val isDecodingImage: Boolean = false,
)

/**
 * Saves the hit flags across recreation: `QrScannerState.isStopped` survives it, so the demo
 * dialog (whose dismissal resumes the scanner) must survive too. `isDecodingImage` comes from
 * the picker and is not saved.
 */
internal val ReputationScannerUiStateSaver = listSaver<ReputationScannerUiState, Boolean>(
    save = { listOf(it.isScanned, it.showDemoDialog) },
    restore = { ReputationScannerUiState(isScanned = it[0], showDemoDialog = it[1]) },
)

internal sealed interface ReputationScannerAction {
    data object PickFromGallery : ReputationScannerAction
    data object DismissDemo : ReputationScannerAction
}

/** Camera slot of [ReputationScannerRoute]: the real [QrScanner]; tests pass a fake that feeds codes. */
internal typealias ReputationCameraSlot = @Composable (onResult: (String) -> Boolean, state: QrScannerState, enabled: Boolean) -> Unit

/** Gallery picker as seen by [ReputationScannerRoute]: decoding flag and launch action. */
internal class ReputationGalleryPicker(val isDecoding: Boolean, val launch: () -> Unit)

/**
 * Gallery slot of [ReputationScannerRoute]: the real Photo Picker ([rememberQrImagePicker]);
 * tests pass a fake that feeds [QrImageResult]s.
 */
internal typealias ReputationGallerySlot = @Composable (onResult: (QrImageResult) -> Unit) -> ReputationGalleryPicker

/**
 * Stateful entry of [ReputationScanner][space.einundzwanzig.meetup.core.navigation.ReputationScanner]
 * (`SecureQRScanner`, spec 04 §2.3). Every reputation hit replaces this screen with the result.
 */
@Composable
internal fun ReputationScannerRoute(
    scanner: ReputationCameraSlot = { onResult, scannerState, enabled ->
        QrScanner(onResult = onResult, state = scannerState, enabled = enabled)
    },
    gallery: ReputationGallerySlot = { onResult ->
        val picker = rememberQrImagePicker(onResult)
        ReputationGalleryPicker(isDecoding = picker.isDecoding, launch = picker::launch)
    },
) {
    val navigator = LocalNavigator.current
    val appHost = LocalAppSnackbarHostState.current
    val localHost = remember { SnackbarHostState() }
    val host = appHost ?: localHost
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val scannerState = rememberQrScannerState()
    var state by rememberSaveable(stateSaver = ReputationScannerUiStateSaver) { mutableStateOf(ReputationScannerUiState()) }
    // Safety net: a stopped scanner without a pending hit (e.g. restored state) would never
    // deliver again, so resume it.
    LaunchedEffect(Unit) {
        if (scannerState.isStopped && !state.isScanned && !state.showDemoDialog) scannerState.resume()
    }

    fun handle(hit: ScanHit): Boolean = when (hit) {
        ScanHit.Demo -> {
            state = state.copy(isScanned = true, showDemoDialog = true)
            true
        }
        is ScanHit.Reputation -> {
            state = state.copy(isScanned = true)
            navigator.replaceTop(ReputationScanResult(hit.code))
            true
        }
        ScanHit.Ignore -> false
    }

    val picker = gallery { result ->
        val outcome = when (result) {
            is QrImageResult.Codes -> ReputationScanLogic.classifyGallery(result.values)
            QrImageResult.NoCode -> GalleryOutcome.NoCode
            is QrImageResult.Failed -> GalleryOutcome.Failed(result.error)
        }
        when (outcome) {
            is GalleryOutcome.Hit -> {
                scannerState.stop()
                handle(outcome.hit)
            }
            GalleryOutcome.NoCode -> scope.launch {
                host.showAppSnackbar(resources.getString(L10nR.string.qrNoCodeInImage), Tone.Warning)
            }
            GalleryOutcome.NotEinundzwanzig -> scope.launch {
                host.showAppSnackbar(resources.getString(L10nR.string.qrNotEinundzwanzig), Tone.Warning)
            }
            is GalleryOutcome.Failed -> scope.launch {
                host.showAppSnackbar(resources.getString(L10nR.string.qrProcessError, outcome.error.toString()), Tone.Error)
            }
        }
    }

    ReputationScannerScreen(
        state = state.copy(isDecodingImage = picker.isDecoding),
        onAction = { action ->
            when (action) {
                ReputationScannerAction.PickFromGallery -> picker.launch()
                ReputationScannerAction.DismissDemo -> {
                    // Flutter resets `_isScanned` after the demo dialog.
                    state = state.copy(isScanned = false, showDemoDialog = false)
                    scannerState.resume()
                }
            }
        },
        onBack = navigator::back,
        snackbarHost = { if (appHost == null) AppSnackbarHost(localHost) },
        camera = {
            scanner(
                { raw -> !state.isScanned && handle(ReputationScanLogic.classify(raw)) },
                scannerState,
                !state.showDemoDialog,
            )
        },
    )
}

/** Stateless reputation scanner: camera slot, gallery action, hint card and demo dialog. */
// The camera fills the viewport beneath the translucent top bar. QrScanner reserves the safe
// top inset for its torch; the bottom controls below apply navigationBarsPadding themselves.
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ReputationScannerScreen(
    state: ReputationScannerUiState,
    onAction: (ReputationScannerAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHost: @Composable () -> Unit = {},
    camera: @Composable () -> Unit = {},
) {
    val galleryEnabled = !state.isScanned && !state.isDecodingImage
    val galleryLabel = stringResource(L10nR.string.qrLoadFromGallery)
    Scaffold(
        modifier = modifier,
        snackbarHost = snackbarHost,
        topBar = {
            AppTopBar(
                title = stringResource(L10nR.string.qrScanTitle),
                onBack = onBack,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                    scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                ),
                actions = {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                        tooltip = { PlainTooltip { Text(galleryLabel) } },
                        state = rememberTooltipState(),
                    ) {
                        IconButton(
                            onClick = { onAction(ReputationScannerAction.PickFromGallery) },
                            enabled = galleryEnabled,
                            modifier = Modifier.testTag(ReputationScannerTags.GALLERY_ACTION),
                        ) {
                            Icon(Icons.Outlined.PhotoLibrary, contentDescription = galleryLabel, tint = if (galleryEnabled) cyanColor else cyanColor.copy(alpha = 0.4f))
                        }
                    }
                },
            )
        },
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
            camera()
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, bottom = 60.dp),
            ) {
                FilledTonalButton(
                    onClick = { onAction(ReputationScannerAction.PickFromGallery) },
                    enabled = galleryEnabled,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag(ReputationScannerTags.GALLERY_BUTTON),
                ) {
                    if (state.isDecodingImage) {
                        LoadingIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(galleryLabel, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                val shape = RoundedCornerShape(14.dp)
                val colors = MaterialTheme.colorScheme
                Text(
                    text = stringResource(L10nR.string.qrScanHint),
                    color = colors.onSurface,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .elevationShadow(3f, shape)
                        .background(Brush.linearGradient(listOf(colors.surfaceContainer.copy(alpha = 0.94f), colors.surfaceContainerLow.copy(alpha = 0.94f))), shape)
                        .border(1.dp, colors.primary.copy(alpha = 0.24f), shape)
                        .padding(16.dp)
                        .testTag(ReputationScannerTags.HINT),
                )
            }
        }
    }
    if (state.showDemoDialog) {
        // App-review demo (English literal on purpose, spec 04 §2.3 step 1).
        AlertDialog(
            onDismissRequest = { onAction(ReputationScannerAction.DismissDemo) },
            title = { Text(AppReviewDemo.SUCCESS_TITLE, fontWeight = FontWeight.Bold) },
            text = { Text(AppReviewDemo.SUCCESS_BODY, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = { TextButton(onClick = { onAction(ReputationScannerAction.DismissDemo) }) { Text("OK") } },
            modifier = Modifier.testTag(ReputationScannerTags.DEMO_DIALOG),
        )
    }
}

@Preview
@Composable
private fun ReputationScannerPreview() {
    EinundzwanzigTheme {
        ReputationScannerScreen(state = ReputationScannerUiState(), onAction = {}, onBack = {})
    }
}
