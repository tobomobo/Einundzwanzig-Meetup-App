package space.einundzwanzig.meetup.data.identity

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.data.identity.keys.IdentityKeys
import space.einundzwanzig.meetup.data.identity.signer.Nip07NostrSigner

/** Port of `signing_mode_test.dart` (Android: Amber is supported) and `recommended_existing_path_test.dart`. */
class SigningModeTest {

    private val npub = "npub1lf0rga7j66uj6enae2mxezamz5nsz3vechhvmh25tcarn4u8qf5q534jzc"

    private fun withMode(mode: String?) =
        TestIdentity(if (mode == null) emptyMap() else mapOf(IdentityKeys.SIGNING_MODE to PrefValue.StringValue(mode)))

    @Test
    fun amberStaysAmberOnAndroid() = runBlocking {
        val id = withMode("amber")
        assertEquals(SigningMode.AMBER, id.signing.getMode())
        assertTrue(id.signing.getMode() != SigningMode.NIP07)
    }

    @Test
    fun localStaysLocal() = runBlocking {
        assertEquals(SigningMode.LOCAL, withMode("local").signing.getMode())
    }

    @Test
    fun missingValueIsLocal() = runBlocking {
        assertEquals(SigningMode.LOCAL, withMode(null).signing.getMode())
    }

    @Test
    fun unknownValueFallsBackToLocal() = runBlocking {
        assertEquals(SigningMode.LOCAL, withMode("irgendwas-neues").signing.getMode())
    }

    @Test
    fun nip07IsStoredAndReadBack() = runBlocking {
        val id = withMode(null)
        id.signing.restoreNip07(npub)
        assertEquals(SigningMode.NIP07, id.signing.getMode())
        assertEquals("nip07", id.prefs.getString(IdentityKeys.SIGNING_MODE))
        assertEquals(npub, id.signing.npub())
    }

    @Test
    fun nip07CannotSignWithoutExtension() = runBlocking {
        val id = withMode(null)
        id.signing.restoreNip07(npub)
        assertFalse(id.signing.canSign())
        try {
            id.signing.signEvent(1, emptyList(), "x")
            throw AssertionError("expected SigningException")
        } catch (e: SigningException) {
            assertEquals(Nip07NostrSigner.WEB_ONLY, e.message)
        }
    }

    @Test
    fun nip07DisconnectResetsToLocalAndCleansUp() = runBlocking {
        val id = withMode(null)
        id.signing.restoreNip07(npub)
        id.signing.disconnectNip07()
        assertEquals(SigningMode.LOCAL, id.signing.getMode())
        assertNull(id.prefs.getString(IdentityKeys.NIP07_PUBKEY_HEX))
        assertNull(id.prefs.getString(IdentityKeys.NIP07_NPUB))
    }

    @Test
    fun nip07ConnectWithoutExtensionIsMissing() = runBlocking {
        assertEquals(Nip07ConnectResult.Missing, withMode(null).signing.connectNip07())
        assertFalse(withMode(null).signing.nip07Available())
    }

    @Test
    fun isExternalSignerForAmberNip07Nip46NotLocal() = runBlocking {
        assertTrue(withMode("amber").signing.isExternalSigner())
        assertTrue(withMode("nip07").signing.isExternalSigner())
        assertTrue(withMode("nip46").signing.isExternalSigner())
        assertEquals(SigningMode.NIP46, withMode("nip46").signing.getMode())
        assertFalse(withMode("local").signing.isExternalSigner())
    }

    @Test
    fun storedValuesAreStable() {
        assertEquals(listOf("local", "amber", "nip07", "nip46"), SigningMode.entries.map { it.stored })
    }

    // recommended_existing_path_test.dart (Android branch)

    @Test
    fun recommendedPathIsBunkerWithoutAmber() = runBlocking {
        assertEquals(RecommendedExistingPath.BUNKER, TestIdentity(nip55 = FakeNip55(installed = false)).signing.recommendedExistingPath())
    }

    @Test
    fun recommendedPathIsAmberWithAmber() = runBlocking {
        assertEquals(RecommendedExistingPath.AMBER, TestIdentity(nip55 = FakeNip55(installed = true)).signing.recommendedExistingPath())
    }

    // Amber mode (spec 01 §8.1)

    @Test
    fun amberConnectRestoreAndDisconnect() = runBlocking {
        val hex = "fa5e3477d2d6b92d667dcab66c8bbb1527014599c5eecddd545e3a39d7870268"
        val id = TestIdentity(nip55 = FakeNip55(installed = true, publicKey = { npub }))
        val result = id.signing.connectAmber()
        assertEquals(AmberConnectResult.Success(hex, npub), result)
        assertEquals(SigningMode.AMBER, id.signing.getMode())
        assertTrue(id.signing.canSign())
        assertEquals(hex, id.signing.pubkeyHex())
        id.signing.disconnectAmber()
        assertEquals(SigningMode.LOCAL, id.signing.getMode())
        assertNull(id.prefs.getString(IdentityKeys.AMBER_PUBKEY_HEX))
        id.signing.restoreAmber(npub)
        assertEquals(npub, id.signing.npub())
    }

    @Test
    fun amberMissingAndCancelled() = runBlocking {
        assertEquals(AmberConnectResult.Missing, TestIdentity(nip55 = FakeNip55(installed = false)).signing.connectAmber())
        val cancelled = TestIdentity(nip55 = FakeNip55(installed = true, publicKey = { "  " }))
        assertEquals(AmberConnectResult.Cancelled, cancelled.signing.connectAmber())
        assertEquals(SigningMode.LOCAL, cancelled.signing.getMode())
    }

    @Test
    fun amberModeWithoutKeyThrows() = runBlocking {
        val id = withMode("amber")
        try {
            id.signing.signEvent(1, emptyList(), "x")
            throw AssertionError("expected SigningException")
        } catch (e: SigningException) {
            assertEquals("Amber-Modus aktiv, aber kein verbundener Schlüssel. Bitte Amber erneut verbinden.", e.message)
        }
        assertFalse(id.signing.canSign())
    }

    @Test
    fun connectingASignerNeverDeletesTheLocalKey() = runBlocking {
        val id = TestIdentity(nip55 = FakeNip55(installed = true, publicKey = { npub }))
        id.keys.generateKeyPair()
        id.signing.connectAmber()
        assertTrue(id.keyStore.hasKey())
    }
}
