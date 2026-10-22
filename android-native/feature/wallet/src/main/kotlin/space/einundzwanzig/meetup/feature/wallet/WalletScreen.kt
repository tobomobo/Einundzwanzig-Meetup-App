package space.einundzwanzig.meetup.feature.wallet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ViewComfy
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.navigation.BadgeCollection
import space.einundzwanzig.meetup.core.navigation.BadgeDetails
import space.einundzwanzig.meetup.core.navigation.BadgeWorldMap
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.MeetupCalendar
import space.einundzwanzig.meetup.core.navigation.ReputationQr
import space.einundzwanzig.meetup.core.ui.component.AppLargeTopBar
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarDurations
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.EmptyState
import space.einundzwanzig.meetup.core.ui.component.FullScreenLoading
import space.einundzwanzig.meetup.core.ui.component.LocalAppSnackbarHostState
import space.einundzwanzig.meetup.core.ui.component.showAppSnackbar
import space.einundzwanzig.meetup.core.ui.guide.GuideController
import space.einundzwanzig.meetup.core.ui.guide.GuideTour
import space.einundzwanzig.meetup.core.ui.guide.LocalGuideController
import space.einundzwanzig.meetup.core.ui.guide.guideAnchor
import space.einundzwanzig.meetup.core.ui.guide.guideScrollContainer
import space.einundzwanzig.meetup.core.ui.security.SecretClipboard
import space.einundzwanzig.meetup.core.ui.share.ShareHelper
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.badges.model.BadgeIdentity
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.feature.wallet.crest.ProvideWalletCrests
import space.einundzwanzig.meetup.feature.wallet.domain.WalletLibrary
import space.einundzwanzig.meetup.feature.wallet.ui.CleanupDialog
import space.einundzwanzig.meetup.feature.wallet.ui.CollectionCard
import space.einundzwanzig.meetup.feature.wallet.ui.CompactBadgeCard
import space.einundzwanzig.meetup.feature.wallet.ui.FormerCollectionsSheet
import space.einundzwanzig.meetup.feature.wallet.ui.MergeFormerDialog
import space.einundzwanzig.meetup.feature.wallet.ui.NormalBadgeCard
import space.einundzwanzig.meetup.feature.wallet.ui.ShareReputationSheet
import space.einundzwanzig.meetup.feature.wallet.ui.YearSectionHeader
import java.util.IdentityHashMap
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags of the wallet screen. */
internal object WalletTestTags {
    const val LIST = "wallet.list"
    const val LOADING = "wallet.loading"
    const val EMPTY = "wallet.empty"
    const val UNREADABLE = "wallet.unreadable"
    const val NO_RESULTS = "wallet.noResults"
    const val SEARCH = "wallet.search"
    const val WORKING = "wallet.working"
}

/** Hard-coded title of the wallet (Flutter parity, spec 03 §2.7). */
internal const val WALLET_TITLE = "BADGE WALLET"

/**
 * Stateful badge wallet (spec 03 §2.1), used for both [space.einundzwanzig.meetup.core.navigation.WalletTab]
 * and the pushed [space.einundzwanzig.meetup.core.navigation.BadgeWallet] ([showBack]).
 */
@Composable
fun WalletRoute(showBack: Boolean = false) {
    ProvideWalletCrests { WalletRouteContent(showBack, hiltViewModel()) }
}

@Composable
internal fun WalletRouteContent(showBack: Boolean, viewModel: WalletViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val appHost = LocalAppSnackbarHostState.current
    val localHost = remember { SnackbarHostState() }
    val snackbars = appHost ?: localHost
    val lifecycleOwner = LocalLifecycleOwner.current
    // The collector outlives recompositions; read the latest host and context, not the first ones.
    val currentSnackbars by rememberUpdatedState(snackbars)
    val currentContext by rememberUpdatedState(context)

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { event ->
                val ctx = currentContext
                val host = currentSnackbars
                when (event) {
                    is WalletEvent.Snackbar -> host.showAppSnackbar(
                        ctx.getString(event.message.text, *event.message.args.toTypedArray()),
                        event.message.tone,
                        AppSnackbarDurations.Medium,
                    )
                    is WalletEvent.ShareText -> {
                        val shared = runCatching { ShareHelper.shareText(ctx, event.text, event.subject) }
                            .onFailure { AppLogger.warn("Wallet", "Teilen fehlgeschlagen", it) }
                            .getOrDefault(false)
                        if (!shared) {
                            // Clipboard fallback (spec 03 §2.1.2 step 5).
                            SecretClipboard.copyPlain(ctx, event.text, event.subject)
                            host.showAppSnackbar(ctx.getString(event.fallback.text), event.fallback.tone)
                        }
                    }
                }
            }
        }
    }

    // Wallet tour (spec 03 §2.1 recommendation): first visible with badges, 600 ms after the first
    // frame, only when no other tour runs.
    val guide = LocalGuideController.current
    val tourReady = state.loaded && state.count > 0
    LaunchedEffect(tourReady) {
        if (!tourReady) return@LaunchedEffect
        withFrameNanos { }
        delay(WalletTour.START_DELAY_MS)
        if (!guide.isActive) guide.startTour(GuideTour.Wallet, WalletTour.steps())
    }
    WalletTourLifecycle(viewModel, guide)

    WalletScreen(
        state = state,
        onBack = if (showBack) navigator::back else null,
        snackbarHostState = if (appHost == null) localHost else null,
        onAction = { action ->
            when (action) {
                WalletAction.OpenMap -> navigator.navigate(BadgeWorldMap)
                is WalletAction.OpenCollection -> navigator.navigate(BadgeCollection(action.groupKey, state.query))
                is WalletAction.OpenDetails -> {
                    val index = WalletLibrary.globalIndex(state.badges, action.badge)
                    if (index >= 0) navigator.navigate(BadgeDetails(BadgeIdentity.unionKey(action.badge), index))
                }
                WalletAction.FindMeetup -> navigator.navigate(MeetupCalendar())
                WalletAction.ShowQr -> navigator.navigate(ReputationQr)
                else -> Unit
            }
            viewModel.onAction(action)
        },
    )
}

