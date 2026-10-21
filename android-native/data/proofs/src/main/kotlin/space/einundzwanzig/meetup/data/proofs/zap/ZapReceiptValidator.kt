package space.einundzwanzig.meetup.data.proofs.zap

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.Bolt11
import space.einundzwanzig.meetup.core.crypto.Sha256
import space.einundzwanzig.meetup.core.nostr.EventGuard
import space.einundzwanzig.meetup.data.proofs.internal.dartNumEquals
import space.einundzwanzig.meetup.data.proofs.internal.dartToStringOrEmpty
import space.einundzwanzig.meetup.data.proofs.internal.dartToStringOrNull

/**
 * Result of [ZapReceiptValidator.checkLocal] (Dart `ZapReceiptCheck`).
 *
 * @property reason one of [ZapReceiptValidator.REASONS]; `ok` on success.
 * @property recipientPubkey first `p` tag of the receipt.
 * @property senderPubkey `pubkey` of the **signed** embedded zap request (never the unsigned `P` tag).
 * @property receiptPubkey `pubkey` of the receipt (the issuer).
 */
data class ZapReceiptCheck(
    val ok: Boolean,
    val reason: String,
    val recipientPubkey: String = "",
    val senderPubkey: String = "",
    val receiptPubkey: String = "",
) {
    companion object {
        /** A failed check with [reason]. */
        fun fail(reason: String): ZapReceiptCheck = ZapReceiptCheck(ok = false, reason = reason)
    }
}

/**
 * Offline zap-receipt validation (Security Audit H3, NIP-57 appendix F; Dart
 * `ZapReceiptValidator.checkLocal`/`descriptionHashOf`). The network half (provider match) is
 * [ZapProviderResolver].
 */
object ZapReceiptValidator {

    /** Kind of a zap receipt. */
    const val KIND_ZAP_RECEIPT: Int = 9735

    /** Kind of a zap request. */
    const val KIND_ZAP_REQUEST: Int = 9734

    /** The reason codes in check order (the tests assert them). */
    val REASONS: List<String> = listOf(
        "kind", "receipt_sig", "tags", "description_json", "request_kind", "request_sig",
        "recipient_mismatch", "bolt11", "description_hash", "ok",
    )

    private const val TAG = "ZapReceipt"

    /**
     * Steps 1–4 of the audit, in this order (spec 06 §8.4): kind 9735 → receipt id+signature →
     * first `p`/`bolt11`/`description` tags non-empty → description is a JSON object → kind 9734 →
     * request id+signature → request `p` equals receipt `p` → bolt11 has an `h` field →
     * `h == sha256(description)`. Amount, `lnurl` tag and the invoice signature are not checked.
     */
    fun checkLocal(receipt: JsonObject): ZapReceiptCheck {
        if (!receipt["kind"].dartNumEquals(KIND_ZAP_RECEIPT.toLong())) return ZapReceiptCheck.fail("kind")
        if (EventGuard.verifiedEvent(receipt, TAG) == null) return ZapReceiptCheck.fail("receipt_sig")

        var p = ""
        var bolt11 = ""
        var description = ""
        for (tag in (receipt["tags"] as? JsonArray).orEmpty()) {
            if (tag !is JsonArray || tag.size < 2) continue
            val key = tag[0].dartToStringOrNull() ?: "null"
            val value = tag[1].dartToStringOrNull() ?: "null"
            when {
                key == "p" && p.isEmpty() -> p = value
                key == "bolt11" && bolt11.isEmpty() -> bolt11 = value
                key == "description" && description.isEmpty() -> description = value
            }
        }
        if (p.isEmpty() || bolt11.isEmpty() || description.isEmpty()) return ZapReceiptCheck.fail("tags")

        val zapRequest = try {
            DartJson.decode(description) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return ZapReceiptCheck.fail("description_json")
        if (!zapRequest["kind"].dartNumEquals(KIND_ZAP_REQUEST.toLong())) return ZapReceiptCheck.fail("request_kind")
        if (EventGuard.verifiedEvent(zapRequest, TAG) == null) return ZapReceiptCheck.fail("request_sig")

        var requestP = ""
        for (tag in (zapRequest["tags"] as? JsonArray).orEmpty()) {
            if (tag is JsonArray && tag.size >= 2 && tag[0].let { it is JsonPrimitive && it.isString && it.content == "p" }) {
                requestP = tag[1].dartToStringOrNull() ?: "null"
                break
            }
        }
        if (requestP.isEmpty() || requestP != p) return ZapReceiptCheck.fail("recipient_mismatch")

        val h = descriptionHashOf(bolt11) ?: return ZapReceiptCheck.fail("bolt11")
        if (h != Sha256.hexOfUtf8(description)) return ZapReceiptCheck.fail("description_hash")

        return ZapReceiptCheck(
            ok = true,
            reason = "ok",
            recipientPubkey = p,
            senderPubkey = zapRequest["pubkey"].dartToStringOrEmpty(),
            receiptPubkey = receipt["pubkey"].dartToStringOrEmpty(),
        )
    }

    /**
     * Description hash (tagged field `h`, 52 words) of a bolt11 invoice as lowercase hex, or
     * `null` (Dart `descriptionHashOf`; delegates to `:core:crypto` [Bolt11]).
     */
    fun descriptionHashOf(bolt11: String): String? = Bolt11.descriptionHashHex(bolt11)
}
