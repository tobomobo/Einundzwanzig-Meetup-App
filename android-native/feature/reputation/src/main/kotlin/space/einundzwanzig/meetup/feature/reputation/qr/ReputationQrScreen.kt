package space.einundzwanzig.meetup.feature.reputation.qr

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MilitaryTech
import androidx.compose.material.icons.rounded.PeopleOutline
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.ReputationCard
import space.einundzwanzig.meetup.core.navigation.ReputationScanner
import space.einundzwanzig.meetup.core.ui.component.AppLargeTopBar
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarDurations
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.EmptyState
import space.einundzwanzig.meetup.core.ui.component.FullScreenLoading
import space.einundzwanzig.meetup.core.ui.component.InfoBanner
import space.einundzwanzig.meetup.core.ui.component.LocalAppSnackbarHostState
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.core.ui.component.showAppSnackbar
import space.einundzwanzig.meetup.core.ui.motion.ExpressiveSprings
import space.einundzwanzig.meetup.core.ui.motion.rememberReducedMotion
import space.einundzwanzig.meetup.core.ui.share.rememberComposableCapturer
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.reputation.publish.PublishResult
import space.einundzwanzig.meetup.data.reputation.qr.ReputationQr.ProofStatus
import space.einundzwanzig.meetup.feature.reputation.R
import space.einundzwanzig.meetup.feature.reputation.common.QR_SHARE_FILE
import space.einundzwanzig.meetup.feature.reputation.common.cyanColor
import space.einundzwanzig.meetup.feature.reputation.common.greenColor
import space.einundzwanzig.meetup.feature.reputation.common.greyColor
import space.einundzwanzig.meetup.feature.reputation.common.orangeColor
import space.einundzwanzig.meetup.feature.reputation.common.purpleColor
import space.einundzwanzig.meetup.feature.reputation.common.shareCapturedCard
import space.einundzwanzig.meetup.feature.reputation.common.tertiaryTextColor
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags of the REPUTATION screen. */
internal object ReputationQrTags {
    const val CARD = "rep_qr_card"
    const val SHARE_BUTTON = "rep_qr_share"
    const val SHARE_PROGRESS = "rep_qr_share_progress"
    const val PUBLISH_BUTTON = "rep_qr_publish"
    const val PUBLISH_INFO = "rep_qr_publish_info"
    const val PROOF_STATUS = "rep_qr_proof_status"
    const val OPEN_CARD = "rep_qr_open_card"
    const val OPEN_SCANNER = "rep_qr_open_scanner"
}

/** String resource and format args of a publish result (spec 04 §2.10 `repPublish*`). */
internal fun PublishResult.toUiText(): Pair<Int, Array<Any>> = when (this) {
    PublishResult.NoKey -> R.string.native_repPublishNoKey to emptyArray()
    PublishResult.NoBadges -> R.string.native_repPublishNoBadges to emptyArray()
    PublishResult.UpToDate -> R.string.native_repPublishUpToDate to emptyArray()
    is PublishResult.Sent -> R.string.native_repPublishOk to arrayOf(relayCount)
    is PublishResult.NoRelay -> R.string.native_repPublishNoRelay to emptyArray()
    is PublishResult.Error -> R.string.native_repPublishError to arrayOf(cause.message ?: cause.toString())
}

/** Localized text of a publish result. */
@Composable
internal fun publishResultText(result: PublishResult): String {
    val (res, args) = result.toUiText()
    return stringResource(res, *args)
}

