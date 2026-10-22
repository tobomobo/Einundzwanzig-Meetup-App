package space.einundzwanzig.meetup.feature.meetups

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.data.calendar.MeetupCalendarService
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.meetups.MeetupService
import space.einundzwanzig.meetup.data.meetups.geocoding.GeoPlace
import space.einundzwanzig.meetup.data.meetups.geocoding.GeocodingService
import space.einundzwanzig.meetup.data.meetups.location.LocationMeetupResult
import space.einundzwanzig.meetup.data.meetups.location.LocationPermission
import space.einundzwanzig.meetup.data.meetups.location.LocationResult
import space.einundzwanzig.meetup.data.meetups.location.MeetupLocationService
import space.einundzwanzig.meetup.data.meetups.location.GpsStatus
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.data.meetups.nearby.DateMode
import space.einundzwanzig.meetup.data.meetups.nearby.NearbyMeetup
import space.einundzwanzig.meetup.data.meetups.nearby.NearbyMeetupService
import space.einundzwanzig.meetup.data.meetups.portal.Attendee
import space.einundzwanzig.meetup.data.meetups.portal.PortalApiService
import space.einundzwanzig.meetup.data.meetups.portal.PortalMeetup
import space.einundzwanzig.meetup.data.meetups.portal.PortalMeetupEvent
import space.einundzwanzig.meetup.data.meetups.portal.PortalResult
import space.einundzwanzig.meetup.data.meetups.portal.StatusBody
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

class FixedTime(var now: Long = 1_784_000_000_000) : TimeSource {
    override fun nowMillis(): Long = now
}

fun meetup(id: String, city: String, name: String = "", lat: Double = 50.0, lng: Double = 10.0, country: String = "DE") =
    Meetup(id = id, name = name, city = city, country = country, telegramLink = "", lat = lat, lng = lng)

class FakeMeetupService(var list: List<Meetup> = emptyList()) : MeetupService {
    var fetchCalls = 0
    var cache: List<Meetup> = emptyList()
    override val cached: List<Meetup> get() = cache
    override val cachedFlow: StateFlow<List<Meetup>> = MutableStateFlow(emptyList())
    override val fallbackMeetups: List<Meetup> = listOf(meetup("m_muc", "München", lat = 48.1351, lng = 11.582))
    override suspend fun fetchMeetups(): List<Meetup> {
        fetchCalls++
        if (list.isNotEmpty()) cache = list
        return list
    }
    override fun resolveFavorite(stored: String): Meetup? = cache.firstOrNull { it.id == stored }
    override fun labelFor(stored: String): String = stored
    override fun cityFor(stored: String): String = stored
    override fun bySlug(slug: String, country: String): Meetup? = null
    override fun byName(name: String): Meetup? = null
    override suspend fun organizerMeetupFor(pubkeyHexOrNpub: String): Meetup? = null
}

class FakeCalendarService(var dates: List<MeetupDate> = emptyList()) : MeetupCalendarService {
    override suspend fun fetchPortalFirst(force: Boolean): List<MeetupDate> = dates
    override suspend fun fetchIcs(): List<MeetupDate> = emptyList()
}

class FakeProfiles(var current: UserProfile = UserProfile()) : UserProfileRepository {
    var saves = 0
    var failSave = false
    var failLoad = false
    private val flow = MutableStateFlow(current)
    override suspend fun load(): UserProfile {
        if (failLoad) error("secure store unreadable")
        return current
    }
    override suspend fun save(profile: UserProfile) {
        if (failSave) error("disk full")
        saves++
        current = profile
        flow.value = profile
    }
    override suspend fun update(transform: (UserProfile) -> UserProfile): UserProfile {
        val p = transform(load())
        save(p)
        return p
    }
    override val profile: Flow<UserProfile> = flow
}

class FakeLocationService(var result: LocationResult = LocationResult(space.einundzwanzig.meetup.data.meetups.location.LocationStatus.OK)) :
    MeetupLocationService {
    var calls = 0
    var gate: CompletableDeferred<Unit>? = null
    var openedAppSettings = 0
    var openedLocationSettings = 0
    override fun isLocationServiceEnabled(): Boolean = true
    override fun permissionState(): LocationPermission = LocationPermission.GRANTED
    override suspend fun getCurrentLocation(): LocationResult {
        calls++
        gate?.await()
        return result
    }
    override suspend fun resolveLocation(): LocationMeetupResult = LocationMeetupResult(GpsStatus.OK)
    override fun openLocationSettings(): Boolean {
        openedLocationSettings++
        return true
    }
    override fun openAppSettings(): Boolean {
        openedAppSettings++
        return true
    }
}

data class SearchCall(
    val lat: Double,
    val lng: Double,
    val radiusKm: Double,
    val mode: DateMode,
    val from: LocalDate?,
    val to: LocalDate?,
)

class FakeNearbyService(var results: List<NearbyMeetup> = emptyList()) : NearbyMeetupService {
    val calls = mutableListOf<SearchCall>()
    override suspend fun preload(forceReload: Boolean) = Unit
    override suspend fun search(
        centerLat: Double,
        centerLng: Double,
        radiusKm: Double,
        dateMode: DateMode,
        dayFrom: LocalDate?,
        dayTo: LocalDate?,
        zone: ZoneId,
    ): List<NearbyMeetup> {
        calls += SearchCall(centerLat, centerLng, radiusKm, dateMode, dayFrom, dayTo)
        return results
    }
}

