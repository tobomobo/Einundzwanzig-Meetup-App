package space.einundzwanzig.meetup.feature.community.glossary

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MilitaryTech
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.ui.component.AppLargeTopBar
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.community.glossary.Glossary
import space.einundzwanzig.meetup.data.community.glossary.GlossaryCategory
import space.einundzwanzig.meetup.data.community.glossary.ResolvedGlossaryEntry
import space.einundzwanzig.meetup.feature.community.R
import space.einundzwanzig.meetup.core.l10n.R as L10nR

internal object GlossaryTestTags {
    const val SEARCH = "glossarySearch"
    const val LIST = "glossaryList"
    const val NO_RESULTS = "glossaryNoResults"
    fun entry(title: String) = "glossaryEntry:$title"
}

@Composable
fun GlossaryRoute(viewModel: GlossaryViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    // Search runs on the localized strings; re-resolve when the locale changes.
    val entries = remember(configuration) { Glossary.resolve(context::getString) }
    GlossaryScreen(state = state, entries = entries, onAction = viewModel::onAction, onBack = navigator::back)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlossaryScreen(
    state: GlossaryUiState,
    entries: List<ResolvedGlossaryEntry>,
    onAction: (GlossaryAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val sections = remember(entries, state.query) { state.sections(entries) }
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppLargeTopBar(
                title = stringResource(L10nR.string.glTitle),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            SearchField(
                query = state.query,
                onQueryChange = { onAction(GlossaryAction.QueryChanged(it)) },
                onClear = { onAction(GlossaryAction.ClearQuery) },
            )
            if (sections.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp).testTag(GlossaryTestTags.NO_RESULTS), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(L10nR.string.glNoResults),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag(GlossaryTestTags.LIST),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                ) {
                    sections.forEach { (category, hits) ->
                        item(key = "cat-${category.name}") { CategoryHeader(category) }
                        items(hits, key = { "entry-${it.entry.titleRes}" }) { hit ->
                            GlossaryCard(
                                entry = hit,
                                expanded = state.isExpanded(hit.title),
                                onToggle = { onAction(GlossaryAction.Toggle(hit.title, it)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, onClear: () -> Unit) {
    val container = MaterialTheme.colorScheme.surfaceContainer
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(stringResource(L10nR.string.glSearchHint)) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Rounded.Clear, contentDescription = stringResource(R.string.native_community_clear_search))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = RoundedCornerShape(28.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
            .testTag(GlossaryTestTags.SEARCH),
    )
}

@Composable
private fun CategoryHeader(category: GlossaryCategory) {
    val orange = MaterialTheme.colorScheme.primary
    Row(
        Modifier.padding(start = 4.dp, top = 14.dp, end = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(category.icon(), contentDescription = null, tint = orange, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(category.labelRes).uppercase(),
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.1.sp),
            color = orange,
        )
    }
}

@Composable
private fun GlossaryCard(entry: ResolvedGlossaryEntry, expanded: Boolean, onToggle: (Boolean) -> Unit) {
    val orange = MaterialTheme.colorScheme.primary
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Surface(
        onClick = { onToggle(!expanded) },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .testTag(GlossaryTestTags.entry(entry.title))
            .semantics {
                if (expanded) {
                    collapse { onToggle(false); true }
                } else {
                    expand { onToggle(true); true }
                }
            },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.title,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.5.sp, fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = if (expanded) orange else MaterialTheme.extendedColors.textTertiary,
                    modifier = Modifier.rotate(rotation),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Text(
                    entry.body,
                    modifier = Modifier.padding(bottom = 12.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 13.5.sp, lineHeight = (13.5 * 1.55).sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Category icons (spec 11 §2.12). */
internal fun GlossaryCategory.icon(): ImageVector = when (this) {
    GlossaryCategory.Start -> Icons.Rounded.Flag
    GlossaryCategory.Badges -> Icons.Rounded.MilitaryTech
    GlossaryCategory.Reputation -> Icons.Rounded.WorkspacePremium
    GlossaryCategory.Network -> Icons.Rounded.Hub
    GlossaryCategory.Identity -> Icons.Rounded.Key
    GlossaryCategory.Events -> Icons.Rounded.Celebration
    GlossaryCategory.Nostr -> Icons.Rounded.Bolt
    GlossaryCategory.App -> Icons.Rounded.Tune
}

@Preview
@Composable
private fun GlossaryScreenPreview() {
    EinundzwanzigTheme {
        val context = LocalContext.current
        GlossaryScreen(
            state = GlossaryUiState(),
            entries = Glossary.resolve(context::getString),
            onAction = {},
            onBack = {},
        )
    }
}
