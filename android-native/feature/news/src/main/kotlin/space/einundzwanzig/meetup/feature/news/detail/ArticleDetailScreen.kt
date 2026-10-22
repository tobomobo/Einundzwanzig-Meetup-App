package space.einundzwanzig.meetup.feature.news.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedToggleButton
import androidx.compose.material3.OutlinedToggleButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.NewsArticleDetails
import space.einundzwanzig.meetup.core.ui.component.AppLoadingIndicator
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.AppTopBar
import space.einundzwanzig.meetup.core.ui.component.EmptyState
import space.einundzwanzig.meetup.core.ui.component.FullScreenLoading
import space.einundzwanzig.meetup.core.ui.component.LocalAppSnackbarHostState
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.core.ui.component.showAppSnackbar
import space.einundzwanzig.meetup.core.ui.markdown.MarkdownView
import space.einundzwanzig.meetup.core.ui.security.SecretClipboard
import space.einundzwanzig.meetup.core.ui.share.ShareHelper
import space.einundzwanzig.meetup.core.ui.theme.Dimens
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.MonoStyle
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.data.news.ArticleLikes
import space.einundzwanzig.meetup.data.news.NewsArticle
import space.einundzwanzig.meetup.data.news.NewsArticles
import space.einundzwanzig.meetup.data.news.NewsZapService
import space.einundzwanzig.meetup.data.news.ZapInvoiceLauncher
import space.einundzwanzig.meetup.feature.news.ArticleMetaRow
import space.einundzwanzig.meetup.feature.news.R
import space.einundzwanzig.meetup.feature.news.list.PreviewArticles
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Flutter's default `SnackBar` display time (4 s) for the like/zap errors and the share fallback. */
private const val SNACKBAR_MILLIS = 4_000L

/** Test tags of the article detail. */
internal object ArticleDetailTestTags {
    const val LIKE = "article_like"
    const val ZAP = "article_zap"
    const val SHARE = "article_share"
    const val CONTENT_LOADING = "article_content_loading"
    const val READ_ON_WEBSITE = "article_read_on_website"
    const val ZAP_SHEET = "article_zap_sheet"
    const val INVOICE_SHEET = "article_invoice_sheet"
    const val COPY_INVOICE = "article_copy_invoice"
    fun zapAmount(sats: Long) = "article_zap_$sats"
}

/** Stateful article detail (spec 11 §2.4). */
@Composable
fun ArticleDetailRoute(
    key: NewsArticleDetails,
    viewModel: ArticleDetailViewModel = hiltViewModel<ArticleDetailViewModel, ArticleDetailViewModel.Factory>(
        creationCallback = { it.create(key) },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val appHost = LocalAppSnackbarHostState.current
    val localHost = remember { SnackbarHostState() }
    val snackbars = appHost ?: localHost
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Only while STARTED: an invoice that arrives in the background stays buffered in the channel
    // and opens the wallet once the user is back (no blocked background activity start).
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { event ->
                when (event) {
                    is ArticleDetailEvent.Message -> scope.launch {
                        snackbars.showAppSnackbar(context.getString(event.text), event.tone, SNACKBAR_MILLIS)
                    }
                    is ArticleDetailEvent.OpenInvoice ->
                        if (!ZapInvoiceLauncher.open(context, event.invoice, viewModel.log)) {
                            viewModel.onWalletMissing(event.invoice)
                        }
                }
            }
        }
    }

    ArticleDetailScreen(
        state = state,
        snackbarHostState = if (appHost == null) localHost else null,
        onAction = { action ->
            when (action) {
                ArticleDetailAction.Back -> navigator.back()
                ArticleDetailAction.Share -> state.article?.let { article ->
                    // Dart `_share`: system share sheet; on failure copy the text + orange snackbar.
                    val text = NewsArticles.shareText(article)
                    if (!ShareHelper.shareText(context, text)) {
                        SecretClipboard.copyPlain(context, text, "news")
                        scope.launch {
                            snackbars.showAppSnackbar(
                                context.getString(L10nR.string.newsShareCopied),
                                Tone.Warning,
                                SNACKBAR_MILLIS,
                            )
                        }
                    }
                }
                is ArticleDetailAction.OpenUrl -> navigator.openUrl(action.url)
                is ArticleDetailAction.CopyInvoice -> {
                    SecretClipboard.copyPlain(context, action.invoice, "invoice")
                    viewModel.onAction(action)
                }
                else -> viewModel.onAction(action)
            }
        },
    )
}

