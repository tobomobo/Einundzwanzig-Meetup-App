package space.einundzwanzig.meetup.data.identity

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.data.identity.keys.IdentityKeys
import space.einundzwanzig.meetup.data.identity.nip46.Nip46Client
import space.einundzwanzig.meetup.data.identity.nip46.Nip46ClientMetadata
import space.einundzwanzig.meetup.data.identity.nip46.Nip46Exception

/** Port of the "Modus und Sitzung", pairing, relay and backup groups of `nip46_signing_service_test.dart`. */
class Nip46SigningServiceTest {

    private val myNpub = "npub1lf0rga7j66uj6enae2mxezamz5nsz3vechhvmh25tcarn4u8qf5q534jzc"
    private val bunkerUri = "bunker://bbbb347dd2d6b92d667dcab6cc8bbb1524e0a2ccc5eecddd551787473c3814d3?relay=wss%3A%2F%2Frelay.damus.io"
    private val clientKey = "1".repeat(64)

    @Test
    fun storedAndReadAsNip46() = runBlocking {
        val id = TestIdentity()
        id.signing.restoreNip46(myNpub, bunkerUri, clientKey)
        assertEquals(SigningMode.NIP46, id.signing.getMode())
        assertTrue(id.signing.isExternalSigner())
        assertEquals(myNpub, id.signing.npub())
        assertEquals("nip46", id.prefs.getString(IdentityKeys.SIGNING_MODE))
    }

    @Test
    fun canSignNeedsAllThreeSessionParts() = runBlocking {
        val id = TestIdentity()
        id.signing.restoreNip46(myNpub, bunkerUri, clientKey)
        assertTrue(id.signing.canSign())
        id.secure.delete(IdentityKeys.NIP46_CLIENT_SK)
        assertFalse(id.signing.canSign())
    }

    @Test
    fun canSignIsFalseWithoutTheBunkerUri() = runBlocking {
        val id = TestIdentity()
        id.signing.restoreNip46(myNpub, bunkerUri, clientKey)
        id.prefs.remove(IdentityKeys.NIP46_BUNKER_URI)
        assertFalse(id.signing.canSign())
    }

    @Test
    fun brokenBunkerUriIsNotStored() = runBlocking {
        val id = TestIdentity()
        try {
            id.signing.restoreNip46(myNpub, "bunker://ohne-relay", clientKey)
            fail("expected Nip46Exception")
        } catch (e: Nip46Exception) {
            // expected
        }
        assertEquals(SigningMode.LOCAL, id.signing.getMode())
    }

    @Test
    fun disconnectClearsAllFourFields() = runBlocking {
        val id = TestIdentity()
        id.signing.restoreNip46(myNpub, bunkerUri, clientKey)
        id.signing.disconnectNip46()
        assertEquals(SigningMode.LOCAL, id.signing.getMode())
        assertNull(id.prefs.getString(IdentityKeys.NIP46_PUBKEY_HEX))
        assertNull(id.prefs.getString(IdentityKeys.NIP46_NPUB))
        assertNull(id.prefs.getString(IdentityKeys.NIP46_BUNKER_URI))
        assertNull(id.secure.entries.value[IdentityKeys.NIP46_CLIENT_SK])
    }

    @Test
    fun useLocalModeClearsTheBunkerSession() = runBlocking {
        val id = TestIdentity()
        id.signing.restoreNip46(myNpub, bunkerUri, clientKey)
        id.signing.useLocalMode()
        assertEquals(SigningMode.LOCAL, id.signing.getMode())
        assertNull(id.prefs.getString(IdentityKeys.NIP46_BUNKER_URI))
        assertNull(id.secure.entries.value[IdentityKeys.NIP46_CLIENT_SK])
    }

    @Test
    fun pairingUriCarriesNameIconUrlAndTheActiveSession() = runBlocking {
        val id = TestIdentity()
        val pairing = id.signing.startNip46Pairing()
        try {
            val uri = pairing.uri
            assertTrue(uri.startsWith("nostrconnect://${pairing.client.clientPubkeyHex}?"))
            assertEquals(pairing.secret, queryParam(uri, "secret"))
            assertEquals(Nip46Client.REQUESTED_PERMS, queryParam(uri, "perms"))
            assertEquals(pairing.relays, queryAll(uri)["relay"])
            assertEquals("Einundzwanzig Meetup", queryParam(uri, "name"))
            assertTrue(uri.contains("name=Einundzwanzig%20Meetup"))
            assertEquals(Nip46ClientMetadata.URL, queryParam(uri, "url"))
            assertEquals(Nip46ClientMetadata.IMAGE, queryParam(uri, "image"))
            assertTrue(queryParam(uri, "image")!!.startsWith("https://"))
            assertFalse("Der private Sitzungsschluessel darf nicht im QR-Code stehen", uri.contains(pairing.clientSecretKeyHex))
        } finally {
            pairing.cancel()
        }
    }

    @Test
    fun newPairingKeepsMetadataButRenewsSessionAndSecret() = runBlocking {
        val id = TestIdentity()
        val first = id.signing.startNip46Pairing()
        val second = id.signing.startNip46Pairing()
        assertNotEquals(first.secret, second.secret)
        assertNotEquals(first.client.clientPubkeyHex, second.client.clientPubkeyHex)
        for (key in listOf("name", "url", "image")) assertEquals(queryParam(first.uri, key), queryParam(second.uri, key))
        first.cancel()
        second.cancel()
        assertEquals("Abgebrochene Kopplung darf keine Sitzung aktivieren", SigningMode.LOCAL, id.signing.getMode())
    }

