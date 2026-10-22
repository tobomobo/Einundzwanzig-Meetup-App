package space.einundzwanzig.meetup.feature.meetups.portal

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.PortalMeetupEvents
import space.einundzwanzig.meetup.core.ui.component.AppLargeTopBar
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.ConfirmDialog
import space.einundzwanzig.meetup.core.ui.component.FullScreenLoading
import space.einundzwanzig.meetup.core.ui.component.StatusChip
import space.einundzwanzig.meetup.core.ui.component.Tile
import space.einundzwanzig.meetup.core.ui.guide.FinishTourOnDispose
import space.einundzwanzig.meetup.core.ui.guide.GuideStep
import space.einundzwanzig.meetup.core.ui.guide.GuideTour
import space.einundzwanzig.meetup.core.ui.guide.StartTourEffect
import space.einundzwanzig.meetup.core.ui.guide.guideAnchor
import space.einundzwanzig.meetup.core.ui.nostr.NpubChip
import space.einundzwanzig.meetup.core.ui.nostr.NpubFormat
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.meetups.portal.PortalMeetup
import space.einundzwanzig.meetup.feature.meetups.common.CollectMessages
import space.einundzwanzig.meetup.feature.meetups.common.IconTile
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags and guide anchors of "Meine Meetups". */
internal object PortalMeetupsTags {
    const val TITLE_ANCHOR = "myMeetups.title"
    const val LOGIN = "portal_login"
    const val LIST = "portal_meetups_list"
    const val ADD_FIELD = "portal_add_leader_field"
    fun card(id: Int) = "portal_meetup_$id"
    fun manage(id: Int) = "portal_manage_$id"
    fun addLeader(id: Int) = "portal_add_leader_$id"
    fun removeLeader(meetupId: Int, index: Int) = "portal_remove_leader_${meetupId}_$index"
}

/** Single-step tour of "Meine Meetups" (Dart `MyMeetupsTour.steps()`, spec 09 §2.1). */
internal fun myMeetupsTourSteps(): List<GuideStep> = listOf(
    GuideStep(
        targetId = PortalMeetupsTags.TITLE_ANCHOR,
        title = L10nR.string.guideMyMeetupsListTitle,
        body = L10nR.string.guideMyMeetupsListBody,
        scrollIntoView = false,
        waitTimeoutMillis = 800,
    ),
)

/** Stateful "Meine Meetups" (spec 09 §2.1). */
@Composable
fun PortalMeetupsRoute(viewModel: PortalMeetupsViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(viewModel.messages, snackbar)
    StartTourEffect(GuideTour.MyMeetups, delayMillis = 700, steps = ::myMeetupsTourSteps)
    FinishTourOnDispose(GuideTour.MyMeetups)
    PortalMeetupsScreen(state, snackbar) { action ->
        when (action) {
            PortalMeetupsAction.Back -> navigator.back()
            is PortalMeetupsAction.ManageEvents ->
                navigator.navigate(PortalMeetupEvents(action.meetup.id, action.meetup.name))
            else -> viewModel.onAction(action)
        }
    }
}

