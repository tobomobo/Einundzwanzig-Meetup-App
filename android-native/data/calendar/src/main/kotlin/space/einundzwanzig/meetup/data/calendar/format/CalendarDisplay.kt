package space.einundzwanzig.meetup.data.calendar.format

import android.content.res.Resources
import space.einundzwanzig.meetup.data.calendar.R
import space.einundzwanzig.meetup.data.calendar.model.CalendarItem
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate

/** Resolve synthesized fallbacks only at presentation time, using the current UI locale. */
fun MeetupDate.displayTitle(resources: Resources): String =
    if (titleIsFallback) resources.getString(R.string.native_calendar_fallback_meetup) else title

fun MeetupDate.displayLocation(resources: Resources): String =
    if (locationIsFallback) resources.getString(R.string.native_calendar_unknown_location) else location

fun CalendarItem.displayTitle(resources: Resources): String = when {
    !titleIsFallback -> title
    isCourse -> resources.getString(R.string.native_calendar_fallback_course)
    else -> resources.getString(R.string.native_calendar_fallback_meetup)
}

fun CalendarItem.displayLocation(resources: Resources): String =
    if (locationIsFallback) resources.getString(R.string.native_calendar_unknown_location) else location
