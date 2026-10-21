package space.einundzwanzig.meetup.data.reputation.qr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.dart.toStringAsFixed
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.dartNumOrNull
import space.einundzwanzig.meetup.core.common.json.dartStringOrNull
import space.einundzwanzig.meetup.core.common.json.toJsonElement
import space.einundzwanzig.meetup.core.crypto.DartBase64
import space.einundzwanzig.meetup.core.crypto.DartUtf8
import space.einundzwanzig.meetup.data.badges.model.BadgeProofs
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.security.BadgeSecurity
import space.einundzwanzig.meetup.data.badges.security.QrSignResult
import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProof
import space.einundzwanzig.meetup.data.reputation.internal.lenientInt
import space.einundzwanzig.meetup.data.reputation.internal.lenientLong
import space.einundzwanzig.meetup.data.trust.reputation.RelayReputationMath
import space.einundzwanzig.meetup.data.trust.score.TrustScore

/**
 * The reputation QR (spec 04 §3.9, §7.5–§7.7): payload v6 builder, `21v3:` / `21:` string
 * encoding and the scanner's parser for `21v3:`, `21v2:` and `21:` codes. Pure; signing goes
 * through [BadgeSecurity.signQrV3] (see `ReputationQrGenerator`).
 *
 * The payload JSON is serialized **once** ([payloadJson]) and that exact string is signed and
 * base64-encoded (R-14).
 */
object ReputationQrCodec {

    /** Payload version written into `v`. */
    const val PAYLOAD_VERSION: Int = 6

    /** Prefix of a signed QR. */
    const val PREFIX_V3: String = BadgeSecurity.QR_V3_PREFIX

    /** Legacy prefixes (always rejected). */
    const val PREFIX_V2: String = "21v2:"
    const val PREFIX_V1: String = "21:"

    /** Codes the reputation scanner reacts to; everything else is ignored silently (Dart). */
    fun isReputationCode(code: String): Boolean =
        code.startsWith(PREFIX_V1) || code.startsWith(PREFIX_V2) || code.startsWith(PREFIX_V3)

    /**
     * Dart `_generateQRData` payload (key order as built, ints as JSON ints, `sc` a double).
     *
     * @param badges ALL wallet badges (`myBadges`): `bc`/`mc`/`si`/`ml`/`tc` count only
     *   `isNostrSigned && !isOrganizer` (not de-duplicated), `vc`/`bb`/`bp` use all badges.
     * @param score `TrustScoreCalculator.calculate(badges)` over the same list.
     * @param proofs `PlatformProofRepository.getSavedProofs()` (`pp`, omitted when empty).
     * @param nowMillis `t`.
     */
    fun buildPayload(
        user: UserProfile,
        badges: List<MeetupBadge>,
        score: TrustScore,
        proofs: List<PlatformProof>,
        nowMillis: Long,
    ): JsonObject {
        val counted = badges.filter { it.isNostrSigned && !it.isOrganizer }
        val uniqueMeetups = counted.mapTo(LinkedHashSet()) { it.meetupName }
        val uniqueSigners = counted.map { it.signerNpub }.filterTo(HashSet()) { it.isNotEmpty() }

        val proofV2 = BadgeProofs.generateBadgeProofV2(badges)
        val badgeProof = proofV2.ifEmpty { BadgeProofs.generateBadgeProof(badges) }
        val verifiedCount = BadgeProofs.countVerifiedBadges(badges)
        val boundCount = BadgeProofs.countBoundBadges(badges)

        val identity = LinkedHashMap<String, Any?>()
        identity["n"] = user.nickname.ifEmpty { "Anon" }
        if (user.nostrNpub.isNotEmpty()) identity["np"] = user.nostrNpub
        if (user.telegramHandle.isNotEmpty()) identity["tg"] = user.telegramHandle
        if (user.twitterHandle.isNotEmpty()) identity["tw"] = user.twitterHandle

        val reputation = LinkedHashMap<String, Any?>()
        reputation["sc"] = roundedScore(score.totalScore)
        reputation["lv"] = score.level
        reputation["bc"] = counted.size
        reputation["vc"] = verifiedCount
        reputation["mc"] = uniqueMeetups.size
        reputation["si"] = uniqueSigners.size
        reputation["ad"] = score.accountAgeDays
        if (uniqueMeetups.isNotEmpty()) {
            reputation["ml"] = uniqueMeetups.take(RelayReputationMath.MAX_MEETUP_LIST_HASHES)
                .map(RelayReputationMath::meetupListHash)
        }
        reputation["bb"] = boundCount

        val proof = linkedMapOf<String, Any?>(
            "bp" to badgeProof,
            "pv" to if (proofV2.isNotEmpty()) 2 else 1,
            "vc" to verifiedCount,
            "tc" to counted.size,
            "bb" to boundCount,
        )

        val platformProofs = LinkedHashMap<String, Any?>()
        for (p in proofs) platformProofs[p.platform] = p.toQrJson()

        val payload = LinkedHashMap<String, Any?>()
        payload["v"] = PAYLOAD_VERSION
        payload["id"] = identity
        payload["rp"] = reputation
        payload["pf"] = proof
        if (platformProofs.isNotEmpty()) payload["pp"] = platformProofs
        payload["t"] = nowMillis
        return payload.toJsonElement() as JsonObject
    }

