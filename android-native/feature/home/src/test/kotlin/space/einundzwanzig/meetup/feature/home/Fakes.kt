package space.einundzwanzig.meetup.feature.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonElement
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.calendar.model.FavoriteMeetupCard
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate
import space.einundzwanzig.meetup.data.calendar.model.MyMeetupDate
import space.einundzwanzig.meetup.data.calendar.model.NostrCalendarEvent
import space.einundzwanzig.meetup.data.chat.ChatRoom
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.meetups.portal.PortalResult
import space.einundzwanzig.meetup.data.meetups.portal.StatusBody
import space.einundzwanzig.meetup.data.mempool.BitcoinDashboardData
import space.einundzwanzig.meetup.data.proofs.satoshiduell.DuellStatus
import space.einundzwanzig.meetup.data.trust.ZoneIdProvider
import space.einundzwanzig.meetup.feature.backup.BackupLauncher
import space.einundzwanzig.meetup.feature.home.data.FavoriteMeetupInfo
import space.einundzwanzig.meetup.feature.home.data.FavoritesSnapshot
import space.einundzwanzig.meetup.feature.home.data.HomeDataSource
import space.einundzwanzig.meetup.feature.home.data.LiveSession
import space.einundzwanzig.meetup.feature.home.data.MyEventsSnapshot
import space.einundzwanzig.meetup.feature.home.data.NewsCounters
import space.einundzwanzig.meetup.feature.home.organizer.OrganizerPorts
import space.einundzwanzig.meetup.feature.home.organizer.PortalOrganizerChecker
import space.einundzwanzig.meetup.feature.home.reset.AppResetter
import space.einundzwanzig.meetup.feature.home.tiles.TileOrderRepository
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

/** 2026-10-22 12:00 Berlin. */
val NOW: Instant = Instant.parse("2026-10-22T10:00:00Z")

class FixedTime(var millis: Long = NOW.toEpochMilli()) : TimeSource {
    override fun nowMillis(): Long = millis
}

fun profile(
    nickname: String = "Satoshi",
    npub: String = "npub1test",
    home: String = "",
    favorites: List<String> = emptyList(),
    adminViaPortal: Boolean = false,
    reviewDemo: Boolean = false,
) = UserProfile(
    nickname = nickname,
    nostrNpub = npub,
    homeMeetupId = home,
    favoriteMeetupIds = favorites,
    adminViaPortal = adminViaPortal,
    isReviewDemo = reviewDemo,
)

fun meetupDate(start: Instant, portalId: Int? = null) =
    MeetupDate(title = "Meetup", description = "", location = "", start = start, url = "", portalEventId = portalId)

fun calendarEvent(id: String, start: Instant = NOW) = NostrCalendarEvent(
    id = id,
    pubkey = "a".repeat(64),
    dTag = id,
    title = "Event $id",
    description = "",
    location = "",
    start = start,
    end = null,
    allDay = false,
    kind = 31923,
)

/** Configurable fake of the dashboard seam; counts the calls the tests check. */
class FakeHomeDataSource : HomeDataSource {
    val profileFlow = MutableStateFlow(space.einundzwanzig.meetup.feature.home.profile())
    var user: UserProfile
        get() = profileFlow.value
        set(value) {
            profileFlow.value = value
        }
    override val profile: kotlinx.coroutines.flow.Flow<UserProfile> = profileFlow
    override suspend fun loadUser(): UserProfile = user

    var directoryRefreshes = 0
    override suspend fun refreshMeetupDirectory() {
        directoryRefreshes++
    }

    val badgeFlow = MutableStateFlow<List<MeetupBadge>>(emptyList())
    val unreadableFlow = MutableStateFlow(false)
    override val badges: StateFlow<List<MeetupBadge>> = badgeFlow
    override val badgesUnreadable: StateFlow<Boolean> = unreadableFlow
    var loadBadgeCalls = 0
    override suspend fun loadBadges(): Boolean {
        loadBadgeCalls++
        return unreadableFlow.value
    }

