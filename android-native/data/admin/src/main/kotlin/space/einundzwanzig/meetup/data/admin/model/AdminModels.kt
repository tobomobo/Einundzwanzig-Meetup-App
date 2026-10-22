package space.einundzwanzig.meetup.data.admin.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.core.common.json.dartStringOrNull

/**
 * One organizer entry of the admin registry (Dart `AdminEntry`, spec 05 §3.3).
 *
 * @property npub organizer npub (not validated on read).
 * @property meetup meetup label as published.
 * @property name display name; `Organic (N Badges)` marks a legacy organic claim (Audit K1).
 * @property addedAt opaque timestamp (`added_at`); local writes use unix seconds, relay data
 *   contains whatever publishers wrote.
 */
data class AdminEntry(
    val npub: String,
    val meetup: String,
    val name: String = "",
    val addedAt: Long = 0,
) {
    /**
     * `true` for the automatically assigned name of the old organic admin claim
     * (`^Organic \(\d+ Badges\)$`, full match). Such entries are never admins (Audit K1).
     */
    val isLegacyOrganicClaim: Boolean get() = LEGACY_ORGANIC_NAME.matches(name)

    /** Dart `toJson()`: `{"npub","meetup","name","added_at"}` in this key order. */
    fun toJsonMap(): Map<String, Any?> = linkedMapOf(
        "npub" to npub,
        "meetup" to meetup,
        "name" to name,
        "added_at" to addedAt,
    )

    /** [toJsonMap] encoded with Dart `jsonEncode` semantics. */
    fun toJson(): String = DartJson.encode(toJsonMap())

    companion object {
        /** Dart `AdminEntry.legacyOrganicName` (ASCII digits only, like Dart's `\d`). */
        val LEGACY_ORGANIC_NAME: Regex = Regex("^Organic \\([0-9]+ Badges\\)$")

        /**
         * Dart `AdminEntry.fromJson`: missing or `null` fields default to `""`/`0`; a field of
         * the wrong type throws (Dart `TypeError`), which skips the whole cache/event.
         *
         * @throws IllegalArgumentException if [json] is not an object or a field has the wrong type.
         */
        fun fromJson(json: JsonElement): AdminEntry {
            require(json is JsonObject) { "AdminEntry is not a JSON object" }
            return AdminEntry(
                npub = json.stringField("npub"),
                meetup = json.stringField("meetup"),
                name = json.stringField("name"),
                addedAt = json.intField("added_at"),
            )
        }

        /**
         * Parses a JSON array of entries (cache format, `my_personal_vouches`).
         *
         * @throws IllegalArgumentException / [kotlinx.serialization.SerializationException] on
         *   malformed input (Dart throws as well).
         */
        fun listFromJson(text: String): List<AdminEntry> {
            val decoded = DartJson.decode(text)
            require(decoded is JsonArray) { "Admin list is not a JSON array" }
            return decoded.map(::fromJson)
        }

        /** Dart `jsonEncode(list.map((e) => e.toJson()).toList())`. */
        fun listToJson(entries: List<AdminEntry>): String = DartJson.encode(entries.map { it.toJsonMap() })
    }
}

/**
 * Result of [space.einundzwanzig.meetup.data.admin.registry.AdminRegistry.checkAdmin]
 * (Dart `AdminCheckResult`).
 *
 * @property source one of the `SOURCE_*` constants. `unavailable` means "the registry could not
 *   be consulted" and must not be shown as "unknown signer" (spec 05 §9 item 10).
 */
data class AdminCheckResult(
    val isAdmin: Boolean,
    val meetup: String? = null,
    val name: String? = null,
    val source: String,
) {
    companion object {
        const val SOURCE_NO_KEY = "no_key"
        const val SOURCE_SUPER_ADMIN = "super_admin"
        const val SOURCE_LOCAL_CACHE = "local_cache"
        const val SOURCE_NOSTR_RELAY = "nostr_relay"
        const val SOURCE_UNAVAILABLE = "unavailable"
        const val SOURCE_NOT_FOUND = "not_found"
        const val SOURCE_INVALID_KEY = "invalid_key"
    }
}

/** Relay answer: hex pubkey of the **signer** (event author after the signature check) → its listed entries. */
typealias AdminsBySigner = Map<String, List<AdminEntry>>

