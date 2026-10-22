package space.einundzwanzig.meetup.core.map

import androidx.compose.runtime.Immutable

/** A WGS84 coordinate in degrees (Dart `latlong2.LatLng`). */
@Immutable
data class LatLng(val latitude: Double, val longitude: Double) {
    /** Flutter treats `(0, 0)` as "no coordinate" (badges, failed location lookups). */
    val isZero: Boolean get() = latitude == 0.0 && longitude == 0.0

    companion object {
        /** Centre of Germany, the location picker's default (spec 09 §2.6). */
        val GermanyCenter = LatLng(51.1657, 10.4515)

        /** Centre of the DACH region, the nearby map's default (spec 09 §2.2). */
        val DachCenter = LatLng(50.5, 9.5)
    }
}

/** Camera target and fractional zoom level (flutter_map `MapCamera` center/zoom). */
@Immutable
data class CameraPosition(val target: LatLng, val zoom: Double)

/**
 * Which point of a marker's measured box sits on the coordinate, as fractions of its size.
 * flutter_map centres markers ([Center]); a box whose bottom edge is the point uses [BottomCenter];
 * [DropPinMarker] uses [PinTip].
 */
@Immutable
data class MarkerAnchor(val x: Float, val y: Float) {
    companion object {
        val Center = MarkerAnchor(0.5f, 0.5f)
        val BottomCenter = MarkerAnchor(0.5f, 1f)

        /**
         * The tip of the Material `location_on` glyphs ([DropPinMarker]): about y = 21.5 of the
         * 24-unit icon viewport, so the visible tip (not the padded box bottom) sits on the point.
         */
        val PinTip = MarkerAnchor(0.5f, 21.5f / 24f)
    }
}

/** A pixel position relative to the top-left corner of the map viewport. */
@Immutable
data class PixelPoint(val x: Double, val y: Double)

/** Axis-aligned lat/lng bounds. Does not handle boxes across the antimeridian. */
@Immutable
data class LatLngBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    val center: LatLng
        get() = LatLng(
            WebMercator.latitude((WebMercator.y(north) + WebMercator.y(south)) / 2),
            (west + east) / 2,
        )

    companion object {
        /** The smallest bounds containing [points], or null when there are none. */
        fun of(points: Collection<LatLng>): LatLngBounds? {
            if (points.isEmpty()) return null
            return LatLngBounds(
                south = points.minOf { it.latitude },
                west = points.minOf { it.longitude },
                north = points.maxOf { it.latitude },
                east = points.maxOf { it.longitude },
            )
        }
    }
}
