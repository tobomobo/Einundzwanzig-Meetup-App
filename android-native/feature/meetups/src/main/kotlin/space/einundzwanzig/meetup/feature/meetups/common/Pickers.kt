package space.einundzwanzig.meetup.feature.meetups.common

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialogDefaults
import androidx.compose.material3.TimePickerDisplayMode
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Below this window height the date pickers open in [DisplayMode.Input]. */
private const val COMPACT_PICKER_HEIGHT_DP = 560

private fun LocalDate.utcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.utcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

@OptIn(ExperimentalMaterial3Api::class)
private fun bounded(first: LocalDate, last: LocalDate) = object : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis in first.utcMillis()..last.utcMillis()
    override fun isSelectableYear(year: Int): Boolean = year in first.year..last.year
}

/** Single date picker limited to [first]..[last] (Flutter `showDatePicker`). Dismiss → [onDismiss]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoundedDatePickerDialog(
    initial: LocalDate,
    first: LocalDate,
    last: LocalDate,
    onDismiss: () -> Unit,
    onPicked: (LocalDate) -> Unit,
) {
    val compact = LocalConfiguration.current.screenHeightDp < COMPACT_PICKER_HEIGHT_DP
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.coerceIn(first, last).utcMillis(),
        yearRange = first.year..last.year,
        initialDisplayMode = if (compact) DisplayMode.Input else DisplayMode.Picker,
        selectableDates = bounded(first, last),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let { onPicked(it.utcDate()) } ?: onDismiss() }) {
                Text(stringResource(L10nR.string.actionOk))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(L10nR.string.actionCancel)) } },
    ) {
        DatePicker(state = state, showModeToggle = true)
    }
}

/** Date range picker limited to [first]..[last] (Flutter `showDateRangePicker`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoundedDateRangePickerDialog(
    initialFrom: LocalDate?,
    initialTo: LocalDate?,
    first: LocalDate,
    last: LocalDate,
    onDismiss: () -> Unit,
    onPicked: (LocalDate, LocalDate) -> Unit,
) {
    val compact = LocalConfiguration.current.screenHeightDp < COMPACT_PICKER_HEIGHT_DP
    val both = initialFrom != null && initialTo != null
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = if (both) initialFrom!!.coerceIn(first, last).utcMillis() else null,
        initialSelectedEndDateMillis = if (both) initialTo!!.coerceIn(first, last).utcMillis() else null,
        yearRange = first.year..last.year,
        initialDisplayMode = if (compact) DisplayMode.Input else DisplayMode.Picker,
        selectableDates = bounded(first, last),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null,
                onClick = {
                    val from = state.selectedStartDateMillis?.utcDate()
                    if (from == null) {
                        onDismiss()
                    } else {
                        onPicked(from, state.selectedEndDateMillis?.utcDate() ?: from)
                    }
                },
            ) { Text(stringResource(L10nR.string.actionOk)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(L10nR.string.actionCancel)) } },
    ) {
        DateRangePicker(state = state, showModeToggle = true, modifier = Modifier.heightIn(max = 560.dp))
    }
}

/**
 * 24 h time picker (M3 `TimePickerDialog` with title and dial/keyboard toggle; keyboard input on
 * short windows). Dismiss/cancel reports `null` (the portal editor then uses 19:00).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimePickerDialog(initial: LocalTime, onDone: (LocalTime?) -> Unit) {
    val compact = LocalConfiguration.current.screenHeightDp < COMPACT_PICKER_HEIGHT_DP
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    var displayMode by rememberSaveable(stateSaver = DisplayModeSaver) {
        mutableStateOf(if (compact) TimePickerDisplayMode.Input else TimePickerDisplayMode.Picker)
    }
    androidx.compose.material3.TimePickerDialog(
        onDismissRequest = { onDone(null) },
        confirmButton = {
            TextButton(onClick = { onDone(LocalTime.of(state.hour, state.minute)) }) {
                Text(stringResource(L10nR.string.actionOk))
            }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(L10nR.string.actionCancel)) } },
        title = { TimePickerDialogDefaults.Title(displayMode = displayMode) },
        modeToggleButton = {
            TimePickerDialogDefaults.DisplayModeToggle(
                onDisplayModeChange = {
                    displayMode = if (displayMode == TimePickerDisplayMode.Picker) {
                        TimePickerDisplayMode.Input
                    } else {
                        TimePickerDisplayMode.Picker
                    }
                },
                displayMode = displayMode,
            )
        },
    ) {
        if (displayMode == TimePickerDisplayMode.Input) TimeInput(state = state) else TimePicker(state = state)
    }
}

private val DisplayModeSaver = Saver<TimePickerDisplayMode, Boolean>(
    save = { it == TimePickerDisplayMode.Input },
    restore = { if (it) TimePickerDisplayMode.Input else TimePickerDisplayMode.Picker },
)
