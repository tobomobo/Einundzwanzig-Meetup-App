package space.einundzwanzig.meetup.data.calendar

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.data.calendar.format.displayLocation
import space.einundzwanzig.meetup.data.calendar.format.displayTitle
import space.einundzwanzig.meetup.data.calendar.ics.IcsComponent
import space.einundzwanzig.meetup.data.calendar.ics.IcsMeetupDates
import space.einundzwanzig.meetup.data.calendar.merge.CalendarMerger
import space.einundzwanzig.meetup.data.calendar.model.CalendarItem
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class CalendarDisplayTest {
    private val now = Instant.parse("2026-10-22T10:00:00Z")

    private fun resources(language: String) = ApplicationProvider.getApplicationContext<Context>().let { context ->
        context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }).resources
    }

    @Test
    fun `missing ICS text localizes in all languages and retains raw dedup fields`() {
        val date = IcsMeetupDates.fromComponent(IcsComponent("VEVENT", emptyMap()), now, ZoneOffset.UTC)
        val item = CalendarItem.fromMeetup(date)
        val key = CalendarMerger.dedupKey(item, ZoneOffset.UTC)
        assertTrue(date.titleIsFallback)
        assertTrue(date.locationIsFallback)
        for ((language, title, location) in listOf(
            Triple("de", "Meetup", "Ort unbekannt"),
            Triple("en", "Meetup", "Unknown location"),
            Triple("es", "Encuentro", "Lugar desconocido"),
        )) {
            val res = resources(language)
            assertEquals(title, date.displayTitle(res))
            assertEquals(location, date.displayLocation(res))
            assertEquals(title, item.displayTitle(res))
            assertEquals(location, item.displayLocation(res))
            assertEquals(key, CalendarMerger.dedupKey(item, ZoneOffset.UTC))
        }
        assertEquals("Meetup", date.title)
        assertEquals("Ort unbekannt", date.location)
    }

    @Test
    fun `course fallback localizes without translating real text with the same spelling`() {
        val details = DartJson.decode("""{"events":[{"from":"2026-10-22T12:00:00Z"}]}""") as JsonObject
        val fallback = CalendarMerger.courseItems(listOf(details), now, ZoneOffset.UTC).single()
        assertTrue(fallback.titleIsFallback)
        assertEquals("Kurs", fallback.title)
        assertEquals("Course", fallback.displayTitle(resources("en")))
        assertEquals("Curso", fallback.displayTitle(resources("es")))
        assertEquals("Kurs", fallback.copy(titleIsFallback = false).displayTitle(resources("en")))

        val realDate = IcsMeetupDates.fromComponent(
            IcsComponent("VEVENT", mapOf("summary" to "Meetup", "location" to "Ort unbekannt")), now, ZoneOffset.UTC,
        )
        assertEquals("Meetup", realDate.displayTitle(resources("es")))
        assertEquals("Ort unbekannt", realDate.displayLocation(resources("en")))
    }
}
