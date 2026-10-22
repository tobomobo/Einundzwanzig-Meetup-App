package space.einundzwanzig.meetup.feature.meetups.portal

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.NavResultEffect
import space.einundzwanzig.meetup.core.navigation.NavResults
import space.einundzwanzig.meetup.core.navigation.PortalEventEditor
import space.einundzwanzig.meetup.core.ui.component.AppLargeTopBar
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.FullScreenLoading
import space.einundzwanzig.meetup.core.ui.component.MonoText
import space.einundzwanzig.meetup.core.ui.component.Tile
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.feature.meetups.common.CollectMessages
import space.einundzwanzig.meetup.feature.meetups.common.IconTile
import space.einundzwanzig.meetup.feature.meetups.common.SectionLabel
import space.einundzwanzig.meetup.core.l10n.R as L10nR

internal object PortalEventsTags {
    const val FAB = "portal_events_fab"
    fun row(index: Int) = "portal_event_row_$index"
}

/** Stateful "Termine verwalten" (Dart `ManageEventsScreen`, spec 09 §2.1). */
@Composable
fun PortalMeetupEventsRoute(
    meetupId: Int,
    meetupName: String,
    viewModel: PortalMeetupEventsViewModel = hiltViewModel<PortalMeetupEventsViewModel, PortalMeetupEventsViewModel.Factory>(
        key = "portal-events-$meetupId",
        creationCallback = { it.create(meetupId, meetupName) },
    ),
) {
    val navigator = LocalNavigator.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(viewModel.messages, snackbar)
    NavResultEffect(NavResults.PortalEventSaved) { created -> viewModel.onSaved(created) }
    PortalMeetupEventsScreen(state, snackbar) { action ->
        when (action) {
            PortalMeetupEventsAction.Back -> navigator.back()
            PortalMeetupEventsAction.Create -> navigator.navigate(PortalEventEditor(meetupId, meetupName))
            is PortalMeetupEventsAction.Edit -> navigator.navigate(
                PortalEventEditor(meetupId, meetupName, eventId = action.row.eventId, eventIndex = action.row.index),
            )
            PortalMeetupEventsAction.Refresh -> viewModel.onAction(action)
        }
    }
}

/** Stateless "Termine verwalten". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortalMeetupEventsScreen(
    state: PortalMeetupEventsUiState,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    onAction: (PortalMeetupEventsAction) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    val expanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        topBar = {
            AppLargeTopBar(
                title = state.meetupName,
                onBack = { onAction(PortalMeetupEventsAction.Back) },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onAction(PortalMeetupEventsAction.Create) },
                expanded = expanded,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text(stringResource(L10nR.string.portalNewEvent), fontWeight = FontWeight.Bold) },
                modifier = Modifier.testTag(PortalEventsTags.FAB),
            )
        },
    ) { padding ->
        if (state.loading) {
            FullScreenLoading(Modifier.padding(padding), message = stringResource(L10nR.string.portalLoadingEvents))
            return@Scaffold
        }
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { onAction(PortalMeetupEventsAction.Refresh) },
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "header") {
                    SectionLabel(stringResource(L10nR.string.portalExistingEvents), Modifier.padding(bottom = 4.dp))
                }
                if (state.rows.isEmpty()) {
                    item(key = "empty") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.Rounded.EventBusy,
                                contentDescription = null,
                                tint = MaterialTheme.extendedColors.textTertiary,
                                modifier = Modifier.size(40.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(L10nR.string.portalNoEvents),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(state.rows, key = { "${it.index}-${it.eventId}" }) { row ->
                        EventRow(row, Modifier.animateItem()) { onAction(PortalMeetupEventsAction.Edit(row)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(row: PortalEventRow, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val editLabel = stringResource(L10nR.string.portalEditEvent)
    Tile(
        modifier = modifier
            .fillMaxWidth()
            .testTag(PortalEventsTags.row(row.index)),
        onClick = onClick,
        onClickLabel = editLabel,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.Event)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                MonoText(row.whenText, fontWeight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                val location = row.location
                Text(
                    if (location.isNullOrEmpty()) stringResource(L10nR.string.portalTapToEdit) else location,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Rounded.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Preview
@Composable
private fun PortalMeetupEventsPreview() {
    EinundzwanzigTheme {
        PortalMeetupEventsScreen(
            PortalMeetupEventsUiState(
                meetupName = "Einundzwanzig Südniedersachsen",
                loading = false,
                rows = listOf(
                    PortalEventRow(3869, 0, "21.07.2026  18:00", "Dinx Diner"),
                    PortalEventRow(null, 1, "18.08.2026  19:00", null),
                ),
            ),
            SnackbarHostState(),
        ) {}
    }
}
