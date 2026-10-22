package space.einundzwanzig.meetup.core.navigation

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DestinationArgumentsTest {

    @Test
    fun `event calendar keeps an ISO day and exposes it as LocalDate`() {
        val key = EventCalendar.forDay(LocalDate.of(2026, 3, 5))
        assertEquals("2026-03-05", key.initialDay)
        assertEquals(LocalDate.of(2026, 3, 5), key.initialLocalDate)
        assertNull(EventCalendar().initialLocalDate)
    }

    @Test
    fun `event calendar rejects a malformed day`() {
        assertThrows(IllegalArgumentException::class.java) { EventCalendar(initialDay = "5.3.2026") }
        assertThrows(IllegalArgumentException::class.java) { EventCalendar(initialDay = "2026-02-30") }
    }

    @Test
    fun `validation also runs when decoding`() {
        assertThrows(IllegalArgumentException::class.java) {
            Json.decodeFromString(EventCalendar.serializer(), """{"initialDay":"tomorrow"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Json.decodeFromString(BadgeDetails.serializer(), """{"unionKey":"evt:x","collectionIndex":-1}""")
        }
    }

    @Test
    fun `meetup calendar preselection excludes explicit filters`() {
        assertThrows(IllegalArgumentException::class.java) {
            MeetupCalendar(initialSearch = "Wien", preselectNextFavorite = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MeetupCalendar(initialMeetupId = "12", preselectNextFavorite = true)
        }
        assertTrue(MeetupCalendar(preselectNextFavorite = true).preselectNextFavorite)
    }

    @Test
    fun `badge details requires a union key and a non-negative index`() {
        assertThrows(IllegalArgumentException::class.java) { BadgeDetails(unionKey = "", collectionIndex = 0) }
        assertThrows(IllegalArgumentException::class.java) { BadgeDetails(unionKey = "evt:x", collectionIndex = -1) }
        assertEquals(2, BadgeDetails(unionKey = "nd:wien|2026-01-02", collectionIndex = 2).collectionIndex)
    }

    @Test
    fun `location picker takes both coordinates or none`() {
        assertThrows(IllegalArgumentException::class.java) { LocationPicker(initialLatitude = 48.2) }
        assertThrows(IllegalArgumentException::class.java) { LocationPicker(initialLongitude = 16.3) }
        assertNull(LocationPicker().initialLatitude)
        assertEquals(16.3, LocationPicker(48.2, 16.3).initialLongitude!!, 0.0)
    }

    @Test
    fun `event chat derives the author from the address`() {
        val pubkey = "cd".repeat(32)
        assertEquals(pubkey, EventChat("31923:$pubkey:d-tag:with:colons", "t").eventAuthor)
        assertEquals(pubkey, EventChat("31922:$pubkey:", "t").eventAuthor)
        assertEquals("", EventChat("31923", "t").eventAuthor)
        assertThrows(IllegalArgumentException::class.java) { EventChat(" ", "t") }
    }

    @Test
    fun `portal event editor distinguishes create and edit`() {
        assertFalse(PortalEventEditor(meetupId = 1, meetupName = "x").isEdit)
        assertTrue(PortalEventEditor(meetupId = 1, meetupName = "x", eventId = 9, eventIndex = 0).isEdit)
        assertTrue(PortalEventEditor(meetupId = 1, meetupName = "x", eventId = 9).isEdit)
        // A date without portal id still opens in edit mode (Flutter `existing: ev`).
        assertTrue(PortalEventEditor(meetupId = 1, meetupName = "x", eventIndex = 2).isEdit)
        assertThrows(IllegalArgumentException::class.java) {
            PortalEventEditor(meetupId = 1, meetupName = "x", eventIndex = -1)
        }
    }

    @Test
    fun `ids from remote data are accepted and resolved by the screen`() {
        // Dart: Meetup.id = json['id'] ?? json['name'] ?? "unknown" -> "" for a nameless entry.
        assertEquals("", MeetupDetails("").meetupId)
        assertEquals(" ", MeetupChat(" ").roomId)
        // Dart RSS parser: id = guid.isNotEmpty ? guid : link -> "" without guid and link.
        assertEquals(NewsArticleDetails("", listIndex = 4), NewsArticleDetails("", 4))
        assertEquals(-1, NewsArticleDetails("x").listIndex)
    }

    @Test
    fun `values the app builds itself must not be blank`() {
        assertThrows(IllegalArgumentException::class.java) { ReputationScanResult("") }
        assertThrows(IllegalArgumentException::class.java) { EventChat("", "t") }
    }

    @Test
    fun `organizer group key matches the wallet spec`() {
        assertEquals("\u0000organizer", BadgeCollection.ORGANIZER_GROUP_KEY)
    }

    @Test
    fun `result keys are unique and compare by name`() {
        val names = NavResults.all.map { it.name }
        assertEquals(names.size, names.toSet().size)
        // Internal constructor: only this module can create keys, so these never collide in practice.
        assertEquals(NavResultKey<String>("x"), NavResultKey<Int>("x"))
        assertEquals(NavResultKey<String>("x").hashCode(), NavResultKey<Int>("x").hashCode())
        assertThrows(IllegalArgumentException::class.java) { NavResultKey<String>(" ") }
    }
}
