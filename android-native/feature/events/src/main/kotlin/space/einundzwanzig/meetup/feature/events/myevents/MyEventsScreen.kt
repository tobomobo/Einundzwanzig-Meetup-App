package space.einundzwanzig.meetup.feature.events.myevents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.EventNote
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.navigation.EventChat
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.MeetupChat
import space.einundzwanzig.meetup.core.ui.component.AppLargeTopBar
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.ConfirmDialog
import space.einundzwanzig.meetup.core.ui.component.FullScreenLoading
import space.einundzwanzig.meetup.core.ui.component.Tile
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.calendar.format.CalendarFormat
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate
import space.einundzwanzig.meetup.data.calendar.model.MyMeetupDate
import space.einundzwanzig.meetup.data.calendar.model.NostrCalendarEvent
import space.einundzwanzig.meetup.feature.events.R
import space.einundzwanzig.meetup.feature.events.common.MessageEffect
import space.einundzwanzig.meetup.feature.events.common.SectionLabel
import java.time.Instant
import java.time.ZoneId

/** Stateful entry of `MyEvents`. */
@Composable
fun MyEventsRoute(viewModel: MyEventsViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(viewModel.messages, snackbar)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is MyEventsEffect.OpenMeetupRoom -> navigator.navigate(MeetupChat(effect.roomId, effect.roomName))
            }
        }
    }
    // Back from an event chat: the read marks changed.
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshUnread()
        onPauseOrDispose { }
    }
    MyEventsScreen(
        state = state,
        snackbarHostState = snackbar,
        onAction = { action ->
            when (action) {
                MyEventsAction.Back -> navigator.back()
                is MyEventsAction.OpenEventChat -> navigator.navigate(EventChat(action.event.address, action.event.title))
                else -> viewModel.onAction(action)
            }
        },
    )
}

/** Test tags. */
internal object MyEventsTags {
    const val LOADING = "myEvents.loading"
    const val EMPTY = "myEvents.empty"
    const val LIST = "myEvents.list"
    fun event(address: String) = "myEvents.event.$address"
    fun meetupChat(index: Int) = "myEvents.meetupChat.$index"
}

