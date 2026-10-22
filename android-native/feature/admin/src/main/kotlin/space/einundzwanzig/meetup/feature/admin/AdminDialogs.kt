package space.einundzwanzig.meetup.feature.admin

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WrongLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.dart.toStringAsFixed
import space.einundzwanzig.meetup.core.ui.component.AppContainedLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.ConfirmDialog
import space.einundzwanzig.meetup.core.ui.component.Tile
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.meetups.location.GpsStatus
import space.einundzwanzig.meetup.data.meetups.location.MeetupCandidate
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** The dialog layer of the panel (spec 05 §2.2.1 confirmations and the §2.2.2 catalogue). */
@Composable
internal fun AdminDialogs(state: AdminPanelUiState, onAction: (AdminPanelAction) -> Unit) {
    val dismiss = { onAction(AdminPanelAction.DismissDialog) }
    when (val dialog = state.dialog) {
        null -> Unit
        AdminDialog.ConfirmStart -> StartConfirmDialog(onAction)
        AdminDialog.ConfirmEnd -> ConfirmDialog(
            title = stringResource(L10nR.string.apSessionEndQ),
            text = stringResource(L10nR.string.apSessionEndBody),
            confirmLabel = stringResource(L10nR.string.apEnd),
            dismissLabel = stringResource(L10nR.string.apCancel),
            onConfirm = { onAction(AdminPanelAction.ConfirmEnd) },
            onDismiss = dismiss,
            destructive = true,
        )
        is AdminDialog.ContinueWithoutGps -> ContinueWithoutGpsDialog(dialog.status, onAction)
        AdminDialog.CustomNeedsLocation -> ActionDialog(
            icon = Icons.Rounded.WrongLocation,
            title = stringResource(L10nR.string.apCustomNeedsGpsTitle),
            text = stringResource(L10nR.string.apCustomNeedsGpsBody),
            onDismiss = dismiss,
        ) {
            TextButton(onClick = { onAction(AdminPanelAction.OpenLocationSettings) }) {
                Text(stringResource(L10nR.string.gpsOpenLocationSettings))
            }
            TextButton(onClick = { onAction(AdminPanelAction.GpsRetry) }) {
                Text(stringResource(L10nR.string.gpsRetry), fontWeight = FontWeight.Bold)
            }
        }
        is AdminDialog.NoReference -> ActionDialog(
            icon = Icons.Rounded.LocationOff,
            title = stringResource(L10nR.string.apNoRefTitle),
            text = stringResource(L10nR.string.apNoRefBody, dialog.city),
            onDismiss = dismiss,
        ) {
            TextButton(onClick = dismiss) { Text(stringResource(L10nR.string.dialogCancel)) }
            TextButton(onClick = { onAction(AdminPanelAction.NoReferenceContinue) }) {
                Text(stringResource(L10nR.string.apNoRefContinue), fontWeight = FontWeight.Bold)
            }
        }
        is AdminDialog.PortalPicker -> {
            PortalPickerDialog(dialog, onAction)
            dialog.confirming?.let { ConfirmPickDialog(it, onAction) }
        }
        is AdminDialog.ManualName -> ManualNameDialog(dialog, onAction)
        is AdminDialog.PickNearby -> PickNearbySheet(dialog.candidates, onAction)
    }
    if (state.busy) BusyDialog()
}

/** Not dismissible, centred indicator (Flutter: orange spinner over a barrier). */
@Composable
private fun BusyDialog() {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Box(Modifier.testTag(AdminPanelTags.BUSY), contentAlignment = Alignment.Center) {
            AppContainedLoadingIndicator(size = 64.dp)
        }
    }
}

