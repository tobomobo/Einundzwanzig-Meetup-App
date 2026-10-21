package space.einundzwanzig.meetup.data.badges.security

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.dart.toDartString
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.DartNumber
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.core.crypto.DartBase64
import space.einundzwanzig.meetup.core.crypto.DartUtf8
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.Hex
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.identity.SigningException
import java.time.ZoneId

/**
 * Badge signatures and payload rules (Dart `BadgeSecurity`, `lib/services/badge_security.dart`;
 * spec 02 §6–§8). Pure functions plus the two signing calls, which take a [BadgeSigner].
 *
 * Payload maps are kotlinx [JsonObject]s decoded with `DartJson.decode` (int vs double and key
 * order preserved). Every signed content goes through Dart's canonical JSON
 * (`DartJson.canonicalJsonEncode`), so ids and signatures are byte-identical to Flutter.
 *
 * Kinds: 21000 compact badge (never published), 21001 reputation QR signature, 21002 claim
 * (see `BadgeClaims`).
 */
object BadgeSecurity {

    /** Validity of a compact badge payload in hours (`RollingQRService.sessionValidityHours`). */
    const val BADGE_VALIDITY_HOURS = 4

    /** Kind of the compact badge signature. */
    const val KIND_BADGE = 21000

    /** Kind of the reputation-QR signature envelope. */
    const val KIND_REPUTATION_QR = 21001

    /** Prefix of a signed reputation QR. */
    const val QR_V3_PREFIX = "21v3:"

    /**
     * Keys that enter the compact Schnorr signature (whitelist, SecurityChangelog Fix 2). Never
     * add `la`/`lo` here: it would invalidate every existing badge.
     */
    val COMPACT_SIGNED_KEYS: Set<String> = linkedSetOf("v", "t", "m", "b", "x")

    /** Coordinates covered by the signature: none until a v3 format exists (Audit H1). */
    val SIGNED_COORDINATE_KEYS: Set<String> = emptySet()

    private val HEX = Regex("^[0-9a-fA-F]+$")

    // Dart RegExp `\s` (JavaScript whitespace; Java's `\s` is ASCII-only).
    private const val DART_REGEX_WS = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private val EVENT_SLUG_STRIP = Regex("[^a-z0-9äöüß${DART_REGEX_WS}-]")
    private val EVENT_SLUG_WS = Regex("[$DART_REGEX_WS]+")

    /** 64 hex characters, any case. */
    fun isValidPubkeyHex(pubkey: String): Boolean = pubkey.length == 64 && HEX.matches(pubkey)

    /** 128 hex characters, any case. */
    fun isValidSignatureHex(sig: String): Boolean = sig.length == 128 && HEX.matches(sig)

    /** Dart `canonicalJsonEncode`: top-level keys sorted, compact Dart JSON. */
    fun canonicalJsonEncode(data: Map<String, Any?>): String = DartJson.canonicalJsonEncode(data)

    /** Legacy v1 signing is disabled (Audit C1): always `""`. */
    @Suppress("UNUSED_PARAMETER")
    fun signLegacy(meetupId: String, timestamp: String, blockHeight: Long): String = ""

    /** Legacy v1 verification is disabled (Audit C1): always `false`. */
    @Suppress("UNUSED_PARAMETER")
    fun verifyLegacy(data: JsonObject): Boolean = false

    /**
     * Dart `signCompact`: signs `{v:2, t:"B", m, b, x}` (kind 21000, tags `[["t","badge"],["m",m]]`)
     * and returns the base payload `v,t,m,b,x,c,p,s` in Dart key order.
     *
     * @param nowSeconds current unix time; `x = nowSeconds + validityHours * 3600`.
     * @throws SigningException `Kein Nostr-Schlüssel vorhanden.` if nothing can sign, or the signer's error.
     */
    suspend fun signCompact(
        signer: BadgeSigner,
        meetupId: String,
        blockHeight: Long,
        nowSeconds: Long,
        validityHours: Int = BADGE_VALIDITY_HOURS,
    ): JsonObject {
        if (!signer.canSign()) throw SigningException("Kein Nostr-Schlüssel vorhanden.")
        val expiresAt = nowSeconds + validityHours * 3600L
        val content = linkedMapOf<String, JsonElement>(
            "v" to JsonPrimitive(2),
            "t" to JsonPrimitive("B"),
            "m" to JsonPrimitive(meetupId),
            "b" to JsonPrimitive(blockHeight),
            "x" to JsonPrimitive(expiresAt),
        )
        val signed = signer.signEvent(KIND_BADGE, compactTags(meetupId), canonicalJsonEncode(content))
        content["c"] = JsonPrimitive(signed.createdAt)
        content["p"] = JsonPrimitive(signed.pubkey)
        content["s"] = JsonPrimitive(signed.sig)
        return JsonObject(content)
    }

