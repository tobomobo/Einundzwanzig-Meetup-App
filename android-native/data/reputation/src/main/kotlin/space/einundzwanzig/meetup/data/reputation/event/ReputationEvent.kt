package space.einundzwanzig.meetup.data.reputation.event

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.dart.dartRound
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProof
import space.einundzwanzig.meetup.data.proofs.platform.ReputationFacts
import space.einundzwanzig.meetup.data.proofs.zap.ZapStats
import space.einundzwanzig.meetup.data.socialgraph.social.SocialStats

/**
 * A parsed kind-30078 reputation event (Dart `ReputationEvent`, spec 04 §3.8). Built by
 * [ReputationEventParser]; defaults are those of the Dart parser (`version` 1 when absent).
 *
 * @property npub bech32 of [pubkeyHex], `""` if that fails.
 * @property score `stats.score`, the relay basic score (0..10, one decimal).
 * @property level `stats.level`, the RELAY scale (`TrustLevel.forBasicScore`), not the local one.
 * @property platformProofs `content.platform_proofs` verbatim (values are normally
 *   `{"username","proof_sig","created_at"}` objects); use [platformProof] for a typed entry.
 * @property humanityReceiptId never written by the app; kept for completeness.
 * @property updatedAt `content.updated_at` (unix seconds), [createdAt] when absent.
 */
data class ReputationEvent(
    val pubkeyHex: String,
    val npub: String,
    val eventId: String,
    val signature: String,
    val createdAt: Long,
    val version: Int = 1,
    val nickname: String = "Anon",
    val score: Double = 0.0,
    val level: String = "NEU",
    val totalBadges: Int = 0,
    val verifiedBadges: Int = 0,
    val boundBadges: Int = 0,
    val retroactiveBadges: Int = 0,
    val meetupCount: Int = 0,
    val signerCount: Int = 0,
    val accountAgeDays: Int = 0,
    val since: String = "",
    val badgeProofHash: String = "",
    val proofVersion: Int = 1,
    val platformProofs: Map<String, JsonElement> = emptyMap(),
    val socialStats: SocialStats? = null,
    val zapStats: ZapStats? = null,
    val humanityVerified: Boolean = false,
    val humanityReceiptId: String? = null,
    val updatedAt: Long = createdAt,
) {
    /** Dart `ageInHours`: `((now − createdAt) / 3600).round()` (half away from zero). */
    fun ageInHours(nowSeconds: Long): Long = ((nowSeconds - createdAt) / 3600.0).dartRound()

    /** Dart `isFresh`: younger than 7 days. */
    fun isFresh(nowSeconds: Long): Boolean = ageInHours(nowSeconds) < 7 * 24

    /** The typed platform proof for [platform], or `null` if absent or not an object. */
    fun platformProof(platform: String): PlatformProof? =
        (platformProofs[platform] as? JsonObject)?.let { PlatformProof.fromReputationJson(platform, it) }

    /** The facts `PlatformProofVerifier` (`:data:proofs`) decides on. */
    fun toFacts(): ReputationFacts = ReputationFacts(boundBadges, totalBadges, platformProofs)
}
