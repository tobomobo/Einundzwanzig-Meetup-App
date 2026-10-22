package space.einundzwanzig.meetup.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson

/**
 * Crypto-level cases of service tests whose remaining parts belong to other modules:
 * test/badge_security_test.dart (hex format checks, canonical JSON, compact badge signatures),
 * test/stage_a_security_test.dart (N3 backup key consistency) and
 * test/local_easy_auth_test.dart (`importNcryptsec` with the default log_n).
 */
class ServicePrimitivesTest {
    // ---- BadgeSecurity.isValidPubkeyHex / isValidSignatureHex ----

    @Test
    fun pubkeyHexFormat() {
        assertTrue(Hex.isHexOfLength(KeyPair.generate().publicKeyHex, 64))
        assertFalse(Hex.isHexOfLength("abcdef1234", 64))
        assertFalse(Hex.isHexOfLength("a".repeat(65), 64))
        assertFalse(Hex.isHexOfLength("g".repeat(64), 64))
        assertFalse(Hex.isHexOfLength("x" + "a".repeat(63), 64))
        assertFalse(Hex.isHexOfLength("", 64))
        assertTrue(Hex.isHexOfLength("A".repeat(64), 64))
        assertTrue(Hex.isHexOfLength("aAbBcCdDeEfF".repeat(5) + "aAbB", 64))
    }

    @Test
    fun signatureHexFormat() {
        assertTrue(Hex.isHexOfLength("a".repeat(128), 128))
        assertFalse(Hex.isHexOfLength("a".repeat(127), 128))
        assertFalse(Hex.isHexOfLength("a".repeat(129), 128))
        assertFalse(Hex.isHexOfLength("z".repeat(128), 128))
    }

    // ---- BadgeSecurity.canonicalJsonEncode ----

    @Test
    fun canonicalJson() {
        assertEquals("{\"a\":2,\"b\":4,\"m\":3,\"z\":1}", DartJson.canonicalJsonEncode(linkedMapOf("z" to 1, "a" to 2, "m" to 3, "b" to 4)))
        val a = DartJson.canonicalJsonEncode(linkedMapOf("x" to 1, "a" to 2, "c" to 3))
        val b = DartJson.canonicalJsonEncode(linkedMapOf("c" to 3, "x" to 1, "a" to 2))
        val c = DartJson.canonicalJsonEncode(linkedMapOf("a" to 2, "x" to 1, "c" to 3))
        assertEquals(a, b)
        assertEquals(b, c)
        assertEquals("{}", DartJson.canonicalJsonEncode(emptyMap()))
        assertEquals(
            "{\"a\":{\"nested\":true},\"b\":[1,2,3]}",
            DartJson.canonicalJsonEncode(linkedMapOf("b" to listOf(1, 2, 3), "a" to mapOf("nested" to true))),
        )
    }

    // ---- BadgeSecurity.verifyCompact (crypto part) ----

    private val organizer = KeyPair.generate()

    private fun compactBadge(meetupId: String = "aschaffenburg-de", block: Int = 875000, expiry: Long = 1_760_021_600): SignedNostrEvent {
        val content = DartJson.canonicalJsonEncode(linkedMapOf("v" to 2, "t" to "B", "m" to meetupId, "b" to block, "x" to expiry))
        return EventIds.sign(organizer, 1_760_000_000, 21000, listOf(listOf("t", "badge"), listOf("m", meetupId)), content)
    }

    /** verifyCompact rebuilds content and tags from the (possibly manipulated) payload fields. */
    private fun verify(signed: SignedNostrEvent, meetupId: String, block: Int, pubkey: String = signed.pubkey, sig: String = signed.sig): Boolean {
        val content = DartJson.canonicalJsonEncode(linkedMapOf("v" to 2, "t" to "B", "m" to meetupId, "b" to block, "x" to 1_760_021_600L))
        val tags = listOf(listOf("t", "badge"), listOf("m", meetupId))
        val id = EventIds.compute(pubkey, signed.createdAt, 21000, tags, content)
        return EventIds.isValid(id, pubkey, signed.createdAt, 21000, tags, content, sig)
    }

    @Test
    fun compactBadgeSignatures() {
        val badge = compactBadge()
        assertTrue(verify(badge, "aschaffenburg-de", 875000))
        assertFalse("manipulated meetup", verify(badge, "manipuliert-de", 875000))
        assertFalse("manipulated block", verify(badge, "aschaffenburg-de", 999999))
        assertFalse("other pubkey", verify(badge, "aschaffenburg-de", 875000, pubkey = KeyPair.generate().publicKeyHex))
        val flipped = (if (badge.sig[0] == 'a') "b" else "a") + badge.sig.substring(1)
        assertFalse("flipped sig", verify(badge, "aschaffenburg-de", 875000, sig = flipped))
    }

    // ---- BackupService.backupKeyConsistent (N3) ----

    @Test
    fun backupKeyConsistency() {
        val key = KeyPair.generate()
        val priv = key.privateKeyHex
        val npub = key.npub
        val nsec = key.nsec
        assertTrue(Keys.backupKeyConsistent(nsec, npub, priv))
        assertTrue(Keys.backupKeyConsistent(" $nsec ", " $npub\n", priv.uppercase()))
        assertFalse(Keys.backupKeyConsistent(nsec, KeyPair.generate().npub, priv))
        assertFalse(Keys.backupKeyConsistent(nsec, npub, KeyPair.generate().privateKeyHex))
        assertFalse(Keys.backupKeyConsistent(nsec, npub, "zz-not-hex"))
        assertFalse(Keys.backupKeyConsistent("nsec1kaputt", npub, priv))
    }

    // ---- local_easy_auth_test: importNcryptsec lädt denselben Key ----

    @Test
    fun importNcryptsecWithDefaultLogN() {
        val keys = KeyPair.generate()
        val wrap = Nip49.encrypt(keys.privateKeyHex, "pw-import-test")
        assertEquals(Nip49.DEFAULT_LOG_N, Nip49.debugDecodePayload(wrap)[1].toInt())
        val imported = KeyPair.fromPrivateKeyHex(Nip49.decrypt(wrap, "pw-import-test"))
        assertEquals(keys.npub, imported.npub)
    }
}
