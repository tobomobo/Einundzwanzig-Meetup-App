package space.einundzwanzig.meetup.feature.community.courses

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.einundzwanzig.meetup.core.navigation.CourseDetails
import space.einundzwanzig.meetup.core.navigation.LecturerDetails
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.ui.component.AppMediumTopBar
import space.einundzwanzig.meetup.core.ui.component.FullScreenLoading
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.feature.community.common.AccentCard
import space.einundzwanzig.meetup.feature.community.common.CardRow
import space.einundzwanzig.meetup.feature.community.common.IconBox
import space.einundzwanzig.meetup.feature.community.common.LinkRow
import space.einundzwanzig.meetup.feature.community.common.PortalThumb
import space.einundzwanzig.meetup.feature.community.common.RowChevron
import space.einundzwanzig.meetup.core.l10n.R as L10nR

@Composable
fun CoursesRoute(viewModel: CoursesViewModel = hiltViewModel()) {
    val navigator = LocalNavigator.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    CoursesScreen(
        state = state,
        onBack = navigator::back,
        onAction = { action ->
            when (action) {
                is CoursesAction.OpenUrl -> navigator.openUrl(action.url)
                is CoursesAction.OpenItem -> {
                    val id = action.item.id
                    when {
                        id == null -> viewModel.onAction(action)
                        action.tab == CoursesTab.Courses -> navigator.navigate(CourseDetails(id))
                        else -> navigator.navigate(LecturerDetails(id))
                    }
                }
                else -> viewModel.onAction(action)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CoursesScreen(
    state: CoursesUiState,
    onAction: (CoursesAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // One scroll position per tab (Flutter TabBarView keeps them separately).
    val coursesListState = rememberLazyListState()
    val lecturersListState = rememberLazyListState()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppMediumTopBar(
                title = stringResource(L10nR.string.paCourses),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabToggle(selected = state.tab, onSelect = { onAction(CoursesAction.SelectTab(it)) })
            if (state.loading) {
                FullScreenLoading(message = stringResource(L10nR.string.crsLoading))
            } else {
                val items = if (state.tab == CoursesTab.Courses) state.courses else state.lecturers
                val pullState = rememberPullToRefreshState()
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = { onAction(CoursesAction.Refresh) },
                    state = pullState,
                    modifier = Modifier.fillMaxSize(),
                    indicator = {
                        PullToRefreshDefaults.LoadingIndicator(
                            state = pullState,
                            isRefreshing = state.refreshing,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    },
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("coursesList"),
                        state = if (state.tab == CoursesTab.Courses) coursesListState else lecturersListState,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (items.isEmpty()) {
                            item(key = "empty") { CoursesEmptyCard() }
                        } else {
                            itemsIndexed(items, key = { i, it -> "${state.tab}-$i-${it.id}" }) { _, item ->
                                PortalItemCard(
                                    item = item,
                                    icon = if (state.tab == CoursesTab.Courses) Icons.Rounded.School else Icons.Rounded.Person,
                                    onClick = { onAction(CoursesAction.OpenItem(state.tab, item)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    state.sheet?.let { (tab, item) ->
        PortalItemSheet(
            item = item,
            icon = if (tab == CoursesTab.Courses) Icons.Rounded.School else Icons.Rounded.Person,
            onOpenUrl = { onAction(CoursesAction.OpenUrl(it)) },
            onDismiss = { onAction(CoursesAction.DismissSheet) },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TabToggle(selected: CoursesTab, onSelect: (CoursesTab) -> Unit) {
    val labels = listOf(
        CoursesTab.Courses to stringResource(L10nR.string.crsCourses),
        CoursesTab.Lecturers to stringResource(L10nR.string.crsLecturers),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        labels.forEachIndexed { index, (tab, label) ->
            ToggleButton(
                checked = selected == tab,
                onCheckedChange = { onSelect(tab) },
                modifier = Modifier.weight(1f),
                shapes = if (index == 0) {
                    ButtonGroupDefaults.connectedLeadingButtonShapes()
                } else {
                    ButtonGroupDefaults.connectedTrailingButtonShapes()
                },
            ) {
                Icon(
                    if (tab == CoursesTab.Courses) Icons.Rounded.School else Icons.Rounded.Person,
                    contentDescription = null,
                    modifier = Modifier.size(ToggleButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
                Text(label)
            }
        }
    }
}

@Composable
private fun CoursesEmptyCard() {
    CardRow(onClick = null, modifier = Modifier.padding(top = 16.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconBox(Icons.Rounded.School, MaterialTheme.extendedColors.nostr, size = 56.dp, iconSize = 28.dp)
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(L10nR.string.crsNone),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PortalItemCard(item: PortalListItem, icon: ImageVector, onClick: () -> Unit) {
    val accent = MaterialTheme.extendedColors.nostr
    AccentCard(accent = accent, onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            PortalThumb(url = item.image, icon = icon, color = accent, size = 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (item.description.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        item.description,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            RowChevron()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortalItemSheet(
    item: PortalListItem,
    icon: ImageVector,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
                .testTag("portalItemSheet"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBox(icon, MaterialTheme.extendedColors.nostr)
                Spacer(Modifier.width(12.dp))
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                item.description.ifEmpty { "—" },
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.links.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                item.links.forEach { url -> LinkRow(label = url, icon = Icons.Rounded.Link, onClick = { onOpenUrl(url) }) }
            }
        }
    }
}

@Preview
@Composable
private fun CoursesScreenPreview() {
    EinundzwanzigTheme {
        CoursesScreen(
            state = CoursesUiState(
                loading = false,
                courses = listOf(
                    PortalListItem(1, "Bitcoin Grundlagen", "Einsteigerkurs über Geld, Knappheit und Selbstverwahrung.", "", emptyList()),
                    PortalListItem(null, "Lightning Workshop", "Praxis mit Wallets. https://example.org", "", listOf("https://example.org")),
                ),
            ),
            onAction = {},
            onBack = {},
        )
    }
}
