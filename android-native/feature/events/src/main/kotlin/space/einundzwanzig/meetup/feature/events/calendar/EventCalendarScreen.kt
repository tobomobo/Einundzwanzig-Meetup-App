package space.einundzwanzig.meetup.feature.events.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import space.einundzwanzig.meetup.core.navigation.EventCalendar
import space.einundzwanzig.meetup.core.navigation.EventChat
import space.einundzwanzig.meetup.core.navigation.EventEditor
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.NavResultEffect
import space.einundzwanzig.meetup.core.navigation.NavResults
import space.einundzwanzig.meetup.core.navigation.RollingQr
import space.einundzwanzig.meetup.core.ui.component.AppLargeTopBar
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.calendar.format.displayTitle
import space.einundzwanzig.meetup.data.calendar.format.displayLocation
import space.einundzwanzig.meetup.data.calendar.model.CalendarItem
import space.einundzwanzig.meetup.data.calendar.model.CalendarTypeFilter
import space.einundzwanzig.meetup.feature.events.R
import space.einundzwanzig.meetup.feature.events.common.EventDetailsSheet
import space.einundzwanzig.meetup.feature.events.common.MessageAction
import space.einundzwanzig.meetup.feature.events.common.MessageEffect
import space.einundzwanzig.meetup.feature.events.common.PlatformActions
import space.einundzwanzig.meetup.feature.events.common.TypeDot
import space.einundzwanzig.meetup.feature.events.common.WeekdayNames
import space.einundzwanzig.meetup.feature.events.common.monthName
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Stateful entry of [EventCalendar]. */
@Composable
fun EventCalendarRoute(
    key: EventCalendar,
    viewModel: EventCalendarViewModel = hiltViewModel<EventCalendarViewModel, EventCalendarViewModel.Factory>(
        key = key.toString(),
        creationCallback = { it.create(key) },
    ),
) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(viewModel.messages, snackbar) { action ->
        when (action) {
            MessageAction.OPEN_LOCATION_SETTINGS -> PlatformActions.openLocationSettings(context)
            MessageAction.OPEN_APP_SETTINGS -> PlatformActions.openAppSettings(context)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                EventCalendarEffect.OpenRollingQr -> navigator.navigate(RollingQr)
            }
        }
    }
    // Back from the rolling QR: the running session may have changed ("QR anzeigen").
    LifecycleResumeEffect(viewModel) {
        viewModel.reloadRunningSession()
        onPauseOrDispose { }
    }
    NavResultEffect(NavResults.EventPublished) { viewModel.load(force = true) }
    EventCalendarScreen(
        state = state,
        snackbarHostState = snackbar,
        crestFor = viewModel::crestFor,
        onAction = { action ->
            when (action) {
                EventCalendarAction.Back -> navigator.back()
                EventCalendarAction.AddEvent -> navigator.navigate(EventEditor)
                is EventCalendarAction.OpenChat -> {
                    viewModel.onAction(EventCalendarAction.CloseDetails)
                    navigator.navigate(EventChat(action.event.address, action.event.title))
                }
                is EventCalendarAction.OpenUrl -> navigator.openUrl(action.url)
                is EventCalendarAction.AddToCalendar -> {
                    val item = action.item
                    PlatformActions.addToCalendar(
                        context = context,
                        title = item.displayTitle(context.resources),
                        description = item.description,
                        location = item.displayLocation(context.resources),
                        begin = item.start,
                        end = item.end ?: item.start.plus(Duration.ofHours(2)),
                        allDay = item.allDay,
                    )
                }
                else -> viewModel.onAction(action)
            }
        },
    )
}

/** Test tags. */
internal object EventCalendarTags {
    const val LIST = "eventCalendar.list"
    const val LOADING = "eventCalendar.loading"
    const val FAB = "eventCalendar.fab"
    const val REFRESH = "eventCalendar.refresh"
    const val QUERY = "eventCalendar.query"
    const val CLEAR = "eventCalendar.clear"
    const val WORLDWIDE = "eventCalendar.worldwide"
    const val MONTH_HEADER = "eventCalendar.monthHeader"
    const val DAY_EMPTY = "eventCalendar.dayEmpty"
    const val RANGE_EMPTY = "eventCalendar.rangeEmpty"
    fun day(day: LocalDate) = "eventCalendar.day.$day"
    fun month(month: Int) = "eventCalendar.month.$month"
}

