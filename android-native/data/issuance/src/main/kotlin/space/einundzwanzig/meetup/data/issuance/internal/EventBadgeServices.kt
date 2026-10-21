package space.einundzwanzig.meetup.data.issuance.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.ApplicationScope
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.dart.toStringAsFixed
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.data.badges.security.BadgeSecurity
import space.einundzwanzig.meetup.data.calendar.model.NostrCalendarEvent
import space.einundzwanzig.meetup.data.issuance.event.EventBadgeAuthService
import space.einundzwanzig.meetup.data.issuance.event.EventBadgeChainService
import space.einundzwanzig.meetup.data.issuance.event.EventBadgeRight
import space.einundzwanzig.meetup.data.issuance.event.EventBadgeSessionService
import space.einundzwanzig.meetup.data.issuance.event.EventChainResult
import space.einundzwanzig.meetup.data.issuance.event.EventChainStatus
import space.einundzwanzig.meetup.data.issuance.event.EventSessionError
import space.einundzwanzig.meetup.data.issuance.event.EventSessionResult
import space.einundzwanzig.meetup.data.issuance.session.RollingQrService
import space.einundzwanzig.meetup.data.meetups.location.Geo
import space.einundzwanzig.meetup.data.meetups.location.LocationStatus
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Port of `lib/services/event_badge_chain_service.dart`. */
@Singleton
internal class DefaultEventBadgeChainService @Inject constructor(
    private val calendar: CalendarPort,
    private val admins: AdminPort,
) : EventBadgeChainService {

    internal var log: DiagnosticLog = AppLogger

    override suspend fun verify(eventAddress: String, signerPubkey: String, issuedAt: Long?): EventChainResult {
        val event = try {
            calendar.fetchByAddress(eventAddress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "Event $eventAddress nicht abrufbar", e)
            null
        }
        if (event == null) {
            log.warn(TAG, "Event $eventAddress nicht auffindbar.")
            return EventChainResult(EventChainStatus.EVENT_NOT_FOUND)
        }
        fun result(status: EventChainStatus, creatorName: String? = null) = EventChainResult(
            status = status,
            eventTitle = event.title,
            creatorPubkey = event.pubkey,
            creatorName = creatorName,
            badgeImageUrl = event.badgeImageUrl,
        )
        if (!event.badgeEnabled) {
            log.warn(TAG, "Event \"${event.title}\" vergibt gar keine Badges.")
            return result(EventChainStatus.EVENT_HAS_NO_BADGE)
        }
        // Issuer list first (already loaded), then the creator (may cost a registry fetch).
        if (!event.isIssuer(signerPubkey, atEpoch = issuedAt)) {
            log.warn(TAG, "Signierer ${signerPubkey.take(8)}… steht nicht in der Ausstellerliste von \"${event.title}\".")
            return result(EventChainStatus.SIGNER_NOT_LISTED)
        }
        // Only the admin registry can vouch for a FOREIGN creator (portal leaders only answer "am I").
        return try {
            val admin = admins.checkAdminByPubkey(event.pubkey)
            if (!admin.isAdmin) {
                log.warn(TAG, "Ersteller ${event.pubkey.take(8)}… ist kein eingetragener Organisator.")
                result(EventChainStatus.CREATOR_NOT_AUTHORIZED)
            } else {
                log.debug(TAG, "Kette vollstaendig fuer \"${event.title}\" (Ersteller ${admin.name ?: admin.meetup ?: "?"}).")
                result(EventChainStatus.VERIFIED, creatorName = admin.name ?: admin.meetup)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Registry unreachable: unverified, reported honestly instead of waved through.
            log.warn(TAG, "Admin-Registry nicht abfragbar", e)
            result(EventChainStatus.CREATOR_NOT_AUTHORIZED)
        }
    }

    private companion object {
        const val TAG = "EventChain"
    }
}

/** Port of `lib/services/event_badge_auth_service.dart`. */
@Singleton
internal class DefaultEventBadgeAuthService @Inject constructor(
    private val identity: IdentityPort,
    private val admins: AdminPort,
    private val directory: MeetupDirectoryPort,
    private val profile: ProfileAdminPort,
) : EventBadgeAuthService {

    internal var log: DiagnosticLog = AppLogger

    override suspend fun myRight(): EventBadgeRight {
        val npub = try {
            identity.npub()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug(TAG, "Identitaet nicht lesbar: ${e.message}")
            null
        }
        if (npub.isNullOrEmpty()) {
            log.debug(TAG, "Keine Identitaet — keine Berechtigung.")
            return EventBadgeRight.NONE
        }
        try {
            if (admins.checkAdmin(npub).isAdmin) {
                log.debug(TAG, "Berechtigt ueber Admin-Registry.")
                return EventBadgeRight.ADMIN_REGISTRY
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug(TAG, "Admin-Registry nicht abfragbar: ${e.message}")
        }
        try {
            // Membership of any portal meetup suffices (an event belongs to no meetup).
            val count = directory.myPortalMeetupCount()
            if (count > 0) {
                log.debug(TAG, "Berechtigt ueber Portal ($count Meetup(s)).")
                return EventBadgeRight.PORTAL_LEADER
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug(TAG, "Portal nicht abfragbar: ${e.message}")
        }
        try {
            // Weakest source last: the status the app determined before (portal or trust score).
            if (profile.isAdmin()) {
                log.debug(TAG, "Berechtigt ueber gespeicherten Status.")
                return EventBadgeRight.ADMIN_REGISTRY
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug(TAG, "Nutzerprofil nicht lesbar: ${e.message}")
        }
        log.debug(TAG, "Keine Berechtigung gefunden.")
        return EventBadgeRight.NONE
    }

    private companion object {
        const val TAG = "EventBadge"
    }
}

/** Port of `lib/services/event_badge_session_service.dart`. */
@Singleton
internal class DefaultEventBadgeSessionService @Inject constructor(
    private val identity: IdentityPort,
    private val location: LocationPort,
    private val rollingQr: RollingQrService,
    private val coAttendance: CoAttendancePort,
    private val time: TimeSource,
    private val zones: ZoneSource,
    @ApplicationScope private val appScope: CoroutineScope,
) : EventBadgeSessionService {

    internal var log: DiagnosticLog = AppLogger

    override suspend fun start(event: NostrCalendarEvent): EventSessionResult {
        val myPubkey = try {
            identity.pubkeyHex()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "Identitaet nicht lesbar", e)
            return failure(EventSessionError.SESSION_FAILED)
        }
        if (myPubkey.isNullOrEmpty()) return failure(EventSessionError.NO_IDENTITY)

        // No atEpoch: former issuers cannot start a session.
        if (!event.isIssuer(myPubkey)) {
            log.warn(TAG, "Nicht als Aussteller eingetragen.")
            return failure(EventSessionError.NOT_ISSUER)
        }
        val zone = zones.zone()
        if (!event.isBadgeWindowOpen(Instant.ofEpochMilli(time.nowMillis()), zone)) {
            log.debug(TAG, "Ausserhalb des Zeitfensters (${event.start}).")
            return failure(EventSessionError.OUTSIDE_WINDOW)
        }
        if (event.lat == 0.0 && event.lng == 0.0) return failure(EventSessionError.NO_EVENT_LOCATION)

        // The fix itself, not resolveLocation(): no meetup list needed, and the exact reason matters.
        val loc = location.getCurrentLocation()
        val pos = loc.position
        if (loc.status != LocationStatus.OK || pos == null) {
            val error = when (loc.status) {
                LocationStatus.SERVICE_DISABLED -> EventSessionError.LOCATION_SERVICE_OFF
                LocationStatus.DENIED -> EventSessionError.LOCATION_DENIED
                LocationStatus.DENIED_FOREVER -> EventSessionError.LOCATION_DENIED_FOREVER
                LocationStatus.NO_FIX -> EventSessionError.LOCATION_NO_FIX
                else -> EventSessionError.LOCATION_UNAVAILABLE
            }
            log.warn(TAG, "Standort nicht verfuegbar: ${loc.status}")
            return failure(error)
        }
        val distance = Geo.distanceKm(pos.latitude, pos.longitude, event.lat, event.lng)
        if (distance > EventBadgeSessionService.ISSUER_RADIUS_KM) {
            log.warn(
                TAG,
                "Zu weit weg: ${distance.toStringAsFixed(1)} km (erlaubt ${EventBadgeSessionService.ISSUER_RADIUS_KM} km).",
            )
            return EventSessionResult(error = EventSessionError.TOO_FAR_AWAY, distanceKm = distance)
        }

        return try {
            val session = rollingQr.getOrCreateSession(
                // The event address lives in the signed m: the scanner's trust chain hangs on it.
                meetupId = BadgeSecurity.eventMeetupId(event.address, event.title),
                meetupName = event.title,
                meetupCountry = "",
                blockHeight = 0,
                // The VENUE, not where the helper happened to stand.
                lat = event.lat,
                lng = event.lng,
                validUntilEpoch = endOfEventDay(event),
            )
            // The helper attended too; recorded in the background so the QR does not wait.
            appScope.launch {
                try {
                    coAttendance.recordOrganizerAttendance(
                        meetupName = event.title,
                        date = DartDateTime.now(time),
                        blockHeight = session.blockHeight,
                        lat = event.lat,
                        lng = event.lng,
                        isEvent = true,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(TAG, "Organisator-Teilnahme nicht erfasst", e)
                }
            }
            log.debug(TAG, "Session fuer \"${event.title}\" gestartet, ${distance.toStringAsFixed(2)} km entfernt.")
            EventSessionResult(session = session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "Session konnte nicht erstellt werden", e)
            failure(EventSessionError.SESSION_FAILED)
        }
    }

    /**
     * Local midnight after the last event day (Dart `_endOfEventDay`). Computed on dates, so a
     * DST change on that day does not shift it by an hour (same rule as the badge window).
     */
    private fun endOfEventDay(event: NostrCalendarEvent): Long {
        val zone = zones.zone()
        return event.lastDay(zone).plusDays(1).atStartOfDay(zone).toEpochSecond()
    }

    private fun failure(error: EventSessionError) = EventSessionResult(error = error)

    private companion object {
        const val TAG = "EventSession"
    }
}
