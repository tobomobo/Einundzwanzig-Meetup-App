package space.einundzwanzig.meetup.data.badgesync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.dart.dartToInt
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.DartNumber
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.crypto.Sha256
import space.einundzwanzig.meetup.data.badges.model.BadgeIdentity
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.util.DartSort

/**
 * Pure functions of the badge sync format (Dart `BadgeSyncFormat`, spec 08 §3.4, §7.4, §7.5,
 * §8.13, §8.14): envelope, deterministic sorting and chunking, hashes, the NIP-44 payload size
 * estimate and the merge rule. No network, no signer.
 *
 * Every string produced here is byte-identical to the Flutter app (Dart `jsonEncode` via
 * [DartJson], badge JSON via [MeetupBadge.toJson]).
 */
object BadgeSyncFormat {

    /** NIP-78 app data, addressable: one event per chunk with `d = badges-<i>`. */
    const val KIND: Int = 30078

    /** Readers query `badges-0` … `badges-15`; never write more chunks than that. */
    const val MAX_CHUNKS: Int = 16

    /** NIP-44 payload characters per event (from the Flutter relay probe). */
    const val CHUNK_PAYLOAD_CHARS: Int = 30_000

    /** The `client` tag value of every chunk event. */
    const val CLIENT_TAG: String = "einundzwanzig-meetup-app"

    /** `d` tag of chunk [i]: `badges-<i>`. */
    fun dTag(i: Int): String = "badges-$i"

    /** All `d` tags a reader asks for (`badges-0` … `badges-15`). */
    val D_TAGS: List<String> = List(MAX_CHUNKS) { dTag(it) }

    /**
     * Sorted by meetup `date` (instant), ties by `id` (UTF-16 code units), with Dart's
     * `List.sort` algorithm ([DartSort]) so even equal keys end up in Dart's order.
     */
    fun sortBadges(badges: Iterable<MeetupBadge>): List<MeetupBadge> =
        DartSort.sorted(badges) { a, b ->
            val byDate = a.date.compareTo(b.date)
            if (byDate != 0) byDate else a.id.compareTo(b.id)
        }

    /**
     * The envelope JSON of chunk [index] of [total]:
     * `{"v":1,"i":…,"n":…,"gen":…,"badges":[…]}` (exact key order, no whitespace).
     */
    fun envelopeJson(badges: List<MeetupBadge>, index: Int, total: Int, generation: Long): String =
        DartJson.encode(
            jsonObjectOf(
                "v" to 1,
                "i" to index,
                "n" to total,
                "gen" to generation,
                "badges" to JsonArray(badges.map { it.toJson() }),
            ),
        )

    /**
     * Distributes [badges] (sorted first) over chunks so that [measure] of each chunk's envelope
     * (generation 0, total = chunks so far + 2) stays ≤ [CHUNK_PAYLOAD_CHARS]. A single badge
     * above the limit gets a chunk of its own. Deterministic: the same set in any order gives the
     * same chunks.
     *
     * @param measure the NIP-44 payload length that the given envelope plaintext would produce.
     */
    fun chunkBadges(badges: List<MeetupBadge>, measure: (plaintext: String) -> Int): List<List<MeetupBadge>> {
        if (badges.isEmpty()) return emptyList()
        val sorted = sortBadges(badges)
        val chunks = mutableListOf<List<MeetupBadge>>()
        var current = mutableListOf<MeetupBadge>()
        for (badge in sorted) {
            current.add(badge)
            val total = chunks.size + 2 // one more chunk is possible
            val length = measure(envelopeJson(current, chunks.size, total, 0))
            if (length > CHUNK_PAYLOAD_CHARS) {
                val last = current.removeAt(current.lastIndex)
                if (current.isEmpty()) {
                    // A single badge above the limit: store it alone instead of looping forever.
                    chunks.add(listOf(last))
                    current = mutableListOf()
                    continue
                }
                chunks.add(current)
                current = mutableListOf(last)
            }
        }
        if (current.isNotEmpty()) chunks.add(current)
        return chunks
    }

    /** The envelope plaintexts of all chunks of [badges] with [generation]. */
    fun plaintexts(badges: List<MeetupBadge>, generation: Long, measure: (plaintext: String) -> Int): List<String> {
        val chunks = chunkBadges(badges, measure)
        return chunks.mapIndexed { i, chunk -> envelopeJson(chunk, i, chunks.size, generation) }
    }

    /** SHA-256 (lowercase hex) of the UTF-8 [plaintext]. */
    fun chunkHash(plaintext: String): String = Sha256.hexOfUtf8(plaintext)

