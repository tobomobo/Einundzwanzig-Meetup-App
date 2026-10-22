package space.einundzwanzig.meetup.data.badges.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.core.common.json.dartNumOrNull
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.crypto.Sha256
import java.math.BigInteger

/**
 * A collected meetup/event badge (Dart `MeetupBadge`, `lib/models/badge.dart`; spec 02 §3.1).
 *
 * Immutable. **Equality is object identity**, exactly like Dart (no `==` override): two badges
 * with identical content are different objects, which the wallet's duplicate cleanup and
 * numbering rely on (spec 03 §3.1, risk 3). Compare content with [BadgeIdentity.unionKey],
 * [BadgeIdentity.sameBadges] or [toJsonString].
 *
 * Defaults mirror the Dart constructor and `fromJson` (spec 03 risk 16): a missing
 * `presenceVerified` is `true` (old badges were only issued with a location), a missing
 * `delivery` is `"nfc"`.
 *
 * @property id badge id; the meetup slug for scanned badges (`"wien"`, `"evt:<dTag>"`), NOT unique.
 * @property meetupName display name (`"City, CC"`).
 * @property date collection time (`DateTime.now()`, local, microseconds); serialized with Dart
 *   `toIso8601String()`, a hash input.
 * @property iconPath `"assets/badge_icon.png"` for scans, `""` for markers/demo.
 * @property coverUrl event badge image URL.
 * @property blockHeight block height at scan time (part of the claim content).
 * @property signerNpub organizer npub.
 * @property meetupEventId `"<slug>-<yyyy-MM-dd>"` or `"evt|<kind>:<pubkey>:<dTag>"`, `""` if unknown.
 * @property delivery `"nfc"`, `"rolling_qr"`, `"organizer"`.
 * @property sig organizer Schnorr signature (128 hex) of the kind-21000 event.
 * @property sigId organizer event id; empty for compact badges.
 * @property adminPubkey organizer hex pubkey.
 * @property sigVersion 2 = Schnorr, 1 = legacy, 0 = none.
 * @property sigContent JSON of the scanned payload, for later re-verification.
 * @property claimSig collector's signature of the kind-21002 claim.
 * @property claimEventId claim event id.
 * @property claimPubkey collector hex pubkey.
 * @property claimTimestamp claim `created_at` == `claimed_at` (unix seconds).
 * @property isRetroactive claimed later.
 * @property presenceVerified location was measurable and within the radius.
 * @property isOrganizer organizer marker (never counts for trust or duplicates).
 * @property isEvent event badge (not a meetup).
 * @property lat collector position at save time (0 = unknown).
 * @property lng collector position at save time (0 = unknown).
 */
