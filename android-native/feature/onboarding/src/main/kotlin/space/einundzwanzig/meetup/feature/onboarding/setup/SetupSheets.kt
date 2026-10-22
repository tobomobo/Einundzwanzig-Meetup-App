package space.einundzwanzig.meetup.feature.onboarding.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.meetups.FavoriteMigration
import space.einundzwanzig.meetup.data.meetups.MeetupSearchRows
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.feature.onboarding.R

/**
 * "Secure your identity" sheet (spec 01 §2.3.7): not dismissible by swipe, back or outside tap,
 * only its buttons close it. Step 1 = backup file, step 2 = ncryptsec file / clipboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SecureIdentitySheet(state: SecureSheetState, onAction: (SetupAction) -> Unit) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden },
    )
    ModalBottomSheet(
        onDismissRequest = {},
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = null,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false, shouldDismissOnClickOutside = false),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        SecureIdentitySheetContent(state, onAction)
    }
}

/** Sheet body, separate for tests and previews. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SecureIdentitySheetContent(state: SecureSheetState, onAction: (SetupAction) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.extendedColors.textTertiary
    val step2 = state.step == 2
    val progress = stringResource(R.string.native_onboarding_secure_progress, state.step, 2)
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 24.dp)
            .testTag(SetupTags.SECURE_SHEET),
    ) {
        AnimatedContent(targetState = state.step, label = "secureStep") { step ->
            val isStep2 = step == 2
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (isStep2) Icons.Rounded.Key else Icons.Rounded.SaveAlt, contentDescription = null, tint = primary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(if (isStep2) L10nR.string.idSetupSecureKeyTitle else L10nR.string.idSetupSecureBackupTitle),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "$step/2",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = tertiary,
                        modifier = Modifier.semantics { contentDescription = progress },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(if (isStep2) L10nR.string.idSetupSecureKeyBody else L10nR.string.idSetupSecureBackupBody),
                    fontSize = 13.5.sp,
                    lineHeight = 19.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                if (!isStep2) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(primary.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                            .padding(12.dp),
                    ) {
                        Icon(Icons.Rounded.Update, contentDescription = null, tint = primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(L10nR.string.idSetupSecureRepeat), fontSize = 12.sp, lineHeight = 17.sp)
                    }
                } else {
                    Row {
                        Icon(Icons.Rounded.Info, contentDescription = null, tint = tertiary, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(L10nR.string.idSetupSecureWhere), fontSize = 11.5.sp, lineHeight = 16.sp, color = tertiary)
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { onAction(if (step2) SetupAction.SecureSaveFile else SetupAction.SecureCreateBackup) },
            enabled = !state.busy,
            shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.ContentPadding,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(SetupTags.SECURE_PRIMARY),
        ) {
            Icon(Icons.Rounded.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(if (step2) L10nR.string.idSetupSecureKeySave else L10nR.string.idSetupSecureBackup),
                fontWeight = FontWeight.ExtraBold,
            )
        }
        if (state.busy) {
            Spacer(Modifier.height(8.dp))
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (step2) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onAction(SetupAction.SecureCopy) },
                enabled = !state.busy,
                shapes = ButtonDefaults.shapes(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag(SetupTags.SECURE_COPY),
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(L10nR.string.idSetupSecureCopy))
            }
        }
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = { onAction(if (step2) SetupAction.SecureLater else SetupAction.SecureSkip) },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(SetupTags.SECURE_SKIP),
        ) {
            Text(
                stringResource(if (step2) L10nR.string.idSetupBackupLater else L10nR.string.idSetupSecureSkip),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Picker helpers; row filter/title/subtitle come from [MeetupSearchRows] (shared with profile). */
internal object MeetupPickerLogic {
    /** Toggle by ID, keeping the order (first = home meetup). */
    fun toggle(selected: List<String>, id: String): List<String> =
        if (id in selected) selected - id else selected + id

    /** Display label of the selected IDs (Flutter joined the raw values; IDs are unreadable). */
    fun label(selected: List<String>, all: List<Meetup>): String =
        selected.joinToString(", ") { id -> all.firstOrNull { it.id == id }?.let { MeetupSearchRows.title(it, all) } ?: id }
}

/** MeetupSearchSheet (spec 09 §2.5): search, star toggles, "N ★" hands the list back; drag/back discards. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MeetupPickerSheet(
    meetups: List<Meetup>,
    initialSelected: List<String>,
    onDone: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        MeetupPickerContent(meetups, initialSelected, onDone, Modifier.fillMaxHeight(0.9f))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MeetupPickerContent(
    meetups: List<Meetup>,
    initialSelected: List<String>,
    onDone: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by rememberSaveable(initialSelected) { mutableStateOf(FavoriteMigration.migrate(initialSelected, meetups).ids) }
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(meetups, query) { MeetupSearchRows.filter(meetups, query) }
    val primary = MaterialTheme.colorScheme.primary
    Column(modifier.padding(horizontal = 16.dp).imePadding()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(stringResource(L10nR.string.profileSearchCity)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().testTag(SetupTags.MEETUP_SEARCH),
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(filtered, key = { it.id }) { meetup ->
                val active = meetup.id in selected
                val favLabel = stringResource(if (active) R.string.native_onboarding_meetup_favorite else R.string.native_onboarding_meetup_not_favorite)
                ListItem(
                    headlineContent = {
                        Text(
                            MeetupSearchRows.title(meetup, meetups),
                            color = if (active) primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    supportingContent = MeetupSearchRows.subtitle(meetup)?.let { { Text(it) } },
                    trailingContent = {
                        Icon(
                            if (active) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            contentDescription = favLabel,
                            tint = if (active) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier
                        .animateItem()
                        .semantics { this.selected = active }
                        .toggleable(value = active, role = Role.Checkbox) { selected = MeetupPickerLogic.toggle(selected, meetup.id) }
                        .testTag(SetupTags.meetupRow(meetup.id)),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { onDone(selected) },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .padding(vertical = 4.dp)
                    .navigationBarsPadding()
                    .testTag(SetupTags.MEETUP_DONE),
            ) {
                Text("${selected.size} ★", fontWeight = FontWeight.Bold)
            }
        }
    }
}
