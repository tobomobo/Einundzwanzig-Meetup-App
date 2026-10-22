package space.einundzwanzig.meetup.feature.home.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.data.admin.claims.PromotionClaimService
import space.einundzwanzig.meetup.data.admin.verify.AdminStatusVerifier
import space.einundzwanzig.meetup.data.badges.claim.BadgeClaimService
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.calendar.CalendarEventService
import space.einundzwanzig.meetup.data.calendar.MeetupCalendarService
import space.einundzwanzig.meetup.data.calendar.MyEventsService
import space.einundzwanzig.meetup.data.calendar.model.FavoriteCards
import space.einundzwanzig.meetup.data.calendar.model.FavoriteMeetupCard
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate
import space.einundzwanzig.meetup.data.calendar.model.MyMeetupDate
import space.einundzwanzig.meetup.data.calendar.model.NostrCalendarEvent
import space.einundzwanzig.meetup.data.calendar.widget.HomeWidgetStore
import space.einundzwanzig.meetup.data.chat.ChatRoom
import space.einundzwanzig.meetup.data.chat.ChatService
import space.einundzwanzig.meetup.data.chat.EventChatService
import space.einundzwanzig.meetup.data.community.plebrap.PlebrapPlayer
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.identity.integrity.DeviceIntegrityService
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.issuance.session.RollingQrService
import space.einundzwanzig.meetup.data.meetups.MeetupService
import space.einundzwanzig.meetup.data.meetups.portal.PortalApiService
import space.einundzwanzig.meetup.data.meetups.portal.PortalLogoRegistry
import space.einundzwanzig.meetup.data.meetups.portal.PortalResult
import space.einundzwanzig.meetup.data.mempool.BitcoinDashboardData
import space.einundzwanzig.meetup.data.mempool.MempoolService
import space.einundzwanzig.meetup.data.mempool.currency.CurrencyRepository
import space.einundzwanzig.meetup.data.mempool.widget.BitcoinWidgetBridge
import space.einundzwanzig.meetup.data.news.NewsArticles
import space.einundzwanzig.meetup.data.news.NewsService
import space.einundzwanzig.meetup.data.profile.LocalPictureImporter
import space.einundzwanzig.meetup.data.profile.NostrProfileService
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityProofRepository
import space.einundzwanzig.meetup.data.proofs.satoshiduell.DuellStatus
import space.einundzwanzig.meetup.data.proofs.satoshiduell.SatoshiDuellClient
import space.einundzwanzig.meetup.data.reputation.publish.ReputationPublisher
import space.einundzwanzig.meetup.data.settings.SettingsRepository
import space.einundzwanzig.meetup.data.trust.ZoneIdProvider
import space.einundzwanzig.meetup.data.trust.score.TrustScoreCalculator
import space.einundzwanzig.meetup.feature.home.organizer.PortalOrganizerChecker
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

/** Live rolling-QR session as the dashboard shows it (spec 12 §2.5.4). The seed never leaves the data layer. */
data class LiveSession(val meetupId: String, val meetupName: String, val expiresAtSeconds: Long) {
    val isEvent: Boolean get() = meetupId.startsWith("evt:")
}

/** Resolved meetup facts for a favorite card (crest, country, details id). */
data class FavoriteMeetupInfo(val meetupId: String, val country: String, val crestUrl: String)

/**
 * Outcome of the favorites load: the cards (sorted by the calendar), whether any dates could be
 * loaded, and the dates themselves (input of the RSVP scan).
 */
data class FavoritesSnapshot(
    val cards: List<FavoriteMeetupCard>,
    val loaded: Boolean,
    val dates: List<MeetupDate> = emptyList(),
)

/** "Meine Termine": accepted Nostr events and the summed unread count of their chats. */
data class MyEventsSnapshot(val events: List<NostrCalendarEvent>, val unread: Int)

/** News tile counters. */
data class NewsCounters(val unread: Int, val latestTitle: String?)

