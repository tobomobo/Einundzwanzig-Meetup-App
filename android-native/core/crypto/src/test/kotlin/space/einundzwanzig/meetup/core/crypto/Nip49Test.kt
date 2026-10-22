package space.einundzwanzig.meetup.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of test/nip49_test.dart plus deterministic vectors produced with the Dart stack of the app
 * (pointycastle scrypt + package:cryptography XChaCha20-Poly1305 + unorm NFKC, fixed salt/nonce)
 * and the XChaCha20-Poly1305 / HChaCha20 vectors of draft-irtf-cfrg-xchacha.
 */
class Nip49Test {
    private val official = Fixtures.identity.obj("nip49").obj("official")
    private val nfkc = Fixtures.identity.obj("nip49").obj("nfkc")

    private val logN = 8
    private val privkey = "a".repeat(64)

    // ---- offizieller Testvektor ----

    @Test
    fun officialVectorDecrypts() {
        assertEquals(official.str("privkeyHex"), Nip49.decrypt(official.str("ncryptsec"), official.str("password")))
        val payload = Nip49.debugDecodePayload(official.str("ncryptsec"))
        assertEquals(91, payload.size)
        assertEquals(2, payload[0].toInt())
        assertEquals(16, payload[1].toInt())
        assertEquals(KeySecurity.INSECURE, Nip49.keySecurityOf(official.str("ncryptsec")))
        assertEquals(162, official.str("ncryptsec").length)
        // Re-encoding the decoded payload is the identity.
        assertEquals(official.str("ncryptsec"), Nip49.debugEncodePayload(payload))
    }

    @Test
    fun officialScryptKey() {
        val payload = Nip49.debugDecodePayload(official.str("ncryptsec"))
        assertEquals(
            official.str("_derived_scrypt_key_hex"),
            Nip49.scrypt("nostr", payload.copyOfRange(2, 18), 16).toHex(),
        )
    }

    @Test
    fun wrongPasswordIsRejected() {
        assertThrows(Nip49Exception::class.java) { Nip49.decrypt(official.str("ncryptsec"), "falsch") }
    }

    @Test
    fun nfkcComposedAndNormalizedPasswordsDecryptAlike() {
        val composed = "\u212B\u2126\u1E9B\u0323"
        val normalized = "\u00C5\u03A9\u1E69"
        assertEquals(nfkc.str("passwordComposed"), composed)
        assertEquals(nfkc.str("passwordNormalized"), normalized)
        assertEquals(
            nfkc.str("normalizedUtf8Hex"),
            DartUtf8.encode(java.text.Normalizer.normalize(composed, java.text.Normalizer.Form.NFKC)).toHex(),
        )
        assertEquals(nfkc.str("privkeyHex"), Nip49.decrypt(nfkc.str("ncryptsec"), composed))
        assertEquals(nfkc.str("privkeyHex"), Nip49.decrypt(nfkc.str("ncryptsec"), normalized))
    }

    // ---- eigener Export laesst sich wieder lesen ----

    @Test
    fun roundTrip() {
        val enc = Nip49.encrypt(privkey, "geheim", logN = logN)
        assertTrue(enc.startsWith("ncryptsec1"))
        assertEquals(privkey, Nip49.decrypt(enc, "geheim"))
    }

    @Test
    fun twoExportsDifferButDecryptAlike() {
        val a = Nip49.encrypt(privkey, "geheim", logN = logN)
        val b = Nip49.encrypt(privkey, "geheim", logN = logN)
        assertNotEquals(a, b)
        assertEquals(Nip49.decrypt(a, "geheim"), Nip49.decrypt(b, "geheim"))
    }

    @Test
    fun keySecurityIsReadableWithoutPassword() {
        assertEquals(
            KeySecurity.INSECURE,
            Nip49.keySecurityOf(Nip49.encrypt(privkey, "geheim", logN = logN, keySecurity = KeySecurity.INSECURE)),
        )
        assertEquals(
            KeySecurity.SECURE,
            Nip49.keySecurityOf(Nip49.encrypt(privkey, "geheim", logN = logN, keySecurity = KeySecurity.SECURE)),
        )
        assertEquals(KeySecurity.UNKNOWN, Nip49.keySecurityOf(Nip49.encrypt(privkey, "geheim", logN = logN)))
        assertEquals(KeySecurity.UNKNOWN, KeySecurity.fromByte(7))
    }

