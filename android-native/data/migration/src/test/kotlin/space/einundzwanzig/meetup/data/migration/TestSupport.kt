package space.einundzwanzig.meetup.data.migration

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.DeterministicAead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.einundzwanzig.meetup.core.crypto.AesCbc
import space.einundzwanzig.meetup.core.crypto.AesGcm
import space.einundzwanzig.meetup.core.crypto.Hex
import space.einundzwanzig.meetup.data.migration.legacy.DecryptOnlyGcmAead
import space.einundzwanzig.meetup.data.migration.legacy.FlutterSecureStorageReader
import space.einundzwanzig.meetup.data.migration.legacy.Fss
import space.einundzwanzig.meetup.data.migration.legacy.LegacyKeystore
import space.einundzwanzig.meetup.data.migration.legacy.RsaMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/** Spec 14 fixture, read directly from `android-native/spec/fixtures` (no copies). */
internal object MigrationFixtures {
    val root: JsonObject by lazy {
        val file = File("../../spec/fixtures/14-platform-migration-vectors.json")
        check(file.exists()) { "missing spec fixture ${file.absolutePath}" }
        Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
    }
    val vectors: JsonObject get() = root.obj("vectors")
}

internal fun JsonElement.obj(key: String): JsonObject = jsonObject.getValue(key).jsonObject
internal fun JsonElement.arr(key: String): JsonArray = jsonObject.getValue(key).jsonArray
internal fun JsonElement.str(key: String): String = jsonObject.getValue(key).jsonPrimitive.content

/** Writes Flutter-format files into the Robolectric app's data dir. */
internal class FlutterDevice(private val context: Context) {
    val sharedPrefs: File = File(context.dataDir, "shared_prefs")

    fun reset() {
        sharedPrefs.deleteRecursively()
        File(context.noBackupFilesDir, "flutter_migration").deleteRecursively()
        File(context.filesDir, "profile").deleteRecursively()
    }

    fun writeRaw(name: String, xml: String, bak: Boolean = false) {
        sharedPrefs.mkdirs()
        File(sharedPrefs, if (bak) "$name.xml.bak" else "$name.xml").writeText(xml, Charsets.UTF_8)
    }

    /** Android `XmlUtils.writeMapXml` layout for String/Boolean/Int/Long/Float/Set values. */
    fun write(name: String, entries: Map<String, Any>, bak: Boolean = false) {
        val sb = StringBuilder("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n")
        for ((k, v) in entries) {
            val n = esc(k)
            when (v) {
                is String -> sb.append("    <string name=\"$n\">${esc(v)}</string>\n")
                is Boolean -> sb.append("    <boolean name=\"$n\" value=\"$v\" />\n")
                is Int -> sb.append("    <int name=\"$n\" value=\"$v\" />\n")
                is Long -> sb.append("    <long name=\"$n\" value=\"$v\" />\n")
                is Float -> sb.append("    <float name=\"$n\" value=\"$v\" />\n")
                is Set<*> -> {
                    sb.append("    <set name=\"$n\">\n")
                    v.forEach { sb.append("        <string>${esc(it as String)}</string>\n") }
                    sb.append("    </set>\n")
                }
                else -> error("unsupported ${v::class}")
            }
        }
        sb.append("</map>\n")
        writeRaw(name, sb.toString(), bak)
    }

    fun exists(name: String) = File(sharedPrefs, "$name.xml").exists()

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("\n", "&#10;")
}

/**
 * Software [LegacyKeystore]: real RSA (OAEP SHA-256/MGF1-SHA1 and PKCS#1) and a software ESP
 * master key in Tink's `AndroidKeystoreAesGcm` layout. Robolectric has no AndroidKeyStore.
 */
internal class FakeLegacyKeystore : LegacyKeystore {
    var oaepPresent = true
    var pkcs1Present = true
    var espMasterKey: SecretKey? = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")
    var aliasesDeleted = 0

    override fun unwrapAesKey(wrapped: ByteArray, mode: RsaMode): ByteArray? = try {
        val present = if (mode == RsaMode.OAEP) oaepPresent else pkcs1Present
        if (!present) {
            null
        } else {
            val c = cipher(mode, Cipher.UNWRAP_MODE, if (mode == RsaMode.OAEP) OAEP_KEYS else PKCS1_KEYS)
            (c.unwrap(wrapped, "AES", Cipher.SECRET_KEY) as SecretKey).encoded?.takeIf { it.size == 16 }
        }
    } catch (e: Exception) {
        null
    }

