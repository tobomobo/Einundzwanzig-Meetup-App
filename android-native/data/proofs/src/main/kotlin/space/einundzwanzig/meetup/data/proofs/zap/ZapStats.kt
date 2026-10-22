package space.einundzwanzig.meetup.data.proofs.zap

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.core.common.json.dartNumOrNull
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import kotlin.math.ceil

/** Activity class of [ZapStats.activity] (Dart `activityLabel`). */
enum class ZapActivity(
    /** The German label of the Flutter app. */
    val german: String,
) {
    NONE("Keine Zap-Aktivität"),
    LITTLE("Wenig Aktivität"),
    REGULAR("Regelmäßig aktiv"),
    VERY_ACTIVE("Sehr aktiv"),
}

/**
 * Lightning-layer statistics over the last 180 days (Dart `ZapStats`, spec 06 §3.13). Used as
 * the kind-30078 `lightning` block (when [totalCount] > 0) and as the cache value.
 */
data class ZapStats(
    val sentCount: Int,
    val receivedCount: Int,
    val uniqueRecipientCount: Int,
    val uniqueSenderCount: Int,
    val activeMonths: Int,
    val hasLightningProof: Boolean,
) {
    /** `sent + received`. */
    val totalCount: Int get() = sentCount + receivedCount

    /** Dart `lightningScore` (0..2.5), constants exact. */
    val lightningScore: Double
        get() {
            var score = 0.0
            if (hasLightningProof) score += 0.5
            if (sentCount > 0) score += (sentCount.toDouble() / (sentCount + 10)) * 0.75
            if (receivedCount > 0) score += (receivedCount.toDouble() / (receivedCount + 10)) * 0.75
            if (uniqueRecipientCount > 2) score += 0.25
            if (activeMonths >= 3) score += 0.25
            return score.coerceIn(0.0, 2.5)
        }

    /** Dart `activityLabel` as a type: 0 → none, < 5 → little, < 20 → regular, else very active. */
    val activity: ZapActivity
        get() = when {
            totalCount == 0 -> ZapActivity.NONE
            totalCount < 5 -> ZapActivity.LITTLE
            totalCount < 20 -> ZapActivity.REGULAR
            else -> ZapActivity.VERY_ACTIVE
        }

    /** The German label (Dart `activityLabel`). */
    val activityLabel: String get() = activity.german

    /**
     * Dart `toJson`, key order `sent, received, unique_recipients, unique_senders, active_months,
     * lightning_proof`; integers are always written as ints.
     */
    fun toJson(): JsonObject = jsonObjectOf(
        "sent" to sentCount,
        "received" to receivedCount,
        "unique_recipients" to uniqueRecipientCount,
        "unique_senders" to uniqueSenderCount,
        "active_months" to activeMonths,
        "lightning_proof" to hasLightningProof,
    )

    companion object {
        /** Dart `ZapStats.empty()`. */
        val EMPTY: ZapStats = ZapStats(0, 0, 0, 0, 0, false)

        /**
         * Dart `ZapStats.fromJson`, but lenient (spec 06 §14 R12): an integral double such as
         * `3.0` is accepted, and a value of the wrong type reads as 0/false instead of throwing
         * (Dart's `as int?` would reject the whole reputation event).
         */
        fun fromJson(json: JsonObject): ZapStats = ZapStats(
            sentCount = json["sent"].lenientInt(),
            receivedCount = json["received"].lenientInt(),
            uniqueRecipientCount = json["unique_recipients"].lenientInt(),
            uniqueSenderCount = json["unique_senders"].lenientInt(),
            activeMonths = json["active_months"].lenientInt(),
            hasLightningProof = (json["lightning_proof"] as? JsonPrimitive)
                ?.takeIf { !it.isString }?.booleanOrNull ?: false,
        )

        /**
         * Dart `_computeStats(pk, received, sent)` (spec 06 §8.5): de-duplicates by event id
         * (later entries overwrite, first position kept), counts sent/received (a self-zap counts
         * in both), unique counterparts, `activeMonths = ceil((last − first) / 30 d)` clamped to
         * 0..24, and `hasLightningProof = any receipt`.
         */
        fun compute(received: List<ZapReceipt>, sent: List<ZapReceipt>): ZapStats {
            val byId = LinkedHashMap<String, ZapReceipt>()
            for (r in received + sent) byId[r.eventId] = r
            val all = byId.values
            val sentZaps = all.filter { it.isSent }
            val receivedZaps = all.filter { it.isReceived }
            var first: Long? = null
            var last: Long? = null
            for (zap in all) {
                if (zap.createdAt > 0) {
                    first = if (first == null || zap.createdAt < first) zap.createdAt else first
                    last = if (last == null || zap.createdAt > last) zap.createdAt else last
                }
            }
            val activeMonths = if (first != null && last != null) {
                activeMonthsOf(last - first)
            } else {
                0
            }
            return ZapStats(
                sentCount = sentZaps.size,
                receivedCount = receivedZaps.size,
                uniqueRecipientCount = sentZaps.map { it.recipientPubkey }.toSet().size,
                uniqueSenderCount = receivedZaps.map { it.senderPubkey }.filter { it.isNotEmpty() }.toSet().size,
                activeMonths = activeMonths,
                hasLightningProof = all.any { it.hasBolt11 },
            )
        }

        /** `ceil(spanSeconds / 2 592 000.0)` clamped to 0..24 (floating-point division as in Dart). */
        fun activeMonthsOf(spanSeconds: Long): Int =
            ceil(spanSeconds / SECONDS_PER_MONTH).toInt().coerceIn(0, 24)

        private const val SECONDS_PER_MONTH = 30.0 * 24 * 3600

        private fun JsonElement?.lenientInt(): Int {
            val l = dartIntOrNull()
                ?: dartNumOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() && it == Math.floor(it) }?.toLong()
                ?: return 0
            return l.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        }
    }
}

/**
 * A locally validated zap receipt relative to one pubkey (Dart `ZapReceipt`).
 *
 * @property hasBolt11 always `true` after [ZapReceiptValidator.checkLocal].
 */
data class ZapReceipt(
    val eventId: String,
    val senderPubkey: String,
    val recipientPubkey: String,
    val createdAt: Long,
    val isSent: Boolean,
    val isReceived: Boolean,
    val hasBolt11: Boolean,
    val receiptPubkey: String = "",
)
