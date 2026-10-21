package space.einundzwanzig.meetup.data.issuance.internal

import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.crypto.CryptoRandom
import space.einundzwanzig.meetup.data.admin.model.AdminCheckResult
import space.einundzwanzig.meetup.data.admin.registry.AdminRegistry
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.calendar.CalendarEventService
import space.einundzwanzig.meetup.data.calendar.model.NostrCalendarEvent
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.meetups.MeetupService
import space.einundzwanzig.meetup.data.meetups.location.LocationMeetupResult
import space.einundzwanzig.meetup.data.meetups.location.LocationResult
import space.einundzwanzig.meetup.data.meetups.location.MeetupLocationService
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.data.meetups.portal.PortalApiService
import space.einundzwanzig.meetup.data.meetups.portal.PortalLeaderService
import space.einundzwanzig.meetup.data.mempool.MempoolService
import space.einundzwanzig.meetup.data.socialgraph.coattendance.CoAttendanceRepository
import java.time.ZoneId
import javax.inject.Inject

// Narrow ports onto the services of other modules. They keep the issuance logic testable with
// small fakes and document exactly which calls this domain makes (spec 02 §12).

/** The active identity (Dart `SigningService.npub()` / `pubkeyHex()`). */
internal interface IdentityPort {
    suspend fun npub(): String?
    suspend fun pubkeyHex(): String?
}

/** Dart `MempoolService.getBlockHeight()`: 0 = unknown, never throws. */
internal fun interface BlockHeightPort {
    suspend fun blockHeight(): Long
}

/** Organizer registry lookups. */
internal interface AdminPort {
    suspend fun checkAdmin(npub: String): AdminCheckResult
    suspend fun checkAdminByPubkey(pubkeyHex: String): AdminCheckResult
}

/** Meetup directory, portal leaders and the user's own portal meetups. */
internal interface MeetupDirectoryPort {
    fun bySlug(slug: String, country: String): Meetup?
    fun byName(name: String): Meetup?
    fun resolveFavorite(stored: String): Meetup?
    suspend fun organizerMeetupFor(pubkeyHexOrNpub: String): Meetup?
    suspend fun fetchMeetups(): List<Meetup>
    val fallbackMeetups: List<Meetup>
    suspend fun isLeaderOf(meetupId: Int, npub: String): Boolean?

    /** Number of portal meetups the user belongs to (Dart `getMyMeetups().length`). */
    suspend fun myPortalMeetupCount(): Int
}

/** Location acquisition. */
internal interface LocationPort {
    suspend fun getCurrentLocation(): LocationResult
    suspend fun resolveLocation(): LocationMeetupResult
}

/** Calendar lookup. */
internal fun interface CalendarPort {
    suspend fun fetchByAddress(address: String): NostrCalendarEvent?
}

/** Co-attendance (organizer marker and opt-in publish). */
internal interface CoAttendancePort {
    suspend fun publishAttendance(badge: MeetupBadge): Int
    suspend fun recordOrganizerAttendance(
        meetupName: String,
        date: DartDateTime,
        blockHeight: Long,
        lat: Double,
        lng: Double,
        isEvent: Boolean,
    ): MeetupBadge?
}

/** The stored admin flag of the user profile (Dart `UserProfile.load().isAdmin`). */
internal fun interface ProfileAdminPort {
    suspend fun isAdmin(): Boolean
}

/** The device time zone (Dart local `DateTime`). */
internal fun interface ZoneSource {
    fun zone(): ZoneId
}

/** 32 CSPRNG bytes as lowercase hex (Dart `_generateSecureSeed`). */
internal fun interface SeedSource {
    fun newSeed(): String
}

internal class SigningIdentityPort @Inject constructor(private val signing: SigningService) : IdentityPort {
    override suspend fun npub(): String? = signing.npub()
    override suspend fun pubkeyHex(): String? = signing.pubkeyHex()
}

internal class MempoolBlockHeightPort @Inject constructor(private val mempool: MempoolService) : BlockHeightPort {
    override suspend fun blockHeight(): Long = mempool.getBlockHeight().toLong()
}

internal class RegistryAdminPort @Inject constructor(private val registry: AdminRegistry) : AdminPort {
    override suspend fun checkAdmin(npub: String): AdminCheckResult = registry.checkAdmin(npub)
    override suspend fun checkAdminByPubkey(pubkeyHex: String): AdminCheckResult = registry.checkAdminByPubkey(pubkeyHex)
}

internal class ServicesMeetupDirectoryPort @Inject constructor(
    private val meetups: MeetupService,
    private val leaders: PortalLeaderService,
    private val portal: PortalApiService,
) : MeetupDirectoryPort {
    override fun bySlug(slug: String, country: String): Meetup? = meetups.bySlug(slug, country)
    override fun byName(name: String): Meetup? = meetups.byName(name)
    override fun resolveFavorite(stored: String): Meetup? = meetups.resolveFavorite(stored)
    override suspend fun organizerMeetupFor(pubkeyHexOrNpub: String): Meetup? = meetups.organizerMeetupFor(pubkeyHexOrNpub)
    override suspend fun fetchMeetups(): List<Meetup> = meetups.fetchMeetups()
    override val fallbackMeetups: List<Meetup> get() = meetups.fallbackMeetups
    override suspend fun isLeaderOf(meetupId: Int, npub: String): Boolean? = leaders.isLeaderOf(meetupId, npub)
    override suspend fun myPortalMeetupCount(): Int = portal.getMyMeetups().size
}

internal class ServiceLocationPort @Inject constructor(private val location: MeetupLocationService) : LocationPort {
    override suspend fun getCurrentLocation(): LocationResult = location.getCurrentLocation()
    override suspend fun resolveLocation(): LocationMeetupResult = location.resolveLocation()
}

internal class ServiceCalendarPort @Inject constructor(private val calendar: CalendarEventService) : CalendarPort {
    override suspend fun fetchByAddress(address: String): NostrCalendarEvent? = calendar.fetchByAddress(address)
}

internal class RepositoryCoAttendancePort @Inject constructor(
    private val coAttendance: CoAttendanceRepository,
) : CoAttendancePort {
    override suspend fun publishAttendance(badge: MeetupBadge): Int = coAttendance.publishAttendance(badge)
    override suspend fun recordOrganizerAttendance(
        meetupName: String,
        date: DartDateTime,
        blockHeight: Long,
        lat: Double,
        lng: Double,
        isEvent: Boolean,
    ): MeetupBadge? = coAttendance.recordOrganizerAttendance(meetupName, date, blockHeight, lat, lng, isEvent)
}

internal class RepositoryProfileAdminPort @Inject constructor(
    private val profiles: UserProfileRepository,
) : ProfileAdminPort {
    override suspend fun isAdmin(): Boolean = profiles.load().isAdmin
}

internal class SystemZoneSource @Inject constructor() : ZoneSource {
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

internal class CryptoSeedSource @Inject constructor() : SeedSource {
    override fun newSeed(): String = CryptoRandom.hex(32)
}
