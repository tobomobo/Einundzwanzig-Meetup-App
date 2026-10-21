package space.einundzwanzig.meetup.data.meetups.internal

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.result.runSuspendCatching
import space.einundzwanzig.meetup.data.meetups.MeetupService
import space.einundzwanzig.meetup.data.meetups.location.Geo
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.data.meetups.nearby.DateMode
import space.einundzwanzig.meetup.data.meetups.nearby.MeetupEventInfo
import space.einundzwanzig.meetup.data.meetups.nearby.MeetupEventsSource
import space.einundzwanzig.meetup.data.meetups.nearby.NearbyMeetup
import space.einundzwanzig.meetup.data.meetups.nearby.NearbyMeetupService
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/** Port of Dart `NearbyMeetupService.preload/search/_eventsFor` (spec 09 §8.8). */
@Singleton
internal class NearbyMeetupServiceImpl @Inject constructor(
    private val meetupService: MeetupService,
    private val events: Optional<MeetupEventsSource>,
    private val time: TimeSource,
    private val log: DiagnosticLog,
) : NearbyMeetupService {

    private val mutex = Mutex()

    @Volatile
    private var meetupCache: List<Meetup>? = null

    @Volatile
    private var eventCache: List<MeetupEventInfo>? = null

    override suspend fun preload(forceReload: Boolean) = mutex.withLock {
        if (!forceReload && meetupCache != null && eventCache != null) return@withLock
        val (loaded, dates) = coroutineScope {
            val m = async { meetupService.fetchMeetups() }
            val e = async { loadEvents() }
            m.await() to e.await()
        }
        val meetups = loaded.ifEmpty { meetupService.fallbackMeetups }
        meetupCache = dedupeByCity(meetups)
        eventCache = dates
    }

    private suspend fun loadEvents(): List<MeetupEventInfo> {
        val source = events.orElse(null) ?: return emptyList()
        return runSuspendCatching { source.fetchMeetupEvents() }.getOrElse {
            log.warn(TAG, "Termine nicht ladbar: $it")
            emptyList()
        }
    }

    override suspend fun search(
        centerLat: Double,
        centerLng: Double,
        radiusKm: Double,
        dateMode: DateMode,
        dayFrom: LocalDate?,
        dayTo: LocalDate?,
        zone: ZoneId,
    ): List<NearbyMeetup> {
        preload()
        return searchIn(
            meetups = meetupCache.orEmpty(),
            events = eventCache.orEmpty(),
            centerLat = centerLat,
            centerLng = centerLng,
            radiusKm = radiusKm,
            dateMode = dateMode,
            dayFrom = dayFrom,
            dayTo = dayTo,
            now = Instant.ofEpochMilli(time.nowMillis()),
            zone = zone,
        )
    }

    companion object {
        private const val TAG = "Nearby"

        /** Dart preload dedupe: first meetup per `city.toLowerCase().trim()`, empty keys always kept. */
        internal fun dedupeByCity(meetups: List<Meetup>): List<Meetup> {
            val seen = HashSet<String>()
            return meetups.filter { m ->
                val key = m.city.dartLower().dartTrim()
                key.isEmpty() || seen.add(key)
            }
        }

        /** Pure search over loaded caches (Dart `search` after `preload`). */
        internal fun searchIn(
            meetups: List<Meetup>,
            events: List<MeetupEventInfo>,
            centerLat: Double,
            centerLng: Double,
            radiusKm: Double,
            dateMode: DateMode,
            dayFrom: LocalDate?,
            dayTo: LocalDate?,
            now: Instant,
            zone: ZoneId,
        ): List<NearbyMeetup> {
            val anyCutoff = now.minusSeconds(NearbyMeetupService.ANY_WINDOW_PAST_HOURS * 3600)
            val out = mutableListOf<NearbyMeetup>()
            for (m in meetups) {
                if (m.lat == 0.0 && m.lng == 0.0) continue
                val dist = Geo.distanceKm(centerLat, centerLng, m.lat, m.lng)
                if (dist > radiusKm) continue

                val matchingRaw = eventsFor(m, events).filter { e ->
                    when (dateMode) {
                        DateMode.ANY -> e.start.isAfter(anyCutoff)
                        DateMode.SINGLE_DAY -> dayFrom != null && e.start.atZone(zone).toLocalDate() == dayFrom
                        DateMode.RANGE -> {
                            if (dayFrom == null || dayTo == null) {
                                false
                            } else {
                                val start = dayFrom.atStartOfDay(zone).toInstant()
                                val end = dayTo.atTime(23, 59, 59).atZone(zone).toInstant()
                                e.start.isAfter(start.minusSeconds(1)) && e.start.isBefore(end.plusSeconds(1))
                            }
                        }
                    }
                }.sortedBy { it.start }

                // The same date (title + start + location) can come several times from the feeds.
                val seen = HashSet<Triple<String, Instant, String>>()
                val matching = matchingRaw.filter { seen.add(Triple(it.title, it.start, it.location)) }

                if (dateMode != DateMode.ANY && matching.isEmpty()) continue
                out += NearbyMeetup(m, matching.firstOrNull(), matching, dist)
            }
            return out.sortedBy { it.distanceKm ?: 0.0 }
        }

        /**
         * Legacy substring matcher (Dart `_eventsFor`): location or title contains the city, or the
         * city contains the first comma part of the location. A location starting with `,` matches
         * every meetup (parity quirk, spec 09 §8.8).
         */
        internal fun eventsFor(m: Meetup, events: List<MeetupEventInfo>): List<MeetupEventInfo> {
            val city = m.city.dartLower().dartTrim()
            if (city.isEmpty()) return emptyList()
            return events.filter { e ->
                val loc = e.location.dartLower()
                val title = e.title.dartLower()
                loc.contains(city) ||
                    title.contains(city) ||
                    (loc.isNotEmpty() && city.contains(loc.split(',').first().dartTrim()))
            }
        }
    }
}
