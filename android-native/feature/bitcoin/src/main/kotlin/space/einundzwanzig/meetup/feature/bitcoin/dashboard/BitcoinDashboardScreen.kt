package space.einundzwanzig.meetup.feature.bitcoin.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.MempoolSettings
import space.einundzwanzig.meetup.core.ui.component.AppMediumTopBar
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.FullScreenLoading
import space.einundzwanzig.meetup.core.ui.component.MonoText
import space.einundzwanzig.meetup.core.ui.component.Tile
import space.einundzwanzig.meetup.core.ui.motion.rememberReducedMotion
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.MonoStyle
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.mempool.BitcoinDashboardData
import space.einundzwanzig.meetup.data.mempool.format.BtcFormat
import space.einundzwanzig.meetup.feature.bitcoin.R
import space.einundzwanzig.meetup.feature.bitcoin.ui.BitcoinLabels
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags of the dashboard. */
internal object DashboardTags {
    const val BLOCK_HEIGHT = "dashboard_block_height"
    const val TIME = "dashboard_time"
    const val STATUS_DOT = "dashboard_status_dot"
    const val ERROR_BANNER = "dashboard_error_banner"
    const val CONTENT = "dashboard_content"
}

/** Stateful dashboard entry (spec 13 §2.1). */
@Composable
fun BitcoinDashboardRoute(viewModel: BitcoinDashboardViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.autoRefreshWhileVisible()
        }
    }
    BitcoinDashboardScreen(state = state) { action ->
        viewModel.onAction(action)
        when (action) {
            BitcoinDashboardAction.ChangeServer -> navigator.navigate(MempoolSettings)
            BitcoinDashboardAction.Back -> navigator.back()
            else -> Unit
        }
    }
}

