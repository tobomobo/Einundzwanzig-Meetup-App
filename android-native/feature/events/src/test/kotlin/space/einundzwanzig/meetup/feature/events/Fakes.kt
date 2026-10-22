package space.einundzwanzig.meetup.feature.events

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.data.calendar.CalendarEventService
import space.einundzwanzig.meetup.data.calendar.EventCalendarRepository
import space.einundzwanzig.meetup.data.calendar.EventRsvpService
import space.einundzwanzig.meetup.data.calendar.MeetupCalendarService
import space.einundzwanzig.meetup.data.calendar.MyEventsService
import space.einundzwanzig.meetup.data.calendar.RsvpError
import space.einundzwanzig.meetup.data.calendar.model.CalendarEventDraft
import space.einundzwanzig.meetup.data.calendar.model.CalendarItem
import space.einundzwanzig.meetup.data.calendar.model.FavoriteCards
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate
import space.einundzwanzig.meetup.data.calendar.model.MyMeetupDate
import space.einundzwanzig.meetup.data.calendar.model.NostrCalendarEvent
import space.einundzwanzig.meetup.data.calendar.model.RsvpStatus
import space.einundzwanzig.meetup.data.chat.ChatRoom
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.issuance.event.EventBadgeAuthService
import space.einundzwanzig.meetup.data.issuance.event.EventBadgeRight
import space.einundzwanzig.meetup.data.issuance.event.EventBadgeSessionService
import space.einundzwanzig.meetup.data.issuance.event.EventSessionResult
import space.einundzwanzig.meetup.data.meetups.MeetupService
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.data.meetups.portal.Attendee
import space.einundzwanzig.meetup.data.meetups.portal.PortalResult
import space.einundzwanzig.meetup.data.profile.blossom.BlossomUploadService
import space.einundzwanzig.meetup.data.profile.blossom.UploadResult
import space.einundzwanzig.meetup.feature.events.common.BadgeImagePreparer
import space.einundzwanzig.meetup.feature.events.common.EventsChat
import space.einundzwanzig.meetup.feature.events.common.EventsIdentity
import space.einundzwanzig.meetup.feature.events.common.EventsPortal
import space.einundzwanzig.meetup.feature.events.common.EventsSessions
import space.einundzwanzig.meetup.feature.events.common.ZoneProvider
import java.io.File
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

/** 2026-10-21 12:00 Berlin. */
val NOW: Instant = Instant.parse("2026-10-21T10:00:00Z")

val PK_ME = "a".repeat(64)
val PK_OTHER = "b".repeat(64)
val PK_HELPER = "c".repeat(64)

class FixedTime(var instant: Instant = NOW) : TimeSource {
    override fun nowMillis(): Long = instant.toEpochMilli()
}

val berlinZone = ZoneProvider { BERLIN }

fun nostrEvent(
    d: String = "d1",
    pubkey: String = PK_OTHER,
    title: String = "Event $d",
    start: Instant = NOW.plusSeconds(3_600),
    location: String = "Wien",
    description: String = "",
    badge: Boolean = false,
    lat: Double = 0.0,
    lng: Double = 0.0,
    issuers: List<String> = emptyList(),
) = NostrCalendarEvent(
    id = "id$d", pubkey = pubkey, dTag = d, title = title, description = description, location = location,
    start = start, end = null, allDay = false, kind = NostrCalendarEvent.KIND_TIME, fromApp = true,
    badgeEnabled = badge, lat = lat, lng = lng, issuers = issuers,
)

fun meetupDate(
    title: String = "Einundzwanzig München",
    start: Instant = NOW.plusSeconds(86_400),
    id: Int? = 1,
    meetupId: String = "107",
    location: String = "Augustiner",
) = MeetupDate(
    title = title, description = "Stammtisch", location = location, start = start,
    url = "https://portal.einundzwanzig.space/m/$meetupId", meetupId = meetupId, portalEventId = id,
)

fun meetup(id: String = "107", city: String = "München", name: String = "Einundzwanzig München") =
    Meetup(id = id, name = name, city = city, country = "DE", telegramLink = "", lat = 48.1, lng = 11.5)

