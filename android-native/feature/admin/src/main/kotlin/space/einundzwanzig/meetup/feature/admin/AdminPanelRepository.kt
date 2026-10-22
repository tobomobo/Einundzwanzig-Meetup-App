package space.einundzwanzig.meetup.feature.admin

import kotlinx.coroutines.CancellationException
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.data.admin.AdminConstants
import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.issuance.session.MeetupSession
import space.einundzwanzig.meetup.data.issuance.session.OrganizerSessionService
import space.einundzwanzig.meetup.data.issuance.session.RollingQrService
import space.einundzwanzig.meetup.data.meetups.MeetupService
import space.einundzwanzig.meetup.data.meetups.location.LocationMeetupResult
import space.einundzwanzig.meetup.data.meetups.location.MeetupLocationService
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.data.meetups.portal.PortalApiService
import javax.inject.Inject

/** Who is looking at the panel (Dart `_loadAdminInfo`). */
data class AdminInfo(
    val npub: String,
    val promotionSource: String,
    val isSuperAdmin: Boolean,
)

/**
 * The thin seam between the admin panel and the data layer. Every method delegates to a data
 * module service; nothing here re-implements domain logic. Exists so the ViewModel can be tested
 * with a small fake instead of the large service interfaces.
 */
interface AdminPanelRepository {
    /** Own npub (`NostrService.getNpub`) and `UserProfile.promotionSource`. */
    suspend fun loadAdminInfo(): AdminInfo

    /** `RollingQRService.loadSession` (ends and returns `null` when expired). */
    suspend fun loadSession(): MeetupSession?

    /** `RollingQRService.endSession`. */
    suspend fun endSession()

    /** `MeetupLocationService.resolveLocation`. */
    suspend fun resolveLocation(): LocationMeetupResult

    /**
     * The portal directory for the picker: `MeetupService.fetchMeetups()`, empty or failed →
     * the bundled fallback list (Dart `allMeetups`).
     */
    suspend fun portalMeetups(): List<Meetup>

    /** `PortalApiService.getMyMeetups()` names, `trim().lowercase()`; failures → empty. */
    suspend fun myMeetupNames(): Set<String>

    /** `OrganizerSessionService.startMeetupSession` (ends the old session first). */
    suspend fun startSession(meetupName: String, meetupCountry: String, lat: Double, lng: Double): MeetupSession

    fun openLocationSettings()

    fun openAppSettings()
}

internal class DefaultAdminPanelRepository @Inject constructor(
    private val signing: SigningService,
    private val profiles: UserProfileRepository,
    private val rollingQr: RollingQrService,
    private val organizer: OrganizerSessionService,
    private val location: MeetupLocationService,
    private val meetups: MeetupService,
    private val portal: PortalApiService,
) : AdminPanelRepository {

    override suspend fun loadAdminInfo(): AdminInfo {
        val profile = guard("Profil nicht ladbar") { profiles.load() }
        val npub = guard("npub nicht ladbar") { signing.npub() }.orEmpty()
            .ifEmpty { profile?.nostrNpub.orEmpty() }
        return AdminInfo(
            npub = npub,
            promotionSource = profile?.promotionSource.orEmpty(),
            isSuperAdmin = npub.isNotEmpty() && npub == AdminConstants.SUPER_ADMIN_NPUB,
        )
    }

    override suspend fun loadSession(): MeetupSession? = rollingQr.loadSession()

    override suspend fun endSession() = rollingQr.endSession()

    override suspend fun resolveLocation(): LocationMeetupResult = location.resolveLocation()

    override suspend fun portalMeetups(): List<Meetup> {
        val all = guard("Portal-Meetupliste nicht ladbar") { meetups.fetchMeetups() }.orEmpty()
        return all.ifEmpty { meetups.fallbackMeetups }
    }

    override suspend fun myMeetupNames(): Set<String> =
        guard("Eigene Meetups nicht ladbar") {
            portal.getMyMeetups().map { DartString.lower(DartString.trim(it.name)) }.toSet()
        }.orEmpty()

    override suspend fun startSession(
        meetupName: String,
        meetupCountry: String,
        lat: Double,
        lng: Double,
    ): MeetupSession = organizer.startMeetupSession(meetupName, meetupCountry, lat, lng).session

    override fun openLocationSettings() {
        location.openLocationSettings()
    }

    override fun openAppSettings() {
        location.openAppSettings()
    }

    private inline fun <T> guard(message: String, block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLogger.warn(TAG, message, e)
        null
    }

    private companion object {
        const val TAG = "Organisator"
    }
}
