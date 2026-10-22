package space.einundzwanzig.meetup.core.data.secure

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.Key
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Non-exportable AES-256 key in the Android Keystore (alias [KEY_ALIAS]), GCM only, no
 * user-authentication requirement so background work (signing, sync) can decrypt.
 *
 * - The key is created only by [encryptionKey] and only when the alias **does not exist**. If
 *   the alias exists but cannot be loaded, or the Keystore itself fails, the call throws and no
 *   key is created: replacing a temporarily unavailable key would make every stored secret
 *   unreadable for good.
 * - [decryptionKey] never creates a key; an absent alias throws [KeyMissingException].
 * - The loaded key is cached until [invalidate].
 * - The key lives in the TEE, deliberately not StrongBox: the store holds values of several
 *   hundred KiB (the badge store keeps second copies of its blobs here) and StrongBox's
 *   per-operation latency and throughput make that impractical.
 *
 * All methods block on Keystore IPC; call them off the main thread.
 */
internal class AndroidKeystoreKeyProvider(
    private val alias: String = KEY_ALIAS,
    private val backend: KeystoreBackend = AndroidKeystoreBackend,
) : SecretKeyProvider {

    private val lock = Any()

    @Volatile
    private var cached: SecretKey? = null

    override fun encryptionKey(): SecretKey = cached ?: synchronized(lock) {
        cached ?: (loadExisting() ?: backend.generateAes256Gcm(alias)).also { cached = it }
    }

    override fun decryptionKey(): SecretKey = cached ?: synchronized(lock) {
        cached ?: (loadExisting() ?: throw KeyMissingException("Keystore key '$alias' does not exist"))
            .also { cached = it }
    }

    override fun invalidate() {
        cached = null
    }

    /** The existing key, `null` if the alias is absent; throws if it exists but is unusable. */
    private fun loadExisting(): SecretKey? {
        if (!backend.containsAlias(alias)) return null
        return backend.getKey(alias) as? SecretKey
            ?: throw UnrecoverableKeyException("Keystore alias '$alias' holds no usable AES key")
    }

    companion object {
        /** Keystore alias of the secure-store key. Changing it orphans every stored secret. */
        const val KEY_ALIAS = "einundzwanzig_secure_store_v1"
    }
}

/**
 * The few Keystore operations [AndroidKeystoreKeyProvider] needs, so its safety rules can be
 * tested on the JVM (Robolectric has no `AndroidKeyStore` provider).
 */
internal interface KeystoreBackend {
    /** Whether [alias] exists. Throws if the Keystore cannot be queried. */
    @Throws(GeneralSecurityException::class)
    fun containsAlias(alias: String): Boolean

    /** The key stored under [alias] (`null` if none). Throws if it cannot be loaded. */
    @Throws(GeneralSecurityException::class)
    fun getKey(alias: String): Key?

    /** Creates a non-exportable AES-256-GCM key under [alias]. */
    @Throws(GeneralSecurityException::class)
    fun generateAes256Gcm(alias: String): SecretKey
}

/** [KeystoreBackend] on the `AndroidKeyStore` JCA provider. */
internal object AndroidKeystoreBackend : KeystoreBackend {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_BITS = 256

    override fun containsAlias(alias: String): Boolean = keyStore().containsAlias(alias)

    override fun getKey(alias: String): Key? = keyStore().getKey(alias, null)

    override fun generateAes256Gcm(alias: String): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()
        return try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                .apply { init(spec) }
                .generateKey()
        } catch (e: java.security.ProviderException) {
            throw GeneralSecurityException("Keystore key generation failed", e)
        }
    }

    private fun keyStore(): KeyStore = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    } catch (e: java.io.IOException) {
        throw GeneralSecurityException("Keystore could not be loaded", e)
    }
}