/** Stateless "Meine Meetups". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortalMeetupsScreen(
    state: PortalMeetupsUiState,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    onAction: (PortalMeetupsAction) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        topBar = {
            AppLargeTopBar(
                title = stringResource(L10nR.string.portalTitle),
                titleModifier = Modifier.guideAnchor(PortalMeetupsTags.TITLE_ANCHOR),
                onBack = { onAction(PortalMeetupsAction.Back) },
                scrollBehavior = scrollBehavior,
                actions = {
                    if (state.connected) {
                        IconButton(onClick = { onAction(PortalMeetupsAction.Logout) }) {
                            Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = stringResource(L10nR.string.portalLogout))
                        }
                    }
                },
            )
        },
    ) { padding ->
        val phase = when {
            state.checking -> 0
            !state.connected -> 1
            state.loadingMeetups -> 2
            else -> 3
        }
        AnimatedContent(
            targetState = phase,
            modifier = Modifier.padding(padding).fillMaxSize(),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "portalPhase",
        ) { p ->
            when (p) {
                0 -> FullScreenLoading()
                1 -> NotConnected(state.loggingIn) { onAction(PortalMeetupsAction.Login) }
                2 -> FullScreenLoading(message = stringResource(L10nR.string.portalLoadingMeetups))
                else -> ConnectedList(state, onAction)
            }
        }
    }
    when (val dialog = state.dialog) {
        is PortalDialog.AddLeader -> AddLeaderDialog(
            onConfirm = { onAction(PortalMeetupsAction.ConfirmAddLeader(it)) },
            onDismiss = { onAction(PortalMeetupsAction.DismissDialog) },
        )
        is PortalDialog.RemoveLeader -> ConfirmDialog(
            title = stringResource(L10nR.string.ldRemove),
            text = stringResource(L10nR.string.ldRemoveConfirm),
            confirmLabel = stringResource(L10nR.string.ldRemove),
            dismissLabel = stringResource(L10nR.string.resetCancel),
            destructive = true,
            onConfirm = { onAction(PortalMeetupsAction.ConfirmRemoveLeader) },
            onDismiss = { onAction(PortalMeetupsAction.DismissDialog) },
        )
        null -> Unit
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NotConnected(loggingIn: Boolean, onLogin: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        IconTile(Icons.Rounded.Hub, size = 72.dp, iconSize = 34.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(L10nR.string.portalNotConnected),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(L10nR.string.portalConnectInfo),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onLogin,
            enabled = !loggingIn,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .testTag(PortalMeetupsTags.LOGIN),
        ) {
            if (loggingIn) {
                AppLoadingIndicator(size = 20.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(Icons.AutoMirrored.Rounded.Login, contentDescription = null)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(if (loggingIn) L10nR.string.portalConnecting else L10nR.string.portalConnect),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectedList(state: PortalMeetupsUiState, onAction: (PortalMeetupsAction) -> Unit) {
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = { onAction(PortalMeetupsAction.Refresh) },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .testTag(PortalMeetupsTags.LIST),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.cards.isEmpty()) {
                item(key = "empty") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp, bottom = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Rounded.EventBusy,
                            contentDescription = null,
                            tint = MaterialTheme.extendedColors.textTertiary,
                            modifier = Modifier.size(44.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(L10nR.string.portalNoMeetups),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(state.cards, key = { it.meetup.id }) { card ->
                    MeetupAdminCard(card, onAction, Modifier.animateItem())
                }
            }
            item(key = "footer") {
                Text(
                    stringResource(L10nR.string.portalSource),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.extendedColors.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun MeetupAdminCard(card: PortalMeetupCard, onAction: (PortalMeetupsAction) -> Unit, modifier: Modifier = Modifier) {
    val meetup = card.meetup
    Tile(
        modifier = modifier
            .fillMaxWidth()
            .testTag(PortalMeetupsTags.card(meetup.id)),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    meetup.name,
                    modifier = Modifier.weight(1f, fill = false),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meetup.isLeader) {
                    Spacer(Modifier.width(8.dp))
                    StatusChip(stringResource(L10nR.string.portalLeader), MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { onAction(PortalMeetupsAction.ManageEvents(meetup)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag(PortalMeetupsTags.manage(meetup.id)),
            ) {
                Icon(Icons.AutoMirrored.Rounded.EventNote, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(L10nR.string.portalManageEvents))
            }
            if (meetup.isLeader) LeadersSection(card, onAction)
        }
    }
}

@Composable
private fun LeadersSection(card: PortalMeetupCard, onAction: (PortalMeetupsAction) -> Unit) {
    val meetupId = card.meetup.id
    HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Shield, contentDescription = null, tint = MaterialTheme.extendedColors.community, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(L10nR.string.ldTitle), style = MaterialTheme.typography.titleMedium)
    }
    Spacer(Modifier.height(8.dp))
    if (card.leadersLoading) {
        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            AppLoadingIndicator(size = 28.dp)
        }
        return
    }
    card.leaders.forEachIndexed { index, leader ->
        LeaderRowItem(
            leader = leader,
            busy = card.busy,
            removeTag = PortalMeetupsTags.removeLeader(meetupId, index),
            onRemove = { onAction(PortalMeetupsAction.AskRemoveLeader(meetupId, leader)) },
        )
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { onAction(PortalMeetupsAction.AskAddLeader(meetupId)) },
        enabled = !card.busy,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.extendedColors.success),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(PortalMeetupsTags.addLeader(meetupId)),
    ) {
        Icon(Icons.Rounded.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(L10nR.string.ldAddButton))
    }
}

@Composable
private fun LeaderRowItem(leader: LeaderRow, busy: Boolean, removeTag: String, onRemove: () -> Unit) {
    val primaryText = when {
        leader.name.isNotEmpty() -> leader.name
        leader.npub.isNotEmpty() -> NpubFormat.shorten(leader.npub)
        else -> "?"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (leader.isCreator) Icons.Rounded.Star else Icons.Rounded.Person,
            contentDescription = null,
            tint = if (leader.isCreator) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(primaryText, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (leader.name.isNotEmpty() && leader.npub.isNotEmpty()) {
                NpubChip(leader.npub, showIcon = false)
            }
        }
        if (leader.isCreator) {
            StatusChip(stringResource(L10nR.string.ldCreator), MaterialTheme.colorScheme.primary)
        } else {
            IconButton(onClick = onRemove, enabled = !busy, modifier = Modifier.testTag(removeTag)) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(L10nR.string.ldRemove),
                    tint = if (busy) MaterialTheme.extendedColors.textTertiary else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AddLeaderDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.PersonAdd, contentDescription = null) },
        title = { Text(stringResource(L10nR.string.ldAdd)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(L10nR.string.ldAddHint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .testTag(PortalMeetupsTags.ADD_FIELD),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(stringResource(L10nR.string.ldAddDo)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(L10nR.string.resetCancel)) } },
    )
}

@Preview
@Composable
private fun PortalMeetupsPreview() {
    EinundzwanzigTheme {
        PortalMeetupsScreen(
            state = PortalMeetupsUiState(
                checking = false,
                connected = true,
                cards = listOf(
                    PortalMeetupCard(
                        PortalMeetup(107, "Einundzwanzig Südniedersachsen", "suedniedersachsen", isLeader = true),
                        leaders = listOf(
                            LeaderRow(1, "Satoshi", "npub1qv02xpsc3lhxxx5x7xswf88w3u7kykft9ea7t78tz7ywxf7mxs9qrxujnc", true),
                            LeaderRow(2, "Hal", "npub1qv02xpsc3lhxxx5x7xswf88w3u7kykft9ea7t78tz7ywxf7mxs9qrxujnc", false),
                        ),
                    ),
                    PortalMeetupCard(PortalMeetup(32, "Einundzwanzig Sachsen", "sachsen")),
                ),
            ),
            snackbarHostState = SnackbarHostState(),
        ) {}
    }
}

@Preview
@Composable
private fun PortalMeetupsLoginPreview() {
    EinundzwanzigTheme {
        PortalMeetupsScreen(PortalMeetupsUiState(checking = false), SnackbarHostState()) {}
    }
}
