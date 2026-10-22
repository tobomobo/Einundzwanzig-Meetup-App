package space.einundzwanzig.meetup.data.meetups.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import space.einundzwanzig.meetup.core.common.IoDispatcher
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.result.runSuspendCatching
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.network.HttpResult
import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.meetups.MeetupService
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.data.meetups.portal.PortalUrls
import space.einundzwanzig.meetup.data.meetups.portal.PortalLogoRegistry
import javax.inject.Inject
import javax.inject.Singleton

/** OkHttp port of Dart `MeetupService` (spec 09 §8.1–§8.2). */
@Singleton
internal class MeetupServiceImpl @Inject constructor(
    private val client: OkHttpClient,
    private val log: DiagnosticLog,
    private val logos: PortalLogoRegistry,
    @IoDispatcher private val io: CoroutineDispatcher = Dispatchers.IO,
) : MeetupService {

    private val cache = MutableStateFlow<List<Meetup>>(emptyList())

    override val cached: List<Meetup> get() = cache.value

    override val cachedFlow: StateFlow<List<Meetup>> = cache.asStateFlow()

    override val fallbackMeetups: List<Meetup> get() = FallbackMeetups.list

    override suspend fun fetchMeetups(): List<Meetup> {
        // Load the Dart case table off the main thread before any lookup needs it.
        withContext(io) { DartString.warmUp() }
        val result = client.getCappedOn(io, PortalUrls.MEETUPS_URL, maxBytes = MAX_BYTES, timeout = PortalUrls.TIMEOUT)
        if (result !is HttpResult.Response) {
            log.warn(TAG_APP, "Fehler beim Laden der Meetups: ${describe(result)}")
            return emptyList()
        }
        if (result.status != 200) {
            log.warn(TAG, "Portal antwortete mit HTTP ${result.status}.")
            return emptyList()
        }
        val list = runSuspendCatching {
            val data = DartJson.decode(result.text()) as? JsonArray
                ?: throw IllegalStateException("Antwort ist keine Liste")
            // Dart threw on a non-object entry and lost the whole directory; native skips it.
            val objs = data.filterIsInstance<JsonObject>()
            if (objs.size != data.size) {
                log.warn(TAG, "${data.size - objs.size} Eintraege ohne Objektform uebersprungen.")
            }
            objs.map(MeetupJson::parse)
        }.getOrElse { e ->
            log.warn(TAG_APP, "Fehler beim Laden der Meetups: $e")
            return emptyList()
        }

        logDuplicateCities(list)

        // Only exact duplicates (identical portal ID) are removed; two groups in one city stay.
        val seen = HashSet<String>()
        val unique = list.filter { seen.add(it.id) }
        if (unique.size != list.size) {
            log.warn(TAG, "${list.size - unique.size} Eintrag/Eintraege mit identischer ID entfernt.")
        }
        logos.seedFrom(unique)
        cache.value = unique
        return unique
    }

    private fun logDuplicateCities(list: List<Meetup>) {
        val byCity = LinkedHashMap<String, MutableList<Meetup>>()
        for (m in list) byCity.getOrPut(m.city.dartTrim().dartLower()) { mutableListOf() } += m
        val dupes = byCity.values.filter { it.size > 1 }
        if (dupes.isEmpty()) return
        val named = dupes.take(MAX_NAMED).joinToString(", ") { "${it.first().city} ${it.size}x" }
        val rest = dupes.size - MAX_NAMED
        log.diag(
            TAG,
            "${dupes.size} Stadt/Staedte kommen mehrfach vom Portal: $named" +
                if (rest > 0) " … und $rest weitere" else "",
        )
        for (d in dupes) log.debug(TAG, "\"${d.first().city}\" IDs: ${d.joinToString(", ") { it.id }}")
    }

    override fun resolveFavorite(stored: String): Meetup? {
        val list = cache.value
        if (stored.isEmpty() || list.isEmpty()) return null
        list.firstOrNull { it.id == stored }?.let { return it }
        val lower = stored.dartLower()
        val sameCity = list.filter { it.city.dartLower() == lower }
        if (sameCity.isEmpty()) return null
        if (sameCity.size > 1) {
            log.debug(
                TAG,
                "Favorit \"$stored\" ist mehrdeutig (${sameCity.size} Meetups) — genommen: ${sameCity.first().name}",
            )
        }
        return sameCity.first()
    }

    override fun labelFor(stored: String): String {
        val m = resolveFavorite(stored) ?: return stored
        val city = m.city.dartLower()
        val sameCity = cache.value.count { it.city.dartLower() == city }
        return if (sameCity > 1 && m.name.isNotEmpty()) m.name else m.city
    }

    override fun cityFor(stored: String): String = resolveFavorite(stored)?.city ?: stored

    override fun bySlug(slug: String, country: String): Meetup? {
        val list = cache.value
        if (slug.isEmpty() || list.isEmpty()) return null
        val c = country.dartTrim().dartUpper()
        return list.firstOrNull { m ->
            meetupSlug(m.city) == slug && (c.isEmpty() || m.country.dartTrim().dartUpper() == c)
        }
    }

    override fun byName(name: String): Meetup? {
        val list = cache.value
        val n = name.dartTrim().dartLower()
        if (n.isEmpty() || list.isEmpty()) return null
        return list.firstOrNull { it.name.dartTrim().dartLower() == n }
            ?: list.firstOrNull { it.city.dartTrim().dartLower() == n }
    }

    override suspend fun organizerMeetupFor(pubkeyHexOrNpub: String): Meetup? {
        if (pubkeyHexOrNpub.isEmpty()) return null
        if (cache.value.isEmpty()) fetchMeetups()
        val list = cache.value
        if (list.isEmpty()) return null
        val npub = if (pubkeyHexOrNpub.startsWith("npub")) {
            pubkeyHexOrNpub
        } else {
            runCatching { Nip19.encodeNpub(pubkeyHexOrNpub) }.getOrNull() ?: return null
        }
        return list.firstOrNull { it.nostrNpub.isNotEmpty() && it.nostrNpub.dartTrim() == npub }
    }

    private fun describe(result: HttpResult): String = when (result) {
        is HttpResult.Failure -> result.cause.toString()
        is HttpResult.TooLarge -> "Antwort zu gross (HTTP ${result.status})"
        is HttpResult.Response -> "HTTP ${result.status}"
    }

    companion object {
        private const val TAG = "Meetups"
        private const val TAG_APP = "App"
        private const val MAX_NAMED = 8
        private const val MAX_BYTES = 16L * 1024 * 1024

        /**
         * Dart `MeetupKey.slug` (badge domain): trim → lowercase → `' '` to `-`, with the canonical
         * `DartString` table, so it equals `:data:badges` `MeetupKey.slug` (pinned by a test).
         */
        fun meetupSlug(city: String): String = city.dartTrim().dartLower().replace(" ", "-")
    }
}
