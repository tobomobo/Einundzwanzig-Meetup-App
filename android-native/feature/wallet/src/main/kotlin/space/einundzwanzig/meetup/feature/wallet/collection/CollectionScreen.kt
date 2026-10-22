package space.einundzwanzig.meetup.feature.wallet.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.einundzwanzig.meetup.core.navigation.BadgeCollection
import space.einundzwanzig.meetup.core.navigation.BadgeDetails
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.ui.component.AppMediumTopBar
import space.einundzwanzig.meetup.core.ui.component.EmptyState
import space.einundzwanzig.meetup.core.ui.component.FullScreenLoading
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.data.badges.model.BadgeIdentity
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.feature.wallet.PreviewData
import space.einundzwanzig.meetup.feature.wallet.crest.ProvideWalletCrests
import space.einundzwanzig.meetup.feature.wallet.domain.WalletLibrary
import space.einundzwanzig.meetup.feature.wallet.ui.NormalBadgeCard
import space.einundzwanzig.meetup.feature.wallet.ui.dartUpper
import java.util.IdentityHashMap
import space.einundzwanzig.meetup.core.l10n.R as L10nR

internal object CollectionTestTags {
    const val GRID = "collection.grid"
    const val EMPTY = "collection.empty"
}

/** Stateful collection screen for [key] (spec 03 §2.1.1). */
@Composable
fun BadgeCollectionRoute(key: BadgeCollection) {
    ProvideWalletCrests {
        val viewModel = hiltViewModel<CollectionViewModel, CollectionViewModel.Factory>(
            creationCallback = { it.create(key) },
        )
        val state by viewModel.state.collectAsStateWithLifecycle()
        val navigator = LocalNavigator.current
        CollectionScreen(
            state = state,
            onBack = navigator::back,
            onOpenBadge = { badge ->
                val index = WalletLibrary.globalIndex(state.all, badge)
                if (index >= 0) navigator.navigate(BadgeDetails(BadgeIdentity.unionKey(badge), index))
            },
        )
    }
}

/** Stateless collection screen: title, count and a 2-column grid of normal cards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectionScreen(
    state: CollectionUiState,
    onBack: () -> Unit,
    onOpenBadge: (MeetupBadge) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val title = if (state.isOrganizer) stringResource(L10nR.string.walletOrganizerSection) else state.meetupTitle
    val numbers = remember(state.all) {
        IdentityHashMap<MeetupBadge, Int>().apply { state.all.forEachIndexed { i, b -> put(b, i + 1) } }
    }
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppMediumTopBar(
                title = dartUpper(title),
                subtitle = stringResource(L10nR.string.walletCollectionCount, state.badges.size),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        when {
            !state.loaded -> FullScreenLoading(Modifier.padding(padding), message = stringResource(L10nR.string.loading))
            state.badges.isEmpty() -> EmptyState(
                icon = Icons.Rounded.SearchOff,
                title = stringResource(L10nR.string.walletNoResults),
                subtitle = "",
                modifier = Modifier.padding(padding).testTag(CollectionTestTags.EMPTY),
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .testTag(CollectionTestTags.GRID),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(state.badges, key = { i, b -> "${numbers[b] ?: 0}:$i" }) { _, badge ->
                    NormalBadgeCard(badge, numbers[badge] ?: 0, onClick = { onOpenBadge(badge) })
                }
            }
        }
    }
}

@Preview(widthDp = 380, heightDp = 800)
@Composable
private fun CollectionScreenPreview() {
    val all = PreviewData.badges()
    EinundzwanzigTheme {
        CollectionScreen(
            state = CollectionUiState(
                groupKey = "Aschaffenburg",
                loaded = true,
                badges = WalletLibrary.collection(all, "Aschaffenburg", ""),
                all = all,
            ),
            onBack = {},
            onOpenBadge = {},
        )
    }
}