/** "Meine Termine" (spec 10 §2.5). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MyEventsScreen(
    state: MyEventsUiState,
    onAction: (MyEventsAction) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppLargeTopBar(
                title = stringResource(L10nR.string.eventChatsTitle),
                onBack = { onAction(MyEventsAction.Back) },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.loading) {
            FullScreenLoading(Modifier.padding(padding).testTag(MyEventsTags.LOADING))
            return@Scaffold
        }
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { onAction(MyEventsAction.Refresh) },
            state = pullState,
            modifier = Modifier.fillMaxSize().padding(padding),
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = pullState,
                    isRefreshing = state.refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
        ) {
            if (state.isEmpty) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val h = maxHeight
                    LazyColumn(Modifier.fillMaxSize().testTag(MyEventsTags.EMPTY)) {
                        item {
                            Box(Modifier.fillMaxWidth().height(h * 0.6f).padding(32.dp), contentAlignment = Alignment.Center) {
                                EmptyContent()
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag(MyEventsTags.LIST),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (state.meetupDates.isNotEmpty()) {
                        item(key = "meetupsLabel") { SectionLabel(stringResource(L10nR.string.eventChatsMeetups)) }
                        items(state.meetupDates.size, key = { "m$it" }) { i ->
                            MeetupDateCard(i, state.meetupDates[i], state.zone, onAction)
                        }
                    }
                    if (state.events.isNotEmpty()) {
                        item(key = "eventsLabel") {
                            SectionLabel(
                                stringResource(L10nR.string.eventChatsEvents),
                                Modifier.padding(top = if (state.meetupDates.isNotEmpty()) 8.dp else 0.dp),
                            )
                        }
                        items(state.events, key = { "e" + it.address }) { event ->
                            EventCard(event, state.unread[event.address] ?: 0, state.now, state.zone, onAction, Modifier.animateItem())
                        }
                    }
                }
            }
        }
    }
    state.withdrawTarget?.let { event ->
        ConfirmDialog(
            title = stringResource(L10nR.string.rsvpWithdrawTitle),
            text = stringResource(L10nR.string.rsvpWithdrawBody, event.title),
            confirmLabel = stringResource(L10nR.string.rsvpWithdrawConfirm),
            onConfirm = { onAction(MyEventsAction.ConfirmWithdraw) },
            onDismiss = { onAction(MyEventsAction.DismissWithdraw) },
            destructive = true,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptyContent() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp).background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                MaterialShapes.Cookie9Sided.toShape(),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.EventNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(L10nR.string.eventChatsEmpty),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MeetupDateCard(index: Int, date: MyMeetupDate, zone: ZoneId, onAction: (MyEventsAction) -> Unit) {
    val purple = MaterialTheme.extendedColors.nostr
    Tile(contentPadding = PaddingValues(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    date.label,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Event, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    val attendees = if (date.attendees >= 0) " · " + stringResource(L10nR.string.rsvpAttendees, date.attendees) else ""
                    Text(
                        CalendarFormat.myMeetupWhen(date.event.start, zone) + attendees,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FilledIconButton(
                onClick = { onAction(MyEventsAction.OpenMeetupChat(date)) },
                modifier = Modifier.size(48.dp).testTag(MyEventsTags.meetupChat(index)),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = purple.copy(alpha = 0.14f), contentColor = purple),
            ) {
                Icon(Icons.Rounded.Forum, contentDescription = stringResource(R.string.native_events_open_meetup_chat))
            }
        }
    }
}

@Composable
private fun EventCard(
    event: NostrCalendarEvent,
    unread: Int,
    now: Instant,
    zone: ZoneId,
    onAction: (MyEventsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val purple = MaterialTheme.extendedColors.nostr
    val orange = MaterialTheme.colorScheme.primary
    val dateColor = when (CalendarFormat.proximity(event.start, now)) {
        CalendarFormat.Proximity.NOW -> orange
        CalendarFormat.Proximity.SOON -> orange.copy(alpha = 0.8f)
        CalendarFormat.Proximity.LATER -> MaterialTheme.extendedColors.textTertiary
    }
    Tile(
        modifier = modifier.fillMaxWidth().testTag(MyEventsTags.event(event.address)),
        onClick = { onAction(MyEventsAction.OpenEventChat(event)) },
        onLongClick = { onAction(MyEventsAction.RequestWithdraw(event)) },
        borderColor = if (unread > 0) purple.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant,
        contentPadding = PaddingValues(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    event.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Event, contentDescription = null, tint = dateColor, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    val location = if (event.location.isNotEmpty()) " · ${event.location}" else ""
                    Text(
                        CalendarFormat.shortDate(event.day(zone)) + location,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val description = if (unread > 0) {
                stringResource(R.string.native_events_unread, CalendarFormat.unreadBadge(unread))
            } else {
                stringResource(L10nR.string.chatEventOpen)
            }
            IconButton(onClick = { onAction(MyEventsAction.OpenEventChat(event)) }) {
                Box {
                    Icon(
                        Icons.Rounded.Forum,
                        contentDescription = description,
                        tint = if (unread > 0) purple else MaterialTheme.extendedColors.textTertiary,
                    )
                    if (unread > 0) {
                        Text(
                            CalendarFormat.unreadBadge(unread),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 10.dp, y = (-8).dp)
                                .background(orange, CircleShape)
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun MyEventsPreview() {
    val zone = ZoneId.of("Europe/Berlin")
    val now = Instant.parse("2026-10-21T10:00:00Z")
    val event = NostrCalendarEvent(
        id = "1", pubkey = "a".repeat(64), dTag = "d", title = "Bitcoin Konferenz", description = "",
        location = "Wien", start = now.plusSeconds(86_400), end = null, allDay = false, kind = NostrCalendarEvent.KIND_TIME,
    )
    val date = MyMeetupDate(
        "107", "Einundzwanzig München",
        MeetupDate(title = "München", description = "", location = "", start = now.plusSeconds(3 * 86_400), url = ""), 7,
    )
    EinundzwanzigTheme {
        MyEventsScreen(
            state = MyEventsUiState(zone = zone, now = now, loading = false, events = listOf(event), meetupDates = listOf(date), unread = mapOf(event.address to 3)),
            onAction = {},
        )
    }
}
