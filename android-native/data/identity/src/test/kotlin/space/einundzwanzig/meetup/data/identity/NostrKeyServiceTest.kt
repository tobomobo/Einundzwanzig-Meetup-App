package space.einundzwanzig.meetup.data.identity

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.Keys
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.data.identity.keys.IdentityKeys

/** Key import rules (spec 01 §7.1), N3 consistency (stage_a_security_test) and the reset (§8.9). */
class NostrKeyServiceTest {

    @Test
    fun importNsecStoresNormalizedKeys() = runBlocking {
        val id = TestIdentity()
        val kp = KeyPair.generate()
        val keys = id.keys.importNsec("  ${kp.nsec}  ")
        assertEquals(kp.npub, keys.npub)
        assertEquals(kp.privateKeyHex, id.keyStore.getPrivHex())
        assertEquals(kp.nsec, id.keyStore.getNsec())
        assertEquals(kp.npub, id.keyStore.loadKeys()!!.npub)
    }

    @Test
    fun importNsecRejectsOtherInput() = runBlocking {
        val id = TestIdentity()
        try {
            id.keys.importNsec("npub1abc")
            fail()
        } catch (e: KeyFormatException) {
            assertEquals("Ungültiges Format. Der Key muss mit \"nsec1\" beginnen.", e.message)
        }
        try {
            id.keys.importNsec("nsec1qqqqqq")
            fail()
        } catch (e: KeyFormatException) {
            assertTrue(e.message, e.message.startsWith("Ungültiger nsec Key: "))
        }
        assertFalse(id.keyStore.hasKey())
    }

    @Test
    fun importPrivHexNormalizesCase() = runBlocking {
        val id = TestIdentity()
        val kp = KeyPair.generate()
        id.keys.importPrivHex(" ${kp.privateKeyHex.uppercase()} ")
        assertEquals(kp.privateKeyHex, id.keyStore.getPrivHex())
        try {
            id.keys.importPrivHex("zz")
            fail()
        } catch (e: KeyFormatException) {
            assertEquals("Ungültiger privater Schlüssel (hex).", e.message)
        }
    }

    @Test
    fun backupKeyConsistencyN3() {
        val key = KeyPair.generate()
        assertTrue(Keys.backupKeyConsistent(key.nsec, key.npub, key.privateKeyHex))
        assertFalse(Keys.backupKeyConsistent(key.nsec, KeyPair.generate().npub, key.privateKeyHex))
        assertFalse(Keys.backupKeyConsistent(key.nsec, key.npub, KeyPair.generate().privateKeyHex))
        assertFalse(Keys.backupKeyConsistent(key.nsec, key.npub, "zz-not-hex"))
    }

    @Test
    fun legacyPlaintextKeysAreMigratedOnce() = runBlocking {
        val kp = KeyPair.generate()
        val id = TestIdentity(
            mapOf(
                IdentityKeys.NSEC to PrefValue.StringValue(kp.nsec),
                IdentityKeys.NPUB to PrefValue.StringValue(kp.npub),
                IdentityKeys.PRIV_HEX to PrefValue.StringValue(kp.privateKeyHex),
            ),
        )
        assertTrue(id.keyStore.hasKey())
        assertEquals(kp.privateKeyHex, id.secure.entries.value[IdentityKeys.PRIV_HEX])
        assertNull(id.prefs.getString(IdentityKeys.NSEC))
        assertNull(id.prefs.getString(IdentityKeys.PRIV_HEX))
        assertEquals(true, id.prefs.getBool(IdentityKeys.SECURE_MIGRATION_DONE))
    }

    @Test
    fun migrationIsSkippedWhenAlreadyDone() = runBlocking {
        val id = TestIdentity(
            mapOf(
                IdentityKeys.SECURE_MIGRATION_DONE to PrefValue.BoolValue(true),
                IdentityKeys.NSEC to PrefValue.StringValue("nsec1stale"),
            ),
        )
        assertFalse(id.keyStore.hasKey())
        assertEquals("nsec1stale", id.prefs.getString(IdentityKeys.NSEC))
    }

    @Test
    fun failedSecureWriteKeepsThePlaintextForTheNextAttempt() = runBlocking {
        val kp = KeyPair.generate()
        val id = TestIdentity(mapOf(IdentityKeys.NSEC to PrefValue.StringValue(kp.nsec)))
        id.secure.failWrite = { true }
        try {
            id.keyStore.hasKey()
            fail()
        } catch (e: IdentityStorageException) {
            // expected
        }
        assertEquals(kp.nsec, id.prefs.getString(IdentityKeys.NSEC))
        id.secure.failWrite = { false }
        assertTrue(id.keyStore.hasKey())
    }

    @Test
    fun deleteKeysKeepsTheNip46SessionKey() = runBlocking {
        val id = TestIdentity()
        id.keys.generateKeyPair()
        id.keyStore.saveNip46ClientKey("1".repeat(64))
        id.keyStore.deleteKeys()
        assertFalse(id.keyStore.hasKey())
        assertNull(id.keyStore.loadKeys())
        assertEquals("1".repeat(64), id.keyStore.getNip46ClientKey())
    }

    @Test
    fun wipeAllRemovesKeysSessionAndVault() = runBlocking {
        val id = TestIdentity()
        id.keys.generateKeyPair()
        id.vault.savePasswordWrap("ncryptsec1-x", "npub1x")
        id.signing.restoreNip46(KeyPair.generate().npub, "bunker://${"ab".repeat(32)}?relay=wss://a.test", "1".repeat(64))
        val repo = id.identityRepo
        assertTrue(repo.wipeAll())
        assertTrue(id.secure.entries.value.isEmpty())
        assertEquals(SigningMode.LOCAL, id.signing.getMode())
    }
}
