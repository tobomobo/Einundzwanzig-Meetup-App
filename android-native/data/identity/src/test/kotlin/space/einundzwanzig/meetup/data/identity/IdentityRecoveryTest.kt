package space.einundzwanzig.meetup.data.identity

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.crypto.Nip49
import space.einundzwanzig.meetup.data.identity.keys.IdentityKeys
import space.einundzwanzig.meetup.data.identity.keys.RecoverySource

/** Recovery copies of the Flutter migration (spec 14 §4.8) and their removal by the full reset. */
class IdentityRecoveryTest {

    private val old = KeyPair.generate()
    private val oldNsec = Nip19.encodeNsec(old.privateKeyHex)
    private val oldNpub = old.npub

    @Test
    fun fullResetDeletesEveryIdentityRecoveryCopyButKeepsBadgeKeys() = runBlocking {
        val id = TestIdentity(
            secureInit = mapOf(
                "legacy_esp/nostr_nsec_key" to oldNsec,
                "legacy_esp/nostr_npub_key" to oldNpub,
                "legacy_esp/nostr_priv_hex" to old.privateKeyHex,
                "legacy_backup/easyauth_password_ncryptsec" to "ncryptsec1-x",
                "legacy_backup/easyauth_password_wrap_npub" to oldNpub,
                "legacy_recovered/nip46_client_sk" to "1".repeat(64),
                "legacy_recovered/easyauth_passkey_ncryptsec" to "ncryptsec1-y",
                "legacy_esp/badge_encryption_key" to "ab".repeat(32),
            ),
        )
        id.keys.generateKeyPair()
        // An entry that cannot be read is not listed by readAll, but still deleted.
        id.secure.failRead = { it == "legacy_esp/nostr_nsec_key" }

        assertTrue(id.identityRepo.wipeAll())

        assertEquals(mapOf("legacy_esp/badge_encryption_key" to "ab".repeat(32)), id.secure.entries.value)
    }

    @Test
    fun plainKeyCopyIsACandidateThatCanBeExportedAndAdopted() = runBlocking {
        val id = TestIdentity(
            secureInit = mapOf(
                "legacy_esp/nostr_nsec_key" to oldNsec,
                "legacy_esp/nostr_npub_key" to oldNpub,
                "legacy_esp/badge_encryption_key" to "ab".repeat(32),
            ),
        )
        val current = id.keys.generateKeyPair()

        val candidate = id.recovery.candidates().single()
        assertEquals(RecoverySource.ESP, candidate.source)
        assertEquals(oldNpub, candidate.npub)
        assertTrue(candidate.hasPrivateKey)
        assertFalse(candidate.needsPassword)
        assertFalse(candidate.toString().contains("nsec1"))
        assertEquals(oldNsec, id.recovery.exportNsec(candidate))
        assertEquals(current.npub, id.keyStore.getNpub())

        val adopted = id.recovery.adopt(candidate)
        assertEquals(oldNpub, adopted.npub)
        assertEquals(oldNpub, id.keyStore.getNpub())
        assertEquals(old.privateKeyHex, id.keyStore.getPrivHex())
        assertNull(id.secure.entries.value["legacy_esp/nostr_nsec_key"])
        assertNull(id.secure.entries.value["legacy_esp/nostr_npub_key"])
        assertEquals("ab".repeat(32), id.secure.entries.value["legacy_esp/badge_encryption_key"])
        assertTrue(id.recovery.candidates().isEmpty())
    }

    @Test
    fun passwordWrapOnlyNeedsThePassword() = runBlocking {
        val ncryptsec = Nip49.encrypt(old.privateKeyHex, "geheim123", logN = 8)
        val id = TestIdentity(
            secureInit = mapOf(
                "legacy_backup/easyauth_password_ncryptsec" to ncryptsec,
                "legacy_backup/easyauth_password_wrap_npub" to oldNpub,
            ),
        )
        val candidate = id.recovery.candidates().single()
        assertEquals(RecoverySource.BACKUP, candidate.source)
        assertEquals(oldNpub, candidate.npub)
        assertTrue(candidate.needsPassword)
        assertTrue(runCatching { id.recovery.adopt(candidate) }.exceptionOrNull() is KeyFormatException)
        assertTrue(runCatching { id.recovery.exportNsec(candidate) }.exceptionOrNull() is KeyFormatException)
        assertEquals(oldNpub, id.recovery.adopt(candidate, "geheim123").npub)
        assertEquals(oldNpub, id.keyStore.getNpub())
    }

    @Test
    fun copyOfTheActiveIdentityIsNoCandidateAndDiscardRemovesCopies() = runBlocking {
        val id = TestIdentity(
            secureInit = mapOf(
                "legacy_recovered/nostr_nsec_key" to oldNsec,
                IdentityKeys.NSEC to oldNsec,
                IdentityKeys.NPUB to oldNpub,
                IdentityKeys.PRIV_HEX to old.privateKeyHex,
            ),
        )
        assertTrue(id.recovery.candidates().isEmpty())

        val other = KeyPair.generate()
        id.secure.write("legacy_esp/nostr_priv_hex", other.privateKeyHex)
        val candidate = id.recovery.candidates().single()
        assertEquals(other.npub, candidate.npub)
        id.recovery.discard(candidate)
        assertNull(id.secure.entries.value["legacy_esp/nostr_priv_hex"])
        assertEquals(oldNsec, id.secure.entries.value[IdentityKeys.NSEC])
    }
}
