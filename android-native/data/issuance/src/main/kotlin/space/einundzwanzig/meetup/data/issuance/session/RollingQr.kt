package space.einundzwanzig.meetup.data.issuance.session

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.core.crypto.DartUtf8
import space.einundzwanzig.meetup.core.crypto.HmacSha256
import space.einundzwanzig.meetup.core.crypto.toHex
import kotlin.math.abs

/**
 * Outcome of [RollingQr.validateNonce] (Dart `NonceValidation`).
 *
 * @property message the hard-coded German Flutter text (shown inside `verifyErrQrExpired`).
 * @property ageSeconds `|currentStep - ts| * 10`; 0 when not a rolling QR.
 */
data class NonceValidation(
    val isValid: Boolean,
    val status: NonceStatus,
    val ageSeconds: Long,
    val message: String,
)

/** Typed reason of a [NonceValidation]. */
enum class NonceStatus {
    /** No `n`/`ts` (NFC tag or static payload): only the signed expiry `x` applies. Valid. */
    NOT_ROLLING,

    /** Current time step. Valid. */
    FRESH,

    /** One step off (≤ 20 s). Valid. */
    WITHIN_TOLERANCE,

    /** More than one step off: screenshot or stale display. Invalid. */
    EXPIRED,

    /**
     * `n`/`qr_nonce` is not a string or `ts`/`qr_time_step` is not an integer. Dart's casts threw
     * here (the scan silently did nothing); native rejects it as invalid.
     */
    MALFORMED,
}

/**
 * Pure rolling-QR logic (Dart `RollingQRService` statics, spec 02 §7.5, §8.5): the HMAC nonce of
 * the current 10-second step, the QR string, and the scanner's freshness check.
 *
 * The scanner cannot verify the nonce (it does not know the seed); it only checks that `ts` is
 * within ±[TOLERANCE_STEPS] of its own time step. Changing [INTERVAL_SECONDS] or the tolerance
 * breaks scanners on other app versions.
 */
object RollingQr {
    /** The QR changes every 10 s. */
    const val INTERVAL_SECONDS: Int = 10

    /** Accept ±1 step (a code is at most ~20 s old). */
    const val TOLERANCE_STEPS: Int = 1

    /** Meetup session validity; must equal `BadgeSecurity.BADGE_VALIDITY_HOURS`. */
    const val SESSION_VALIDITY_HOURS: Int = 4

    /** Unsigned rolling fields appended to the base payload (never part of the signed content). */
    val ROLLING_FIELDS: List<String> = listOf("n", "ts", "d", "la", "lo")

    /** `delivery` value of the rolling QR (`d`). */
    const val DELIVERY_ROLLING_QR: String = "rolling_qr"

    /** Dart `_currentTimeStep()`: `unixSeconds ~/ 10`. */
    fun timeStep(nowSeconds: Long): Long = Math.floorDiv(nowSeconds, INTERVAL_SECONDS.toLong())

    /**
     * Dart `_generateNonce(seed, timeStep)`: HMAC-SHA256 keyed with the UTF-8 bytes of the 64-char
     * hex **string** (not hex-decoded) over the decimal time step; first 16 lowercase hex chars.
     */
    fun nonce(seed: String, timeStep: Long): String =
        HmacSha256.mac(DartUtf8.encode(seed), DartUtf8.encode(timeStep.toString())).toHex().substring(0, 16)

