package space.einundzwanzig.meetup.data.issuance.scan

import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.data.badges.review.BadgeReviewDemo

/** What the badge camera recognised in one barcode (Dart `_QRScannerScreen._onDetect`). */
sealed interface QrDetection {
    /** The app-review demo payload. */
    data object ReviewDemo : QrDetection

    /** A JSON object with a `t` or `type` key: a badge payload (order-preserving map). */
    data class Badge(val payload: JsonObject) : QrDetection
}

/** Pure barcode filter of the badge camera. */
object BadgeQrParser {
    /**
     * Dart `_onDetect` for one raw value: the review payload first, then a JSON **object** that
     * contains `t` or `type`; anything else (non-JSON, arrays, other objects) is ignored (`null`).
     */
    fun parse(raw: String?): QrDetection? {
        if (raw == null) return null
        if (BadgeReviewDemo.isReviewDemoPayload(raw)) return QrDetection.ReviewDemo
        val decoded = try {
            DartJson.decode(raw)
        } catch (_: Exception) {
            return null
        }
        if (decoded !is JsonObject) return null
        return if ("t" in decoded || "type" in decoded) QrDetection.Badge(decoded) else null
    }

    /** The first recognised barcode of one camera frame / gallery image, in order. */
    fun firstMatch(rawValues: List<String?>): QrDetection? = rawValues.firstNotNullOfOrNull(::parse)
}

/**
 * The participant's badge scanner logic without UI (Dart `MeetupVerificationScreen._startQRScan`,
 * `_startNfcRead` and `_processFoundTagData`, spec 02 §2.1): rolling-nonce freshness, signature
 * and expiry check, normalization, already-collected check against the stored wallet, self-scan
 * prevention, signer recognition (registry → portal leader of this meetup → portal `nostr`
 * field, or the event trust chain), automatic claim binding and the pending badge.
 *
 * Nothing is stored here: [ScanOutcome.Found] goes to [BadgeSaveUseCase.confirm] once the user
 * confirms. Calls may take several seconds (block height, registry, relays, signer prompt).
 */
interface BadgeScanProcessor {

    /**
     * One camera/gallery barcode text. `null` = not a badge QR (keep scanning, gallery stays silent).
     *
     * @param unknownMeetupLabel the localized `verifyUnknownMeetup` (its slug marks unusable names).
     */
    suspend fun processQrText(raw: String, unknownMeetupLabel: String): ScanOutcome?

    /** A decoded badge QR map ([QrDetection.Badge.payload]). */
    suspend fun processQr(scanned: JsonObject, unknownMeetupLabel: String): ScanOutcome

    /**
     * The payload of the first NDEF record of a tag (behind `IssuanceFeatures.NFC_ENABLED`).
     * The reader reports "no NDEF" / "empty tag" itself.
     */
    suspend fun processNfcPayload(recordPayload: ByteArray, unknownMeetupLabel: String): ScanOutcome
}
