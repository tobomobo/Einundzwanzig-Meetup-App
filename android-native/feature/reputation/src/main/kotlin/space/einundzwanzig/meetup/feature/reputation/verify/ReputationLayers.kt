package space.einundzwanzig.meetup.feature.reputation.verify

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.einundzwanzig.meetup.core.ui.motion.ExpressiveSprings
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.trust.layers.IdentityMeaning
import space.einundzwanzig.meetup.data.trust.layers.LayersEvaluation
import space.einundzwanzig.meetup.data.trust.layers.LayersInput
import space.einundzwanzig.meetup.data.trust.layers.LightningMeaning
import space.einundzwanzig.meetup.data.trust.layers.MeetupMeaning
import space.einundzwanzig.meetup.data.trust.layers.SocialLayerInput
import space.einundzwanzig.meetup.data.trust.layers.SocialMeaning
import space.einundzwanzig.meetup.data.trust.layers.TrustLayersEvaluator
import space.einundzwanzig.meetup.data.trust.layers.TrustSignalKind
import space.einundzwanzig.meetup.data.trust.layers.ZapLayerInput
import space.einundzwanzig.meetup.feature.reputation.R
import space.einundzwanzig.meetup.feature.reputation.common.amberColor
import space.einundzwanzig.meetup.feature.reputation.common.cyanColor
import space.einundzwanzig.meetup.feature.reputation.common.greenColor
import space.einundzwanzig.meetup.feature.reputation.common.greyColor
import space.einundzwanzig.meetup.feature.reputation.common.orangeColor
import space.einundzwanzig.meetup.feature.reputation.common.purpleColor
import space.einundzwanzig.meetup.feature.reputation.common.toColor
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags of the layers widget. */
internal object LayersTags {
    const val SIGNAL = "layers_signal"
    const val ADVICE = "layers_advice"
    fun layer(index: Int) = "layers_card_$index"
}

/** One detail row of a layer card. */
private data class DetailRow(val label: String, val sublabel: String, val positive: Boolean)

/**
 * `ReputationLayersWidget` (spec 04 §2.5, §8.9): traffic-light card, four expandable layer cards
 * and the advice box. Scores and selection come from `:data:trust` [TrustLayersEvaluator]; the
 * detail rows are the plain conditionals of §8.9.
 */
