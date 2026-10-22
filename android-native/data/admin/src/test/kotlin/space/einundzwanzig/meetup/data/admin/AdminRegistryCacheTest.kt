package space.einundzwanzig.meetup.data.admin

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.data.admin.model.AdminEntry
import space.einundzwanzig.meetup.data.admin.registry.AdminRegistryException
import space.einundzwanzig.meetup.data.admin.registry.AdminRelayFetch

/**
 * Admin parts of `test/security_followup_test.dart` plus the cache operations of spec 05 §8.4.6
 * (addAdmin/removeAdmin/removeFromCache/forceRefresh) and the sunset revocation (Audit M1).
 */
class AdminRegistryCacheTest {

    private val noRelay = AdminRelayFetch { _, _ -> null }

    // ---- security_followup_test.dart ----

    @Test
    fun `Organic-Name aus dem alten Claim ist kein Admin`() {
        assertTrue(AdminEntry(npub = "a", meetup = "m", name = "Organic (3 Badges)").isLegacyOrganicClaim)
        assertFalse(AdminEntry(npub = "a", meetup = "m", name = "Ben").isLegacyOrganicClaim)
        assertFalse(AdminEntry(npub = "a", meetup = "m", name = "Organic admin").isLegacyOrganicClaim)
    }

    @Test
    fun `getAdminList entfernt organische Alt-Claims aus dem Cache`() = runTest {
        val cache = DartJson.encode(
            listOf(
                linkedMapOf("npub" to "npub1organic", "meetup" to "x", "name" to "Organic (4 Badges)", "added_at" to 1),
                linkedMapOf("npub" to "npub1real", "meetup" to "y", "name" to "Ben", "added_at" to 2),
            ),
        )
        val registry = registry(prefs(AdminConstants.KEY_CACHE to cache), noRelay)

        assertEquals(listOf("npub1real"), registry.getAdminList().map { it.npub })
        assertEquals(1, registry.getAdminList().size)
    }

