package space.einundzwanzig.meetup.feature.community

import android.content.Intent
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.data.community.portal.PortalCourseEvent
import space.einundzwanzig.meetup.feature.community.courses.calendarInsertIntent

@RunWith(AndroidJUnit4::class)
class CalendarInsertTest {

    private val beginMs = 1_767_268_800_000L

    private fun event(title: String = "", location: String = "", start: DartDateTime? = DartDateTime(beginMs * 1000, true)) =
        PortalCourseEvent(
            from = "2026-01-01T12:00:00Z",
            location = location,
            link = "",
            title = title,
            description = "Beschreibung",
            start = start,
            whenLabel = "",
        )

    @Test
    fun `insert intent carries title, location, description and a two hour slot`() {
        val intent = calendarInsertIntent(event(title = "Kurs", location = "Wien"))!!
        assertEquals(Intent.ACTION_INSERT, intent.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, intent.data)
        assertEquals("Kurs", intent.getStringExtra(CalendarContract.Events.TITLE))
        assertEquals("Wien", intent.getStringExtra(CalendarContract.Events.EVENT_LOCATION))
        assertEquals("Beschreibung", intent.getStringExtra(CalendarContract.Events.DESCRIPTION))
        assertEquals(beginMs, intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1))
        assertEquals(beginMs + 2 * 60 * 60 * 1000L, intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1))
    }

    @Test
    fun `title falls back to location, then to Termin`() {
        assertEquals("Wien", calendarInsertIntent(event(location = "Wien"))!!.getStringExtra(CalendarContract.Events.TITLE))
        assertEquals("Termin", calendarInsertIntent(event())!!.getStringExtra(CalendarContract.Events.TITLE))
    }

    @Test
    fun `no intent without a parsable start`() {
        assertNull(calendarInsertIntent(event(title = "Kurs", start = null)))
    }
}
