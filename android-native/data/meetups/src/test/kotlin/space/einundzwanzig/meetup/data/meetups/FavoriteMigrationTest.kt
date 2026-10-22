package space.einundzwanzig.meetup.data.meetups

import org.junit.Assert.assertEquals
import org.junit.Test
import space.einundzwanzig.meetup.data.meetups.model.Meetup

class FavoriteMigrationTest {

    private fun m(id: String, city: String) = Meetup(id = id, city = city, country = "DE", telegramLink = "", lat = 0.0, lng = 0.0)

    private val list = listOf(m("107", "Göttingen"), m("1", "Würzburg"), m("2", "Würzburg"), m("5", "Bonn"))

    @Test
    fun `spec vector collapses duplicates and drops ambiguous cities`() {
        val r = FavoriteMigration.migrate(listOf("107", "Würzburg", "Göttingen"), list)
        assertEquals(listOf("107"), r.ids)
        assertEquals(listOf("Würzburg"), r.ambiguous)
    }

    @Test
    fun `unique legacy city maps to its id in order`() {
        val r = FavoriteMigration.migrate(listOf("bonn", "Paris", "107"), list)
        assertEquals(listOf("5", "107"), r.ids)
        assertEquals(listOf("Paris"), r.dropped)
    }

    @Test
    fun `empty directory drops everything`() {
        assertEquals(emptyList<String>(), FavoriteMigration.migrate(listOf("107"), emptyList()).ids)
    }
}
