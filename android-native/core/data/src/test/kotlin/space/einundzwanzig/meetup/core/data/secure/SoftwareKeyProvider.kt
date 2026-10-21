package space.einundzwanzig.meetup.core.data.secure

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Test-only replacement for [AndroidKeystoreKeyProvider]: Robolectric has no `AndroidKeyStore`
 * JCA provider, so the secure-store tests run [AesGcmSecretCipher] with a software AES-256 key.
 * Everything except the key's location (TEE vs. heap) is the production code path.
 */
internal class SoftwareKeyProvider(
    private val key: SecretKey = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES"),
) : SecretKeyProvider {

    /** Simulates an unavailable Keystore (e.g. during early boot). */
    @Volatile
    var unavailable: Boolean = false

    /** Simulates a deleted Keystore alias: [decryptionKey] throws [KeyMissingException]. */
    @Volatile
    var missing: Boolean = false

    var invalidations: Int = 0
        private set

    override fun encryptionKey(): SecretKey = check()

    override fun decryptionKey(): SecretKey {
        if (missing) throw KeyMissingException("Keystore key does not exist (simulated)")
        return check()
    }

    override fun invalidate() {
        invalidations++
    }

    private fun check(): SecretKey {
        if (unavailable) throw GeneralSecurityException("Keystore unavailable (simulated)")
        return key
    }
}