    /**
     * Dart `double.parse(totalScore.toStringAsFixed(1))`. A non-finite score (only reachable with
     * badges dated centuries ahead) becomes `0.0`: Dart's `jsonEncode` would throw there.
     */
    fun roundedScore(totalScore: Double): Double =
        if (totalScore.isFinite()) totalScore.toStringAsFixed(1).toDouble() else 0.0

    /** Dart `jsonEncode(qrPayload)`: the exact string that is signed and embedded. */
    fun payloadJson(payload: JsonObject): String = DartJson.encode(payload)

    /** Standard base64 with padding of the UTF-8 bytes (Dart `base64Encode(utf8.encode(json))`). */
    fun base64(json: String): String = DartBase64.encode(DartUtf8.encode(json))

    /**
     * The QR string: `21v3:<b64>.<sig>.<id>.<created_at>.<pubkey>` when [sign] is a Nostr
     * signature, else the unsigned (always rejected) `21:<b64>.<sig>` (Flutter's fallback, R-8).
     */
    fun encode(json: String, sign: QrSignResult): String =
        if (sign.isNostr) encodeSigned(json, sign) else encodeUnsigned(json, sign.signature)

    /** `21v3:<b64>.<sig>.<id>.<created_at>.<pubkey>`. */
    fun encodeSigned(json: String, sign: QrSignResult): String =
        "$PREFIX_V3${base64(json)}.${sign.signature}.${sign.eventId}.${sign.createdAt}.${sign.pubkeyHex}"

    /** `21:<b64>.<signature>`; Flutter's signature is `""`, so the string ends with `.`. */
    fun encodeUnsigned(json: String, signature: String = ""): String = "$PREFIX_V1${base64(json)}.$signature"

    /**
     * The scanner (Dart `SecureQRScanner._verifyAndShow`, spec 04 §7.7), in the same order:
     * `21v3:` with ≥ 5 parts → decode + Schnorr check (kind 21001, tags `[]`); `21v2:` with ≥ 3
     * parts and `21:` with ≥ 2 parts → decode, then always [QrParseResult.SignatureInvalid]
     * (legacy, Audit C1); otherwise [QrParseResult.UnknownFormat]. Decoding errors and a payload
     * that is not a JSON object give [QrParseResult.ReadError]. Never throws.
     *
     * Scalars are read leniently (an integral double for an int field is accepted, other wrong
     * types read as the default; Dart showed `LESEFEHLER`). `id`/`rp`/`pf`/`pp` that are present
     * but not objects are a read error, like Dart's cast failure.
     */
    fun parse(code: String): QrParseResult = try {
        parseOrThrow(code)
    } catch (e: Exception) {
        QrParseResult.ReadError(e)
    }

