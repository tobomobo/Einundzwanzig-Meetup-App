package space.einundzwanzig.meetup.data.admin.claims

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.dart.toDartString
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.DartNumber
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.data.admin.AdminConstants
import space.einundzwanzig.meetup.data.admin.model.RawClaim
import space.einundzwanzig.meetup.data.admin.model.VerifiedClaim
import space.einundzwanzig.meetup.data.admin.model.intField
import space.einundzwanzig.meetup.data.admin.model.stringField
import space.einundzwanzig.meetup.data.badges.claim.BadgeClaims
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.security.BadgeSecurity
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Pure parts of `PromotionClaimService` (spec 05 §3.5, §7.4, §8.5): the claim content and the
 * K1-hardened verification. No I/O, no Android.
 */
object PromotionClaims {

    /**
     * Dart `PromotionClaimService.verifyClaim(claim, knownAdminPubkeys)` — exact check order of
     * spec 05 §8.5. A proof counts only if: its sig is new, its `admin_pubkey` is a known admin,
     * the organizer signature (compact or legacy, expiry ignored) is valid **by that key** and is
     * the proof's sig, and the claimer bound it with its **own** key (kind 21002). The claim needs
     * ≥ 3 such proofs from ≥ 3 distinct `(signer, meetup_id, UTC day)` sessions, and is rejected
     * when older than 90 days (event `created_at`).
     *
     * Where Dart would throw out of `verifyClaim` (a non-list `proofs`, a non-string `meetup`),
     * this returns `null`; the caller skipped such claims anyway.
     *
     * @param knownAdminPubkeys hex keys of the super admin and the registry (never organic claims).
     * @param nowSeconds the current unix time (Dart `DateTime.now()`).
     */
    fun verifyClaim(claim: RawClaim, knownAdminPubkeys: Set<String>, nowSeconds: Long): VerifiedClaim? {
        val content = try {
            DartJson.decode(claim.content) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null

        val type = content["type"]
        if (!(type is JsonPrimitive && type.isString && type.content == "admin_claim")) return null

        val proofs: JsonArray = when (val p = content["proofs"]) {
            null, is JsonNull -> JsonArray(emptyList())
            is JsonArray -> p
            else -> return null
        }
        val meetup = try {
            content.stringField("meetup")
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (proofs.isEmpty() || meetup.isEmpty()) return null

        // Audit-2 Fund 5: max claim age.
        if (nowSeconds - claim.createdAt > AdminConstants.MAX_CLAIM_AGE_DAYS * 86_400L) return null

        var validBadgeCount = 0
        val verifiedSigners = LinkedHashSet<String>()
        val seenSigs = HashSet<String>()
        val sessions = LinkedHashSet<String>()

        for (proof in proofs) {
            try {
                val session = verifyProof(proof, claim.pubkey, knownAdminPubkeys, seenSigs, nowSeconds) ?: continue
                sessions += session.key
                validBadgeCount++
                verifiedSigners += session.adminPubkey
            } catch (_: Exception) {
                continue
            }
        }

        if (validBadgeCount < AdminConstants.MIN_VERIFIED_BADGES) return null
        if (sessions.size < AdminConstants.MIN_VERIFIED_BADGES) return null
        if (verifiedSigners.size < AdminConstants.MIN_UNIQUE_SIGNERS) return null
        // Double check: signers must still be known admins (Audit-2 Fund 5).
        val active = verifiedSigners.count { it in knownAdminPubkeys }
        if (active < AdminConstants.MIN_UNIQUE_SIGNERS) return null

        return VerifiedClaim(
            claimerPubkey = claim.pubkey,
            meetup = meetup,
            verifiedBadgeCount = validBadgeCount,
            uniqueSignerCount = active,
            claimedAt = claim.createdAt,
        )
    }

    private class ProofSession(val key: String, val adminPubkey: String)

    /** One proof; `null` = skip. Throws where Dart threw inside the per-proof `try` (also = skip). */
    private fun verifyProof(
        proof: JsonElement,
        claimerPubkey: String,
        known: Set<String>,
        seenSigs: MutableSet<String>,
        nowSeconds: Long,
    ): ProofSession? {
        val map = proof as? JsonObject ?: throw IllegalArgumentException("proof is not a map")
        val sig = map.stringField("sig")
        val adminPubkey = map.stringField("admin_pubkey")
        val sigVersion = map.intField("sig_version")
        val sigContent = map.stringField("sig_content")
        val sigId = map.stringField("sig_id")
        val block = map.intField("block")
        val claimSig = map.stringField("claim_sig")
        val claimEventId = map.stringField("claim_event_id")
        val claimTimestamp = map.intField("claim_timestamp")

        if (sig.isEmpty() || adminPubkey.isEmpty()) return null
        // a) duplicates (the seen-set is updated before the other checks)
        if (!seenSigs.add(sig)) return null
        if (adminPubkey !in known) return null

        // b) organizer signature
        if (sigVersion < 2 || sigContent.isEmpty()) return null
        val data = try {
            DartJson.decode(sigContent) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val verifyResult = try {
            BadgeSecurity.verify(data, nowSeconds, checkExpiry = false)
        } catch (_: Exception) {
            return null
        }
        if (!verifyResult.isValid) return null
        if (verifyResult.adminPubkey != adminPubkey) return null
        val signedSig = dartToString(firstPresent(data, "s", "sig") ?: JsonPrimitive(""))
        if (signedSig != sig) return null

        // c) participant binding by the claimer's own key
        if (claimSig.isEmpty() || claimEventId.isEmpty() || claimTimestamp == 0L) return null
        val binding = BadgeClaims.verifyClaim(
            claimSig = claimSig,
            claimEventId = claimEventId,
            claimPubkey = claimerPubkey,
            claimTimestamp = claimTimestamp,
            orgSig = sig,
            orgEventId = sigId,
            orgPubkey = adminPubkey,
            blockHeight = block,
        )
        if (!binding.isValid) return null

        // d) session key from SIGNED fields: "<admin>|<meetup_id>|<UTC y-m-d without padding>"
        val normalized = BadgeSecurity.normalize(data, ZoneOffset.UTC)
        val signedAtElement = firstPresent(data, "c", "created_at") ?: JsonPrimitive(0)
        val signedAt = signedAtElement.dartIntOrNull() ?: throw IllegalArgumentException("signed time is not an int")
        // Dart `DateTime.fromMillisecondsSinceEpoch(signedAt * 1000, isUtc: true)`: the 64-bit
        // product wraps like on the Dart VM and |ms| > 8.64e15 throws (= proof skipped).
        val day = DartDateTime.fromMillisecondsSinceEpoch(signedAt * 1000, isUtc = true)
        val dayKey = "${day.year}-${day.month}-${day.day}"
        val meetupId = normalized["meetup_id"]?.takeUnless { it is JsonNull }?.let(::dartToString) ?: "null"
        return ProofSession("$adminPubkey|$meetupId|$dayKey", adminPubkey)
    }

    /**
     * The proof object of a badge in the admin claim (spec 05 §3.5), keys in Flutter order.
     * `date` is Dart `toIso8601String()` in [zone] (local, or with `Z` for UTC badge dates).
     */
    fun proofOf(badge: MeetupBadge, zone: ZoneId): Map<String, Any?> = linkedMapOf(
        "meetup" to badge.meetupName,
        "date" to badge.date.toIso8601String(zone),
        "block" to badge.blockHeight,
        "sig" to badge.sig,
        "sig_id" to badge.sigId,
        "admin_pubkey" to badge.adminPubkey,
        "sig_version" to badge.sigVersion,
        "sig_content" to badge.sigContent,
        "claim_sig" to badge.claimSig,
        "claim_event_id" to badge.claimEventId,
        "claim_timestamp" to badge.claimTimestamp,
    )

    /**
     * The admin-claim content (kind 30021), keys in Flutter order:
     * `type, version, claimer_npub, meetup, verified_badges, unique_signers, proofs, claimed_at`.
     * [verifiedBadges] are the own fully bound badges (see [claimableBadges]).
     */
    fun claimContent(
        claimerNpub: String,
        meetupName: String,
        verifiedBadges: List<MeetupBadge>,
        claimedAtSeconds: Long,
        zone: ZoneId,
    ): String = DartJson.encode(
        linkedMapOf(
            "type" to "admin_claim",
            "version" to 1,
            "claimer_npub" to claimerNpub,
            "meetup" to meetupName,
            "verified_badges" to verifiedBadges.size,
            "unique_signers" to verifiedBadges.map { it.adminPubkey }.filter { it.isNotEmpty() }.toSet().size,
            "proofs" to verifiedBadges.map { proofOf(it, zone) },
            "claimed_at" to claimedAtSeconds,
        ),
    )

    /** Audit K1: only badges the claimer bound with its own key (`isFullyBound && claimPubkey == own`). */
    fun claimableBadges(badges: List<MeetupBadge>, ownPubkeyHex: String): List<MeetupBadge> =
        badges.filter { it.isFullyBound && it.claimPubkey == ownPubkeyHex }

    /** Tags of the admin claim: `[["d","einundzwanzig-admin-claim"],["meetup",meetupName]]`. */
    fun claimTags(meetupName: String): List<List<String>> = listOf(
        listOf("d", AdminConstants.CLAIM_D_TAG),
        listOf("meetup", meetupName),
    )

    /** Dart `a ?? b`: the first key whose value is present and not JSON `null`. */
    private fun firstPresent(data: JsonObject, vararg keys: String): JsonElement? =
        keys.firstNotNullOfOrNull { k -> data[k]?.takeUnless { it is JsonNull } }

    /** Dart `value.toString()` for decoded JSON scalars (maps/lists only need to be unequal to a hex sig). */
    private fun dartToString(value: JsonElement): String = when {
        value is JsonPrimitive && value.isString -> value.content
        value is JsonPrimitive -> when (val n = runCatching { DartJson.numberOf(value) }.getOrNull()) {
            is DartNumber.Int -> n.value.toString()
            is DartNumber.Double -> n.value.toDartString()
            null -> value.content
        }
        else -> DartJson.encode(value)
    }
}
