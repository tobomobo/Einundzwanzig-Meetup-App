package space.einundzwanzig.meetup.feature.news.list

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.NewsArticleDetails
import space.einundzwanzig.meetup.core.ui.component.AppLargeTopBar
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.Tile
import space.einundzwanzig.meetup.core.ui.motion.pressScale
import space.einundzwanzig.meetup.core.ui.theme.Dimens
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.news.NewsArticle
import space.einundzwanzig.meetup.data.news.NewsLinks
import space.einundzwanzig.meetup.feature.news.ArticleMetaRow
import space.einundzwanzig.meetup.feature.news.MEDIA_HOST_LABEL
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags of the News list. */
internal object NewsTestTags {
    const val LIST = "news_list"
    const val LOADING = "news_loading"
    const val EMPTY = "news_empty"
    const val WRITE_TILE = "news_write_tile"
    const val WEBSITE_TILE = "news_website_tile"
    fun card(index: Int) = "news_card_$index"
}

/** Stateful News screen (spec 11 §2.3). */
@Composable
fun NewsRoute(
    markAsRead: Boolean,
    viewModel: NewsViewModel = hiltViewModel<NewsViewModel, NewsViewModel.Factory>(
        key = "news-$markAsRead",
        creationCallback = { it.create(markAsRead) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    NewsScreen(
        state = state,
        onAction = { action ->
            when (action) {
                NewsAction.Back -> navigator.back()
                NewsAction.Refresh -> viewModel.refresh()
                is NewsAction.OpenArticle -> navigator.navigate(NewsArticleDetails(action.article.id, action.index))
                NewsAction.WriteArticle -> navigator.openUrl(NewsLinks.WRITE_URL)
                NewsAction.OpenWebsite -> navigator.openUrl(NewsLinks.WEBSITE_URL)
            }
        },
    )
}

/** Stateless News list: action tiles, loading/empty/cards, source footer. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NewsScreen(
    state: NewsUiState,
    onAction: (NewsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppLargeTopBar(
                title = stringResource(L10nR.string.newsTitle),
                onBack = { onAction(NewsAction.Back) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { onAction(NewsAction.Refresh) },
            state = pullState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = pullState,
                    isRefreshing = state.refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(NewsTestTags.LIST),
                contentPadding = PaddingValues(16.dp),
            ) {
                item(key = "actions") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        NewsActionTile(
                            icon = Icons.Rounded.EditNote,
                            label = stringResource(L10nR.string.newsWriteArticle),
                            shape = MaterialShapes.Cookie6Sided,
                            onClick = { onAction(NewsAction.WriteArticle) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .testTag(NewsTestTags.WRITE_TILE),
                        )
                        NewsActionTile(
                            icon = Icons.Rounded.Public,
                            label = stringResource(L10nR.string.newsOpenWebsite),
                            shape = MaterialShapes.Sunny,
                            onClick = { onAction(NewsAction.OpenWebsite) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .testTag(NewsTestTags.WEBSITE_TILE),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
                when {
                    state.loading && state.articles.isEmpty() -> item(key = "loading") { LoadingBlock() }
                    state.isEmpty -> item(key = "empty") { EmptyBlock() }
                    else -> itemsIndexed(
                        state.articles,
                        key = { index, article -> "$index|${article.id}" },
                    ) { index, article ->
                        ArticleCard(
                            article = article,
                            onClick = { onAction(NewsAction.OpenArticle(index, article)) },
                            modifier = Modifier
                                .animateItem()
                                .padding(bottom = 14.dp)
                                .testTag(NewsTestTags.card(index)),
                        )
                    }
                }
                item(key = "footer") {
                    Text(
                        stringResource(L10nR.string.newsSource),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.extendedColors.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 20.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NewsActionTile(
    icon: ImageVector,
    label: String,
    shape: androidx.graphics.shapes.RoundedPolygon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .pressScale(interaction)
            .background(
                Brush.linearGradient(listOf(primary.copy(alpha = 0.16f), MaterialTheme.colorScheme.surfaceContainer)),
                Dimens.TileShape,
            )
            .border(Dimens.Hairline, primary.copy(alpha = 0.4f), Dimens.TileShape)
            .clip(Dimens.TileShape)
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .background(primary.copy(alpha = 0.15f), shape.toShape()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = primary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            MEDIA_HOST_LABEL,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.extendedColors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LoadingBlock() {
    val label = stringResource(L10nR.string.newsLoading)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 60.dp)
            .testTag(NewsTestTags.LOADING),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppLoadingIndicator(contentDescription = label)
        Spacer(Modifier.height(14.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyBlock() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 60.dp)
            .testTag(NewsTestTags.EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.Article,
            contentDescription = null,
            tint = MaterialTheme.extendedColors.textTertiary,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(L10nR.string.newsEmpty),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ArticleCard(article: NewsArticle, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Tile(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = PaddingValues(0.dp),
    ) {
        Column {
            if (article.image.isNotEmpty()) {
                var failed by remember(article.image) { mutableStateOf(false) }
                if (!failed) {
                    AsyncImage(
                        model = article.image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        onState = { if (it is AsyncImagePainter.State.Error) failed = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    )
                }
            }
            Column(Modifier.padding(14.dp)) {
                Text(
                    article.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = 21.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (article.summary.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        article.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                ArticleMetaRow(
                    article = article,
                    style = MaterialTheme.typography.bodySmall,
                    iconSize = 13.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

internal val PreviewArticles = listOf(
    NewsArticle(
        id = "30023:${"a".repeat(64)}:halving",
        author = "Satoshi",
        title = "Halving 2028: Was Meetups jetzt vorbereiten",
        summary = "Ein Überblick über Termine, Badges und wie ihr eure Community auf den nächsten Zyklus vorbereitet.",
        image = "",
        content = "",
        publishedAt = 1_792_226_373,
        link = "https://media.einundzwanzig.space/a/halving",
    ),
    NewsArticle(
        id = "30023:${"b".repeat(64)}:lightning",
        author = "",
        title = "Lightning im Alltag",
        summary = "Zahlen mit Sats beim Stammtisch.",
        image = "",
        content = "",
        publishedAt = 1_792_152_977,
        link = "",
    ),
)

@Preview(name = "News list", backgroundColor = 0xFF0C0C0E, showBackground = true)
@Composable
private fun NewsScreenPreview() {
    EinundzwanzigTheme { NewsScreen(NewsUiState(loading = false, articles = PreviewArticles), onAction = {}) }
}

@Preview(name = "News loading", backgroundColor = 0xFF0C0C0E, showBackground = true)
@Composable
private fun NewsScreenLoadingPreview() {
    EinundzwanzigTheme { NewsScreen(NewsUiState(loading = true), onAction = {}) }
}

@Preview(name = "News empty", backgroundColor = 0xFF0C0C0E, showBackground = true)
@Composable
private fun NewsScreenEmptyPreview() {
    EinundzwanzigTheme { NewsScreen(NewsUiState(loading = false), onAction = {}) }
}
