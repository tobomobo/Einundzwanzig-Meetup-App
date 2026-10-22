package space.einundzwanzig.meetup.data.proofs.platform

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.proofs.internal.dartToStringOrEmpty

/** Pure backup helpers for platform proofs (Dart `BackupService` restore rules, spec 06 §4). */
object PlatformProofBackup {

    private val HEX = Regex("^[0-9a-fA-F]+$")

    /**
     * Audit 2 finding #4: keeps only objects whose `proof_sig` is a non-empty string of exactly
     * 128 hex chars. Entries are returned as is (extra keys survive).
     */
    fun restorable(entries: List<JsonElement>): List<JsonElement> = entries.filter { e ->
        val sig = ((e as? JsonObject)?.get("proof_sig") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content ?: return@filter false
        sig.isNotEmpty() && sig.length == 128 && HEX.matches(sig)
    }

    /**
     * Dart `BackupService.unionPlatformProofs(device, incoming)`: key = `platform` lowercased
     * (Dart case mapping), the device entry wins, new incoming entries are appended in order.
     * Non-object entries are skipped.
     */
    fun union(device: List<JsonElement>, incoming: List<JsonElement>): List<JsonObject> {
        fun keyOf(o: JsonObject) = DartString.lower(o["platform"].dartToStringOrEmpty())
        val byKey = LinkedHashMap<String, JsonObject>()
        for (raw in device) {
            val o = raw as? JsonObject ?: continue
            byKey[keyOf(o)] = o
        }
        for (raw in incoming) {
            val o = raw as? JsonObject ?: continue
            byKey.putIfAbsent(keyOf(o), o)
        }
        return byKey.values.toList()
    }
}
