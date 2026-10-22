package space.einundzwanzig.meetup.feature.events.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.MilitaryTech
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.ui.component.ConfirmDialog
import space.einundzwanzig.meetup.core.ui.component.StatusChip
import space.einundzwanzig.meetup.core.ui.nostr.NpubChip
import space.einundzwanzig.meetup.core.ui.theme.MonoStyle
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.calendar.model.NostrCalendarEvent
import space.einundzwanzig.meetup.data.calendar.model.RsvpStatus
import space.einundzwanzig.meetup.feature.events.R
import space.einundzwanzig.meetup.feature.events.common.InlineLoading
import space.einundzwanzig.meetup.feature.events.common.LinkRow
import space.einundzwanzig.meetup.feature.events.common.SheetActionButton
import space.einundzwanzig.meetup.feature.events.common.TypeDot
import java.time.ZoneId

/** Test tags of the event details sheet. */
internal object CalendarSheetTags {
    const val SESSION_BUTTON = "eventCalendar.sheet.session"
    const val CANCEL_BUTTON = "eventCalendar.sheet.cancel"
    const val RSVP_YES = "eventCalendar.sheet.rsvpYes"
    const val RSVP_NO = "eventCalendar.sheet.rsvpNo"
    const val CHAT = "eventCalendar.sheet.chat"
    const val HELPER_INPUT = "eventCalendar.helpers.input"
    const val HELPER_ADD = "eventCalendar.helpers.add"
    const val HELPER_SAVE = "eventCalendar.helpers.save"
}

