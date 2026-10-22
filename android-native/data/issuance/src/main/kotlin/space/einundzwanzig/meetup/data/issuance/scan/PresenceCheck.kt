package space.einundzwanzig.meetup.data.issuance.scan

import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.meetups.location.Geo
import space.einundzwanzig.meetup.data.meetups.model.Meetup

/** Where a presence reference comes from (spec 02 §2.1 `_confirmSaveBadge` step 4). */
enum class ReferenceSource {
    /** Signed organizer coordinates (measured on site). Unused today: nothing is signed (H1). */
    SIGNED,

    /** The coordinates of the meetup the scanner was opened for (radar city detail; Dart `widget.meetup`). */
    OPENED_FOR_MEETUP,

    /** Resolved from the badge's meetup name in the directory. */
    DIRECTORY,
}

/**
 * A presence reference point.
 *
 * @property measured `true` only for [ReferenceSource.SIGNED]: strict 5 km instead of 50 km.
 */
data class PresenceReference(
    val lat: Double,
    val lng: Double,
    val source: ReferenceSource,
) {
    /** A measured reference (the organizer stood there). */
    val measured: Boolean get() = source == ReferenceSource.SIGNED

    /** The radius that applies to this reference. */
    val allowedKm: Double get() = if (measured) PresenceCheck.PARTICIPANT_RADIUS_KM else PresenceCheck.DERIVED_RADIUS_KM
}

/** Outcome of [PresenceCheck.evaluate]. */
sealed interface PresenceResult {
    /** Measurable and within the radius: `presenceVerified = true`. */
    data class Verified(val distanceKm: Double, val allowedKm: Double) : PresenceResult

    /** Measurable and too far: the badge is refused (counter-evidence). */
    data class TooFar(val distanceKm: Double, val allowedKm: Double) : PresenceResult

    /** No fix or no reference: the badge is stored with `presenceVerified = false` (no evidence). */
    data class Unverified(val reason: Reason) : PresenceResult {
        /** Why the check could not run. */
        enum class Reason { NO_LOCATION, NO_REFERENCE }
    }
}

/**
 * The soft presence check of the badge scanner (spec 02 §2.1 steps 4–6, §8.6, §9 item 17):
 * "no evidence" is not "counter-evidence". Pure logic; distances use geolocator's haversine
 * (`Geo`, R = 6378137 m).
 */
object PresenceCheck {
    /** Participant ↔ measured (signed) reference. */
    const val PARTICIPANT_RADIUS_KM: Double = 5.0

    /** Participant ↔ derived reference (portal coordinates; regions can be wide). */
    const val DERIVED_RADIUS_KM: Double = 50.0

    /**
     * Picks the reference in Dart's order: signed coordinates, then the meetup the scanner was
     * opened for, then [directoryLookup] (called only when needed). `null` if none has coordinates.
     *
     * @param openedForMeetupLat/openedForMeetupLng Dart `widget.meetup.lat/lng` (0/0 = none),
     *   never the device position.
     */
    suspend fun reference(
        signedLat: Double,
        signedLng: Double,
        openedForMeetupLat: Double,
        openedForMeetupLng: Double,
        directoryLookup: suspend () -> Meetup?,
    ): PresenceReference? {
        if (!(signedLat == 0.0 && signedLng == 0.0)) return PresenceReference(signedLat, signedLng, ReferenceSource.SIGNED)
        if (!(openedForMeetupLat == 0.0 && openedForMeetupLng == 0.0)) {
            return PresenceReference(openedForMeetupLat, openedForMeetupLng, ReferenceSource.OPENED_FOR_MEETUP)
        }
        val m = directoryLookup() ?: return null
        if (m.lat == 0.0 && m.lng == 0.0) return null
        return PresenceReference(m.lat, m.lng, ReferenceSource.DIRECTORY)
    }

    /**
     * Dart `_lookupMeetupForBadge`: the city is the part of the badge name before the first comma
     * (Dart-trimmed and lower-cased); the first meetup with coordinates whose city equals,
     * contains or is contained in it wins.
     */
    fun findReferenceMeetup(badgeMeetupName: String, meetups: List<Meetup>): Meetup? {
        val city = DartString.lower(DartString.trim(badgeMeetupName.split(',').first()))
        if (city.isEmpty()) return null
        for (m in meetups) {
            if (m.lat == 0.0 && m.lng == 0.0) continue
            val c = DartString.lower(DartString.trim(m.city))
            if (c == city || c.contains(city) || city.contains(c)) return m
        }
        return null
    }

    /**
     * @param locationOk the participant's fix is available ([lat]/[lng]).
     * @param reference from [reference]; `null` = none.
     */
    fun evaluate(locationOk: Boolean, lat: Double, lng: Double, reference: PresenceReference?): PresenceResult {
        if (!locationOk) return PresenceResult.Unverified(PresenceResult.Unverified.Reason.NO_LOCATION)
        if (reference == null) return PresenceResult.Unverified(PresenceResult.Unverified.Reason.NO_REFERENCE)
        val distance = Geo.distanceKm(lat, lng, reference.lat, reference.lng)
        val allowed = reference.allowedKm
        return if (distance > allowed) PresenceResult.TooFar(distance, allowed) else PresenceResult.Verified(distance, allowed)
    }
}