    @Test
    fun keySecurityIsBoundAsAad() {
        val enc = Nip49.encrypt(privkey, "geheim", logN = logN, keySecurity = KeySecurity.INSECURE)
        val payload = Nip49.debugDecodePayload(enc)
        payload[42] = if (payload[42].toInt() == 0) 1 else 0
        val tampered = Nip49.debugEncodePayload(payload)
        assertNotEquals(enc, tampered)
        assertThrows(Nip49Exception::class.java) { Nip49.decrypt(tampered, "geheim") }
    }

    @Test
    fun unicodePasswordSurvivesRoundTrip() {
        val pw = "Grüß-Ähre-😀"
        assertEquals(privkey, Nip49.decrypt(Nip49.encrypt(privkey, pw, logN = logN), pw))
    }

    // ---- Eingaben werden geprueft ----

    @Test
    fun emptyPassword() {
        assertThrows(Nip49Exception::class.java) { Nip49.encrypt(privkey, "", logN = logN) }
    }

    @Test
    fun privateKeyOfWrongLength() {
        assertThrows(Nip49Exception::class.java) { Nip49.encrypt("ab".repeat(16), "geheim", logN = logN) }
    }

    @Test
    fun privateKeyNotHex() {
        assertThrows(Nip49Exception::class.java) { Nip49.encrypt("zz".repeat(32), "geheim", logN = logN) }
    }

    @Test
    fun logNOutOfRangeOnEncrypt() {
        assertThrows(Nip49Exception::class.java) { Nip49.encrypt(privkey, "geheim", logN = 0) }
        assertThrows(Nip49Exception::class.java) { Nip49.encrypt(privkey, "geheim", logN = 23) }
    }

    @Test
    fun wrongPrefix() {
        assertThrows(Nip49Exception::class.java) { Nip49.decrypt("nsec1abcdef", "geheim") }
    }

    @Test
    fun truncatedNcryptsec() {
        assertThrows(Nip49Exception::class.java) {
            Nip49.decrypt(official.str("ncryptsec").substring(0, 60), official.str("password"))
        }
    }

    @Test
    fun forgedLogNIsRejectedBeforeScrypt() {
        val enc = Nip49.encrypt(privkey, "geheim", logN = logN)
        val payload = Nip49.debugDecodePayload(enc)
        payload[1] = 255.toByte()
        val tampered = Nip49.debugEncodePayload(payload)
        // Would need 128 * 2^255 * 8 bytes if scrypt started; must fail fast.
        assertThrows(Nip49Exception::class.java) { Nip49.decrypt(tampered, "geheim") }
    }

    @Test
    fun surroundingWhitespaceAndUppercaseAreAccepted() {
        val enc = Nip49.encrypt(privkey, "geheim", logN = logN)
        assertEquals(privkey, Nip49.decrypt("  $enc\n", "geheim"))
        assertEquals(privkey, Nip49.decrypt(enc.uppercase(), "geheim"))
    }

    // ---- Dart-generated deterministic vectors (fixed salt/nonce) ----

    private data class DartVector(
        val privkeyHex: String,
        val password: String,
        val logN: Int,
        val keySecurity: KeySecurity,
        val saltHex: String,
        val nonceHex: String,
        val scryptKeyHex: String,
        val ncryptsec: String,
    )

    private val dartVectors = listOf(
        DartVector(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "geheim", 8, KeySecurity.UNKNOWN,
            "01080f161d242b323940474e555c636a", "4144474a4d505356595c5f6265686b6e7174777a7d808386",
            "37b30ebda34f31c6ebe06e8ec1d0276a67bb3d41e59d3cbbfa955e79869bae27",
            "ncryptsec1qgyqzzq0zcwjg2ej89qywnj4t33k5s2yga9y65zn2ev4chmzv45xkmn3w3mh5lvqswrqymh6m4w34wqz9wktz8m7yzws55d7xmp3xsfn2gpyzsfdcgzvy2sffcjme58qypzxcqcm7y5taa3xngyt2vg5",
        ),
        DartVector(
            "3501454135014541350145413501453fefb02227e449e57cf4d3a3ce05378683", "Grüß-Ähre-😀", 4, KeySecurity.INSECURE,
            "020910171e252c333a41484f565d646b", "4245484b4e5154575a5d606366696c6f7275787b7e818487",
            "414a708938af43d93172674f90b366d25a880965792b4b0660411c1af84dea47",
            "ncryptsec1qgzqyzgszu0z2tpn8fq5sn6kt4jxksj9fp95u5252ad96crrve5kcmmjw4u8kl5psjrspql0rm88d5lh4cx37mzl6c7f8puj95cuh2mf6lep309qjfzcygf0ydte2g3njcdru4ka024mxflf0ymn2zdx",
        ),
        DartVector(
            "0000000000000000000000000000000000000000000000000000000000000001", "\u212B\u2126\u1E9B\u0323", 6,
            KeySecurity.SECURE,
            "030a11181f262d343b424950575e656c", "4346494c4f5255585b5e6164676a6d707376797c7f828588",
            "b1eabb9c044364cfc52dfdf4ef8a8b820a06d2d9d8f80111abc8f16ff3ae6a33",
            "ncryptsec1qgrqxzs3rq0jvtf58dpyj5zhtejkcs6xf9xy75j4tpd4uctyva4x6urnweuhcluzskyqrp8fuwnhz7m88lt2m3kaeu6wg2de7r28q7rl406s4gddqqmsmvj37qf7rpw8lwatqedxszygxhxahvh2faj3",
        ),
    )

