package space.einundzwanzig.meetup.feature.wallet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.data.badges.model.BadgeIdentity
import space.einundzwanzig.meetup.feature.wallet.badge

class WalletLibraryTest {

    @Test
    fun `meetup grouping sorts by size desc then key, organizer last, blank name is question mark`() {
        val all = listOf(
            badge("1", name = "Ulm"),
            badge("2", name = "Berlin"),
            badge("3", name = "Ulm", date = "2026-10-01T19:00:00.000"),
            badge("4", name = "  ", date = "2026-01-01T19:00:00.000"),
            badge("5", name = "Aachen", isOrganizer = true),
            badge("6", name = "Berlin", date = "2025-01-01T19:00:00.000"),
            badge("7", name = "Zürich"),
        )
        val groups = WalletLibrary.groups(all, groupByMeetup = true)
        assertEquals(listOf("Berlin", "Ulm", "?", "Zürich", WalletLibrary.ORGANIZER_GROUP_KEY), groups.map { it.key })
        // Newest first inside a group.
        assertEquals(listOf("3", "1"), groups[1].badges.map { it.id })
    }

    @Test
    fun `year grouping sorts years descending and keeps organizer last`() {
        val all = listOf(
            badge("1", date = "2024-05-01T19:00:00.000"),
            badge("2", date = "2026-05-01T19:00:00.000"),
            badge("o", date = "2027-01-01T19:00:00.000", isOrganizer = true),
            badge("3", date = "2025-05-01T19:00:00.000"),
        )
        assertEquals(
            listOf("2026", "2025", "2024", WalletLibrary.ORGANIZER_GROUP_KEY),
            WalletLibrary.groups(all, groupByMeetup = false).map { it.key },
        )
    }

    @Test
    fun `utc dates group by their own year fields`() {
        val utc = badge("u", date = "2025-12-31T23:30:00.000Z")
        assertEquals("2025", WalletLibrary.groupKey(utc, groupByMeetup = false))
    }

    @Test
    fun `filter trims and lowercases the query with Dart semantics`() {
        val all = listOf(badge("1", name = "München, DE"), badge("2", name = "Berlin"), badge("3", name = "STRAßE"))
        assertEquals(listOf("1"), WalletLibrary.filter(all, "  münch ").map { it.id })
        assertEquals(listOf("3"), WalletLibrary.filter(all, "straß").map { it.id })
        assertEquals(3, WalletLibrary.filter(all, "   ").size)
    }

    @Test
    fun `search and compact thresholds`() {
        assertFalse(WalletLibrary.showSearch(5))
        assertTrue(WalletLibrary.showSearch(6))
        assertFalse(WalletLibrary.showCompactToggle(6))
        assertTrue(WalletLibrary.showCompactToggle(7))
        assertTrue(WalletLibrary.showCountDots(24))
        assertFalse(WalletLibrary.showCountDots(25))
    }

    @Test
    fun `two structurally identical badges count as one duplicate`() {
        val a = badge("x", meetupEventId = "m1")
        val b = badge("x", meetupEventId = "m1")
        val dups = WalletLibrary.duplicates(listOf(a, b))
        assertEquals(1, dups.size)
        assertSame(b, dups.single())
    }

    @Test
    fun `rolling qr variant with a claim beats the one without`() {
        val plain = badge("a", meetupEventId = "m1")
        val claimed = badge("b", meetupEventId = "m1", claimSig = "d".repeat(128), claimPubkey = "e".repeat(64))
        assertSame(plain, WalletLibrary.duplicates(listOf(plain, claimed)).single())
    }

    @Test
    fun `organizer marker is not a duplicate of the participant badge`() {
        val participant = badge("a", meetupEventId = "m1")
        val marker = badge("a", meetupEventId = "m1", isOrganizer = true)
        assertTrue(WalletLibrary.duplicates(listOf(participant, marker)).isEmpty())
    }

    @Test
    fun `withoutDuplicates removes exactly the objects and keeps order`() {
        val a = badge("a", meetupEventId = "m1")
        val b = badge("b", meetupEventId = "m2")
        val c = badge("a", meetupEventId = "m1")
        val d = badge("d", meetupEventId = "m3")
        val all = listOf(a, b, c, d)
        val kept = WalletLibrary.withoutDuplicates(all, WalletLibrary.duplicates(all))
        assertEquals(3, kept.size)
        assertSame(a, kept[0]); assertSame(b, kept[1]); assertSame(d, kept[2])
    }

    @Test
    fun `cleanup summary counts per trimmed name in first-occurrence order`() {
        val dups = listOf(badge("1", name = " Ulm "), badge("2", name = ""), badge("3", name = "Ulm"))
        assertEquals(linkedMapOf("Ulm" to 2, "?" to 1), WalletLibrary.cleanupSummary(dups))
    }

    @Test
    fun `global index uses identity`() {
        val a = badge("a")
        val twin = badge("a")
        val all = listOf(a, twin)
        assertEquals(1, WalletLibrary.globalIndex(all, twin))
        assertEquals(-1, WalletLibrary.globalIndex(all, badge("a")))
    }

    @Test
    fun `resolve prefers the index when its union key matches, else the first match`() {
        val a = badge("a", meetupEventId = "m1")
        val dup = badge("a2", meetupEventId = "m1")
        val other = badge("b", meetupEventId = "m2")
        val all = listOf(a, other, dup)
        val key = BadgeIdentity.unionKey(dup)
        assertSame(dup, WalletLibrary.resolve(all, key, 2))
        assertSame(a, WalletLibrary.resolve(all, key, 1))
        assertSame(a, WalletLibrary.resolve(all, key, 99))
        assertNull(WalletLibrary.resolve(all, "evt:none", 0))
    }

    @Test
    fun `collection builds the group from the filtered list newest first`() {
        val old = badge("1", name = "Ulm", date = "2025-01-01T19:00:00.000")
        val new = badge("2", name = "Ulm", date = "2026-01-01T19:00:00.000")
        val all = listOf(old, badge("3", name = "Berlin"), new)
        assertEquals(listOf("2", "1"), WalletLibrary.collection(all, "Ulm", "").map { it.id })
        assertTrue(WalletLibrary.collection(all, "Ulm", "berl").isEmpty())
        assertTrue(WalletLibrary.collection(all, "Gone", "").isEmpty())
    }

    @Test
    fun `located and city count`() {
        val all = listOf(
            badge("1", name = "Ulm", lat = 48.4, lng = 10.0),
            badge("2", name = "Ulm", lat = 48.4, lng = 10.0),
            badge("3", name = "Null"),
            badge("4", name = "Equator", lat = 0.0, lng = 5.0),
        )
        val located = WalletLibrary.located(all)
        assertEquals(listOf("1", "2", "4"), located.map { it.id })
        assertEquals(2, WalletLibrary.cityCount(located))
    }

    @Test
    fun `countAdded counts new union keys`() {
        val before = setOf(BadgeIdentity.unionKey(badge("a", meetupEventId = "m1")))
        val after = listOf(badge("a", meetupEventId = "m1"), badge("b", meetupEventId = "m2"), badge("c", meetupEventId = "m3"))
        assertEquals(2, WalletLibrary.countAdded(before, after))
    }
}
