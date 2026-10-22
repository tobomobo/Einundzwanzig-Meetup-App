package space.einundzwanzig.meetup.core.camera

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NoPhotography
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import space.einundzwanzig.meetup.core.ui.motion.ExpressiveSprings
import space.einundzwanzig.meetup.core.ui.motion.rememberReducedMotion
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme

/**
 * Shown instead of the camera while the permission is missing: an explanation and the matching
 * action (ask / ask again / open the app settings when denied forever).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CameraPermissionContent(
    status: CameraPermissionStatus,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val permanentlyDenied = status == CameraPermissionStatus.PermanentlyDenied
    CameraMessagePanel(
        icon = if (permanentlyDenied) Icons.Rounded.NoPhotography else Icons.Rounded.PhotoCamera,
        shape = if (permanentlyDenied) MaterialShapes.Cookie4Sided.toShape() else MaterialShapes.Cookie9Sided.toShape(),
        title = stringResource(R.string.native_camera_permission_title),
        body = stringResource(
            when (status) {
                CameraPermissionStatus.PermanentlyDenied -> R.string.native_camera_permission_denied_forever
                CameraPermissionStatus.Denied -> R.string.native_camera_permission_rationale
                else -> R.string.native_camera_permission_body
            },
        ),
        modifier = modifier.testTag(QrScannerTestTags.Permission),
    ) {
        if (permanentlyDenied) {
            PrimaryAction(Icons.Rounded.Settings, stringResource(R.string.native_camera_open_settings), onOpenSettings)
            // Android 11+ also reports "denied forever" when the dialog was only dismissed;
            // asking again is harmless (the system answers at once if it really is permanent).
            TextButton(onClick = onRequestPermission, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.native_camera_permission_ask_again))
            }
        } else {
            PrimaryAction(
                Icons.Rounded.PhotoCamera,
                stringResource(R.string.native_camera_permission_grant),
                onRequestPermission,
            )
        }
    }
}

/** Shown when the camera could not be opened (no camera, in use by another app, CameraX error). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CameraUnavailableContent(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    CameraMessagePanel(
        icon = Icons.Rounded.VideocamOff,
        shape = MaterialShapes.Cookie4Sided.toShape(),
        title = stringResource(R.string.native_camera_unavailable_title),
        body = stringResource(R.string.native_camera_unavailable_body),
        modifier = modifier.testTag(QrScannerTestTags.Unavailable),
    ) {
        PrimaryAction(Icons.Rounded.Refresh, stringResource(R.string.native_camera_retry), onRetry)
    }
}

@Composable
private fun PrimaryAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun CameraMessagePanel(
    icon: ImageVector,
    shape: Shape,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    val reducedMotion = rememberReducedMotion()
    val scale = remember { Animatable(if (reducedMotion) 1f else 0.6f) }
    LaunchedEffect(icon) {
        if (!reducedMotion) {
            scale.snapTo(0.6f)
            scale.animateTo(1f, ExpressiveSprings.fastSpatial())
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Box(
                Modifier
                    .size(120.dp)
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                    }
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), shape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(28.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            actions()
        }
    }
}

@Preview(name = "Permission – first ask", widthDp = 360, heightDp = 640)
@Composable
private fun PermissionPreview() {
    EinundzwanzigTheme { CameraPermissionContent(CameraPermissionStatus.NotRequested, {}, {}) }
}

@Preview(name = "Permission – denied forever", widthDp = 360, heightDp = 640)
@Composable
private fun PermissionDeniedForeverPreview() {
    EinundzwanzigTheme { CameraPermissionContent(CameraPermissionStatus.PermanentlyDenied, {}, {}) }
}

@Preview(name = "Camera unavailable", widthDp = 360, heightDp = 640)
@Composable
private fun UnavailablePreview() {
    EinundzwanzigTheme { CameraUnavailableContent(onRetry = {}) }
}
