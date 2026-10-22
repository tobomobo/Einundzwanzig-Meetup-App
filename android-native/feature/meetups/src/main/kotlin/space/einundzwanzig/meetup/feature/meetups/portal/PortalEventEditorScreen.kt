package space.einundzwanzig.meetup.feature.meetups.portal

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.result.LocalResultEventBus
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.NavResults
import space.einundzwanzig.meetup.core.navigation.PortalEventEditor
import space.einundzwanzig.meetup.core.navigation.sendResult
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.AppMediumTopBar
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.EmptyState
import space.einundzwanzig.meetup.core.ui.component.FullScreenLoading
import space.einundzwanzig.meetup.core.ui.component.MonoText
import space.einundzwanzig.meetup.core.ui.component.Tile
import space.einundzwanzig.meetup.core.ui.format.Formats
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.feature.meetups.R
import space.einundzwanzig.meetup.feature.meetups.common.BoundedDatePickerDialog
import space.einundzwanzig.meetup.feature.meetups.common.CollectMessages
import space.einundzwanzig.meetup.feature.meetups.common.IconTile
import space.einundzwanzig.meetup.feature.meetups.common.SectionLabel
import space.einundzwanzig.meetup.feature.meetups.common.TimePickerDialog
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import space.einundzwanzig.meetup.core.l10n.R as L10nR

internal object PortalEditorTags {
    const val START = "portal_editor_start"
    const val LOCATION = "portal_editor_location"
    const val DESCRIPTION = "portal_editor_description"
    const val LINK = "portal_editor_link"
    const val SAVE = "portal_editor_save"
    const val LOADING = "portal_editor_loading"
}

/** Stateful portal date editor (Dart `_EventEditor`, spec 09 §2.1). */
@Composable
fun PortalEventEditorRoute(
    key: PortalEventEditor,
    viewModel: PortalEventEditorViewModel = hiltViewModel<PortalEventEditorViewModel, PortalEventEditorViewModel.Factory>(
        key = "portal-editor-${key.meetupId}-${key.eventId}-${key.eventIndex}",
        creationCallback = { it.create(key) },
    ),
) {
    val navigator = LocalNavigator.current
    val bus = LocalResultEventBus.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(viewModel.messages, snackbar)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PortalEventEditorEvent.Saved -> {
                    bus.sendResult(NavResults.PortalEventSaved, event.created)
                    navigator.back()
                }
            }
        }
    }
    PortalEventEditorScreen(state, snackbar) { action ->
        if (action == PortalEventEditorAction.Back) navigator.back() else viewModel.onAction(action)
    }
}

/** Stateless portal date editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortalEventEditorScreen(
    state: PortalEventEditorUiState,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    onAction: (PortalEventEditorAction) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var pendingDate by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        topBar = {
            AppMediumTopBar(
                title = stringResource(if (state.isEdit) L10nR.string.portalEditEvent else L10nR.string.portalEventTitle),
                onBack = { onAction(PortalEventEditorAction.Back) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        if (state.loading) {
            FullScreenLoading(Modifier.padding(padding).testTag(PortalEditorTags.LOADING))
            return@Scaffold
        }
        if (state.notFound) {
            EmptyState(
                icon = Icons.Rounded.EventBusy,
                title = stringResource(R.string.native_portal_event_not_found),
                subtitle = state.meetupName,
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Tile(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Rounded.Groups)
                    Spacer(Modifier.width(12.dp))
                    Text(state.meetupName, style = MaterialTheme.typography.titleMedium)
                }
            }
            Field(stringResource(L10nR.string.portalFieldStart)) {
                StartBox(state.start) { pickingDate = true }
            }
            Field(stringResource(L10nR.string.portalFieldLocation)) {
                EditorField(
                    value = state.location,
                    onChange = { onAction(PortalEventEditorAction.LocationChanged(it)) },
                    hint = stringResource(L10nR.string.portalFieldLocationHint),
                    tag = PortalEditorTags.LOCATION,
                )
            }
            Field(stringResource(L10nR.string.portalFieldDescription)) {
                EditorField(
                    value = state.description,
                    onChange = { onAction(PortalEventEditorAction.DescriptionChanged(it)) },
                    hint = stringResource(L10nR.string.portalFieldDescriptionHint),
                    tag = PortalEditorTags.DESCRIPTION,
                    singleLine = false,
                    minLines = 4,
                )
            }
            Field(stringResource(L10nR.string.portalFieldLink)) {
                EditorField(
                    value = state.link,
                    onChange = { onAction(PortalEventEditorAction.LinkChanged(it)) },
                    hint = stringResource(L10nR.string.portalFieldLinkHint),
                    tag = PortalEditorTags.LINK,
                    keyboardType = KeyboardType.Uri,
                )
            }
            Button(
                onClick = { onAction(PortalEventEditorAction.Save) },
                enabled = !state.saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag(PortalEditorTags.SAVE),
            ) {
                if (state.saving) {
                    AppLoadingIndicator(size = 20.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Rounded.Check, contentDescription = null)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(
                        when {
                            state.saving -> L10nR.string.portalSaving
                            state.isEdit -> L10nR.string.portalUpdate
                            else -> L10nR.string.portalSave
                        },
                    ),
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    val pending = pendingDate
    if (pickingDate && pending == null) {
        BoundedDatePickerDialog(
            initial = (state.start ?: state.now).toLocalDate(),
            first = state.firstDate,
            last = state.lastDate,
            onDismiss = { pickingDate = false },
            onPicked = { pendingDate = it.toString() },
        )
    } else if (pickingDate && pending != null) {
        TimePickerDialog(initial = (state.start ?: state.now).toLocalTime()) { time ->
            onAction(PortalEventEditorAction.StartPicked(LocalDate.parse(pending), time))
            pendingDate = null
            pickingDate = false
        }
    }
}

@Composable
private fun Field(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(label)
        content()
    }
}

@Composable
private fun StartBox(start: ZonedDateTime?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val border by animateColorAsState(
        if (start != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        label = "startBorder",
    )
    val label = stringResource(L10nR.string.portalFieldStart)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .border(if (start != null) 1.5.dp else 1.dp, border, shape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag(PortalEditorTags.START),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.CalendarMonth,
            contentDescription = null,
            tint = if (start != null) MaterialTheme.colorScheme.primary else MaterialTheme.extendedColors.textTertiary,
        )
        Spacer(Modifier.width(12.dp))
        if (start != null) {
            MonoText(Formats.dateTimePadded(start.toLocalDateTime()), fontWeight = FontWeight.Bold)
        } else {
            Text(stringResource(L10nR.string.portalPickDate), color = MaterialTheme.extendedColors.textTertiary)
        }
    }
}

@Composable
private fun EditorField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    tag: String,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
        placeholder = { Text(hint) },
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
    )
}

@Preview
@Composable
private fun PortalEventEditorPreview() {
    val now = ZonedDateTime.of(2026, 7, 1, 12, 0, 0, 0, ZoneId.of("Europe/Berlin"))
    EinundzwanzigTheme {
        PortalEventEditorScreen(
            PortalEventEditorUiState(
                meetupName = "Einundzwanzig Südniedersachsen",
                isEdit = true,
                start = now.plusDays(20).withHour(18),
                location = "Dinx Diner",
                today = now.toLocalDate(),
                now = now,
            ),
            SnackbarHostState(),
        ) {}
    }
}
