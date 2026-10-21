package space.einundzwanzig.meetup.data.meetups.internal

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.DartUri
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.result.runSuspendCatching
import space.einundzwanzig.meetup.core.network.HttpResult
import space.einundzwanzig.meetup.core.network.getCapped
import space.einundzwanzig.meetup.data.meetups.geocoding.GeoPlace
import space.einundzwanzig.meetup.data.meetups.geocoding.GeocodingService
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/** OkHttp port of Dart `GeocodingService.search` (spec 09 §5.4). */
@Singleton
internal class GeocodingServiceImpl @Inject constructor(
    private val client: OkHttpClient,
    private val time: TimeSource,
    private val log: DiagnosticLog,
) : GeocodingService {

    private val throttle = Mutex()
    private var lastRequestAt = Long.MIN_VALUE

    override suspend fun search(query: String, limit: Int): List<GeoPlace> {
        val q = query.dartTrim()
        if (q.length < GeocodingService.MIN_QUERY_LENGTH) return emptyList()
        // Dart Uri.replace(queryParameters:) encoding (space → `+`, `,` → `%2C`), same key order.
        val url = GeocodingService.URL + "?" + listOf(
            "q" to q,
            "format" to "jsonv2",
            "limit" to limit.toString(),
            "addressdetails" to "1",
            "accept-language" to "de,en",
        ).joinToString("&") { (k, v) -> DartUri.encodeQueryComponent(k) + "=" + DartUri.encodeQueryComponent(v) }

        throttle.withLock {
            if (lastRequestAt != Long.MIN_VALUE) {
                val wait = lastRequestAt + GeocodingService.MIN_INTERVAL_MS - time.nowMillis()
                if (wait > 0) delay(wait)
            }
            lastRequestAt = time.nowMillis()
        }

        val result = client.getCapped(
            url,
            headers = mapOf("User-Agent" to GeocodingService.USER_AGENT),
            maxBytes = MAX_BYTES,
            timeout = 10.seconds,
        )
        if (result !is HttpResult.Response) {
            log.debug(TAG, "Fehler: $result")
            return emptyList()
        }
        if (result.status != 200) {
            log.debug(TAG, "Status ${result.status}")
            return emptyList()
        }
        return runSuspendCatching { parse(result.text()) }.getOrElse {
            log.debug(TAG, "Fehler: $it")
            emptyList()
        }
    }

    companion object {
        private const val TAG = "Geocode"
        private const val MAX_BYTES = 2L * 1024 * 1024

        /** Parses a Nominatim `jsonv2` array; throws on a non-array body (caller maps to `[]`). */
        internal fun parse(body: String): List<GeoPlace> {
            val data = DartJson.decode(body) as? JsonArray ?: throw IllegalStateException("keine Liste")
            return data.map { el ->
                val e = el as? JsonObject ?: throw IllegalStateException("Eintrag ist kein Objekt")
                val display = e.stringOrEmpty("display_name")
                val name = e.stringOrEmpty("name")
                GeoPlace(
                    displayName = display,
                    shortName = name.ifEmpty { display.split(',').first().dartTrim() },
                    lat = dartTryParseDouble(e.stringOrEmpty("lat")) ?: 0.0,
                    lng = dartTryParseDouble(e.stringOrEmpty("lon")) ?: 0.0,
                )
            }.filter { it.lat != 0.0 || it.lng != 0.0 }
        }
    }
}
