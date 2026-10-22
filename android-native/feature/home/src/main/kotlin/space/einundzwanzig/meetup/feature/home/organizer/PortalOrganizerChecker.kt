package space.einundzwanzig.meetup.feature.home.organizer

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.identity.profile.UserProfileRepository
import space.einundzwanzig.meetup.data.admin.claims.PromotionClaimService
import space.einundzwanzig.meetup.data.admin.registry.AdminRegistry
import space.einundzwanzig.meetup.data.meetups.portal.PortalApiService
import space.einundzwanzig.meetup.data.meetups.portal.StatusBody
import javax.inject.Inject

/** What the portal organizer check did (spec 12 §8.7). */
enum class OrganizerChange { GRANTED, REVOKED, UNCHANGED }

/** Result of [PortalOrganizerChecker.check]; the UI shows the hint/mismatch snackbars. */
data class OrganizerCheckResult(
    val change: OrganizerChange = OrganizerChange.UNCHANGED,
    /** Show `portalConnectForOrganizer` (once per screen instance, only for non-admins). */
    val showHint: Boolean = false,
    /** Show `portalTokenMismatch` (external signer with a foreign token). */
    val showTokenMismatch: Boolean = false,
    /** A silent login with the local key was attempted. */
    val loginAttempted: Boolean = false,
)

/** The calls the state machine needs; [DefaultOrganizerPorts] binds them to the data layer. */
interface OrganizerPorts {
    suspend fun hasToken(): Boolean
    suspend fun isExternalSigner(): Boolean
    suspend fun login(): Boolean
    suspend fun tokenMatchesCurrentKey(): Boolean
    suspend fun deleteToken()
    suspend fun myMeetups(): StatusBody
    suspend fun loadProfile(): UserProfile
    suspend fun updateProfile(transform: (UserProfile) -> UserProfile): UserProfile
    suspend fun badges(): List<MeetupBadge>
    suspend fun publishAdminClaim(badges: List<MeetupBadge>, meetupName: String)
    suspend fun ownNpub(): String?
    suspend fun removeFromAdminCache(npub: String)
}

/**
 * `_checkPortalOrganizer`, reproduced exactly (spec 12 §8.7). Security rules: never revoke on a
 * network error or non-200 answer; silent auto-login only with a local key; the vouch/seed admin
 * flags are untouched; on revocation the own admin-cache entry is removed.
 */
