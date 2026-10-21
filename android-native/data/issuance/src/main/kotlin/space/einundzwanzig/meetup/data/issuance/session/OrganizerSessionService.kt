package space.einundzwanzig.meetup.data.issuance.session

import space.einundzwanzig.meetup.data.badges.model.MeetupBadge

/**
 * The organizer's "MEETUP STARTEN" flow of the admin panel after the meetup was chosen
 * (spec 02 §2.8, first bullet): ends any running session, starts a new one for the signed id
 * `MeetupKey.compose(MeetupKey.slug(cityOrFreeText), country)` and records the organizer's own
 * attendance (the organizer marker badge `org-<slug>-<date>` + kind-30079 organizer publish,
 * awaited like in Flutter).
 */
interface OrganizerSessionService {

    /**
     * @param meetupName the city of the chosen meetup or the organizer's free text (display name
     *   and slug source).
     * @param meetupCountry ISO code of the meetup (`""` for free text).
     * @param lat measured organizer GPS or 0 — portal coordinates are never passed (Dart).
     * @throws SessionStartException as [RollingQrService.getOrCreateSession].
     */
    suspend fun startMeetupSession(
        meetupName: String,
        meetupCountry: String,
        lat: Double = 0.0,
        lng: Double = 0.0,
    ): OrganizerSessionResult
}

/**
 * @property marker the stored organizer marker badge, or `null` if it could not be stored or
 *   published (the session still runs; Flutter ignored this result).
 */
data class OrganizerSessionResult(val session: MeetupSession, val marker: MeetupBadge?)
