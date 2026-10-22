package space.einundzwanzig.meetup.feature.events.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.Settings
import space.einundzwanzig.meetup.core.ui.util.findActivity
import java.time.Instant

/** Everything the event screens hand to other apps. */
object PlatformActions {

    /**
     * System calendar insert (Flutter `add_2_calendar`): title, description, location, begin, end,
     * all-day. Returns `false` when no calendar app is installed.
     */
    fun addToCalendar(
        context: Context,
        title: String,
        description: String,
        location: String,
        begin: Instant,
        end: Instant,
        allDay: Boolean,
    ): Boolean {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.Events.DESCRIPTION, description)
            .putExtra(CalendarContract.Events.EVENT_LOCATION, location)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin.toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
        return start(context, intent)
    }

    /** System location settings (`EventSessionError.LOCATION_SERVICE_OFF` snackbar action). */
    fun openLocationSettings(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

    /** App details settings (`EventSessionError.LOCATION_DENIED_FOREVER` snackbar action). */
    fun openAppSettings(context: Context): Boolean = start(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )

    private fun start(context: Context, intent: Intent): Boolean {
        if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
