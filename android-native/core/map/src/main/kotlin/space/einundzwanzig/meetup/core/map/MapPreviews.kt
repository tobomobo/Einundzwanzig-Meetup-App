package space.einundzwanzig.meetup.core.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme

private val PreviewBadges = listOf(
    LatLng(48.2082, 16.3738),
    LatLng(47.0707, 15.4395),
    LatLng(48.1351, 11.5820),
    LatLng(52.5200, 13.4050),
)

@Preview(name = "World map markers", widthDp = 360, heightDp = 420)
@Composable
private fun WorldMapPreview() {
    EinundzwanzigTheme {
        val camera = rememberOsmCameraState(LatLng(50.0, 13.5), 4.6)
        Box {
            OsmMap(camera, Modifier.fillMaxWidth().height(420.dp)) {
                PreviewBadges.forEach { Marker(it, onClick = {}) { BitcoinBadgeMarker() } }
            }
            MapZoomControls(camera, Modifier.align(Alignment.CenterEnd).padding(12.dp))
        }
    }
}

@Preview(name = "Nearby map", widthDp = 360, heightDp = 230)
@Composable
private fun NearbyMapPreview() {
    EinundzwanzigTheme {
        val center = LatLng(48.2082, 16.3738)
        val camera = rememberOsmCameraState(center, 9.0)
        val primary = MaterialTheme.colorScheme.primary
        OsmMap(camera, Modifier.fillMaxWidth().height(230.dp)) {
            Circle(center, 25_000.0, primary.copy(alpha = 0.08f), primary.copy(alpha = 0.4f))
            Marker(center) { LocationDot() }
            Marker(LatLng(48.30, 16.10), onClick = {}) { MeetupPinMarker(active = true) }
            Marker(LatLng(48.05, 16.60), onClick = {}) { MeetupPinMarker(active = false) }
        }
    }
}

@Preview(name = "Location picker", widthDp = 360, heightDp = 400)
@Composable
private fun LocationPickerPreview() {
    EinundzwanzigTheme {
        val pick = LatLng(48.2082, 16.3738)
        LocationPickerMap(
            cameraState = rememberLocationPickerCameraState(pick),
            picked = pick,
            onPick = {},
            modifier = Modifier.fillMaxWidth().height(400.dp),
        )
    }
}
