package space.einundzwanzig.meetup.data.reputation.event

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.core.common.json.dartNumOrNull
import space.einundzwanzig.meetup.core.common.json.dartStringOrNull
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.data.proofs.zap.ZapStats
import space.einundzwanzig.meetup.data.reputation.internal.lenientInt
import space.einundzwanzig.meetup.data.reputation.internal.lenientLong
import space.einundzwanzig.meetup.data.socialgraph.social.SocialStats

/**
 * Dart `ReputationEvent.fromNostrEvent` (spec 04 §3.8). Does **not** verify the event: callers
 * pass events that already went through `EventGuard` (the [ReputationFetcher][space.einundzwanzig.meetup.data.reputation.fetch.ReputationFetcher]
 * does) or that they verified themselves.
 *
 * Type strictness (spec 04 §3.8, §10.4, documented choice): the parser is **lenient**. Dart's
 * `as int?` casts throw on `3.0` or `"3"` and Flutter then drops the whole event; here an
 * integral double (`3.0`) reads as the int, and any other wrong-typed value (string for a
 * number, number for a string, array for an object …) falls back to the field default. Only a
 * `content` that is not a JSON object makes the event unusable (`null`), as in Dart. The writer
 * ([space.einundzwanzig.meetup.data.reputation.content.ReputationContentBuilder]) emits exactly
 * the Dart types, so Flutter readers accept native events.
 */
object ReputationEventParser {

    /** Parses [event]; `null` if its content is not a JSON object. */
    fun parse(event: NostrEvent): ReputationEvent? =
        parse(event.pubkey, event.id, event.sig, event.createdAt, event.content)

    /**
     * Parses a raw relay event object (as returned by `RelayClient`), with the Dart
     * constructor defaults for missing fields (`''`, `0`). `null` if `content` is not a JSON
     * object text.
     */
    fun parseRaw(raw: JsonObject): ReputationEvent? = parse(
        pubkeyHex = raw["pubkey"].dartStringOrNull() ?: "",
        eventId = raw["id"].dartStringOrNull() ?: "",
        signature = raw["sig"].dartStringOrNull() ?: "",
        createdAt = raw["created_at"].dartIntOrNull() ?: 0L,
        contentText = raw["content"].dartStringOrNull() ?: "",
    )

    /** Parses the event fields; `null` if [contentText] is not a JSON object. */
    fun parse(pubkeyHex: String, eventId: String, signature: String, createdAt: Long, contentText: String): ReputationEvent? {
        val content = try {
            DartJson.decode(contentText)
        } catch (_: Exception) {
            return null
        } as? JsonObject ?: return null
        return fromContent(pubkeyHex, eventId, signature, createdAt, content)
    }

    /** Dart `ReputationEvent.fromNostrEvent(event, content)` on an already decoded [content]. */
    fun fromContent(pubkeyHex: String, eventId: String, signature: String, createdAt: Long, content: JsonObject): ReputationEvent {
        val identity = content["identity"] as? JsonObject ?: EMPTY
        val stats = content["stats"] as? JsonObject ?: EMPTY
        val proof = content["proof"] as? JsonObject ?: EMPTY
        val platforms = content["platform_proofs"] as? JsonObject ?: EMPTY
        val social = content["social"] as? JsonObject
        val lightning = content["lightning"] as? JsonObject
        val humanity = content["humanity_proof"] as? JsonObject

        val npub = try {
            Nip19.encodeNpub(pubkeyHex)
        } catch (_: Exception) {
            ""
        }

        return ReputationEvent(
            pubkeyHex = pubkeyHex,
            npub = npub,
            eventId = eventId,
            signature = signature,
            createdAt = createdAt,
            version = content["version"].lenientInt() ?: 1,
            nickname = identity["nickname"].dartStringOrNull() ?: "Anon",
            score = stats["score"].dartNumOrNull() ?: 0.0,
            level = stats["level"].dartStringOrNull() ?: "NEU",
            totalBadges = stats["total_badges"].lenientInt() ?: 0,
            verifiedBadges = stats["verified_badges"].lenientInt() ?: 0,
            boundBadges = stats["bound_badges"].lenientInt() ?: 0,
            retroactiveBadges = stats["retroactive_badges"].lenientInt() ?: 0,
            meetupCount = stats["meetup_count"].lenientInt() ?: 0,
            signerCount = stats["signer_count"].lenientInt() ?: 0,
            accountAgeDays = stats["account_age_days"].lenientInt() ?: 0,
            since = stats["since"].dartStringOrNull() ?: "",
            badgeProofHash = proof["badge_proof_hash"].dartStringOrNull() ?: "",
            proofVersion = proof["proof_version"].lenientInt() ?: 1,
            platformProofs = LinkedHashMap(platforms),
            socialStats = social?.let {
                SocialStats(
                    followCount = it[SocialStats.KEY_FOLLOWS].lenientInt() ?: 0,
                    estimatedMutualCount = it[SocialStats.KEY_MUTUALS].lenientInt() ?: 0,
                    orgFollowerCount = it[SocialStats.KEY_ORG_FOLLOWERS].lenientInt() ?: 0,
                )
            },
            zapStats = lightning?.let(ZapStats::fromJson),
            humanityVerified = (humanity?.get("verified") as? JsonPrimitive)
                ?.takeIf { !it.isString }?.booleanOrNull ?: false,
            humanityReceiptId = humanity?.get("receipt_event_id").dartStringOrNull(),
            updatedAt = content["updated_at"].lenientLong() ?: createdAt,
        )
    }

    private val EMPTY = JsonObject(emptyMap())
}