class FakeEventCalendarRepository(var items: List<CalendarItem> = emptyList()) : EventCalendarRepository {
    val calls = mutableListOf<Boolean>()
    val forced = mutableListOf<Boolean>()
    override suspend fun load(worldwide: Boolean, force: Boolean): List<CalendarItem> {
        calls += worldwide
        forced += force
        return items
    }
}

class FakeCalendarEventService : CalendarEventService {
    var publishResult = 2
    var cancelResult = true
    var updateResult = true
    val published = mutableListOf<CalendarEventDraft>()
    val cancelled = mutableListOf<NostrCalendarEvent>()
    val updated = mutableListOf<Pair<NostrCalendarEvent, List<String>>>()
    var byAddress: Map<String, NostrCalendarEvent> = emptyMap()

    override suspend fun fetchEvents(limit: Int, worldwide: Boolean): List<NostrCalendarEvent> = emptyList()
    override suspend fun fetchByAddress(address: String): NostrCalendarEvent? = byAddress[address]
    override suspend fun publishEvent(draft: CalendarEventDraft, existingDTag: String?): Int {
        published += draft
        return publishResult
    }
    override suspend fun updateIssuers(event: NostrCalendarEvent, newIssuersHex: List<String>): Boolean {
        updated += event to newIssuersHex
        return updateResult
    }
    override suspend fun cancelEvent(event: NostrCalendarEvent): Boolean {
        cancelled += event
        return cancelResult
    }
}

class FakeRsvpService(var mine: Map<String, RsvpStatus> = emptyMap()) : EventRsvpService {
    var error: RsvpError? = null
    val sent = mutableListOf<Pair<String, RsvpStatus>>()
    override fun dTagFor(eventAddress: String): String = eventAddress
    override suspend fun setStatus(eventAddress: String, eventAuthorPubkey: String, status: RsvpStatus): RsvpError? {
        sent += eventAddress to status
        return error
    }
    override suspend fun loadMine(): Map<String, RsvpStatus> = mine
}

class FakeSessionService(var result: EventSessionResult = EventSessionResult()) : EventBadgeSessionService {
    val started = mutableListOf<NostrCalendarEvent>()
    override suspend fun start(event: NostrCalendarEvent): EventSessionResult {
        started += event
        return result
    }
}

class FakeIdentity(var pubkey: String? = PK_ME) : EventsIdentity {
    val prefetched = mutableListOf<String>()
    override suspend fun pubkeyHex(): String? = pubkey
    override suspend fun prefetchDisplayNames(hexes: Collection<String>) {
        prefetched += hexes
    }
}

class FakeSessions(var running: String? = null) : EventsSessions {
    var calls = 0
    override suspend fun runningSessionMeetupId(): String? {
        calls++
        return running
    }
}

class FakeAuth(var right: EventBadgeRight = EventBadgeRight.PORTAL_LEADER) : EventBadgeAuthService {
    override suspend fun myRight(): EventBadgeRight = right
}

class FakeBlossom(var result: UploadResult = UploadResult.success("https://blossom.example/x.jpg")) : BlossomUploadService {
    val uploads = mutableListOf<File>()
    override suspend fun uploadImage(file: File): UploadResult {
        uploads += file
        return result
    }
}

class FakePreparer(var failure: Exception? = null) : BadgeImagePreparer {
    val prepared = mutableListOf<String>()
    override suspend fun prepare(uri: String): File {
        failure?.let { throw it }
        prepared += uri
        return File.createTempFile("badge", ".jpg")
    }
}

class FakeMyEventsService(
    var events: List<NostrCalendarEvent> = emptyList(),
    var dates: List<MyMeetupDate> = emptyList(),
) : MyEventsService {
    var withdrawError: RsvpError? = null
    val withdrawn = mutableListOf<NostrCalendarEvent>()
    var cards: FavoriteCards = FavoriteCards.NoFavorites
    override suspend fun loadAcceptedEvents(): List<NostrCalendarEvent> = events
    override suspend fun loadMeetupRsvps(dates: List<MeetupDate>?): List<MyMeetupDate> = this.dates
    override suspend fun withdraw(event: NostrCalendarEvent): RsvpError? {
        withdrawn += event
        return withdrawError
    }
    override suspend fun loadFavoriteCards(dates: List<MeetupDate>?, updateWidget: Boolean): FavoriteCards = cards
}

