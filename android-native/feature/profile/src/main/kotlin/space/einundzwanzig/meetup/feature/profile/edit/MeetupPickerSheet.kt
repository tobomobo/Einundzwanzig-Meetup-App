package space.einundzwanzig.meetup.feature.profile.edit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.toggleable
import space.einundzwanzig.meetup.core.l10n.R as L10nR
import space.einundzwanzig.meetup.data.meetups.FavoriteMigration
import space.einundzwanzig.meetup.data.meetups.MeetupSearchRows
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.feature.profile.R

/** Row logic of the MeetupSearchSheet (spec 09 §2.5); filter/title/subtitle live in `:data:meetups`. */
internal object MeetupPickerLogic {
    /** Filter on `city` or `name` contains, case-insensitive with Dart case mapping. */
    fun filter(meetups: List<Meetup>, query: String): List<Meetup> = MeetupSearchRows.filter(meetups, query)

    /** Title = name when several meetups share the city (and the name is set), else the city. */
    fun title(meetup: Meetup, all: List<Meetup>): String = MeetupSearchRows.title(meetup, all)

    /** Subtitle = name if it is set and differs from the city. */
    fun subtitle(meetup: Meetup): String? = MeetupSearchRows.subtitle(meetup)

    /** Toggle by ID, keeping the selection order (first = home meetup). */
    fun toggle(selected: List<String>, id: String): List<String> =
        if (id in selected) selected - id else selected + id
}

/**
 * Favourites / home-meetup picker (MeetupSearchSheet, spec 09 §2.5). Dismissing by drag or back
 * discards the changes; only the "N ★" button hands the list back.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MeetupPickerSheet(
    meetups: List<Meetup>,
    initialSelected: List<String>,
    onDone: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by rememberSaveable(initialSelected) {
        mutableStateOf(FavoriteMigration.migrate(initialSelected, meetups).ids)
    }
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(meetups, query) { MeetupPickerLogic.filter(meetups, query) }
    val focus = remember { FocusRequester() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(Modifier.fillMaxHeight(0.9f).padding(horizontal = 16.dp).imePadding()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(L10nR.string.profileSearchCity)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(ProfileEditTags.MEETUP_SEARCH),
            )
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(filtered, key = { it.id }) { meetup ->
                    val active = meetup.id in selected
                    val favLabel = stringResource(if (active) R.string.native_meetup_favorite else R.string.native_meetup_not_favorite)
                    ListItem(
                        headlineContent = {
                            Text(
                                MeetupPickerLogic.title(meetup, meetups),
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        supportingContent = MeetupPickerLogic.subtitle(meetup)?.let { { Text(it) } },
                        trailingContent = {
                            Icon(
                                if (active) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                contentDescription = favLabel,
                                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier
                            .animateItem()
                            .semantics { this.selected = active }
                            .toggleable(value = active, role = Role.Checkbox) { selected = MeetupPickerLogic.toggle(selected, meetup.id) }
                            .testTag(ProfileEditTags.meetupRow(meetup.id)),
                    )
                }
            }
            Button(
                onClick = { onDone(selected) },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .padding(vertical = 4.dp)
                    .navigationBarsPadding()
                    .testTag(ProfileEditTags.MEETUP_DONE),
            ) {
                Text("${selected.size} ★", fontWeight = FontWeight.Bold)
            }
        }
    }
}
