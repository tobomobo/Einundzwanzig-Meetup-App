package space.einundzwanzig.meetup.data.issuance.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import space.einundzwanzig.meetup.core.common.dart.toStringAsFixed
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.data.badges.model.BadgeIdentity
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.issuance.scan.BadgeSaveUseCase
import space.einundzwanzig.meetup.data.issuance.scan.BadgeSavedEvent
import space.einundzwanzig.meetup.data.issuance.scan.PendingBadge
import space.einundzwanzig.meetup.data.issuance.scan.PresenceCheck
import space.einundzwanzig.meetup.data.issuance.scan.PresenceResult
import space.einundzwanzig.meetup.data.issuance.scan.SaveOutcome
import space.einundzwanzig.meetup.data.meetups.location.GpsStatus
import space.einundzwanzig.meetup.data.meetups.location.LocationMeetupResult
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import javax.inject.Inject
import javax.inject.Singleton

/** Port of `MeetupVerificationScreen._confirmSaveBadge` without UI (spec 02 §2.1). */
@Singleton
internal class DefaultBadgeSaveUseCase @Inject constructor(
    private val repository: BadgeRepository,
    private val location: LocationPort,
    private val directory: MeetupDirectoryPort,
    private val coAttendance: CoAttendancePort,
) : BadgeSaveUseCase {

    internal var log: DiagnosticLog = AppLogger

    private val saved = MutableSharedFlow<BadgeSavedEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val badgeSaved: SharedFlow<BadgeSavedEvent> = saved.asSharedFlow()

    override suspend fun confirm(
        pending: PendingBadge,
        openedForMeetupLat: Double,
        openedForMeetupLng: Double,
    ): SaveOutcome {
        val initial = pending.badge
        log.diag(
            TAG,
            "Claim gestartet: \"${initial.meetupName}\" · Zustellung=${initial.delivery} " +
                "· sigVersion=${initial.sigVersion} · Key=${BadgeIdentity.identityKey(initial)}",
        )

        // Soft check: unmeasurable → unverified badge; measurable and too far → refused.
        val loc = try {
            location.resolveLocation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "Standortbestimmung fehlgeschlagen", e)
            LocationMeetupResult(GpsStatus.ERROR)
        }
        val locationOk = loc.status == GpsStatus.OK
        if (!locationOk) {
            log.warn(TAG, "Standort nicht ermittelbar (${loc.status}) — Badge wird als UNGEPRUEFTE Praesenz vergeben.")
        }
        val reference = PresenceCheck.reference(
            signedLat = pending.orgLat,
            signedLng = pending.orgLng,
            openedForMeetupLat = openedForMeetupLat,
            openedForMeetupLng = openedForMeetupLng,
        ) { PresenceCheck.findReferenceMeetup(initial.meetupName, directoryMeetups()) }

        val presenceVerified = when (val check = PresenceCheck.evaluate(locationOk, loc.lat, loc.lng, reference)) {
            is PresenceResult.TooFar -> {
                log.warn(
                    TAG,
                    "Zu weit entfernt: ${check.distanceKm.toStringAsFixed(1)} km > ${check.allowedKm} km " +
                        "(Referenz ${if (reference?.measured == true) "gemessen" else "abgeleitet aus \"${initial.meetupName}\""}) — Badge abgelehnt.",
                )
                return SaveOutcome.TooFar(check.distanceKm, check.allowedKm)
            }
            is PresenceResult.Verified -> {
                log.diag(TAG, "Umkreis-Pruefung: ${check.distanceKm.toStringAsFixed(1)} km (erlaubt ${check.allowedKm} km).")
                true
            }
            is PresenceResult.Unverified -> {
                log.warn(TAG, "Umkreis-Pruefung UEBERSPRUNGEN (${check.reason}) — Badge gilt als UNGEPRUEFTE Praesenz.")
                false
            }
        }

        // The participant's own position (0/0 without a fix): presence proof + world map.
        val badge = initial.copyWith(lat = loc.lat, lng = loc.lng, presenceVerified = presenceVerified)
        val key = BadgeIdentity.identityKey(badge)

        // Binding duplicate guard against the STORED wallet, atomically with the save.
        var duplicate = false
        val stored = repository.update { current ->
            if (current.any { !it.isOrganizer && BadgeIdentity.identityKey(it) == key }) {
                duplicate = true
                null
            } else {
                current + badge
            }
        }
        if (stored && duplicate) {
            log.warn(TAG, "Duplikat abgewiesen ($key) — Badge bereits in der Wallet.")
            return SaveOutcome.Duplicate(badge, presenceVerified)
        }
        if (!stored) {
            // Unreadable wallet or failed save: nothing is reported as saved or published.
            val aside = repository.keepAside(badge)
            log.warn(TAG, "Badge nicht gespeichert — ${if (aside) "vorgemerkt" else "auch Vormerkung fehlgeschlagen"}.")
            return if (aside) {
                SaveOutcome.KeptAside(badge, presenceVerified)
            } else {
                SaveOutcome.SaveFailed(pending.copy(badge = badge), presenceVerified)
            }
        }

        val collection = repository.badges.value
        log.diag(TAG, "Badge gespeichert ($key). Wallet: ${collection.size} Badge(s).")
        saved.tryEmit(BadgeSavedEvent(badge, collection))
        return SaveOutcome.Saved(
            badge = badge,
            presenceVerified = presenceVerified,
            askCoAttendance = badge.isNostrSigned && badge.meetupEventId.isNotEmpty(),
            collection = collection,
        )
    }

    override suspend fun publishCoAttendance(badge: MeetupBadge): Int = try {
        coAttendance.publishAttendance(badge)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(TAG, "Co-Attendance-Veroeffentlichung fehlgeschlagen", e)
        0
    }

    /** Directory for the reference lookup: portal list, else the bundled fallback (Dart `allMeetups`). */
    private suspend fun directoryMeetups(): List<Meetup> {
        val list = try {
            directory.fetchMeetups()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        return list.ifEmpty { directory.fallbackMeetups }
    }

    private companion object {
        const val TAG = "Scan"
    }
}
