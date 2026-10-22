package space.einundzwanzig.meetup.feature.events.meetups

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.MeetupCalendar
import space.einundzwanzig.meetup.core.ui.component.AppLargeTopBar
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.MonoText
import space.einundzwanzig.meetup.core.ui.component.Tile
import space.einundzwanzig.meetup.core.ui.security.SecretClipboard
import space.einundzwanzig.meetup.core.ui.share.ShareHelper
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.calendar.format.CalendarFormat
import space.einundzwanzig.meetup.data.calendar.model.MeetupDate
import space.einundzwanzig.meetup.data.calendar.portal.PortalRsvp
import space.einundzwanzig.meetup.data.meetups.portal.Attendee
import space.einundzwanzig.meetup.feature.events.R
import space.einundzwanzig.meetup.feature.events.common.CrestBox
import space.einundzwanzig.meetup.feature.events.common.EventDetailsSheet
import space.einundzwanzig.meetup.feature.events.common.InlineLoading
import space.einundzwanzig.meetup.feature.events.common.MessageEffect
import space.einundzwanzig.meetup.feature.events.common.PlatformActions
import space.einundzwanzig.meetup.feature.events.common.SheetActionButton
import java.time.Duration
import java.time.Instant

/** Stateful entry of [MeetupCalendar]. */
@Composable
fun MeetupCalendarRoute(
    key: MeetupCalendar,
    viewModel: MeetupCalendarViewModel = hiltViewModel<MeetupCalendarViewModel, MeetupCalendarViewModel.Factory>(
        key = key.toString(),
        creationCallback = { it.create(key) },
    ),
) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(viewModel.messages, snackbar)
    MeetupCalendarScreen(
        state = state,
        snackbarHostState = snackbar,
        onAction = { action ->
            when (action) {
                MeetupCalendarAction.Back -> navigator.back()
                is MeetupCalendarAction.OpenUrl -> navigator.openUrl(action.url)
                is MeetupCalendarAction.Share -> {
                    if (!ShareHelper.shareText(context, action.text)) {
                        SecretClipboard.copyPlain(context, action.text)
                        viewModel.onAction(MeetupCalendarAction.ShareCopied)
                    }
                }
                is MeetupCalendarAction.AddToCalendar -> PlatformActions.addToCalendar(
                    context = context,
                    title = action.date.title,
                    description = action.date.description,
                    location = action.date.location,
                    begin = action.date.start,
                    end = action.date.start.plus(Duration.ofHours(2)),
                    allDay = false,
                )
                else -> viewModel.onAction(action)
            }
        },
    )
}

/** Test tags. */
internal object MeetupCalendarTags {
    const val SEARCH = "meetupCalendar.search"
    const val LIST = "meetupCalendar.list"
    const val LOADING = "meetupCalendar.loading"
    const val EMPTY = "meetupCalendar.empty"
}

