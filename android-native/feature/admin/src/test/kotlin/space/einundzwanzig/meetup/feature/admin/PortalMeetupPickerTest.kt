package space.einundzwanzig.meetup.feature.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalMeetupPickerTest {

    @Test
    fun `isMine matches trimmed lower-cased name or city`() {
        val m = meetup(" Würzburg ", name = " Einundzwanzig Würzburg ")
        assertTrue(PortalMeetupPicker.isMine(m, setOf("einundzwanzig würzburg")))
        assertTrue(PortalMeetupPicker.isMine(m, setOf("würzburg")))
        assertFalse(PortalMeetupPicker.isMine(m, setOf("köln")))
    }

    @Test
    fun `order puts own meetups first, each part sorted by lower-cased city`() {
        val all = listOf(meetup("zürich"), meetup("Bern"), meetup("aachen"), meetup("Wien"), meetup("Graz"))
        val rows = PortalMeetupPicker.order(all, setOf("wien", "graz"))
        assertEquals(listOf("Graz", "Wien", "aachen", "Bern", "zürich"), rows.map { it.meetup.city })
        assertEquals(listOf(true, true, false, false, false), rows.map { it.isMine })
    }

    @Test
    fun `filter matches city or name, blank query keeps everything`() {
        val rows = PortalMeetupPicker.order(
            listOf(meetup("Würzburg", name = "Einundzwanzig Unterfranken"), meetup("Köln")),
            emptySet(),
        )
        assertEquals(2, PortalMeetupPicker.filter(rows, "   ").size)
        assertEquals(listOf("Würzburg"), PortalMeetupPicker.filter(rows, "unterFRANKEN").map { it.meetup.city })
        assertEquals(listOf("Köln"), PortalMeetupPicker.filter(rows, " köl").map { it.meetup.city })
        assertTrue(PortalMeetupPicker.filter(rows, "xyz").isEmpty())
    }

    @Test
    fun `subtitle only when the name differs from the city`() {
        assertNull(PickerMeetup(meetup("Köln", name = "KÖLN"), false).subtitle)
        assertNull(PickerMeetup(meetup("Köln", name = ""), false).subtitle)
        assertEquals("Einundzwanzig Köln", PickerMeetup(meetup("Köln", name = "Einundzwanzig Köln"), false).subtitle)
    }

    @Test
    fun `coordinate indicator is off only for exactly 0,0`() {
        assertFalse(PickerMeetup(meetup("A", lat = 0.0, lng = 0.0), false).hasCoordinates)
        assertTrue(PickerMeetup(meetup("A", lat = 0.0, lng = 1.0), false).hasCoordinates)
    }
}
