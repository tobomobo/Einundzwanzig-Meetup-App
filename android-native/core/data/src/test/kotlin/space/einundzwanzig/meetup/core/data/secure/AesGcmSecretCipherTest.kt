package space.einundzwanzig.meetup.core.data.secure

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AesGcmSecretCipherTest {

    private val keyBytes = ByteArray(32) { it.toByte() }
    private val cipher = AesGcmSecretCipher(SoftwareKeyProvider(SecretKeySpec(keyBytes, "AES")))
    private val aad = "einundzwanzig-secure-store/v1:k".encodeToByteArray()

    @Test
    fun layoutIsIvCiphertextTagAndInteroperatesWithPlainJca() {
        val plaintext = "hello secure world".encodeToByteArray()
        val sealed = cipher.seal(plaintext, aad)
        assertEquals(12 + plaintext.size + 16, sealed.size)

        // Decrypt with a plain JCA cipher to pin the byte layout.
        val jca = Cipher.getInstance("AES/GCM/NoPadding")
        jca.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, sealed, 0, 12))
        jca.updateAAD(aad)
        assertArrayEquals(plaintext, jca.doFinal(sealed, 12, sealed.size - 12))
        assertArrayEquals(plaintext, cipher.open(sealed, aad))
    }

    @Test
    fun emptyPlaintextWorks() {
        val sealed = cipher.seal(ByteArray(0), aad)
        assertEquals(28, sealed.size)
        assertArrayEquals(ByteArray(0), cipher.open(sealed, aad))
    }

    @Test
    fun wrongAssociatedDataFails() {
        val sealed = cipher.seal("x".encodeToByteArray(), aad)
        assertThrows(GeneralSecurityException::class.java) {
            cipher.open(sealed, "einundzwanzig-secure-store/v1:other".encodeToByteArray())
        }
    }

    @Test
    fun tooShortInputFails() {
        assertThrows(MalformedCiphertextException::class.java) { cipher.open(ByteArray(27), aad) }
    }

    @Test
    fun wrongKeyFailsAuthenticationWithoutDroppingTheCachedKey() {
        val sealed = cipher.seal("x".encodeToByteArray(), aad)
        val keys = SoftwareKeyProvider()
        val other = AesGcmSecretCipher(keys)
        assertThrows(AEADBadTagException::class.java) { other.open(sealed, aad) }
        // Bad data is not a bad key: no Keystore reload for every old entry after a key loss.
        assertEquals(0, keys.invalidations)
    }

    @Test
    fun missingKeyIsReportedAsSuchAndDoesNotInvalidate() {
        var invalidations = 0
        val missing = object : SecretKeyProvider {
            override fun encryptionKey() = throw KeyMissingException("gone")
            override fun decryptionKey() = throw KeyMissingException("gone")
            override fun invalidate() {
                invalidations++
            }
        }
        assertThrows(KeyMissingException::class.java) { AesGcmSecretCipher(missing).open(ByteArray(40), aad) }
        assertEquals(0, invalidations)
    }

    @Test
    fun keyProviderRuntimeFailuresAreNormalizedAndInvalidate() {
        var invalidations = 0
        val failing = object : SecretKeyProvider {
            override fun encryptionKey() = throw IllegalStateException("keystore2 crashed")
            override fun decryptionKey() = throw java.io.IOException("load failed")
            override fun invalidate() {
                invalidations++
            }
        }
        val broken = AesGcmSecretCipher(failing)
        assertThrows(GeneralSecurityException::class.java) { broken.seal(ByteArray(1), aad) }
        assertThrows(GeneralSecurityException::class.java) { broken.open(ByteArray(40), aad) }
        assertEquals(2, invalidations)
    }
}
