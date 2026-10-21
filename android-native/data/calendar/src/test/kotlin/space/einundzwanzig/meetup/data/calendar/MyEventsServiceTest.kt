package space.einundzwanzig.meetup.data.calendar

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.data.calendar.internal.MyEventsServiceImpl
import space.einundzwanzig.meetup.data.calendar.model.CalendarEventDraft
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate
import space.einundzwanzig.meetup.data.calendar.model.NostrCalendarEvent
import space.einundzwanzig.meetup.data.calendar.model.RsvpStatus
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetSnapshot
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.meetups.MeetupService
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.data.meetups.portal.PortalApiService
import java.lang.reflect.Proxy
import java.time.Duration
import java.time.Instant

/** Home "Meine Termine" / favorite-card logic (spec 10 §2.5, spec 12 §8.3–§8.5). */
class MyEventsServiceTest {

    private val time = FakeTime(Instant.parse("2026-03-05T10:00:00Z").toEpochMilli())
    private val now = Instant.ofEpochMilli(time.now)

    private val berlin = group("1", "Einundzwanzig Berlin", "Berlin", "berlin")
    private val hamburg = group("2", "Einundzwanzig Hamburg", "Hamburg", "hamburg")
    private val munich = group("3", "Einundzwanzig München", "München", "muenchen")

    private class Profiles(var user: UserProfile) : UserProfileRepository {
        override suspend fun load() = user
        override suspend fun save(profile: UserProfile) {
            user = profile
        }
        override suspend fun update(transform: (UserProfile) -> UserProfile) = transform(user).also { user = it }
        override val profile: Flow<UserProfile> get() = flowOf(user)
    }

    private inner class Directory(override val cached: List<Meetup>) : MeetupService {
        override val cachedFlow: StateFlow<List<Meetup>> = MutableStateFlow(cached)
        override val fallbackMeetups: List<Meetup> = emptyList()
        override suspend fun fetchMeetups() = cached
        override fun resolveFavorite(stored: String) =
            cached.firstOrNull { it.id == stored } ?: cached.firstOrNull { it.city.equals(stored, ignoreCase = true) }
        override fun labelFor(stored: String) = resolveFavorite(stored)?.city ?: stored
        override fun cityFor(stored: String) = resolveFavorite(stored)?.city ?: stored
        override fun bySlug(slug: String, country: String): Meetup? = null
        override fun byName(name: String): Meetup? = null
        override suspend fun organizerMeetupFor(pubkeyHexOrNpub: String): Meetup? = null
    }

    private class Widget : HomeWidgetStore {
        val meetups = mutableListOf<Pair<String, Instant?>>()
        override suspend fun snapshot() = HomeWidgetSnapshot()
        override suspend fun writeBitcoin(values: Map<String, String>) = Unit
        override suspend fun updateMeetup(city: String, start: Instant?) {
            meetups += city to start
        }
        override suspend fun publishLatestNews(id: String, title: String) = Unit
        override suspend fun markNewsSeen() = Unit
    }

    private val rsvpByEvent = mutableMapOf<Int, JsonObject>()
    private val portal = Proxy.newProxyInstance(PortalApiService::class.java.classLoader, arrayOf(PortalApiService::class.java)) { _, m, args ->
        when (m.name) {
            "getRsvpCached" -> rsvpByEvent[args[0] as Int]
            "isGoing" -> (args[0] as JsonObject?)?.get("status")?.toString() == "\"attending\""
            else -> throw UnsupportedOperationException(m.name)
        }
    } as PortalApiService

    private val events = mutableMapOf<String, NostrCalendarEvent>()
    private val mine = mutableMapOf<String, RsvpStatus>()
    private val sent = mutableListOf<Triple<String, String, RsvpStatus>>()

    private val calendar = object : CalendarEventService {
        override suspend fun fetchEvents(limit: Int, worldwide: Boolean) = events.values.toList()
        override suspend fun fetchByAddress(address: String) = events[address]
        override suspend fun publishEvent(draft: CalendarEventDraft, existingDTag: String?) = 0
        override suspend fun updateIssuers(event: NostrCalendarEvent, newIssuersHex: List<String>) = false
        override suspend fun cancelEvent(event: NostrCalendarEvent) = false
    }
    private val rsvps = object : EventRsvpService {
        override fun dTagFor(eventAddress: String) = ""
        override suspend fun setStatus(eventAddress: String, eventAuthorPubkey: String, status: RsvpStatus): RsvpError? {
            sent += Triple(eventAddress, eventAuthorPubkey, status)
            return null
        }
        override suspend fun loadMine() = mine
    }

