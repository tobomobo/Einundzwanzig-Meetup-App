package space.einundzwanzig.meetup.data.meetups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.einundzwanzig.meetup.data.meetups.model.Meetup

/** Port of `test/meetup_group_name_test.dart`. */
class MeetupGroupNameTest {

    private fun meetup(name: String = "", city: String = "Augsburg") = Meetup(
        id = "test",
        name = name,
        city = city,
        country = "DE",
        telegramLink = "",
        lat = 0.0,
        lng = 0.0,
    )

    @Test
    fun `group name differentiates meetups in the same city`() {
        assertEquals("Einundzwanzig Augsburg Mitte", meetup(name = "Einundzwanzig Augsburg Mitte").groupName)
    }

    @Test
    fun `group name is omitted when absent or identical to city`() {
        assertNull(meetup().groupName)
        assertNull(meetup(name = "Augsburg").groupName)
        assertNull(meetup(name = "augsburg").groupName)
    }
}