/** Stateless Bitcoin dashboard in the "Blockclock" look. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BitcoinDashboardScreen(
    state: BitcoinDashboardUiState,
    modifier: Modifier = Modifier,
    onAction: (BitcoinDashboardAction) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppMediumTopBar(
                title = BitcoinLabels.TITLE,
                onBack = { onAction(BitcoinDashboardAction.Back) },
                scrollBehavior = scrollBehavior,
                actions = { RefreshButton(spinning = state.loading && !state.showFullScreenLoading) { onAction(BitcoinDashboardAction.Refresh) } },
            )
        },
    ) { padding ->
        if (state.showFullScreenLoading) {
            FullScreenLoading(Modifier.padding(padding))
            return@Scaffold
        }
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { onAction(BitcoinDashboardAction.PullRefresh) },
            state = pullState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = pullState,
                    isRefreshing = state.refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
        ) {
            DashboardContent(state, onAction)
        }
    }
}

@Composable
private fun RefreshButton(spinning: Boolean, onClick: () -> Unit) {
    val description = stringResource(L10nR.string.convRefresh)
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
        AnimatedContent(targetState = spinning, label = "refreshIcon") { busy ->
            if (busy) {
                AppLoadingIndicator(size = 24.dp)
            } else {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun DashboardContent(state: BitcoinDashboardUiState, onAction: (BitcoinDashboardAction) -> Unit) {
    val data = state.data
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(DashboardTags.CONTENT)
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 40.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusRow(state)
        AnimatedVisibility(
            visible = data != null && data.isDead,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            if (data != null) {
                Column {
                    Spacer(Modifier.height(20.dp))
                    DeadBanner(data) { onAction(BitcoinDashboardAction.ChangeServer) }
                }
            }
        }
        if (data != null && !data.isDead && !data.isLive) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "${stringResource(L10nR.string.dashPartial)} (${data.sourcesOk}/${data.sourcesTotal})",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(28.dp))
        BlockHero(data?.blockHeight ?: 0)
        Spacer(Modifier.height(32.dp))
        if (data != null) {
            NetworkRows(data, state.currency)
            Spacer(Modifier.height(20.dp))
            LightningSection(data)
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "${stringResource(L10nR.string.dashSource)}: ${state.hostLabel}",
            color = MaterialTheme.extendedColors.textTertiary,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatusRow(state: BitcoinDashboardUiState) {
    val status = state.status
    val color = when (status) {
        DashboardStatus.UNKNOWN -> MaterialTheme.extendedColors.textTertiary
        DashboardStatus.DEAD -> MaterialTheme.colorScheme.error
        DashboardStatus.PARTIAL -> MaterialTheme.colorScheme.primary
        DashboardStatus.LIVE -> MaterialTheme.extendedColors.success
    }
    val description = stringResource(
        when (status) {
            DashboardStatus.UNKNOWN -> R.string.native_bitcoin_status_unknown
            DashboardStatus.DEAD -> R.string.native_bitcoin_status_dead
            DashboardStatus.PARTIAL -> R.string.native_bitcoin_status_partial
            DashboardStatus.LIVE -> R.string.native_bitcoin_status_live
        },
    )
    val glow by animateFloatAsState(if (state.fresh) 1f else 0f, tween(400), label = "glow")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .testTag(DashboardTags.STATUS_DOT)
                .semantics { contentDescription = description }
                .drawBehind {
                    if (glow > 0f) {
                        drawCircle(color.copy(alpha = 0.35f * glow), radius = size.minDimension * 1.1f, center = Offset(size.width / 2, size.height / 2))
                    }
                    drawCircle(color.copy(alpha = 0.55f + 0.45f * glow))
                },
        )
        Spacer(Modifier.width(8.dp))
        MonoText(
            text = BtcFormat.updatedLabel(state.data),
            modifier = Modifier.testTag(DashboardTags.TIME),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun DeadBanner(data: BitcoinDashboardData, onChangeServer: () -> Unit) {
    val red = MaterialTheme.colorScheme.error
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(DashboardTags.ERROR_BANNER)
            .background(red.copy(alpha = 0.10f), shape)
            .border(0.8.dp, red.copy(alpha = 0.35f), shape)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = red, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(if (data.blocked) L10nR.string.dashBlockedTitle else L10nR.string.dashOfflineTitle),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.semantics { heading() },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(if (data.blocked) L10nR.string.dashBlockedBody else L10nR.string.dashOfflineBody),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.8.sp),
        )
        if (data.lastError.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            MonoText(data.lastError, color = MaterialTheme.extendedColors.textTertiary, fontSize = 11.sp)
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onChangeServer, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
            Icon(Icons.Rounded.Dns, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(L10nR.string.dashChangeServer), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BlockHero(height: Int) {
    val primary = MaterialTheme.colorScheme.primary
    val reduced = rememberReducedMotion()
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
        // Expressive decorative shape behind the block clock.
        Box(
            Modifier
                .size(170.dp)
                .background(primary.copy(alpha = 0.07f), MaterialShapes.Cookie9Sided.toShape()),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = BitcoinLabels.BLOCK,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                letterSpacing = 3.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            AnimatedContent(
                targetState = BtcFormat.intOrDash(height.toLong()),
                transitionSpec = {
                    if (reduced) {
                        fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    } else {
                        (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
                    }
                },
                label = "blockHeight",
            ) { text ->
                Text(
                    text = text,
                    modifier = Modifier.testTag(DashboardTags.BLOCK_HEIGHT),
                    color = primary,
                    fontSize = 64.sp,
                    lineHeight = 64.sp,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.ExtraBold,
                    style = MonoStyle,
                )
            }
        }
    }
}

@Composable
private fun NetworkRows(data: BitcoinDashboardData, currency: String) {
    Tile(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
        Column {
            val rows = listOf(
                BitcoinLabels.FEES to BtcFormat.feesRow(data),
                BitcoinLabels.MOSCOW to data.moscowTime,
                BitcoinLabels.priceRow(currency) to BtcFormat.priceOrDash(data.priceIn(currency)),
                BitcoinLabels.SUPPLY to BtcFormat.intOrDash(data.supply),
                BitcoinLabels.HASHRATE to BtcFormat.hashrate(data.hashrateEhs),
                BitcoinLabels.DIFFICULTY to BtcFormat.difficultyRow(data),
            )
            rows.forEachIndexed { i, (label, value) ->
                if (i > 0) HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                StatRow(label, value)
            }
        }
    }
}

@Composable
private fun LightningSection(data: BitcoinDashboardData) {
    val primary = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Bolt, contentDescription = null, tint = primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            BitcoinLabels.LIGHTNING,
            color = primary,
            fontSize = 12.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.width(10.dp))
        HorizontalDivider(Modifier.weight(1f), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
    Spacer(Modifier.height(8.dp))
    Tile(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
        Column {
            StatRow(BitcoinLabels.CAPACITY, BtcFormat.lnCapacity(data.lnCapacityBtc))
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            StatRow(BitcoinLabels.NODES, BtcFormat.intOrDash(data.lnNodeCount.toLong()))
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            StatRow(BitcoinLabels.CHANNELS, BtcFormat.intOrDash(data.lnChannelCount.toLong()))
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(12.dp))
        MonoText(value, color = MaterialTheme.colorScheme.primary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

internal fun previewDashboard(sourcesOk: Int = 6, blocked: Boolean = false) = BitcoinDashboardData(
    blockHeight = 912_345,
    feeLow = 3,
    feeMedium = 8,
    feeHigh = 12,
    priceEur = 95_000.0,
    priceUsd = 105_000.0,
    prices = mapOf("EUR" to 95_000.0, "USD" to 105_000.0, "CHF" to 89_000.0),
    supply = 19_912_345,
    hashrateEhs = 812.4,
    difficultyChangePct = 3.7,
    difficultyRemainingBlocks = 1234,
    lnCapacityBtc = 5123.4,
    lnNodeCount = 15_234,
    lnChannelCount = 52_001,
    updatedAtMillis = 1_760_000_000_000,
    sourcesOk = sourcesOk,
    lastError = if (sourcesOk == 0) "HTTP 403" else "",
    blocked = blocked,
)

@Preview(name = "Dashboard live", backgroundColor = 0xFF0C0C0E, showBackground = true, heightDp = 900)
@Composable
private fun DashboardLivePreview() {
    EinundzwanzigTheme {
        BitcoinDashboardScreen(
            BitcoinDashboardUiState(data = previewDashboard(), loading = false, fresh = true, hostLabel = "mempool.space"),
        ) {}
    }
}

@Preview(name = "Dashboard blocked", backgroundColor = 0xFF0C0C0E, showBackground = true, heightDp = 900)
@Composable
private fun DashboardDeadPreview() {
    EinundzwanzigTheme {
        BitcoinDashboardScreen(
            BitcoinDashboardUiState(data = previewDashboard(sourcesOk = 0, blocked = true), loading = false, hostLabel = "mempool.space"),
        ) {}
    }
}
