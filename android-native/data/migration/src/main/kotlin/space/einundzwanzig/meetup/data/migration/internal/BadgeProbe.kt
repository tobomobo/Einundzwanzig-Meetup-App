package space.einundzwanzig.meetup.data.migration.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.AesGcmBlob
import space.einundzwanzig.meetup.core.crypto.Hex

/**
 * Test-opens the encrypted badge blobs with every 64-hex secret (spec 14 §4.7 step 7, Dart
 * `_candidateKeys`). Diagnostic only: the badge domain repeats this at runtime.
 */
internal object BadgeProbe {

    /** Prefs keys holding a `base64(iv):base64(ct‖tag)` blob. */
    val PREF_BLOB_KEYS = listOf(
        "badges_encrypted", "badges_encrypted_preserved", "badges_encrypted_preserved_older", "badges_pending_encrypted",
    )

    data class Result(val blobs: Int, val opened: Int, val unopened: List<String>)

    /** Every distinct 64-hex value (trimmed, lowercased) among [secrets], as AES-256 keys. */
    fun candidateKeys(secrets: Collection<String>): List<ByteArray> =
        secrets.map { it.trim().lowercase() }
            .filter { Hex.isHexOfLength(it, 64) }
            .distinct()
            .map(Hex::decode)

    /** Named blobs: the prefs blobs, the secure copy and every `badges_former[].blob`. */
    fun blobs(prefStrings: Map<String, String?>, secureCopy: String?, formerJson: String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (k in PREF_BLOB_KEYS) prefStrings[k]?.takeIf { it.isNotEmpty() }?.let { out[k] = it }
        secureCopy?.takeIf { it.isNotEmpty() }?.let { out["secure:badges_encrypted"] = it }
        formerBlobs(formerJson).forEachIndexed { i, b -> out["badges_former[$i]"] = b }
        return out
    }

    fun probe(blobs: Map<String, String>, keys: List<ByteArray>): Result {
        val unopened = blobs.filter { (_, blob) -> keys.none { AesGcmBlob.openOrNull(blob, it) != null } }.keys.toList()
        return Result(blobs.size, blobs.size - unopened.size, unopened)
    }

    private fun formerBlobs(json: String?): List<String> {
        if (json.isNullOrEmpty()) return emptyList()
        val array = runCatching { DartJson.decode(json) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { e -> ((e as? JsonObject)?.get("blob") as? JsonPrimitive)?.takeIf { it.isString }?.content }
            .filter { it.isNotEmpty() }
    }
}
