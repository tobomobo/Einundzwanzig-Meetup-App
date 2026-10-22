package space.einundzwanzig.meetup.feature.community.hub

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.News
import space.einundzwanzig.meetup.core.navigation.PlebrapLibrary
import space.einundzwanzig.meetup.core.navigation.PortalArea
import space.einundzwanzig.meetup.core.ui.component.AppLargeTopBar
import space.einundzwanzig.meetup.core.ui.guide.FinishTourOnDispose
import space.einundzwanzig.meetup.core.ui.guide.GuideStep
import space.einundzwanzig.meetup.core.ui.guide.GuideTour
import space.einundzwanzig.meetup.core.ui.guide.StartTourEffect
import space.einundzwanzig.meetup.core.ui.guide.guideAnchor
import space.einundzwanzig.meetup.core.ui.guide.guideScrollContainer
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.community.CommunityLinks
import space.einundzwanzig.meetup.data.proofs.satoshiduell.DuellStatus
import space.einundzwanzig.meetup.feature.community.R
import space.einundzwanzig.meetup.feature.community.common.AccentCard
import space.einundzwanzig.meetup.feature.community.common.ObserveAsEvents
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Guide anchors of the community tour (spec 11 §2.5 `CommunityTour`). */
internal object CommunityHubAnchors {
    const val PORTAL = "community.portal"
    const val NEWS = "community.news"
    const val SHOUTOUT = "community.shoutout"
    const val DUELL = "community.duell"
}

/** `CommunityTour.steps()`. */
internal fun communityTourSteps(): List<GuideStep> = listOf(
    GuideStep(CommunityHubAnchors.PORTAL, L10nR.string.guideCommunityPortalTitle, L10nR.string.guideCommunityPortalBody),
    GuideStep(CommunityHubAnchors.NEWS, L10nR.string.guideCommunityNewsTitle, L10nR.string.guideCommunityNewsBody),
    GuideStep(CommunityHubAnchors.SHOUTOUT, L10nR.string.guidePortalShoutoutTitle, L10nR.string.guidePortalShoutoutBody),
    GuideStep(CommunityHubAnchors.DUELL, L10nR.string.guideCommunityFunTitle, L10nR.string.guideCommunityFunBody),
)

