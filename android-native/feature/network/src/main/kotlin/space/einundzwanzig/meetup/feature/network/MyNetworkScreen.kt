package space.einundzwanzig.meetup.feature.network

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.VerifyPerson
import space.einundzwanzig.meetup.core.ui.component.AppLargeTopBar
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarDurations
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.core.ui.component.showAppSnackbar
import space.einundzwanzig.meetup.core.ui.nostr.NpubFormat
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.core.ui.util.SafeUrlLauncher
import space.einundzwanzig.meetup.data.socialgraph.coattendance.MyNetwork
import space.einundzwanzig.meetup.data.socialgraph.coattendance.NetworkContact
import space.einundzwanzig.meetup.feature.network.ui.ContactRow
import space.einundzwanzig.meetup.feature.network.ui.FailedBanner
import space.einundzwanzig.meetup.feature.network.ui.FilterRow
import space.einundzwanzig.meetup.feature.network.ui.GraphCard
import space.einundzwanzig.meetup.feature.network.ui.HowItWorksRow
import space.einundzwanzig.meetup.feature.network.ui.InfoSheet
import space.einundzwanzig.meetup.feature.network.ui.NetworkInfoBox
import space.einundzwanzig.meetup.feature.network.ui.NetworkSearchField
import space.einundzwanzig.meetup.feature.network.ui.NoResults
import space.einundzwanzig.meetup.feature.network.ui.PersonDetailSheet
import space.einundzwanzig.meetup.feature.network.ui.ReachCard
import space.einundzwanzig.meetup.feature.network.ui.SavedStateHint
import space.einundzwanzig.meetup.feature.network.ui.SectionHeader
import space.einundzwanzig.meetup.feature.network.ui.ShowAllRow
import java.time.Instant
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Stateful "Meine Vernetzung" ([space.einundzwanzig.meetup.core.navigation.MyTrustNetwork], spec 05 §2.1). */
@Composable
internal fun MyNetworkRoute(viewModel: MyNetworkViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    LifecycleStartEffect(viewModel) {
        viewModel.onStart()
        onStopOrDispose { }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { ev ->
            when (ev) {
                is MyNetworkEvent.RetryResult -> snackbar.showAppSnackbar(
                    message = resources.getString(L10nR.string.mnRetryResult, ev.fixed, ev.total),
                    tone = if (ev.fixed > 0) Tone.Success else Tone.Error,
                    durationMillis = AppSnackbarDurations.Medium,
                )
            }
        }
    }
    MyNetworkScreen(
        state = state,
        onBack = navigator::back,
        snackbarHostState = snackbar,
        onAction = { action ->
            when (action) {
                MyNetworkAction.CheckPerson -> navigator.navigate(VerifyPerson())
                is MyNetworkAction.OpenInNostr -> openInNostr(context, action.npub)
                else -> viewModel.onAction(action)
            }
        },
    )
}

/** Opens `nostr:<npub>` in an installed app, falling back to njump.me (Dart `_openInNostr`). */
internal fun openInNostr(context: Context, npub: String) {
    val intent = Intent(Intent.ACTION_VIEW, "nostr:$npub".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        SafeUrlLauncher.open(context, NpubFormat.profileUrl(npub))
    } catch (_: SecurityException) {
        SafeUrlLauncher.open(context, NpubFormat.profileUrl(npub))
    }
}