class MeetupBadge(
    val id: String,
    val meetupName: String,
    val date: DartDateTime,
    val iconPath: String,
    val coverUrl: String = "",
    val blockHeight: Long = 0,
    val signerNpub: String = "",
    val meetupEventId: String = "",
    val delivery: String = DEFAULT_DELIVERY,
    val sig: String = "",
    val sigId: String = "",
    val adminPubkey: String = "",
    val sigVersion: Long = 0,
    val sigContent: String = "",
    val claimSig: String = "",
    val claimEventId: String = "",
    val claimPubkey: String = "",
    val claimTimestamp: Long = 0,
    val isRetroactive: Boolean = false,
    val presenceVerified: Boolean = true,
    val isOrganizer: Boolean = false,
    val isEvent: Boolean = false,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
) {

    /** Has an organizer proof: `sig != "" && sigVersion > 0`. */
    val hasCryptoProof: Boolean get() = sig.isNotEmpty() && sigVersion > 0

    /** v2, 128-hex sig, 64-hex pubkey and a non-zero signature (format check only, no verification). */
    val isNostrSigned: Boolean
        get() {
            if (sigVersion != 2L) return false
            if (!HEX128.matches(sig)) return false
            if (!HEX64.matches(adminPubkey)) return false
            return BigInteger(sig, 16).signum() != 0
        }

    /** Has a claim binding: `claimSig != "" && claimPubkey != ""`. */
    val isClaimed: Boolean get() = claimSig.isNotEmpty() && claimPubkey.isNotEmpty()

    /** Organizer proof AND claim binding. */
    val isFullyBound: Boolean get() = hasCryptoProof && isClaimed

    /**
     * Proof id for `badge_proof` (v1): [sigId], else the first 64 chars of [sig], else
     * `sha256("$id-$meetupName-${date.toIso8601String()}-$blockHeight")`.
     *
     * Dart throws a `RangeError` for a non-empty [sig] shorter than 64 chars; this port uses the
     * whole [sig] then (spec 03 risk 14). Valid data hashes identically.
     */
    val proofId: String
        get() = when {
            sigId.isNotEmpty() -> sigId
            sig.isNotEmpty() -> if (sig.length >= 64) sig.substring(0, 64) else sig
            else -> Sha256.hexOfUtf8(verificationInput())
        }

    /** `sha256("$sig|$claimSig|$claimPubkey")` when claimed, else `""`. */
    val claimProofId: String
        get() = if (!isClaimed) "" else Sha256.hexOfUtf8("$sig|$claimSig|$claimPubkey")

    /** Dart `getVerificationHash()`: first 16 hex chars of `sha256("$id-$meetupName-${date.toIso8601String()}-$blockHeight")`. */
    fun verificationHash(): String = Sha256.hexOfUtf8(verificationInput()).substring(0, 16)

    private fun verificationInput(): String = "$id-$meetupName-${date.toIso8601String()}-$blockHeight"

    /** Dart `toReputationString()` (German share text of one badge). */
    fun toReputationString(): String =
        "Badge #$id\n" +
            "Meetup: $meetupName\n" +
            "Datum: ${date.day}.${date.month}.${date.year}\n" +
            "Block: $blockHeight\n" +
            "${if (isNostrSigned) "🔐 Nostr-verifiziert" else "Verifiziert"} bei Einundzwanzig" +
            (if (isClaimed) "\n🔗 Gebunden" else "")

    /** Dart `toJson()`: exact key order and types (ints as ints, `lat`/`lng` as doubles). */
    fun toJson(): JsonObject = jsonObjectOf(
        "id" to id,
        "meetupName" to meetupName,
        "date" to date.toIso8601String(),
        "iconPath" to iconPath,
        "coverUrl" to coverUrl,
        "blockHeight" to blockHeight,
        "signerNpub" to signerNpub,
        "meetupEventId" to meetupEventId,
        "delivery" to delivery,
        "sig" to sig,
        "sigId" to sigId,
        "adminPubkey" to adminPubkey,
        "sigVersion" to sigVersion,
        "sigContent" to sigContent,
        "claimSig" to claimSig,
        "claimEventId" to claimEventId,
        "claimPubkey" to claimPubkey,
        "claimTimestamp" to claimTimestamp,
        "isRetroactive" to isRetroactive,
        "presenceVerified" to presenceVerified,
        "isOrganizer" to isOrganizer,
        "isEvent" to isEvent,
        "lat" to lat,
        "lng" to lng,
    )

    /** Dart `jsonEncode(toJson())`, byte-identical. */
    fun toJsonString(): String = DartJson.encode(toJson())

    /**
     * Dart `withClaim(...)`: copies everything and sets the claim fields. [presenceVerified]
     * defaults to `true` like Dart (it does not keep the old value); pass it explicitly to keep it.
     * [isEvent] is always kept.
     */
    fun withClaim(
        claimSig: String,
        claimEventId: String,
        claimPubkey: String,
        claimTimestamp: Long,
        isRetroactive: Boolean = false,
        isOrganizer: Boolean? = null,
        lat: Double? = null,
        lng: Double? = null,
        presenceVerified: Boolean = true,
    ): MeetupBadge = MeetupBadge(
        id = id, meetupName = meetupName, date = date, iconPath = iconPath, coverUrl = coverUrl,
        blockHeight = blockHeight, signerNpub = signerNpub, meetupEventId = meetupEventId,
        delivery = delivery, sig = sig, sigId = sigId, adminPubkey = adminPubkey,
        sigVersion = sigVersion, sigContent = sigContent,
        claimSig = claimSig, claimEventId = claimEventId, claimPubkey = claimPubkey,
        claimTimestamp = claimTimestamp, isRetroactive = isRetroactive,
        isOrganizer = isOrganizer ?: this.isOrganizer, isEvent = isEvent,
        lat = lat ?: this.lat, lng = lng ?: this.lng, presenceVerified = presenceVerified,
    )

    /** Dart `copyWith(...)`: every field overridable except [isEvent], which is always kept. */
    fun copyWith(
        id: String = this.id,
        meetupName: String = this.meetupName,
        date: DartDateTime = this.date,
        iconPath: String = this.iconPath,
        coverUrl: String = this.coverUrl,
        blockHeight: Long = this.blockHeight,
        signerNpub: String = this.signerNpub,
        meetupEventId: String = this.meetupEventId,
        delivery: String = this.delivery,
        sig: String = this.sig,
        sigId: String = this.sigId,
        adminPubkey: String = this.adminPubkey,
        sigVersion: Long = this.sigVersion,
        sigContent: String = this.sigContent,
        claimSig: String = this.claimSig,
        claimEventId: String = this.claimEventId,
        claimPubkey: String = this.claimPubkey,
        claimTimestamp: Long = this.claimTimestamp,
        isRetroactive: Boolean = this.isRetroactive,
        presenceVerified: Boolean = this.presenceVerified,
        isOrganizer: Boolean = this.isOrganizer,
        lat: Double = this.lat,
        lng: Double = this.lng,
    ): MeetupBadge = MeetupBadge(
        id = id, meetupName = meetupName, date = date, iconPath = iconPath, coverUrl = coverUrl,
        blockHeight = blockHeight, signerNpub = signerNpub, meetupEventId = meetupEventId,
        delivery = delivery, sig = sig, sigId = sigId, adminPubkey = adminPubkey,
        sigVersion = sigVersion, sigContent = sigContent, claimSig = claimSig,
        claimEventId = claimEventId, claimPubkey = claimPubkey, claimTimestamp = claimTimestamp,
        isRetroactive = isRetroactive, presenceVerified = presenceVerified,
        isOrganizer = isOrganizer, isEvent = isEvent, lat = lat, lng = lng,
    )

    override fun toString(): String =
        "MeetupBadge(id=$id, meetupName=$meetupName, date=${date.toIso8601String()}, " +
            "meetupEventId=$meetupEventId, isOrganizer=$isOrganizer, claimed=$isClaimed)"

    companion object {
        /** Default of [delivery] (Dart constructor and `fromJson`). */
        const val DEFAULT_DELIVERY = "nfc"

        private val HEX128 = Regex("^[0-9a-fA-F]{128}$")
        private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

        /**
         * Dart `MeetupBadge.fromJson`: strict on types like Dart's casts. `id`, `meetupName`,
         * `date`, `iconPath` are required strings; optional fields may be absent or `null`
         * (default) but a value of the wrong type throws (`blockHeight: 1.0` is not an int).
         *
         * @throws BadgeFormatException for any shape Dart's `fromJson` would reject.
         */
        fun fromJson(json: JsonElement): MeetupBadge {
            val o = json as? JsonObject ?: throw BadgeFormatException("Badge is not a JSON object")
            return try {
                MeetupBadge(
                    id = o.requiredString("id"),
                    meetupName = o.requiredString("meetupName"),
                    date = DartDateTime.parse(o.requiredString("date")),
                    iconPath = o.requiredString("iconPath"),
                    coverUrl = o.optString("coverUrl", ""),
                    blockHeight = o.optInt("blockHeight"),
                    signerNpub = o.optString("signerNpub", ""),
                    meetupEventId = o.optString("meetupEventId", ""),
                    delivery = o.optString("delivery", DEFAULT_DELIVERY),
                    sig = o.optString("sig", ""),
                    sigId = o.optString("sigId", ""),
                    adminPubkey = o.optString("adminPubkey", ""),
                    sigVersion = o.optInt("sigVersion"),
                    sigContent = o.optString("sigContent", ""),
                    claimSig = o.optString("claimSig", ""),
                    claimEventId = o.optString("claimEventId", ""),
                    claimPubkey = o.optString("claimPubkey", ""),
                    claimTimestamp = o.optInt("claimTimestamp"),
                    isRetroactive = o.optBool("isRetroactive", false),
                    presenceVerified = o.optBool("presenceVerified", true),
                    isOrganizer = o.optBool("isOrganizer", false),
                    isEvent = o.optBool("isEvent", false),
                    lat = o.optNum("lat"),
                    lng = o.optNum("lng"),
                )
            } catch (e: BadgeFormatException) {
                throw e
            } catch (e: IllegalArgumentException) {
                // DartDateTime.parse (FormatException in Dart).
                throw BadgeFormatException("Invalid badge: ${e.message}", e)
            }
        }

        /** Parses one badge from Dart JSON text (`MeetupBadge.fromJson(jsonDecode(s))`). */
        fun fromJsonString(text: String): MeetupBadge = try {
            fromJson(DartJson.decode(text))
        } catch (e: BadgeFormatException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw BadgeFormatException("Invalid badge JSON", e)
        }

        /**
         * Dart `jsonDecode(text) as List` + `fromJson` for each element (the decrypted blob).
         *
         * @throws BadgeFormatException if the text is not a list of valid badges.
         */
        fun listFromJsonString(text: String): List<MeetupBadge> {
            val root = try {
                DartJson.decode(text)
            } catch (e: IllegalArgumentException) {
                throw BadgeFormatException("Invalid badge list JSON", e)
            }
            val array = root as? JsonArray ?: throw BadgeFormatException("Badge list is not a JSON array")
            return array.map { fromJson(it) }
        }

        /** Dart `jsonEncode(badges.map((b) => b.toJson()).toList())`. */
        fun listToJsonString(badges: List<MeetupBadge>): String =
            DartJson.encode(JsonArray(badges.map { it.toJson() }))

        private fun JsonObject.requiredString(key: String): String {
            val v = this[key]
            if (v is JsonPrimitive && v !is JsonNull && v.isString) return v.content
            throw BadgeFormatException("'$key' is not a String")
        }

        private fun JsonObject.optString(key: String, default: String): String = when (val v = this[key]) {
            null, JsonNull -> default
            is JsonPrimitive -> if (v.isString) v.content else throw BadgeFormatException("'$key' is not a String")
            else -> throw BadgeFormatException("'$key' is not a String")
        }

        private fun JsonObject.optInt(key: String): Long = when (val v = this[key]) {
            null, JsonNull -> 0L
            else -> v.dartIntOrNull() ?: throw BadgeFormatException("'$key' is not an int")
        }

        private fun JsonObject.optBool(key: String, default: Boolean): Boolean = when (val v = this[key]) {
            null, JsonNull -> default
            is JsonPrimitive -> when {
                v.isString -> throw BadgeFormatException("'$key' is not a bool")
                v.content == "true" -> true
                v.content == "false" -> false
                else -> throw BadgeFormatException("'$key' is not a bool")
            }
            else -> throw BadgeFormatException("'$key' is not a bool")
        }

        private fun JsonObject.optNum(key: String): Double = when (val v = this[key]) {
            null, JsonNull -> 0.0
            else -> v.dartNumOrNull() ?: throw BadgeFormatException("'$key' is not a num")
        }
    }
}

/** A badge JSON that Dart's `MeetupBadge.fromJson` would reject (TypeError / FormatException). */
class BadgeFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
