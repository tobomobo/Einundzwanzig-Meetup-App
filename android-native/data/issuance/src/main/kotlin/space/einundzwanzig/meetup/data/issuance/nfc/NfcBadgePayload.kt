package space.einundzwanzig.meetup.data.issuance.nfc

import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.DartUtf8
import space.einundzwanzig.meetup.data.badges.text.DartString

/**
 * Compile-time NFC switch (Dart `kNfcEnabled` in `lib/features.dart`, spec 02 §0). NFC read and
 * write are disabled since August 2026; the code is kept. Must stay equal to
 * `:data:settings` `FeatureFlags.NFC_ENABLED` (this module does not depend on settings, which
 * pulls in Compose). While `false`: no NFC permission, no NDEF intent filter (Audit N4), no NFC
 * buttons.
 */
object IssuanceFeatures {
    /** Dart `kNfcEnabled`. */
    const val NFC_ENABLED: Boolean = false
}

/** Why an NDEF text record did not yield a badge map (scanner, spec 02 §2.1 NFC flow). */
sealed interface NfcPayloadError {
    /** No NDEF on the tag (`verifyErrNoNdef`); detected by the reader in the feature. */
    data object NoNdef : NfcPayloadError

    /** No message or no records (`verifyErrTagEmpty`); detected by the reader in the feature. */
    data object TagEmpty : NfcPayloadError

    /** First record has an empty payload (`verifyErrPayloadEmpty`). */
    data object PayloadEmpty : NfcPayloadError

    /** Payload ends inside the language code (`verifyErrInvalidFormat`). */
    data object InvalidFormat : NfcPayloadError

    /** Text is not a JSON object (`verifyErrInvalidTag(detail)`). */
    data class InvalidTag(val detail: String) : NfcPayloadError

    /** Reading failed, e.g. the text is not valid UTF-8 (`verifyErrReadError(detail)`). */
    data class ReadError(val detail: String) : NfcPayloadError
}

/** Typed NFC writer failure (Dart `NFCWriterScreen` error mapping, spec 02 §2.4). */
enum class NfcWriteError {
    /** Message contains `IOException` (`writerTagRemovedEarly`). */
    TAG_REMOVED_EARLY,

    /** Message contains `TagLost` (`writerTagLost`). */
    TAG_LOST,

    /** Anything else: show the raw text as `"❌ $error"`. */
    OTHER,
}

/**
 * NDEF wire format of the NFC badge tag (spec 02 §2.4, §3.5): one NFC Forum **Text** record
 * (TNF well-known, type `"T"`, empty id) whose payload is `[0x02, 'e', 'n'] + UTF-8(json)` and
 * the JSON is the session's signed base payload (`v,t,m,b,x,c,p,s`) in Dart `jsonEncode` form.
 * `NdefRecord.createTextRecord("en", json)` produces the same bytes. Pure logic; the reader
 * session (`NfcAdapter.enableReaderMode`) lives in the feature behind [IssuanceFeatures.NFC_ENABLED].
 */
object NfcBadgePayload {
    /** NDEF record type of a Text record (`"T"`). */
    const val RECORD_TYPE_TEXT: Byte = 0x54

    /** Language code written by the app. */
    const val LANGUAGE = "en"

    /** Validity shown in the writer (`writerValidHours(4)`). */
    const val VALID_HOURS = 4

    /** The tag JSON: the base payload in Dart `jsonEncode` form (key order kept). */
    fun tagJson(basePayload: JsonObject): String = DartJson.encode(basePayload)

    /** Text record payload: status byte `0x02` (UTF-8, language length 2) + `en` + UTF-8 [json]. */
    fun encodeTextPayload(json: String): ByteArray =
        byteArrayOf(0x02, 'e'.code.toByte(), 'n'.code.toByte()) + DartUtf8.encode(json)

    /**
     * Dart `actualSize = jsonData.length + 7` (3 prefix bytes + ~4 NDEF header; `length` counts
     * UTF-16 code units, as in Dart). Compared against `Ndef.maxSize` before writing.
     */
    fun actualSize(json: String): Int = json.length + 7

    /** `true` if [json] does not fit a tag with [maxSize] bytes (`writerTagTooSmall`). */
    fun isTooLarge(json: String, maxSize: Int): Boolean = actualSize(json) > maxSize

    /**
     * The writer's size preview for the home meetup (display only): the length of a placeholder
     * payload with `m = "<city lower, spaces→dashes>-<country lower>"` plus 10.
     */
    fun estimatedSize(city: String, country: String): Int {
        val compactId = "${DartString.lower(city).replace(' ', '-')}-${DartString.lower(country)}"
        val estimated = "{\"v\":2,\"t\":\"B\",\"m\":\"$compactId\",\"b\":000000,\"x\":0000000000," +
            "\"c\":0000000000,\"p\":\"${"0".repeat(64)}\",\"s\":\"${"0".repeat(128)}\"}"
        return estimated.length + 10
    }

    /**
     * Decodes the first record's Text payload like the Dart scanner: `langLen = payload[0] & 0x3F`,
     * text = strict UTF-8 of the rest (the UTF-16 flag is ignored, as in Dart).
     *
     * @return the text, or [NfcPayloadError.PayloadEmpty] / [NfcPayloadError.InvalidFormat] /
     *   [NfcPayloadError.ReadError] (invalid UTF-8; Dart's `utf8.decode` threw into the read-error branch).
     */
    fun decodeTextPayload(payload: ByteArray): Result {
        if (payload.isEmpty()) return Result.Error(NfcPayloadError.PayloadEmpty)
        val textStart = 1 + (payload[0].toInt() and 0x3F)
        if (payload.size <= textStart) return Result.Error(NfcPayloadError.InvalidFormat)
        return try {
            Result.Text(DartUtf8.decodeStrict(payload.copyOfRange(textStart, payload.size)))
        } catch (e: java.nio.charset.CharacterCodingException) {
            Result.Error(NfcPayloadError.ReadError(e.message ?: e.toString()))
        }
    }

    /** Dart writer error mapping on the exception text. */
    fun classifyWriteError(message: String): NfcWriteError = when {
        message.contains("IOException") -> NfcWriteError.TAG_REMOVED_EARLY
        message.contains("TagLost") -> NfcWriteError.TAG_LOST
        else -> NfcWriteError.OTHER
    }

    /** Outcome of [decodeTextPayload]. */
    sealed interface Result {
        /** The record text. */
        data class Text(val text: String) : Result

        /** Not decodable. */
        data class Error(val error: NfcPayloadError) : Result
    }
}