class PortalOrganizerChecker @Inject constructor(
    private val ports: OrganizerPorts,
    private val log: DiagnosticLog,
) {

    suspend fun check(): OrganizerCheckResult {
        return try {
            run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Dart: catch-all, silent.
            log.debug(TAG, "Organisator-Prüfung abgebrochen: $e")
            OrganizerCheckResult()
        }
    }

    private suspend fun run(): OrganizerCheckResult {
        log.diag(TAG, "Organisator-Prüfung gestartet")
        var loginAttempted = false
        if (!ports.hasToken()) {
            if (ports.isExternalSigner()) {
                log.warn(
                    TAG,
                    "Kein Token und ein externer Signer ist aktiv — Auto-Verbindung nicht moeglich, " +
                        "Organisator-Status bleibt unerkannt.",
                )
                return OrganizerCheckResult(showHint = true)
            }
            log.diag(TAG, "Kein Token — versuche automatische Verbindung (lokaler Schlüssel).")
            loginAttempted = true
            if (!ports.login()) {
                log.diag(TAG, "Automatische Verbindung fehlgeschlagen — übersprungen.")
                return OrganizerCheckResult(loginAttempted = true)
            }
        }
        if (!ports.tokenMatchesCurrentKey()) {
            if (ports.hasToken()) {
                log.warn(TAG, "Token gehört zu anderem Schlüssel — getrennt.")
                ports.deleteToken()
                if (!ports.isExternalSigner()) {
                    if (!ports.login()) {
                        log.warn(TAG, "Neuanmeldung nach Token-Wechsel fehlgeschlagen.")
                        return OrganizerCheckResult(showHint = true, loginAttempted = true)
                    }
                    loginAttempted = true
                } else {
                    return OrganizerCheckResult(showTokenMismatch = true, loginAttempted = loginAttempted)
                }
            } else {
                log.diag(TAG, "Kein Portal-Token vorhanden — übersprungen.")
                return OrganizerCheckResult(loginAttempted = loginAttempted)
            }
        }
        var res = ports.myMeetups()
        if (res.status == 401 || res.status == 403) {
            log.warn(TAG, "Token abgelehnt (HTTP ${res.status}) — wird erneuert.")
            ports.deleteToken()
            if (ports.isExternalSigner()) return OrganizerCheckResult(showHint = true, loginAttempted = loginAttempted)
            loginAttempted = true
            if (!ports.login()) {
                log.warn(TAG, "Erneuerung fehlgeschlagen — keine Änderung.")
                return OrganizerCheckResult(loginAttempted = true)
            }
            res = ports.myMeetups()
        }
        val body = res.body
        if (res.status != 200 || body == null || body is JsonNull) {
            log.diag(TAG, "/my-meetups: keine verwertbare Antwort (HTTP ${res.status}) — keine Änderung.")
            return OrganizerCheckResult(loginAttempted = loginAttempted)
        }
        val meetups = meetupsOf(body)
        log.diag(TAG, "/my-meetups lieferte ${meetups.size} Meetup(s) für aktuellen Schlüssel.")
        val user = ports.loadProfile()
        if (meetups.isNotEmpty() && !user.adminViaPortal) {
            ports.updateProfile { p -> p.copy(adminViaPortal = true).let { it.copy(isAdminVerified = it.isAdmin) } }
            val first = meetups.first()["name"]
            val name = (if (first == null || first is JsonNull) user.homeMeetupId else first.textValue())
                .ifEmpty { UNKNOWN_MEETUP }
            try {
                ports.publishAdminClaim(ports.badges(), name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug(TAG, "Organisator-Claim nicht veröffentlicht: $e")
            }
            log.info(TAG, "Organisator-Status bestaetigt (Portal).")
            return OrganizerCheckResult(OrganizerChange.GRANTED, loginAttempted = loginAttempted)
        }
        if (meetups.isEmpty() && user.adminViaPortal) {
            ports.updateProfile { p -> p.copy(adminViaPortal = false).let { it.copy(isAdminVerified = it.isAdmin) } }
            ports.ownNpub()?.let { ports.removeFromAdminCache(it) }
            return OrganizerCheckResult(OrganizerChange.REVOKED, loginAttempted = loginAttempted)
        }
        return OrganizerCheckResult(loginAttempted = loginAttempted)
    }

    companion object {
        private const val TAG = "Portal"

        /** Literal meetup name for claims without a name (wire-visible, keep byte-identical). */
        const val UNKNOWN_MEETUP = "Unbekannt"

        /** Body `{"data":[…]}` or `[…]` → the object entries. */
        fun meetupsOf(body: JsonElement): List<JsonObject> {
            val data = if (body is JsonObject) body["data"] else body
            return (data as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
        }

        private fun JsonElement.textValue(): String =
            if (this is JsonPrimitive) content else toString()
    }
}

/** Binds [OrganizerPorts] to the portal, identity, admin and badge services. */
internal class DefaultOrganizerPorts @Inject constructor(
    private val portal: PortalApiService,
    private val signing: SigningService,
    private val profiles: UserProfileRepository,
    private val badgeRepository: BadgeRepository,
    private val claims: PromotionClaimService,
    private val registry: AdminRegistry,
) : OrganizerPorts {
    override suspend fun hasToken() = portal.hasToken()
    override suspend fun isExternalSigner() = signing.isExternalSigner()
    override suspend fun login() = portal.loginWithNostr().ok
    override suspend fun tokenMatchesCurrentKey() = portal.tokenMatchesCurrentKey()
    override suspend fun deleteToken() {
        portal.deleteToken()
    }
    override suspend fun myMeetups() = portal.rawGetStatus("/my-meetups")
    override suspend fun loadProfile() = profiles.load()
    override suspend fun updateProfile(transform: (UserProfile) -> UserProfile) = profiles.update(transform)
    override suspend fun badges(): List<MeetupBadge> = badgeRepository.badges.value
    override suspend fun publishAdminClaim(badges: List<MeetupBadge>, meetupName: String) {
        claims.publishAdminClaim(badges, meetupName)
    }
    override suspend fun ownNpub() = signing.npub()
    override suspend fun removeFromAdminCache(npub: String) = registry.removeFromCache(npub)
}
