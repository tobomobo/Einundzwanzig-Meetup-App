package space.einundzwanzig.meetup.core.data.secure

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import space.einundzwanzig.meetup.core.data.internal.DataLog
import space.einundzwanzig.meetup.core.data.secure.SecureRead.Unreadable.Reason
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException
import kotlin.coroutines.cancellation.CancellationException

/**
 * [SecureStore] that keeps `base64(iv || ciphertext || tag)` per key in a Preferences
 * [DataStore] (production file: `secure_prefs`).
 *
 * Each value is encrypted with [cipher] and bound to its key name through the GCM associated
 * data (`einundzwanzig-secure-store/v1:<key>`), so ciphertexts cannot be swapped between entries.
 * Key names are stored in clear, as flutter_secure_storage did.
 *
 * Keystore and decryption work runs on [ioDispatcher]; failures are logged (never with values)
 * and reported through the [SecureStore] error model instead of being thrown.
 *
 * **Decryption cache.** A stored ciphertext only ever decrypts to one plaintext, so decrypted
 * values (and permanent failures) are remembered per `(key, ciphertext)`. Repeated reads of an
 * unchanged entry, in particular [readAll] over the badge store's large second copies, then
 * cost no Keystore round trip. An entry whose ciphertext changed is decrypted again; entries
 * that no longer exist are dropped from the cache on the next read.
 */
internal class DataStoreSecureStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
    private val ioDispatcher: CoroutineDispatcher,
) : SecureStore {

    /** Outcome of decrypting one stored ciphertext. */
    private class Decoded(val ciphertext: String, val read: SecureRead)

    private val cache = ConcurrentHashMap<String, Decoded>()

    override suspend fun readChecked(key: String): SecureRead {
        val encoded = try {
            dataStore.data.first()[stringPreferencesKey(key)]
        } catch (e: IOException) {
            val reason = storageReason(e)
            DataLog.e(TAG, "Reading secure storage failed for '$key' ($reason)", e)
            return SecureRead.Unreadable(e, reason)
        }
        if (encoded == null) {
            cache.remove(key)
            return SecureRead.Absent
        }
        return withContext(ioDispatcher) { decode(key, encoded) }
    }

    override suspend fun write(key: String, value: String): Boolean = writeAll(mapOf(key to value))

    override suspend fun writeAll(entries: Map<String, String>): Boolean {
        if (entries.isEmpty()) return true
        // Encrypt everything first: a Keystore failure must not leave a partial write behind.
        val encoded = withContext(ioDispatcher) {
            try {
                entries.mapValues { (key, value) -> encrypt(key, value) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DataLog.e(TAG, "Encrypting secure entries ${entries.keys} failed (${e.javaClass.simpleName})", e)
                null
            }
        } ?: return false
        val stored = persist("write ${entries.keys}") { prefs ->
            encoded.forEach { (key, value) -> prefs[stringPreferencesKey(key)] = value }
        }
        if (stored) {
            encoded.forEach { (key, ciphertext) ->
                cache[key] = Decoded(ciphertext, SecureRead.Present(entries.getValue(key)))
            }
        }
        return stored
    }

    override suspend fun delete(key: String): Boolean =
        persist("delete '$key'") { it.remove(stringPreferencesKey(key)) }.also { if (it) cache.remove(key) }

    override suspend fun deleteAll(): Boolean = persist("delete all") { it.clear() }.also { if (it) cache.clear() }

    override suspend fun containsKeyChecked(key: String): Result<Boolean> = try {
        Result.success(stringPreferencesKey(key) in dataStore.data.first())
    } catch (e: IOException) {
        DataLog.e(TAG, "Reading secure storage failed (${storageReason(e)})", e)
        Result.failure(e)
    }

    override suspend fun readAllChecked(): Result<Map<String, String>> {
        val entries = try {
            dataStore.data.first().asMap()
        } catch (e: IOException) {
            DataLog.e(TAG, "Reading secure storage failed (${storageReason(e)})", e)
            return Result.failure(e)
        }
        cache.keys.retainAll(entries.keys.mapTo(HashSet()) { it.name })
        return withContext(ioDispatcher) {
            val out = sortedMapOf<String, String>()
            for ((k, raw) in entries) {
                if (raw !is String) continue
                val read = decode(k.name, raw)
                if (read is SecureRead.Present) out[k.name] = read.value
            }
            Result.success(LinkedHashMap(out))
        }
    }

    /** Decrypts [encoded] stored under [key], using and updating the cache. Never throws (except cancellation). */
    private fun decode(key: String, encoded: String): SecureRead {
        cache[key]?.takeIf { it.ciphertext == encoded }?.let { return it.read }
        val read = try {
            SecureRead.Present(decrypt(key, encoded))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = decryptionReason(e)
            DataLog.e(TAG, "Secure entry '$key' is unreadable ($reason, ${e.javaClass.simpleName})", e)
            SecureRead.Unreadable(e, reason)
        }
        // Transient failures are retried on the next read; everything else is final for this ciphertext.
        if (read !is SecureRead.Unreadable || read.isPermanent) cache[key] = Decoded(encoded, read)
        return read
    }

    private suspend fun persist(
        what: String,
        change: (MutablePreferences) -> Unit,
    ): Boolean = try {
        dataStore.edit { change(it) }
        true
    } catch (e: IOException) {
        DataLog.e(TAG, "Secure storage $what failed (${storageReason(e)})", e)
        false
    }

    private fun encrypt(key: String, value: String): String =
        Base64.getEncoder().encodeToString(cipher.seal(value.encodeToByteArray(), aad(key)))

    private fun decrypt(key: String, encoded: String): String {
        val sealed = Base64.getDecoder().decode(encoded)
        return cipher.open(sealed, aad(key)).decodeToString(throwOnInvalidSequence = true)
    }

    private fun aad(key: String): ByteArray = (AAD_PREFIX + key).encodeToByteArray()

    private companion object {
        const val TAG = "SecureStore"
        const val AAD_PREFIX = "einundzwanzig-secure-store/v1:"

        fun storageReason(e: IOException): Reason =
            if (e is CorruptionException) Reason.STORAGE_CORRUPT else Reason.STORAGE_IO

        fun decryptionReason(e: Exception): Reason = when (e) {
            // Base64 decoding, malformed UTF-8 plaintext, IV/tag missing.
            is IllegalArgumentException, is CharacterCodingException, is MalformedCiphertextException -> Reason.MALFORMED
            is AEADBadTagException -> Reason.AUTHENTICATION_FAILED
            is KeyMissingException, is KeyPermanentlyInvalidatedException -> Reason.KEY_MISSING
            else -> Reason.KEYSTORE_UNAVAILABLE
        }
    }
}