/** Content of the event details sheet (spec 10 §2.3 "Details sheet"). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ColumnScope.CalendarDetailsContent(
    details: CalendarItemDetails,
    zone: ZoneId,
    startingSession: Boolean,
    cancelling: Boolean,
    onAction: (EventCalendarAction) -> Unit,
) {
    val item = details.item
    val nostr = item.nostr
    val color = item.color()
    Row(verticalAlignment = Alignment.CenterVertically) {
        TypeDot(color, size = 10.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            item.title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
        )
        Spacer(Modifier.width(8.dp))
        StatusChip(
            label = stringResource(
                when {
                    item.isCourse -> L10nR.string.calLegendCourse
                    item.isMeetup -> L10nR.string.calLegendMeetup
                    else -> L10nR.string.calLegendEvent
                },
            ),
            color = color,
        )
    }
    Spacer(Modifier.height(12.dp))
    IconLine(Icons.Rounded.Schedule, fmtWhen(item, showDate = true, zone = zone), iconSize = 16.dp, maxLines = 2)
    if (item.location.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        IconLine(Icons.Rounded.Place, item.location, iconSize = 16.dp, maxLines = 3)
    }
    if (item.description.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(item.description, style = MaterialTheme.typography.bodyLarge, fontSize = 14.sp)
    }
    if (nostr != null && item.hasBadge) {
        Spacer(Modifier.height(14.dp))
        BadgeNotice(details, nostr, startingSession, onAction)
        Spacer(Modifier.height(10.dp))
        IssuerList(nostr, details.isCreator, onAction)
    }
    if (nostr != null && details.isCreator) {
        Spacer(Modifier.height(12.dp))
        val error = MaterialTheme.colorScheme.error
        OutlinedButton(
            onClick = { onAction(EventCalendarAction.RequestCancel) },
            enabled = !cancelling,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(CalendarSheetTags.CANCEL_BUTTON),
            border = BorderStroke(1.dp, error.copy(alpha = 0.7f)),
        ) {
            if (cancelling) InlineLoading(18.dp, error) else Icon(Icons.Rounded.EventBusy, null, tint = error)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(L10nR.string.evCancelAction), color = error, fontWeight = FontWeight.Bold)
        }
    }
    if (nostr != null) {
        Spacer(Modifier.height(12.dp))
        NostrRsvpRow(details.rsvp, details.rsvpBusy, onAction)
        Spacer(Modifier.height(10.dp))
        val purple = MaterialTheme.extendedColors.nostr
        OutlinedButton(
            onClick = { onAction(EventCalendarAction.OpenChat(nostr)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(CalendarSheetTags.CHAT),
            border = BorderStroke(1.dp, purple.copy(alpha = 0.7f)),
        ) {
            Icon(Icons.Rounded.Forum, null, tint = purple)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(L10nR.string.chatEventOpen), color = purple, fontWeight = FontWeight.Bold)
        }
    }
    if (details.links.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            details.links.forEach { url -> LinkRow(url, onClick = { onAction(EventCalendarAction.OpenUrl(url)) }) }
        }
    }
    Spacer(Modifier.height(6.dp))
    SheetActionButton(Icons.Rounded.EventAvailable, L10nR.string.evToCalendar, { onAction(EventCalendarAction.AddToCalendar(item)) })
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BadgeNotice(
    details: CalendarItemDetails,
    event: NostrCalendarEvent,
    startingSession: Boolean,
    onAction: (EventCalendarAction) -> Unit,
) {
    val orange = MaterialTheme.colorScheme.primary
    val issuer = details.iAmIssuer
    val noLocation = event.hasNoLocation
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(orange.copy(alpha = if (issuer) 0.12f else 0.06f), shape)
            .border(1.dp, orange.copy(alpha = if (issuer) 0.5f else 0.25f), shape)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.MilitaryTech, contentDescription = null, tint = orange)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (issuer) L10nR.string.evBadgeYouIssue else L10nR.string.evBadgeAvailable),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(
                        when {
                            noLocation -> L10nR.string.evBadgeNoLocationSet
                            issuer -> L10nR.string.evBadgeYouIssueSub
                            else -> L10nR.string.evBadgeAvailableSub
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (noLocation) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (details.showSessionButton) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onAction(EventCalendarAction.StartSession) },
                enabled = !startingSession,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(CalendarSheetTags.SESSION_BUTTON),
                colors = ButtonDefaults.buttonColors(
                    containerColor = orange,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = orange.copy(alpha = 0.75f),
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                if (startingSession) {
                    InlineLoading(16.dp, MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Rounded.QrCode2, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        when {
                            startingSession -> L10nR.string.evBadgeLocating
                            details.sessionRunning -> L10nR.string.evBadgeShowSession
                            else -> L10nR.string.evBadgeStartSession
                        },
                    ),
                    fontWeight = FontWeight.Bold,
                )
            }
            if (startingSession) {
                Spacer(Modifier.height(10.dp))
                LinearWavyProgressIndicator(Modifier.fillMaxWidth(), color = orange)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(L10nR.string.evBadgeLocatingHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun IssuerList(event: NostrCalendarEvent, isCreator: Boolean, onAction: (EventCalendarAction) -> Unit) {
    val creator = event.pubkey
    val helpers = event.issuers.filter { it.lowercase() != creator.lowercase() }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(L10nR.string.evIssuersTitle).uppercase(),
            fontSize = 10.5.sp,
            letterSpacing = 1.2.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.extendedColors.textTertiary,
        )
        IssuerRow(Icons.Rounded.Star, MaterialTheme.colorScheme.primary, creator, stringResource(L10nR.string.evIssuerCreator))
        helpers.forEach { IssuerRow(Icons.Rounded.Person, MaterialTheme.colorScheme.onSurfaceVariant, it, null) }
        if (isCreator) {
            TextButton(onClick = { onAction(EventCalendarAction.OpenHelperEditor) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(L10nR.string.evIssuersEdit))
            }
        }
    }
}

@Composable
private fun IssuerRow(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, hex: String, label: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        NpubChip(npubOf(hex), showIcon = false, modifier = Modifier.weight(1f, fill = false))
        if (label != null) {
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extendedColors.textTertiary)
        }
    }
}

/** hex → npub; the raw value when it does not encode (never crash on relay data). */
internal fun npubOf(hex: String): String = runCatching { Nip19.encodeNpub(hex) }.getOrDefault(hex)

