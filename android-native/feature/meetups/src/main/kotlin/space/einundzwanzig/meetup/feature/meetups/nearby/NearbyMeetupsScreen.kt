package space.einundzwanzig.meetup.feature.meetups.nearby

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.einundzwanzig.meetup.core.common.dart.dartRound
import space.einundzwanzig.meetup.core.map.LatLng
import space.einundzwanzig.meetup.core.map.LocationDot
import space.einundzwanzig.meetup.core.map.MapZoomControls
import space.einundzwanzig.meetup.core.map.MarkerAnchor
import space.einundzwanzig.meetup.core.map.MeetupPinMarker
import space.einundzwanzig.meetup.core.map.OsmMap
import space.einundzwanzig.meetup.core.map.OsmUrls
import space.einundzwanzig.meetup.core.map.OsmCameraState
import space.einundzwanzig.meetup.core.map.rememberOsmCameraState
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.MeetupDetails
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.AppTopBar
import space.einundzwanzig.meetup.core.ui.component.Tile
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.meetups.MeetupScreenRules
import space.einundzwanzig.meetup.data.meetups.geocoding.GeoPlace
import space.einundzwanzig.meetup.data.meetups.location.LocationStatus
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.data.meetups.nearby.DateMode
import space.einundzwanzig.meetup.data.meetups.nearby.MeetupEventInfo
import space.einundzwanzig.meetup.data.meetups.nearby.NearbyMeetup
import space.einundzwanzig.meetup.data.meetups.nearby.NearbyMeetupService
import space.einundzwanzig.meetup.data.meetups.nearby.SearchMode
import space.einundzwanzig.meetup.feature.meetups.R
import space.einundzwanzig.meetup.feature.meetups.common.BoundedDatePickerDialog
import space.einundzwanzig.meetup.feature.meetups.common.BoundedDateRangePickerDialog
import space.einundzwanzig.meetup.feature.meetups.common.IconTile
import space.einundzwanzig.meetup.feature.meetups.common.InfoBox
import space.einundzwanzig.meetup.feature.meetups.common.LinkFailure
import space.einundzwanzig.meetup.feature.meetups.common.MeetupFormats
import space.einundzwanzig.meetup.feature.meetups.common.rememberLinkOpener
import java.time.Instant
import java.time.ZoneId
import space.einundzwanzig.meetup.core.l10n.R as L10nR

internal object NearbyTags {
    const val MAP = "nearby_map"
    const val LIST = "nearby_list"
    const val RADIUS = "nearby_radius"
    const val PLACE_FIELD = "nearby_place_field"
    const val LOCATION_HINT = "nearby_location_hint"
    const val RESULTS_HEADER = "nearby_results_header"
    const val DATE_ANY = "nearby_date_any"
    const val DATE_DAY = "nearby_date_day"
    const val DATE_RANGE = "nearby_date_range"
    const val EMPTY = "nearby_empty"
    const val SEARCH_HINT = "nearby_search_hint"
    fun mode(mode: SearchMode) = "nearby_mode_${mode.name}"
    fun place(index: Int) = "nearby_place_$index"
    fun card(id: String) = "nearby_card_$id"
    fun details(id: String) = "nearby_details_$id"
}

/** Map height (Flutter 230 dp). */
private val MapHeight = 230.dp

/** Stateful "MEETUPS IN DER NÄHE" (spec 09 §2.2). */
@Composable
fun NearbyMeetupsRoute(viewModel: NearbyMeetupsViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val links = rememberLinkOpener(snackbar)
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    NearbyMeetupsScreen(state, snackbar) { action ->
        when (action) {
            NearbyAction.Back -> navigator.back()
            is NearbyAction.OpenDetails -> navigator.navigate(MeetupDetails(action.meetupId))
            is NearbyAction.OpenDirections -> {
                val m = action.nearby.meetup
                // Flutter: open only if resolvable, silent otherwise.
                links.open(OsmUrls.marker(m.lat, m.lng), LinkFailure.Silent)
            }
            else -> viewModel.onAction(action)
        }
    }
}