@Composable
fun CommunityHubRoute(viewModel: CommunityHubViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    ObserveAsEvents(viewModel.urls) { navigator.openUrl(it) }
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    StartTourEffect(GuideTour.Portal, delayMillis = 500, steps = ::communityTourSteps)
    FinishTourOnDispose(GuideTour.Portal)

    CommunityHubScreen(
        state = state,
        onBack = navigator::back,
        onAction = { action ->
            when (action) {
                CommunityHubAction.OpenPortal -> navigator.navigate(PortalArea)
                CommunityHubAction.OpenNews -> navigator.navigate(News())
                CommunityHubAction.OpenNostr -> navigator.openUrl(CommunityLinks.NOSTR_PROFILE)
                CommunityHubAction.OpenShoutout -> navigator.openUrl(CommunityLinks.SHOUTOUT)
                CommunityHubAction.OpenPodcast -> navigator.openUrl(CommunityLinks.PODCAST)
                CommunityHubAction.OpenSatoshiDuell -> viewModel.openSatoshiDuell()
                CommunityHubAction.OpenPlebrap -> navigator.navigate(PlebrapLibrary)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityHubScreen(
    state: CommunityHubUiState,
    onAction: (CommunityHubAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scrollState = rememberScrollState()
    val colors = MaterialTheme.colorScheme
    val ext = MaterialTheme.extendedColors
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppLargeTopBar(
                title = stringResource(L10nR.string.chTitle),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .guideScrollContainer(scrollState)
                .verticalScroll(scrollState)
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PortalBigCard(
                onClick = { onAction(CommunityHubAction.OpenPortal) },
                modifier = Modifier.guideAnchor(CommunityHubAnchors.PORTAL),
            )
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                HubSmallCard(
                    icon = Icons.AutoMirrored.Rounded.Article,
                    color = colors.primary,
                    title = stringResource(L10nR.string.chNews),
                    subtitle = stringResource(L10nR.string.chNewsSub),
                    onClick = { onAction(CommunityHubAction.OpenNews) },
                    modifier = Modifier.weight(1f).fillMaxHeight().guideAnchor(CommunityHubAnchors.NEWS),
                )
                HubSmallCard(
                    icon = Icons.Rounded.Hub,
                    color = ext.nostr,
                    title = stringResource(L10nR.string.chNostr),
                    subtitle = stringResource(L10nR.string.chNostrSub),
                    onClick = { onAction(CommunityHubAction.OpenNostr) },
                    external = true,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                HubSmallCard(
                    icon = Icons.Rounded.Campaign,
                    color = colors.primary,
                    title = stringResource(L10nR.string.chShoutout),
                    subtitle = stringResource(L10nR.string.chShoutoutSub),
                    onClick = { onAction(CommunityHubAction.OpenShoutout) },
                    external = true,
                    modifier = Modifier.weight(1f).fillMaxHeight().guideAnchor(CommunityHubAnchors.SHOUTOUT),
                )
                HubSmallCard(
                    icon = Icons.Rounded.Podcasts,
                    color = ext.purple,
                    title = stringResource(L10nR.string.chPodcast),
                    subtitle = stringResource(L10nR.string.chPodcastSub),
                    onClick = { onAction(CommunityHubAction.OpenPodcast) },
                    external = true,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            SatoshiDuellCard(
                status = state.shownDuell,
                onClick = { onAction(CommunityHubAction.OpenSatoshiDuell) },
                modifier = Modifier.guideAnchor(CommunityHubAnchors.DUELL),
            )
            HubWideCard(
                icon = Icons.Rounded.GraphicEq,
                color = colors.primary,
                title = "PlebRap",
                subtitle = stringResource(L10nR.string.chPlebrapSub),
                onClick = { onAction(CommunityHubAction.OpenPlebrap) },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Watermark(icon: ImageVector, color: Color, size: Dp = 96.dp) {
    Icon(
        icon,
        contentDescription = null,
        tint = color.copy(alpha = 0.10f),
        modifier = Modifier.size(size),
    )
}

@Composable
private fun BoxScope.CornerWatermark(icon: ImageVector, color: Color) {
    Box(Modifier.align(Alignment.BottomEnd).offset(x = 12.dp, y = 12.dp)) { Watermark(icon, color) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PortalBigCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    AccentCard(accent = accent, onClick = onClick, modifier = modifier.fillMaxWidth()) {
        CornerWatermark(Icons.Rounded.Public, accent)
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(52.dp)
                    .background(accent.copy(alpha = 0.14f), MaterialShapes.Cookie9Sided.toShape()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Public, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(L10nR.string.chPortal),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(L10nR.string.chPortalSub),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.extendedColors.textTertiary,
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.extendedColors.textTertiary,
            )
        }
    }
}

@Composable
private fun HubCardText(title: String, subtitle: String, subtitleLines: Int) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(3.dp))
    Text(
        subtitle,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
        color = MaterialTheme.extendedColors.textTertiary,
        maxLines = subtitleLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun HubSmallCard(
    icon: ImageVector,
    color: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    external: Boolean = false,
) {
    AccentCard(accent = color, onClick = onClick, modifier = modifier) {
        CornerWatermark(icon, color)
        Column(
            Modifier.fillMaxSize().heightIn(min = 130.dp).padding(16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                Spacer(Modifier.weight(1f))
                Icon(
                    if (external) Icons.Rounded.OpenInNew else Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.extendedColors.textTertiary,
                    modifier = Modifier.size(if (external) 17.dp else 20.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            HubCardText(title, subtitle, subtitleLines = 2)
        }
    }
}

@Composable
private fun HubWideCard(
    icon: ImageVector,
    color: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AccentCard(accent = color, onClick = onClick, modifier = modifier.fillMaxWidth()) {
        CornerWatermark(icon, color)
        Column(
            Modifier.fillMaxWidth().heightIn(min = 112.dp).padding(16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.extendedColors.textTertiary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            HubCardText(title, subtitle, subtitleLines = 1)
        }
    }
}

/** SatoshiDuell card (spec 11 §2.5 item 4, status colours as in SatoshiDuell itself). */
@Composable
internal fun SatoshiDuellCard(status: DuellStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val gold = MaterialTheme.extendedColors.duellGold
    val orange = MaterialTheme.colorScheme.primary
    val green = MaterialTheme.extendedColors.success
    val tertiary = MaterialTheme.extendedColors.textTertiary
    val turn = stringResource(L10nR.string.sdShortTurn)
    val lobby = stringResource(L10nR.string.sdShortLobby)
    val wait = stringResource(L10nR.string.sdShortWait)
    AccentCard(
        accent = gold,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        gradientAlpha = 0.16f,
        borderAlpha = 0.28f,
    ) {
        Image(
            painter = painterResource(R.drawable.satoshiduell),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 10.dp, y = 10.dp)
                .size(100.dp)
                .alpha(0.13f),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .padding(PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 18.dp)),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bolt, contentDescription = null, tint = gold, modifier = Modifier.size(22.dp))
                Spacer(Modifier.weight(1f))
                val n = status.actionCount
                if (n > 0) {
                    Text(
                        "$n",
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .background(gold.copy(alpha = 0.18f), RoundedCornerShape(9.dp))
                            .border(0.8.dp, gold.copy(alpha = 0.5f), RoundedCornerShape(9.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                            .testTag("duellCount"),
                        color = gold,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold),
                    )
                }
                Icon(Icons.Rounded.OpenInNew, contentDescription = null, tint = tertiary, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "SatoshiDuell",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(3.dp))
            val parts = buildList {
                if (status.myTurn > 0) add(Triple("⚡ ${status.myTurn} $turn", SpanStyle(color = gold, fontWeight = FontWeight.Bold), 0))
                if (status.lobby > 0) add(Triple("${status.lobby} $lobby", SpanStyle(color = orange, fontWeight = FontWeight.SemiBold), 1))
                if (status.waiting > 0) add(Triple("${status.waiting} $wait", SpanStyle(color = green), 2))
            }
            val style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp)
            if (parts.isEmpty()) {
                Text(
                    stringResource(L10nR.string.chDuellSub),
                    style = style,
                    color = tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    buildAnnotatedString {
                        parts.forEachIndexed { i, (text, span, _) ->
                            if (i > 0) withStyle(SpanStyle(color = tertiary)) { append("  ·  ") }
                            withStyle(span) { append(text) }
                        }
                    },
                    style = style,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Preview
@Composable
private fun CommunityHubScreenPreview() {
    EinundzwanzigTheme {
        CommunityHubScreen(
            state = CommunityHubUiState(DuellStatus(myTurn = 2, waiting = 1, lobby = 3)),
            onAction = {},
            onBack = {},
        )
    }
}