    /**
     * Dart `verifyCompact`: whitelisted content, recomputed id, BIP-340 check, optional expiry.
     * Messages and versions are Flutter's, including `Verifikations-Fehler: Invalid event`
     * (version 0) for a bad signature. An upper-case `p` is invalid (Dart hashes it as given).
     *
     * @param checkExpiry `false` for proofs about past badges (promotion claims).
     */
    fun verifyCompact(data: JsonObject, nowSeconds: Long, checkExpiry: Boolean = true): VerifyResult = try {
        verifyCompactOrThrow(data, nowSeconds, checkExpiry)
    } catch (e: CompactVerifyException) {
        VerifyResult(isValid = false, version = 0, adminNpub = "", adminPubkey = "", message = "Verifikations-Fehler: ${e.message}")
    }

    private class CompactVerifyException(message: String) : Exception(message)

    private fun verifyCompactOrThrow(data: JsonObject, nowSeconds: Long, checkExpiry: Boolean): VerifyResult {
        val pubkey = data.stringOr("p", "") ?: throw CompactVerifyException(typeError("p", "String"))
        val sig = data.stringOr("s", "") ?: throw CompactVerifyException(typeError("s", "String"))
        val createdAt = data.intOr("c", 0) ?: throw CompactVerifyException(typeError("c", "int"))

        if (pubkey.isEmpty() || sig.isEmpty() || createdAt == 0L) {
            return VerifyResult(false, 2, "", "", "Fehlende Signatur-Daten")
        }
        if (!isValidPubkeyHex(pubkey)) {
            return VerifyResult(false, 2, "", pubkey, "Ungültiges Pubkey-Format (erwartet: 64 Hex-Zeichen)")
        }
        if (!isValidSignatureHex(sig)) {
            return VerifyResult(false, 2, "", pubkey, "Ungültiges Signatur-Format (erwartet: 128 Hex-Zeichen)")
        }
        val expiresAt = data.intOr("x", 0) ?: throw CompactVerifyException(typeError("x", "int"))
        if (checkExpiry && expiresAt > 0 && nowSeconds > expiresAt) {
            return VerifyResult(
                false, 2, "", pubkey,
                "Tag abgelaufen! ${expiryInfoOf(expiresAt, nowSeconds).toGermanString()}. Bitte Organisator um neuen Tag.",
            )
        }

        val content = LinkedHashMap<String, Any?>()
        for (key in COMPACT_SIGNED_KEYS) if (data.containsKey(key)) content[key] = data[key]
        val contentJson = canonicalJsonEncode(content)
        val m = data["m"].let { if (it == null || it is JsonNull) "" else dartToString(it) }
        val tags = listOf(listOf("t", "badge"), listOf("m", m))

        // Dart hashes `p` as given while Event() lower-cases it: an upper-case `p` never matches.
        if (pubkey != pubkey.lowercase()) throw CompactVerifyException("Invalid event")
        val eventId = EventIds.compute(pubkey, createdAt, KIND_BADGE, tags, contentJson)
        if (!EventIds.isValid(eventId, pubkey, createdAt, KIND_BADGE, tags, contentJson, sig)) {
            throw CompactVerifyException("Invalid event")
        }
        val adminNpub = npubOrEmpty(pubkey)
        return VerifyResult(true, 2, adminNpub, pubkey, "Schnorr-Signatur gültig ✓")
    }