    @Test
    fun dartGeneratedVectorsEncryptByteIdentical() {
        for (v in dartVectors) {
            assertEquals(v.scryptKeyHex, Nip49.scrypt(v.password, v.saltHex.hexToBytes(), v.logN).toHex())
            assertEquals(
                v.ncryptsec,
                Nip49.encrypt(
                    v.privkeyHex, v.password, logN = v.logN, keySecurity = v.keySecurity,
                    salt = v.saltHex.hexToBytes(), nonce = v.nonceHex.hexToBytes(),
                ),
            )
            assertEquals(v.privkeyHex, Nip49.decrypt(v.ncryptsec, v.password))
            assertEquals(v.keySecurity, Nip49.keySecurityOf(v.ncryptsec))
        }
    }

    // ---- XChaCha20-Poly1305 building blocks ----

    @Test
    fun hChaCha20DraftVector() {
        // draft-irtf-cfrg-xchacha-03 §2.2.1
        val key = ByteArray(32) { it.toByte() }
        val nonce = "000000090000004a0000000031415927".hexToBytes()
        assertEquals(
            "82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc",
            XChaCha20Poly1305.hChaCha20(key, nonce).toHex(),
        )
    }

    @Test
    fun xChaCha20Poly1305DraftVector() {
        // draft-irtf-cfrg-xchacha-03 §A.3.1, re-derived with package:cryptography.
        val key = "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f".hexToBytes()
        val nonce = "404142434445464748494a4b4c4d4e4f5051525354555657".hexToBytes()
        val aad = "50515253c0c1c2c3c4c5c6c7".hexToBytes()
        val plaintext = DartUtf8.encode(
            "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.",
        )
        val expected = (
            "bd6d179d3e83d43b9576579493c0e939572a1700252bfaccbed2902c21396cbb731c7f1b0b4aa6440bf3a82f4eda7e39" +
                "ae64c6708c54c216cb96b72e1213b4522f8c9ba40db5d945b11b69b982c1bb9e3f3fac2bc369488f76b2383565d3fff9" +
                "21f9664c97637da9768812f615c68b13b52e" + "c0875924c1c7987947deafd8780acf49"
            )
        val sealed = XChaCha20Poly1305.seal(key, nonce, plaintext, aad)
        assertEquals(expected, sealed.toHex())
        assertArrayEquals(plaintext, XChaCha20Poly1305.open(key, nonce, sealed, aad))
        val tampered = sealed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(AeadAuthenticationException::class.java) { XChaCha20Poly1305.open(key, nonce, tampered, aad) }
        assertThrows(AeadAuthenticationException::class.java) { XChaCha20Poly1305.open(key, nonce, sealed, ByteArray(0)) }
    }

    // ---- crypto part of test/local_easy_auth_test.dart ----

    @Test
    fun easyAuthRegisterThenUnlockRestoresTheSameKey() {
        val keys = KeyPair.generate()
        val wrap = Nip49.encrypt(keys.privateKeyHex, "test-password-ok", logN = logN, keySecurity = KeySecurity.UNKNOWN)
        assertTrue(wrap.startsWith("ncryptsec1"))
        val restored = KeyPair.fromPrivateKeyHex(Nip49.decrypt(wrap, "test-password-ok"))
        assertEquals(keys.privateKeyHex, restored.privateKeyHex)
        assertEquals(keys.npub, restored.npub)
    }

    @Test
    fun passkeyWrapUsesPrfHexAsPassword() {
        // PasskeyPrfService: PRF output (base64url) → lowercase hex → NIP-49 password, log_n 8.
        val prfOutput = ByteArray(32) { (it * 3).toByte() }
        val prfHex = DartBase64.decodeUrlLenient(DartBase64.encodeUrlNoPadding(prfOutput)).toHex()
        assertEquals(64, prfHex.length)
        val wrap = Nip49.encrypt(privkey, prfHex, logN = 8)
        assertEquals(privkey, Nip49.decrypt(wrap, prfHex))
    }
}