/** Stateless "MEETUPS IN DER NÄHE". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyMeetupsScreen(
    state: NearbyUiState,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    onAction: (NearbyAction) -> Unit,
) {
    var pickDay by rememberSaveable { mutableStateOf(false) }
    var pickRange by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        topBar = { AppTopBar(title = stringResource(L10nR.string.nbTitle), onBack = { onAction(NearbyAction.Back) }) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .fillMaxSize(),
        ) {
            NearbyMap(state, onAction)
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag(NearbyTags.LIST),
                contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "mode") { ModeToggle(state.mode) { onAction(NearbyAction.SetMode(it)) } }
                item(key = "controls") {
                    Controls(state, onAction, onPickDay = { pickDay = true }, onPickRange = { pickRange = true })
                }
                val center = state.center
                if (!state.loading && center != null) {
                    item(key = "header") {
                        Text(
                            stringResource(L10nR.string.nbResultsHeader, state.results.size),
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .testTag(NearbyTags.RESULTS_HEADER),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                when {
                    state.loading -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            AppLoadingIndicator()
                        }
                    }
                    state.mode == SearchMode.PLANNED && center == null -> item(key = "searchHint") {
                        InfoBox(Icons.Rounded.Search, stringResource(L10nR.string.nbSearchPlace), Modifier.testTag(NearbyTags.SEARCH_HINT))
                    }
                    state.results.isEmpty() -> item(key = "empty") {
                        InfoBox(
                            Icons.Rounded.EventBusy,
                            stringResource(L10nR.string.nbNoneInRadius),
                            modifier = Modifier.testTag(NearbyTags.EMPTY),
                            subtitle = stringResource(L10nR.string.nbNoneInRadiusSub),
                        )
                    }
                    else -> items(state.results, key = { "r-${it.meetup.id}" }) { n ->
                        ResultCard(n, state.now, state.zone, onAction, Modifier.animateItem())
                    }
                }
            }
        }
    }
    if (pickDay) {
        BoundedDatePickerDialog(
            initial = state.dayFrom ?: state.today,
            first = state.firstPickDate,
            last = state.lastPickDate,
            onDismiss = { pickDay = false },
            onPicked = {
                pickDay = false
                onAction(NearbyAction.DaySelected(it))
            },
        )
    }
    if (pickRange) {
        BoundedDateRangePickerDialog(
            initialFrom = state.dayFrom.takeIf { state.dateMode == DateMode.RANGE },
            initialTo = state.dayTo.takeIf { state.dateMode == DateMode.RANGE },
            first = state.firstPickDate,
            last = state.lastPickDate,
            onDismiss = { pickRange = false },
            onPicked = { from, to ->
                pickRange = false
                onAction(NearbyAction.RangeSelected(from, to))
            },
        )
    }
}

@Composable
private fun NearbyMap(state: NearbyUiState, onAction: (NearbyAction) -> Unit) {
    val center = state.center
    val camera = rememberOsmCameraState(
        initialTarget = center ?: LatLng.DachCenter,
        initialZoom = if (center != null) 9.0 else 5.0,
    )
    val currentState by rememberUpdatedState(state)
    // Dart `_fitMap` after every search: center + all results with coordinates.
    FitCameraOnNewSearch(state.fitSession, state.fitToken, camera) {
        val s = currentState
        buildList {
            s.center?.let(::add)
            s.results.filter { it.hasCoords }.forEach { add(LatLng(it.meetup.lat, it.meetup.lng)) }
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxWidth()
            .height(MapHeight)
            .testTag(NearbyTags.MAP),
    ) {
        OsmMap(cameraState = camera, modifier = Modifier.fillMaxSize()) {
            if (center != null) {
                Circle(center, state.radiusKm * 1000, primary.copy(alpha = 0.08f), primary.copy(alpha = 0.4f))
                Marker(center, contentDescription = state.centerDescription()) { LocationDot() }
            }
            state.results.filter { it.hasCoords }.forEach { n ->
                Marker(
                    position = LatLng(n.meetup.lat, n.meetup.lng),
                    anchor = MarkerAnchor.Center,
                    contentDescription = n.meetup.city,
                    onClick = { onAction(NearbyAction.OpenDetails(n.meetup.id)) },
                ) { MeetupPinMarker(active = n.nextEvent != null, Modifier.minimumInteractiveComponentSize()) }
            }
        }
        MapZoomControls(camera, Modifier.align(Alignment.CenterEnd).padding(end = 8.dp))
    }
}

/**
 * Fits [camera] to [points] once per search ([token] within the ViewModel [session]). The applied token is saved, so coming back
 * from MeetupDetails (Navigation 3 recomposes the entry) or rotating keeps the user's zoom/pan.
 */
