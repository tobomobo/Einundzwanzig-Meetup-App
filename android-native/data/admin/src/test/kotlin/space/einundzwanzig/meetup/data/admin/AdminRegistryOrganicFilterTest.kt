package space.einundzwanzig.meetup.data.admin

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.data.admin.model.AdminEntry
import space.einundzwanzig.meetup.data.admin.registry.AdminRelayFetch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Port of `test/admin_registry_organic_filter_test.dart` (K1 follow-up): poisoned
 * `Organic (N Badges)` entries never make anyone admin, neither from the cache nor from the raw
 * relay list, and never trigger the sunset. Relay access is replaced by the fetch hook.
 */
class AdminRegistryOrganicFilterTest {

    private val victim = KeyPair.generate()
    private val victimNpub = victim.npub
    private val realNpub = freshNpub()

    private fun entry(npub: String, name: String) =
        AdminEntry(npub = npub, meetup = "aschaffenburg-de", name = name, addedAt = 1)

    private val noRelay = AdminRelayFetch { _, _ -> null }

    @Test
    fun `vergifteter Cache-Eintrag ist fuer checkAdmin kein Admin mehr`() = runTest {
        val clock = FakeClock()
        val store = prefs(
            AdminConstants.KEY_CACHE to AdminEntry.listToJson(listOf(entry(victimNpub, "Organic (3 Badges)"), entry(realNpub, "Ben"))),
            AdminConstants.KEY_CACHE_TIMESTAMP to clock.nowMillis(),
        )
        val registry = registry(store, noRelay, clock)

        val result = registry.checkAdmin(victimNpub)
        assertFalse(result.isAdmin)
        assertNotEquals("local_cache", result.source)
        assertEquals("unavailable", result.source)

        val byPubkey = registry.checkAdminByPubkey(victim.publicKeyHex)
        assertFalse(byPubkey.isAdmin)
        assertNotEquals("local_cache", byPubkey.source)

        val real = registry.checkAdmin(realNpub)
        assertTrue(real.isAdmin)
        assertEquals("local_cache", real.source)

        val stored = DartJson.decode(store.getString(AdminConstants.KEY_CACHE)!!) as JsonArray
        assertEquals(listOf(realNpub), stored.map { it.jsonObject["npub"]!!.jsonPrimitive.content })
    }

    @Test
    fun `rohe Relay-Liste mit Organic-Eintrag macht in Schritt 4 niemanden zum Admin`() = runTest {
        val store = prefs()
        val registry = registry(
            store,
            AdminRelayFetch { _, _ ->
                mapOf(
                    superHex to listOf(
                        AdminEntry(npub = victimNpub, meetup = "x", name = "Organic (3 Badges)"),
                        AdminEntry(npub = realNpub, meetup = "y", name = "Ben"),
                    ),
                )
            },
        )

        val result = registry.checkAdmin(victimNpub)
        assertFalse(result.isAdmin)
        assertEquals("not_found", result.source)

        val real = registry.checkAdmin(realNpub)
        assertTrue(real.isAdmin)

        assertEquals(listOf(realNpub), registry.getAdminList().map { it.npub })
    }

    @Test
    fun `vergifteter Cache loest Sunset nicht aus und wird zurueckgesetzt`() = runTest {
        val clock = FakeClock()
        val poisoned = List(25) { i -> entry(freshNpub(), "Organic (${i + 3} Badges)") } + entry(realNpub, "Ben")
        val store = prefs(
            AdminConstants.KEY_CACHE to AdminEntry.listToJson(poisoned),
            AdminConstants.KEY_CACHE_TIMESTAMP to clock.nowMillis(),
            AdminConstants.KEY_UNIQUE_AUTHORS to 25,
        )
        val registry = registry(store, noRelay, clock)

        assertFalse(registry.isSunsetActive())

        val count = store.getInt(AdminConstants.KEY_UNIQUE_AUTHORS) ?: 0
        assertTrue(count < 20)

        val list = registry.getAdminList()
        assertTrue(list.none { it.isLegacyOrganicClaim })
        assertEquals(realNpub, list.single().npub)
    }

    @Test
    fun `Bootstrap zaehlt nur den angefragten Super-Admin, nicht mitgeschickte Signer`() = runTest {
        val store = prefs()
        val secondSigner = KeyPair.generate().publicKeyHex
        val organicSigner = KeyPair.generate().publicKeyHex
        val secondNpub = freshNpub()
        val organicNpubs = List(25) { freshNpub() }
        val registry = registry(
            store,
            AdminRelayFetch { _, _ ->
                mapOf(
                    superHex to listOf(
                        AdminEntry(npub = realNpub, meetup = "y", name = "Ben"),
                        AdminEntry(npub = organicNpubs.first(), meetup = "x", name = "Organic (3 Badges)"),
                    ),
                    secondSigner to listOf(AdminEntry(npub = secondNpub, meetup = "z", name = "Eva")),
                    organicSigner to organicNpubs.map { AdminEntry(npub = it, meetup = "x", name = "Organic (3 Badges)") },
                )
            },
        )

        registry.checkAdmin(realNpub)

        assertEquals(1L, store.getInt(AdminConstants.KEY_UNIQUE_AUTHORS) ?: 0L)
        assertFalse(registry.isSunsetActive())
        assertEquals(setOf(realNpub), registry.getAdminList().map { it.npub }.toSet())
    }

    @Test
    fun `checkAdminByPubkey mit ungueltigem Hex ist invalid_key, leer ist no_key`() = runTest {
        val registry = registry(prefs(), noRelay)
        assertEquals("invalid_key", registry.checkAdminByPubkey("zz-not-hex").source)
        assertEquals("no_key", registry.checkAdminByPubkey("").source)
        assertEquals("no_key", registry.checkAdmin("").source)
        assertEquals(Nip19.encodeNpub(superHex), AdminConstants.SUPER_ADMIN_NPUB)
    }
}