/**
 * Everything the dashboard reads and triggers, as one seam over the data modules (no data logic
 * of its own; the default implementation only delegates). The ViewModel tests fake this
 * interface.
 */
interface HomeDataSource {
    /** Profile changes (favorites, nickname, admin flags) from any screen. */
    val profile: Flow<UserProfile>
    suspend fun loadUser(): UserProfile

    /** `MeetupService.fetchMeetups()` (Dart `_loadUser` with favorites). */
    suspend fun refreshMeetupDirectory()

    val badges: StateFlow<List<MeetupBadge>>
    val badgesUnreadable: StateFlow<Boolean>

    /**
     * `_loadBadges`: loads the store, claims unbound badges while it is readable and publishes the
     * reputation in the background for a readable, non-empty wallet. Returns `true` if unreadable.
     */
    suspend fun loadBadges(): Boolean
    /** `calculateScore(badges, firstBadgeDate)` → `totalScore`. */
    fun trustScore(badges: List<MeetupBadge>): Double

    /** `_reVerifyAdminStatus` without the unreadable guard (the caller checks it). */
    suspend fun reVerifyAdmin(badges: List<MeetupBadge>, homeMeetupId: String)

    /** `_loadIdentityData`: humanity proof re-verification when a restore asked for it. */
    suspend fun reverifyHumanity()
    suspend fun syncOrganicAdmins()
    suspend fun deviceCompromised(): Boolean
    suspend fun loadSession(): LiveSession?

    /** `_loadNextHomeMeetup` (also updates the home-screen widget). */
    suspend fun loadFavorites(): FavoritesSnapshot

    /** `_loadMeetupRsvps`: the favorites' portal dates the user RSVP'd to. */
    suspend fun loadMeetupRsvps(dates: List<MeetupDate>): List<MyMeetupDate>
    fun favoriteInfo(favKey: String, city: String): FavoriteMeetupInfo?
    fun favoriteLabel(favKey: String): String
    fun favoriteCity(favKey: String): String

    /** `_findRoomFor`; throws `ChatRelayUnavailable` (no fallback to the city then). */
    suspend fun findChatRoom(favKey: String): ChatRoom?

    /** `_loadChatUnread`: unread count per favorite key. */
    suspend fun chatUnread(favKeys: List<String>): Map<String, Int>
    suspend fun loadMyEvents(): MyEventsSnapshot
    suspend fun newsCounters(): NewsCounters

    /** Dart `WidgetService.refreshNews()` at Home start. */
    suspend fun publishLatestNews()
    suspend fun eventsToday(): Int

    val dashboard: StateFlow<BitcoinDashboardData?>
    val currency: StateFlow<String>

    /** BTC tile poll: fetch the dashboard and feed the widget (dead data is skipped). */
    suspend fun refreshDashboard()
    suspend fun duellStatus(): DuellStatus

    val plebrapIndex: StateFlow<Int?>
    val plebrapLoading: StateFlow<Boolean>
    val plebrapPlaying: StateFlow<Boolean>
    suspend fun plebrapToggle()
    suspend fun plebrapNext()

    suspend fun nostrLastSeen(): Long
    suspend fun setNostrLastSeen(seconds: Long)
    suspend fun localPicture(): String?
    suspend fun profilePicture(npub: String): String?
    suspend fun importPicture(uri: String): String
    suspend fun npub(): String?

    suspend fun portalConnected(): Boolean
    suspend fun portalLogin(): PortalResult
    suspend fun portalLogout()
}

