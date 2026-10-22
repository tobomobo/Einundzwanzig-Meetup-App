package space.einundzwanzig.meetup.data.identity

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.data.identity.keys.IdentityKeys
import space.einundzwanzig.meetup.data.identity.passkey.PasskeyJson

/** Port of `local_key_vault_test.dart`. The web-host allow-list case is web-only (spec 01 §10.12). */
class LocalKeyVaultTest {

    private fun identity() = TestIdentity(mapOf(IdentityKeys.SECURE_MIGRATION_DONE to PrefValue.BoolValue(true)))

    @Test
    fun emptyVaultReportsNothing() = runBlocking {
        val v = identity().vault
        assertNull(v.getPasswordWrap())
        assertNull(v.getPasskeyWrap())
        assertNull(v.getPasskeyCredentialId())
        assertFalse(v.hasPasskeyWrap())
    }

    @Test
    fun passwordAndPasskeyWrapsAreSeparate() = runBlocking {
        val v = identity().vault
        v.savePasswordWrap("ncryptsec1-passwort", "npub1test")
        v.savePasskeyWrap("ncryptsec1-passkey", "cred-abc")
        assertEquals("ncryptsec1-passwort", v.getPasswordWrap())
        assertEquals("npub1test", v.getPasswordWrapNpub())
        assertEquals("ncryptsec1-passkey", v.getPasskeyWrap())
        assertEquals("cred-abc", v.getPasskeyCredentialId())
        assertTrue(v.hasPasskeyWrap())
    }

    @Test
    fun clearPasskeyWrapKeepsThePasswordWrap() = runBlocking {
        val v = identity().vault
        v.savePasswordWrap("ncryptsec1-passwort", "npub1test")
        v.savePasskeyWrap("ncryptsec1-passkey", "cred-abc")
        v.clearPasskeyWrap()
        assertEquals("ncryptsec1-passwort", v.getPasswordWrap())
        assertNull(v.getPasskeyWrap())
        assertNull(v.getPasskeyCredentialId())
        assertFalse(v.hasPasskeyWrap())
    }

    @Test
    fun clearAllDeletesEverythingIncludingTheBinding() = runBlocking {
        val v = identity().vault
        v.savePasswordWrap("ncryptsec1-passwort", "npub1test")
        v.savePasskeyWrap("ncryptsec1-passkey", "cred-abc")
        v.clearAll()
        assertNull(v.getPasswordWrap())
        assertNull(v.getPasswordWrapNpub())
        assertNull(v.getPasskeyWrap())
        assertNull(v.getPasskeyCredentialId())
    }

    @Test
    fun passwordWrapMatchesNpubNeedsTheRightBinding() = runBlocking {
        val v = identity().vault
        v.savePasswordWrap("ncryptsec1-passwort", "npub1alice")
        assertTrue(v.passwordWrapMatchesNpub("npub1alice"))
        assertFalse(v.passwordWrapMatchesNpub("npub1bob"))
    }

    @Test
    fun wrapWithoutBindingIsNoMatch() = runBlocking {
        val id = identity()
        id.secure.write(IdentityKeys.EASYAUTH_PASSWORD_NCRYPTSEC, "ncryptsec1-orphan")
        assertFalse(id.vault.passwordWrapMatchesNpub("npub1x"))
    }

    @Test
    fun hasPasskeyWrapNeedsWrapAndCredentialId() = runBlocking {
        val v = identity().vault
        v.savePasskeyWrap("ncryptsec1-passkey", "")
        assertEquals("ncryptsec1-passkey", v.getPasskeyWrap())
        assertFalse(v.hasPasskeyWrap())
    }

    @Test
    fun nativeRelyingPartyIsTheFixedDomain() {
        // Replacement for the web-only allow-list test: no shared host can become the RP id.
        assertEquals("einundzwanzig.space", PasskeyJson.RP_ID)
    }

    @Test
    fun unreadableWrapIsNotReportedAsMissing() = runBlocking {
        val id = identity()
        id.secure.write(IdentityKeys.EASYAUTH_PASSWORD_NCRYPTSEC, "ncryptsec1-x")
        id.secure.failRead = { it == IdentityKeys.EASYAUTH_PASSWORD_NCRYPTSEC }
        try {
            id.vault.getPasswordWrap()
            throw AssertionError("expected SecureStorageUnreadableException")
        } catch (e: SecureStorageUnreadableException) {
            assertEquals(IdentityKeys.EASYAUTH_PASSWORD_NCRYPTSEC, e.key)
        }
    }
}