class FakeGeocoding(var places: List<GeoPlace> = emptyList()) : GeocodingService {
    val queries = mutableListOf<String>()
    override suspend fun search(query: String, limit: Int): List<GeoPlace> {
        queries += query
        return places
    }
}

class FakePortal : PortalApiService {
    var token: String? = null
    var tokenMatches = true
    var loginResult = PortalResult(ok = true, statusCode = 201)
    var myMeetups: List<PortalMeetup> = emptyList()
    var myEvents: List<PortalMeetupEvent> = emptyList()
    var leaders: MutableMap<Int, List<JsonObject>> = mutableMapOf()
    var writeResult = PortalResult(ok = true, statusCode = 200)
    var getMyMeetupsCalls = 0
    var getMyEventsCalls = 0
    var logoutCalls = 0
    var deleteCalls = 0
    val created = mutableListOf<List<String?>>()
    val updated = mutableListOf<List<Any?>>()
    val added = mutableListOf<Pair<Int, String>>()
    val removed = mutableListOf<Pair<Int, Int>>()
    private var lastEvents: List<PortalMeetupEvent> = emptyList()

    override suspend fun getToken(): String? = token
    override suspend fun hasToken(): Boolean = token != null
    override suspend fun storeToken(token: String): Boolean {
        this.token = token
        return true
    }
    override suspend fun setManualToken(token: String): Boolean = storeToken(token)
    override suspend fun deleteToken(): Boolean {
        deleteCalls++
        token = null
        return true
    }
    override suspend fun loginWithNostr(deviceName: String): PortalResult {
        if (loginResult.ok) token = "t"
        return loginResult
    }
    override suspend fun logout() {
        logoutCalls++
        token = null
    }
    override suspend fun tokenMatchesCurrentKey(): Boolean = tokenMatches
    override suspend fun getProfile(): JsonObject? = null
    override suspend fun rawGet(path: String): JsonElement? = null
    override suspend fun rawGetStatus(path: String): StatusBody = StatusBody(0, null)
    override suspend fun getMyMeetups(): List<PortalMeetup> {
        getMyMeetupsCalls++
        return myMeetups
    }
    override suspend fun getMyMeetupEvents(): List<PortalMeetupEvent> {
        getMyEventsCalls++
        lastEvents = myEvents
        return myEvents
    }
    override val lastMyMeetupEvents: List<PortalMeetupEvent> get() = lastEvents
    fun primeLastEvents(events: List<PortalMeetupEvent>) {
        lastEvents = events
    }
    override suspend fun createMeetupEvent(
        meetupId: Int,
        start: String,
        location: String?,
        description: String?,
        link: String?,
        recurrenceType: String?,
        recurrenceDayOfWeek: String?,
        recurrenceDayPosition: String?,
        recurrenceInterval: Int?,
        recurrenceEndDate: String?,
    ): PortalResult {
        created += listOf(meetupId.toString(), start, location, description, link)
        return writeResult
    }
    override suspend fun updateMeetupEvent(
        eventId: Int,
        meetupId: Int?,
        start: String?,
        location: String?,
        description: String?,
        link: String?,
        recurrenceType: String?,
        recurrenceDayOfWeek: String?,
        recurrenceDayPosition: String?,
        recurrenceInterval: Int?,
        recurrenceEndDate: String?,
    ): PortalResult {
        updated += listOf(eventId, meetupId, start, location, description, link)
        return writeResult
    }
    var getLeadersCalls = 0
    override suspend fun getMeetupLeaders(meetupId: Int): List<JsonObject> {
        getLeadersCalls++
        return leaders[meetupId].orEmpty()
    }
    override suspend fun addMeetupLeader(meetupId: Int, npub: String): PortalResult {
        added += meetupId to npub
        return writeResult
    }
    override suspend fun removeMeetupLeader(meetupId: Int, userId: Int): PortalResult {
        removed += meetupId to userId
        return writeResult
    }
    override suspend fun getAllMeetupEvents(today: LocalDate): List<JsonObject> = emptyList()
    override suspend fun getAllMeetupEvents(): List<JsonObject> = emptyList()
    override suspend fun getRsvp(eventId: Int): JsonObject? = null
    override suspend fun getRsvpCached(eventId: Int, forceRefresh: Boolean): JsonObject? = null
    override suspend fun clearRsvpCache(eventId: Int) = Unit
    override suspend fun rsvp(eventId: Int, status: String): PortalResult = writeResult
    override suspend fun getRsvpAttendees(eventId: Int): List<Attendee> = emptyList()
    override fun isGoing(r: JsonObject?): Boolean = false
    override suspend fun getCourses(): List<JsonObject> = emptyList()
    override suspend fun getCourse(id: Int): JsonObject? = null
    override suspend fun getLecturers(): List<JsonObject> = emptyList()
    override suspend fun getLecturer(id: Int): JsonObject? = null
}