/**
 * Navigation 3 drops a covered entry's content, so leaving the composition does not mean the
 * wallet was closed. Like the Flutter route that stays mounted under a pushed screen (spec 03
 * §2.1), the tour is only paused while covered and resumed on return; it ends (persisted as
 * completed) when the entry is popped, i.e. when its ViewModel is cleared.
 */
@Composable
private fun WalletTourLifecycle(viewModel: WalletViewModel, guide: GuideController) {
    DisposableEffect(viewModel, guide) {
        if (guide.activeTour == GuideTour.Wallet) guide.resume()
        viewModel.setOnClearedListener { guide.finishIfActive(GuideTour.Wallet) }
        onDispose { if (guide.activeTour == GuideTour.Wallet) guide.pause() }
    }
}

/** Stateless badge wallet (spec 03 §2.1). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun WalletScreen(
    state: WalletUiState,
    onAction: (WalletAction) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    snackbarHostState: SnackbarHostState? = null,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val title = if (state.count > 0) "$WALLET_TITLE (${state.count})" else WALLET_TITLE
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppLargeTopBar(
                title = title,
                onBack = onBack,
                scrollBehavior = scrollBehavior,
                actions = { WalletActions(state, onAction) },
            )
        },
        snackbarHost = { if (snackbarHostState != null) AppSnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !state.loaded -> FullScreenLoading(
                    Modifier.testTag(WalletTestTags.LOADING),
                    message = stringResource(L10nR.string.loading),
                )
                state.isEmptyWallet && state.unreadable -> EmptyState(
                    icon = Icons.Rounded.WarningAmber,
                    title = stringResource(L10nR.string.badgesUnreadableShort),
                    subtitle = stringResource(L10nR.string.badgesUnreadableBody),
                    modifier = Modifier.testTag(WalletTestTags.UNREADABLE),
                    accentColor = MaterialTheme.colorScheme.error,
                )
                state.isEmptyWallet -> EmptyState(
                    icon = Icons.Outlined.CollectionsBookmark,
                    title = stringResource(L10nR.string.walletNoBadges),
                    subtitle = stringResource(L10nR.string.walletNoBadgesSub),
                    modifier = Modifier.testTag(WalletTestTags.EMPTY),
                    actionLabel = stringResource(L10nR.string.emptyFindMeetup),
                    onAction = { onAction(WalletAction.FindMeetup) },
                )
                else -> WalletLibraryList(state, onAction)
            }
            if (state.working || state.loadingFormer) {
                LinearWavyProgressIndicator(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .testTag(WalletTestTags.WORKING),
                )
            }
        }
    }

    if (state.shareSheetOpen) {
        ShareReputationSheet(
            onShareText = { onAction(WalletAction.ShareText) },
            onShowQr = { onAction(WalletAction.ShowQr) },
            onExportJson = { onAction(WalletAction.ExportJson) },
            onDismiss = { onAction(WalletAction.DismissShareSheet) },
        )
    }
    state.formerCollections?.let { entries ->
        FormerCollectionsSheet(
            entries = entries,
            onMerge = { onAction(WalletAction.RequestMerge(it)) },
            onDismiss = { onAction(WalletAction.DismissFormer) },
        )
    }
    if (state.mergeCandidate != null) {
        MergeFormerDialog(
            onConfirm = { onAction(WalletAction.ConfirmMerge) },
            onDismiss = { onAction(WalletAction.DismissMerge) },
        )
    }
    state.cleanup?.let { cleanup ->
        CleanupDialog(
            state = cleanup,
            onConfirm = { onAction(WalletAction.ConfirmCleanup) },
            onDismiss = { onAction(WalletAction.DismissCleanup) },
        )
    }
}

/** App-bar actions in Flutter order: map, view toggle, history, cleanup, share. */
@Composable
private fun WalletActions(state: WalletUiState, onAction: (WalletAction) -> Unit) {
    if (state.showMap) {
        WalletAction(
            icon = Icons.Rounded.Public,
            label = stringResource(L10nR.string.mapButton),
            onClick = { onAction(WalletAction.OpenMap) },
            modifier = Modifier.guideAnchor(WalletTour.MAP),
        )
    }
    if (state.showCompactToggle) {
        WalletAction(
            icon = if (state.compact) Icons.Rounded.GridView else Icons.Rounded.ViewComfy,
            label = stringResource(if (state.compact) R.string.native_wallet_view_normal else R.string.native_wallet_view_compact),
            onClick = { onAction(WalletAction.ToggleCompact) },
            modifier = Modifier.guideAnchor(WalletTour.VIEW),
        )
    }
    WalletAction(
        icon = Icons.Rounded.History,
        label = stringResource(L10nR.string.walletFormerTitle),
        onClick = { onAction(WalletAction.OpenFormer) },
        enabled = !state.loadingFormer,
    )
    if (state.showCleanup) {
        WalletAction(
            icon = Icons.Rounded.CleaningServices,
            label = stringResource(L10nR.string.walletCleanupTitle),
            onClick = { onAction(WalletAction.OpenCleanup) },
            enabled = !state.working,
        )
    }
    if (state.showShare) {
        WalletAction(
            icon = Icons.Rounded.Share,
            label = stringResource(L10nR.string.walletShareTitle),
            onClick = { onAction(WalletAction.OpenShareSheet) },
            modifier = Modifier.guideAnchor(WalletTour.SHARE),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletAction(
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
private fun WalletLibraryList(state: WalletUiState, onAction: (WalletAction) -> Unit) {
    val listState = rememberLazyListState()
    val numbers = remember(state.badges) {
        IdentityHashMap<MeetupBadge, Int>().apply { state.badges.forEachIndexed { i, b -> put(b, i + 1) } }
    }
    // Search and grouping stay pinned above the scrolling list (badge_wallet.dart: Column with
    // the controls above an Expanded list).
    Column(Modifier.fillMaxSize()) {
        LibraryControls(state, onAction)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(WalletTestTags.LIST)
                .guideScrollContainer(listState),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = if (state.groupByMeetup) 8.dp else 4.dp, bottom = 24.dp),
        ) {
            if (state.groups.isEmpty()) {
                item(key = "noResults", contentType = "noResults") { NoResults() }
            } else if (state.groupByMeetup) {
                state.groups.forEachIndexed { index, group ->
                    item(key = "c:${group.key}", contentType = "collection") {
                        CollectionCard(
                            group = group,
                            onClick = { onAction(WalletAction.OpenCollection(group.key)) },
                            modifier = Modifier
                                .padding(bottom = 10.dp)
                                .then(if (index == 0) Modifier.guideAnchor(WalletTour.FIRST_BADGE) else Modifier),
                        )
                    }
                }
            } else {
                yearSections(state, numbers, onAction)
            }
        }
    }
}

private fun LazyListScope.yearSections(
    state: WalletUiState,
    numbers: Map<MeetupBadge, Int>,
    onAction: (WalletAction) -> Unit,
) {
    val columns = if (state.compact) 3 else 2
    val spacing = if (state.compact) 8.dp else 12.dp
    state.groups.forEachIndexed { index, group ->
        val collapsed = group.key in state.collapsed
        val rows = if (collapsed) emptyList() else group.group.badges.chunked(columns)
        val header = @Composable {
            YearSectionHeader(
                group = group,
                collapsed = collapsed,
                onToggle = { onAction(WalletAction.ToggleCollapsed(group.key)) },
            )
        }
        if (index == 0) {
            // The tour spotlights the first section's column (header plus its first cards,
            // spec 03 §2.1), so both share one anchored item.
            item(key = "h:${group.key}", contentType = "firstSection$columns") {
                Column(Modifier.guideAnchor(WalletTour.FIRST_BADGE)) {
                    header()
                    rows.firstOrNull()?.let { BadgeRow(it, state.compact, columns, spacing, numbers, onAction) }
                }
            }
        } else {
            item(key = "h:${group.key}", contentType = "header") { header() }
        }
        rows.forEachIndexed { row, badges ->
            if (index == 0 && row == 0) return@forEachIndexed
            item(key = "r:${group.key}:$row:$columns", contentType = "row$columns") {
                BadgeRow(badges, state.compact, columns, spacing, numbers, onAction)
            }
        }
    }
}

@Composable
private fun BadgeRow(
    badges: List<MeetupBadge>,
    compact: Boolean,
    columns: Int,
    spacing: Dp,
    numbers: Map<MeetupBadge, Int>,
    onAction: (WalletAction) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = spacing),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        badges.forEach { badge ->
            val number = numbers[badge] ?: 0
            if (compact) {
                CompactBadgeCard(badge, number, { onAction(WalletAction.OpenDetails(badge)) }, Modifier.weight(1f))
            } else {
                NormalBadgeCard(badge, number, { onAction(WalletAction.OpenDetails(badge)) }, Modifier.weight(1f))
            }
        }
        repeat(columns - badges.size) { Spacer(Modifier.weight(1f)) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LibraryControls(state: WalletUiState, onAction: (WalletAction) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp)) {
        if (state.showSearch) {
            // The field owns its text synchronously (no IME desync through the StateFlow round
            // trip). It leaves the composition when the search disappears, and the ViewModel
            // clears the query at the same time, so a re-shown field starts empty.
            val query = rememberTextFieldState(state.query)
            val currentQuery by rememberUpdatedState(state.query)
            val currentOnAction by rememberUpdatedState(onAction)
            LaunchedEffect(query) {
                snapshotFlow { query.text.toString() }.collect { text ->
                    if (text != currentQuery) currentOnAction(WalletAction.QueryChanged(text))
                }
            }
            OutlinedTextField(
                state = query,
                lineLimits = TextFieldLineLimits.SingleLine,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WalletTestTags.SEARCH),
                placeholder = { Text(stringResource(L10nR.string.walletSearchHint)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp)) },
                trailingIcon = if (query.text.isNotEmpty()) {
                    {
                        IconButton(onClick = { query.clearText() }) {
                            Icon(
                                Icons.Rounded.Clear,
                                contentDescription = stringResource(R.string.native_wallet_clear_search),
                                tint = MaterialTheme.extendedColors.textTertiary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                } else {
                    null
                },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = colors.surfaceContainer,
                    unfocusedContainerColor = colors.surfaceContainer,
                    unfocusedBorderColor = colors.outlineVariant,
                    focusedBorderColor = colors.primary,
                ),
            )
            Spacer(Modifier.height(10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            GroupToggle(
                label = stringResource(L10nR.string.walletGroupMeetup),
                icon = Icons.Rounded.Place,
                checked = state.groupByMeetup,
                leading = true,
                onClick = { onAction(WalletAction.SetGroupByMeetup(true)) },
            )
            GroupToggle(
                label = stringResource(L10nR.string.walletGroupYear),
                icon = Icons.Rounded.Event,
                checked = !state.groupByMeetup,
                leading = false,
                onClick = { onAction(WalletAction.SetGroupByMeetup(false)) },
            )
        }
    }
}

/** One half of the connected "Nach Meetup" / "Nach Jahr" button group. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GroupToggle(label: String, icon: ImageVector, checked: Boolean, leading: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ToggleButton(
        checked = checked,
        onCheckedChange = { onClick() },
        modifier = Modifier.heightIn(min = 48.dp),
        shapes = if (leading) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
        colors = ToggleButtonDefaults.colors(
            containerColor = colors.surfaceContainer,
            contentColor = colors.onSurfaceVariant,
            checkedContainerColor = colors.primary.copy(alpha = 0.16f),
            checkedContentColor = colors.primary,
        ),
        border = if (checked) BorderStroke(1.dp, colors.primary.copy(alpha = 0.6f)) else BorderStroke(0.5.dp, colors.outlineVariant),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.5.sp, fontWeight = if (checked) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun NoResults() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(32.dp)
            .testTag(WalletTestTags.NO_RESULTS),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.SearchOff, contentDescription = null, tint = MaterialTheme.extendedColors.textTertiary, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(L10nR.string.walletNoResults),
            color = MaterialTheme.extendedColors.textTertiary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(name = "Wallet", widthDp = 380, heightDp = 820)
@Composable
private fun WalletScreenPreview() {
    EinundzwanzigTheme {
        WalletScreen(state = PreviewData.walletState(), onAction = {})
    }
}

@Preview(name = "Wallet, by year compact", widthDp = 380, heightDp = 820)
@Composable
private fun WalletScreenYearPreview() {
    EinundzwanzigTheme {
        WalletScreen(state = PreviewData.walletState(groupByMeetup = false, compact = true), onAction = {})
    }
}

@Preview(name = "Wallet, empty", widthDp = 380, heightDp = 720)
@Composable
private fun WalletScreenEmptyPreview() {
    EinundzwanzigTheme {
        WalletScreen(state = WalletUiState(loaded = true), onAction = {})
    }
}
