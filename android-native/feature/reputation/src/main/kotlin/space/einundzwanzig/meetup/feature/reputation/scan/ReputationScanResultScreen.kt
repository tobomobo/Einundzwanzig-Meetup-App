package space.einundzwanzig.meetup.feature.reputation.scan

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MilitaryTech
import androidx.compose.material.icons.rounded.PeopleOutline
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import space.einundzwanzig.meetup.core.common.dart.toStringAsFixed
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.ui.component.AppMediumTopBar
import space.einundzwanzig.meetup.core.ui.component.InfoBanner
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.MonoStyle
import space.einundzwanzig.meetup.data.proofs.nip05.Nip05Result
import space.einundzwanzig.meetup.data.proofs.zap.ZapStats
import space.einundzwanzig.meetup.data.reputation.qr.QrParseResult
import space.einundzwanzig.meetup.data.reputation.qr.ReputationQrData
import space.einundzwanzig.meetup.data.socialgraph.social.SocialAnalysis
import space.einundzwanzig.meetup.feature.reputation.R
import space.einundzwanzig.meetup.feature.reputation.common.platformDisplayName
import space.einundzwanzig.meetup.feature.reputation.common.EvidenceRow
import space.einundzwanzig.meetup.feature.reputation.common.LayerLoad
import space.einundzwanzig.meetup.feature.reputation.common.LiveLayers
import space.einundzwanzig.meetup.feature.reputation.common.SectionCard
import space.einundzwanzig.meetup.feature.reputation.common.SectionHeader
import space.einundzwanzig.meetup.feature.reputation.common.amberColor
import space.einundzwanzig.meetup.feature.reputation.common.cyanColor
import space.einundzwanzig.meetup.feature.reputation.common.nip05DomainLabel
import space.einundzwanzig.meetup.feature.reputation.common.zapActivityLabel
import space.einundzwanzig.meetup.feature.reputation.common.greenColor
import space.einundzwanzig.meetup.feature.reputation.common.greyColor
import space.einundzwanzig.meetup.feature.reputation.common.levelStyle
import space.einundzwanzig.meetup.feature.reputation.common.localizedLevel
import space.einundzwanzig.meetup.feature.reputation.common.orangeColor
import space.einundzwanzig.meetup.feature.reputation.common.platformIcon
import space.einundzwanzig.meetup.feature.reputation.common.purpleColor
import space.einundzwanzig.meetup.feature.reputation.common.qrVerifyMessage
import space.einundzwanzig.meetup.feature.reputation.common.redColor
import space.einundzwanzig.meetup.feature.reputation.common.shortenNpub
import space.einundzwanzig.meetup.feature.reputation.common.toColor
import space.einundzwanzig.meetup.feature.reputation.common.toImageVector
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags of the result screen. */
internal object ScanResultTags {
    const val HEADER = "scan_result_header"
    const val BACK = "scan_result_back"
    const val MISMATCH = "scan_result_mismatch"
    const val LAYERS_LOADING = "scan_result_layers_loading"
}

