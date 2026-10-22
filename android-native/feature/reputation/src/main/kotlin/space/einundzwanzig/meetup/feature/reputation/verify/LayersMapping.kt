package space.einundzwanzig.meetup.feature.reputation.verify

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.einundzwanzig.meetup.data.proofs.nip05.Nip05DomainType
import space.einundzwanzig.meetup.data.proofs.nip05.Nip05Result
import space.einundzwanzig.meetup.data.proofs.zap.ZapStats
import space.einundzwanzig.meetup.data.reputation.event.ReputationEvent
import space.einundzwanzig.meetup.data.socialgraph.social.SocialAnalysis
import space.einundzwanzig.meetup.data.trust.layers.LayersInput
import space.einundzwanzig.meetup.data.trust.layers.Nip05DomainKind
import space.einundzwanzig.meetup.data.trust.layers.Nip05LayerInput
import space.einundzwanzig.meetup.data.trust.layers.SocialLayerInput
import space.einundzwanzig.meetup.data.trust.layers.ZapLayerInput

/**
 * Maps the verify screen's data to the `ReputationLayersWidget` parameters (spec 04 §2.4 step 4):
 * counts from the fetched kind-30078 event, live zaps/social/NIP-05.
 */
internal object LayersMapping {

    fun input(
        reputation: ReputationEvent?,
        zaps: ZapStats?,
        social: SocialAnalysis?,
        nip05: Nip05Result?,
    ): LayersInput = LayersInput(
        badgeCount = reputation?.totalBadges?.toLong(),
        boundBadges = reputation?.boundBadges?.toLong(),
        meetupCount = reputation?.meetupCount?.toLong(),
        signerCount = reputation?.signerCount?.toLong(),
        since = reputation?.since,
        zaps = zaps?.let(::zapInput),
        humanityVerified = reputation?.humanityVerified ?: false,
        social = social?.let(::socialInput),
        nip05 = nip05?.let(::nip05Input),
        platformProofCount = reputation?.platformProofs?.size?.toLong(),
        platformProofs = reputation?.platformProofs?.mapValues { (_, v) ->
            ((v as? JsonObject)?.get("username") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
        },
        accountAgeDays = reputation?.accountAgeDays?.toLong(),
    )

    fun zapInput(z: ZapStats) = ZapLayerInput(
        sentCount = z.sentCount,
        receivedCount = z.receivedCount,
        uniqueRecipientCount = z.uniqueRecipientCount,
        uniqueSenderCount = z.uniqueSenderCount,
        activeMonths = z.activeMonths,
        hasLightningProof = z.hasLightningProof,
    )

    fun socialInput(s: SocialAnalysis) = SocialLayerInput(
        iFollow = s.iFollow,
        followsMe = s.followsMe,
        isMutual = s.isMutual,
        commonContactCount = s.commonContactCount,
        orgFollowerCount = s.orgFollowerCount,
    )

    fun nip05Input(r: Nip05Result) = Nip05LayerInput(
        valid = r.valid,
        nip05 = r.nip05,
        domainKind = when (r.domainType) {
            Nip05DomainType.COMMUNITY -> Nip05DomainKind.COMMUNITY
            Nip05DomainType.CUSTOM -> Nip05DomainKind.CUSTOM
            Nip05DomainType.PUBLIC_PROVIDER -> Nip05DomainKind.PUBLIC_PROVIDER
            Nip05DomainType.UNKNOWN -> Nip05DomainKind.UNKNOWN
        },
    )
}