@Composable
private fun StartConfirmDialog(onAction: (AdminPanelAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(AdminPanelAction.DismissDialog) },
        title = { Text(stringResource(L10nR.string.apNewMeetupQ)) },
        text = { Text(stringResource(L10nR.string.apNewMeetupBody)) },
        dismissButton = {
            TextButton(onClick = { onAction(AdminPanelAction.DismissDialog) }) {
                Text(stringResource(L10nR.string.apCancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = { onAction(AdminPanelAction.ConfirmStart) }) {
                Text(stringResource(L10nR.string.apStart), fontWeight = FontWeight.Bold)
            }
        },
    )
}

/** Alert with an accent icon next to the title and a wrapping action row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionDialog(
    icon: ImageVector,
    title: String,
    text: String,
    onDismiss: () -> Unit,
    actions: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(title, textAlign = TextAlign.Center) },
        text = {
            Text(text, modifier = Modifier.verticalScroll(rememberScrollState()))
        },
        confirmButton = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) { actions() }
        },
    )
}

@Composable
private fun ContinueWithoutGpsDialog(status: GpsStatus, onAction: (AdminPanelAction) -> Unit) {
    val msg = stringResource(
        when (status) {
            GpsStatus.DENIED -> L10nR.string.gpsDenied
            GpsStatus.SERVICE_DISABLED -> L10nR.string.gpsDisabled
            else -> L10nR.string.gpsError
        },
    )
    ActionDialog(
        icon = Icons.Rounded.LocationOff,
        title = stringResource(L10nR.string.orgGpsSoftTitle),
        text = msg + "\n\n" + stringResource(L10nR.string.orgGpsSoftBody),
        onDismiss = { onAction(AdminPanelAction.DismissDialog) },
    ) {
        when (status) {
            GpsStatus.SERVICE_DISABLED -> TextButton(onClick = { onAction(AdminPanelAction.OpenLocationSettings) }) {
                Text(stringResource(L10nR.string.gpsOpenLocationSettings))
            }
            GpsStatus.DENIED -> TextButton(onClick = { onAction(AdminPanelAction.OpenAppSettings) }) {
                Text(stringResource(L10nR.string.gpsOpenAppSettings))
            }
            else -> Unit
        }
        TextButton(onClick = { onAction(AdminPanelAction.GpsRetry) }) {
            Text(stringResource(L10nR.string.gpsRetry))
        }
        TextButton(onClick = { onAction(AdminPanelAction.GpsContinue) }) {
            Text(stringResource(L10nR.string.orgGpsSoftContinue), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PortalPickerDialog(dialog: AdminDialog.PortalPicker, onAction: (AdminPanelAction) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = { onAction(AdminPanelAction.DismissDialog) },
        title = { Text(stringResource(L10nR.string.apPickPortalTitle), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.fillMaxWidth().height(380.dp)) {
                Text(
                    stringResource(L10nR.string.apPickPortalHint),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.extendedColors.textTertiary,
                )
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = dialog.query,
                    onValueChange = { onAction(AdminPanelAction.PickerQueryChanged(it)) },
                    placeholder = { Text(stringResource(L10nR.string.profileSearchCity)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    trailingIcon = if (dialog.query.isNotEmpty()) {
                        {
                            IconButton(onClick = { onAction(AdminPanelAction.PickerQueryChanged("")) }) {
                                Icon(Icons.Rounded.Clear, contentDescription = stringResource(R.string.native_admin_clear_search))
                            }
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = MaterialTheme.colorScheme.surface,
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.surface,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .testTag(AdminPanelTags.PICKER_SEARCH),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(dialog.visibleRows, key = { i, r -> "$i|${r.meetup.id}" }) { _, row ->
                        PickerRow(row, onClick = { onAction(AdminPanelAction.PickerRowClicked(row.meetup)) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAction(AdminPanelAction.PickerEnterManually) }) {
                Text(stringResource(L10nR.string.apEnterManually))
            }
        },
    )
}

@Composable
private fun PickerRow(row: PickerMeetup, onClick: () -> Unit) {
    val tertiary = MaterialTheme.extendedColors.textTertiary
    ListItem(
        headlineContent = {
            Text(row.meetup.city, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
        },
        supportingContent = row.subtitle?.let {
            { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.5.sp, color = tertiary) }
        },
        leadingContent = {
            if (row.isMine) {
                Icon(
                    Icons.Rounded.Star,
                    contentDescription = stringResource(R.string.native_admin_own_meetup),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Spacer(Modifier.width(18.dp))
            }
        },
        trailingContent = {
            if (row.hasCoordinates) {
                Icon(
                    Icons.Rounded.Place,
                    contentDescription = stringResource(R.string.native_admin_has_location),
                    tint = MaterialTheme.extendedColors.success,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Icon(
                    Icons.Rounded.LocationOff,
                    contentDescription = stringResource(R.string.native_admin_no_location),
                    tint = tertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

@Composable
private fun ConfirmPickDialog(meetup: Meetup, onAction: (AdminPanelAction) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    AlertDialog(
        onDismissRequest = { onAction(AdminPanelAction.PickerConfirmCancel) },
        title = { Text(stringResource(L10nR.string.apConfirmPickTitle)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(primary.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                        .border(1.dp, primary.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        DartString.upper(meetup.city),
                        color = primary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(stringResource(L10nR.string.apConfirmPickBody))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(AdminPanelAction.PickerConfirmCancel) }) {
                Text(stringResource(L10nR.string.dialogCancel))
            }
        },
        confirmButton = {
            Button(onClick = { onAction(AdminPanelAction.PickerConfirmYes) }) {
                Text(stringResource(L10nR.string.apConfirmPickYes), fontWeight = FontWeight.Bold)
            }
        },
    )
}

@Composable
private fun ManualNameDialog(dialog: AdminDialog.ManualName, onAction: (AdminPanelAction) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = { onAction(AdminPanelAction.DismissDialog) },
        icon = { Icon(Icons.Rounded.AddLocationAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(L10nR.string.gpsNoMeetupTitle), textAlign = TextAlign.Center) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(L10nR.string.gpsNoMeetupBody))
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = dialog.text,
                    onValueChange = { onAction(AdminPanelAction.ManualNameChanged(it)) },
                    label = { Text(stringResource(L10nR.string.gpsMeetupNameLabel)) },
                    placeholder = { Text(stringResource(L10nR.string.gpsMeetupNameHint)) },
                    isError = dialog.showError,
                    supportingText = if (dialog.showError) {
                        { Text(stringResource(L10nR.string.gpsNameRequired)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onAction(AdminPanelAction.ManualNameSubmit) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .testTag(AdminPanelTags.MANUAL_NAME),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(AdminPanelAction.DismissDialog) }) {
                Text(stringResource(L10nR.string.actionCancel))
            }
        },
        confirmButton = {
            Button(onClick = { onAction(AdminPanelAction.ManualNameSubmit) }) {
                Text(stringResource(L10nR.string.gpsStartAnyway), fontWeight = FontWeight.Bold)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickNearbySheet(candidates: List<MeetupCandidate>, onAction: (AdminPanelAction) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // The ViewModel drops the sheet from state on answer; hide first so it animates out.
    fun hideThen(action: AdminPanelAction) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onAction(action) }
    }
    ModalBottomSheet(
        // Swipe/scrim dismiss already animated the sheet out before this callback.
        onDismissRequest = { onAction(AdminPanelAction.DismissDialog) },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            Text(
                stringResource(L10nR.string.gpsPickMeetup),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(L10nR.string.gpsPickMeetupSub),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            candidates.take(AdminPanelViewModel.MAX_NEARBY).forEach { candidate ->
                NearbyRow(candidate, onClick = { hideThen(AdminPanelAction.NearbyPicked(candidate)) })
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun NearbyRow(candidate: MeetupCandidate, onClick: () -> Unit) {
    val tertiary = MaterialTheme.extendedColors.textTertiary
    Tile(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(candidate.meetup.city, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                candidate.meetup.groupName?.let {
                    Text(it, fontSize = 11.5.sp, color = tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    stringResource(L10nR.string.gpsDistanceKm, candidate.distanceKm.toStringAsFixed(1)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = tertiary)
        }
    }
}

@Preview(name = "Portal picker", backgroundColor = 0xFF0C0C0E, showBackground = true)
@Composable
private fun PortalPickerPreview() {
    val meetups = listOf(
        Meetup(id = "1", name = "Einundzwanzig Würzburg", city = "Würzburg", country = "DE", telegramLink = "", lat = 49.79, lng = 9.95),
        Meetup(id = "2", name = "Aschaffenburg", city = "Aschaffenburg", country = "DE", telegramLink = "", lat = 0.0, lng = 0.0),
    )
    EinundzwanzigTheme {
        AdminDialogs(
            AdminPanelUiState(dialog = AdminDialog.PortalPicker(PortalMeetupPicker.order(meetups, setOf("würzburg")))),
            onAction = {},
        )
    }
}