@Composable
private fun NostrRsvpRow(current: RsvpStatus?, busy: Boolean, onAction: (EventCalendarAction) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RsvpButton(
            label = stringResource(L10nR.string.rsvpYes),
            icon = Icons.Rounded.Check,
            active = current == RsvpStatus.ACCEPTED,
            activeColor = MaterialTheme.extendedColors.success,
            enabled = !busy,
            onClick = { onAction(EventCalendarAction.SetRsvp(RsvpStatus.ACCEPTED)) },
            modifier = Modifier.weight(1f).testTag(CalendarSheetTags.RSVP_YES),
        )
        RsvpButton(
            label = stringResource(L10nR.string.rsvpNo),
            icon = Icons.Rounded.Close,
            active = current == RsvpStatus.DECLINED,
            activeColor = MaterialTheme.colorScheme.onSurfaceVariant,
            enabled = !busy,
            onClick = { onAction(EventCalendarAction.SetRsvp(RsvpStatus.DECLINED)) },
            modifier = Modifier.weight(1f).testTag(CalendarSheetTags.RSVP_NO),
        )
    }
}

@Composable
private fun RsvpButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    activeColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (active) activeColor else Color.Transparent
    val fg = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        border = BorderStroke(0.5.dp, if (active) activeColor else MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = bg,
            contentColor = fg,
            disabledContainerColor = bg.copy(alpha = if (active) 0.6f else 0f),
            disabledContentColor = fg.copy(alpha = 0.6f),
        ),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold)
    }
}

/** "Termin absagen?" confirmation. */
@Composable
fun CancelEventDialog(title: String, onAction: (EventCalendarAction) -> Unit) {
    ConfirmDialog(
        title = stringResource(L10nR.string.evCancelTitle),
        text = stringResource(L10nR.string.evCancelBody, title),
        confirmLabel = stringResource(L10nR.string.evCancelConfirm),
        onConfirm = { onAction(EventCalendarAction.ConfirmCancel) },
        onDismiss = { onAction(EventCalendarAction.DismissCancel) },
        destructive = true,
        icon = Icons.Rounded.EventBusy,
    )
}

/** "Helfer bearbeiten" bottom sheet (spec 10 §2.3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelperEditorSheet(editor: HelperEditorState, onAction: (EventCalendarAction) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = { onAction(EventCalendarAction.CloseHelperEditor) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        HelperEditorContent(editor, onAction)
    }
}

/** Body of [HelperEditorSheet] (separate for UI tests). */
@Composable
fun HelperEditorContent(editor: HelperEditorState, onAction: (EventCalendarAction) -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(L10nR.string.evIssuersEdit), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(L10nR.string.evIssuersEditHint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (editor.helpers.isEmpty()) {
            Text(
                stringResource(L10nR.string.evIssuersNone),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.extendedColors.textTertiary,
            )
        }
        editor.helpers.forEach { hex ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                NpubChip(npubOf(hex), showIcon = false, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onAction(EventCalendarAction.RemoveHelper(hex)) }, enabled = !editor.saving) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.native_events_remove_helper),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                value = editor.input,
                onValueChange = { onAction(EventCalendarAction.HelperInput(it)) },
                modifier = Modifier.weight(1f).testTag(CalendarSheetTags.HELPER_INPUT),
                textStyle = MonoStyle,
                singleLine = true,
                enabled = !editor.saving,
                placeholder = { Text(stringResource(R.string.native_events_npub_hint), style = MonoStyle) },
                isError = editor.error != null,
                supportingText = editor.error?.let { e -> { Text(e.resolve(context)) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAction(EventCalendarAction.AddHelper) }),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = { onAction(EventCalendarAction.AddHelper) },
                enabled = !editor.saving,
                modifier = Modifier.padding(top = 4.dp).size(52.dp).testTag(CalendarSheetTags.HELPER_ADD),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.native_events_add_helper))
            }
        }
        Button(
            onClick = { onAction(EventCalendarAction.SaveHelpers) },
            enabled = !editor.saving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(CalendarSheetTags.HELPER_SAVE),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            if (editor.saving) {
                InlineLoading(20.dp, MaterialTheme.colorScheme.onPrimary)
            } else {
                Text(stringResource(L10nR.string.evIssuersSave), fontWeight = FontWeight.Bold)
            }
        }
    }
}
