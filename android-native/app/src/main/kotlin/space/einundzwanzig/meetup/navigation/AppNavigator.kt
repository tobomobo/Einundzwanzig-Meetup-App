package space.einundzwanzig.meetup.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.navigation3.runtime.NavKey
import space.einundzwanzig.meetup.core.navigation.Navigator
import space.einundzwanzig.meetup.core.navigation.TopLevelDestination

/**
 * Back stacks of the app (spec 12 §2.4, ROUTES.md).
 *
 * [root] is the stack the user sees: either a pre-shell flow (`Splash`, `Intro`, `IdentitySetup`)
 * or, when its first key is a [TopLevelDestination], the app shell with the selected tab at index 0
 * and the screens pushed above the shell after it (Flutter pushes every route on the root
 * navigator, above the shell and its bottom bar).
 *
 * [tabs] keeps one stack per tab, `[tab]` once the tab was opened in the current shell, else empty.
 * The tab entries are decorated from these stacks, so a tab keeps its saveable state and ViewModels
 * while another tab is shown (Flutter `IndexedStack`), and loses them when the shell goes away
 * (reset to `Intro`).
 *
 * Both are snapshot-state lists (`NavBackStack`), so the UI recomposes on every change and the keys
 * survive process death.
 */
@Stable
class AppBackStacks(
    val root: MutableList<NavKey>,
    val tabs: Map<TopLevelDestination, MutableList<NavKey>>,
) {
    init {
        require(tabs.keys.containsAll(TopLevelDestination.entries)) { "every tab needs a stack" }
    }

    /** `true` while the app shell (bottom bar + tabs) is the base of [root]. */
    val shellActive: Boolean get() = root.firstOrNull() is TopLevelDestination

    /** The selected tab, `null` before the shell exists. */
    val currentTab: TopLevelDestination? get() = root.firstOrNull() as? TopLevelDestination

    /** The visible destination. */
    val top: NavKey? get() = root.lastOrNull()

    /** Keys above the shell (or the whole pre-shell flow). */
    val overlayKeys: List<NavKey> get() = if (shellActive) root.drop(1) else root.toList()
}

/**
 * The app's [Navigator] over [AppBackStacks] (Flutter navigator mapping in core:navigation).
 *
 * @param openExternalUrl opens an http(s) URL outside the app and returns whether something was
 *   started; non-http(s) URLs must be refused by it.
 */
@Stable
class AppNavigator(
    private val stacks: AppBackStacks,
    private val openExternalUrl: (String) -> Boolean,
) : Navigator {

    override fun navigate(key: NavKey) = mutate {
        if (key is TopLevelDestination) {
            if (stacks.shellActive) selectTabInternal(key) else resetInternal(key)
        } else {
            stacks.root.add(key)
        }
    }

    override fun replaceTop(key: NavKey) = mutate {
        when {
            stacks.root.size <= 1 -> resetInternal(key)
            key is TopLevelDestination -> if (stacks.shellActive) selectTabInternal(key) else resetInternal(key)
            else -> stacks.root[stacks.root.lastIndex] = key
        }
    }

    override fun back() = mutate {
        if (stacks.root.size > 1) stacks.root.removeAt(stacks.root.lastIndex)
    }

    override fun backTo(key: NavKey, inclusive: Boolean) = mutate {
        val index = stacks.root.lastIndexOf(key)
        if (index < 0) return@mutate
        val keep = maxOf(if (inclusive) index else index + 1, 1)
        while (stacks.root.size > keep) stacks.root.removeAt(stacks.root.lastIndex)
    }

    override fun resetTo(key: NavKey) = mutate { resetInternal(key) }

    override fun navigateFromRoot(key: NavKey) = mutate {
        while (stacks.root.size > 1) stacks.root.removeAt(stacks.root.lastIndex)
        if (key is TopLevelDestination) {
            if (stacks.shellActive) selectTabInternal(key) else resetInternal(key)
        } else {
            stacks.root.add(key)
        }
    }

    override fun openUrl(url: String) {
        openExternalUrl(url)
    }

    /** [openUrl] that reports whether a browser was started. */
    fun openUrlChecked(url: String): Boolean = openExternalUrl(url)

    /** Bottom-bar tap: shows [tab]; the screens above the shell are closed. */
    fun selectTab(tab: TopLevelDestination) = mutate {
        if (stacks.shellActive) selectTabInternal(tab) else resetInternal(tab)
    }

    private fun selectTabInternal(tab: TopLevelDestination) {
        while (stacks.root.size > 1) stacks.root.removeAt(stacks.root.lastIndex)
        val tabStack = stacks.tabs.getValue(tab)
        if (tabStack.isEmpty()) tabStack.add(tab)
        if (stacks.root.isEmpty()) stacks.root.add(tab) else stacks.root[0] = tab
    }

    private fun resetInternal(key: NavKey) {
        stacks.tabs.forEach { (tab, stack) ->
            val keep = key is TopLevelDestination && tab == key
            if (!keep) stack.clear() else if (stack.isEmpty()) stack.add(tab)
        }
        stacks.root.clear()
        stacks.root.add(key)
    }

    private inline fun mutate(crossinline block: () -> Unit) {
        // One atomic change: the UI never sees a half-updated (e.g. empty) stack.
        Snapshot.withMutableSnapshot { block() }
    }
}
