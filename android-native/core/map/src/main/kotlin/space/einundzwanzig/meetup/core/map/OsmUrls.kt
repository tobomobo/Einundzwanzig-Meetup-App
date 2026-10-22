package space.einundzwanzig.meetup.core.map

import space.einundzwanzig.meetup.core.common.dart.toDartString

/** OpenStreetMap URLs used by the map features. */
object OsmUrls {
    /** OSM standard raster tiles (spec 09 §5.5). No a/b/c subdomains, per current OSM advice. */
    const val TILE_BASE_URL = "https://tile.openstreetmap.org/"

    /** Copyright and licence page, opened from the attribution chip. */
    const val COPYRIGHT = "https://www.openstreetmap.org/copyright"

    /**
     * The nearby screen's directions link (spec 09 §2.2):
     * `https://www.openstreetmap.org/?mlat=<lat>&mlon=<lng>#map=15/<lat>/<lng>` with Dart
     * `double.toString()` formatting.
     */
    fun marker(latitude: Double, longitude: Double, zoom: Int = 15): String {
        val lat = latitude.toDartString()
        val lng = longitude.toDartString()
        return "https://www.openstreetmap.org/?mlat=$lat&mlon=$lng#map=$zoom/$lat/$lng"
    }
}
