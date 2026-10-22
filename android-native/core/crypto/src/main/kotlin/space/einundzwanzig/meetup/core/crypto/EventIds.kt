package space.einundzwanzig.meetup.core.crypto

import space.einundzwanzig.meetup.core.common.json.DartJson

/** A signed Nostr event as produced by `Event.from` / `SigningService.signEvent`. */
data class SignedNostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    /** `SignedEvent.toJson()` with Dart's key order, encoded like `jsonEncode`. */
    fun toJson(): String = DartJson.encode(toMap())

    /** `["EVENT", <event>]` relay frame (`SignedEvent.toEventMessage()`). */
    fun toEventMessage(): String = DartJson.encode(listOf("EVENT", toMap()))

    /** Re-checks id and signature exactly like `Event.isValid()`. */
    fun isValid(): Boolean = EventIds.isValid(id, pubkey, createdAt, kind, tags, content, sig)

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "pubkey" to pubkey,
        "created_at" to createdAt,
        "kind" to kind,
        "tags" to tags,
        "content" to content,
        "sig" to sig,
    )
}

/**
 * NIP-01 event ids, signing and validation, byte-compatible with `package:nostr` 1.5.0
 * (`Event._processEventId`, `Event.from`, `Event.isValid`).
 */
object EventIds {
    /** `json.encode([0, pubkey.toLowerCase(), createdAt, kind, tags, content])`. */
    fun serialize(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
        val sb = StringBuilder(128 + content.length)
        sb.append("[0,").append(DartJson.quote(pubkey.lowercase())).append(',')
            .append(createdAt).append(',').append(kind).append(",[")
        tags.forEachIndexed { i, tag ->
            if (i > 0) sb.append(',')
            sb.append('[')
            tag.forEachIndexed { j, value ->
                if (j > 0) sb.append(',')
                sb.append(DartJson.quote(value))
            }
            sb.append(']')
        }
        sb.append("],").append(DartJson.quote(content)).append(']')
        return sb.toString()
    }

    /** SHA-256 of the UTF-8 serialization (32 bytes). */
    fun computeBytes(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): ByteArray =
        Sha256.digest(DartUtf8.encode(serialize(pubkey, createdAt, kind, tags, content)))

    /** The event id as 64 lowercase hex characters. */
    fun compute(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String =
        computeBytes(pubkey, createdAt, kind, tags, content).toHex()

    /**
     * `Event.from(...)`: derives the pubkey, computes the id and signs it with BIP-340.
     * [auxRand] defaults to fresh randomness (`generate64RandomHexChars()`).
     */
    fun sign(
        keyPair: KeyPair,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
        auxRand: ByteArray = CryptoRandom.bytes(32),
    ): SignedNostrEvent {
        val pubkey = keyPair.publicKeyHex
        val id = computeBytes(pubkey, createdAt, kind, tags, content)
        val sig = keyPair.sign(id, auxRand)
        return SignedNostrEvent(id.toHex(), pubkey, createdAt, kind, tags, content, sig.toHex())
    }

    /**
     * `Event(id, pubkey, createdAt, kind, tags, content, sig, verify: false).isValid()`:
     * - the pubkey is lower-cased first (the Dart constructor does that),
     * - `createdAt.toString()` must be exactly 10 characters long,
     * - [id] must equal the recomputed lowercase id (case-sensitive string equality),
     * - the BIP-340 signature over the id must verify.
     * Malformed hex never throws, it is simply invalid.
     */
    fun isValid(
        id: String,
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
        sig: String,
    ): Boolean {
        val pk = pubkey.lowercase()
        if (createdAt.toString().length != 10) return false
        if (id != compute(pk, createdAt, kind, tags, content)) return false
        return Schnorr.verifyHex(pk, id, sig)
    }
}