/** Stateful entry of [ReputationQr][space.einundzwanzig.meetup.core.navigation.ReputationQr] (spec 04 §2.1). */
@Composable
internal fun ReputationQrRoute(viewModel: ReputationQrViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val appHost = LocalAppSnackbarHostState.current
    val localHost = remember { SnackbarHostState() }
    val host = appHost ?: localHost
    val context = LocalContext.current
    val resources by rememberUpdatedState(LocalResources.current)
    val scope = rememberCoroutineScope()
    val capturer = rememberComposableCapturer()
    var cardSize by remember { mutableStateOf(DpSize.Zero) }
    val shareText = stringResource(L10nR.string.reputationVerified)
    var sharing by remember { mutableStateOf(false) }
    val fontScale = LocalDensity.current.fontScale

    LaunchedEffect(viewModel, host) {
        viewModel.events.collect { event ->
            when (event) {
                is ReputationQrEvent.Published -> host.showAppSnackbar(
                    message = publishMessage(resources, event.result),
                    tone = if (event.result.success) Tone.Success else Tone.Error,
                    durationMillis = AppSnackbarDurations.Medium,
                )
                ReputationQrEvent.SignFailed -> host.showAppSnackbar(
                    message = resources.getString(R.string.native_repSignFailed),
                    tone = Tone.Error,
                    durationMillis = AppSnackbarDurations.VeryLong,
                    withDismissAction = true,
                )
            }
        }
    }

    ReputationQrScreen(
        state = state,
        isSharing = sharing,
        onAction = { action ->
            when (action) {
                ReputationQrAction.OpenCard -> navigator.navigate(ReputationCard)
                ReputationQrAction.OpenScanner -> navigator.navigate(ReputationScanner)
                ReputationQrAction.ShareImage -> {
                    val content = state.content
                    if (content != null && !sharing && cardSize != DpSize.Zero) {
                        sharing = true
                        scope.launch {
                            try {
                                shareCapturedCard(context, capturer, cardSize.width, cardSize.height, fontScale, QR_SHARE_FILE, shareText) {
                                    content.CardVisual()
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                host.showAppSnackbar(resources.getString(L10nR.string.repShareError, e.toString()), Tone.Error)
                            } finally {
                                sharing = false
                            }
                        }
                    }
                }
                else -> viewModel.onAction(action)
            }
        },
        onBack = navigator::back,
        onCardSize = { cardSize = it },
        snackbarHost = { if (appHost == null) AppSnackbarHost(localHost) },
    )
}

private fun publishMessage(resources: android.content.res.Resources, result: PublishResult): String {
    val (res, args) = result.toUiText()
    return resources.getString(res, *args)
}

@Composable
private fun ReputationQrContent.CardVisual(modifier: Modifier = Modifier) {
    ReputationCardVisual(
        nickname = nickname,
        score = score,
        level = level,
        badgeCount = verifiedBadgeCount,
        meetupCount = uniqueMeetups,
        signerCount = uniqueSigners,
        qrData = qrData,
        isSigned = isSigned,
        modifier = modifier,
    )
}

/** Stateless REPUTATION screen (spec 04 §2.1). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReputationQrScreen(
    state: ReputationQrUiState,
    onAction: (ReputationQrAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    isSharing: Boolean = false,
    onCardSize: (DpSize) -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = snackbarHost,
        topBar = {
            AppLargeTopBar(
                title = stringResource(L10nR.string.reputationTitle),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
                actions = {
                    TooltipIconButton(
                        icon = Icons.Rounded.IosShare,
                        label = stringResource(L10nR.string.rcShareImage),
                        enabled = state.canOpenCard,
                        onClick = { onAction(ReputationQrAction.OpenCard) },
                        modifier = Modifier.testTag(ReputationQrTags.OPEN_CARD),
                    )
                    TooltipIconButton(
                        icon = Icons.Rounded.QrCodeScanner,
                        label = stringResource(L10nR.string.reputationCheck),
                        onClick = { onAction(ReputationQrAction.OpenScanner) },
                        modifier = Modifier.testTag(ReputationQrTags.OPEN_SCANNER),
                    )
                },
            )
        },
    ) { padding ->
        AnimatedContent(
            targetState = when {
                state.isLoading -> 0
                state.loadFailed -> 1
                state.content == null -> 2
                else -> 3
            },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "repQrState",
            modifier = Modifier.padding(padding),
        ) { phase ->
            when (phase) {
                0 -> FullScreenLoading()
                1 -> EmptyState(
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.native_repLoadError),
                    subtitle = "",
                    actionLabel = stringResource(R.string.native_retry),
                    onAction = { onAction(ReputationQrAction.Retry) },
                    accentColor = MaterialTheme.colorScheme.error,
                )
                2 -> EmptyView(unreadable = state.badgesUnreadable, onScan = { onAction(ReputationQrAction.OpenScanner) })
                else -> state.content?.let { ContentView(state, it, isSharing, onAction, onCardSize) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TooltipIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            Icon(icon, contentDescription = label)
        }
    }
}

@Composable
private fun EmptyView(unreadable: Boolean, onScan: () -> Unit) {
    EmptyState(
        icon = Icons.Rounded.WorkspacePremium,
        title = stringResource(if (unreadable) L10nR.string.badgesUnreadableBody else L10nR.string.reputationNoBadges),
        subtitle = if (unreadable) {
            ""
        } else {
            stringResource(L10nR.string.reputationBuildHint1) + stringResource(L10nR.string.reputationBuildHint2)
        },
        actionLabel = stringResource(L10nR.string.reputationScanQr),
        onAction = onScan,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ContentView(
    state: ReputationQrUiState,
    content: ReputationQrContent,
    isSharing: Boolean,
    onAction: (ReputationQrAction) -> Unit,
    onCardSize: (DpSize) -> Unit,
) {
    val density = LocalDensity.current
    val reducedMotion = rememberReducedMotion()
    val entrance = remember { Animatable(if (reducedMotion) 1f else 0.92f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, ExpressiveSprings.defaultSpatial()) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!state.hasIdentity) {
            InfoBanner(text = stringResource(L10nR.string.reputationNoIdentity), tone = Tone.Error)
            Spacer(Modifier.height(16.dp))
        }
        content.CardVisual(
            modifier = Modifier
                .testTag(ReputationQrTags.CARD)
                .graphicsLayer {
                    scaleX = entrance.value
                    scaleY = entrance.value
                }
                .onSizeChanged { size ->
                    with(density) { onCardSize(DpSize(size.width.toDp(), size.height.toDp())) }
                },
        )
        Spacer(Modifier.height(20.dp))
        StatsRow(content)
        if (content.organizerMeetupCount > 0) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = pluralOrganizerNote(content.organizerMeetupCount),
                    color = tertiaryTextColor,
                    fontSize = 11.5.sp,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        ProofStatusBlock(content, state.platformProofCount)
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onAction(ReputationQrAction.ShareImage) },
            enabled = !isSharing,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag(ReputationQrTags.SHARE_BUTTON),
        ) {
            if (isSharing) {
                LoadingIndicator(
                    color = LocalContentColor.current,
                    modifier = Modifier.size(22.dp).testTag(ReputationQrTags.SHARE_PROGRESS),
                )
            } else {
                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(stringResource(L10nR.string.reputationShareImage), fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        PublishButton(state.isPublishing) { onAction(ReputationQrAction.Publish) }
        AnimatedVisibility(visible = state.lastPublish != null) {
            state.lastPublish?.let {
                Text(
                    text = publishResultText(it),
                    color = greyColor,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .testTag(ReputationQrTags.PUBLISH_INFO)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        Spacer(Modifier.height(56.dp))
    }
}

/** `reputationOrganizerNote(n)` is an `int` placeholder string, not a plural. */
@Composable
private fun pluralOrganizerNote(n: Int): String = stringResource(L10nR.string.reputationOrganizerNote, n)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PublishButton(isPublishing: Boolean, onClick: () -> Unit) {
    val cyan = cyanColor
    FilledTonalButton(
        onClick = onClick,
        enabled = !isPublishing,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = cyan.copy(alpha = 0.15f),
            contentColor = cyan,
            disabledContainerColor = cyan.copy(alpha = 0.08f),
            disabledContentColor = cyan.copy(alpha = 0.7f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag(ReputationQrTags.PUBLISH_BUTTON),
    ) {
        if (isPublishing) {
            LoadingIndicator(color = cyan, modifier = Modifier.size(22.dp))
        } else {
            Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(if (isPublishing) L10nR.string.reputationPublishing else L10nR.string.reputationUpdateRelays),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun StatsRow(content: ReputationQrContent) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        StatTile(Icons.Rounded.MilitaryTech, content.totalBadges, stringResource(L10nR.string.reputationBadges), orangeColor, Modifier.weight(1f))
        StatTile(Icons.Rounded.LocationOn, content.uniqueMeetups, stringResource(L10nR.string.reputationMeetups), cyanColor, Modifier.weight(1f))
        StatTile(Icons.Rounded.PeopleOutline, content.uniqueSigners, stringResource(L10nR.string.reputationSigners), purpleColor, Modifier.weight(1f))
        StatTile(Icons.Rounded.Link, content.boundBadgeCount, stringResource(L10nR.string.reputationBound), greenColor, Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(icon: ImageVector, value: Int, label: String, color: Color, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .border(1.dp, color.copy(alpha = 0.2f), shape)
            .padding(vertical = 14.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(value.toString(), color = color, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
private fun ProofStatusBlock(content: ReputationQrContent, platformProofCount: Int) {
    val total = content.countedBadgeCount
    val bound = content.boundBadgeCount
    val verified = content.verifiedBadgeCount
    // Spec 04 §8.7; args (total, bound) / (total, verified) per R-17.
    val (color, icon, text) = when (content.proofStatus) {
        ProofStatus.ALL_BOUND -> Triple(greenColor, Icons.Rounded.Verified, stringResource(L10nR.string.repAllBound, total))
        ProofStatus.PARTLY_BOUND -> Triple(
            cyanColor,
            Icons.Rounded.Link,
            stringResource(L10nR.string.repBoundOf, total, bound) +
                if (verified > bound) stringResource(L10nR.string.repBoundExtra, verified) else "",
        )
        ProofStatus.ALL_VERIFIED -> Triple(orangeColor, Icons.Outlined.Shield, stringResource(L10nR.string.repAllVerified, total))
        ProofStatus.SOME_VERIFIED -> Triple(orangeColor, Icons.Outlined.Shield, stringResource(L10nR.string.repVerifiedSchnorr, total, verified))
        ProofStatus.NONE -> Triple(greyColor, Icons.Outlined.Info, stringResource(L10nR.string.reputationNoProofs))
    }
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.08f), shape)
            .border(1.dp, color.copy(alpha = 0.3f), shape)
            .padding(14.dp)
            .testTag(ReputationQrTags.PROOF_STATUS),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, color = color, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        if (platformProofCount > 0) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Language, contentDescription = null, tint = greenColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(L10nR.string.repPlatformLinksActive, platformProofCount),
                    color = greenColor,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private val previewContent = ReputationQrContent(
    nickname = "Satoshi",
    score = 12.3,
    level = "AKTIV",
    qrData = "21v3:eyJ2Ijo2fQ==.sig.id.1700000000.pub",
    isSigned = true,
    verifiedBadgeCount = 9,
    totalBadges = 8,
    uniqueMeetups = 4,
    uniqueSigners = 3,
    boundBadgeCount = 5,
    countedBadgeCount = 8,
    organizerMeetupCount = 1,
    proofStatus = ProofStatus.PARTLY_BOUND,
)

@Preview
@Composable
private fun ReputationQrScreenPreview() {
    EinundzwanzigTheme {
        ReputationQrScreen(
            state = ReputationQrUiState(isLoading = false, hasIdentity = false, platformProofCount = 2, content = previewContent),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun ReputationQrEmptyPreview() {
    EinundzwanzigTheme {
        ReputationQrScreen(state = ReputationQrUiState(isLoading = false), onAction = {}, onBack = {})
    }
}