/** "Veranstaltungskalender" (spec 10 §2.3). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EventCalendarScreen(
    state: EventCalendarUiState,
    onAction: (EventCalendarAction) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    crestFor: (String) -> String = { "" },
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppLargeTopBar(
                title = stringResource(L10nR.string.calTitle),
                onBack = { onAction(EventCalendarAction.Back) },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(
                        onClick = { onAction(EventCalendarAction.Refresh) },
                        enabled = !state.loading,
                        modifier = Modifier.testTag(EventCalendarTags.REFRESH),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.native_events_refresh))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onAction(EventCalendarAction.AddEvent) },
                expanded = fabExpanded,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text(stringResource(L10nR.string.calAddEvent), fontWeight = FontWeight.Bold) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag(EventCalendarTags.FAB),
            )
        },
        // While the details sheet is open its own host shows the snackbars (it is a separate window).
        snackbarHost = { if (state.details == null) AppSnackbarHost(snackbarHostState) },
    ) { padding ->
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = { onAction(EventCalendarAction.Refresh) },
            state = pullState,
            modifier = Modifier.fillMaxSize().padding(padding),
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = pullState,
                    isRefreshing = false,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag(EventCalendarTags.LIST),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "switcher") { ViewSwitcher(state.view, onAction) }
                item(key = "legend") { Legend() }
                item(key = "filters") { FilterBar(state, onAction) }
                if (state.loading) {
                    item(key = "loading") {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 48.dp).testTag(EventCalendarTags.LOADING),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AppLoadingIndicator()
                            Spacer(Modifier.height(14.dp))
                            Text(stringResource(L10nR.string.calLoading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    when (state.view) {
                        CalendarView.MONTH -> monthView(state, crestFor, onAction)
                        CalendarView.YEAR -> yearView(state, onAction)
                        CalendarView.LIST -> listView(state, crestFor, onAction)
                    }
                }
            }
        }
    }
    state.details?.let { details ->
        EventDetailsSheet(
            onDismiss = { onAction(EventCalendarAction.CloseDetails) },
            skipPartiallyExpanded = false,
            snackbarHostState = snackbarHostState,
        ) {
            CalendarDetailsContent(details, state.zone, state.startingSession, state.cancelling, onAction)
        }
        if (state.confirmCancel) CancelEventDialog(details.item.title, onAction)
        state.helperEditor?.let { HelperEditorSheet(it, onAction) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ViewSwitcher(view: CalendarView, onAction: (EventCalendarAction) -> Unit) {
    val options = listOf(
        Triple(CalendarView.MONTH, L10nR.string.calViewMonth, Icons.Rounded.CalendarMonth),
        Triple(CalendarView.YEAR, L10nR.string.calViewYear, Icons.Rounded.GridView),
        Triple(CalendarView.LIST, L10nR.string.calViewList, Icons.Rounded.ViewAgenda),
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, (v, label, icon) ->
            ToggleButton(
                checked = view == v,
                onCheckedChange = { onAction(EventCalendarAction.SetView(v)) },
                modifier = Modifier.weight(1f).semantics { role = Role.Tab },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(ToggleButtonDefaults.IconSize))
                Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
                Text(stringResource(label))
            }
        }
    }
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendDot(MaterialTheme.colorScheme.primary, stringResource(L10nR.string.calLegendMeetup))
        LegendDot(MaterialTheme.extendedColors.nostr, stringResource(L10nR.string.calLegendEvent))
        LegendDot(MaterialTheme.extendedColors.course, stringResource(L10nR.string.calLegendCourse))
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TypeDot(color)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FilterBar(state: EventCalendarUiState, onAction: (EventCalendarAction) -> Unit) {
    val purple = MaterialTheme.extendedColors.nostr
    val types = listOf(
        Triple(CalendarTypeFilter.ALL, L10nR.string.calFilterAll, MaterialTheme.colorScheme.primary),
        Triple(CalendarTypeFilter.MEETUPS, L10nR.string.calFilterMeetups, MaterialTheme.colorScheme.primary),
        Triple(CalendarTypeFilter.EXTERNAL, L10nR.string.calFilterExternal, purple),
        Triple(CalendarTypeFilter.COURSES, L10nR.string.calFilterCourses, MaterialTheme.extendedColors.course),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp))
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            types.forEachIndexed { index, (type, label, color) ->
                ToggleButton(
                    checked = state.typeFilter == type,
                    onCheckedChange = { onAction(EventCalendarAction.SetType(type)) },
                    modifier = Modifier.weight(1f),
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        types.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    colors = ToggleButtonDefaults.colors(
                        checkedContainerColor = color,
                        checkedContentColor = MaterialTheme.colorScheme.surface,
                    ),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    Text(stringResource(label), fontSize = 12.sp, maxLines = 1)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = state.query,
                onValueChange = { onAction(EventCalendarAction.SetQuery(it)) },
                modifier = Modifier.weight(1f).testTag(EventCalendarTags.QUERY),
                singleLine = true,
                placeholder = { Text(stringResource(L10nR.string.calFilterLocation)) },
                leadingIcon = { Icon(Icons.Rounded.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            if (state.filterActive) {
                IconButton(
                    onClick = { onAction(EventCalendarAction.ClearFilter) },
                    modifier = Modifier.testTag(EventCalendarTags.CLEAR),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.native_events_clear_filter))
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Switch) { onAction(EventCalendarAction.ToggleWorldwide) }
                .testTag(EventCalendarTags.WORLDWIDE),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (state.worldwide) Icons.Rounded.Public else Icons.Rounded.Groups,
                contentDescription = null,
                tint = if (state.worldwide) purple else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(if (state.worldwide) L10nR.string.calWorldwide else L10nR.string.calCommunityOnly),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            Switch(
                checked = state.worldwide,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = purple),
            )
        }
        if (state.worldwide) {
            Text(
                stringResource(L10nR.string.calWorldwideHint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun LazyListScope.monthView(
    state: EventCalendarUiState,
    crestFor: (String) -> String,
    onAction: (EventCalendarAction) -> Unit,
) {
    item(key = "monthHeader") {
        PeriodHeader(
            title = "${monthName(state.focused.monthValue)} ${state.focused.year}",
            prevLabel = stringResource(R.string.native_events_prev_month),
            nextLabel = stringResource(R.string.native_events_next_month),
            onPrev = { onAction(EventCalendarAction.ShiftMonth(-1)) },
            onNext = { onAction(EventCalendarAction.ShiftMonth(1)) },
            modifier = Modifier.testTag(EventCalendarTags.MONTH_HEADER),
        )
    }
    item(key = "monthGrid") { MonthGrid(state, onAction) }
    item(key = "dayHeader") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text(
                "${state.selected.dayOfMonth}. ${monthName(state.selected.monthValue)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
            if (state.selected == state.today) {
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(L10nR.string.calToday),
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
    val dayItems = state.selectedItems
    if (dayItems.isEmpty()) {
        item(key = "dayEmpty") {
            Text(
                stringResource(L10nR.string.calNoEvents),
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag(EventCalendarTags.DAY_EMPTY),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        cards(dayItems, state.zone, showDate = false, crestFor, onAction, "day")
    }
    item(key = "source") { SourceFooter() }
}

private fun LazyListScope.cards(
    items: List<CalendarItem>,
    zone: ZoneId,
    showDate: Boolean,
    crestFor: (String) -> String,
    onAction: (EventCalendarAction) -> Unit,
    prefix: String,
) {
    itemsIndexedKeyed(items, prefix) { item ->
        CalendarEventCard(
            item = item,
            zone = zone,
            showDate = showDate,
            crestUrl = if (item.isMeetup) crestFor(item.title) else "",
            onClick = { onAction(EventCalendarAction.OpenDetails(item)) },
            modifier = Modifier.animateItem(),
        )
    }
}

private fun LazyListScope.itemsIndexedKeyed(
    items: List<CalendarItem>,
    prefix: String,
    content: @Composable androidx.compose.foundation.lazy.LazyItemScope.(CalendarItem) -> Unit,
) {
    // Keys must be unique: dates may share title and time, so the index is part of the key.
    items(items.size, key = { i -> "$prefix.$i.${items[i].title}.${items[i].start}" }) { i -> content(items[i]) }
}

@Composable
private fun SourceFooter() {
    Text(
        stringResource(L10nR.string.calSource),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        textAlign = TextAlign.Center,
        fontSize = 11.sp,
        color = MaterialTheme.extendedColors.textTertiary,
    )
}

@Composable
private fun PeriodHeader(
    title: String,
    prevLabel: String,
    nextLabel: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) { Icon(Icons.Rounded.ChevronLeft, contentDescription = prevLabel) }
        AnimatedContent(
            targetState = title,
            modifier = Modifier.weight(1f),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "periodTitle",
        ) { t ->
            Text(
                t,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }
        IconButton(onClick = onNext) { Icon(Icons.Rounded.ChevronRight, contentDescription = nextLabel) }
    }
}

@Composable
private fun MonthGrid(state: EventCalendarUiState, onAction: (EventCalendarAction) -> Unit) {
    val first = state.focused.atDay(1)
    val blanks = (first.dayOfWeek.value + 6) % 7
    val days = state.focused.lengthOfMonth()
    val cells = blanks + days
    val rows = (cells + 6) / 7
    // The grid bleeds GRID_BLEED into the list's 16dp side padding and the cells have no gaps
    // (the 2dp visual inset is inside each cell), so a day is >= 48dp wide even on 360dp phones.
    Column(
        Modifier.layout { measurable, constraints ->
            val bleed = GRID_BLEED.roundToPx()
            val width = constraints.maxWidth + 2 * bleed
            val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
            layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed, 0) }
        },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            WeekdayNames.forEach { wd ->
                Text(
                    stringResource(wd),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.extendedColors.textTertiary,
                )
            }
        }
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val index = r * 7 + c
                    val dayNumber = index - blanks + 1
                    if (index < blanks || dayNumber > days) {
                        Spacer(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val day = state.focused.atDay(dayNumber)
                        DayCell(
                            day = day,
                            selected = day == state.selected,
                            today = day == state.today,
                            dots = state.byDay[day].orEmpty().take(3).map { it.color() },
                            count = state.byDay[day].orEmpty().size,
                            onClick = { onAction(EventCalendarAction.SelectDay(day)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    selected: Boolean,
    today: Boolean,
    dots: List<Color>,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val orange = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(10.dp)
    val description = pluralStringResource(R.plurals.native_events_day_events, count, day.dayOfMonth.toString(), count)
    Box(
        modifier
            .aspectRatio(1f)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = description
            }
            .testTag(EventCalendarTags.day(day))
            .padding(2.dp)
            .then(
                when {
                    selected -> Modifier.background(orange, shape)
                    today -> Modifier.background(orange.copy(alpha = 0.12f), shape).border(1.dp, orange, shape)
                    else -> Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                day.dayOfMonth.toString(),
                fontWeight = if (selected || today) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
            if (dots.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 2.dp)) {
                    dots.forEach { c ->
                        Box(
                            Modifier.size(4.dp).background(
                                if (selected) MaterialTheme.colorScheme.onPrimary else c,
                                CircleShape,
                            ),
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.yearView(state: EventCalendarUiState, onAction: (EventCalendarAction) -> Unit) {
    item(key = "yearHeader") {
        PeriodHeader(
            title = state.focused.year.toString(),
            prevLabel = stringResource(R.string.native_events_prev_year),
            nextLabel = stringResource(R.string.native_events_next_year),
            onPrev = { onAction(EventCalendarAction.ShiftYear(-1)) },
            onNext = { onAction(EventCalendarAction.ShiftYear(1)) },
        )
    }
    item(key = "yearGrid") {
        val counts = state.yearCounts
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (r in 0 until 4) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (c in 0 until 3) {
                        val month = r * 3 + c + 1
                        MonthTile(month, counts[month] ?: 0, onAction, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthTile(month: Int, count: Int, onAction: (EventCalendarAction) -> Unit, modifier: Modifier = Modifier) {
    val orange = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(12.dp)
    val name = monthName(month)
    val description = pluralStringResource(R.plurals.native_events_month_events, count, name, count)
    Column(
        modifier
            .aspectRatio(1.1f)
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .border(
                if (count > 0) 1.dp else 0.5.dp,
                if (count > 0) orange.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant,
                shape,
            )
            .clickable(role = Role.Button) { onAction(EventCalendarAction.OpenMonth(month)) }
            .semantics { contentDescription = description }
            .testTag(EventCalendarTags.month(month))
            .padding(8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        if (count > 0) {
            Text(
                count.toString(),
                modifier = Modifier
                    .background(orange.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                color = orange,
                fontWeight = FontWeight.Bold,
            )
        } else {
            Text(stringResource(R.string.native_events_dash), color = MaterialTheme.extendedColors.textTertiary)
        }
    }
}

private fun LazyListScope.listView(
    state: EventCalendarUiState,
    crestFor: (String) -> String,
    onAction: (EventCalendarAction) -> Unit,
) {
    val upcoming = state.upcoming
    if (upcoming.isEmpty()) {
        item(key = "rangeEmpty") {
            Column(
                Modifier.fillMaxWidth().padding(top = 100.dp).testTag(EventCalendarTags.RANGE_EMPTY),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Rounded.EventBusy,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.extendedColors.textTertiary,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(L10nR.string.calNoEventsRange),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    } else {
        cards(upcoming, state.zone, showDate = true, crestFor, onAction, "list")
        item(key = "source") { SourceFooter() }
    }
}

@Preview
@Composable
private fun EventCalendarScreenPreview() {
    val zone = ZoneId.of("Europe/Berlin")
    val now = Instant.parse("2026-10-21T10:00:00Z")
    val item = CalendarItem(
        title = "Bitcoin Konferenz",
        description = "Vorträge und Workshops",
        location = "Wien",
        start = now.plus(Duration.ofHours(8)),
    )
    val items = listOf(item)
    EinundzwanzigTheme {
        EventCalendarScreen(
            state = EventCalendarUiState(
                zone = zone,
                today = LocalDate.of(2026, 10, 21),
                focused = YearMonth.of(2026, 10),
                selected = LocalDate.of(2026, 10, 21),
                now = now,
                loading = false,
                items = items,
                filtered = items,
                byDay = mapOf(LocalDate.of(2026, 10, 21) to items),
            ),
            onAction = {},
        )
    }
}

/** How far the month grid extends into the list's horizontal content padding. */
private val GRID_BLEED = 8.dp