@Composable
internal fun FitCameraOnNewSearch(session: Long, token: Int, camera: OsmCameraState, points: () -> List<LatLng>) {
    // Keyed by ViewModel session + token: after process death the new ViewModel counts from 0
    // again, and a bare saved token would swallow the matching new search.
    var fitted by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(session, token) {
        val key = "$session:$token"
        if (token == 0 || key == fitted) return@LaunchedEffect
        fitted = key
        val list = points()
        runCatching {
            when (list.size) {
                0 -> Unit
                1 -> camera.animateTo(list[0], 10.0)
                else -> camera.fitPoints(list, padding = 50.dp, maxZoom = 12.0, animate = true)
            }
        }
    }
}

@Composable
private fun NearbyUiState.centerDescription(): String =
    if (mode == SearchMode.HERE) stringResource(L10nR.string.nbCenterHere) else placeLabel

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModeToggle(mode: SearchMode, onSelect: (SearchMode) -> Unit) {
    val options = listOf(
        Triple(SearchMode.HERE, Icons.Rounded.MyLocation, stringResource(L10nR.string.nbModeHere)),
        Triple(SearchMode.PLANNED, Icons.Rounded.Event, stringResource(L10nR.string.nbModePlanned)),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, (m, icon, label) ->
            ToggleButton(
                checked = mode == m,
                onCheckedChange = { onSelect(m) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag(NearbyTags.mode(m)),
                shapes = if (index == 0) {
                    ButtonGroupDefaults.connectedLeadingButtonShapes()
                } else {
                    ButtonGroupDefaults.connectedTrailingButtonShapes()
                },
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(ToggleButtonDefaults.IconSize))
                Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
                Text(label, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Controls(
    state: NearbyUiState,
    onAction: (NearbyAction) -> Unit,
    onPickDay: () -> Unit,
    onPickRange: () -> Unit,
) {
    Tile(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                state.mode == SearchMode.PLANNED -> PlaceSearch(state, onAction)
                state.locating -> Row(verticalAlignment = Alignment.CenterVertically) {
                    AppLoadingIndicator(size = 24.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.native_nearby_locating),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.showLocationHint -> LocationHint(state.locStatus, onAction)
            }
            RadiusRow(state, onAction)
            DateFilters(state, onAction, onPickDay, onPickRange)
        }
    }
}

@Composable
private fun LocationHint(status: LocationStatus, onAction: (NearbyAction) -> Unit) {
    val title = when (status) {
        LocationStatus.DENIED, LocationStatus.DENIED_FOREVER -> stringResource(L10nR.string.nbLocationDenied)
        LocationStatus.SERVICE_DISABLED -> stringResource(L10nR.string.nbServiceDisabled)
        else -> null
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
            .padding(12.dp)
            .testTag(NearbyTags.LOCATION_HINT),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.LocationOff, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(L10nR.string.nbLocationDeniedSub),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FlowRowButtons(status, onAction)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowButtons(status: LocationStatus, onAction: (NearbyAction) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { onAction(NearbyAction.RetryLocation) }) {
            Text(stringResource(L10nR.string.nbRetryLocation))
        }
        when (status) {
            LocationStatus.DENIED_FOREVER -> TextButton(onClick = { onAction(NearbyAction.OpenAppSettings) }) {
                Text(stringResource(R.string.native_meetups_open_app_settings))
            }
            LocationStatus.SERVICE_DISABLED -> TextButton(onClick = { onAction(NearbyAction.OpenLocationSettings) }) {
                Text(stringResource(R.string.native_meetups_open_location_settings))
            }
            else -> Unit
        }
    }
}

@Composable
private fun PlaceSearch(state: NearbyUiState, onAction: (NearbyAction) -> Unit) {
    val focus = LocalFocusManager.current
    Column {
        OutlinedTextField(
            value = state.query,
            onValueChange = { onAction(NearbyAction.QueryChanged(it)) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(NearbyTags.PLACE_FIELD),
            placeholder = { Text(stringResource(L10nR.string.nbSearchPlace)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                when {
                    state.searchingPlace -> AppLoadingIndicator(size = 20.dp)
                    state.query.isNotEmpty() -> IconButton(onClick = { onAction(NearbyAction.QueryChanged("")) }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.native_meetups_clear_search))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )
        AnimatedVisibility(
            visible = state.placeResults.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(
                Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)),
            ) {
                state.placeResults.forEachIndexed { i, place ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button) {
                                focus.clearFocus()
                                onAction(NearbyAction.PlaceSelected(place))
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag(NearbyTags.place(i)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(place.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (state.placeCenter != null && state.placeLabel.isNotEmpty()) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = MaterialTheme.extendedColors.community, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(state.placeLabel, color = MaterialTheme.extendedColors.community, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun RadiusRow(state: NearbyUiState, onAction: (NearbyAction) -> Unit) {
    val radiusText = stringResource(L10nR.string.nbRadiusValue, state.radiusKm.dartRound().toString())
    val radiusTitle = stringResource(L10nR.string.nbRadius)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Radar, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(radiusTitle, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(radiusText, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = state.radiusKm.toFloat(),
            onValueChange = { onAction(NearbyAction.RadiusChanging(it.toDouble())) },
            onValueChangeFinished = { onAction(NearbyAction.RadiusChangeFinished) },
            valueRange = NearbyMeetupService.MIN_RADIUS_KM.toFloat()..NearbyMeetupService.MAX_RADIUS_KM.toFloat(),
            steps = 18,
            modifier = Modifier
                .testTag(NearbyTags.RADIUS)
                .semantics {
                    contentDescription = radiusTitle
                    stateDescription = radiusText
                },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DateFilters(state: NearbyUiState, onAction: (NearbyAction) -> Unit, onPickDay: () -> Unit, onPickRange: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(L10nR.string.nbDateSingle), style = MaterialTheme.typography.titleSmall)
    }
    val dayFrom = state.dayFrom
    val dayTo = state.dayTo
    val dayLabel = if (state.dateMode == DateMode.SINGLE_DAY && dayFrom != null) {
        MeetupFormats.ddMMyyyy(dayFrom)
    } else {
        stringResource(L10nR.string.nbPickDay)
    }
    val rangeLabel = if (state.dateMode == DateMode.RANGE && dayFrom != null && dayTo != null) {
        stringResource(L10nR.string.nbDateFromTo, MeetupFormats.ddMM(dayFrom), MeetupFormats.ddMM(dayTo))
    } else {
        stringResource(L10nR.string.nbPickRange)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DateChip(stringResource(L10nR.string.nbDateAny), null, state.dateMode == DateMode.ANY, NearbyTags.DATE_ANY) {
            onAction(NearbyAction.DateAny)
        }
        DateChip(dayLabel, Icons.Rounded.Event, state.dateMode == DateMode.SINGLE_DAY, NearbyTags.DATE_DAY, onPickDay)
        DateChip(rangeLabel, Icons.Rounded.DateRange, state.dateMode == DateMode.RANGE, NearbyTags.DATE_RANGE, onPickRange)
    }
}

@Composable
private fun DateChip(label: String, icon: ImageVector?, selected: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
        leadingIcon = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) } },
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag(tag),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

@Composable
private fun ResultCard(
    n: NearbyMeetup,
    now: Instant,
    zone: ZoneId,
    onAction: (NearbyAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val m = n.meetup
    Tile(
        modifier = modifier
            .fillMaxWidth()
            .testTag(NearbyTags.card(m.id)),
        contentPadding = PaddingValues(14.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Rounded.LocationOn)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.city, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    m.groupName?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    n.distanceKm?.let { d ->
                        Text(
                            stringResource(L10nR.string.nbKmAway, MeetupFormats.km(d)),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { onAction(NearbyAction.OpenDirections(n)) }) {
                    Icon(
                        Icons.AutoMirrored.Rounded.DirectionsWalk,
                        contentDescription = stringResource(R.string.native_meetups_directions),
                        tint = MaterialTheme.extendedColors.community,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            val next = n.nextEvent
            if (next != null) {
                NextEventBox(next, n.allEvents.size, now, zone)
            } else {
                Text(
                    stringResource(L10nR.string.nbNoDate),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.extendedColors.textTertiary,
                )
            }
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(
                onClick = { onAction(NearbyAction.OpenDetails(m.id)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag(NearbyTags.details(m.id)),
            ) { Text(stringResource(L10nR.string.nbDetails), fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun NextEventBox(next: MeetupEventInfo, total: Int, now: Instant, zone: ZoneId) {
    val locale = LocalResources.current.configuration.locales[0]
    val whenText = when (val d = MeetupFormats.nearbyDate(next.start, now, zone, locale)) {
        is MeetupFormats.NearbyDate.Today -> "${stringResource(L10nR.string.nbToday)}, ${d.time}"
        is MeetupFormats.NearbyDate.Tomorrow -> "${stringResource(L10nR.string.nbTomorrow)}, ${d.time}"
        is MeetupFormats.NearbyDate.Other -> d.text
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(whenText, fontWeight = FontWeight.SemiBold)
        }
        if (MeetupScreenRules.showsEventLocation(next.location)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Place, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(next.location, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (total > 1) {
            Text(
                stringResource(L10nR.string.nbMoreDates, total - 1),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Preview
@Composable
private fun NearbyMeetupsPreview() {
    val now = Instant.parse("2026-07-20T10:00:00Z")
    val berlin = Meetup(id = "1", name = "Einundzwanzig Berlin", city = "Berlin", country = "DE", telegramLink = "", lat = 52.52, lng = 13.405)
    val potsdam = Meetup(id = "2", name = "", city = "Potsdam", country = "DE", telegramLink = "", lat = 52.39, lng = 13.06)
    EinundzwanzigTheme {
        NearbyMeetupsScreen(
            NearbyUiState(
                hereCenter = LatLng(52.5, 13.4),
                loading = false,
                now = now,
                results = listOf(
                    NearbyMeetup(
                        berlin,
                        MeetupEventInfo("Berlin Meetup", location = "Bitcoin Bar", start = now.plusSeconds(3600 * 8)),
                        listOf(
                            MeetupEventInfo("Berlin Meetup", location = "Bitcoin Bar", start = now.plusSeconds(3600 * 8)),
                            MeetupEventInfo("Berlin Meetup", start = now.plusSeconds(86_400 * 14)),
                        ),
                        distanceKm = 2.25,
                    ),
                    NearbyMeetup(potsdam, distanceKm = 23.6),
                ),
            ),
            SnackbarHostState(),
        ) {}
    }
}
