package space.einundzwanzig.meetup.data.socialgraph.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.dart.toDartString
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.DartNumber
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.describeError
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.data.socialgraph.coattendance.CoAttNode

/**
 * The stored connection graph (Dart `_GraphCache`, pref `coatt_graph_cache_v1`):
 * `{"v":1,"owner","full","sync","ka","kk","n":{npub:{"m":[…],"e":[…]}}}` (`e` omitted when empty).
 */
internal data class GraphCache(
    val owner: String,
    val fullSyncAt: Long,
    val syncAt: Long,
    val knownAuthors: Set<String>,
    val knownKeys: Set<String>,
    val nodes: Map<String, CoAttNode>,
) {
    /** Dart `toJson()` (key order kept). */
    fun toJson(): Map<String, Any?> {
        val n = LinkedHashMap<String, Any?>()
        for ((npub, node) in nodes) {
            val entry = LinkedHashMap<String, Any?>()
            entry["m"] = node.meetups.toList()
            if (node.events.isNotEmpty()) entry["e"] = node.events.toList()
            n[npub] = entry
        }
        return linkedMapOf(
            "v" to VERSION,
            "owner" to owner,
            "full" to fullSyncAt,
            "sync" to syncAt,
            "ka" to knownAuthors.toList(),
            "kk" to knownKeys.toList(),
            "n" to n,
        )
    }

    fun encode(): String = DartJson.encode(toJson())

    companion object {
        const val KEY = "coatt_graph_cache_v1"
        const val VERSION = 1

        /**
         * Dart `_GraphCache.fromJson`: `null` if `v != 1`; `full`/`sync` must be ints (else 0);
         * list elements are stringified like Dart `toString()`; a non-map node gets empty sets.
         * Throws if [json] is not an object (the caller logs and rebuilds).
         */
        fun fromJson(json: JsonElement): GraphCache? {
            val j = json as? JsonObject ?: throw IllegalArgumentException("Cache ist kein JSON-Objekt")
            if (!isDartOne(j["v"])) return null
            val nodes = LinkedHashMap<String, CoAttNode>()
            val raw = j["n"]
            if (raw is JsonObject) {
                for ((k, v) in raw) {
                    val node = CoAttNode(k)
                    if (v is JsonObject) {
                        node.meetups.addAll(strings(v["m"]))
                        node.events.addAll(strings(v["e"]))
                    }
                    nodes[node.npub] = node
                }
            }
            return GraphCache(
                owner = j["owner"]?.takeUnless { it is JsonNull }?.let(::dartToString) ?: "",
                fullSyncAt = j["full"].dartIntOrNull() ?: 0L,
                syncAt = j["sync"].dartIntOrNull() ?: 0L,
                knownAuthors = strings(j["ka"]).toCollection(LinkedHashSet()),
                knownKeys = strings(j["kk"]).toCollection(LinkedHashSet()),
                nodes = nodes,
            )
        }

        /** Dart `j['v'] != 1` is false for `1` and `1.0` (num equality). */
        private fun isDartOne(v: JsonElement?): Boolean {
            if (v !is JsonPrimitive || v.isString) return false
            return when (val n = runCatching { DartJson.numberOf(v) }.getOrNull()) {
                is DartNumber.Int -> n.value == 1L
                is DartNumber.Double -> n.value == 1.0
                null -> false
            }
        }

        private fun strings(v: JsonElement?): List<String> = (v as? JsonArray)?.map(::dartToString) ?: emptyList()

        /** Reads and parses the cache; `null` if absent, unreadable (logged) or of another version. */
        suspend fun read(store: KeyValueStore, log: DiagnosticLog): GraphCache? = try {
            store.getString(KEY)?.let { fromJson(DartJson.decode(it)) }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Netzwerk", "Cache nicht lesbar, wird neu aufgebaut: ${describeError(e)}")
            null
        }

        /** Writes the cache; failures are logged, never thrown. */
        suspend fun write(store: KeyValueStore, cache: GraphCache, log: DiagnosticLog) {
            try {
                if (!store.setString(KEY, cache.encode())) log.warn("Netzwerk", "Cache nicht gespeichert: Schreibfehler")
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Netzwerk", "Cache nicht gespeichert: ${describeError(e)}")
            }
        }
    }
}

/** Dart `Object.toString()` of a `jsonDecode` value. */
internal fun dartToString(e: JsonElement): String = when (e) {
    is JsonNull -> "null"
    is JsonPrimitive -> if (e.isString) {
        e.content
    } else {
        when (val n = runCatching { DartJson.numberOf(e) }.getOrNull()) {
            is DartNumber.Int -> n.value.toString()
            is DartNumber.Double -> n.value.toDartString()
            null -> e.content
        }
    }
    is JsonArray -> e.joinToString(", ", "[", "]") { dartToString(it) }
    is JsonObject -> e.entries.joinToString(", ", "{", "}") { (k, v) -> "$k: ${dartToString(v)}" }
}