    /**
     * Dart `verifyNostr` (legacy full format, verify only): `admin_pubkey`, `sig`, `sig_id`
     * non-empty strings, `created_at` an int, content = canonical JSON of all keys minus
     * `sig, sig_id, admin_pubkey, _verified_by, created_at`, tags
     * `[["t", lower(type)], ["meetup", meetup_id], ["block", block_height]]`. Never throws.
     */
    fun verifyNostr(data: JsonObject): Boolean = try {
        val adminPubkey = data.stringOr("admin_pubkey", "")
        val signature = data.stringOr("sig", "")
        val eventId = data.stringOr("sig_id", "")
        when {
            adminPubkey == null || signature == null || eventId == null -> false
            adminPubkey.isEmpty() || signature.isEmpty() || eventId.isEmpty() -> false
            !isValidPubkeyHex(adminPubkey) -> false
            else -> {
                val createdAt = data["created_at"]?.dartIntOrNull()
                if (createdAt == null) {
                    false
                } else {
                    val contentData = LinkedHashMap<String, Any?>(data)
                    for (k in listOf("sig", "sig_id", "admin_pubkey", "_verified_by", "created_at")) contentData.remove(k)
                    val content = canonicalJsonEncode(contentData)
                    val meetupId = data["meetup_id"].orDefault(JsonPrimitive(""))
                    val blockHeight = data["block_height"].orDefault(JsonPrimitive(0))
                    val tagType = DartString.lower(dartToString(data["type"].orDefault(JsonPrimitive(""))))
                    val tags = listOf(
                        listOf("t", tagType),
                        listOf("meetup", dartToString(meetupId)),
                        listOf("block", dartToString(blockHeight)),
                    )
                    EventIds.isValid(eventId, adminPubkey, createdAt, KIND_BADGE, tags, content, signature)
                }
            }
        }
    } catch (e: IllegalArgumentException) {
        false
    }

    /**
     * Dart `verify`: compact iff the payload has `p` and `s` but no `sig`; else `v >= 2` →
     * [verifyNostr] (legacy full format); else the v1 rejection.
     */
    fun verify(data: JsonObject, nowSeconds: Long, checkExpiry: Boolean = true): VerifyResult {
        if (data.containsKey("p") && data.containsKey("s") && !data.containsKey("sig")) {
            return verifyCompact(data, nowSeconds, checkExpiry)
        }
        // Dart: `final int version = data['v'] ?? 1;` throws for a non-int `v`.
        val version = data.intOr("v", 1)
            ?: return VerifyResult(false, 0, "", "", "Verifikations-Fehler: ${typeError("v", "int")}")
        if (version >= 2) {
            val isValid = verifyNostr(data)
            val adminPubkey = data.stringOr("admin_pubkey", "") ?: ""
            var adminNpub = data.stringOr("admin_npub", "") ?: ""
            if (adminNpub.isEmpty() && adminPubkey.isNotEmpty()) adminNpub = npubOrEmpty(adminPubkey)
            return VerifyResult(
                isValid = isValid,
                version = 2,
                adminNpub = adminNpub,
                adminPubkey = adminPubkey,
                message = if (isValid) "Schnorr-Signatur gültig (Legacy-Format)" else "Signatur ungültig!",
            )
        }
        return VerifyResult(
            false, 1, "", "",
            "Legacy-Badges (v1) werden nicht mehr akzeptiert. Bitte Organisator um neuen v2-Tag (Schnorr-signiert).",
        )
    }

