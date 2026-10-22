package space.einundzwanzig.meetup.feature.events.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MilitaryTech
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.result.LocalResultEventBus
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.LocationPicker
import space.einundzwanzig.meetup.core.navigation.NavResultEffect
import space.einundzwanzig.meetup.core.navigation.NavResults
import space.einundzwanzig.meetup.core.navigation.sendResult
import space.einundzwanzig.meetup.core.ui.component.AppLargeTopBar
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.guide.FinishTourOnDispose
import space.einundzwanzig.meetup.core.ui.guide.GuideStep
import space.einundzwanzig.meetup.core.ui.guide.GuideTour
import space.einundzwanzig.meetup.core.ui.guide.StartTourEffect
import space.einundzwanzig.meetup.core.ui.guide.guideAnchor
import space.einundzwanzig.meetup.core.ui.guide.guideScrollContainer
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.MonoStyle
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.calendar.format.CalendarFormat
import space.einundzwanzig.meetup.data.calendar.format.IssuerInput
import space.einundzwanzig.meetup.data.issuance.event.EventBadgeRight
import space.einundzwanzig.meetup.feature.events.R
import space.einundzwanzig.meetup.feature.events.calendar.HidingImage
import space.einundzwanzig.meetup.feature.events.common.InlineLoading
import space.einundzwanzig.meetup.feature.events.common.MessageEffect
import space.einundzwanzig.meetup.feature.events.common.SectionLabel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Guide anchors of the event badge tour (Dart `EventBadgeTour` keys). */
object EventEditorAnchors {
    const val BASICS = "events.basics"
    const val WHEN_WHERE = "events.whenWhere"
    const val SWITCH = "events.badgeSwitch"
    const val IMAGE = "events.badgeImage"
    const val LOCATION = "events.badgeLocation"
    const val ISSUERS = "events.badgeIssuers"
}

/** Tour steps of `GuideTour.Events` (Dart `EventBadgeTour.steps`, spec 10 §2.4). */
fun eventBadgeTourSteps(mayCreateBadge: Boolean): List<GuideStep> = buildList {
    add(GuideStep(EventEditorAnchors.BASICS, L10nR.string.guideEvBasicsTitle, L10nR.string.guideEvBasicsBody))
    add(GuideStep(EventEditorAnchors.WHEN_WHERE, L10nR.string.guideEvWhenWhereTitle, L10nR.string.guideEvWhenWhereBody))
    if (mayCreateBadge) {
        add(
            GuideStep(
                EventEditorAnchors.SWITCH,
                L10nR.string.guideEvBadgeSwitchTitle,
                L10nR.string.guideEvBadgeSwitchBody,
                hint = L10nR.string.guideEvBadgeSwitchHint,
            ),
        )
        // Short wait: without the switch these targets never appear and the steps are skipped.
        add(GuideStep(EventEditorAnchors.IMAGE, L10nR.string.guideEvBadgeImageTitle, L10nR.string.guideEvBadgeImageBody, waitTimeoutMillis = OPTIONAL_STEP_MS))
        add(GuideStep(EventEditorAnchors.LOCATION, L10nR.string.guideEvBadgeLocationTitle, L10nR.string.guideEvBadgeLocationBody, waitTimeoutMillis = OPTIONAL_STEP_MS))
        add(GuideStep(EventEditorAnchors.ISSUERS, L10nR.string.guideEvBadgeIssuersTitle, L10nR.string.guideEvBadgeIssuersBody, waitTimeoutMillis = OPTIONAL_STEP_MS))
    }
}

private const val OPTIONAL_STEP_MS = 900L

/** Stateful entry of `EventEditor`. */
@Composable
fun EventEditorRoute(viewModel: EventEditorViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val bus = LocalResultEventBus.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    MessageEffect(viewModel.messages, snackbar)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                EventEditorEffect.Published -> {
                    bus.sendResult(NavResults.EventPublished, Unit)
                    navigator.back()
                }
            }
        }
    }
    NavResultEffect(NavResults.LocationPicked) { picked ->
        viewModel.onAction(EventEditorAction.LocationPicked(picked.latitude, picked.longitude))
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.onAction(EventEditorAction.ImagePicked(uri.toString()))
    }
    // First-run tour once the permission is known (Dart: 400 ms after `myRight()`).
    val right = state.right
    StartTourEffect(GuideTour.Events, enabled = right != null, delayMillis = 400) {
        eventBadgeTourSteps(mayCreateBadge = right != null && right != EventBadgeRight.NONE)
    }
    FinishTourOnDispose(GuideTour.Events)
    EventEditorScreen(
        state = state,
        snackbarHostState = snackbar,
        onAction = { action ->
            when (action) {
                EventEditorAction.Back -> navigator.back()
                EventEditorAction.PickLocation -> navigator.navigate(
                    if (state.hasLocation) LocationPicker(state.lat, state.lng) else LocationPicker(),
                )
                EventEditorAction.PickImage -> picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
                else -> viewModel.onAction(action)
            }
        },
    )
}

