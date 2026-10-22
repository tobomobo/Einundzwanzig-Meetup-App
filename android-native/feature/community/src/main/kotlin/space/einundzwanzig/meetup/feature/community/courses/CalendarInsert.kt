package space.einundzwanzig.meetup.feature.community.courses

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.data.community.portal.PortalCourseEvent
import space.einundzwanzig.meetup.data.community.portal.PortalFields

/** Two hours, the event length Flutter passes to `Add2Calendar` (spec 11 §2.8). */
internal const val COURSE_EVENT_DURATION_MS: Long = 2 * 60 * 60 * 1000L

/**
 * `Add2Calendar.addEvent2Cal`: the system "insert event" screen, prefilled with
 * `title || loc || "Termin"`, description, location, start and start + 2 h. Returns null when
 * the event has no parsable start (the button is hidden then).
 */
internal fun calendarInsertIntent(event: PortalCourseEvent): Intent? {
    val start = event.start ?: return null
    val begin = start.millisecondsSinceEpoch
    return Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, PortalFields.calendarTitle(event))
        .putExtra(CalendarContract.Events.DESCRIPTION, event.description)
        .putExtra(CalendarContract.Events.EVENT_LOCATION, event.location)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + COURSE_EVENT_DURATION_MS)
}

/** Starts [calendarInsertIntent]; failures are logged and otherwise ignored (Flutter: no feedback). */
internal fun Context.insertCourseEvent(event: PortalCourseEvent) {
    val intent = calendarInsertIntent(event) ?: return
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        AppLogger.warn("CourseDetail", "Keine Kalender-App für den Termin", e)
    }
}
