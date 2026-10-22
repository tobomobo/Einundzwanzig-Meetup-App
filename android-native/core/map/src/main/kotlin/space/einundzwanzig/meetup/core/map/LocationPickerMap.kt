package space.einundzwanzig.meetup.core.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import space.einundzwanzig.meetup.core.common.dart.toStringAsFixed

/**
 * Map for the event-venue picker (spec 09 §2.6, `location_picker_screen.dart`): a tap sets or moves
 * the pick, which is shown as a [DropPinMarker] whose tip sits on the point. Each new pick drops the
 * pin in again with a spring. Assistive technology picks the map centre via the map's click action
 * after panning with its custom actions. The hint bar, coordinates, "jump to me" and confirm button belong to
 * the screen (`:feature:meetups`).
 *
 * Build the camera with [rememberLocationPickerCameraState].
 */
@Composable
fun LocationPickerMap(
    cameraState: OsmCameraState,
    picked: LatLng?,
    onPick: (LatLng) -> Unit,
    modifier: Modifier = Modifier,
    tileStyle: MapTileStyle = MapTileStyle.Standard,
    contentDescription: String? = null,
) {
    OsmMap(
        cameraState = cameraState,
        modifier = modifier,
        onMapClick = onPick,
        tileStyle = tileStyle,
        contentDescription = contentDescription,
        // TalkBack / switch access: pan with the map's custom actions, then pick the centre.
        centerClickLabel = stringResource(R.string.native_map_pick_center),
    ) {
        if (picked != null) {
            key(picked) {
                Marker(
                    position = picked,
                    anchor = MarkerAnchor.PinTip,
                    contentDescription = stringResource(
                        R.string.native_map_picked_location_at,
                        picked.format(LocationPicker.COORDINATE_DIGITS),
                    ),
                ) { DropPinMarker() }
            }
        }
    }
}

/** Defaults and rules of the Flutter location picker (spec 09 §2.6). */
object LocationPicker {
    const val PICKED_ZOOM = 15.0
    const val DEFAULT_ZOOM = 5.0
    const val JUMP_TO_ME_ZOOM = 13.0
    const val COORDINATE_DIGITS = 5

    /**
     * The preset pick: both values given and not both 0 (an unset venue is stored as 0/0).
     */
    fun initialPick(latitude: Double?, longitude: Double?): LatLng? {
        if (latitude == null || longitude == null) return null
        if (latitude == 0.0 && longitude == 0.0) return null
        return LatLng(latitude, longitude)
    }

    /** Camera at the pick (zoom 15) or over Germany (zoom 5). */
    fun initialCamera(pick: LatLng?): CameraPosition =
        if (pick != null) CameraPosition(pick, PICKED_ZOOM) else CameraPosition(LatLng.GermanyCenter, DEFAULT_ZOOM)
}

/** Camera state for [LocationPickerMap], starting at [LocationPicker.initialCamera]. */
@Composable
fun rememberLocationPickerCameraState(initialPick: LatLng?): OsmCameraState {
    val initial = LocationPicker.initialCamera(initialPick)
    return rememberOsmCameraState(initial.target, initial.zoom)
}

/** `"<lat>, <lng>"` with Dart `toStringAsFixed(digits)` (picker: 5, meetup details: 4). */
fun LatLng.format(digits: Int): String =
    "${latitude.toStringAsFixed(digits)}, ${longitude.toStringAsFixed(digits)}"