@Composable
internal fun ReputationLayers(input: LayersInput, modifier: Modifier = Modifier) {
    val eval = TrustLayersEvaluator.evaluate(input)
    Column(modifier.fillMaxWidth()) {
        SignalCard(eval)
        Spacer(Modifier.height(16.dp))
        LayerCard(
            index = 0,
            icon = Icons.Rounded.Nfc,
            color = orangeColor,
            title = stringResource(L10nR.string.rlMeetupProofs),
            hasData = eval.hasMeetupData,
            meaning = meetupMeaning(eval.meetupMeaning, input),
            details = if (eval.hasMeetupData) meetupDetails(input, eval) else emptyList(),
        )
        Spacer(Modifier.height(10.dp))
        LayerCard(
            index = 1,
            icon = Icons.Rounded.Bolt,
            color = amberColor,
            title = stringResource(L10nR.string.rlLightningProof),
            hasData = eval.hasLightningData,
            meaning = lightningMeaning(eval.lightningMeaning),
            details = lightningDetails(input),
        )
        Spacer(Modifier.height(10.dp))
        LayerCard(
            index = 2,
            icon = Icons.Rounded.Hub,
            color = cyanColor,
            title = stringResource(L10nR.string.rlSocialTitle),
            hasData = eval.hasSocialData,
            meaning = socialMeaning(eval.socialMeaning),
            details = input.social?.let { socialDetails(it) } ?: emptyList(),
        )
        Spacer(Modifier.height(10.dp))
        LayerCard(
            index = 3,
            icon = Icons.Rounded.Fingerprint,
            color = purpleColor,
            title = stringResource(L10nR.string.rlIdentityTitle),
            hasData = eval.hasIdentityData,
            meaning = identityMeaning(eval.identityMeaning),
            details = identityDetails(input),
        )
        val advice = stringResource(signalAdviceRes(eval.signal))
        if (advice.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            val color = eval.signal.color.toColor()
            val shape = RoundedCornerShape(12.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color.copy(alpha = 0.05f), shape)
                    .border(1.dp, color.copy(alpha = 0.15f), shape)
                    .padding(14.dp)
                    .testTag(LayersTags.ADVICE),
            ) {
                Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(advice, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}

private fun signalIcon(kind: TrustSignalKind): ImageVector = when (kind) {
    TrustSignalKind.WEAK -> Icons.Rounded.WarningAmber
    TrustSignalKind.LIMITED -> Icons.Outlined.Info
    TrustSignalKind.BUILDING, TrustSignalKind.DEFAULT -> Icons.Outlined.Shield
    TrustSignalKind.CONNECTED -> Icons.Rounded.VerifiedUser
    TrustSignalKind.SOLID -> Icons.Rounded.Shield
}

private fun signalLabelRes(kind: TrustSignalKind): Int = when (kind) {
    TrustSignalKind.WEAK -> L10nR.string.rlWeakLabel
    TrustSignalKind.LIMITED -> L10nR.string.rlLimitedLabel
    TrustSignalKind.BUILDING, TrustSignalKind.DEFAULT -> L10nR.string.rlBuildingLabel
    TrustSignalKind.CONNECTED -> L10nR.string.rlConnectedLabel
    TrustSignalKind.SOLID -> L10nR.string.rlSolidLabel
}

private fun signalExplanationRes(kind: TrustSignalKind): Int = when (kind) {
    TrustSignalKind.WEAK -> L10nR.string.rlWeakExpl
    TrustSignalKind.LIMITED -> L10nR.string.rlLimitedExpl
    TrustSignalKind.BUILDING -> L10nR.string.rlBuildingExpl
    TrustSignalKind.CONNECTED -> L10nR.string.rlConnectedExpl
    TrustSignalKind.SOLID -> L10nR.string.rlSolidExpl
    TrustSignalKind.DEFAULT -> L10nR.string.rlDefaultExpl
}

private fun signalAdviceRes(kind: TrustSignalKind): Int = when (kind) {
    TrustSignalKind.WEAK -> L10nR.string.rlWeakAdvice
    TrustSignalKind.LIMITED -> L10nR.string.rlLimitedAdvice
    TrustSignalKind.BUILDING -> L10nR.string.rlBuildingAdvice
    TrustSignalKind.CONNECTED -> L10nR.string.rlConnectedAdvice
    TrustSignalKind.SOLID -> L10nR.string.rlSolidAdvice
    TrustSignalKind.DEFAULT -> L10nR.string.rlDefaultAdvice
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SignalCard(eval: LayersEvaluation) {
    val color = eval.signal.color.toColor()
    val shape = RoundedCornerShape(14.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .border(1.5.dp, color.copy(alpha = 0.4f), shape)
            .padding(16.dp)
            .testTag(LayersTags.SIGNAL)
            .semantics(mergeDescendants = true) {},
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .background(color.copy(alpha = 0.15f), MaterialShapes.Cookie9Sided.toShape())
                .border(1.dp, color.copy(alpha = 0.3f), MaterialShapes.Cookie9Sided.toShape()),
        ) {
            Icon(signalIcon(eval.signal), contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(signalLabelRes(eval.signal)).uppercase(),
                    color = color,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = stringResource(L10nR.string.rlProofsOfFour, eval.activeLayerCount),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(signalExplanationRes(eval.signal)), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun LayerCard(
    index: Int,
    icon: ImageVector,
    color: Color,
    title: String,
    hasData: Boolean,
    meaning: String?,
    details: List<DetailRow>,
) {
    var expanded by rememberSaveable(index) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, ExpressiveSprings.defaultSpatial(), label = "layerChevron")
    val shape = RoundedCornerShape(14.dp)
    val tint = if (hasData) color else greyColor
    val stateLabel = stringResource(if (hasData) L10nR.string.rlActiveShort else L10nR.string.rlMissingShort)
    val toggleLabel = stringResource(if (expanded) R.string.native_layerHideDetails else R.string.native_layerShowDetails)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .border(1.dp, if (hasData) color.copy(alpha = 0.25f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), shape)
            .testTag(LayersTags.layer(index)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClickLabel = toggleLabel) { expanded = !expanded }
                .semantics { stateDescription = stateLabel }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .background(color.copy(alpha = if (hasData) 0.15f else 0.05f), RoundedCornerShape(10.dp)),
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = title.uppercase(),
                        color = if (hasData) MaterialTheme.colorScheme.onSurface else greyColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = stateLabel,
                        color = if (hasData) color else greyColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (!meaning.isNullOrEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = meaning,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (details.isNotEmpty()) {
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(rotation),
                )
            }
        }
        AnimatedVisibility(
            visible = expanded && details.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                for (row in details) DetailRowView(row)
            }
        }
    }
}

@Composable
private fun DetailRowView(row: DetailRow) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 4.dp)) {
        Icon(
            imageVector = if (row.positive) Icons.Outlined.CheckCircleOutline else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (row.positive) greenColor else greyColor,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(row.label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (row.sublabel.isNotEmpty()) Text(row.sublabel, color = greyColor, fontSize = 10.sp)
        }
    }
}

@Composable
private fun meetupMeaning(meaning: MeetupMeaning, input: LayersInput): String? = when (meaning) {
    MeetupMeaning.GOOD_DIVERSITY -> stringResource(L10nR.string.rlMeetupGood)
    MeetupMeaning.SINGLE_ORGANIZER -> stringResource(L10nR.string.rlPhysGoodDiversity, (input.signerCount ?: 1L).toString())
    MeetupMeaning.NONE -> stringResource(L10nR.string.rlMeetupNone)
    MeetupMeaning.NO_TEXT -> null
}

@Composable
private fun lightningMeaning(meaning: LightningMeaning): String = stringResource(
    when (meaning) {
        LightningMeaning.BOTH -> L10nR.string.rlLnBoth
        LightningMeaning.PAID -> L10nR.string.rlLnPaid
        LightningMeaning.ACTIVE_ONLY -> L10nR.string.rlLnActiveOnly
        LightningMeaning.NONE -> L10nR.string.rlLnNone
    },
)

@Composable
private fun socialMeaning(meaning: SocialMeaning): String = stringResource(
    when (meaning) {
        SocialMeaning.MUTUAL_MANY -> L10nR.string.rlSocMutualMany
        SocialMeaning.MUTUAL -> L10nR.string.rlSocMutual
        SocialMeaning.COMMON -> L10nR.string.rlSocCommon
        SocialMeaning.ONE_SIDED -> L10nR.string.rlSocOneSided
        SocialMeaning.ORG_FOLLOW -> L10nR.string.rlSocOrgFollow
        SocialMeaning.DEFAULT -> L10nR.string.rlSocDefault
        SocialMeaning.NONE -> L10nR.string.rlSocNone
    },
)

@Composable
private fun identityMeaning(meaning: IdentityMeaning): String = stringResource(
    when (meaning) {
        IdentityMeaning.NIP05_AND_PLATFORMS -> L10nR.string.rlIdNip05Plat
        IdentityMeaning.NIP05_ONLY -> L10nR.string.rlIdNip05Only
        IdentityMeaning.PLATFORMS_ONLY -> L10nR.string.rlIdPlatOnly
        IdentityMeaning.NONE -> L10nR.string.rlIdNone
    },
)

@Composable
private fun meetupDetails(input: LayersInput, eval: LayersEvaluation): List<DetailRow> {
    val bc = input.badgeCount ?: 0L
    val mc = input.meetupCount ?: 0L
    val sc = input.signerCount ?: 0L
    val rows = mutableListOf(
        DetailRow(
            stringResource(L10nR.string.rlBadgeCount, bc.toString()),
            if (eval.allBadgesBound) {
                stringResource(L10nR.string.rlAllBound)
            } else {
                stringResource(L10nR.string.rlBoundOf, (input.boundBadges ?: 0L).toString(), bc.toString())
            },
            eval.allBadgesBound,
        ),
        DetailRow(
            stringResource(L10nR.string.rlDiffMeetups, mc.toString()),
            stringResource(if (mc >= 3) L10nR.string.rlGoodSpread else L10nR.string.rlLowSpread),
            mc >= 2,
        ),
        DetailRow(
            stringResource(L10nR.string.rlOrganizers, sc.toString()),
            stringResource(if (eval.hasSignerDiversity) L10nR.string.rlConfirmedByDiff else L10nR.string.rlOneOrgOnly),
            eval.hasSignerDiversity,
        ),
    )
    val since = input.since
    if (!since.isNullOrEmpty()) {
        val age = input.accountAgeDays ?: 0L
        rows += DetailRow(
            stringResource(L10nR.string.rlMemberSince, since),
            stringResource(L10nR.string.rlDaysCount, age.toString()),
            age > 60,
        )
    }
    return rows
}

@Composable
private fun lightningDetails(input: LayersInput): List<DetailRow> {
    val rows = mutableListOf<DetailRow>()
    if (input.humanityVerified) {
        rows += DetailRow(stringResource(L10nR.string.rlHumanVerified), stringResource(L10nR.string.rlRealLnPayment), true)
    }
    val z: ZapLayerInput? = input.zaps?.takeIf { it.totalCount > 0 }
    if (z != null) {
        rows += DetailRow(
            stringResource(L10nR.string.rlZapsSent, z.sentCount.toString()),
            stringResource(L10nR.string.rlToRecipients, z.uniqueRecipientCount.toString()),
            z.uniqueRecipientCount > 3,
        )
        rows += DetailRow(
            stringResource(L10nR.string.rlZapsReceived, z.receivedCount.toString()),
            stringResource(L10nR.string.rlFromSenders, z.uniqueSenderCount.toString()),
            z.receivedCount > 0,
        )
        if (z.activeMonths > 0) {
            rows += DetailRow(stringResource(L10nR.string.rlMonthsActive, z.activeMonths.toString()), z.activityLabel, z.activeMonths >= 3)
        }
    }
    return rows
}

@Composable
private fun socialDetails(s: SocialLayerInput): List<DetailRow> {
    val anyFollow = s.isMutual || s.iFollow || s.followsMe
    val rows = mutableListOf(
        DetailRow(
            stringResource(
                when {
                    s.isMutual -> L10nR.string.rlMutualFollow
                    s.iFollow -> L10nR.string.rlYouFollow
                    s.followsMe -> L10nR.string.rlFollowsYou
                    else -> L10nR.string.rlNoFollow
                },
            ),
            stringResource(if (s.isMutual) L10nR.string.rlKnowOnNostr else L10nR.string.rlNoDirectConn),
            anyFollow,
        ),
        DetailRow(
            stringResource(L10nR.string.rlCommonContacts, s.commonContactCount.toString()),
            stringResource(
                when {
                    s.commonContactCount > 5 -> L10nR.string.rlSameNetwork
                    s.commonContactCount > 0 -> L10nR.string.rlSomeOverlap
                    else -> L10nR.string.rlSeparateNetworks
                },
            ),
            s.commonContactCount > 0,
        ),
    )
    if (s.orgFollowerCount > 0) {
        rows += DetailRow(stringResource(L10nR.string.rlOrgsFollow, s.orgFollowerCount.toString()), stringResource(L10nR.string.rlEndorsement), true)
    }
    return rows
}

@Composable
private fun identityDetails(input: LayersInput): List<DetailRow> {
    val rows = mutableListOf<DetailRow>()
    val nip05 = input.nip05?.takeIf { it.valid }
    if (nip05 != null) rows += DetailRow(nip05.nip05, nip05.domainLabel, true)
    input.platformProofs?.forEach { (key, username) ->
        val label = TrustLayersEvaluator.platformLabel(key) + if (username.isNotEmpty()) ": @$username" else ""
        rows += DetailRow(label, stringResource(L10nR.string.rlLinked), true)
    }
    if (rows.isEmpty()) {
        rows += DetailRow(stringResource(L10nR.string.rlNoIdentification), stringResource(L10nR.string.rlAnonymous), false)
    }
    return rows
}

@Preview
@Composable
private fun ReputationLayersPreview() {
    EinundzwanzigTheme {
        ReputationLayers(
            LayersInput(
                badgeCount = 8, boundBadges = 7, meetupCount = 4, signerCount = 3, since = "2025-03",
                zaps = ZapLayerInput(12, 5, 4, 3, 6, true), humanityVerified = true,
                social = SocialLayerInput(iFollow = true, followsMe = true, isMutual = true, commonContactCount = 8, orgFollowerCount = 2),
                accountAgeDays = 120,
            ),
            modifier = Modifier.padding(16.dp),
        )
    }
}
