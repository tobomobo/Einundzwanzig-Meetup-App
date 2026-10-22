package space.einundzwanzig.meetup.data.calendar.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.data.calendar.CalendarEventService
import space.einundzwanzig.meetup.data.calendar.EventRsvpService
import space.einundzwanzig.meetup.data.calendar.MeetupCalendarService
import space.einundzwanzig.meetup.data.calendar.MyEventsService
import space.einundzwanzig.meetup.data.calendar.RsvpError
import space.einundzwanzig.meetup.data.calendar.match.MeetupEventMatcher
import space.einundzwanzig.meetup.data.calendar.model.FavoriteCards
import space.einundzwanzig.meetup.data.calendar.model.FavoriteMeetupCard
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate
import space.einundzwanzig.meetup.data.calendar.model.MyMeetupDate
import space.einundzwanzig.meetup.data.calendar.model.NostrCalendarEvent
import space.einundzwanzig.meetup.data.calendar.model.RsvpStatus
import space.einundzwanzig.meetup.data.calendar.portal.PortalRsvp
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.meetups.MeetupService
import space.einundzwanzig.meetup.data.meetups.portal.PortalApiService
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class MyEventsServiceImpl @Inject constructor(
    private val calendar: CalendarEventService,
    private val rsvps: EventRsvpService,
    private val meetupDates: MeetupCalendarService,
    private val meetups: MeetupService,
    private val portal: PortalApiService,
    private val profiles: UserProfileRepository,
    private val widget: HomeWidgetStore,
    private val time: TimeSource,
    private val log: DiagnosticLog,
) : MyEventsService {

    /** Local zone for "today" (Dart `DateTime.now()` local); tests pin it. */
    internal var zoneProvider: () -> ZoneId = ZoneId::systemDefault

    private fun now(): Instant = Instant.ofEpochMilli(time.nowMillis())

    override suspend fun loadAcceptedEvents(): List<NostrCalendarEvent> = guard(emptyList()) {
        val mine = rsvps.loadMine()
        val from = now().minus(Duration.ofDays(1))
        val accepted = mine.filterValues { it == RsvpStatus.ACCEPTED }.keys.toList()
        // Bounded parallelism (Dart: sequential); the order is irrelevant because of the sort.
        coroutineScope {
            accepted.chunked(FETCH_PARALLELISM).flatMap { batch ->
                batch.map { address -> async { calendar.fetchByAddress(address) } }.awaitAll()
            }
        }.filterNotNull().filter { !it.start.isBefore(from) }.sortedBy { it.start }
    }

    override suspend fun loadMeetupRsvps(dates: List<MeetupDate>?): List<MyMeetupDate> = guard(emptyList()) {
        val user = profiles.load()
        val favKeys = LinkedHashSet<String>()
        if (user.homeMeetupId.isNotEmpty()) favKeys += user.homeMeetupId
        favKeys += user.favoriteMeetupIds
        if (favKeys.isEmpty()) return@guard emptyList()
        val events = dates ?: meetupDates.fetchPortalFirst()
        if (meetups.cached.isEmpty()) meetups.fetchMeetups()
        val now = now()
        val from = now.minus(Duration.ofDays(1))
        val horizon = now.plus(Duration.ofDays(MyEventsService.RSVP_HORIZON_DAYS))
        val candidates = events.filter { e ->
            e.portalEventId != null && !e.start.isBefore(from) && !e.start.isAfter(horizon) &&
                favKeys.any { MeetupEventMatcher.matchesFavorite(e, it, meetups) }
        }
        val out = ArrayList<MyMeetupDate>()
        for (e in candidates) {
            val r = portal.getRsvpCached(e.portalEventId!!)
            if (!portal.isGoing(r)) continue
            val favKey = favKeys.firstOrNull { MeetupEventMatcher.matchesFavorite(e, it, meetups) } ?: e.meetupId
            out += MyMeetupDate(favKey, meetups.labelFor(favKey), e, PortalRsvp.attendees(r))
        }
        log.diag(TAG, "${candidates.size} Termine der Favoriten geprueft, ${out.size} davon zugesagt.")
        out.sortedBy { it.event.start }
    }

    override suspend fun withdraw(event: NostrCalendarEvent): RsvpError? =
        rsvps.setStatus(event.address, event.pubkey, RsvpStatus.DECLINED)

    override suspend fun loadFavoriteCards(dates: List<MeetupDate>?, updateWidget: Boolean): FavoriteCards =
        guard(FavoriteCards.Failed) {
            val user = profiles.load()
            val favs = user.favoriteMeetupIds.ifEmpty {
                if (user.homeMeetupId.isNotEmpty()) listOf(user.homeMeetupId) else emptyList()
            }
            if (favs.isEmpty()) {
                if (updateWidget) widget.updateMeetup("", null)
                return@guard FavoriteCards.NoFavorites
            }
            val events = dates ?: meetupDates.fetchPortalFirst()
            if (meetups.cached.isEmpty()) meetups.fetchMeetups()
            val zone = zoneProvider()
            val todayStart = now().atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
            val cards = favs.map { key ->
                val next = events
                    .filter { MeetupEventMatcher.matchesFavorite(it, key, meetups) && !it.start.isBefore(todayStart) }
                    .minByOrNull { it.start }
                FavoriteMeetupCard(key, meetups.labelFor(key), meetups.cityFor(key), next).also {
                    log.diag(TAG, "Favorit \"${it.label}\" ($key): naechster = ${next?.start ?: "keiner"}")
                }
            }
            val sorted = cards.sortedWith { a, b ->
                val sa = a.next?.start
                val sb = b.next?.start
                when {
                    sa == null && sb == null -> a.city.compareTo(b.city)
                    sa == null -> 1
                    sb == null -> -1
                    else -> sa.compareTo(sb)
                }
            }
            val datesAvailable = events.isNotEmpty()
            if (updateWidget) {
                if (datesAvailable) {
                    val front = sorted.first()
                    widget.updateMeetup(front.city, front.next?.start)
                } else {
                    // Portal and iCal unreachable: keep the stored countdown instead of clearing it.
                    log.debug(TAG, "Keine Termine geladen, Widget-Meetup bleibt unveraendert.")
                }
            }
            FavoriteCards.Loaded(sorted, datesAvailable)
        }

    private suspend fun <T> guard(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(TAG, "Meine Termine konnten nicht geladen werden", e)
        fallback
    }

    private companion object {
        const val TAG = "Home"
        const val FETCH_PARALLELISM = 4
    }
}
