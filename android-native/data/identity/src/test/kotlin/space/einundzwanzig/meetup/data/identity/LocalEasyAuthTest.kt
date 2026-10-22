package space.einundzwanzig.meetup.data.identity

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import space.einundzwanzig.meetup.core.crypto.KeySecurity
import space.einundzwanzig.meetup.core.crypto.Nip49
import space.einundzwanzig.meetup.core.crypto.Nip49Exception
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.data.identity.easyauth.LocalEasyAuth
import space.einundzwanzig.meetup.data.identity.keys.IdentityKeys
import space.einundzwanzig.meetup.data.identity.passkey.PasskeyPrfResult

/** Port of `local_easy_auth_test.dart` (scrypt log_n 16 runs for real, like in Dart). */
class LocalEasyAuthTest {

    private fun identity() = TestIdentity(mapOf(IdentityKeys.SECURE_MIGRATION_DONE to PrefValue.BoolValue(true)))

    @Test
    fun registerCreatesKeyNicknameAndPasswordWrap() = runBlocking {
        val id = identity()
        id.easyAuth.register("Ralph", "test-password-ok")

        assertTrue(id.keyStore.hasKey())
        val wrap = id.vault.getPasswordWrap()
        assertNotNull(wrap)
        assertTrue(wrap!!.startsWith("ncryptsec1"))
        assertEquals(KeySecurity.UNKNOWN, Nip49.keySecurityOf(wrap))
        assertEquals(16, Nip49.debugDecodePayload(wrap)[1].toInt())

        val user = id.profiles.load()
        assertEquals("Ralph", user.nickname)
        assertTrue(user.isOnboarded)
        assertTrue(user.isNostrVerified)
        assertFalse(user.isReviewDemo)
        assertEquals(SigningMode.LOCAL, id.signing.getMode())

        val privBefore = id.keyStore.getPrivHex()
        id.keyStore.deleteKeys()
        assertFalse(id.keyStore.hasKey())
        id.easyAuth.unlockWithPassword("test-password-ok")
        assertEquals(privBefore, id.keyStore.getPrivHex())
    }

    @Test
    fun registerRejectsAnonAndShortPasswords() = runBlocking {
        val id = identity()
        for ((name, pw) in listOf("Anon" to "longenough", "Ok" to "short", "   " to "longenough")) {
            try {
                id.easyAuth.register(name, pw)
                fail("expected LocalEasyAuthException for $name/$pw")
            } catch (e: LocalEasyAuthException) {
                // expected
            }
        }
        assertFalse(id.keyStore.hasKey())
    }

    @Test
    fun importNcryptsecLoadsTheSameKey() = runBlocking {
        val id = identity()
        val keys = id.keys.generateKeyPair()
        val priv = id.keyStore.getPrivHex()!!
        val wrap = Nip49.encrypt(priv, "pw-import-test", logN = 8)
        id.keyStore.deleteKeys()
        val imported = id.keys.importNcryptsec(wrap, "pw-import-test")
        assertEquals(keys.npub, imported.npub)
    }

    @Test
    fun registerClearsAnOldPasskeyWrapAndBindsTheNpub() = runBlocking {
        val id = identity()
        id.vault.savePasskeyWrap("ncryptsec1-alt", "cred-alt")
        id.easyAuth.register("Neu", "test-password-ok")
        assertFalse(id.vault.hasPasskeyWrap())
        val npub = id.keyStore.getNpub()
        assertNotNull(npub)
        assertTrue(id.vault.passwordWrapMatchesNpub(npub!!))
    }

    @Test
    fun unlockErrors() = runBlocking {
        val id = identity()
        try {
            id.easyAuth.unlockWithPassword("egal")
            fail()
        } catch (e: LocalEasyAuthException) {
            assertEquals("Kein gespeicherter Schlüssel.", e.message)
        }
        id.vault.savePasswordWrap(Nip49.encrypt("aa".repeat(32), "richtig", logN = 8), "npub1x")
        try {
            id.easyAuth.unlockWithPassword("falsch")
            fail()
        } catch (e: Nip49Exception) {
            assertEquals("Falsches Passwort — oder der Schlüssel ist beschädigt.", e.message)
        }
        assertFalse(id.keyStore.hasKey())
    }

    @Test
    fun splitPasskeyStepsWrapAndUnlockWithoutAnActivity() = runBlocking {
        val id = identity()
        assertEquals(8, LocalEasyAuth.MIN_PASSWORD_LENGTH)
        try {
            id.easyAuth.passkeyRegistration("Ralph")
            fail()
        } catch (e: LocalEasyAuthException) {
            assertEquals("Kein lokaler Schlüssel vorhanden.", e.message)
        }
        try {
            id.easyAuth.passkeyCredentialIds()
            fail()
        } catch (e: LocalEasyAuthException) {
            assertEquals("Kein Passkey hinterlegt.", e.message)
        }

        id.easyAuth.register("Ralph", "test-password-ok")
        val npub = id.keyStore.getNpub()!!
        val req = id.easyAuth.passkeyRegistration("  ")
        assertEquals(Nip19.decodeNpub(npub), req.userIdHex)
        assertEquals("21meetup", req.userName)

        val prf = PasskeyPrfResult("cred-1", "ab".repeat(32))
        id.easyAuth.savePasskeyWrap(req, prf)
        val wrap = id.vault.getPasskeyWrap()!!
        assertEquals(8, Nip49.debugDecodePayload(wrap)[1].toInt())
        assertEquals(listOf("cred-1"), id.easyAuth.passkeyCredentialIds())

        val priv = id.keyStore.getPrivHex()
        id.keyStore.deleteKeys()
        try {
            id.easyAuth.unlockWithPrfSecret(PasskeyPrfResult("cred-1", "cd".repeat(32)))
            fail()
        } catch (e: Nip49Exception) {
            // wrong PRF secret
        }
        id.easyAuth.unlockWithPrfSecret(prf)
        assertEquals(priv, id.keyStore.getPrivHex())
        assertEquals(SigningMode.LOCAL, id.signing.getMode())
    }

    @Test
    fun passkeyWrapIsRefusedWhenTheKeyChangedMeanwhile() = runBlocking {
        val id = identity()
        id.easyAuth.register("Ralph", "test-password-ok")
        val req = id.easyAuth.passkeyRegistration("Ralph")
        // A new identity was created while the passkey dialog was open.
        id.easyAuth.register("Ralph", "another-password")
        try {
            id.easyAuth.savePasskeyWrap(req, PasskeyPrfResult("cred-1", "ab".repeat(32)))
            fail()
        } catch (e: LocalEasyAuthException) {
            assertEquals("Kein lokaler Schlüssel vorhanden.", e.message)
        }
        assertFalse(id.vault.hasPasskeyWrap())
    }
}