    /**
     * Dart `generateQRString`: [basePayloadJson] (the stored signed base payload) plus
     * `n`, `ts`, `d = "rolling_qr"`, and `la`/`lo` only if the session has coordinates, encoded with
     * Dart's `jsonEncode` (compact, decoded key order kept, appended keys last).
     *
     * @throws kotlinx.serialization.SerializationException if [basePayloadJson] is not valid JSON.
     * @throws IllegalArgumentException if it is not a JSON object.
     */
    fun buildQrString(basePayloadJson: String, session: MeetupSession, nowSeconds: Long): String {
        val decoded = DartJson.decode(basePayloadJson)
        require(decoded is JsonObject) { "Base payload is not a JSON object" }
        val payload = LinkedHashMap<String, JsonElement>(decoded.jsonObject)
        val step = timeStep(nowSeconds)
        payload["n"] = JsonPrimitive(nonce(session.seed, step))
        payload["ts"] = JsonPrimitive(step)
        payload["d"] = JsonPrimitive(DELIVERY_ROLLING_QR)
        if (session.lat != 0.0 || session.lng != 0.0) {
            payload["la"] = JsonPrimitive(session.lat)
            payload["lo"] = JsonPrimitive(session.lng)
        }
        return DartJson.encode(JsonObject(payload))
    }

    /**
     * Dart `validateNonce(payload)`: `n ?? qr_nonce` and `ts ?? qr_time_step`; without both it is
     * not a rolling QR (valid). Otherwise valid iff `|currentStep - ts| <= 1`.
     */
    fun validateNonce(payload: Map<String, JsonElement>, nowSeconds: Long): NonceValidation {
        val nonce = stringField(payload, "n") ?: stringField(payload, "qr_nonce")
        val step = intField(payload, "ts") ?: intField(payload, "qr_time_step")
        if (nonce is Field.Malformed || step is Field.Malformed) {
            return NonceValidation(false, NonceStatus.MALFORMED, 0, "Ungültiges Rolling-QR-Format.")
        }
        if (nonce == null || step == null) {
            return NonceValidation(true, NonceStatus.NOT_ROLLING, 0, "Kein Rolling-QR (NFC/Static)")
        }
        val diff = abs(timeStep(nowSeconds) - (step as Field.IntValue).value)
        val age = diff * INTERVAL_SECONDS
        return when {
            diff > TOLERANCE_STEPS -> NonceValidation(
                false,
                NonceStatus.EXPIRED,
                age,
                "QR-Code abgelaufen (${age}s alt). Bitte direkt am Bildschirm scannen.",
            )
            age == 0L -> NonceValidation(true, NonceStatus.FRESH, 0, "Frisch ✓")
            else -> NonceValidation(true, NonceStatus.WITHIN_TOLERANCE, age, "Gültig (${age}s)")
        }
    }

    /** Whether the scanned map carries a rolling nonce (`n` or `qr_nonce` key present). */
    fun hasNonce(payload: Map<String, JsonElement>): Boolean = "n" in payload || "qr_nonce" in payload

    /** Dart `secondsUntilNextChange()`: `10 - (now % 10)` (1…10). */
    fun secondsUntilNextChange(nowSeconds: Long): Int =
        INTERVAL_SECONDS - Math.floorMod(nowSeconds, INTERVAL_SECONDS.toLong()).toInt()

    /** Dart `currentProgress()`: `(now % 10) / 10`. */
    fun currentProgress(nowSeconds: Long): Double =
        Math.floorMod(nowSeconds, INTERVAL_SECONDS.toLong()).toDouble() / INTERVAL_SECONDS

    private sealed interface Field {
        data class StringValue(val value: String) : Field
        data class IntValue(val value: Long) : Field
        data object Malformed : Field
    }

    /** Dart `payload[key] as String?`: absent/null → `null`, string → value, else a cast error. */
    private fun stringField(payload: Map<String, JsonElement>, key: String): Field? {
        val v = payload[key] ?: return null
        if (v is JsonNull) return null
        return if (v is JsonPrimitive && v.isString) Field.StringValue(v.content) else Field.Malformed
    }

    /** Dart `payload[key] as int?`: a JSON double (`1.0`) is a cast error, like in Dart. */
    private fun intField(payload: Map<String, JsonElement>, key: String): Field? {
        val v = payload[key] ?: return null
        if (v is JsonNull) return null
        return v.dartIntOrNull()?.let { Field.IntValue(it) } ?: Field.Malformed
    }
}
