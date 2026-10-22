package space.einundzwanzig.meetup.core.ui.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Brand defaults for the top app bars (Flutter: transparent bar, secondary-coloured icons). */
object AppTopBarDefaults {
    /**
     * Transparent while content sits below, surface container once content scrolls under it.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun colors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.background,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Back arrow (auto-mirrored) with the localized `actionBack` description. */
@Composable
fun BackNavigationButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onBack, modifier = modifier) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(L10nR.string.actionBack))
    }
}

/**
 * Small, single-line top app bar. Pass a `pinnedScrollBehavior` to get the scrolled colour.
 *
 * @param onBack shows a back button when not null.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    colors: TopAppBarColors = AppTopBarDefaults.colors(),
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        navigationIcon = { if (onBack != null) BackNavigationButton(onBack) },
        actions = actions,
        colors = colors,
        scrollBehavior = scrollBehavior,
    )
}

/**
 * Material 3 Expressive medium flexible top app bar (title + optional subtitle that collapse
 * on scroll). Use with `TopAppBarDefaults.exitUntilCollapsedScrollBehavior()` and
 * `Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)` on the Scaffold.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppMediumTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    colors: TopAppBarColors = AppTopBarDefaults.colors(),
) {
    MediumFlexibleTopAppBar(
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        subtitle = subtitle?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        navigationIcon = { if (onBack != null) BackNavigationButton(onBack) },
        actions = actions,
        colors = colors,
        scrollBehavior = scrollBehavior,
    )
}

/** Material 3 Expressive large flexible top app bar; see [AppMediumTopBar]. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppLargeTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    colors: TopAppBarColors = AppTopBarDefaults.colors(),
    /** Applied to the title text only (e.g. a guided-tour anchor on just the title). */
    titleModifier: Modifier = Modifier,
) {
    LargeFlexibleTopAppBar(
        title = { Text(title, modifier = titleModifier, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        subtitle = subtitle?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        navigationIcon = { if (onBack != null) BackNavigationButton(onBack) },
        actions = actions,
        colors = colors,
        scrollBehavior = scrollBehavior,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "AppTopBars", backgroundColor = 0xFF0C0C0E, showBackground = true, heightDp = 360)
@Composable
private fun AppTopBarsPreview() {
    EinundzwanzigTheme {
        val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        Scaffold(
            modifier = Modifier.nestedScroll(behavior.nestedScrollConnection),
            topBar = {
                AppLargeTopBar(
                    title = "Badge-Wallet",
                    subtitle = "12 Badges · 5 Meetups",
                    onBack = {},
                    actions = {
                        IconButton(onClick = {}) { Icon(Icons.Rounded.Share, contentDescription = null) }
                    },
                    scrollBehavior = behavior,
                )
            },
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                items(30) { Text("Zeile $it") }
            }
        }
    }
}