    @Test
    fun `addAdmin lehnt den automatischen Organic-Namen ab`() = runTest {
        val registry = registry(prefs(), noRelay)
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                registry.addAdmin(AdminEntry(npub = "npub1organic", meetup = "x", name = "Organic (3 Badges)"))
            }
        }
    }

    // ---- cache operations ----

    @Test
    fun `addAdmin - ungueltiger npub wirft, Duplikat wird ignoriert, addedAt wird gesetzt`() = runTest {
        val clock = FakeClock(1_760_000_000_123)
        val store = prefs()
        val registry = registry(store, noRelay, clock)
        val npub = freshNpub()

        val error = assertThrows(AdminRegistryException::class.java) {
            kotlinx.coroutines.runBlocking { registry.addAdmin(AdminEntry(npub = "npub1kaputt", meetup = "x")) }
        }
        assertEquals("Ungültiger npub.", error.message)

        registry.addAdmin(AdminEntry(npub = npub, meetup = "aschaffenburg-de", name = "Ben"))
        registry.addAdmin(AdminEntry(npub = npub, meetup = "anders", name = "Doppelt", addedAt = 5))
        registry.addAdmin(AdminEntry(npub = freshNpub(), meetup = "y", name = "Eva", addedAt = 42))

        val list = registry.getAdminList()
        assertEquals(2, list.size)
        assertEquals(AdminEntry(npub, "aschaffenburg-de", "Ben", 1_760_000_000), list[0])
        assertEquals(42L, list[1].addedAt)
        assertEquals(1_760_000_000_123, store.getInt(AdminConstants.KEY_CACHE_TIMESTAMP))
        // Wire format of the cache: Dart key order npub, meetup, name, added_at.
        assertEquals(
            "[{\"npub\":\"$npub\",\"meetup\":\"aschaffenburg-de\",\"name\":\"Ben\",\"added_at\":1760000000}",
            store.getString(AdminConstants.KEY_CACHE)!!.substringBefore(",{"),
        )
    }

    @Test
    fun `removeFromCache und removeAdmin entfernen den Eintrag und setzen den Zeitstempel`() = runTest {
        val clock = FakeClock(1_000)
        val a = freshNpub()
        val b = freshNpub()
        val store = prefs(
            AdminConstants.KEY_CACHE to AdminEntry.listToJson(listOf(AdminEntry(a, "x", "A", 1), AdminEntry(b, "y", "B", 2))),
            AdminConstants.KEY_CACHE_TIMESTAMP to 1L,
        )
        val registry = registry(store, noRelay, clock)

        registry.removeFromCache("npub1unbekannt")
        assertEquals(1L, store.getInt(AdminConstants.KEY_CACHE_TIMESTAMP)) // nothing changed → not saved

        registry.removeFromCache(a)
        assertEquals(listOf(b), registry.getAdminList().map { it.npub })
        assertEquals(1_000L, store.getInt(AdminConstants.KEY_CACHE_TIMESTAMP))

        clock.advance(500)
        registry.removeAdmin(b)
        assertTrue(registry.getAdminList().isEmpty())
        assertEquals(1_500L, store.getInt(AdminConstants.KEY_CACHE_TIMESTAMP))
        assertEquals(0L, registry.cacheAge()!!.inWholeMilliseconds)

        registry.clearCache()
        assertNull(store.getString(AdminConstants.KEY_CACHE))
        assertNull(registry.cacheAge())
    }

    @Test
    fun `forceRefresh liefert -1 ohne Relay und speichert nichts`() = runTest {
        val store = prefs()
        val registry = registry(store, noRelay)
        assertEquals(-1, registry.forceRefresh())
        assertNull(store.getString(AdminConstants.KEY_CACHE))
    }

    @Test
    fun `forceRefresh speichert die Bootstrap-Liste des Super-Admins`() = runTest {
        val listed = List(3) { freshNpub() }
        val store = prefs()
        val registry = registry(
            store,
            AdminRelayFetch { _, _ -> mapOf(superHex to listed.map { AdminEntry(it, "m", "Admin") }) },
        )
        assertEquals(3, registry.forceRefresh())
        assertEquals(listed, registry.getAdminList().map { it.npub })
    }

    @Test
    fun `Sunset-Phase - Relay-Daten ersetzen den Cache (Widerruf, Audit M1)`() = runTest {
        val a = freshNpub()
        val b = freshNpub()
        val signers = List(AdminConstants.SUNSET_THRESHOLD) { Nip19.decodeNpub(freshNpub()) }
        val clock = FakeClock()
        val store = prefs(
            // Fresh cache: the background refresh after the cache hit is skipped (< 1 h).
            AdminConstants.KEY_CACHE_TIMESTAMP to clock.nowMillis(),
            AdminConstants.KEY_SUNSET_RESET_DONE to true,
            AdminConstants.KEY_SUNSET_FLAG to true,
            AdminConstants.KEY_UNIQUE_AUTHORS to signers.size.toLong(),
            AdminConstants.KEY_CACHE to AdminEntry.listToJson(listOf(AdminEntry(a, "x", "A"), AdminEntry(b, "y", "B"))),
        )
        store.setStringList(AdminConstants.KEY_SUNSET_AUTHORS_SET, signers)
        val asked = mutableListOf<List<String>>()
        val aHex = Nip19.decodeNpub(a)
        val registry = registry(
            store,
            AdminRelayFetch { _, authors ->
                asked += authors
                mapOf(aHex to listOf(AdminEntry(a, "x", "A")))
            },
            clock,
        )

        assertTrue(registry.isSunsetActive())
        // The super admin is no longer special after the sunset.
        assertEquals("local_cache", registry.checkAdmin(a).source)
        assertEquals(1, registry.forceRefresh())
        assertEquals(listOf(Nip19.decodeNpub(a), Nip19.decodeNpub(b)), asked.first())
        assertEquals(listOf(a), registry.getAdminList().map { it.npub })
        // aHex was a new signer → counted once more.
        assertEquals(signers.size + 1L, store.getInt(AdminConstants.KEY_UNIQUE_AUTHORS))
    }

    @Test
    fun `Sunset-Phase - Super-Admin ist nicht mehr automatisch Admin`() = runTest {
        val signers = List(AdminConstants.SUNSET_THRESHOLD) { Nip19.decodeNpub(freshNpub()) }
        val store = prefs(
            AdminConstants.KEY_SUNSET_RESET_DONE to true,
            AdminConstants.KEY_UNIQUE_AUTHORS to signers.size.toLong(),
        )
        val registry = registry(store, noRelay)
        val result = registry.checkAdmin(AdminConstants.SUPER_ADMIN_NPUB)
        assertFalse(result.isAdmin)
        assertEquals("unavailable", result.source)
        assertEquals(true, store.getBool(AdminConstants.KEY_SUNSET_FLAG))
    }

    @Test
    fun `einmaliger K1-Reset verwirft alte Sunset-Zaehlung`() = runTest {
        val store = prefs(
            AdminConstants.KEY_UNIQUE_AUTHORS to 30L,
            AdminConstants.KEY_SUNSET_FLAG to true,
        )
        val registry = registry(store, noRelay)
        assertFalse(registry.isSunsetActive())
        assertEquals(true, store.getBool(AdminConstants.KEY_SUNSET_RESET_DONE))
        assertNull(store.getInt(AdminConstants.KEY_UNIQUE_AUTHORS))
        assertEquals(false, store.getBool(AdminConstants.KEY_SUNSET_FLAG))
    }

    @Test
    fun `manipuliertes Sunset-Flag ohne Zaehlung wird zurueckgesetzt`() = runTest {
        val store = prefs(
            AdminConstants.KEY_SUNSET_RESET_DONE to true,
            AdminConstants.KEY_SUNSET_FLAG to true,
            AdminConstants.KEY_UNIQUE_AUTHORS to 19L,
        )
        val registry = registry(store, noRelay)
        assertFalse(registry.isSunsetActive())
        assertEquals(false, store.getBool(AdminConstants.KEY_SUNSET_FLAG))
    }

    @Test
    fun `checkAdmin mit leerem npub ist no_key`() = runTest {
        val registry = registry(prefs(), noRelay)
        assertEquals("no_key", registry.checkAdmin("").source)
        val sa = registry.checkAdmin(AdminConstants.SUPER_ADMIN_NPUB)
        assertEquals(true, sa.isAdmin)
        assertEquals("Alle Meetups", sa.meetup)
        assertEquals("Super-Admin", sa.name)
        assertEquals("super_admin", sa.source)
    }
}
