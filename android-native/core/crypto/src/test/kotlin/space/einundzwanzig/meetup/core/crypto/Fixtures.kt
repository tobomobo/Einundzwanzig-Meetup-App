package space.einundzwanzig.meetup.core.crypto

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Test vectors:
 * - `nip44.vectors.json`: test resource, verbatim copy of `<repo>/test/fixtures/nip44.vectors.json`
 *   (paulmillr/nip44).
 * - `spec-NN-<domain>-vectors.json`: read directly from `android-native/spec/fixtures/NN-<domain>-vectors.json`
 *   (no copies, so the vectors cannot drift from the spec).
 */
internal object Fixtures {
    fun load(name: String): JsonObject {
        val text = if (name.startsWith("spec-")) {
            // Unit tests run with the module directory as working directory.
            val file = File("../../spec/fixtures/${name.removePrefix("spec-")}")
            check(file.exists()) { "missing spec fixture ${file.absolutePath}" }
            file.readText(Charsets.UTF_8)
        } else {
            val stream = Fixtures::class.java.classLoader!!.getResourceAsStream(name)
                ?: error("missing test resource $name")
            stream.bufferedReader(Charsets.UTF_8).readText()
        }
        return Json.parseToJsonElement(text).jsonObject
    }

    val nip44: JsonObject by lazy { load("nip44.vectors.json") }
    val identity: JsonObject by lazy { load("spec-01-identity-keys-vectors.json") }
    val badges: JsonObject by lazy { load("spec-02-badges-claim-vectors.json") }
    val trust: JsonObject by lazy { load("spec-04-trust-reputation-vectors.json") }
    val nostrInfra: JsonObject by lazy { load("spec-07-nostr-infra-vectors.json") }
    val backup: JsonObject by lazy { load("spec-08-backup-sync-vectors.json") }
    val migration: JsonObject by lazy { load("spec-14-platform-migration-vectors.json") }
}

internal fun JsonElement.obj(key: String): JsonObject = jsonObject.getValue(key).jsonObject
internal fun JsonElement.arr(key: String): JsonArray = jsonObject.getValue(key).jsonArray
internal fun JsonElement.str(key: String): String = jsonObject.getValue(key).jsonPrimitive.content
internal fun JsonElement.int(key: String): Int = jsonObject.getValue(key).jsonPrimitive.int
internal fun JsonElement.long(key: String): Long = jsonObject.getValue(key).jsonPrimitive.long
internal fun JsonElement.tags(key: String): List<List<String>> =
    arr(key).map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } }
