package space.einundzwanzig.meetup.core.nostr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.core.common.json.dartStringOrNull
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.nostr.internal.dartContains
import space.einundzwanzig.meetup.core.nostr.internal.dartEquals
import space.einundzwanzig.meetup.core.nostr.internal.dartScalarToString
import space.einundzwanzig.meetup.core.nostr.internal.dartToLogString
import java.util.concurrent.atomic.AtomicInteger

/**
 * The central guard for events that arrive from relays (Dart `RelaySocket.verifiedEvent`,
 * `answersFilter`, `replaceableCreatedAt`; Security Audit H2/M1 and commit 263648a).
 *
 * Every `["EVENT", sub, {...}]` must pass, in this order:
 * 1. [verifiedEvent] — id recomputation and BIP-340 signature (proves the event was signed so),
 * 2. [answersFilter] with the exact filter that was sent (proves it answers the question),
 * 3. for "newest wins" decisions, [replaceableCreatedAt] (a timestamp > 10 min in the future
 *    must not win forever).
 *
 * All functions work on the **raw** decoded object (Dart `Map<String, dynamic>`), never throw,
 * and use Dart's value semantics (`1 == 1.0`, `created_at` must be an int, …).
 * `RelayClient` applies 1 and 2 automatically.
 */
object EventGuard {

    /** Seconds a replaceable event's `created_at` may lie in the future and still win. */
    const val FUTURE_TOLERANCE_SECONDS: Long = 600

    private val rejected = AtomicInteger()

    /** Events rejected by [verifiedEvent] since process start (Dart `rejectedEventCount`). */
    val rejectedEventCount: Int get() = rejected.get()

    /**
     * Returns [raw] if it is an object whose id and signature are valid, else `null`.
     *
     * Mirrors the Dart construction `Event(raw['id'] ?? '', raw['pubkey'] ?? '', raw['created_at'] ?? 0,
     * raw['kind'] ?? 0, tags.map(toString), raw['content'] ?? '', raw['sig'] ?? '').isValid()`:
     * missing or `null` fields take those defaults, wrong JSON types reject, scalar tag values
     * are stringified like Dart `toString()` before the id is recomputed.
     *
     * A non-object [raw] returns `null` without counting (as in Dart). Every other rejection
     * increments [rejectedEventCount] and logs on `debug` at the 1st and every 10th:
     * `Event mit ungültiger Signatur verworfen (kind <kind>, gesamt: <n>)`.
     *
     * @param tag log tag of the caller.
     */
    fun verifiedEvent(raw: JsonElement?, tag: String = "RelaySocket"): JsonObject? {
        if (raw !is JsonObject) return null
        val ok = try {
            isValidRaw(raw)
        } catch (_: Exception) {
            false
        }
        if (ok) return raw
        val count = rejected.incrementAndGet()
        if (count == 1 || count % 10 == 0) {
            AppLogger.debug(
                tag,
                "Event mit ungültiger Signatur verworfen (kind ${dartToLogString(raw["kind"])}, gesamt: $count)",
            )
        }
        return null
    }

    private fun isValidRaw(raw: JsonObject): Boolean {
        val id = stringOrDefault(raw["id"]) ?: return false
        val pubkey = stringOrDefault(raw["pubkey"]) ?: return false
        val createdAt = intOrDefault(raw["created_at"]) ?: return false
        val kind = intOrDefault(raw["kind"])?.toIntExactOrNull() ?: return false
        val tags = tagsOrDefault(raw["tags"]) ?: return false
        val content = stringOrDefault(raw["content"]) ?: return false
        val sig = stringOrDefault(raw["sig"]) ?: return false
        return EventIds.isValid(id, pubkey, createdAt, kind, tags, content, sig)
    }

    /** `raw[k] ?? ''` typed as String: absent/null → "", string → value, else reject (null). */
    private fun stringOrDefault(e: JsonElement?): String? =
        if (e.isDartNull()) "" else e.dartStringOrNull()

    /** `raw[k] ?? 0` typed as int. */
    private fun intOrDefault(e: JsonElement?): Long? = if (e.isDartNull()) 0L else e.dartIntOrNull()

    /** `(raw['tags'] as List?)?.map((t) => (t as List).map((e) => e.toString()).toList()) ?? []`. */
    private fun tagsOrDefault(e: JsonElement?): List<List<String>>? {
        if (e.isDartNull()) return emptyList()
        if (e !is JsonArray) return null
        return e.map { tag ->
            if (tag !is JsonArray) return null
            tag.map { dartScalarToString(it) ?: return null }
        }
    }

    /**
     * Whether [event] satisfies the fields of [filter] — the same object that was sent in the REQ.
     *
     * - `kinds` (if a list) must contain `event.kind`; `authors` (if a list) must contain
     *   `event.pubkey` (exact, Dart equality).
     * - If `since` or `until` is present, `created_at` must be an int; an int `since` requires
     *   `created_at >= since`, an int `until` requires `created_at <= until`.
     * - Every key `#X` (length ≥ 2) needs a non-empty list value and a tag `[X, v, …]` in the
     *   event with `v` in that list. Tag names are case-sensitive (`#p` ≠ `#P`).
     * - `limit`, `ids` and all other keys are ignored.
     */
    fun answersFilter(event: JsonObject, filter: JsonObject): Boolean {
        val kinds = filter["kinds"]
        if (kinds is JsonArray && !kinds.dartContains(event["kind"])) return false

        val authors = filter["authors"]
        if (authors is JsonArray && !authors.dartContains(event["pubkey"])) return false

        val since = filter["since"]
        val until = filter["until"]
        if (!since.isDartNull() || !until.isDartNull()) {
            val created = event["created_at"].dartIntOrNull() ?: return false
            val sinceInt = since.dartIntOrNull()
            val untilInt = until.dartIntOrNull()
            if (sinceInt != null && created < sinceInt) return false
            if (untilInt != null && created > untilInt) return false
        }

        val tags = event["tags"]
        for ((key, wanted) in filter) {
            if (!key.startsWith("#") || key.length < 2) continue
            if (wanted !is JsonArray || wanted.isEmpty()) return false
            if (tags !is JsonArray) return false
            val name = key.substring(1)
            val hit = tags.any { tag ->
                tag is JsonArray &&
                    tag.size >= 2 &&
                    tag[0].dartStringOrNull() == name &&
                    wanted.any { dartEquals(it, tag[1]) }
            }
            if (!hit) return false
        }
        return true
    }

    /** [answersFilter] with a [NostrFilter] (uses its exact JSON). */
    fun answersFilter(event: JsonObject, filter: NostrFilter): Boolean = answersFilter(event, filter.toJson())

    /** `true` if [event] answers at least one of [filters] (a REQ with several filters). */
    fun answersAnyFilter(event: JsonObject, filters: List<JsonObject>): Boolean =
        filters.any { answersFilter(event, it) }

    /**
     * `created_at` of a replaceable event that may count as "the newest": an int `>= 0` and at
     * most [FUTURE_TOLERANCE_SECONDS] after [nowSeconds]; otherwise `null`.
     */
    fun replaceableCreatedAt(event: JsonObject, nowSeconds: Long): Long? {
        val at = event["created_at"].dartIntOrNull() ?: return null
        if (at < 0) return null
        if (at > nowSeconds + FUTURE_TOLERANCE_SECONDS) return null
        return at
    }

    /** [replaceableCreatedAt] with the current time of [time]. */
    fun replaceableCreatedAt(event: JsonObject, time: TimeSource): Long? =
        replaceableCreatedAt(event, time.nowSeconds())
}
