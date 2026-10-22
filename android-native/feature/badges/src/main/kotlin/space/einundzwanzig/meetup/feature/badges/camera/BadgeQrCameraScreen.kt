package space.einundzwanzig.meetup.feature.badges.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.result.LocalResultEventBus
import space.einundzwanzig.meetup.core.camera.QrImageResult
import space.einundzwanzig.meetup.core.camera.QrScanner
import space.einundzwanzig.meetup.core.camera.rememberQrImagePicker
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.NavResults
import space.einundzwanzig.meetup.core.navigation.sendResult
import space.einundzwanzig.meetup.core.ui.component.AppTopBar
import space.einundzwanzig.meetup.core.ui.qr.ScannerOverlay
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.issuance.scan.BadgeQrParser
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags of the badge camera. */
object BadgeQrCameraTags {
    const val GALLERY = "badgeQrCamera.gallery"
    const val INSTRUCTION = "badgeQrCamera.instruction"
}

/**
 * Picks the first barcode text the badge scanner accepts (Dart `_QRScannerScreen._onDetect`,
 * spec 02 §2.2): the app-review payload or a JSON object with `t`/`type`; `null` = ignore.
 */
internal fun firstAcceptedBadgeQr(values: List<String?>): String? = values.firstOrNull { BadgeQrParser.parse(it) != null }

/**
 * Stateful entry of `BadgeQrCamera` (spec 02 §2.2): the first accepted code is sent as
 * [NavResults.BadgeQrScanned] and the screen pops; the gallery stays silent without a match.
 */
@Composable
fun BadgeQrCameraRoute() {
    BadgeQrCameraRoute(scanner = { onResult, enabled -> QrScanner(onResult = onResult, enabled = enabled) })
}

/**
 * [BadgeQrCameraRoute] with a replaceable camera [scanner] and gallery picker [galleryResults]
 * (gets the result handler, returns the launch action); tests feed codes through them.
 */
@Composable
internal fun BadgeQrCameraRoute(
    scanner: @Composable BoxScope.(onResult: (String) -> Boolean, enabled: Boolean) -> Unit,
    galleryResults: @Composable (onResult: (QrImageResult) -> Unit) -> (() -> Unit) = { handler ->
        val picker = rememberQrImagePicker(onResult = handler)
        picker::launch
    },
) {
    val navigator = LocalNavigator.current
    val bus = LocalResultEventBus.current
    var handled by rememberSaveable { mutableStateOf(false) }
    val deliver: (String) -> Boolean = { raw ->
        if (!handled && firstAcceptedBadgeQr(listOf(raw)) != null) {
            handled = true
            bus.sendResult(NavResults.BadgeQrScanned, raw)
            navigator.back()
            true
        } else {
            handled
        }
    }
    val launchPicker = galleryResults { result -> galleryCode(result)?.let(deliver) }
    BadgeQrCameraScreen(
        onBack = navigator::back,
        onPickImage = launchPicker,
        galleryEnabled = !handled,
    ) {
        scanner(deliver, !handled)
    }
}

/** The accepted code of a gallery pick; `null` (Dart: silently return) for no match or errors. */
internal fun galleryCode(result: QrImageResult): String? =
    (result as? QrImageResult.Codes)?.let { firstAcceptedBadgeQr(it.values) }

/**
 * Stateless badge camera: top bar with gallery action, the camera [content] (full bleed with
 * the scanner overlay) and the instruction box 60 dp above the bottom.
 */
@Composable
fun BadgeQrCameraScreen(
    onBack: () -> Unit,
    onPickImage: () -> Unit,
    modifier: Modifier = Modifier,
    galleryEnabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            AppTopBar(
                title = stringResource(L10nR.string.verifyScanQr),
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = onPickImage,
                        enabled = galleryEnabled,
                        modifier = Modifier.testTag(BadgeQrCameraTags.GALLERY),
                    ) {
                        Icon(
                            Icons.Outlined.PhotoLibrary,
                            contentDescription = stringResource(L10nR.string.qrLoadFromGallery),
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            content()
            Text(
                text = stringResource(L10nR.string.verifyScanQrInstruction),
                // Over the camera scrim in both themes, like the camera panels.
                color = MaterialTheme.extendedColors.onScrim,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 40.dp, end = 40.dp, bottom = 60.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                    .padding(16.dp)
                    .testTag(BadgeQrCameraTags.INSTRUCTION),
            )
        }
    }
}

@Preview(name = "Badge camera", backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun BadgeQrCameraPreview() {
    EinundzwanzigTheme {
        BadgeQrCameraScreen(onBack = {}, onPickImage = {}) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim),
            ) { ScannerOverlay() }
        }
    }
}
