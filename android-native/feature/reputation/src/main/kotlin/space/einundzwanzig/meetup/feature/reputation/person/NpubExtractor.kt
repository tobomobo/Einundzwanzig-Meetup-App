package space.einundzwanzig.meetup.feature.reputation.person

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.dart.dartTrim
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.DartBase64
import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.reputation.qr.QrParseResult
import space.einundzwanzig.meetup.data.reputation.qr.ReputationQrCodec

/**
 * Dart `extractNpub(raw)` of the VerifyPerson QR scanner (spec 04 §2.7.1) plus the recommended
 * branch 1b for real reputation QRs (R-21). Case mapping goes through Dart-compatible
 * [DartString] (spec 00 item 12).
 */
internal object NpubExtractor {

    private const val NOSTR_PREFIX = "nostr:"

    fun extract(raw: String): String? {
        val code = raw.dartTrim()
        var lower = DartString.lower(code)
        if (lower.startsWith(NOSTR_PREFIX)) lower = lower.replaceFirst(NOSTR_PREFIX, "")
        if (lower.startsWith("npub1") && lower.length > 50) return lower
        if (ReputationQrCodec.isReputationCode(code)) return fromReputationQr(code)
        return fromJson(code)
    }

    /**
     * Branch 1b: `21v3:` uses the verified signer (its npub equals a genuine `id.np`, S-8); an
     * invalid signature yields nothing. Legacy `21v2:`/`21:` codes carry no usable signature, so
     * their `id.np` is read from the base64 payload.
     */
    private fun fromReputationQr(code: String): String? {
        if (code.startsWith(ReputationQrCodec.PREFIX_V3)) {
            val parsed = ReputationQrCodec.parse(code) as? QrParseResult.Verified ?: return null
            return parsed.signerNpub.takeIf { it.startsWith("npub1") }?.let(DartString::lower)
        }
        val body = if (code.startsWith(ReputationQrCodec.PREFIX_V2)) {
            code.substring(ReputationQrCodec.PREFIX_V2.length)
        } else {
            code.substring(ReputationQrCodec.PREFIX_V1.length)
        }
        val json = runCatching { DartBase64.decode(body.substringBefore('.')).toString(Charsets.UTF_8) }.getOrNull() ?: return null
        return fromJson(json)
    }

    /** Branch 2: JSON object with `id.np` starting with `npub1` (case-insensitive). */
    private fun fromJson(code: String): String? {
        val data = runCatching { DartJson.decode(code) }.getOrNull() as? JsonObject ?: return null
        val id = data["id"] as? JsonObject ?: return null
        val np = (id["np"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val lower = DartString.lower(np)
        return if (lower.startsWith("npub1")) lower else null
    }
}
