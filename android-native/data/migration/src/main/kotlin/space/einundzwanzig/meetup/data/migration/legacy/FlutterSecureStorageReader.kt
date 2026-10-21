package space.einundzwanzig.meetup.data.migration.legacy

import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.DeterministicAead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.daead.DeterministicAeadConfig
import space.einundzwanzig.meetup.core.crypto.AesCbc
import space.einundzwanzig.meetup.core.crypto.AesGcm
import space.einundzwanzig.meetup.core.crypto.DartUtf8
import space.einundzwanzig.meetup.core.crypto.Hex
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/** A decrypted Flutter secret. [toString] never reveals the value. */
internal data class LegacySecret(val value: String, val source: Source) {
    enum class Source { GCM, CBC, GCM_BACKUP, CBC_BACKUP, ESP, PLAINTEXT_PREFS }

    override fun toString(): String = "LegacySecret(<redacted>, $source)"
}

/** One unwrapped flutter_secure_storage AES key and where it came from. */
internal class LegacyAesKey(val key: ByteArray, val wrappedName: String, val rsa: RsaMode) {
    override fun toString(): String = "LegacyAesKey(<redacted>, $wrappedName, $rsa)"
}

/** Custom-cipher entries (spec 14 §4.6.3). */
internal data class CustomEntries(
    /** `<prefix>_<key>` → value. */
    val main: Map<String, LegacySecret>,
    /** `<prefix>_<key>_BACKUP` → value (old cipher of an interrupted 10.x migration). */
    val backup: Map<String, LegacySecret>,
    /** Logical names (with `_BACKUP` suffix for backups) that no candidate key opened. */
    val failed: List<String>,
)

/** ESP entries (spec 14 §4.6.4). */
internal data class EspEntries(
    /** Logical key → value. */
    val values: Map<String, String>,
    /** Logical keys whose name decrypted but whose value did not. */
    val failed: List<String>,
    /** `false` if ESP data exists but the keysets could not be opened at all. */
    val readable: Boolean,
    /** Number of encrypted (non-reserved, non-custom) entries in the file. */
    val encryptedEntryCount: Int,
)

/**
 * Read-only reader for `FlutterSecureStorage.xml` + `FlutterSecureKeyStorage.xml` in both storage
 * eras. Values are only held in memory; nothing is logged here.
 */