/** Stateless article detail: header, Markdown body, like/zap/share bar, zap sheets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleDetailScreen(
    state: ArticleDetailUiState,
    onAction: (ArticleDetailAction) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = stringResource(L10nR.string.newsTitle),
                onBack = { onAction(ArticleDetailAction.Back) },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { snackbarHostState?.let { AppSnackbarHost(it) } },
    ) { padding ->
        val article = state.article
        when {
            state.resolving -> FullScreenLoading(Modifier.padding(padding))
            article == null -> EmptyState(
                icon = Icons.AutoMirrored.Rounded.Article,
                title = stringResource(R.string.native_news_article_not_found_title),
                subtitle = stringResource(R.string.native_news_article_not_found_body),
                actionLabel = stringResource(L10nR.string.actionBack),
                onAction = { onAction(ArticleDetailAction.Back) },
                modifier = Modifier.padding(padding),
            )
            else -> ArticleBody(article, state, onAction, Modifier.padding(padding))
        }
    }
    if (state.zapSheetVisible) ZapAmountSheet(onAction)
    state.fallbackInvoice?.let { InvoiceFallbackSheet(it, onAction) }
}

@Composable
private fun ArticleBody(
    article: NewsArticle,
    state: ArticleDetailUiState,
    onAction: (ArticleDetailAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        if (article.image.isNotEmpty()) {
            var failed by remember(article.image) { mutableStateOf(false) }
            if (!failed) {
                AsyncImage(
                    model = article.image,
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    onState = { if (it is AsyncImagePainter.State.Error) failed = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(Dimens.TileShape),
                )
                Spacer(Modifier.height(18.dp))
            }
        }
        Text(
            article.title,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 30.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        ArticleMetaRow(
            article = article,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            iconSize = 14.dp,
            spaceBetween = false,
        )
        Spacer(Modifier.height(20.dp))
        when {
            state.contentLoading -> {
                Summary(article)
                Spacer(Modifier.height(20.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .testTag(ArticleDetailTestTags.CONTENT_LOADING),
                    contentAlignment = Alignment.Center,
                ) {
                    AppLoadingIndicator(contentDescription = stringResource(L10nR.string.newsLoading))
                }
            }
            state.hasFullContent -> MarkdownView(
                data = state.fullContent.orEmpty(),
                onLinkClick = { onAction(ArticleDetailAction.OpenUrl(it)) },
            )
            else -> {
                Summary(article)
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { onAction(ArticleDetailAction.OpenUrl(NewsArticles.articleUrl(article))) },
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = PaddingValues(vertical = 14.dp, horizontal = 24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ArticleDetailTestTags.READ_ON_WEBSITE),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.native_news_read_on_website),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        ActionBar(state, onAction)
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(L10nR.string.newsSource),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.extendedColors.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Summary(article: NewsArticle) {
    if (article.summary.isEmpty()) return
    Text(
        article.summary,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionBar(state: ArticleDetailUiState, onAction: (ArticleDetailAction) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val buttonPadding = PaddingValues(vertical = 12.dp, horizontal = 8.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val liked = state.likes.mine
        // TalkBack: name the action and the count ("Gefällt mir, 21") and the liked state. Exposed as a
        // plain button, not a toggle: NIP-25 has no un-like, so a liked button has no click action.
        val likeA11y = buildString {
            append(stringResource(L10nR.string.newsLike))
            state.likeCountLabel?.let { append(", ").append(it) }
        }
        val likedA11y = stringResource(R.string.native_news_liked)
        OutlinedToggleButton(
            checked = liked,
            onCheckedChange = { onAction(ArticleDetailAction.Like) },
            enabled = !state.liking,
            border = BorderStroke(1.dp, if (liked) primary else MaterialTheme.colorScheme.outlineVariant),
            // Dart: orange heart/label/border when liked, secondary otherwise; the expressive
            // default would fill a checked button with inverseSurface.
            colors = OutlinedToggleButtonDefaults.colors(
                contentColor = secondary,
                checkedContainerColor = primary.copy(alpha = 0.12f),
                checkedContentColor = primary,
            ),
            contentPadding = buttonPadding,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .testTag(ArticleDetailTestTags.LIKE)
                .clearAndSetSemantics {
                    contentDescription = likeA11y
                    role = Role.Button
                    when {
                        liked -> stateDescription = likedA11y
                        state.liking -> disabled()
                        else -> onClick { onAction(ArticleDetailAction.Like); true }
                    }
                },
        ) {
            if (state.liking) {
                AppLoadingIndicator(size = 18.dp, color = primary)
            } else {
                Icon(
                    if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                state.likeCountLabel ?: stringResource(L10nR.string.newsLike),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedButton(
            onClick = { onAction(ArticleDetailAction.Zap) },
            shapes = ButtonDefaults.shapes(),
            enabled = !state.zapping,
            border = BorderStroke(1.dp, primary.copy(alpha = 0.5f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = primary),
            contentPadding = buttonPadding,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .testTag(ArticleDetailTestTags.ZAP),
        ) {
            if (state.zapping) {
                AppLoadingIndicator(size = 18.dp, color = primary)
            } else {
                Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(stringResource(L10nR.string.newsZap), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        OutlinedButton(
            onClick = { onAction(ArticleDetailAction.Share) },
            shapes = ButtonDefaults.shapes(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = secondary),
            contentPadding = buttonPadding,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .testTag(ArticleDetailTestTags.SHARE),
        ) {
            Icon(Icons.Rounded.IosShare, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(L10nR.string.newsShare), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ZapAmountSheet(onAction: (ArticleDetailAction) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = { onAction(ArticleDetailAction.DismissZapSheet) },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.testTag(ArticleDetailTestTags.ZAP_SHEET),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text(
                stringResource(L10nR.string.newsZapTitle),
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(L10nR.string.newsZapBody),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NewsZapService.ZAP_AMOUNTS_SATS.forEach { sats ->
                    OutlinedButton(
                        // Animate the sheet out first (Flutter `Navigator.pop`), then start the zap.
                        onClick = {
                            scope.launch { sheetState.hide() }
                                .invokeOnCompletion { cause -> if (cause == null) onAction(ArticleDetailAction.ZapAmount(sats)) }
                        },
                        shapes = ButtonDefaults.shapes(),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                        modifier = Modifier.testTag(ArticleDetailTestTags.zapAmount(sats)),
                    ) {
                        Text(
                            stringResource(R.string.native_news_zap_amount, sats.toString()),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InvoiceFallbackSheet(invoice: String, onAction: (ArticleDetailAction) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = { onAction(ArticleDetailAction.DismissInvoiceFallback) },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.testTag(ArticleDetailTestTags.INVOICE_SHEET),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text(
                stringResource(L10nR.string.newsZapNoWallet),
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                invoice,
                style = MonoStyle.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Dimens.TileRadius))
                    .background(MaterialTheme.colorScheme.background)
                    .padding(12.dp),
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    scope.launch { sheetState.hide() }
                        .invokeOnCompletion { cause -> if (cause == null) onAction(ArticleDetailAction.CopyInvoice(invoice)) }
                },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag(ArticleDetailTestTags.COPY_INVOICE),
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(L10nR.string.newsZapCopyInvoice), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Preview(name = "Article loaded", backgroundColor = 0xFF0C0C0E, showBackground = true, heightDp = 900)
@Composable
private fun ArticleDetailPreview() {
    EinundzwanzigTheme {
        ArticleDetailScreen(
            state = ArticleDetailUiState(
                resolving = false,
                article = PreviewArticles.first(),
                contentLoading = false,
                fullContent = "## Warum Meetups?\n\nWeil **Bitcoin** offline beginnt.\n\n- Badges\n- Chat\n\n> Plebs together strong",
                likes = ArticleLikes(21, true),
                likesLoaded = true,
            ),
            onAction = {},
        )
    }
}

@Preview(name = "Article loading", backgroundColor = 0xFF0C0C0E, showBackground = true)
@Composable
private fun ArticleDetailLoadingPreview() {
    EinundzwanzigTheme {
        ArticleDetailScreen(
            state = ArticleDetailUiState(resolving = false, article = PreviewArticles.first()),
            onAction = {},
        )
    }
}

@Preview(name = "Article without full text", backgroundColor = 0xFF0C0C0E, showBackground = true)
@Composable
private fun ArticleDetailNoContentPreview() {
    EinundzwanzigTheme {
        ArticleDetailScreen(
            state = ArticleDetailUiState(resolving = false, article = PreviewArticles.last(), contentLoading = false),
            onAction = {},
        )
    }
}