/** Stateless "Meine Vernetzung". */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MyNetworkScreen(
    state: MyNetworkUiState,
    onAction: (MyNetworkAction) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppLargeTopBar(
                title = stringResource(L10nR.string.mnTitle),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { onAction(MyNetworkAction.OpenInfo) }) {
                        Icon(Icons.Outlined.Info, contentDescription = stringResource(L10nR.string.mnHowItWorks))
                    }
                    IconButton(onClick = { onAction(MyNetworkAction.CheckPerson) }) {
                        Icon(Icons.Rounded.PersonSearch, contentDescription = stringResource(L10nR.string.mnCheckPerson))
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
    ) { padding ->
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { onAction(MyNetworkAction.Refresh) },
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            state = pullState,
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = pullState,
                    isRefreshing = state.refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
        ) {
            Crossfade(targetState = state.body, label = "body") { body ->
                when (body) {
                    MyNetworkUiState.Body.FirstLoad -> FirstLoadBody(state.doneDepth)
                    MyNetworkUiState.Body.Empty -> EmptyBody(state, onAction)
                    MyNetworkUiState.Body.Content -> state.network?.let { ContentBody(state, it, onAction) }
                }
            }
        }
    }

    if (state.infoSheetOpen) {
        InfoSheet(
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            onDismiss = { onAction(MyNetworkAction.CloseInfo) },
        )
    }
    // The sheet shows the network as it was when it opened (Dart `_openDetail(net)`); later build
    // stages only update the graph highlight behind it.
    val net = state.detailNetwork ?: state.network
    val highlight = state.highlight
    if (net != null && highlight != null) {
        PersonDetailSheet(
            net = net,
            npub = highlight,
            names = state.names,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            onDismiss = { onAction(MyNetworkAction.CloseDetail) },
            onOpenInNostr = { onAction(MyNetworkAction.OpenInNostr(it)) },
        )
    }
}

