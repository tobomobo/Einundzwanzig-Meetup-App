package space.einundzwanzig.meetup.data.reputation.qr

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The reputation data of a verified `21v3:` QR (Dart `_showV3Result` mapping, spec 04 §7.7).
 * Field defaults are the Dart ones (`rp.vc` falls back to `pf.vc`, `rp.bb` to `pf.bb`).
 *
 * @property identity the raw `id` object (`n`, `np`, `tg`, `tw`).
 * @property hasIdentity Dart `hasRealIdentity`: `id.np`, `id.tg` or `id.tw` non-empty. Ignores
 *   the signer; see [QrParseResult.Verified.hasTrustedIdentity].
 * @property trustLevel `rp.lv`, the LOCAL level wire string (`""` if absent).
 * @property platformProofs raw `pp` map: `{platform: {"u": username, "s": sig}}`. The signatures
 *   are NOT cryptographically checked (spec 04 S-9): label them "verknüpft (unbestätigt)".
 * @property payloadVersion `v` (informational, not checked; 6 today).
 * @property timestampMillis `t`, creation time in ms (no expiry, S-10).
 */
data class ReputationQrData(
    val identity: JsonObject,
    val nickname: String?,
    val claimedNpub: String?,
    val telegram: String?,
    val twitter: String?,
    val hasIdentity: Boolean,
    val trustLevel: String,
    val trustScore: Double,
    val badgeCount: Int,
    val verifiedBadgeCount: Int,
    val boundBadgeCount: Int,
    val meetupCount: Int,
    val signerCount: Int,
    val accountAgeDays: Int,
    val meetupList: List<String>,
    val badgeProof: String,
    val proofTotalCount: Int,
    val proofVerifiedCount: Int,
    val platformProofs: Map<String, JsonElement>,
    val payloadVersion: Int?,
    val timestampMillis: Long?,
) {
    /** `pp` entries as `platform → username` (entries without a string `u` map to `""`). */
    val platformUsernames: Map<String, String>
        get() = platformProofs.mapValues { (_, v) ->
            ((v as? JsonObject)?.get("u") as? JsonPrimitive)
                ?.takeIf { it.isString }?.content ?: ""
        }
}

/**
 * Result of [ReputationQrCodec.parse], one case per Flutter result screen (spec 04 §7.7).
 */
sealed interface QrParseResult {

    /**
     * `21v3:` with a valid kind-21001 Schnorr signature (title `qrVerified`, subtitle [message]).
     *
     * @property signerNpub npub of the signing key.
     * @property identityMismatch spec 04 S-8 FIX: the QR claims an `id.np` that is not the
     *   signer (case-insensitive). Show a prominent warning (new string, e.g. `qrIdentityMismatch`).
     */
    data class Verified(
        val data: ReputationQrData,
        val signerNpub: String,
        val message: String,
        val identityMismatch: Boolean,
    ) : QrParseResult {
        /** [ReputationQrData.hasIdentity], but `false` when the claimed npub is not the signer. */
        val hasTrustedIdentity: Boolean get() = data.hasIdentity && !identityMismatch

        /**
         * The npub whose live layers (NIP-05, Lightning, social) to load: the signer on a
         * mismatch, else the claimed `id.np` (Flutter), else `null` (Flutter loads none).
         */
        val layersNpub: String?
            get() = if (identityMismatch) signerNpub else data.claimedNpub
    }

    /**
     * Signature check failed (title `qrSigInvalid`, subtitle [message]): a bad `21v3:` signature,
     * or any legacy `21v2:`/`21:` code (always rejected, Audit C1).
     *
     * @property version 3, 2 or 1 (format of the code).
     */
    data class SignatureInvalid(val version: Int, val message: String) : QrParseResult

    /** Recognized prefix but too few parts (title `qrFormatUnknown`, subtitle `qrCantRead`). */
    data object UnknownFormat : QrParseResult

    /**
     * Bad base64, bad UTF-8, bad JSON or wrong structure (title `qrReadError`, subtitle
     * `qrProcessError(cause.toString())`).
     */
    data class ReadError(val cause: Throwable) : QrParseResult
}