/** Test tags. */
internal object EventEditorTags {
    const val TITLE = "eventEditor.title"
    const val LOCATION = "eventEditor.location"
    const val DESCRIPTION = "eventEditor.description"
    const val ALL_DAY = "eventEditor.allDay"
    const val START = "eventEditor.start"
    const val END = "eventEditor.end"
    const val CLEAR_END = "eventEditor.clearEnd"
    const val BADGE_SWITCH = "eventEditor.badgeSwitch"
    const val BADGE_IMAGE = "eventEditor.badgeImage"
    const val PICK_IMAGE = "eventEditor.pickImage"
    const val PICK_LOCATION = "eventEditor.pickLocation"
    const val PUBLISH = "eventEditor.publish"
    fun issuerRow(i: Int) = "eventEditor.issuer.$i"
}

/** "Veranstaltung eintragen" (spec 10 §2.4). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EventEditorScreen(
    state: EventEditorUiState,
    onAction: (EventEditorAction) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scroll = rememberScrollState()
    var picking by rememberSaveable { mutableStateOf<DateField?>(null) }
    var pendingDate by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppLargeTopBar(
                title = stringResource(L10nR.string.calNewEventTitle),
                onBack = { onAction(EventEditorAction.Back) },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .guideScrollContainer(scroll)
                .verticalScroll(scroll)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.guideAnchor(EventEditorAnchors.BASICS), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(L10nR.string.calFieldTitle) {
                    EditorTextField(
                        state.title,
                        { onAction(EventEditorAction.SetTitle(it)) },
                        stringResource(L10nR.string.calFieldTitleHint),
                        Modifier.testTag(EventEditorTags.TITLE),
                    )
                }
                Field(L10nR.string.calFieldLocation) {
                    EditorTextField(
                        state.location,
                        { onAction(EventEditorAction.SetLocation(it)) },
                        stringResource(L10nR.string.calFieldLocationHint),
                        Modifier.testTag(EventEditorTags.LOCATION),
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Switch) { onAction(EventEditorAction.SetAllDay(!state.allDay)) }
                    .testTag(EventEditorTags.ALL_DAY)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.EventAvailable, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(L10nR.string.calFieldAllDay), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(checked = state.allDay, onCheckedChange = null)
            }
            Column(Modifier.guideAnchor(EventEditorAnchors.WHEN_WHERE), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(L10nR.string.calFieldStart) {
                    DateButton(state.start, state.allDay, state.zone, Modifier.testTag(EventEditorTags.START)) {
                        picking = DateField.START
                    }
                }
                Field(L10nR.string.calFieldEnd) {
                    DateButton(state.end, state.allDay, state.zone, Modifier.testTag(EventEditorTags.END)) {
                        picking = DateField.END
                    }
                }
                if (state.end != null) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        TextButton(
                            onClick = { onAction(EventEditorAction.ClearEnd) },
                            modifier = Modifier.testTag(EventEditorTags.CLEAR_END),
                        ) { Text(stringResource(L10nR.string.calClearEnd)) }
                    }
                }
            }
            Field(L10nR.string.calFieldDescription) {
                EditorTextField(
                    state.description,
                    { onAction(EventEditorAction.SetDescription(it)) },
                    stringResource(L10nR.string.calFieldDescriptionHint),
                    Modifier.testTag(EventEditorTags.DESCRIPTION),
                    minLines = 4,
                    singleLine = false,
                )
            }
            BadgeSection(state, onAction)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.extendedColors.nostr.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Rounded.Public, contentDescription = null, tint = MaterialTheme.extendedColors.nostr)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(L10nR.string.calPublishInfo), style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = { onAction(EventEditorAction.Publish) },
                enabled = !state.publishing,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag(EventEditorTags.PUBLISH),
            ) {
                if (state.publishing) {
                    InlineLoading(20.dp, MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(L10nR.string.calPublishing), fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(L10nR.string.calPublish), fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    picking?.let { field ->
        val pending = pendingDate
        if (pending == null) {
            val initial = when (field) {
                DateField.START -> state.start
                DateField.END -> state.end ?: state.start
            } ?: state.now
            EditorDatePicker(
                initial = initial.atZone(state.zone).toLocalDate(),
                first = state.firstDate,
                last = state.lastDate,
                onDismiss = { picking = null },
                onPicked = { date ->
                    if (state.allDay) {
                        onAction(EventEditorAction.DatePicked(field, date, null))
                        picking = null
                    } else {
                        pendingDate = date.toString()
                    }
                },
            )
        } else {
            val initial = (
                when (field) {
                    DateField.START -> state.start
                    DateField.END -> state.end ?: state.start
                } ?: state.now
                ).atZone(state.zone).toLocalTime()
            EditorTimePicker(
                initial = initial,
                onDone = { time ->
                    onAction(EventEditorAction.DatePicked(field, LocalDate.parse(pending), time))
                    pendingDate = null
                    picking = null
                },
            )
        }
    }
}

@Composable
private fun Field(label: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(stringResource(label))
        content()
    }
}

@Composable
private fun EditorTextField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    singleLine: Boolean = true,
    isError: Boolean = false,
    mono: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(hint, style = if (mono) MonoStyle else MaterialTheme.typography.bodyLarge) },
        singleLine = singleLine,
        minLines = minLines,
        isError = isError,
        textStyle = if (mono) MonoStyle else MaterialTheme.typography.bodyLarge,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
    )
}

@Composable
private fun DateButton(value: Instant?, allDay: Boolean, zone: ZoneId, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val active = value != null
    val accent = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, accent),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        Icon(
            Icons.Rounded.CalendarMonth,
            contentDescription = null,
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (value != null) {
                CalendarFormat.editorDate(value, allDay, zone)
            } else {
                stringResource(if (allDay) L10nR.string.calPickDate else L10nR.string.calPickDateTime)
            },
            modifier = Modifier.weight(1f),
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorDatePicker(
    initial: LocalDate,
    first: LocalDate,
    last: LocalDate,
    onDismiss: () -> Unit,
    onPicked: (LocalDate) -> Unit,
) {
    val firstMillis = first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val lastMillis = last.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val clamped = initial.coerceIn(first, last)
    // The calendar grid does not scroll: on short windows (landscape, split screen) it would push
    // OK/Abbrechen out of the dialog, so start with the compact text input there (toggle stays).
    val compact = LocalConfiguration.current.screenHeightDp < COMPACT_PICKER_HEIGHT_DP
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = clamped.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = first.year..last.year,
        initialDisplayMode = if (compact) DisplayMode.Input else DisplayMode.Picker,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis in firstMillis..lastMillis
            override fun isSelectableYear(year: Int): Boolean = year in first.year..last.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = pickerState.selectedDateMillis
                    if (millis == null) {
                        onDismiss()
                    } else {
                        onPicked(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                },
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(L10nR.string.actionCancel)) } },
    ) {
        DatePicker(state = pickerState, showModeToggle = true)
    }
}

/** Below this window height the date picker opens in [DisplayMode.Input]. */
private const val COMPACT_PICKER_HEIGHT_DP = 560

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTimePicker(initial: LocalTime, onDone: (LocalTime?) -> Unit) {
    val pickerState = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        // Dismissing the time dialog keeps the date and uses 19:00 (Dart behaviour).
        onDismissRequest = { onDone(null) },
        confirmButton = {
            TextButton(onClick = { onDone(LocalTime.of(pickerState.hour, pickerState.minute)) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(L10nR.string.actionCancel)) } },
        text = { TimePicker(state = pickerState) },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BadgeSection(state: EventEditorUiState, onAction: (EventEditorAction) -> Unit) {
    val orange = MaterialTheme.colorScheme.primary
    val border by animateColorAsState(
        if (state.badgeEnabled) orange.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant,
        label = "badgeBorder",
    )
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .border(1.dp, border, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.MilitaryTech,
                contentDescription = null,
                tint = if (state.mayCreateBadge) orange else MaterialTheme.extendedColors.textTertiary,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(L10nR.string.evBadgeCreate), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(
                        when {
                            state.right == null -> L10nR.string.evBadgeChecking
                            state.mayCreateBadge -> L10nR.string.evBadgeCreateSub
                            else -> L10nR.string.evBadgeNotAllowed
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = state.badgeEnabled,
                onCheckedChange = { onAction(EventEditorAction.SetBadgeEnabled(it)) },
                enabled = state.mayCreateBadge,
                modifier = Modifier.guideAnchor(EventEditorAnchors.SWITCH).testTag(EventEditorTags.BADGE_SWITCH),
            )
        }
        AnimatedVisibility(
            state.badgeEnabled,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                BadgeImageField(state, onAction)
                BadgeLocationField(state, onAction)
                IssuerRows(state, onAction)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BadgeImageField(state: EventEditorUiState, onAction: (EventEditorAction) -> Unit) {
    Column(Modifier.guideAnchor(EventEditorAnchors.IMAGE), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(stringResource(L10nR.string.evBadgeImage))
        Row(verticalAlignment = Alignment.CenterVertically) {
            EditorTextField(
                state.badgeImage,
                { onAction(EventEditorAction.SetBadgeImage(it)) },
                stringResource(L10nR.string.evBadgeImageHint),
                Modifier.weight(1f).testTag(EventEditorTags.BADGE_IMAGE),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = { onAction(EventEditorAction.PickImage) },
                enabled = !state.uploading,
                modifier = Modifier.size(48.dp).testTag(EventEditorTags.PICK_IMAGE),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                if (state.uploading) {
                    InlineLoading(22.dp, MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Rounded.PhotoLibrary, contentDescription = stringResource(R.string.native_events_pick_image))
                }
            }
        }
        Text(
            stringResource(if (state.uploading) L10nR.string.evBadgeUploading else L10nR.string.evBadgeImageInfo),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.badgeImage.startsWith("http")) {
            HidingImage(
                state.badgeImage.trim(),
                Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(10.dp)),
            )
        }
    }
}

@Composable
private fun BadgeLocationField(state: EventEditorUiState, onAction: (EventEditorAction) -> Unit) {
    Column(Modifier.guideAnchor(EventEditorAnchors.LOCATION), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(stringResource(L10nR.string.evBadgeLocation))
        val shape = RoundedCornerShape(12.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerLow, shape)
                .border(
                    1.dp,
                    if (state.hasLocation) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape,
                )
                .clickable(role = Role.Button) { onAction(EventEditorAction.PickLocation) }
                .testTag(EventEditorTags.PICK_LOCATION)
                .padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Map, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            if (state.hasLocation) {
                Text(
                    CalendarFormat.coordinates(state.lat, state.lng),
                    modifier = Modifier.weight(1f),
                    style = MonoStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { onAction(EventEditorAction.ClearLocation) }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.native_events_clear_location))
                }
            } else {
                Text(stringResource(L10nR.string.evBadgeLocationPick), modifier = Modifier.weight(1f))
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 12.dp),
                    tint = MaterialTheme.extendedColors.textTertiary,
                )
            }
        }
        Text(
            stringResource(L10nR.string.evBadgeLocationInfo),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IssuerRows(state: EventEditorUiState, onAction: (EventEditorAction) -> Unit) {
    Column(Modifier.guideAnchor(EventEditorAnchors.ISSUERS), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(stringResource(L10nR.string.evBadgeIssuers))
        state.issuerRows.forEachIndexed { i, row ->
            val last = i == state.issuerRows.lastIndex
            Row(verticalAlignment = Alignment.CenterVertically) {
                EditorTextField(
                    row,
                    { onAction(EventEditorAction.SetIssuerRow(i, it)) },
                    stringResource(L10nR.string.evBadgeIssuerHint),
                    Modifier.weight(1f).testTag(EventEditorTags.issuerRow(i)),
                    isError = IssuerInput.isRowInvalid(row),
                    mono = true,
                )
                Spacer(Modifier.width(4.dp))
                if (last) {
                    IconButton(onClick = { onAction(EventEditorAction.AddIssuerRow) }) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.native_events_add_issuer_row),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    IconButton(onClick = { onAction(EventEditorAction.RemoveIssuerRow(i)) }) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.native_events_remove_issuer_row),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Text(
            stringResource(L10nR.string.evBadgeIssuerInfo),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview
@Composable
private fun EventEditorPreview() {
    EinundzwanzigTheme {
        EventEditorScreen(
            state = EventEditorUiState(
                zone = ZoneId.of("Europe/Berlin"),
                now = Instant.parse("2026-10-21T10:00:00Z"),
                title = "BTC Prag",
                right = EventBadgeRight.PORTAL_LEADER,
                badgeEnabled = true,
                lat = 50.08804,
                lng = 14.42076,
            ),
            onAction = {},
        )
    }
}
