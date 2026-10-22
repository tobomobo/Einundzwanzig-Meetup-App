package space.einundzwanzig.meetup.data.admin

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.data.admin.model.AdminEntry
import space.einundzwanzig.meetup.data.admin.registry.AdminRelayFetch

/**
 * Port of `test/admin_registry_sunset_signer_test.dart` (Audit-2 Fund 6): the sunset counts
 * distinct queried SIGNERS (event pubkeys), never the npubs listed inside an event.
 */
class AdminRegistrySunsetSignerTest {

    private fun legit(npub: String) = AdminEntry(npub = npub, meetup = "m", name = "Admin")

    @Test
    fun `EIN Signer mit 25 legitimen npubs zaehlt als EIN Autor - kein Sunset`() = runTest {
        val store = prefs()
        val listed = List(25) { freshNpub() }
        val registry = registry(store, AdminRelayFetch { _, _ -> mapOf(superHex to listed.map(::legit)) })

        val result = registry.checkAdmin(listed.first())
        assertTrue(result.isAdmin)
        assertEquals("nostr_relay", result.source)

        assertEquals(1L, store.getInt(AdminConstants.KEY_UNIQUE_AUTHORS))
        assertFalse(registry.isSunsetActive())
        assertNotEquals(true, store.getBool(AdminConstants.KEY_SUNSET_FLAG))

        val superResult = registry.checkAdmin(AdminConstants.SUPER_ADMIN_NPUB)
        assertEquals("super_admin", superResult.source)
        assertEquals("Alle Meetups", superResult.meetup)
        assertEquals("Super-Admin", superResult.name)

        assertEquals(listed.toSet(), registry.getAdminList().map { it.npub }.toSet())
    }

    @Test
    fun `Bootstrap fragt nur den Super-Admin - fremde Signer loesen keinen Sunset aus`() = runTest {
        assertEquals(20, AdminConstants.SUNSET_THRESHOLD)
        val store = prefs()
        val strangers = List(AdminConstants.SUNSET_THRESHOLD) { KeyPair.generate().publicKeyHex }
        var asked: List<String>? = null
        val registry = registry(
            store,
            AdminRelayFetch { _, authors ->
                asked = authors.toList()
                strangers.associateWith { listOf(legit(freshNpub())) } + (superHex to listOf(legit(freshNpub())))
            },
        )

        registry.fetchFromRelays()

        assertEquals(listOf(superHex), asked)
        assertEquals(1L, store.getInt(AdminConstants.KEY_UNIQUE_AUTHORS))
        assertFalse(registry.isSunsetActive())
        assertNotEquals(true, store.getBool(AdminConstants.KEY_SUNSET_FLAG))
    }
}
