package space.einundzwanzig.meetup.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SHA-256 / HMAC helpers against the hash vectors of spec fixtures 02 and 04 and Dart outputs. */
class HashesTest {
    @Test
    fun sha256Basics() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hexOfUtf8("abc"))
        assertEquals(
            Fixtures.backup.obj("enc_v1").str("key_hex_sha256_of_password"),
            Sha256.hexOfUtf8("korrekt pferd batterie klammer"),
        )
    }

    @Test
    fun proofHashesFromSpec02() {
        val p = Fixtures.badges.obj("proofs")
        assertEquals(p.str("claimProofId"), Sha256.hexOfUtf8(p.str("claimProofId_input")))
        assertEquals(p.str("proofId_fallback"), Sha256.hexOfUtf8(p.str("proofId_fallback_input")))
        assertEquals(p.str("verificationHash"), Sha256.hexOfUtf8(p.str("proofId_fallback_input")).substring(0, 16))
        assertEquals(p.str("reputationQr_meetupList_hash12"), Sha256.hexOfUtf8(p.str("reputationQr_meetupList_hash_input")).substring(0, 12))
    }

    @Test
    fun hashVectorsFromSpec04() {
        val h = Fixtures.trust.obj("hashes")
        assertEquals(h.obj("claimProofId").str("sha256_hex"), Sha256.hexOfUtf8(h.obj("claimProofId").str("input_utf8")))
        for (k in listOf("badgeProofV2_single", "badgeProofV2_two_bound_same_ts", "badgeProofV1_two_with_sigId_c64")) {
            assertEquals(k, h.obj(k).str("sha256_hex"), Sha256.hexOfUtf8(h.obj(k).str("chain")))
        }
        assertEquals(h.obj("proofId_fallback").str("sha256_hex"), Sha256.hexOfUtf8(h.obj("proofId_fallback").str("input_utf8")))
        for (c in h.arr("meetupListHash12")) {
            assertEquals(c.str("hex12"), Sha256.hexOfUtf8(c.str("input_utf8")).substring(0, 12))
            assertEquals(c.str("input_utf8"), "21meetup:" + c.str("meetupName"))
        }
        for (c in h.arr("publishContentHash16")) {
            assertEquals(c.str("hex16"), Sha256.hexOfUtf8(c.str("input")).substring(0, 16))
        }
    }

    @Test
    fun rollingQrNonceFromSpec02() {
        // RollingQrService._generateNonce: HMAC key = UTF-8 of the 64-char hex STRING.
        val r = Fixtures.badges.obj("rollingQr")
        val mac = HmacSha256.hexOfUtf8(r.str("seed_hex_string"), r.long("time_step").toString())
        assertEquals(r.str("hmac_sha256_hex"), mac)
        assertEquals(r.str("nonce"), mac.substring(0, 16))
    }

    @Test
    fun hmacEdgeKeysLikeDart() {
        // Dart: Hmac(sha256, []).convert(utf8.encode('abc'))
        assertEquals(
            "fd7adb152c05ef80dccf50a1fa4c05d5a3ec6da95575fc312ae7c5d091836351",
            HmacSha256.mac(ByteArray(0), DartUtf8.encode("abc")).toHex(),
        )
        // Dart: Hmac(sha256, List.filled(100, 0x61)) — keys longer than the block are hashed
        assertEquals(
            "c661b9f4ae9d1a9e9bd6ad956d01d63f91809f83039431a3a362ab665652adfe",
            HmacSha256.mac(ByteArray(100) { 0x61 }, DartUtf8.encode("abc")).toHex(),
        )
        // RFC 4231 test case 2
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            HmacSha256.hexOfUtf8("Jefe", "what do ya want for nothing?"),
        )
    }

    @Test
    fun randomHelpers() {
        assertEquals(64, CryptoRandom.hex(32).length)
        assertEquals(16, CryptoRandom.bytes(16).size)
        assertTrue(CryptoRandom.hex(32) != CryptoRandom.hex(32))
        assertTrue(constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertTrue(!constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 3)))
    }
}
