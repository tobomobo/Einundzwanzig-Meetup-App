package space.einundzwanzig.meetup.data.migration.legacy

import com.google.crypto.tink.Aead
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.NoSuchProviderException
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/** RSA padding flutter_secure_storage used to wrap its AES key. */
internal enum class RsaMode { OAEP, PKCS1 }

/**
 * The Android Keystore part of the Flutter storage (spec 14 §4.6.2/§4.6.4), behind a seam:
 * Robolectric has no `AndroidKeyStore`, so tests supply software keys.
 *
 * Every method is **read-only**: it never creates an alias (a created key would make the
 * Flutter data look like a fresh install) and never throws; failures read as `null`.
 */
internal interface LegacyKeystore {
    /**
     * Unwraps a flutter_secure_storage AES key (16 bytes) with the RSA alias of [mode].
     * `null` if the alias does not exist or the unwrap fails or yields no 16-byte key.
     */
    fun unwrapAesKey(wrapped: ByteArray, mode: RsaMode): ByteArray?

    /**
     * The EncryptedSharedPreferences master key (`_androidx_security_master_key_`) as a
     * decrypt-only Tink [Aead] in the `AndroidKeystoreAesGcm` format (`iv12 ‖ ct ‖ tag`).
     * `null` if the alias does not exist.
     */
    fun espMasterAead(): Aead?

    /** Deletes the three Flutter aliases (cleanup / app reset). `false` if a delete failed. */
    fun deleteLegacyAliases(): Boolean
}

/** Production [LegacyKeystore] on `AndroidKeyStore`. */
internal class AndroidLegacyKeystore(private val packageName: String) : LegacyKeystore {

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun unwrapAesKey(wrapped: ByteArray, mode: RsaMode): ByteArray? = try {
        val ks = keyStore()
        val alias = if (mode == RsaMode.OAEP) Fss.oaepAlias(packageName) else Fss.pkcs1Alias(packageName)
        if (!ks.containsAlias(alias)) {
            null // NEVER generate
        } else {
            val priv = ks.getKey(alias, null) as? PrivateKey
            if (priv == null) {
                null
            } else {
                val cipher = when (mode) {
                    RsaMode.OAEP -> rsaCipher("RSA/ECB/OAEPPadding").apply {
                        init(
                            Cipher.UNWRAP_MODE,
                            priv,
                            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT),
                        )
                    }
                    RsaMode.PKCS1 -> rsaCipher("RSA/ECB/PKCS1Padding").apply { init(Cipher.UNWRAP_MODE, priv) }
                }
                // A wrong-mode unwrap may "succeed" with garbage of another length.
                (cipher.unwrap(wrapped, "AES", Cipher.SECRET_KEY) as SecretKey).encoded?.takeIf { it.size == 16 }
            }
        }
    } catch (e: Exception) {
        null
    }

    override fun espMasterAead(): Aead? = try {
        val ks = keyStore()
        if (!ks.containsAlias(Fss.ESP_MASTER_ALIAS)) null else (ks.getKey(Fss.ESP_MASTER_ALIAS, null) as? SecretKey)?.let(::DecryptOnlyGcmAead)
    } catch (e: Exception) {
        null
    }

    override fun deleteLegacyAliases(): Boolean = try {
        val ks = keyStore()
        var ok = true
        for (alias in listOf(Fss.pkcs1Alias(packageName), Fss.oaepAlias(packageName), Fss.ESP_MASTER_ALIAS)) {
            try {
                if (ks.containsAlias(alias)) ks.deleteEntry(alias)
            } catch (e: Exception) {
                ok = false
            }
        }
        ok
    } catch (e: Exception) {
        false
    }

    private fun rsaCipher(transformation: String): Cipher = try {
        Cipher.getInstance(transformation, "AndroidKeyStoreBCWorkaround")
    } catch (e: NoSuchProviderException) {
        Cipher.getInstance(transformation)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

/**
 * Decrypt-only Tink [Aead] with the byte layout of Tink's `AndroidKeystoreAesGcm`
 * (`iv(12) ‖ ciphertext ‖ tag(16)`, associated data via `updateAAD`). Encryption is refused: the
 * migration never writes Flutter data.
 */
internal class DecryptOnlyGcmAead(private val key: SecretKey) : Aead {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray =
        throw GeneralSecurityException("read-only")

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray {
        if (ciphertext.size < IV + TAG) throw GeneralSecurityException("ciphertext too short")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG * 8, ciphertext, 0, IV))
        cipher.updateAAD(associatedData ?: ByteArray(0))
        return cipher.doFinal(ciphertext, IV, ciphertext.size - IV)
    }

    private companion object {
        const val IV = 12
        const val TAG = 16
    }
}