    var score = 3.25
    override fun trustScore(badges: List<MeetupBadge>): Double = score

    var reVerifyCalls = 0
    override suspend fun reVerifyAdmin(badges: List<MeetupBadge>, homeMeetupId: String) {
        reVerifyCalls++
    }

    var humanityCalls = 0
    override suspend fun reverifyHumanity() {
        humanityCalls++
    }

    var organicCalls = 0
    override suspend fun syncOrganicAdmins() {
        organicCalls++
    }

    var compromised = false
    override suspend fun deviceCompromised(): Boolean = compromised

    var session: LiveSession? = null
    override suspend fun loadSession(): LiveSession? = session

    var favorites = FavoritesSnapshot(emptyList(), loaded = true)
    var favoritesCalls = 0
    var favoritesGate: CompletableDeferred<Unit>? = null
    override suspend fun loadFavorites(): FavoritesSnapshot {
        favoritesCalls++
        favoritesGate?.await()
        return favorites
    }

    var rsvps: List<MyMeetupDate> = emptyList()
    override suspend fun loadMeetupRsvps(dates: List<MeetupDate>): List<MyMeetupDate> = rsvps

    var infos: Map<String, FavoriteMeetupInfo> = emptyMap()
    override fun favoriteInfo(favKey: String, city: String): FavoriteMeetupInfo? = infos[favKey]
    override fun favoriteLabel(favKey: String): String = "Label $favKey"
    override fun favoriteCity(favKey: String): String = "City $favKey"

    var room: ChatRoom? = null
    var roomError: Exception? = null
    override suspend fun findChatRoom(favKey: String): ChatRoom? {
        roomError?.let { throw it }
        return room
    }

    var unread: Map<String, Int> = emptyMap()
    var chatUnreadCalls = 0
    override suspend fun chatUnread(favKeys: List<String>): Map<String, Int> {
        chatUnreadCalls++
        return unread
    }

    var myEvents = MyEventsSnapshot(emptyList(), 0)
    var myEventsCalls = 0
    override suspend fun loadMyEvents(): MyEventsSnapshot {
        myEventsCalls++
        return myEvents
    }

    var news = NewsCounters(0, null)
    var newsCalls = 0
    override suspend fun newsCounters(): NewsCounters {
        newsCalls++
        return news
    }

    var publishedNews = 0
    override suspend fun publishLatestNews() {
        publishedNews++
    }

    var today = 0
    override suspend fun eventsToday(): Int = today

    val dashboardFlow = MutableStateFlow<BitcoinDashboardData?>(null)
    val currencyFlow = MutableStateFlow("EUR")
    override val dashboard: StateFlow<BitcoinDashboardData?> = dashboardFlow
    override val currency: StateFlow<String> = currencyFlow
    var dashboardRefreshes = 0
    var dashboardResult: BitcoinDashboardData? = null
    override suspend fun refreshDashboard(): BitcoinDashboardData {
        dashboardRefreshes++
        return dashboardResult ?: checkNotNull(dashboardFlow.value)
    }

    var duell = DuellStatus(0, 0, 0)
    override suspend fun duellStatus(): DuellStatus = duell

    override val plebrapIndex = MutableStateFlow<Int?>(null)
    override val plebrapLoading = MutableStateFlow(false)
    override val plebrapPlaying = MutableStateFlow(false)
    var toggles = 0
    var nexts = 0
    override suspend fun plebrapToggle() {
        toggles++
    }
    override suspend fun plebrapNext() {
        nexts++
    }

    var lastSeen = 0L
    override suspend fun nostrLastSeen(): Long = lastSeen
    override suspend fun setNostrLastSeen(seconds: Long) {
        lastSeen = seconds
    }

