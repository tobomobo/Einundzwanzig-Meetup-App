package space.einundzwanzig.meetup.data.trust.layers

/**
 * Zap figures the Lightning layer needs (the fields of Dart `ZapStats`, spec 04 §3.11). The
 * zap domain maps its own type to this one; [lightningScore] is the Dart getter verbatim.
 */
data class ZapLayerInput(
    val sentCount: Int,
    val receivedCount: Int,
    val uniqueRecipientCount: Int,
    val uniqueSenderCount: Int,
    val activeMonths: Int,
    val hasLightningProof: Boolean,
) {
    /** `sent + received`. */
    val totalCount: Int get() = sentCount + receivedCount

    /**
     * Dart `ZapStats.activityLabel` (German, not localized in Flutter): sublabel of the
     * `rlMonthsActive` row.
     */
    val activityLabel: String
        get() = when {
            totalCount == 0 -> "Keine Zap-Aktivität"
            totalCount < 5 -> "Wenig Aktivität"
            totalCount < 20 -> "Regelmäßig aktiv"
            else -> "Sehr aktiv"
        }

    /** Dart `ZapStats.lightningScore`, 0..2.5. */
    val lightningScore: Double
        get() {
            var score = 0.0
            if (hasLightningProof) score += 0.5
            if (sentCount > 0) score += (sentCount.toDouble() / (sentCount + 10)) * 0.75
            if (receivedCount > 0) score += (receivedCount.toDouble() / (receivedCount + 10)) * 0.75
            if (uniqueRecipientCount > 2) score += 0.25
            if (activeMonths >= 3) score += 0.25
            return score.coerceIn(0.0, 2.5)
        }
}

/**
 * Social-graph figures the Social layer needs (fields of Dart `SocialAnalysis`). [socialScore]
 * is the Dart getter verbatim.
 */
data class SocialLayerInput(
    val iFollow: Boolean,
    val followsMe: Boolean,
    val isMutual: Boolean,
    val commonContactCount: Int,
    val orgFollowerCount: Int,
) {
    /** Dart `SocialAnalysis.socialScore`, 0..3. */
    val socialScore: Double
        get() {
            var score = 0.0
            if (isMutual) {
                score += 1.0
            } else if (iFollow || followsMe) {
                score += 0.3
            }
            if (commonContactCount > 0) {
                score += (commonContactCount.toDouble() / (commonContactCount + 5)) * 1.0
            }
            score += (orgFollowerCount * 0.5).coerceIn(0.0, 1.0)
            return score.coerceIn(0.0, 3.0)
        }
}

/** Dart `Nip05DomainType`. */
enum class Nip05DomainKind { COMMUNITY, CUSTOM, PUBLIC_PROVIDER, UNKNOWN }

/** NIP-05 result the Identity layer needs (Dart `Nip05Result`). */
data class Nip05LayerInput(
    val valid: Boolean,
    val nip05: String,
    val domainKind: Nip05DomainKind,
) {
    /** Dart `Nip05Service.score`: invalid 0, community 1.0, custom 0.7, public provider 0.3, unknown 0.2. */
    val score: Double
        get() = if (!valid) {
            0.0
        } else {
            when (domainKind) {
                Nip05DomainKind.COMMUNITY -> 1.0
                Nip05DomainKind.CUSTOM -> 0.7
                Nip05DomainKind.PUBLIC_PROVIDER -> 0.3
                Nip05DomainKind.UNKNOWN -> 0.2
            }
        }

    /** Dart `Nip05Result.domainLabel` (German, not localized in Flutter): sublabel of the NIP-05 row. */
    val domainLabel: String
        get() = when (domainKind) {
            Nip05DomainKind.COMMUNITY -> "Community-verifiziert"
            Nip05DomainKind.CUSTOM -> "Eigene Domain"
            Nip05DomainKind.PUBLIC_PROVIDER -> "Public Provider"
            Nip05DomainKind.UNKNOWN -> ""
        }
}

/**
 * Inputs of the four-layer evaluation (the constructor parameters of Flutter's
 * `ReputationLayersWidget`, spec 04 §8.9). `meetupScore`/`totalScore` are unused there and
 * therefore omitted.
 *
 * @property platformProofs platform id → username (may be empty) in display order, from the
 *   event's `platform_proofs` map.
 */
data class LayersInput(
    val badgeCount: Int? = null,
    val boundBadges: Int? = null,
    val meetupCount: Int? = null,
    val signerCount: Int? = null,
    val since: String? = null,
    val zaps: ZapLayerInput? = null,
    val humanityVerified: Boolean = false,
    val social: SocialLayerInput? = null,
    val nip05: Nip05LayerInput? = null,
    val platformProofCount: Int? = null,
    val platformProofs: Map<String, String>? = null,
    val accountAgeDays: Int? = null,
)