    /**
     * Hash of the chunk content only (not of `i`, `n`, `gen`): `chunkHash({"v":1,"badges":[…]})`
     * of the re-encoded parsed badges. Otherwise every new generation would republish every chunk.
     *
     * @throws IllegalArgumentException if [plaintext] is not a valid envelope ([parseEnvelope]).
     */
    fun contentHash(plaintext: String): String {
        val env = parseEnvelope(plaintext)
        return chunkHash(
            DartJson.encode(jsonObjectOf("v" to 1, "badges" to JsonArray(env.badges.map { it.toJson() }))),
        )
    }

    /**
     * NIP-44 v2 payload length estimate for [gzipBytes] plaintext bytes:
     * `base64(1 + 32 + padded + 32)` characters. Verbatim Dart, including its quirk that the real
     * plaintext is the (longer) base64 of the gzip bytes.
     */
    fun payloadCharsForGzip(gzipBytes: Int): Int {
        val padded = padLen(gzipBytes.toLong())
        return (((1 + 32 + padded + 32 + 2) / 3) * 4).toInt()
    }

    private fun padLen(len: Long): Long {
        if (len <= 32) return 32
        var power = 1L
        while (power < len) power = power shl 1
        val chunk = if (power <= 256) 32 else power / 8
        return chunk * ((len - 1) / chunk + 1)
    }

    /**
     * Parses an envelope (Dart `parseEnvelope`). Defective badge entries are dropped one by one
     * instead of discarding the whole chunk; `i`/`n`/`gen` default to 0/1/0.
     *
     * @throws IllegalArgumentException ([BadgeSyncFormatException] or
     *   [space.einundzwanzig.meetup.core.common.json.DartJsonFormatException]) for invalid JSON,
     *   a non-object, `v != 1`, a missing `badges` list or a non-numeric `i`/`n`/`gen`.
     */
    fun parseEnvelope(plaintext: String): Envelope {
        val map = DartJson.decode(plaintext) as? JsonObject
            ?: throw BadgeSyncFormatException("Hülle ist kein Objekt")
        val v = map["v"]
        if (numberOf(v, "v")?.toDouble() != 1.0) {
            throw BadgeSyncFormatException("Hülle v${dartToString(v)} wird nicht unterstützt")
        }
        val raw = map["badges"] as? JsonArray ?: throw BadgeSyncFormatException("Hülle ohne badges")
        val badges = raw.mapNotNull { entry ->
            if (entry !is JsonObject) return@mapNotNull null
            try {
                MeetupBadge.fromJson(entry)
            } catch (_: Exception) {
                null // a defective badge is dropped, not the whole chunk
            }
        }
        return Envelope(
            index = numberOf(map["i"], "i")?.toDartInt()?.toInt() ?: 0,
            total = numberOf(map["n"], "n")?.toDartInt()?.toInt() ?: 1,
            generation = numberOf(map["gen"], "gen")?.toDartInt() ?: 0L,
            badges = badges,
        )
    }

    /**
     * Union per event identity (local wins ties), sorted: `sortBadges(unionByIdentity(local, remote))`.
     */
    fun merge(local: List<MeetupBadge>, remote: List<MeetupBadge>): List<MeetupBadge> =
        sortBadges(BadgeIdentity.unionByIdentity(local, remote))

    /** Dart `(map[key] as num?)`: `null` for absent/null, throws for anything that is not a number. */
    private fun numberOf(element: JsonElement?, key: String): DartNumber? {
        if (element == null || element is JsonNull) return null
        val number = (element as? JsonPrimitive)?.let { DartJson.numberOf(it) }
        return number ?: throw BadgeSyncFormatException("Hülle: '$key' ist keine Zahl")
    }

    private fun DartNumber.toDartInt(): Long = when (this) {
        is DartNumber.Int -> value
        is DartNumber.Double -> value.dartToInt()
    }

    /** `${map['v']}` for the error message (absent → `null`). */
    private fun dartToString(element: JsonElement?): String = when (element) {
        null, JsonNull -> "null"
        is JsonPrimitive -> element.content
        else -> DartJson.encode(element)
    }
}

/**
 * A parsed sync envelope ([BadgeSyncFormat.parseEnvelope]). [index] and [total] are informative
 * only (readers do no completeness check).
 */
data class Envelope(
    val index: Int,
    val total: Int,
    val generation: Long,
    val badges: List<MeetupBadge>,
)

/** A sync envelope that Dart's `parseEnvelope` rejects with a `FormatException`. */
class BadgeSyncFormatException(message: String) : IllegalArgumentException(message)