    private fun parseOrThrow(fullCode: String): QrParseResult {
        val isV3 = fullCode.startsWith(PREFIX_V3)
        val isV2 = fullCode.startsWith(PREFIX_V2)
        val clean = when {
            isV3 || isV2 -> fullCode.substring(5)
            fullCode.length >= 3 -> fullCode.substring(3)
            else -> throw IllegalArgumentException("RangeError: code too short")
        }
        val parts = clean.split('.')

        if (isV3 && parts.size >= 5) {
            val v3 = BadgeSecurity.parseQrV3(fullCode)
                ?: throw IllegalStateException("21v3 parts")
            val result = BadgeSecurity.verifyQrV3(v3)
            if (!result.isValid) return QrParseResult.SignatureInvalid(3, result.message)
            val data = DartJson.decode(v3.json) as? JsonObject
                ?: throw IllegalArgumentException("QR payload is not a JSON object")
            val parsed = readV3(data)
            // S-8: any non-empty `id.np` counts as a claim (it sets hasIdentity); a non-string
            // claim (number, array, object) can never be the signer, so it is a mismatch too.
            val mismatch = nonEmptyJson(parsed.identity["np"]) && (
                parsed.claimedNpub == null ||
                    DartString.lower(parsed.claimedNpub) != DartString.lower(result.signerNpub)
                )
            return QrParseResult.Verified(parsed, result.signerNpub, result.message, mismatch)
        }
        if (isV2 && parts.size >= 3) {
            val json = decodePayload(parts[0])
            val result = BadgeSecurity.verifyQrLegacy(json, parts[1], parts[2])
            return QrParseResult.SignatureInvalid(2, result.message)
        }
        if (!isV3 && !isV2 && parts.size >= 2) {
            val json = decodePayload(parts[0])
            val result = BadgeSecurity.verifyQrLegacy(json, parts[1])
            return QrParseResult.SignatureInvalid(1, result.message)
        }
        return QrParseResult.UnknownFormat
    }

    /** Dart `hasRealIdentity` field test: present, not null and not an empty primitive. */
    private fun nonEmptyJson(e: JsonElement?): Boolean = when (e) {
        null, JsonNull -> false
        is JsonPrimitive -> e.content.isNotEmpty()
        else -> true
    }

    /** Dart `utf8.decode(base64.decode(s))`, both strict. */
    private fun decodePayload(b64: String): String {
        val bytes = DartBase64.decode(b64)
        return try {
            DartUtf8.decodeStrict(bytes)
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("Invalid UTF-8 in QR payload", e)
        }
    }

    /** Dart `_showV3Result` field mapping. */
    internal fun readV3(data: JsonObject): ReputationQrData {
        val id = data.objectOrEmpty("id")
        val rp = data.objectOrEmpty("rp")
        val pf = data.objectOrEmpty("pf")
        val pp = data.objectOrEmpty("pp")

        return ReputationQrData(
            identity = id,
            nickname = id["n"].dartStringOrNull(),
            claimedNpub = id["np"].dartStringOrNull()?.takeIf { it.isNotEmpty() },
            telegram = id["tg"].dartStringOrNull()?.takeIf { it.isNotEmpty() },
            twitter = id["tw"].dartStringOrNull()?.takeIf { it.isNotEmpty() },
            hasIdentity = nonEmptyJson(id["np"]) || nonEmptyJson(id["tg"]) || nonEmptyJson(id["tw"]),
            trustLevel = rp["lv"].dartStringOrNull() ?: "",
            trustScore = rp["sc"].dartNumOrNull() ?: 0.0,
            badgeCount = rp["bc"].lenientInt() ?: 0,
            verifiedBadgeCount = rp["vc"].lenientInt() ?: pf["vc"].lenientInt() ?: 0,
            boundBadgeCount = rp["bb"].lenientInt() ?: pf["bb"].lenientInt() ?: 0,
            meetupCount = rp["mc"].lenientInt() ?: 0,
            signerCount = rp["si"].lenientInt() ?: 0,
            accountAgeDays = rp["ad"].lenientInt() ?: 0,
            meetupList = (rp["ml"] as? JsonArray)?.mapNotNull { it.dartStringOrNull() } ?: emptyList(),
            badgeProof = pf["bp"].dartStringOrNull() ?: "",
            proofTotalCount = pf["tc"].lenientInt() ?: 0,
            proofVerifiedCount = pf["vc"].lenientInt() ?: 0,
            platformProofs = LinkedHashMap(pp),
            payloadVersion = data["v"].lenientInt(),
            timestampMillis = data["t"].lenientLong(),
        )
    }

    /** `data[key] as Map? ?? {}`: absent/null → empty, another type → error (Dart cast). */
    private fun JsonObject.objectOrEmpty(key: String): JsonObject = when (val v = this[key]) {
        null, JsonNull -> JsonObject(emptyMap())
        is JsonObject -> v
        else -> throw IllegalArgumentException("type mismatch: '$key' is not a Map")
    }
}