/** "MEETUP TERMINE" (spec 10 §2.2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetupCalendarScreen(
    state: MeetupCalendarUiState,
    onAction: (MeetupCalendarAction) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppLargeTopBar(
                title = stringResource(L10nR.string.calendarTitle),
                onBack = { onAction(MeetupCalendarAction.Back) },
                scrollBehavior = scrollBehavior,
            )
        },
        // While the details sheet is open its own host shows the snackbars (it is a separate window).
        snackbarHost = { if (state.details == null) AppSnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TextField(
                value = state.query,
                onValueChange = { onAction(MeetupCalendarAction.Search(it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .testTag(MeetupCalendarTags.SEARCH),
                singleLine = true,
                placeholder = { Text(stringResource(L10nR.string.calendarSearch)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
            )
            when {
                state.loading -> Box(Modifier.fillMaxSize().testTag(MeetupCalendarTags.LOADING), contentAlignment = Alignment.Center) {
                    AppLoadingIndicator()
                }
                state.rows.isEmpty() -> Box(Modifier.fillMaxSize().testTag(MeetupCalendarTags.EMPTY), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(L10nR.string.calendarNoEvents),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag(MeetupCalendarTags.LIST),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.rows, key = { it.index }) { row ->
                        MeetupDateCard(row, onAction, Modifier.animateItem())
                    }
                }
            }
        }
    }
    state.details?.let { details ->
        EventDetailsSheet(
            onDismiss = { onAction(MeetupCalendarAction.CloseDetails) },
            snackbarHostState = snackbarHostState,
        ) {
            MeetupDateSheetContent(details, onAction)
        }
    }
}

@Composable
private fun MeetupDateCard(row: MeetupDateRow, onAction: (MeetupCalendarAction) -> Unit, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Tile(
        modifier = modifier.fillMaxWidth().animateContentSize(spring()),
        onClick = { onAction(MeetupCalendarAction.OpenDetails(row.index)) },
        contentPadding = PaddingValues(12.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DateBox(row.dayText, row.monthText)
                Spacer(Modifier.width(10.dp))
                CrestBox(row.crestUrl, size = 42.dp, radius = 9.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        row.date.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.native_events_time_suffix, row.timeText),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (row.date.location.isNotEmpty()) {
                        Text(
                            row.date.location,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.extendedColors.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (row.favKey != null) {
                    IconButton(onClick = { onAction(MeetupCalendarAction.ToggleFavorite(row.favKey)) }) {
                        Icon(
                            if (row.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                            contentDescription = stringResource(
                                if (row.isFavorite) R.string.native_events_favorite_remove else R.string.native_events_favorite_add,
                            ),
                            tint = if (row.isFavorite) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.extendedColors.textTertiary)
            }
            row.rsvp?.let { RsvpRow(it, onAction) }
        }
    }
}

@Composable
private fun DateBox(day: String, month: String) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .width(48.dp)
            .background(primary.copy(alpha = 0.10f), shape)
            .border(1.dp, primary.copy(alpha = 0.30f), shape)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(day, color = primary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(month, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RsvpRow(rsvp: RsvpRowState, onAction: (MeetupCalendarAction) -> Unit) {
    val success = MaterialTheme.extendedColors.success
    val error = MaterialTheme.colorScheme.error
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (rsvp.count >= 0) {
                val countText = "${rsvp.count} ${stringResource(L10nR.string.rsvpCount)}"
                val expandable = rsvp.count > 0
                Row(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .then(
                            if (expandable) {
                                Modifier.clickable(
                                    role = Role.Button,
                                    onClickLabel = stringResource(
                                        if (rsvp.expanded) R.string.native_events_hide_attendees else R.string.native_events_show_attendees,
                                    ),
                                ) { onAction(MeetupCalendarAction.ToggleAttendees(rsvp.eventId)) }
                            } else {
                                Modifier
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(countText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (expandable) {
                        Icon(
                            if (rsvp.expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (rsvp.going) {
                Text(stringResource(L10nR.string.rsvpYouGo), color = success, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { onAction(MeetupCalendarAction.Rsvp(rsvp.eventId, PortalRsvp.NONE)) },
                    enabled = !rsvp.busy,
                    border = BorderStroke(1.dp, error.copy(alpha = 0.6f)),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    if (rsvp.busy) InlineLoading(16.dp, error) else Text(stringResource(L10nR.string.rsvpCancel), color = error, fontSize = 12.sp)
                }
            } else {
                OutlinedButton(
                    onClick = { onAction(MeetupCalendarAction.Rsvp(rsvp.eventId, PortalRsvp.ATTENDING)) },
                    enabled = !rsvp.busy,
                    border = BorderStroke(1.dp, success.copy(alpha = 0.7f)),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                ) {
                    if (rsvp.busy) InlineLoading(16.dp, success) else Text(stringResource(L10nR.string.rsvpGoing), color = success, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
        AnimatedVisibility(rsvp.expanded, enter = expandVertically(), exit = shrinkVertically()) {
            AttendeeList(rsvp.attendees)
        }
    }
}

@Composable
private fun AttendeeList(attendees: List<Attendee>?) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            attendees == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { InlineLoading(24.dp) }
            attendees.isEmpty() -> Text(
                stringResource(L10nR.string.rsvpNoNames),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> attendees.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(26.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(CalendarFormat.initial(a.name), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(a.name.ifEmpty { stringResource(L10nR.string.rsvpAnon) }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        if (a.npub.isNotEmpty()) {
                            MonoText(CalendarFormat.shortNpub(a.npub), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MeetupDateSheetContent(details: MeetupDateDetails, onAction: (MeetupCalendarAction) -> Unit) {
    val row = details.row
    val date = row.date
    Row(verticalAlignment = Alignment.CenterVertically) {
        CrestBox(row.crestUrl, size = 42.dp, radius = 9.dp)
        Spacer(Modifier.width(12.dp))
        Text(date.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Event, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(details.whenText, style = MaterialTheme.typography.bodyLarge)
    }
    if (date.location.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(date.location, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (date.description.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text(date.description, style = MaterialTheme.typography.bodyMedium)
    }
    row.rsvp?.let { rsvp ->
        Spacer(Modifier.height(14.dp))
        val countLine = buildString {
            append("${if (rsvp.count >= 0) rsvp.count else 0} ${stringResource(L10nR.string.rsvpCount)}")
            if (rsvp.might >= 0) append(" · ${rsvp.might} ${stringResource(L10nR.string.rsvpMaybe)}")
        }
        Text(countLine, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                Triple(PortalRsvp.ATTENDING, L10nR.string.rsvpImComing, Icons.Rounded.Check),
                Triple(PortalRsvp.MAYBE, L10nR.string.rsvpMaybe, Icons.AutoMirrored.Rounded.HelpOutline),
            ).forEach { (status, label, icon) ->
                val active = rsvp.status == status
                val busy = details.busyButton != null
                val spinning = details.busyButton == status
                SheetToggle(
                    label = stringResource(label),
                    icon = icon,
                    active = active,
                    busy = spinning,
                    enabled = !busy,
                    onClick = { onAction(MeetupCalendarAction.SheetRsvp(status)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    val shareText = CalendarFormat.shareText(date.title, details.whenText, date.location, date.url)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (date.url.isNotEmpty()) {
            SheetActionButton(Icons.Rounded.Link, L10nR.string.evOpenLink, { onAction(MeetupCalendarAction.OpenUrl(date.url)) })
        }
        SheetActionButton(Icons.Rounded.Share, L10nR.string.evShare, { onAction(MeetupCalendarAction.Share(shareText)) })
    }
    SheetActionButton(Icons.Rounded.EventAvailable, L10nR.string.evToCalendar, { onAction(MeetupCalendarAction.AddToCalendar(date)) })
}

@Composable
private fun SheetToggle(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val success = MaterialTheme.extendedColors.success
    val fg = if (active) success else MaterialTheme.colorScheme.onSurfaceVariant
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        border = BorderStroke(if (active) 1.2.dp else 0.5.dp, if (active) success else MaterialTheme.colorScheme.outlineVariant),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            containerColor = if (active) success.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent,
            disabledContainerColor = if (active) success.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent,
        ),
    ) {
        if (busy) InlineLoading(16.dp, fg) else Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = fg, fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold)
    }
}

@Preview
@Composable
private fun MeetupCalendarScreenPreview() {
    val date = MeetupDate(
        title = "Einundzwanzig München",
        description = "Stammtisch",
        location = "Augustiner, München",
        start = Instant.parse("2026-11-03T18:30:00Z"),
        url = "https://portal.einundzwanzig.space",
        portalEventId = 1,
    )
    EinundzwanzigTheme {
        MeetupCalendarScreen(
            state = MeetupCalendarUiState(
                loading = false,
                rows = listOf(
                    MeetupDateRow(
                        0, date, "03", "11", "19:30", "", "107", true,
                        RsvpRowState(1, 4, 1, true, "attending", false, true, emptyList()),
                    ),
                ),
            ),
            onAction = {},
        )
    }
}