internal class DefaultHomeDataSource @Inject constructor(
    private val profiles: UserProfileRepository,
    private val meetups: MeetupService,
    private val badgeRepository: BadgeRepository,
    private val claimService: BadgeClaimService,
    private val publisher: ReputationPublisher,
    private val calculator: TrustScoreCalculator,
    private val adminVerifier: AdminStatusVerifier,
    private val promotionClaims: PromotionClaimService,
    private val humanity: HumanityProofRepository,
    private val integrity: DeviceIntegrityService,
    private val rollingQr: RollingQrService,
    private val calendar: MeetupCalendarService,
    private val calendarEvents: CalendarEventService,
    private val myEvents: MyEventsService,
    private val logos: PortalLogoRegistry,
    private val chat: ChatService,
    private val eventChat: EventChatService,
    private val news: NewsService,
    private val widget: HomeWidgetStore,
    private val mempool: MempoolService,
    private val currencyRepository: CurrencyRepository,
    private val widgetBridge: BitcoinWidgetBridge,
    private val duell: SatoshiDuellClient,
    private val plebrap: PlebrapPlayer,
    private val settings: SettingsRepository,
    private val profileService: NostrProfileService,
    private val pictureImporter: LocalPictureImporter,
    private val signing: SigningService,
    private val portal: PortalApiService,
    private val time: TimeSource,
    private val zones: ZoneIdProvider,
    private val log: DiagnosticLog,
) : HomeDataSource {

    override val profile: Flow<UserProfile> get() = profiles.profile
    override suspend fun loadUser(): UserProfile = profiles.load()

    override suspend fun refreshMeetupDirectory() {
        meetups.fetchMeetups()
    }

    override val badges: StateFlow<List<MeetupBadge>> get() = badgeRepository.badges
    override val badgesUnreadable: StateFlow<Boolean> get() = badgeRepository.badgesUnreadable

    override suspend fun loadBadges(): Boolean {
        val loaded = badgeRepository.loadBadges()
        val unreadable = badgeRepository.badgesUnreadable.value
        if (!unreadable) claimService.ensureBadgesClaimed(loaded)
        val current = badgeRepository.badges.value
        if (!unreadable && current.isNotEmpty()) publisher.publishInBackground(current)
        return unreadable
    }

    override fun trustScore(badges: List<MeetupBadge>): Double = calculator.calculate(badges).totalScore

    override suspend fun reVerifyAdmin(badges: List<MeetupBadge>, homeMeetupId: String) {
        val v = adminVerifier.reVerifyAdmin(badges).verification
        if (v.shouldPublishClaim) {
            try {
                promotionClaims.publishAdminClaim(badges, homeMeetupId.ifEmpty { PortalOrganizerChecker.UNKNOWN_MEETUP })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug(TAG_ADMIN, "Organisator-Claim nicht veröffentlicht: $e")
            }
            log.info(TAG_ADMIN, "Organisator-Status bestaetigt (${v.source}).")
        }
    }

    override suspend fun reverifyHumanity() {
        if (humanity.getStatus().needsReverification) humanity.reverifyIfNeeded()
    }

    override suspend fun syncOrganicAdmins() {
        promotionClaims.syncOrganicAdmins()
    }

    override suspend fun deviceCompromised(): Boolean = integrity.check().isCompromised

    override suspend fun loadSession(): LiveSession? =
        rollingQr.loadSession()?.let { LiveSession(it.meetupId, it.meetupName, it.expiresAt) }

    override suspend fun loadFavorites(): FavoritesSnapshot {
        val dates = calendar.fetchPortalFirst()
        if (meetups.cached.isEmpty()) meetups.fetchMeetups()
        val result = myEvents.loadFavoriteCards(dates)
        val loaded = result is FavoriteCards.Loaded && result.datesAvailable
        return FavoritesSnapshot(result.cards, loaded, dates)
    }

    override suspend fun loadMeetupRsvps(dates: List<MeetupDate>): List<MyMeetupDate> =
        if (dates.isEmpty()) emptyList() else myEvents.loadMeetupRsvps(dates)

    override fun favoriteInfo(favKey: String, city: String): FavoriteMeetupInfo? {
        val meetup = meetups.resolveFavorite(favKey)
            ?: (meetups.cached.firstOrNull { it.city == city } ?: meetups.fallbackMeetups.firstOrNull { it.city == city })
            ?: return null
        val own = meetup.logoUrl.ifEmpty { meetup.coverImagePath }
        val crest = logos.absoluteImageUrl(own).ifEmpty { logos.logoFor(city) }
        return FavoriteMeetupInfo(meetup.id, meetup.country, crest)
    }

    override fun favoriteLabel(favKey: String): String = meetups.labelFor(favKey)
    override fun favoriteCity(favKey: String): String = meetups.cityFor(favKey)

    override suspend fun findChatRoom(favKey: String): ChatRoom? {
        val meetup = meetups.resolveFavorite(favKey)
        if (meetup != null) chat.findRoomForMeetupId(meetup.id)?.let { return it }
        return chat.findRoomForCity(meetups.cityFor(favKey))
    }

    override suspend fun chatUnread(favKeys: List<String>): Map<String, Int> {
        val byRoom = linkedMapOf<String, String>()
        for (key in favKeys) {
            val room = findChatRoom(key) ?: continue
            byRoom[room.h] = key
        }
        if (byRoom.isEmpty()) return emptyMap()
        val counts = chat.unreadCounts(byRoom.keys.toList())
        return byRoom.entries.associate { (h, key) -> key to (counts[h] ?: 0) }
    }

    override suspend fun loadMyEvents(): MyEventsSnapshot {
        val events = myEvents.loadAcceptedEvents()
        if (events.isEmpty()) return MyEventsSnapshot(emptyList(), 0)
        val counts = eventChat.unreadCounts(events.map { it.address })
        return MyEventsSnapshot(events, counts.values.sum())
    }

    override suspend fun newsCounters(): NewsCounters {
        val unread = news.unreadCount()
        val latest = NewsArticles.newest(news.cachedArticles())?.title?.trim()
        return NewsCounters(unread, latest)
    }

    override suspend fun publishLatestNews() {
        val latest = news.fetchArticles(limit = 1).firstOrNull() ?: return
        widget.publishLatestNews(latest.id, latest.title)
    }

    override suspend fun eventsToday(): Int {
        val zone = zones.zone()
        val today: LocalDate = Instant.ofEpochMilli(time.nowMillis()).atZone(zone).toLocalDate()
        val dates = calendar.fetchPortalFirst()
        val events = calendarEvents.fetchEvents()
        val meetupCount = dates.count { it.start.atZone(zone).toLocalDate() == today }
        val nostrCount = events.count { it.start.atZone(zone).toLocalDate() == today }
        return meetupCount + nostrCount
    }

    override val dashboard: StateFlow<BitcoinDashboardData?> get() = mempool.lastDashboard
    override val currency: StateFlow<String> get() = currencyRepository.current

    override suspend fun refreshDashboard() {
        val d = mempool.getDashboardData()
        widgetBridge.publish(d)
    }

    override suspend fun duellStatus(): DuellStatus = duell.fetchStatus()

    override val plebrapIndex: StateFlow<Int?> get() = plebrap.index
    override val plebrapLoading: StateFlow<Boolean> get() = plebrap.loading
    override val plebrapPlaying: StateFlow<Boolean> get() = plebrap.isPlaying
    override suspend fun plebrapToggle() = plebrap.toggle()
    override suspend fun plebrapNext() = plebrap.next()

    override suspend fun nostrLastSeen(): Long = settings.getNostrLastSeen()
    override suspend fun setNostrLastSeen(seconds: Long) {
        settings.setNostrLastSeen(seconds)
    }

    override suspend fun localPicture(): String? = profileService.getExistingLocalPicture()

    override suspend fun profilePicture(npub: String): String? {
        val hex = runCatching { Nip19.decodeNpub(npub) }.getOrNull() ?: return null
        return profileService.fetchProfilePicture(hex)
    }

    override suspend fun importPicture(uri: String): String = pictureImporter.import(uri)
    override suspend fun npub(): String? = signing.npub()

    override suspend fun portalConnected(): Boolean = portal.tokenMatchesCurrentKey()
    override suspend fun portalLogin(): PortalResult = portal.loginWithNostr()
    override suspend fun portalLogout() = portal.logout()

    private companion object {
        const val TAG = "Dashboard"
        const val TAG_ADMIN = "Admin"
    }
}