/** Stateful entry of [ReputationScanResult][space.einundzwanzig.meetup.core.navigation.ReputationScanResult]. */
@Composable
internal fun ReputationScanResultRoute(
    code: String,
    viewModel: ReputationScanResultViewModel = hiltViewModel<ReputationScanResultViewModel, ReputationScanResultViewModel.Factory>(
        key = "scan-result:$code",
        creationCallback = { factory -> factory.create(code) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    ReputationScanResultScreen(state = state, onBack = navigator::back)
}

/** Stateless "ERGEBNIS" screen (spec 04 §2.3 result layout). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReputationScanResultScreen(
    state: ReputationScanResultUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { AppMediumTopBar(title = stringResource(L10nR.string.qrResultTitle), onBack = onBack, scrollBehavior = scrollBehavior) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            when (val result = state.result) {
                is QrParseResult.Verified -> VerifiedContent(result, state.layers)
                is QrParseResult.SignatureInvalid -> StatusHeader(
                    valid = false,
                    hasIdentity = false,
                    title = stringResource(L10nR.string.qrSigInvalid),
                    subtitle = qrVerifyMessage(result.message),
                    // Flutter _showFailed always passes version 0: no version chip on failures.
                    version = 0,
                )
                QrParseResult.UnknownFormat -> StatusHeader(
                    valid = false,
                    hasIdentity = false,
                    title = stringResource(L10nR.string.qrFormatUnknown),
                    subtitle = stringResource(L10nR.string.qrCantRead),
                    version = 0,
                )
                is QrParseResult.ReadError -> StatusHeader(
                    valid = false,
                    hasIdentity = false,
                    title = stringResource(L10nR.string.qrReadError),
                    subtitle = stringResource(L10nR.string.qrProcessError, result.cause.toString()),
                    version = 0,
                )
            }
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag(ScanResultTags.BACK),
            ) {
                Text(stringResource(L10nR.string.qrBack), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun VerifiedContent(result: QrParseResult.Verified, layers: LiveLayers) {
    val data = result.data
    StatusHeader(
        valid = true,
        hasIdentity = result.hasTrustedIdentity,
        title = stringResource(L10nR.string.qrVerified),
        subtitle = qrVerifyMessage(result.message),
        version = 3,
    )
    if (result.identityMismatch) {
        Spacer(Modifier.height(12.dp))
        InfoBanner(
            text = stringResource(R.string.native_qrIdentityMismatch),
            tone = Tone.Error,
            modifier = Modifier.testTag(ScanResultTags.MISMATCH),
        )
    }
    if (data.trustLevel.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        TrustBlock(data)
    }
    if (data.badgeProof.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        ProofCard(data)
    }
    LiveLayersSection(data, layers)
    if (data.meetupList.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        MeetupsCard(data.meetupList)
    }
    Spacer(Modifier.height(14.dp))
    IdentityCard(data, result.hasTrustedIdentity, result.signerNpub)
}

@Composable
private fun StatusHeader(valid: Boolean, hasIdentity: Boolean, title: String, subtitle: String, version: Int) {
    val color = when {
        !valid -> redColor
        hasIdentity -> greenColor
        else -> orangeColor
    }
    val shape = RoundedCornerShape(14.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.08f), shape)
            .border(2.dp, color.copy(alpha = 0.4f), shape)
            .padding(16.dp)
            .testTag(ScanResultTags.HEADER)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Icon(if (valid) Icons.Rounded.Verified else Icons.Rounded.GppBad, contentDescription = null, tint = color, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = color,
                fontSize = 16.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        if (version > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "v$version",
                style = MonoStyle,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun TrustBlock(data: ReputationQrData) {
    val style = levelStyle(data.trustLevel)
    val color = style.color.toColor()
    SectionCard(borderColor = color.copy(alpha = 0.25f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .background(color.copy(alpha = 0.12f), CircleShape),
            ) {
                Icon(style.icon.toImageVector(), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(localizedLevel(data.trustLevel), color = color, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    text = stringResource(R.string.native_qrStatsLine, data.badgeCount, data.meetupCount, data.signerCount),
                    color = greyColor,
                    fontSize = 10.sp,
                )
            }
            Text(data.trustScore.toStringAsFixed(1), style = MonoStyle, color = color, fontSize = 24.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.SpaceAround, modifier = Modifier.fillMaxWidth()) {
            MiniStat(Icons.Rounded.MilitaryTech, data.badgeCount, stringResource(L10nR.string.qrStatBadges), orangeColor)
            MiniStat(Icons.Rounded.LocationOn, data.meetupCount, stringResource(L10nR.string.qrStatMeetups), cyanColor)
            MiniStat(Icons.Rounded.PeopleOutline, data.signerCount, stringResource(L10nR.string.qrStatSigners), purpleColor)
            MiniStat(Icons.Rounded.Link, data.boundBadgeCount, stringResource(L10nR.string.qrStatBound), greenColor)
            MiniStat(Icons.Rounded.CalendarToday, data.accountAgeDays, stringResource(L10nR.string.qrStatDays), greyColor)
        }
    }
}

@Composable
private fun MiniStat(icon: ImageVector, value: Int, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(value.toString(), color = color, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ProofCard(data: ReputationQrData) {
    val allVerified = data.proofVerifiedCount == data.proofTotalCount && data.proofTotalCount > 0
    val color = if (allVerified) greenColor else orangeColor
    val proof = data.badgeProof
    SectionCard(borderColor = color.copy(alpha = 0.3f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (allVerified) Icons.Rounded.Verified else Icons.Outlined.Shield, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.native_qrProofLine, data.proofVerifiedCount, data.proofTotalCount),
                color = color,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Proof: " + if (proof.length > 16) proof.take(16) + "..." else proof,
            style = MonoStyle,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LiveLayersSection(data: ReputationQrData, layers: LiveLayers) {
    AnimatedVisibility(visible = layers.isLoading, enter = fadeIn() + expandVertically()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(top = 14.dp)
                .testTag(ScanResultTags.LAYERS_LOADING)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            LoadingIndicator(color = cyanColor, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = when (layers.currentLoad) {
                    LayerLoad.SOCIAL, null -> stringResource(L10nR.string.qrAnalyzingNetwork)
                    LayerLoad.LIGHTNING -> stringResource(L10nR.string.qrCheckingLightning)
                    LayerLoad.NIP05 -> stringResource(L10nR.string.qrCheckingNip05)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
    layers.nip05?.let { Nip05Row(it) }
    layers.zaps?.let {
        Spacer(Modifier.height(14.dp))
        LightningSection(it)
    }
    if (data.platformProofs.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        PlatformsSection(data)
    }
    layers.social?.let {
        Spacer(Modifier.height(14.dp))
        SocialSection(it)
    }
}

@Composable
private fun Nip05Row(nip05: Nip05Result) {
    Spacer(Modifier.height(14.dp))
    SectionCard(borderColor = (if (nip05.valid) cyanColor else redColor).copy(alpha = 0.25f)) {
        if (nip05.valid) {
            EvidenceRow(Icons.Rounded.AlternateEmail, nip05.nip05, nip05DomainLabel(nip05.domainType), cyanColor)
        } else {
            EvidenceRow(Icons.Rounded.AlternateEmail, stringResource(L10nR.string.qrNip05Invalid), nip05.nip05, redColor)
        }
    }
}

@Composable
private fun LightningSection(zaps: ZapStats) {
    val active = zaps.totalCount > 0
    val headerColor = if (active) amberColor else greyColor
    SectionCard(borderColor = headerColor.copy(alpha = 0.25f)) {
        SectionHeader(Icons.Rounded.Bolt, stringResource(L10nR.string.qrSectionLightning), headerColor)
        Spacer(Modifier.height(6.dp))
        if (zaps.hasLightningProof) {
            EvidenceRow(Icons.Rounded.VerifiedUser, stringResource(L10nR.string.qrHumanVerified), stringResource(L10nR.string.qrLightningActive), greenColor)
        }
        if (active) {
            EvidenceRow(
                Icons.Rounded.ArrowUpward,
                stringResource(L10nR.string.qrSentCount, zaps.sentCount),
                stringResource(L10nR.string.qrUniqueRecipients, zaps.uniqueRecipientCount),
                if (zaps.sentCount > 5) greenColor else greyColor,
            )
            EvidenceRow(
                Icons.Rounded.ArrowDownward,
                stringResource(L10nR.string.qrReceivedCount, zaps.receivedCount),
                stringResource(L10nR.string.qrUniqueSenders, zaps.uniqueSenderCount),
                if (zaps.receivedCount > 0) greenColor else greyColor,
            )
            if (zaps.activeMonths > 0) {
                EvidenceRow(Icons.Rounded.Schedule, stringResource(L10nR.string.rlMonthsActive, zaps.activeMonths), zapActivityLabel(zaps.activity), amberColor)
            }
        } else {
            EvidenceRow(Icons.Outlined.Info, stringResource(L10nR.string.qrNoZap), stringResource(L10nR.string.qrNoLightning), greyColor)
        }
    }
}

@Composable
private fun PlatformsSection(data: ReputationQrData) {
    SectionCard(borderColor = purpleColor.copy(alpha = 0.25f)) {
        SectionHeader(Icons.Rounded.Link, stringResource(L10nR.string.qrSectionPlatforms), purpleColor)
        Spacer(Modifier.height(6.dp))
        for ((platform, value) in data.platformProofs) {
            val entry = value as? JsonObject
            val username = entry.stringField("u") ?: entry.stringField("username") ?: ""
            val hasSig = (entry.stringField("s") ?: entry.stringField("proof_sig") ?: "").isNotEmpty()
            val label = platformDisplayName(platform)
            // S-9: the pp signature is never checked; a signature only means "linked (unconfirmed)".
            EvidenceRow(
                icon = platformIcon(platform),
                title = label + if (username.isNotEmpty()) ": @$username" else "",
                subtitle = stringResource(if (hasSig) R.string.native_qrLinkedUnconfirmed else L10nR.string.qrLinkedShort),
                // Never success green: nothing here is cryptographically confirmed.
                color = amberColor,
            )
        }
    }
}

private fun JsonObject?.stringField(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

@Composable
private fun SocialSection(social: SocialAnalysis) {
    val hasConnection = social.isMutual || social.iFollow || social.followsMe || social.commonContactCount > 0
    val headerColor = if (hasConnection) cyanColor else greyColor
    SectionCard(borderColor = headerColor.copy(alpha = 0.25f)) {
        SectionHeader(Icons.Rounded.Hub, stringResource(L10nR.string.qrSectionSocial), headerColor)
        Spacer(Modifier.height(6.dp))
        when {
            social.isMutual -> EvidenceRow(Icons.Rounded.SyncAlt, stringResource(L10nR.string.qrMutualFollow), stringResource(L10nR.string.qrBidirectional), greenColor)
            social.iFollow -> EvidenceRow(Icons.Rounded.PersonAdd, stringResource(L10nR.string.qrYouFollow), stringResource(L10nR.string.qrOneWay), cyanColor)
            social.followsMe -> EvidenceRow(Icons.Rounded.Person, stringResource(L10nR.string.qrFollowsYou), stringResource(L10nR.string.qrOneWay), cyanColor)
            else -> EvidenceRow(Icons.Rounded.PersonOff, stringResource(L10nR.string.qrNoDirectFollow), "", greyColor)
        }
        val common = social.commonContactCount
        EvidenceRow(
            Icons.Rounded.Group,
            stringResource(L10nR.string.rlCommonContacts, common),
            stringResource(
                when {
                    common > 3 -> L10nR.string.qrStrongOverlap
                    common > 0 -> L10nR.string.qrPartiallyConnected
                    else -> L10nR.string.qrNoOverlap
                },
            ),
            if (common > 0) greenColor else greyColor,
        )
        if (social.orgFollowerCount > 0) {
            EvidenceRow(Icons.Rounded.VerifiedUser, stringResource(L10nR.string.rlOrgsFollow, social.orgFollowerCount), stringResource(L10nR.string.qrEndorsement), greenColor)
        }
        if (social.hops > 0) {
            EvidenceRow(
                Icons.Rounded.Route,
                pluralStringResource(R.plurals.native_qrHopsAway, social.hops, social.hops),
                stringResource(if (social.hops == 1) L10nR.string.qrDirectConnection else L10nR.string.qrViaContacts),
                if (social.hops == 1) greenColor else amberColor,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MeetupsCard(hashes: List<String>) {
    SectionCard(borderColor = cyanColor.copy(alpha = 0.25f)) {
        SectionHeader(Icons.Rounded.LocationOn, stringResource(L10nR.string.qrSectionMeetups), cyanColor)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (hash in hashes) {
                Text(
                    text = hash,
                    style = MonoStyle,
                    fontSize = 11.sp,
                    color = cyanColor,
                    modifier = Modifier
                        .background(cyanColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun IdentityCard(data: ReputationQrData, hasIdentity: Boolean, signerNpub: String) {
    val borderColor = if (hasIdentity) purpleColor else orangeColor
    SectionCard(borderColor = borderColor.copy(alpha = 0.4f)) {
        SectionHeader(
            Icons.Rounded.Fingerprint,
            stringResource(if (hasIdentity) L10nR.string.qrSectionIdentity else L10nR.string.qrNoIdentity),
            borderColor,
        )
        Spacer(Modifier.height(8.dp))
        IdentityLine(stringResource(L10nR.string.qrLabelNickname), data.nickname ?: "Anon")
        data.claimedNpub?.takeIf { it.isNotEmpty() }?.let { IdentityLine("Nostr", it, mono = true) }
        data.telegram?.takeIf { it.isNotEmpty() }?.let { IdentityLine("Telegram", "@$it") }
        data.twitter?.takeIf { it.isNotEmpty() }?.let { IdentityLine(stringResource(L10nR.string.qrLabelTwitter), "@$it") }
        if (!hasIdentity) {
            Spacer(Modifier.height(8.dp))
            InfoBanner(text = stringResource(L10nR.string.qrNoVerifiableIdentity), tone = Tone.Warning)
        } else if (signerNpub.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.native_qrSignedBy, shortenNpub(signerNpub)),
                style = MonoStyle,
                fontSize = 10.sp,
                color = greenColor,
            )
        }
    }
}

@Composable
private fun IdentityLine(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.width(76.dp))
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            style = if (mono) MonoStyle else MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Start,
        )
    }
}

internal fun previewQrData(): ReputationQrData = ReputationQrData(
    identity = buildJsonObject { put("n", "Satoshi"); put("np", "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsxxxxxx") },
    nickname = "Satoshi",
    claimedNpub = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsxxxxxx",
    telegram = "satoshi",
    twitter = null,
    hasIdentity = true,
    trustLevel = "AKTIV",
    trustScore = 12.3,
    badgeCount = 8,
    verifiedBadgeCount = 9,
    boundBadgeCount = 5,
    meetupCount = 4,
    signerCount = 3,
    accountAgeDays = 120,
    meetupList = listOf("d4ca521ba896", "0a1b2c3d4e5f"),
    badgeProof = "abcdef0123456789abcdef",
    proofTotalCount = 8,
    proofVerifiedCount = 8,
    platformProofs = mapOf("telegram" to buildJsonObject { put("u", "satoshi"); put("s", "a".repeat(128)) }),
    payloadVersion = 6,
    timestampMillis = 1_700_000_000_000,
)

@Preview
@Composable
private fun ScanResultPreview() {
    EinundzwanzigTheme {
        ReputationScanResultScreen(
            state = ReputationScanResultUiState(
                result = QrParseResult.Verified(previewQrData(), "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsxxxxxx", "Schnorr-Signatur verifiziert ✓", false),
                layers = LiveLayers(
                    zaps = ZapStats(12, 5, 4, 3, 6, true),
                    social = SocialAnalysis("hex", 100, 80, iFollow = true, followsMe = true, isMutual = true, commonContactCount = 8, orgFollowerCount = 2, hops = 1),
                    nip05 = Nip05Result(valid = true, nip05 = "satoshi@einundzwanzig.space"),
                ),
            ),
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun ScanResultInvalidPreview() {
    EinundzwanzigTheme {
        ReputationScanResultScreen(
            state = ReputationScanResultUiState(QrParseResult.SignatureInvalid(3, "Fehler: Invalid event")),
            onBack = {},
        )
    }
}
