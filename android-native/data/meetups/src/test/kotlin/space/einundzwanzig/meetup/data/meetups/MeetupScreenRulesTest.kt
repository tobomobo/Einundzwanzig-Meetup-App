package space.einundzwanzig.meetup.data.meetups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.data.meetups.portal.PortalMeetupEvent

/** Screen list rules of spec 09 §2.1, §2.3, §2.4. */
class MeetupScreenRulesTest {

    private fun m(id: String, city: String, country: String = "DE", name: String = "") =
        Meetup(id = id, name = name, city = city, country = country, telegramLink = "", lat = 0.0, lng = 0.0)

    @Test
    fun `selection filter matches city or country but not the name`() {
        val all = listOf(m("1", "Wien", "AT", "Einundzwanzig Hamburg Fans"), m("2", "Hamburg"), m("3", "İzmir", "TR"))
        assertEquals(listOf("2"), MeetupScreenRules.selectionFilter(all, "HAMB").map { it.id })
        assertEquals(listOf("1"), MeetupScreenRules.selectionFilter(all, "at").map { it.id })
        assertEquals(listOf("3"), MeetupScreenRules.selectionFilter(all, "izm").map { it.id })
        assertEquals(all, MeetupScreenRules.selectionFilter(all, ""))
    }

    @Test
    fun `details dates match the city in title or location`() {
        assertTrue(MeetupScreenRules.detailsDateMatches("Stammtisch MÜNCHEN", "", "München"))
        assertTrue(MeetupScreenRules.detailsDateMatches("Stammtisch", "Bar, münchen", "München"))
        assertFalse(MeetupScreenRules.detailsDateMatches("Stammtisch", "Berlin", "München"))
    }

    @Test
    fun `event location placeholder is hidden`() {
        assertFalse(MeetupScreenRules.showsEventLocation(""))
        assertFalse(MeetupScreenRules.showsEventLocation("Ort Unbekannt"))
        assertTrue(MeetupScreenRules.showsEventLocation("Café"))
    }

    private fun ev(id: Int?, start: String, meetup: Int = 7) = PortalMeetupEvent(id, meetup, start)

    @Test
    fun `events of a meetup are sorted by the raw start string`() {
        val events = listOf(ev(1, "2026-08-01 18:00:00"), ev(2, "2026-07-01 18:00:00"), ev(3, "2026-01-01", meetup = 8))
        assertEquals(listOf(2, 1), MeetupScreenRules.eventsOfMeetup(events, 7).map { it.id })
    }

    @Test
    fun `edited event resolves by index, then id, else null`() {
        val events = listOf(ev(10, "2026-08-01"), ev(null, "2026-07-01"), ev(12, "2026-09-01"))
        // sorted: [null@07, 10@08, 12@09]
        assertEquals(10, MeetupScreenRules.resolveEditedEvent(events, 7, 10, 1)?.id)
        assertEquals(12, MeetupScreenRules.resolveEditedEvent(events, 7, 12, 0)?.id)
        val noId = MeetupScreenRules.resolveEditedEvent(events, 7, null, 0)
        assertEquals("2026-07-01", noId?.start)
        assertNull(MeetupScreenRules.resolveEditedEvent(events, 7, 99, 5))
        assertNull(MeetupScreenRules.resolveEditedEvent(events, 7, null, 2))
    }
}
