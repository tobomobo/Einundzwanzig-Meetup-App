package space.einundzwanzig.meetup.data.badges.model

import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.crypto.Sha256
import space.einundzwanzig.meetup.data.badges.util.DartSort

/**
 * Proof hashes, statistics and the reputation export of a badge collection (Dart statics of
 * `MeetupBadge`; spec 02 §3.8/§7.8, spec 03 §7.2/§8.8). All hashes are lowercase hex SHA-256
 * over UTF-8. Sorting uses [DartSort], so tie order equals Dart's for every list size.
 */
object BadgeProofs {

    /** Dart `generateBadgeProof` (v1): `""` for an empty list, else sha256 of the date-sorted `proofId`s joined by `|`. */
    fun generateBadgeProof(badges: List<MeetupBadge>): String {
        if (badges.isEmpty()) return ""
        val sorted = DartSort.sorted(badges) { a, b -> a.date.compareTo(b.date) }
        return Sha256.hexOfUtf8(sorted.joinToString("|") { it.proofId })
    }

    /** Dart `generateBadgeProofV2`: only fully bound badges, sorted by `claimTimestamp`, chain of `claimProofId`s. */
    fun generateBadgeProofV2(badges: List<MeetupBadge>): String {
        val bound = badges.filter { it.isFullyBound }.toMutableList()
        if (bound.isEmpty()) return ""
        DartSort.sort(bound) { a, b -> a.claimTimestamp.compareTo(b.claimTimestamp) }
        return Sha256.hexOfUtf8(bound.joinToString("|") { it.claimProofId })
    }

    /** Dart `verifyBadgeProof`: `generateBadgeProof(badges) == expectedProof`. */
    fun verifyBadgeProof(badges: List<MeetupBadge>, expectedProof: String): Boolean =
        generateBadgeProof(badges) == expectedProof

    /** Dart `getReputationStats`. */
    fun getReputationStats(badges: List<MeetupBadge>): ReputationStats {
        val bound = badges.filter { it.isFullyBound }
        val retroactive = bound.count { it.isRetroactive }
        return ReputationStats(
            total = badges.size,
            cryptoProof = badges.count { it.hasCryptoProof },
            claimed = bound.size,
            retroactive = retroactive,
            fullyTrusted = bound.size - retroactive,
        )
    }

    /** Dart `countVerifiedBadges`: badges with an organizer proof. */
    fun countVerifiedBadges(badges: List<MeetupBadge>): Int = badges.count { it.hasCryptoProof }

    /** Dart `countBoundBadges`: fully bound badges. */
    fun countBoundBadges(badges: List<MeetupBadge>): Int = badges.count { it.isFullyBound }

    /**
     * Hashed meetup list entry of the reputation QR (`ml`): first 12 hex chars of
     * `sha256("21meetup:<meetupName>")` (spec 02 §3.7).
     */
    fun meetupListHash(meetupName: String): String = Sha256.hexOfUtf8("21meetup:$meetupName").substring(0, 12)

    /**
     * Dart `exportBadgesForReputation` (JSON v4.0, read by `badge-verifier.html`): exact key
     * order, checksum = first 8 hex chars of sha256 over the compact JSON without `checksum`,
     * output pretty-printed with 2 spaces and `checksum` last.
     *
     * @param exportedAt Dart `DateTime.now()` (local, microseconds) — inject for tests.
     */
    fun exportBadgesForReputation(
        badges: List<MeetupBadge>,
        userNpub: String,
        exportedAt: DartDateTime,
        nickname: String = "",
        telegram: String = "",
        twitter: String = "",
    ): String {
        val identity = LinkedHashMap<String, Any?>()
        if (nickname.isNotEmpty()) identity["nickname"] = nickname
        if (userNpub.isNotEmpty()) identity["nostr_npub"] = userNpub
        if (telegram.isNotEmpty()) identity["telegram"] = telegram
        if (twitter.isNotEmpty()) identity["twitter"] = twitter

        val data = LinkedHashMap<String, Any?>()
        data["version"] = "4.0"
        data["identity"] = if (identity.isNotEmpty()) identity else linkedMapOf("status" to "anonymous")
        data["total_badges"] = badges.size
        data["verified_badges"] = countVerifiedBadges(badges)
        data["bound_badges"] = countBoundBadges(badges)
        data["meetups_visited"] = badges.map { it.meetupName }.toSet().size
        data["unique_signers"] = badges.map { it.signerNpub }.filter { it.isNotEmpty() }.toSet().size
        data["badge_proof"] = generateBadgeProof(badges)
        data["badge_proof_v2"] = generateBadgeProofV2(badges)
        data["stats"] = getReputationStats(badges).toMap()
        data["badges"] = badges.map { b ->
            val entry = LinkedHashMap<String, Any?>()
            entry["meetup"] = b.meetupName
            entry["date"] = b.date.toIso8601String()
            entry["block"] = b.blockHeight
            entry["signer_npub"] = b.signerNpub
            entry["sig_version"] = b.sigVersion
            entry["is_bound"] = b.isFullyBound
            entry["is_retroactive"] = b.isRetroactive
            entry["presence_verified"] = b.presenceVerified
            if (b.hasCryptoProof) {
                entry["sig"] = b.sig
                entry["sig_id"] = b.sigId
                entry["admin_pubkey"] = b.adminPubkey
            }
            if (b.isClaimed) {
                entry["claim_sig"] = b.claimSig
                entry["claim_event_id"] = b.claimEventId
                entry["claim_pubkey"] = b.claimPubkey
            }
            entry["hash"] = b.verificationHash()
            entry
        }
        data["exported_at"] = exportedAt.toIso8601String()

        val checksum = Sha256.hexOfUtf8(DartJson.encode(data)).substring(0, 8)
        data["checksum"] = checksum
        return DartJson.encodePretty(data, "  ")
    }
}
