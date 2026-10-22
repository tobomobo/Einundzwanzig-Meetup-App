package space.einundzwanzig.meetup.core.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalFloatingToolbar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import space.einundzwanzig.meetup.core.ui.util.SafeUrlLauncher

/**
 * "© OpenStreetMap contributors" (OSM tile policy; Flutter showed none, spec 03 §2.4 / 09 §5.5).
 * Tapping opens the OSM copyright page.
 */
@Composable
fun MapAttribution(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val actionLabel = stringResource(R.string.native_map_attribution_action)
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.82f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = stringResource(R.string.native_map_attribution),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = MaterialTheme.typography.bodySmall.letterSpacing),
            modifier = Modifier
                .clickable(onClickLabel = actionLabel, role = Role.Button) {
                    SafeUrlLauncher.open(context, OsmConfiguration.ATTRIBUTION_URL)
                }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * Optional zoom buttons in an expressive vertical floating toolbar. The Flutter maps only had
 * gestures; screens can add this for users who cannot pinch.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MapZoomControls(cameraState: OsmCameraState, modifier: Modifier = Modifier) {
    VerticalFloatingToolbar(
        expanded = true,
        modifier = modifier,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
    ) {
        IconButton(onClick = { cameraState.zoomBy(1.0) }, enabled = cameraState.canZoomIn) {
            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.native_map_zoom_in))
        }
        IconButton(onClick = { cameraState.zoomBy(-1.0) }, enabled = cameraState.canZoomOut) {
            Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.native_map_zoom_out))
        }
    }
}