internal class FlutterSecureStorageReader(
    private val files: SharedPrefsFiles,
    private val keystore: LegacyKeystore,
) {

    /** Raw data file entries (empty if absent). */
    fun dataEntries(): Map<String, Any> = files.read(Fss.DATA)

    /** The storage-cipher marker from the 10.x config files or the v9 data file, if any. */
    fun storageAlgorithmMarker(data: Map<String, Any>): String? =
        listOf(Fss.CONFIG, Fss.LEGACY_CONFIG).firstNotNullOfOrNull { name ->
            runCatching { files.read(name)[Fss.MARKER_STORAGE_ALGORITHM] as? String }.getOrNull()
        } ?: data[Fss.MARKER_STORAGE_ALGORITHM] as? String

    /** Unwraps every wrapped AES key (main and `_BACKUP` names, both RSA modes), ordered (§4.6.2). */
    fun candidateKeys(storageMarker: String?): List<LegacyAesKey> {
        val prefs = runCatching { files.read(Fss.KEYS) }.getOrDefault(emptyMap())
        val order = if (storageMarker == Fss.STORAGE_GCM) {
            Fss.WRAPPED_KEY_NAMES // GCM names first (already the default order)
        } else if (storageMarker != null) {
            listOf(Fss.WRAPPED_V9_CBC, Fss.WRAPPED_V10_GCM, Fss.WRAPPED_V9_GCM_TYPO)
        } else {
            Fss.WRAPPED_KEY_NAMES
        }
        val out = mutableListOf<LegacyAesKey>()
        for (base in order) for (name in listOf(base, base + Fss.BACKUP)) {
            val b64 = prefs[name] as? String ?: continue
            val wrapped = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: continue
            for (mode in RsaMode.entries) keystore.unwrapAesKey(wrapped, mode)?.let { out += LegacyAesKey(it, name, mode) }
        }
        return out.distinctBy { it.key.toList() }
    }

    /** Decrypts `<prefix>_<key>` and `<prefix>_<key>_BACKUP` entries with the candidate keys (§4.6.3). */
    fun readCustomCipherEntries(data: Map<String, Any>, keys: List<LegacyAesKey>): CustomEntries {
        val main = LinkedHashMap<String, LegacySecret>()
        val backup = LinkedHashMap<String, LegacySecret>()
        val failed = mutableListOf<String>()
        for ((name, v) in data) {
            if (v !is String || !name.startsWith(Fss.ENTRY)) continue // ESP names, keysets, v9 markers
            if (name.endsWith(Fss.MIGRATED)) continue
            val isBackup = name.endsWith(Fss.BACKUP)
            val logical = name.removePrefix(Fss.ENTRY).let { if (isBackup) it.removeSuffix(Fss.BACKUP) else it }
            val bytes = runCatching { Base64.decode(v, Base64.DEFAULT) }.getOrNull()
            val plain = bytes?.let { decryptWithAny(it, keys, logical) }
            when {
                plain == null -> failed += name.removePrefix(Fss.ENTRY)
                isBackup -> backup[logical] = plain.copy(
                    source = if (plain.source == LegacySecret.Source.GCM) LegacySecret.Source.GCM_BACKUP else LegacySecret.Source.CBC_BACKUP,
                )
                else -> main[logical] = plain
            }
        }
        return CustomEntries(main, backup, failed)
    }

    private fun decryptWithAny(data: ByteArray, keys: List<LegacyAesKey>, logical: String): LegacySecret? {
        // 1) GCM is authenticated: the first key that opens it is the right one.
        if (data.size >= GCM_IV + GCM_TAG) {
            for (k in keys) {
                val plain = runCatching { DartUtf8.decodeStrict(AesGcm.decryptIvPrefixed(k.key, data, GCM_IV)) }.getOrNull()
                if (plain != null) return LegacySecret(plain, LegacySecret.Source.GCM)
            }
        }
        // 2) CBC is not authenticated: only strict UTF-8 that passes the per-key plausibility check.
        if (data.size >= 32 && (data.size - 16) % 16 == 0) {
            for (k in keys) {
                val plain = runCatching { DartUtf8.decodeStrict(AesCbc.decryptIvPrefixed(k.key, data)) }.getOrNull()
                if (plain != null && SecureKeyInventory.isPlausible(logical, plain)) return LegacySecret(plain, LegacySecret.Source.CBC)
            }
        }
        return null
    }

    /**
     * Reads EncryptedSharedPreferences entries (E1, including F1 orphans) without
     * `EncryptedSharedPreferences`/`AndroidKeysetManager`, which could create or rewrite keysets
     * and master keys. Returns `null` if the file holds no ESP keysets.
     *
     * Every entry name is tried with the key keyset (AES256-SIV, associated data = file name), so
     * entries of other formats in a mixed E1/E2 file are simply skipped (`getAll()` would throw).
     */
    fun readEspEntries(data: Map<String, Any>): EspEntries? {
        val keyKeyset = data[Fss.ESP_KEY_KEYSET] as? String
        val valueKeyset = data[Fss.ESP_VALUE_KEYSET] as? String
        if (keyKeyset == null || valueKeyset == null) return null
        val candidates = data.filter { (name, v) ->
            v is String && !name.startsWith(Fss.ENTRY) && name != Fss.ESP_KEY_KEYSET && name != Fss.ESP_VALUE_KEYSET &&
                name != Fss.MARKER_KEY_ALGORITHM && name != Fss.MARKER_STORAGE_ALGORITHM
        }
        val primitives = runCatching {
            registerTink()
            val master = keystore.espMasterAead()
            val daead = parseKeyset(keyKeyset, master).getPrimitive(RegistryConfiguration.get(), DeterministicAead::class.java)
            val aead = parseKeyset(valueKeyset, master).getPrimitive(RegistryConfiguration.get(), Aead::class.java)
            daead to aead
        }.getOrNull() ?: return EspEntries(emptyMap(), emptyList(), readable = false, encryptedEntryCount = candidates.size)
        val (daead, aead) = primitives
        val fileNameBytes = Fss.DATA.toByteArray(StandardCharsets.UTF_8)
        val values = LinkedHashMap<String, String>()
        val failed = mutableListOf<String>()
        var count = 0
        for ((encName, encValue) in candidates) {
            val name = runCatching {
                String(daead.decryptDeterministically(Base64.decode(encName, Base64.DEFAULT), fileNameBytes), StandardCharsets.UTF_8)
            }.getOrNull() ?: continue // not an ESP entry
            count++
            if (!name.startsWith(Fss.ENTRY)) continue // written by another ESP user of the file (none known)
            val logical = name.removePrefix(Fss.ENTRY)
            val value = runCatching {
                decodeEspString(aead.decrypt(Base64.decode(encValue as String, Base64.DEFAULT), encName.toByteArray(StandardCharsets.UTF_8)))
            }.getOrNull()
            when {
                value == null -> failed += logical
                value.isEmpty -> Unit // stored null
                else -> values[logical] = value.get()
            }
        }
        return EspEntries(values, failed, readable = true, encryptedEntryCount = maxOf(count, 0))
    }

    /** Keysets are hex-encoded `EncryptedKeyset` protos (AD = empty); cleartext keysets were written when the Keystore was unusable. */
    private fun parseKeyset(hex: String, master: Aead?): KeysetHandle {
        val bytes = Hex.decode(hex)
        if (master != null) {
            runCatching { return TinkProtoKeysetFormat.parseEncryptedKeyset(bytes, master, ByteArray(0)) }
        }
        return TinkProtoKeysetFormat.parseKeyset(bytes, InsecureSecretKeyAccess.get())
    }

    internal companion object {
        private const val GCM_IV = 12
        private const val GCM_TAG = 16
        private const val TYPE_STRING = 0
        private const val NULL_VALUE = "__NULL__"

        @Volatile
        private var tinkRegistered = false

        fun registerTink() {
            if (tinkRegistered) return
            synchronized(this) {
                if (!tinkRegistered) {
                    DeterministicAeadConfig.register()
                    AeadConfig.register()
                    tinkRegistered = true
                }
            }
        }

        /**
         * ESP value payload `[int32 type][int32 len][utf8]`; only strings were ever written.
         * Like ESP, the string is everything after the 8-byte header. Empty Optional = stored null.
         */
        fun decodeEspString(plain: ByteArray): java.util.Optional<String> {
            val buf = ByteBuffer.wrap(plain)
            val type = buf.getInt()
            require(type == TYPE_STRING) { "Not a string value" }
            buf.getInt() // declared length (ESP ignores it when decoding)
            val s = DartUtf8.decodeStrict(plain.copyOfRange(8, plain.size))
            return if (s == NULL_VALUE) java.util.Optional.empty() else java.util.Optional.of(s)
        }
    }
}
