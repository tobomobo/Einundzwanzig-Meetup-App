package space.einundzwanzig.meetup.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import space.einundzwanzig.meetup.core.navigation.BadgeScanner
import space.einundzwanzig.meetup.core.navigation.ReputationQr
import space.einundzwanzig.meetup.core.navigation.TopLevelDestination

/** Entry metadata keys of the app's NavDisplay. */
object AppEntryMetadata {
    /** Set on tab entries: switching tabs is a quick cross-fade, never a slide. */
    const val SHELL_TAB = "einundzwanzig.shellTab"

    /** Set on the screens the scan sheet opens: they slide up from the bottom (spec 12 §2.4). */
    const val SLIDE_UP = "einundzwanzig.slideUp"
}

/** Destinations that Flutter opened with the slide-up route of the scan sheet. */
internal fun slidesUp(key: NavKey): Boolean = key == BadgeScanner || key == ReputationQr

/**
 * Wraps the feature entry for [key]: tab entries get the shell frame (bottom bar), every entry gets
 * the transition metadata. The content key stays the feature's, so saved state is keyed as before.
 */
internal fun wrapEntry(
    key: NavKey,
    featureProvider: (NavKey) -> NavEntry<NavKey>,
    shellFrame: @Composable (TopLevelDestination, @Composable () -> Unit) -> Unit,
): NavEntry<NavKey> {
    val base = featureProvider(key)
    val extra = buildMap<String, Any> {
        if (key is TopLevelDestination) put(AppEntryMetadata.SHELL_TAB, true)
        if (slidesUp(key)) put(AppEntryMetadata.SLIDE_UP, true)
    }
    val metadata = base.metadata + extra
    return if (key is TopLevelDestination) {
        NavEntry(key, base.contentKey, metadata) { shellFrame(key) { base.Content() } }
    } else {
        NavEntry(key, base.contentKey, metadata) { base.Content() }
    }
}

/**
 * The app's [NavDisplay] over [AppBackStacks].
 *
 * Each tab is decorated from its own stack, so it keeps its saveable state and its entry-scoped
 * ViewModels while another tab is selected (Flutter `IndexedStack`). Screens above the shell and
 * the pre-shell flow are decorated from the root stack. Every entry gets a saveable-state holder and
 * its own `ViewModelStore` (ARCHITECTURE.md "Per-entry ViewModels"), so popped screens stop their
 * work. Predictive back is handled by `NavDisplay`.
 */
@Composable
fun AppNavHost(
    stacks: AppBackStacks,
    onBack: () -> Unit,
    featureProvider: (NavKey) -> NavEntry<NavKey>,
    shellFrame: @Composable (TopLevelDestination, @Composable () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val provider: (NavKey) -> NavEntry<NavKey> = remember(featureProvider, shellFrame) {
        { key -> wrapEntry(key, featureProvider, shellFrame) }
    }
    val tabEntries = TopLevelDestination.entries.associateWith { tab ->
        rememberDecoratedNavEntries(
            backStack = stacks.tabs.getValue(tab),
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = provider,
        )
    }
    val overlayEntries = rememberDecoratedNavEntries(
        backStack = stacks.overlayKeys,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = provider,
    )
    val current = stacks.currentTab
    val entries = if (current != null) tabEntries.getValue(current) + overlayEntries else overlayEntries
    if (entries.isEmpty()) return

    NavDisplay(
        entries = entries,
        modifier = modifier,
        onBack = onBack,
        transitionSpec = { forwardTransition() },
        popTransitionSpec = { popTransition() },
        predictivePopTransitionSpec = { popTransition() },
    )
}

private fun <T> spatial(): FiniteAnimationSpec<T> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

private val fadeSpec = tween<Float>(durationMillis = 180)

private fun Scene<NavKey>.topMetadata(): Map<String, Any> = entries.lastOrNull()?.metadata.orEmpty()

private fun AnimatedContentTransitionScope<Scene<NavKey>>.isTabSwitch(): Boolean =
    initialState.topMetadata()[AppEntryMetadata.SHELL_TAB] == true &&
        targetState.topMetadata()[AppEntryMetadata.SHELL_TAB] == true

private fun AnimatedContentTransitionScope<Scene<NavKey>>.forwardTransition(): ContentTransform = when {
    isTabSwitch() -> fadeIn(fadeSpec) togetherWith fadeOut(fadeSpec)
    targetState.topMetadata()[AppEntryMetadata.SLIDE_UP] == true ->
        slideInVertically(spatial<IntOffset>()) { it } togetherWith fadeOut(fadeSpec)
    else -> (slideInHorizontally(spatial<IntOffset>()) { it } + fadeIn(fadeSpec)) togetherWith
        (slideOutHorizontally(spatial<IntOffset>()) { -it / 4 } + fadeOut(fadeSpec))
}

private fun AnimatedContentTransitionScope<Scene<NavKey>>.popTransition(): ContentTransform = when {
    isTabSwitch() -> fadeIn(fadeSpec) togetherWith fadeOut(fadeSpec)
    initialState.topMetadata()[AppEntryMetadata.SLIDE_UP] == true ->
        fadeIn(fadeSpec) togetherWith slideOutVertically(spatial<IntOffset>()) { it }
    else -> (slideInHorizontally(spatial<IntOffset>()) { -it / 4 } + fadeIn(fadeSpec)) togetherWith
        (slideOutHorizontally(spatial<IntOffset>()) { it } + scaleOut(targetScale = 0.96f))
}