    @Test
    fun disabledRelayIsNotInThePairingAddress() = runBlocking {
        val id = TestIdentity(mapOf("disabled_default_relays" to PrefValue.StringListValue(listOf("wss://relay.damus.io"))))
        val relays = id.signing.nip46PairingRelays()
        assertFalse(relays.contains("wss://relay.damus.io"))
        assertTrue(relays.isNotEmpty())
    }

    private val allDefaults = PrefValue.StringListValue(
        listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.nostr.band", "wss://nostr.einundzwanzig.space"),
    )

    @Test
    fun customRelaysAreUsed() = runBlocking {
        val id = TestIdentity(
            mapOf(
                "custom_relays" to PrefValue.StringListValue(listOf("wss://mein.relay.test")),
                "disabled_default_relays" to allDefaults,
            ),
        )
        assertEquals(listOf("wss://mein.relay.test"), id.signing.nip46PairingRelays())
    }

    @Test
    fun atMostThreeRelays() = runBlocking {
        assertTrue(TestIdentity().signing.nip46PairingRelays().size <= 3)
    }

    @Test
    fun fallbackWithoutAnyActiveRelay() = runBlocking {
        val id = TestIdentity(mapOf("disabled_default_relays" to allDefaults))
        assertEquals(listOf("wss://relay.damus.io", "wss://nos.lol"), id.signing.nip46PairingRelays())
    }

    @Test
    fun backupCarriesTheAddressButNoSessionKey() = runBlocking {
        val id = TestIdentity()
        assertNull(id.signing.nip46SessionForBackup())
        id.signing.restoreNip46(myNpub, bunkerUri, clientKey)
        val session = id.signing.nip46SessionForBackup()!!
        assertEquals(bunkerUri, session["bunker_uri"])
        assertFalse(session.containsKey("client_sk"))
        assertFalse(session.values.any { it == clientKey })
    }

    @Test
    fun restoreWithoutKeyKeepsIdentityButCannotSign() = runBlocking {
        val id = TestIdentity()
        id.signing.restoreNip46(myNpub, bunkerUri)
        assertEquals(SigningMode.NIP46, id.signing.getMode())
        assertEquals(myNpub, id.signing.npub())
        assertTrue(id.signing.isExternalSigner())
        assertFalse(id.signing.canSign())
    }

    @Test
    fun anOldSessionKeyIsRemovedOnRestore() = runBlocking {
        val id = TestIdentity()
        id.signing.restoreNip46(myNpub, bunkerUri, clientKey)
        assertEquals(clientKey, id.secure.entries.value[IdentityKeys.NIP46_CLIENT_SK])
        id.signing.restoreNip46(myNpub, bunkerUri)
        assertNull(id.secure.entries.value[IdentityKeys.NIP46_CLIENT_SK])
        assertFalse(id.signing.canSign())
    }

    @Test
    fun failedPrefsWriteRestoresThePreviousSessionKey() = runBlocking {
        val id = TestIdentity()
        id.signing.restoreNip46(myNpub, bunkerUri, clientKey)
        val otherUri = bunkerUri.replace("relay.damus.io", "nos.lol")
        id.prefs.failingKeys = setOf(IdentityKeys.NIP46_BUNKER_URI)

        // New key: rolled back to the old one, prefs still describe the old session.
        assertTrue(runCatching { id.signing.restoreNip46(myNpub, otherUri, "2".repeat(64)) }.exceptionOrNull() is IdentityStorageException)
        assertEquals(clientKey, id.secure.entries.value[IdentityKeys.NIP46_CLIENT_SK])
        assertEquals(bunkerUri, id.prefs.getString(IdentityKeys.NIP46_BUNKER_URI))

        // No key (delete): the old key comes back as well.
        assertTrue(runCatching { id.signing.restoreNip46(myNpub, otherUri) }.exceptionOrNull() is IdentityStorageException)
        assertEquals(clientKey, id.secure.entries.value[IdentityKeys.NIP46_CLIENT_SK])
        assertTrue(id.signing.canSign())
    }

    @Test
    fun sessionAliveIsFalseOutsideNip46AndWithoutReachableRelays() = runBlocking {
        val id = TestIdentity()
        assertFalse(id.signing.nip46SessionAlive())
        id.signing.restoreNip46(myNpub, bunkerUri, clientKey)
        // The test opener is offline: ping fails → false.
        assertFalse(id.signing.nip46SessionAlive())
    }

    @Test
    fun connectBunkerWithGarbageIsAnError() = runBlocking {
        val id = TestIdentity()
        val result = id.signing.connectNip46Bunker("https://example.com")
        assertTrue(result is Nip46ConnectResult.Error)
        assertEquals(SigningMode.LOCAL, id.signing.getMode())
    }

    @Test
    fun signerReadyFiresOnRestoreAndLocalMode() = runBlocking {
        val id = TestIdentity()
        val events = java.util.concurrent.CopyOnWriteArrayList<Unit>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val job = scope.launch { id.signing.signerReady.collect { events += it } }
        id.signing.restoreNip46(myNpub, bunkerUri)
        id.signing.useLocalMode()
        job.cancel()
        assertEquals(2, events.size)
    }
}