    private fun date(title: String, inDays: Long, id: Int? = null, meetupId: String = "") = MeetupDate(
        title = title, description = "", location = "", start = now.plus(Duration.ofDays(inDays)), url = "",
        meetupId = meetupId, portalEventId = id,
    )

    private fun service(user: UserProfile, widget: Widget = Widget(), dates: List<MeetupDate> = emptyList()) = MyEventsServiceImpl(
        calendar, rsvps,
        object : MeetupCalendarService {
            override suspend fun fetchPortalFirst() = dates
            override suspend fun fetchIcs() = emptyList<MeetupDate>()
        },
        Directory(listOf(berlin, hamburg, munich)), portal, Profiles(user), widget, time, testLog(),
    )

    private fun nostr(address: String, startInDays: Long) = NostrCalendarEvent(
        id = "i", pubkey = address.split(':')[1], dTag = address.split(':')[2], title = address, description = "", location = "",
        start = now.plus(Duration.ofHours(startInDays * 24)), end = null, allDay = false, kind = 31923,
    )

    @Test
    fun `accepted events are fetched, filtered and sorted`() = runTest {
        val a = "31923:p:a"
        val b = "31923:p:b"
        val old = "31923:p:old"
        events[a] = nostr(a, 5)
        events[b] = nostr(b, 1)
        events[old] = nostr(old, -2)
        mine[a] = RsvpStatus.ACCEPTED
        mine[b] = RsvpStatus.ACCEPTED
        mine[old] = RsvpStatus.ACCEPTED
        mine["31923:p:declined"] = RsvpStatus.DECLINED
        mine["31923:p:missing"] = RsvpStatus.ACCEPTED
        val list = service(UserProfile()).loadAcceptedEvents()
        assertEquals(listOf(b, a), list.map { it.address })
        assertNull(service(UserProfile()).withdraw(list.first()))
        assertEquals(Triple(b, "p", RsvpStatus.DECLINED), sent.single())
    }

    @Test
    fun `meetup RSVPs of favorites within 90 days`() = runTest {
        val dates = listOf(
            date("Einundzwanzig Berlin", 3, id = 11, meetupId = "1"),
            date("Einundzwanzig Hamburg", 2, id = 12, meetupId = "2"),
            date("Einundzwanzig Berlin", 120, id = 13, meetupId = "1"),
            date("Einundzwanzig München", 1, id = 14, meetupId = "3"),
            date("Einundzwanzig Berlin", 4, id = null, meetupId = "1"),
        )
        rsvpByEvent[11] = jsonObjectOf("status" to "attending", "attendees" to 7)
        rsvpByEvent[12] = jsonObjectOf("status" to "attending")
        rsvpByEvent[13] = jsonObjectOf("status" to "attending")
        rsvpByEvent[14] = jsonObjectOf("status" to "attending")
        val user = UserProfile(homeMeetupId = "Hamburg", favoriteMeetupIds = listOf("1"))
        val out = service(user).loadMeetupRsvps(dates)
        assertEquals(listOf(12, 11), out.map { it.event.portalEventId })
        assertEquals(listOf("Hamburg", "1"), out.map { it.favKey })
        assertEquals(listOf(-1, 7), out.map { it.attendees })
    }

    @Test
    fun `favorite cards sort by next date and feed the widget`() = runTest {
        val dates = listOf(
            date("Einundzwanzig Hamburg", 6, meetupId = "2"),
            date("Einundzwanzig Hamburg", 2, meetupId = "2"),
            date("Einundzwanzig Berlin", -1, meetupId = "1"),
        )
        val widget = Widget()
        val cards = service(UserProfile(favoriteMeetupIds = listOf("1", "3", "2")), widget, dates).loadFavoriteCards()
        assertEquals(listOf("2", "1", "3"), cards.map { it.favKey })
        assertEquals(now.plus(Duration.ofDays(2)), cards[0].next?.start)
        assertNull(cards[1].next)
        assertEquals(listOf("Hamburg" to now.plus(Duration.ofDays(2))), widget.meetups)

        val empty = Widget()
        assertEquals(emptyList<Any>(), service(UserProfile(), empty, dates).loadFavoriteCards())
        assertEquals(listOf("" to null), empty.meetups)
    }
}
