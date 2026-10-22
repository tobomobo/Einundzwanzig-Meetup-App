package space.einundzwanzig.meetup.data.socialgraph.social

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf

/**
 * Result of the NIP-02 social analysis between the user and one target (Dart `SocialAnalysis`).
 *
 * @property targetPubkey target pubkey hex; `""` for [empty].
 * @property hops `1` (direct, mutual or one-way), `2` (common contacts) or `-1` (no connection).
 */
data class SocialAnalysis(
    val targetPubkey: String,
    val myFollowCount: Int,
    val targetFollowCount: Int,
    val iFollow: Boolean,
    val followsMe: Boolean,
    val isMutual: Boolean,
    val commonContactCount: Int,
    val orgFollowerCount: Int,
    val hops: Int,
) {
    /**
     * Social trust score `0.0..3.0`, verbatim from Dart: mutual +1.0 (one-way +0.3), common
     * contacts `n / (n + 5)`, organizer followers `min(n * 0.5, 1.0)`.
     */
    val socialScore: Double
        get() {
            var score = 0.0
            if (isMutual) {
                score += 1.0
            } else if (iFollow || followsMe) {
                score += 0.3
            }
            if (commonContactCount > 0) {
                score += (commonContactCount.toDouble() / (commonContactCount + 5)) * 1.0
            }
            score += (orgFollowerCount * 0.5).coerceIn(0.0, 1.0)
            return score.coerceIn(0.0, 3.0)
        }

    /** Hard-coded German label (not localized in Flutter either). */
    val connectionLabel: String
        get() = when {
            isMutual -> "Gegenseitig verbunden"
            iFollow -> "Du folgst"
            followsMe -> "Folgt dir"
            commonContactCount > 0 -> "$commonContactCount gemeinsame Kontakte"
            else -> "Keine Verbindung"
        }

    companion object {
        /** Dart `SocialAnalysis.empty()`. */
        fun empty(): SocialAnalysis = SocialAnalysis("", 0, 0, false, false, false, 0, 0, -1)
    }
}

/**
 * Aggregated social numbers of the user (Dart `SocialStats`), embedded by the reputation
 * publisher under `content.social`.
 */
data class SocialStats(
    val followCount: Int,
    val estimatedMutualCount: Int,
    val orgFollowerCount: Int,
) {
    /** `{"follows": …, "mutuals": …, "org_followers": …}` in this key order. */
    fun toJson(): JsonObject = jsonObjectOf(
        KEY_FOLLOWS to followCount,
        KEY_MUTUALS to estimatedMutualCount,
        KEY_ORG_FOLLOWERS to orgFollowerCount,
    )

    companion object {
        const val KEY_FOLLOWS = "follows"
        const val KEY_MUTUALS = "mutuals"
        const val KEY_ORG_FOLLOWERS = "org_followers"

        /** Dart `SocialStats.empty()`. */
        fun empty(): SocialStats = SocialStats(0, 0, 0)

        /** Missing or non-int fields read as `0` (see README deviations). */
        fun fromJson(json: JsonElement?): SocialStats {
            val obj = json as? JsonObject ?: return empty()
            fun int(key: String): Int = obj[key].dartIntOrNull()?.let(::saturatedInt) ?: 0
            return SocialStats(int(KEY_FOLLOWS), int(KEY_MUTUALS), int(KEY_ORG_FOLLOWERS))
        }

        private fun saturatedInt(v: Long): Int = v.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }
}