/**
 * Result of the self admin-status verification (Dart `AdminVerification`, spec 05 §3.4).
 *
 * @property source `no_key`, `trust_score`, `seed_admin` or `not_admin`.
 * @property reason German explanation, verbatim from Flutter.
 */
data class AdminVerification(
    val isAdmin: Boolean,
    val source: String,
    val reason: String = "",
) {
    /**
     * Home publishes the own admin claim (kind 30021) after a re-verification iff this is true
     * (Dart: `v.isAdmin && (v.source == 'trust_score' || v.source == 'vouch_consensus')`).
     */
    val shouldPublishClaim: Boolean
        get() = isAdmin && (source == SOURCE_TRUST_SCORE || source == SOURCE_VOUCH_CONSENSUS)

    companion object {
        const val SOURCE_NO_KEY = "no_key"
        const val SOURCE_TRUST_SCORE = "trust_score"
        const val SOURCE_SEED_ADMIN = "seed_admin"
        const val SOURCE_NOT_ADMIN = "not_admin"

        /** Removed path (Aug 2026), still accepted by the Home claim trigger. */
        const val SOURCE_VOUCH_CONSENSUS = "vouch_consensus"

        /** Dart `AdminVerification.notAdmin`. */
        val NOT_ADMIN = AdminVerification(
            isAdmin = false,
            source = SOURCE_NOT_ADMIN,
            reason = "Weder Trust Score noch Seed-Admin Bedingungen erfüllt.",
        )

        /** Dart `no_key` result. */
        val NO_KEY = AdminVerification(isAdmin = false, source = SOURCE_NO_KEY, reason = "Kein Nostr-Key vorhanden.")
    }
}

/**
 * A kind-30021 admin claim as fetched from a relay (Dart `RawClaim`).
 *
 * @property pubkey event author (hex) — the claimer.
 * @property createdAt event `created_at` (unix seconds).
 */
data class RawClaim(
    val pubkey: String,
    val content: String,
    val createdAt: Long,
)

/**
 * A verified organic admin claim (Dart `VerifiedClaim`). Information only: it grants **no**
 * admin rights and never counts as a known admin (Audit K1).
 *
 * @property claimedAt event `created_at` of the claim.
 */
data class VerifiedClaim(
    val claimerPubkey: String,
    val meetup: String,
    val verifiedBadgeCount: Int,
    val uniqueSignerCount: Int,
    val claimedAt: Long,
) {
    /** Dart `toJson()`: `claimer_pubkey, meetup, verified_badges, unique_signers, claimed_at, organic: true`. */
    fun toJsonMap(): Map<String, Any?> = linkedMapOf(
        "claimer_pubkey" to claimerPubkey,
        "meetup" to meetup,
        "verified_badges" to verifiedBadgeCount,
        "unique_signers" to uniqueSignerCount,
        "claimed_at" to claimedAt,
        "organic" to true,
    )

    companion object {
        /**
         * Dart `VerifiedClaim.fromJson`: missing → `""`/`0`, `organic` ignored.
         *
         * @throws IllegalArgumentException on a wrong field type (Dart cast error).
         */
        fun fromJson(json: JsonElement): VerifiedClaim {
            require(json is JsonObject) { "VerifiedClaim is not a JSON object" }
            return VerifiedClaim(
                claimerPubkey = json.stringField("claimer_pubkey"),
                meetup = json.stringField("meetup"),
                verifiedBadgeCount = json.intField("verified_badges").toInt(),
                uniqueSignerCount = json.intField("unique_signers").toInt(),
                claimedAt = json.intField("claimed_at"),
            )
        }
    }
}

/** Dart `json[key] as String? ?? ''`: absent/null → `""`, wrong type throws. */
internal fun JsonObject.stringField(key: String): String {
    val v = this[key]
    if (v == null || v is JsonNull) return ""
    return v.dartStringOrNull() ?: throw IllegalArgumentException("type mismatch: '$key' is not a String")
}

/** Dart `json[key] as int? ?? 0`: absent/null → `0`, wrong type (also `1.0`) throws. */
internal fun JsonObject.intField(key: String): Long {
    val v = this[key]
    if (v == null || v is JsonNull) return 0
    return v.dartIntOrNull() ?: throw IllegalArgumentException("type mismatch: '$key' is not an int")
}