    var local: String? = null
    var pictureUrl: String? = null
    override suspend fun localPicture(): String? = local
    override suspend fun profilePicture(npub: String): String? = pictureUrl
    var importError: Exception? = null
    override suspend fun importPicture(uri: String): String {
        importError?.let { throw it }
        return "/files/profile_picture/p.jpg"
    }

    var npub: String? = "npub1me"
    override suspend fun npub(): String? = npub

    var connected = false
    var loginResult = PortalResult(ok = true, statusCode = 200)
    var logouts = 0
    override suspend fun portalConnected(): Boolean = connected
    override suspend fun portalLogin(): PortalResult = loginResult
    override suspend fun portalLogout() {
        logouts++
    }

    fun card(key: String, start: Instant?) =
        FavoriteMeetupCard(favKey = key, label = "Label $key", city = "City $key", next = start?.let { meetupDate(it) })
}

/** Organizer ports with a scripted portal. */
class FakeOrganizerPorts : OrganizerPorts {
    var token = false
    var external = false
    var loginOk = true
    var matches = true
    var statuses = ArrayDeque<StatusBody>()
    var profileValue = space.einundzwanzig.meetup.feature.home.profile()
    var logins = 0
    var deletes = 0
    var claims = mutableListOf<String>()
    var removed = mutableListOf<String>()

    override suspend fun hasToken() = token
    override suspend fun isExternalSigner() = external
    override suspend fun login(): Boolean {
        logins++
        if (loginOk) {
            token = true
            matches = true
        }
        return loginOk
    }
    override suspend fun tokenMatchesCurrentKey() = token && matches
    override suspend fun deleteToken() {
        deletes++
        token = false
    }
    override suspend fun myMeetups(): StatusBody = statuses.removeFirstOrNull() ?: StatusBody(0, null)
    override suspend fun loadProfile() = profileValue
    override suspend fun updateProfile(transform: (UserProfile) -> UserProfile): UserProfile {
        profileValue = transform(profileValue)
        return profileValue
    }
    override suspend fun badges(): List<MeetupBadge> = emptyList()
    override suspend fun publishAdminClaim(badges: List<MeetupBadge>, meetupName: String) {
        claims += meetupName
    }
    override suspend fun ownNpub(): String? = "npub1me"
    override suspend fun removeFromAdminCache(npub: String) {
        removed += npub
    }
}

class FakeBackupLauncher(var result: Boolean = true) : BackupLauncher {
    override val busy = MutableStateFlow(false)
    var exports = 0
    override suspend fun export(): Boolean {
        exports++
        return result
    }
    override suspend fun restore(): Boolean = true
    override fun launchExport(onResult: (Boolean) -> Unit) = onResult(result)
    override fun launchRestore(onResult: (Boolean) -> Unit) = onResult(true)
}

class FakeResetter(var ok: Boolean = true) : AppResetter {
    var calls = 0
    override suspend fun reset(): Boolean {
        calls++
        return ok
    }
}

fun status(code: Int, body: JsonElement?) = StatusBody(code, body)

/** Builds a ViewModel with fakes. */
class VmFixture(
    val data: FakeHomeDataSource = FakeHomeDataSource(),
    val ports: FakeOrganizerPorts = FakeOrganizerPorts().apply { token = true },
    val store: InMemoryKeyValueStore = InMemoryKeyValueStore(),
    val backup: FakeBackupLauncher = FakeBackupLauncher(),
    val resetter: FakeResetter = FakeResetter(),
    val refresh: HomeRefreshRequests = HomeRefreshRequests(),
    val time: FixedTime = FixedTime(),
) {
    fun create() = HomeViewModel(
        data = data,
        organizer = PortalOrganizerChecker(ports, AppLogger),
        tiles = TileOrderRepository(store),
        resetter = resetter,
        backup = backup,
        refreshRequests = refresh,
        time = time,
        zones = ZoneIdProvider { BERLIN },
        log = AppLogger,
    )
}
