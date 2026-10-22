package space.einundzwanzig.meetup.feature.reputation.person

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.result.LocalResultEventBus
import space.einundzwanzig.meetup.core.camera.QrScanner
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.NavResults
import space.einundzwanzig.meetup.core.navigation.sendResult
import space.einundzwanzig.meetup.core.ui.component.AppTopBar
import space.einundzwanzig.meetup.core.ui.haptics.rememberHaptics
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags of the npub camera. */
internal object NpubCameraTags {
    const val HINT = "vp_camera_hint"
}

/**
 * Stateful entry of [VerifyPersonQrCamera][space.einundzwanzig.meetup.core.navigation.VerifyPersonQrCamera]
 * (spec 04 §2.7.1): the first code [NpubExtractor] accepts is sent as [NavResults.NpubScanned]
 * with a medium haptic, then the screen pops. Other codes are ignored without feedback.
 */
@Composable
internal fun VerifyPersonQrCameraRoute(
    // Camera slot: the real [QrScanner]; tests pass a fake that feeds codes.
    scanner: @Composable (onResult: (String) -> Boolean, enabled: Boolean) -> Unit = { onResult, enabled ->
        QrScanner(onResult = onResult, enabled = enabled)
    },
) {
    val navigator = LocalNavigator.current
    val bus = LocalResultEventBus.current
    val haptics = rememberHaptics()
    var done by rememberSaveable { mutableStateOf(false) }
    VerifyPersonQrCameraScreen(onBack = navigator::back) {
        scanner(
            { raw ->
                val npub = if (done) null else NpubExtractor.extract(raw)
                if (npub != null) {
                    done = true
                    haptics.medium()
                    bus.sendResult(NavResults.NpubScanned, npub)
                    navigator.back()
                    true
                } else {
                    done
                }
            },
            !done,
        )
    }
}

/** Stateless npub camera: transparent top bar, camera [content] and the hint box. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VerifyPersonQrCameraScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.scrim,
        topBar = {
            AppTopBar(
                title = stringResource(L10nR.string.vpScanQr),
                onBack = onBack,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                ),
            )
        },
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
            content()
            val shape = RoundedCornerShape(10.dp)
            Text(
                text = stringResource(L10nR.string.vpEnterNpub),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 40.dp, end = 40.dp, bottom = 60.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), shape)
                    .border(1.dp, MaterialTheme.colorScheme.primary, shape)
                    .padding(16.dp)
                    .testTag(NpubCameraTags.HINT),
            )
        }
    }
}

@Preview
@Composable
private fun VerifyPersonQrCameraPreview() {
    EinundzwanzigTheme { VerifyPersonQrCameraScreen(onBack = {}) {} }
}
