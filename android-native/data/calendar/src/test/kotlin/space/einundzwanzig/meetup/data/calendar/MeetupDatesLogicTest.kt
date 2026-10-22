package space.einundzwanzig.meetup.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.data.calendar.meetups.FavoriteToggle
import space.einundzwanzig.meetup.data.calendar.meetups.MeetupDatesLogic
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import java.time.Instant

/** `calendar_screen.dart` rules (spec 10 §2.2): filter, crest, favorite star/toggle, RSVP waves. */
class MeetupDatesLogicTest {

    private val berlin = group("1", "Einundzwanzig Berlin", "Berlin", "berlin")
    private val au = group("2", "Einundzwanzig Au", "Au", "au")
    private val aschaffenburg = group("3", "Einundzwanzig Aschaffenburg", "Aschaffenburg", "aschaffenburg")
        .copy(logoUrl = "/storage/asch.png")
    private val hamburg = group("4", "Einundzwanzig Hamburg", "Hamburg", "hamburg")
        .copy(coverImagePath = "https://img/hh.jpg")
    private val meetups = listOf(berlin, au, aschaffenburg, hamburg)

    private fun date(title: String, location: String = "", meetupId: String = "", logo: String = "") = MeetupDate(
        title = title, description = "", location = location, start = Instant.parse("2026-03-10T18:00:00Z"),
        url = "", meetupId = meetupId, logo = logo,
    )

    private fun resolve(key: String): Meetup? =
        meetups.firstOrNull { it.id == key } ?: meetups.firstOrNull { it.city.equals(key, ignoreCase = true) }

    @Test
    fun `filter keeps the exact meetup while the prefilled search is untouched`() {
        val a = date("Einundzwanzig Berlin", meetupId = "1")
        val b = date("Berlin Stammtisch", location = "Berlin", meetupId = "9")
        val c = date("Hamburg", location = "Hafen", meetupId = "4")
        val all = listOf(a, b, c)
        assertEquals(listOf(a), MeetupDatesLogic.filter(all, "BERLIN", "Berlin", "1", meetups))
        // Edited search → substring on title/location.
        assertEquals(listOf(a, b), MeetupDatesLogic.filter(all, "berl", "Berlin", "1", meetups))
        // No initial meetup → substring; empty query keeps all; query is not trimmed.
        assertEquals(listOf(c), MeetupDatesLogic.filter(all, "hafen", null, null, meetups))
        assertEquals(all, MeetupDatesLogic.filter(all, "", null, null, meetups))
        assertEquals(emptyList<MeetupDate>(), MeetupDatesLogic.filter(all, " hafen", null, null, meetups))
        // initialMeetupId with null initialSearch: exact mode only for the empty query.
        assertEquals(listOf(c), MeetupDatesLogic.filter(all, "", null, "4", meetups))
    }

    @Test
    fun `filterPrepared keeps the same dates as filter`() {
        val all = listOf(
            date("Einundzwanzig Berlin", meetupId = "1"),
            date("Berlin Stammtisch", location = "Berlin", meetupId = "9"),
            date("Hamburg", location = "Hafen", meetupId = "4"),
            date("Stammtisch", location = "Aschaffenburg"),
        )
        val keys = all.map { MeetupDatesLogic.searchKey(it, meetups) }
        val cases = listOf(
            listOf("BERLIN", "Berlin", "1"), listOf("berl", "Berlin", "1"), listOf("hafen", null, null),
            listOf("", null, null), listOf(" hafen", null, null), listOf("", null, "4"), listOf("asch", null, null),
        )
        for ((q, search, id) in cases) {
            val expected = MeetupDatesLogic.filter(all, q!!, search, id, meetups)
            val actual = MeetupDatesLogic.filterPrepared(keys, q, search, id).map { all[it] }
            assertEquals("query=$q", expected, actual)
        }
    }

    @Test
    fun `crest picks the longest contained city and falls back to cover image`() {
        assertEquals(aschaffenburg, MeetupDatesLogic.crestMeetup(date("Meetup Aschaffenburg"), meetups))
        // "Au" is shorter than 3 characters and never matches.
        assertNull(MeetupDatesLogic.crestMeetup(date("Treffen in Au"), meetups))
        assertEquals(berlin, MeetupDatesLogic.crestMeetup(date("Stammtisch", "berlin mitte"), meetups))
        assertEquals(
            "https://portal.einundzwanzig.space/storage/asch.png",
            MeetupDatesLogic.crestUrl(date("Aschaffenburg"), meetups),
        )
        assertEquals("https://img/hh.jpg", MeetupDatesLogic.crestUrl(date("Hamburg"), meetups))
        assertEquals("", MeetupDatesLogic.crestUrl(date("Berlin"), meetups))
        assertEquals("", MeetupDatesLogic.crestUrl(date("Nirgendwo"), meetups))
        // The date's own portal logo wins.
        assertEquals("https://x/l.png", MeetupDatesLogic.crestUrl(date("Hamburg", logo = "https://x/l.png"), meetups))
        assertEquals(
            "https://portal.einundzwanzig.space/storage/l.png",
            MeetupDatesLogic.crestUrl(date("Hamburg", logo = "/storage/l.png"), meetups),
        )
    }

    @Test
    fun `favorite star and toggle treat legacy city names as the same meetup`() {
        assertTrue(MeetupDatesLogic.isFavorite(listOf("berlin"), "1", ::resolve))
        assertFalse(MeetupDatesLogic.isFavorite(listOf("4"), "1", ::resolve))

        assertEquals(FavoriteToggle(listOf("4", "1"), "4", added = true), MeetupDatesLogic.toggleFavorite(listOf("4"), "1", ::resolve))
        assertEquals(FavoriteToggle(listOf("4"), "4", added = false), MeetupDatesLogic.toggleFavorite(listOf("Berlin", "4", "1"), "1", ::resolve))
        assertEquals(FavoriteToggle(emptyList(), "", added = false), MeetupDatesLogic.toggleFavorite(listOf("1"), "1", ::resolve))
        // Unresolvable key equal to favKey still counts as the same meetup.
        assertEquals(FavoriteToggle(emptyList(), "", added = false), MeetupDatesLogic.toggleFavorite(listOf("99"), "99", ::resolve))
        assertEquals(FavoriteToggle(listOf("99"), "99", added = true), MeetupDatesLogic.toggleFavorite(emptyList(), "99", ::resolve))
    }

    @Test
    fun `RSVP prefetch loads 12 first, then waves of 20`() {
        assertEquals(emptyList<List<Int>>(), MeetupDatesLogic.rsvpPrefetchWaves(emptyList()))
        assertEquals(listOf(listOf(1, 2, 3)), MeetupDatesLogic.rsvpPrefetchWaves(listOf(1, 2, 3)))
        val waves = MeetupDatesLogic.rsvpPrefetchWaves((1..55).toList())
        assertEquals(listOf(12, 20, 20, 3), waves.map { it.size })
        assertEquals((1..55).toList(), waves.flatten())
        assertEquals(listOf(12), MeetupDatesLogic.rsvpPrefetchWaves((1..12).toList()).map { it.size })
    }
}