class FakeEventsChat : EventsChat {
    var room: ChatRoom? = null
    var failure: Exception? = null
    var unread: Map<String, Int> = emptyMap()
    var unreadCalls = 0
    override suspend fun findMeetupRoom(favKey: String): ChatRoom? {
        failure?.let { throw it }
        return room
    }
    override suspend fun eventUnreadCounts(addresses: List<String>): Map<String, Int> {
        unreadCalls++
        return unread.filterKeys { it in addresses }
    }
}

class FakeMeetupCalendarService(var dates: List<MeetupDate> = emptyList()) : MeetupCalendarService {
    override suspend fun fetchPortalFirst(force: Boolean): List<MeetupDate> = dates
    override suspend fun fetchIcs(): List<MeetupDate> = emptyList()
}

class FakeMeetupService(var meetups: List<Meetup> = listOf(meetup())) : MeetupService {
    override val cached: List<Meetup> get() = meetups
    override val cachedFlow: StateFlow<List<Meetup>> = MutableStateFlow(meetups)
    override val fallbackMeetups: List<Meetup> = emptyList()
    override suspend fun fetchMeetups(): List<Meetup> = meetups
    override fun resolveFavorite(stored: String): Meetup? = meetups.firstOrNull { it.id == stored || it.city == stored }
    override fun labelFor(stored: String): String = resolveFavorite(stored)?.city ?: stored
    override fun cityFor(stored: String): String = resolveFavorite(stored)?.city ?: stored
    override fun bySlug(slug: String, country: String): Meetup? = null
    override fun byName(name: String): Meetup? = null
    override suspend fun organizerMeetupFor(pubkeyHexOrNpub: String): Meetup? = null
}

class FakeProfiles(var current: UserProfile = UserProfile()) : UserProfileRepository {
    private val flow = MutableStateFlow(current)
    /** When set, [update] suspends until it completes (simulates a slow write). */
    var updateGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var failUpdate = false
    override suspend fun load(): UserProfile = current
    override suspend fun save(profile: UserProfile) {
        current = profile
        flow.value = profile
    }
    override suspend fun update(transform: (UserProfile) -> UserProfile): UserProfile {
        updateGate?.await()
        if (failUpdate) throw java.io.IOException("disk full")
        save(transform(current))
        return current
    }
    override val profile: Flow<UserProfile> = flow
}

class FakePortal : EventsPortal {
    var tokenOk = true
    var loginResult = PortalResult(ok = true, statusCode = 200)
    var rsvpResult = PortalResult(ok = true, statusCode = 200)
    var rsvpMap: Map<Int, JsonObject> = emptyMap()
    var attendees: List<Attendee> = listOf(Attendee("npub1" + "x".repeat(58), "Satoshi"))
    val rsvps = mutableListOf<Pair<Int, String>>()
    var logins = 0
    /** When set, [loginWithNostr] suspends until it completes. */
    var loginGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    override suspend fun tokenMatchesCurrentKey(): Boolean = tokenOk
    override suspend fun loginWithNostr(): PortalResult {
        logins++
        loginGate?.await()
        if (loginResult.ok) tokenOk = true
        return loginResult
    }
    override suspend fun rsvp(eventId: Int, status: String): PortalResult {
        rsvps += eventId to status
        return rsvpResult
    }
    override suspend fun getRsvpCached(eventId: Int, forceRefresh: Boolean): JsonObject? = rsvpMap[eventId]
    override suspend fun getRsvpAttendees(eventId: Int): List<Attendee> = attendees
    override fun isGoing(r: JsonObject?): Boolean {
        val status = (r?.get("status") as? JsonPrimitive)?.contentOrNull
        if (status == "attending" || status == "maybe") return true
        return r?.get("going")?.jsonPrimitive?.booleanOrNull == true
    }
}
