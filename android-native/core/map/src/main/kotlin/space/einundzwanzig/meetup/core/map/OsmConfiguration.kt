package space.einundzwanzig.meetup.core.map

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource
import space.einundzwanzig.meetup.core.common.log.AppLogger
import java.io.File

/**
 * Process-wide osmdroid setup (spec 09 §5.5, spec 03 §5, spec 14 §2).
 *
 * - User agent: `<applicationId>/<versionName> (Android)` (spec 03 §5 suggestion; specs 09 §5.5 and
 *   14 §2 say the bare package, see spec/00-contradictions.md item 17). The OSM tile usage policy
 *   forbids library-default agents; Flutter sent `flutter_map (space.einundzwanzig.meetup)`.
 * - Tile cache in `cacheDir/osmdroid` (no storage permission, cleared with the app cache). flutter_map
 *   kept no cache; the policy encourages one.
 * - Tiles from `https://tile.openstreetmap.org/{z}/{x}/{y}.png`, the URL flutter_map used.
 */
object OsmConfiguration {
    private const val TAG = "OsmMap"

    /** Upper bound of the on-disk tile cache. */
    const val TILE_CACHE_MAX_BYTES: Long = 100L * 1024 * 1024
    const val TILE_CACHE_TRIM_BYTES: Long = 80L * 1024 * 1024

    /** Attribution required by the OSM tile policy (also shown by [OsmMap]). */
    const val ATTRIBUTION_URL = OsmUrls.COPYRIGHT

    /**
     * The OSM standard tile layer: one host (no a/b/c subdomains), zoom 0–19, 256 px tiles, at most
     * two concurrent downloads and no bulk or preventive downloading, as the policy requires.
     */
    val OsmStandardTiles: OnlineTileSourceBase = XYTileSource(
        "Mapnik",
        0,
        WebMercator.OSM_MAX_ZOOM.toInt(),
        WebMercator.TILE_SIZE_DP.toInt(),
        ".png",
        arrayOf(OsmUrls.TILE_BASE_URL),
        "© OpenStreetMap contributors",
        TileSourcePolicy(
            2,
            TileSourcePolicy.FLAG_NO_BULK or
                TileSourcePolicy.FLAG_NO_PREVENTIVE or
                TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL or
                TileSourcePolicy.FLAG_USER_AGENT_NORMALIZED,
        ),
    )

    @Volatile
    private var configured = false

    /** Builds the user agent, e.g. `space.einundzwanzig.meetup/1.4.0 (Android)`. */
    fun userAgent(packageName: String, versionName: String?): String =
        if (versionName.isNullOrBlank()) "$packageName (Android)" else "$packageName/$versionName (Android)"

    /** Idempotent; [OsmMap] calls it before creating a map view. */
    fun ensureConfigured(context: Context) {
        if (configured) return
        synchronized(this) {
            if (configured) return
            val app = context.applicationContext
            val config = Configuration.getInstance()
            config.userAgentValue = userAgent(app.packageName, versionName(app))
            val base = File(app.cacheDir, "osmdroid")
            config.osmdroidBasePath = base
            config.osmdroidTileCache = File(base, "tiles")
            config.tileFileSystemCacheMaxBytes = TILE_CACHE_MAX_BYTES
            config.tileFileSystemCacheTrimBytes = TILE_CACHE_TRIM_BYTES
            config.isDebugMode = false
            config.isDebugTileProviders = false
            configured = true
        }
    }

    private fun versionName(context: Context): String? = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        info.versionName
    } catch (e: PackageManager.NameNotFoundException) {
        AppLogger.warn(TAG, "Versionsname für den Karten-User-Agent nicht lesbar", e)
        null
    }
}