    override fun espMasterAead(): Aead? = espMasterKey?.let(::DecryptOnlyGcmAead)

    override fun deleteLegacyAliases(): Boolean {
        aliasesDeleted++
        return true
    }

    /** Encrypting side of the master key (the production seam is decrypt-only). */
    fun masterAeadForWriting(): Aead = SoftwareGcmAead(espMasterKey!!)

    companion object {
        private val OAEP_KEYS: KeyPair by lazy { rsa() }
        private val PKCS1_KEYS: KeyPair by lazy { rsa() }

        private fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.genKeyPair()

        private fun cipher(mode: RsaMode, op: Int, keys: KeyPair): Cipher = when (mode) {
            RsaMode.OAEP -> Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
                init(
                    op,
                    if (op == Cipher.WRAP_MODE) keys.public else keys.private,
                    OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT),
                )
            }
            RsaMode.PKCS1 -> Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
                init(op, if (op == Cipher.WRAP_MODE) keys.public else keys.private)
            }
        }

        /** What flutter_secure_storage stores in `FlutterSecureKeyStorage.xml` (Base64.DEFAULT of the wrapped key). */
        fun wrapB64(aesKey: ByteArray, mode: RsaMode): String =
            android.util.Base64.encodeToString(
                cipher(mode, Cipher.WRAP_MODE, if (mode == RsaMode.OAEP) OAEP_KEYS else PKCS1_KEYS).wrap(SecretKeySpec(aesKey, "AES")),
                android.util.Base64.DEFAULT,
            )
    }
}

internal class SoftwareGcmAead(private val key: SecretKey) : Aead {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        c.updateAAD(associatedData ?: ByteArray(0))
        return iv + c.doFinal(plaintext)
    }

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray =
        DecryptOnlyGcmAead(key).decrypt(ciphertext, associatedData)
}

/** Builders for flutter_secure_storage file contents. */
internal object FssWriter {
    fun gcmEntry(aesKey: ByteArray, plaintext: String): String {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        return android.util.Base64.encodeToString(iv + AesGcm.encrypt(aesKey, iv, plaintext.toByteArray(StandardCharsets.UTF_8)), android.util.Base64.DEFAULT)
    }

    fun cbcEntry(aesKey: ByteArray, plaintext: String): String {
        val iv = ByteArray(16).also(SecureRandom()::nextBytes)
        return android.util.Base64.encodeToString(iv + AesCbc.encrypt(aesKey, iv, plaintext.toByteArray(StandardCharsets.UTF_8)), android.util.Base64.DEFAULT)
    }

    /**
     * EncryptedSharedPreferences content exactly as the AndroidX/FSS class writes it: keysets
     * (hex `EncryptedKeyset`, AD empty, or cleartext `Keyset`) and AES-SIV names / AES-GCM values.
     */
    fun espEntries(master: Aead?, values: Map<String, String>): Map<String, String> {
        FlutterSecureStorageReader.registerTink()
        val keyHandle = KeysetHandle.generateNew(KeyTemplates.get("AES256_SIV"))
        val valueHandle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
        fun ser(h: KeysetHandle): String = Hex.encode(
            if (master != null) {
                TinkProtoKeysetFormat.serializeEncryptedKeyset(h, master, ByteArray(0))
            } else {
                TinkProtoKeysetFormat.serializeKeyset(h, InsecureSecretKeyAccess.get())
            },
        )
        val out = LinkedHashMap<String, String>()
        out[Fss.ESP_KEY_KEYSET] = ser(keyHandle)
        out[Fss.ESP_VALUE_KEYSET] = ser(valueHandle)
        val daead = keyHandle.getPrimitive(RegistryConfiguration.get(), DeterministicAead::class.java)
        val aead = valueHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        for ((k, v) in values) {
            val encName = java.util.Base64.getEncoder().encodeToString(
                daead.encryptDeterministically((Fss.ENTRY + k).toByteArray(StandardCharsets.UTF_8), Fss.DATA.toByteArray()),
            )
            val bytes = v.toByteArray(StandardCharsets.UTF_8)
            val payload = ByteBuffer.allocate(8 + bytes.size).putInt(0).putInt(bytes.size).put(bytes).array()
            out[encName] = java.util.Base64.getEncoder().encodeToString(aead.encrypt(payload, encName.toByteArray(StandardCharsets.UTF_8)))
        }
        return out
    }
}
