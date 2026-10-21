package space.einundzwanzig.meetup.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * AES layouts of the Dart app: badge blobs (`base64(iv12):base64(ct‖tag)`), backup envelopes
 * (`enc_v2` / `enc_v1`, 16-byte IV), flutter_secure_storage values (migration).
 */
class AesTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun gcmMatchesPackageEncrypt() {
        // Dart: Encrypter(AES(key 00..1f, mode: gcm)).encrypt('Grüße 🧡', iv: a0..).base64
        val cases = mapOf(
            12 to ("Grüße 🧡" to "oWq/kYZUZ5+S+iByBYrkSWUIMDsnm8epAoWdEw=="),
            16 to ("Grüße 🧡" to "bdHgANtSqFYzAbgQlTIa+/2YwKtNC8KSw07kIw=="),
        )
        for ((ivLen, pair) in cases) {
            val iv = ByteArray(ivLen) { (0xa0 + it).toByte() }
            assertEquals(pair.second, DartBase64.encode(AesGcm.encrypt(key, iv, DartUtf8.encode(pair.first))))
            assertEquals(pair.first, DartUtf8.decodeStrict(AesGcm.decrypt(key, iv, DartBase64.decode(pair.second))))
        }
        val empty12 = ByteArray(12) { (0xa0 + it).toByte() }
        val empty16 = ByteArray(16) { (0xa0 + it).toByte() }
        assertEquals("XGmWJa9Lk6D4IgoqYRnF0A==", DartBase64.encode(AesGcm.encrypt(key, empty12, ByteArray(0))))
        assertEquals("Ai+EHHArZrC+yBEfZ94iHQ==", DartBase64.encode(AesGcm.encrypt(key, empty16, ByteArray(0))))
    }

    @Test
    fun gcmRejectsWrongKeyAndTampering() {
        val iv = ByteArray(12)
        val sealed = AesGcm.encrypt(key, iv, "geheim".toByteArray())
        assertThrows(AeadAuthenticationException::class.java) { AesGcm.decrypt(ByteArray(32), iv, sealed) }
        val tampered = sealed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(AeadAuthenticationException::class.java) { AesGcm.decrypt(key, iv, tampered) }
        assertThrows(AeadAuthenticationException::class.java) { AesGcm.decrypt(key, iv, ByteArray(5)) }
    }

    @Test
    fun badgeBlobVectorFromSpec02() {
        val v = Fixtures.badges.obj("aesGcmBlob")
        val k = v.str("key_hex").hexToBytes()
        assertEquals(v.str("blob"), AesGcmBlob.seal(k, v.str("plaintext"), v.str("iv_hex").hexToBytes()))
        assertEquals(v.str("plaintext"), AesGcmBlob.open(v.str("blob"), k))
    }

    @Test
    fun badgeBlobVectorFromSpec14() {
        val v = Fixtures.migration.obj("vectors").obj("badge_blob")
        val k = v.str("keyHex").hexToBytes()
        assertEquals(v.str("blob"), AesGcmBlob.seal(k, v.str("plaintextJson"), v.str("ivHex").hexToBytes()))
        assertEquals(v.str("plaintextJson"), AesGcmBlob.open(v.str("blob"), k))
        assertNull(AesGcmBlob.openOrNull(v.str("blob"), ByteArray(32)))
        assertNull(AesGcmBlob.openOrNull("no-colon", k))
        assertNull(AesGcmBlob.openOrNull("a:b:c", k))
    }

    @Test
    fun badgeBlobRoundTripWithRandomIv() {
        val blob = AesGcmBlob.seal(key, "[{\"id\":\"wien\"}]")
        val iv = DartBase64.decode(blob.substringBefore(':'))
        assertEquals(12, iv.size)
        assertEquals("[{\"id\":\"wien\"}]", AesGcmBlob.open(blob, key))
    }

    @Test
    fun flutterSecureStorageV10GcmValues() {
        val v = Fixtures.migration.obj("vectors").obj("fss_v10_gcm")
        val aesKey = v.str("aes128KeyHex").hexToBytes()
        for (e in v.arr("entries")) {
            val raw = Base64.getMimeDecoder().decode(e.str("storedValue"))
            assertEquals(e.str("rawHex"), raw.toHex())
            assertEquals(e.str("plaintext"), DartUtf8.decodeStrict(AesGcm.decryptIvPrefixed(aesKey, raw)))
            val iv = e.str("ivHex").hexToBytes()
            assertArrayEquals(raw, iv + AesGcm.encrypt(aesKey, iv, DartUtf8.encode(e.str("plaintext"))))
        }
    }

    @Test
    fun flutterSecureStorageV9CbcValue() {
        val v = Fixtures.migration.obj("vectors").obj("fss_v9_cbc")
        val aesKey = v.str("aes128KeyHex").hexToBytes()
        val raw = Base64.getMimeDecoder().decode(v.str("storedValue"))
        assertEquals(v.str("rawHex"), raw.toHex())
        assertEquals(v.str("plaintext"), DartUtf8.decodeStrict(AesCbc.decryptIvPrefixed(aesKey, raw)))
        val iv = v.str("ivHex").hexToBytes()
        assertArrayEquals(raw, iv + AesCbc.encrypt(aesKey, iv, DartUtf8.encode(v.str("plaintext"))))
        assertThrows(java.security.GeneralSecurityException::class.java) {
            AesCbc.decryptIvPrefixed(ByteArray(16), raw)
        }
    }

    // ---- backup envelope (spec fixture 08) ----

    @Test
    fun backupEncV2VectorDecryptsAndEncryptsByteIdentical() {
        val v = Fixtures.backup.obj("enc_v2")
        val salt = v.str("salt_hex").hexToBytes()
        val iv = v.str("iv_hex").hexToBytes()
        val derived = BackupEnvelope.deriveKeyV2(v.str("password"), salt)
        assertEquals(v.str("derived_key_hex"), derived.toHex())
        // Encryption with the derived key reproduces the file byte for byte.
        val sealed = AesGcm.encrypt(derived, iv, DartUtf8.encode(v.str("plaintext")))
        assertEquals(
            v.str("file_content"),
            BackupEnvelope.PREFIX_V2 + DartBase64.encode(salt) + ":" + DartBase64.encode(iv) + ":" + DartBase64.encode(sealed),
        )
        assertEquals(BackupEnvelope.Format.V2, BackupEnvelope.formatOf(v.str("file_content")))
        // Full path including the 600 000-round derivation.
        assertEquals(v.str("plaintext"), BackupEnvelope.decrypt(v.str("file_content"), v.str("password")))
    }

    @Test
    fun backupEncV2WrongPasswordAndCorruption() {
        val content = BackupEnvelope.encryptV2("{\"user\":{},\"badges\":[]}", "richtig-123", iterations = 10)
        assertTrue(content.startsWith("enc_v2:"))
        val parts = content.split(':')
        assertEquals(4, parts.size)
        assertEquals(32, DartBase64.decode(parts[1]).size)
        assertEquals(16, DartBase64.decode(parts[2]).size)
        assertEquals("{\"user\":{},\"badges\":[]}", BackupEnvelope.decrypt(content, "richtig-123", iterations = 10))
        assertThrows(BackupCryptoException::class.java) { BackupEnvelope.decrypt(content, "falsch", iterations = 10) }
        assertThrows(BackupCryptoException::class.java) { BackupEnvelope.decrypt("$content:extra", "richtig-123", iterations = 10) }
        assertThrows(BackupCryptoException::class.java) {
            BackupEnvelope.decrypt("enc_v2:%%%:${parts[2]}:${parts[3]}", "richtig-123", iterations = 10)
        }
    }

    @Test
    fun backupEncV1LegacyVector() {
        val v = Fixtures.backup.obj("enc_v1")
        assertEquals(v.str("key_hex_sha256_of_password"), BackupEnvelope.deriveKeyV1(v.str("password")).toHex())
        assertEquals(BackupEnvelope.Format.V1, BackupEnvelope.formatOf(v.str("file_content")))
        assertEquals(v.str("plaintext"), BackupEnvelope.decrypt(v.str("file_content"), v.str("password")))
        assertThrows(BackupCryptoException::class.java) { BackupEnvelope.decrypt(v.str("file_content"), "falsch") }
    }

    @Test
    fun plainBackupPassesThrough() {
        assertEquals(BackupEnvelope.Format.PLAIN, BackupEnvelope.formatOf("{\"user\":{}}"))
        assertEquals("{\"user\":{}}", BackupEnvelope.decrypt("{\"user\":{}}", "egal"))
    }
}