    /**
     * Dart `normalize`: compact payload → full map (keys in Dart insertion order). A map that
     * already has `meetup_id` is returned as the same instance. `timestamp`/`meetup_date` from
     * `c` are rendered as local `DateTime` in [zone] (the app uses the device zone).
     *
     * @throws IllegalArgumentException where Dart throws a `TypeError` (`m` not a String, `c` not an int).
     */
    fun normalize(data: JsonObject, zone: ZoneId = ZoneId.systemDefault()): JsonObject {
        if (data.containsKey("meetup_id")) return data
        val n = LinkedHashMap<String, JsonElement>()
        n["v"] = data["v"].orDefault(JsonPrimitive(1))

        val t = data["t"]
        n["type"] = when {
            t is JsonPrimitive && t.isString && t.content == "B" -> JsonPrimitive("BADGE")
            t is JsonPrimitive && t.isString -> t
            else -> data["type"].orDefault(JsonPrimitive("BADGE"))
        }

        val m = data.stringOr("m", "") ?: throw IllegalArgumentException(typeError("m", "String?"))
        if (m.startsWith("evt:")) {
            val parts = m.split(':')
            if (parts.size >= 5) {
                n["event_address"] = JsonPrimitive("${parts[1]}:${parts[2]}:${parts[3]}")
                val slug = parts.subList(4, parts.size).joinToString(":")
                n["meetup_id"] = JsonPrimitive("evt:${parts[3]}")
                n["meetup_name"] = JsonPrimitive(MeetupKey.displayFromSlug(slug))
                n["meetup_country"] = JsonPrimitive("")
                n["is_event"] = JsonPrimitive(true)
            } else {
                n["meetup_id"] = JsonPrimitive(m)
                n["meetup_name"] = JsonPrimitive(m)
            }
        } else {
            val parsed = MeetupKey.parse(m)
            n["meetup_id"] = JsonPrimitive(parsed.slug)
            n["meetup_slug"] = JsonPrimitive(parsed.slug)
            n["meetup_name"] = JsonPrimitive(if (parsed.slug.isEmpty()) m else MeetupKey.displayFromSlug(parsed.slug))
            n["meetup_country"] = JsonPrimitive(parsed.country)
        }

        data.present("meetup_name")?.let { n["meetup_name"] = it }
        data.present("meetup_country")?.let { n["meetup_country"] = it }

        val c = data.present("c")
        if (c != null) {
            val seconds = c.dartIntOrNull() ?: throw IllegalArgumentException(typeError("c", "int"))
            val iso = JsonPrimitive(DartDateTime.fromMillisecondsSinceEpoch(seconds * 1000).toIso8601String(zone))
            n["timestamp"] = iso
            n["meetup_date"] = iso
        } else {
            data.present("timestamp")?.let { ts ->
                n["timestamp"] = ts
                n["meetup_date"] = data.present("meetup_date") ?: ts
            }
        }

        n["block_height"] = data.present("b") ?: data.present("block_height") ?: JsonPrimitive(0)
        data.present("x")?.let { n["expires_at"] = it }

        val p = data.present("p")
        if (p != null) {
            n["admin_pubkey"] = p
            val hex = (p as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (hex != null) runCatching { Nip19.encodeNpub(hex) }.getOrNull()?.let { n["admin_npub"] = JsonPrimitive(it) }
        } else {
            data.present("admin_pubkey")?.let { ap ->
                n["admin_pubkey"] = ap
                n["admin_npub"] = data.present("admin_npub") ?: JsonPrimitive("")
            }
        }

        (data.present("s") ?: data.present("sig"))?.let { n["sig"] = it }
        data.present("sig_id")?.let { n["sig_id"] = it }

        for (k in listOf("qr_nonce", "qr_time_step", "qr_interval", "delivery")) data.present(k)?.let { n[k] = it }
        data.present("n")?.let { n["qr_nonce"] = it }
        data.present("ts")?.let { n["qr_time_step"] = it }
        data.present("d")?.let { d ->
            val isQr = d is JsonPrimitive && d.isString && d.content == "qr"
            n["delivery"] = if (isQr) JsonPrimitive("rolling_qr") else d
        }
        return JsonObject(n)
    }

    /** Dart `isExpired`: `x ?? expires_at ?? 0`; 0 → `false`; else `now > expiresAt`. */
    fun isExpired(data: JsonObject, nowSeconds: Long): Boolean {
        val expiresAt = expiresAtOf(data)
        if (expiresAt == 0L) return false
        return nowSeconds > expiresAt
    }

    /** Dart `expiryInfo` as a typed value; `toGermanString()` gives the Flutter text. */
    fun expiryInfo(data: JsonObject, nowSeconds: Long): ExpiryInfo = expiryInfoOf(expiresAtOf(data), nowSeconds)

    private fun expiryInfoOf(expiresAt: Long, nowSeconds: Long): ExpiryInfo {
        if (expiresAt == 0L) return ExpiryInfo.NoExpiry
        val diff = expiresAt - nowSeconds
        return if (diff <= 0) ExpiryInfo.Expired(-diff) else ExpiryInfo.Valid(diff)
    }

    /** `x ?? expires_at ?? 0`; @throws IllegalArgumentException for a non-int value (Dart TypeError). */
    private fun expiresAtOf(data: JsonObject): Long {
        val raw = data.present("x") ?: data.present("expires_at") ?: return 0L
        return raw.dartIntOrNull() ?: throw IllegalArgumentException(typeError("x", "int"))
    }

    /**
     * Dart `eventMeetupId`: `evt:<eventAddress>:<slug>` where the slug is
     * `lower(trim(title))`, everything outside `[a-z0-9äöüß\s-]` removed, `\s+` → `-`
     * (Dart/JavaScript whitespace set).
     */
    fun eventMeetupId(eventAddress: String, title: String): String {
        val slug = DartString.lower(DartString.trim(title))
            .replace(EVENT_SLUG_STRIP, "")
            .replace(EVENT_SLUG_WS, "-")
        return "evt:$eventAddress:$slug"
    }

    /** `normalized["event_address"]` if it is a non-empty String, else `null`. */
    fun eventAddressOf(normalized: JsonObject): String? {
        val a = normalized["event_address"]
        return if (a is JsonPrimitive && a.isString && a.content.isNotEmpty()) a.content else null
    }

    /** `"evt|$address"`: `meetupEventId` of an event badge (same form as `EventChatService.readKey`). */
    fun eventBadgeKey(address: String): String = "evt|$address"

    /**
     * Coordinates covered by the signature: always `(0.0, 0.0)` while [SIGNED_COORDINATE_KEYS] is
     * empty (unsigned `la`/`lo` are never a measured reference, Audit H1).
     */
    fun signedCoordinates(data: JsonObject): Pair<Double, Double> {
        if (SIGNED_COORDINATE_KEYS.contains("la") && SIGNED_COORDINATE_KEYS.contains("lo")) {
            val la = (data["la"] as? JsonPrimitive)?.takeIf { !it.isString }?.let { DartJson.numberOf(it) }
            val lo = (data["lo"] as? JsonPrimitive)?.takeIf { !it.isString }?.let { DartJson.numberOf(it) }
            if (la != null && lo != null) return la.toDouble() to lo.toDouble()
        }
        return 0.0 to 0.0
    }

    /**
     * Dart `signQRv3`: signs the exact [json] as kind 21001 with tags `[]`. Without a key the
     * result is empty with `isNostr == false` (Audit C1: no unsigned fallback).
     */
    suspend fun signQrV3(signer: BadgeSigner, json: String): QrSignResult {
        if (!signer.canSign()) return QrSignResult("", "", 0, "", false)
        val signed = signer.signEvent(KIND_REPUTATION_QR, emptyList(), json)
        return QrSignResult(signed.sig, signed.id, signed.createdAt, signed.pubkey, true)
    }

    /** Dart `verifyQRv3`; messages as in Flutter (`Fehler: Invalid event` for a bad signature). */
    fun verifyQrV3(json: String, signature: String, eventId: String, createdAt: Long, pubkeyHex: String): QrVerifyResult {
        if (pubkeyHex.isEmpty() || signature.isEmpty() || eventId.isEmpty()) {
            return QrVerifyResult(false, 0, "", "Fehlende Signatur-Daten")
        }
        if (!isValidPubkeyHex(pubkeyHex)) return QrVerifyResult(false, 0, "", "Ungültiges Pubkey-Format")
        if (!isValidSignatureHex(signature)) return QrVerifyResult(false, 0, "", "Ungültiges Signatur-Format")
        if (!EventIds.isValid(eventId, pubkeyHex, createdAt, KIND_REPUTATION_QR, emptyList(), json, signature)) {
            return QrVerifyResult(false, 0, "", "Fehler: Invalid event")
        }
        return QrVerifyResult(true, 3, npubOrEmpty(pubkeyHex), "Schnorr-Signatur verifiziert ✓")
    }

    /** [verifyQrV3] for parsed QR parts. */
    fun verifyQrV3(parts: ReputationQrV3Parts): QrVerifyResult =
        verifyQrV3(parts.json, parts.signature, parts.eventId, parts.createdAt, parts.pubkeyHex)

    /**
     * Splits a `21v3:` QR like the Flutter scanner: `null` if it is not `21v3:` or has fewer than
     * five `.`-separated parts; `createdAt` = `int.tryParse(parts[3]) ?? 0`.
     *
     * @throws IllegalArgumentException if the payload is not strict base64 (standard or URL-safe,
     *   padded) of valid UTF-8 (the scanner shows `qrReadError` then).
     */
    fun parseQrV3(code: String): ReputationQrV3Parts? {
        if (!code.startsWith(QR_V3_PREFIX)) return null
        val parts = code.substring(QR_V3_PREFIX.length).split('.')
        if (parts.size < 5) return null
        val bytes = DartBase64.decode(parts[0])
        val json = try {
            DartUtf8.decodeStrict(bytes)
        } catch (e: java.nio.charset.CharacterCodingException) {
            // Dart's utf8.decode throws FormatException; keep the documented IAE contract.
            throw IllegalArgumentException("Invalid UTF-8 in 21v3 payload", e)
        }
        return ReputationQrV3Parts(json, parts[1], parts[2], dartIntTryParse(parts[3]) ?: 0, parts[4])
    }

    /** Legacy QR verification is disabled (Audit C1): always invalid, version 1. */
    @Suppress("UNUSED_PARAMETER")
    fun verifyQrLegacy(json: String, signature: String, pubkeyHex: String? = null): QrVerifyResult =
        QrVerifyResult(false, 1, "", "Legacy-QR (v1) wird nicht mehr akzeptiert. Bitte Organisator um neuen v2-Tag.")

    /** Tags of a compact badge event. */
    internal fun compactTags(meetupId: String): List<List<String>> = listOf(listOf("t", "badge"), listOf("m", meetupId))

    internal fun npubOrEmpty(hex: String): String =
        if (Hex.decodeOrNull(hex) == null) "" else runCatching { Nip19.encodeNpub(hex) }.getOrDefault("")

    /**
     * Dart VM `int.tryParse(s)` without radix: surrounding whitespace ignored, optional `+`/`-`,
     * ASCII decimal digits within the int64 range, or a `0x`/`0X` hex literal of up to 64 bits
     * (wrapping like Dart, so `0xFFFFFFFFFFFFFFFF` is `-1`).
     */
    internal fun dartIntTryParse(s: String): Long? {
        var body = DartString.trim(s)
        var negative = false
        if (body.startsWith("+") || body.startsWith("-")) {
            negative = body[0] == '-'
            body = body.substring(1)
        }
        if (body.isEmpty()) return null
        if (body.length >= 2 && body[0] == '0' && (body[1] == 'x' || body[1] == 'X')) {
            val hex = body.substring(2)
            if (hex.isEmpty() || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            val significant = hex.trimStart('0')
            if (significant.length > 16) return null
            val value = if (significant.isEmpty()) 0L else significant.toULong(16).toLong()
            return if (negative) -value else value
        }
        if (!body.all { it in '0'..'9' }) return null
        return (if (negative) "-$body" else body).toLongOrNull()
    }

    private fun typeError(key: String, type: String) = "type mismatch: '$key' is not a $type"

    /** `data[key] as String? ?? default`; `null` result = wrong type (Dart TypeError). */
    private fun JsonObject.stringOr(key: String, default: String): String? = when (val v = this[key]) {
        null, JsonNull -> default
        is JsonPrimitive -> if (v.isString) v.content else null
        else -> null
    }

    /** `data[key] as int? ?? default`; `null` result = wrong type. */
    private fun JsonObject.intOr(key: String, default: Long): Long? = when (val v = this[key]) {
        null, JsonNull -> default
        else -> v.dartIntOrNull()
    }

    /** The value unless absent or JSON `null` (Dart `data[key] != null`). */
    private fun JsonObject.present(key: String): JsonElement? = this[key]?.takeIf { it !is JsonNull }

    private fun JsonElement?.orDefault(default: JsonElement): JsonElement = if (this == null || this is JsonNull) default else this

    /** Dart `value.toString()` for JSON values (strings raw, ints decimal, doubles Dart-formatted). */
    internal fun dartToString(value: JsonElement): String = when {
        value is JsonNull -> "null"
        value is JsonPrimitive && value.isString -> value.content
        value is JsonPrimitive -> when (val num = runCatching { DartJson.numberOf(value) }.getOrNull()) {
            is DartNumber.Int -> num.value.toString()
            is DartNumber.Double -> num.value.toDartString()
            null -> value.content
        }
        else -> DartJson.encode(value)
    }
}
