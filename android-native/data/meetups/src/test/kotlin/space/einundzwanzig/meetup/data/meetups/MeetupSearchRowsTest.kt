package space.einundzwanzig.meetup.data.meetups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.einundzwanzig.meetup.data.meetups.model.Meetup

/** MeetupSearchSheet rows (spec 09 §2.5) with Dart case mapping (spec 00 item 12). */
class MeetupSearchRowsTest {

    private fun m(id: String, city: String, name: String = "") =
        Meetup(id = id, name = name, city = city, country = "DE", telegramLink = "", lat = 0.0, lng = 0.0)

    private val all = listOf(
        m("1", "Würzburg", "Einundzwanzig Würzburg Nord"),
        m("2", "würzburg", "Einundzwanzig Würzburg Süd"),
        m("3", "ΟΔΟΣ", ""),
        m("4", "İstanbul", "Istanbul"),
    )

    @Test
    fun `filter matches city or name with Dart lower case`() {
        assertEquals(listOf("1", "2"), MeetupSearchRows.filter(all, "WÜRZ").map { it.id })
        assertEquals(listOf("2"), MeetupSearchRows.filter(all, "süd").map { it.id })
        // No final-sigma rule: Dart lowers ΟΔΟΣ to οδοσ.
        assertEquals(listOf("3"), MeetupSearchRows.filter(all, "οδοσ").map { it.id })
        // İ lowers to plain i in Dart.
        assertEquals(listOf("4"), MeetupSearchRows.filter(all, "istan").map { it.id })
        assertEquals(all, MeetupSearchRows.filter(all, ""))
    }

    @Test
    fun `title uses the name only when the city is shared`() {
        assertEquals("Einundzwanzig Würzburg Nord", MeetupSearchRows.title(all[0], all))
        assertEquals("ΟΔΟΣ", MeetupSearchRows.title(all[2], all))
        assertEquals(2, MeetupSearchRows.sameCityCount("WÜRZBURG", all))
    }

    @Test
    fun `subtitle is the name when it differs from the city`() {
        assertEquals("Einundzwanzig Würzburg Nord", MeetupSearchRows.subtitle(all[0]))
        assertNull(MeetupSearchRows.subtitle(all[2]))
        assertNull(MeetupSearchRows.subtitle(all[3]))
    }
}
