package space.einundzwanzig.meetup.data.badges.model

import space.einundzwanzig.meetup.core.common.dart.DartDateTime

/**
 * One archived earlier collection (Dart `FormerCollection`, spec 02 §3.3, spec 03 §3.3).
 *
 * @property index position in the **stored** (oldest-first) archive list; pass it to
 *   `BadgeRepository.mergeFormer` — not the display position (the list is shown newest first).
 * @property at when it was archived (local `DateTime` from the stored unix seconds).
 * @property reason `replaced`, `rotated`, `cleared` (or any other stored value).
 * @property npub identity the collection belonged to, may be `""`.
 * @property badges the decrypted badges, or `null` if no key on the device opens the blob.
 */
data class FormerCollection(
    val index: Int,
    val at: DartDateTime,
    val reason: String,
    val npub: String,
    val badges: List<MeetupBadge>?,
)

/**
 * Dart `getReputationStats` (spec 02 §3.8): counts of a collection. [toMap] keeps the Dart key
 * order `total, crypto_proof, claimed, retroactive, fully_trusted`.
 */
data class ReputationStats(
    val total: Int,
    val cryptoProof: Int,
    val claimed: Int,
    val retroactive: Int,
    val fullyTrusted: Int,
) {
    /** `{'total', 'crypto_proof', 'claimed', 'retroactive', 'fully_trusted'}` in Dart order. */
    fun toMap(): Map<String, Int> = linkedMapOf(
        "total" to total,
        "crypto_proof" to cryptoProof,
        "claimed" to claimed,
        "retroactive" to retroactive,
        "fully_trusted" to fullyTrusted,
    )
}