@Composable
private fun FirstLoadBody(doneDepth: Int) {
    LazyColumn(
        Modifier
            .fillMaxSize()
            .testTag("firstLoad"),
        contentPadding = PaddingValues(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item { Spacer(Modifier.height(120.dp)) }
        item { AppLoadingIndicator(size = 64.dp) }
        item { Spacer(Modifier.height(18.dp)) }
        item {
            Text(
                if (doneDepth == 0) {
                    stringResource(L10nR.string.mnLoading)
                } else {
                    stringResource(L10nR.string.mnUpdating, NetworkPresentation.updatingDegree(doneDepth))
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptyBody(state: MyNetworkUiState, onAction: (MyNetworkAction) -> Unit) {
    LazyColumn(
        Modifier
            .fillMaxSize()
            .testTag("empty"),
        contentPadding = PaddingValues(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.updateFailed) {
            item {
                SavedStateHint()
                Spacer(Modifier.height(14.dp))
            }
        }
        if (state.failedCount > 0) {
            item {
                FailedBanner(state.failedCount, state.retrying, onRetry = { onAction(MyNetworkAction.Retry) })
                Spacer(Modifier.height(16.dp))
            }
        }
        item {
            Spacer(Modifier.height(60.dp))
            Box(
                Modifier
                    .size(96.dp)
                    .clip(MaterialShapes.Cookie9Sided.toShape())
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Hub,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.extendedColors.textTertiary,
                )
            }
            Spacer(Modifier.height(20.dp))
        }
        item {
            Text(
                stringResource(L10nR.string.mnEmpty),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(L10nR.string.mnEmptySub),
                fontSize = 13.sp,
                lineHeight = 19.5.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
        item { NetworkInfoBox(stringResource(L10nR.string.mnEventNote), Icons.Outlined.Info) }
    }
}

@Composable
private fun rememberLabels(): NetworkPresentation.Labels {
    val resources = LocalResources.current
    val you = stringResource(L10nR.string.mnYou)
    val and = stringResource(L10nR.string.mnAnd)
    val one = stringResource(L10nR.string.mnOneSharedMeetup)
    return remember(resources, you, and, one) {
        object : NetworkPresentation.Labels {
            override val you = you
            override val and = and
            override val oneSharedMeetup = one
            override fun sharedMeetups(count: Int) = resources.getString(L10nR.string.mnSharedMeetups, count.toString())
            override fun lastAt(place: String) = resources.getString(L10nR.string.mnLastAt, place)
            override fun via(names: String) = resources.getString(L10nR.string.mnVia, names)
        }
    }
}

@Composable
private fun ContentBody(state: MyNetworkUiState, net: MyNetwork, onAction: (MyNetworkAction) -> Unit) {
    val labels = rememberLabels()
    val sections = remember(net, state.names, state.filter, state.query, state.expanded) {
        NetworkPresentation.sections(net, state.names, state.filter, state.query, state.expanded)
    }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .testTag("content"),
        contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        if (state.failedCount > 0) {
            item(key = "banner") {
                FailedBanner(
                    state.failedCount,
                    state.retrying,
                    onRetry = { onAction(MyNetworkAction.Retry) },
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
        }
        item(key = "reach") {
            ReachCard(net, state.updating, state.doneDepth, state.updateFailed, state.standTime)
            Spacer(Modifier.height(16.dp))
        }
        item(key = "graph") {
            GraphCard(
                net = net,
                names = state.names,
                highlight = state.highlight,
                onNodeTap = { onAction(MyNetworkAction.OpenDetail(it)) },
            )
            Spacer(Modifier.height(16.dp))
        }
        item(key = "filter") {
            FilterRow(net, state.filter, onSelect = { onAction(MyNetworkAction.SetFilter(it)) })
            Spacer(Modifier.height(10.dp))
        }
        item(key = "search") {
            NetworkSearchField(state.query, onQuery = { onAction(MyNetworkAction.SetQuery(it)) })
            Spacer(Modifier.height(6.dp))
        }
        if (sections.isEmpty()) {
            item(key = "none") { NoResults(state.query) }
        }
        sections.forEach { section -> sectionItems(section, net, state.names, labels, onAction) }
        item(key = "how") {
            Spacer(Modifier.height(18.dp))
            HowItWorksRow(onClick = { onAction(MyNetworkAction.OpenInfo) })
        }
    }
}

private fun LazyListScope.sectionItems(
    section: NetworkPresentation.Section,
    net: MyNetwork,
    names: Map<String, String>,
    labels: NetworkPresentation.Labels,
    onAction: (MyNetworkAction) -> Unit,
) {
    val d = section.degree
    item(key = "h$d") { SectionHeader(d, section.total) }
    itemsIndexed(section.shown, key = { _, c: NetworkContact -> "c$d:${c.npub}" }) { i, c ->
        ContactRow(
            contact = c,
            name = names[c.npub],
            detail = NetworkPresentation.detailLine(net, c, names, labels),
            first = i == 0,
            last = i == section.shown.lastIndex && !section.hasMore,
            onClick = { onAction(MyNetworkAction.OpenDetail(c.npub)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (section.hasMore) {
        item(key = "more$d") { ShowAllRow(d, section.total, onClick = { onAction(MyNetworkAction.Expand(d)) }) }
    }
}

// ------------------------------------------------------------------ previews

internal object PreviewNetworks {
    const val ME = "npub1me000000000000000000000000000000000000000000000000000000"
    private fun np(i: Int) = "npub1contact${i.toString().padStart(4, '0')}qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"

    fun sample(): MyNetwork {
        val d1 = (1..5).map { i ->
            NetworkContact(np(i), 1, setOf("wuerzburg-2026-09-${(10 + i)}@abc", "berlin-2026-08-0$i@abc"), emptySet(), ME)
        }
        val d2 = (6..12).map { i -> NetworkContact(np(i), 2, emptySet(), setOf(np(1 + i % 5)), np(1 + i % 5)) }
        val d3 = (13..16).map { i -> NetworkContact(np(i), 3, emptySet(), setOf(np(1 + i % 5)), np(6 + i % 7)) }
        val meetups = buildMap {
            put(ME, setOf("wuerzburg-2026-09-11@abc", "berlin-2026-08-01@abc"))
            d1.forEach { put(it.npub, it.sharedMeetupsWithMe) }
        }
        return MyNetwork(ME, linkedMapOf(1 to d1, 2 to d2, 3 to d3), 4, Instant.parse("2026-10-21T08:15:00Z"), meetupsOf = meetups)
    }

    val names = mapOf(np(1) to "Anna", np(2) to "Ben", np(6) to "Carla")
}

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun MyNetworkContentPreview() {
    EinundzwanzigTheme {
        MyNetworkScreen(
            state = MyNetworkUiState(
                loading = false,
                network = PreviewNetworks.sample(),
                failedCount = 2,
                standTime = "10:15",
                names = PreviewNetworks.names,
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MyNetworkEmptyPreview() {
    EinundzwanzigTheme {
        MyNetworkScreen(state = MyNetworkUiState(loading = false, updateFailed = true), onAction = {}, onBack = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun MyNetworkFirstLoadPreview() {
    EinundzwanzigTheme {
        MyNetworkScreen(state = MyNetworkUiState(loading = true, updating = true, doneDepth = 1), onAction = {}, onBack = {})
    }
}
