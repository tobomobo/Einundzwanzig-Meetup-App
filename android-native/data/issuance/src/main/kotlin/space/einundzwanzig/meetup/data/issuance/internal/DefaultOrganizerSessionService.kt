package space.einundzwanzig.meetup.data.issuance.internal

import kotlinx.coroutines.CancellationException
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.data.badges.security.MeetupKey
import space.einundzwanzig.meetup.data.issuance.session.OrganizerSessionResult
import space.einundzwanzig.meetup.data.issuance.session.OrganizerSessionService
import space.einundzwanzig.meetup.data.issuance.session.RollingQrService
import javax.inject.Inject
import javax.inject.Singleton

/** The session part of `admin_panel.dart` `_startMeetup` (spec 02 §2.8). */
@Singleton
internal class DefaultOrganizerSessionService @Inject constructor(
    private val rollingQr: RollingQrService,
    private val coAttendance: CoAttendancePort,
    private val time: TimeSource,
) : OrganizerSessionService {

    internal var log: DiagnosticLog = AppLogger

    override suspend fun startMeetupSession(
        meetupName: String,
        meetupCountry: String,
        lat: Double,
        lng: Double,
    ): OrganizerSessionResult {
        // Signed id: slug plus country, so older participant apps that split on the last '-' agree.
        val compactId = MeetupKey.compose(MeetupKey.slug(meetupName), meetupCountry)
        // Always a FRESH session with the current name/location.
        rollingQr.endSession()
        val session = rollingQr.getOrCreateSession(
            meetupId = compactId,
            meetupName = meetupName,
            meetupCountry = meetupCountry,
            blockHeight = 0,
            lat = lat,
            lng = lng,
        )
        // The organizer joins the co-attendance network (marker badge, no reputation badge).
        val marker = try {
            coAttendance.recordOrganizerAttendance(
                meetupName = meetupName,
                date = DartDateTime.now(time),
                blockHeight = session.blockHeight,
                lat = lat,
                lng = lng,
                isEvent = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "Organisator-Teilnahme nicht erfasst", e)
            null
        }
        return OrganizerSessionResult(session, marker)
    }

    private companion object {
        const val TAG = "RollingQR"
    }
}
