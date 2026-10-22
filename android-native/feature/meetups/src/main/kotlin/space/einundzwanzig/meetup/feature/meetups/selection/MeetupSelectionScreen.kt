package space.einundzwanzig.meetup.feature.meetups.selection

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.ui.component.AppLargeTopBar
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.EmptyState
import space.einundzwanzig.meetup.core.ui.component.FullScreenLoading
import space.einundzwanzig.meetup.core.ui.motion.ExpressiveSprings
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.meetups.model.Meetup
import space.einundzwanzig.meetup.feature.meetups.R
import space.einundzwanzig.meetup.feature.meetups.common.CollectMessages
import space.einundzwanzig.meetup.feature.meetups.common.IconTile
import space.einundzwanzig.meetup.core.l10n.R as L10nR

internal object MeetupSelectionTags {
    const val SEARCH = "selection_search"
    const val SAVE = "selection_save"
    const val COUNT = "selection_count"
    fun row(id: String) = "selection_row_$id"
}

/** Stateful "MEETUP AUSWÄHLEN" (spec 09 §2.4). */
@Composable
fun MeetupSelectionRoute(viewModel: MeetupSelectionViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(viewModel.messages, snackbar)
    LaunchedEffect(viewModel) { viewModel.saved.collect { navigator.back() } }
    MeetupSelectionScreen(state, snackbar) { action ->
        if (action == MeetupSelectionAction.Back) navigator.back() else viewModel.onAction(action)
    }
}

/** Stateless "MEETUP AUSWÄHLEN". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetupSelectionScreen(
    state: MeetupSelectionUiState,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    onAction: (MeetupSelectionAction) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        topBar = {
            AppLargeTopBar(
                title = stringResource(L10nR.string.msSelectMeetup),
                onBack = { onAction(MeetupSelectionAction.Back) },
                scrollBehavior = scrollBehavior,
                actions = {
                    if (!state.loading) {
                        Text(
                            state.selected.size.toString(),
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .testTag(MeetupSelectionTags.COUNT),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (!state.loading && !state.loadFailed) SaveBar(state, onAction)
        },
    ) { padding ->
        when {
            state.loading -> FullScreenLoading(Modifier.padding(padding))
            state.loadFailed -> EmptyState(
                icon = Icons.Rounded.CloudOff,
                title = stringResource(R.string.native_meetups_load_failed),
                subtitle = stringResource(R.string.native_meetups_load_failed_sub),
                actionLabel = stringResource(L10nR.string.actionRetry),
                onAction = { onAction(MeetupSelectionAction.Retry) },
                modifier = Modifier.padding(padding),
            )
            else -> MeetupList(state, onAction, Modifier.padding(padding))
        }
    }
}

@Composable
private fun MeetupList(state: MeetupSelectionUiState, onAction: (MeetupSelectionAction) -> Unit, modifier: Modifier) {
    val selected = remember(state.selected) { state.selected.toHashSet() }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "search") {
            OutlinedTextField(
                value = state.query,
                onValueChange = { onAction(MeetupSelectionAction.QueryChanged(it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(MeetupSelectionTags.SEARCH),
                placeholder = { Text(stringResource(L10nR.string.msSearchMeetup)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onAction(MeetupSelectionAction.QueryChanged("")) }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.native_meetups_clear_search))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
        }
        item(key = "hint") {
            Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(L10nR.string.msFavoritesHint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.visible, key = { "m-${it.id}" }) { m ->
            MeetupRow(m, m.id in selected, Modifier.animateItem()) { onAction(MeetupSelectionAction.Toggle(m.id)) }
        }
    }
}

@Composable
private fun MeetupRow(meetup: Meetup, active: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val border by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        label = "selBorder",
    )
    val starScale by animateFloatAsState(if (active) 1.15f else 1f, ExpressiveSprings.fastSpatial(), label = "star")
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .border(if (active) 1.4.dp else 1.dp, border, shape)
            .toggleable(value = active, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(12.dp)
            .testTag(MeetupSelectionTags.row(meetup.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(
            Icons.Rounded.LocationOn,
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                meetup.city.uppercase(),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            meetup.groupName?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(
            if (active) Icons.Rounded.Star else Icons.Rounded.StarOutline,
            contentDescription = stringResource(if (active) R.string.native_meetups_favorite_on else R.string.native_meetups_favorite_off),
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.extendedColors.textTertiary,
            modifier = Modifier.scale(starScale),
        )
    }
}

@Composable
private fun SaveBar(state: MeetupSelectionUiState, onAction: (MeetupSelectionAction) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            Button(
                onClick = { onAction(MeetupSelectionAction.Save) },
                enabled = state.canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag(MeetupSelectionTags.SAVE),
            ) {
                if (state.saving) {
                    AppLoadingIndicator(size = 22.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    val n = state.selected.size
                    Text(
                        if (n == 0) stringResource(L10nR.string.msSaveNone) else stringResource(L10nR.string.msSaveFavorites, n),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun MeetupSelectionPreview() {
    EinundzwanzigTheme {
        MeetupSelectionScreen(
            MeetupSelectionUiState(
                loading = false,
                meetups = listOf(
                    Meetup("107", "Einundzwanzig Südniedersachsen", "Göttingen", "DE", "", 51.54, 9.92),
                    Meetup("5", "Würzburg Walk", "Würzburg", "DE", "", 49.79, 9.95),
                    Meetup("6", "", "Wien", "AT", "", 48.2, 16.37),
                ),
                selected = listOf("107"),
            ),
            SnackbarHostState(),
        ) {}
    }
}
