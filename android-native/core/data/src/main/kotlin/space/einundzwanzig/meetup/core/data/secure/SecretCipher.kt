package space.einundzwanzig.meetup.core.data.secure

import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Authenticated encryption used by [DataStoreSecureStore].
 *
 * The production implementation is [AesGcmSecretCipher] with [AndroidKeystoreKeyProvider].
 * Robolectric has no `AndroidKeyStore` provider, so unit tests plug a software AES key into the
 * same [AesGcmSecretCipher]: only the key source differs, the framing and AAD binding are tested.
 */
internal interface SecretCipher {
    /** Encrypts [plaintext] bound to [associatedData]; returns `iv || ciphertext || tag`. */
    @Throws(GeneralSecurityException::class)
    fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray

    /** Reverses [seal]. Throws when the data was tampered with or the key no longer matches. */
    @Throws(GeneralSecurityException::class)
    fun open(sealed: ByteArray, associatedData: ByteArray): ByteArray
}

/** Supplies the AES key of an [AesGcmSecretCipher]. */
internal interface SecretKeyProvider {
    /** The key to encrypt with; may create it on first use. */
    @Throws(GeneralSecurityException::class)
    fun encryptionKey(): SecretKey

    /**
     * The key to decrypt with; never creates one. Throws [KeyMissingException] if the key does
     * not exist, another [GeneralSecurityException] if it exists but cannot be used right now.
     */
    @Throws(GeneralSecurityException::class)
    fun decryptionKey(): SecretKey

    /** Forgets any cached key handle after a failure so the next call reloads it. */
    fun invalidate() {}
}

/**
 * AES-GCM with a 96-bit random IV chosen by the provider and a 128-bit tag.
 *
 * Output layout: `iv (12 bytes) || ciphertext || tag (16 bytes)`. The IV is never chosen by us:
 * Android Keystore keys require randomized encryption and generate it themselves.
 */
internal class AesGcmSecretCipher(private val keys: SecretKeyProvider) : SecretCipher {

    override fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray = guarded {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.encryptionKey())
        val iv = cipher.iv
        if (iv == null || iv.size != IV_BYTES) throw GeneralSecurityException("Unexpected GCM IV length ${iv?.size}")
        cipher.updateAAD(associatedData)
        iv + cipher.doFinal(plaintext)
    }

    override fun open(sealed: ByteArray, associatedData: ByteArray): ByteArray {
        if (sealed.size < IV_BYTES + TAG_BYTES) throw MalformedCiphertextException("Ciphertext too short")
        return guarded {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keys.decryptionKey(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            cipher.updateAAD(associatedData)
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        }
    }

    /**
     * Keystore failures surface as assorted exceptions (`ProviderException`, `IOException` from
     * `KeyStore.load`, ...); normalize them and drop the cached key handle.
     *
     * A failed authentication ([AEADBadTagException]) says the *data* does not match the key, not
     * that the key handle is broken, and a missing key stays missing: neither invalidates the
     * cache, so reading old entries after a key loss does not reload the key every time.
     */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: AEADBadTagException) {
        throw e
    } catch (e: KeyMissingException) {
        throw e
    } catch (e: GeneralSecurityException) {
        keys.invalidate()
        throw e
    } catch (e: Exception) {
        keys.invalidate()
        throw GeneralSecurityException("Cipher operation failed: ${e.javaClass.simpleName}", e)
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val TAG_BITS = TAG_BYTES * 8
    }
}

/** The key a [SecretKeyProvider] should decrypt with does not exist (it was never created or was deleted). */
internal class KeyMissingException(message: String) : GeneralSecurityException(message)

/** A sealed value is structurally invalid (too short to hold IV and tag). */
internal class MalformedCiphertextException(message: String) : GeneralSecurityException(message)
