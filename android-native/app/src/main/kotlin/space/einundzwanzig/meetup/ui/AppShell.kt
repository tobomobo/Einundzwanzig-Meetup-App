package space.einundzwanzig.meetup.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import space.einundzwanzig.meetup.R
import space.einundzwanzig.meetup.core.navigation.EventsTab
import space.einundzwanzig.meetup.core.navigation.HomeTab
import space.einundzwanzig.meetup.core.navigation.TopLevelDestination
import space.einundzwanzig.meetup.core.navigation.WalletTab
import space.einundzwanzig.meetup.core.ui.guide.guideAnchor
import space.einundzwanzig.meetup.core.ui.haptics.rememberHaptics
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.extendedColors
import space.einundzwanzig.meetup.feature.home.HomeTour
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags of the app shell. */
object AppShellTags {
    const val BAR = "shell_bar"
    const val SCAN = "shell_scan"
    const val NEARBY = "shell_nearby"
    fun tab(tab: TopLevelDestination) = "shell_tab_$tab"
}

/** Actions of the app shell (spec 12 §2.4). */
sealed interface ShellAction {
    /** Tab tap (also on the selected tab: Home refreshes then). */
    data class SelectTab(val tab: TopLevelDestination) : ShellAction

    /** Central scan button: opens the scan sheet. */
    data object Scan : ShellAction

    /** "In der Nähe": pushes `NearbyMeetups` (an action, never selected). */
    data object Nearby : ShellAction
}

/**
 * Frame of a tab entry: the tab content above the bottom bar. Each tab entry draws its own bar, so
 * screens pushed above the shell cover it, like Flutter's routes above `AppShell`.
 */
@Composable
fun AppShellScaffold(
    currentTab: TopLevelDestination,
    onAction: (ShellAction) -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        bottomBar = { AppBottomBar(currentTab, onAction) },
    ) { padding ->
        val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom)
                .consumeWindowInsets(bottom),
        ) { content() }
    }
}

/**
 * Bottom bar (spec 12 §2.4, F5–F7): Home, Badges, central scan button, Events, "In der Nähe".
 * Material 3 Expressive `ShortNavigationBar`; the scan button is a cookie-shaped primary button that
 * springs on press. Light haptic on tabs and Nearby, medium haptic on scan (each checks the
 * `haptic_enabled` setting through `LocalHapticsEnabled`).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppBottomBar(currentTab: TopLevelDestination, onAction: (ShellAction) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val colors = ShortNavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.primary,
        selectedTextColorTopIconPosition = MaterialTheme.colorScheme.onSurface,
        selectedTextColorStartIconPosition = MaterialTheme.colorScheme.onSurface,
        selectedIndicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
        unselectedIconColor = MaterialTheme.extendedColors.textTertiary,
        unselectedTextColor = MaterialTheme.extendedColors.textTertiary,
    )
    Box(modifier) {
        ShortNavigationBar(
            modifier = Modifier.testTag(AppShellTags.BAR),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            TabItem(HomeTab, currentTab, Icons.Rounded.Home, Icons.Outlined.Home, L10nR.string.navHome, HomeTour.HOME_TAB, colors) {
                haptics.light(); onAction(ShellAction.SelectTab(HomeTab))
            }
            TabItem(WalletTab, currentTab, Icons.Rounded.Style, Icons.Outlined.Style, L10nR.string.navWalletTab, HomeTour.BADGE_TAB, colors) {
                haptics.light(); onAction(ShellAction.SelectTab(WalletTab))
            }
            Box(Modifier, contentAlignment = Alignment.Center) {
                ScanButton(onClick = { haptics.medium(); onAction(ShellAction.Scan) })
            }
            TabItem(EventsTab, currentTab, Icons.Rounded.Event, Icons.Outlined.Event, L10nR.string.navEvents, HomeTour.EVENTS_TAB, colors) {
                haptics.light(); onAction(ShellAction.SelectTab(EventsTab))
            }
            ShortNavigationBarItem(
                selected = false,
                onClick = { haptics.light(); onAction(ShellAction.Nearby) },
                icon = { Icon(Icons.Outlined.NearMe, contentDescription = null) },
                label = { Text(stringResource(L10nR.string.navNearby), maxLines = 1) },
                colors = colors,
                modifier = Modifier.guideAnchor(HomeTour.NEARBY_TAB).testTag(AppShellTags.NEARBY),
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun TabItem(
    tab: TopLevelDestination,
    current: TopLevelDestination,
    selectedIcon: ImageVector,
    icon: ImageVector,
    label: Int,
    anchor: String,
    colors: androidx.compose.material3.NavigationItemColors,
    onClick: () -> Unit,
) {
    val selected = tab == current
    ShortNavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(if (selected) selectedIcon else icon, contentDescription = null) },
        label = { Text(stringResource(label), maxLines = 1) },
        colors = colors,
        modifier = Modifier.guideAnchor(anchor).testTag(AppShellTags.tab(tab)),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScanButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "scanScale",
    )
    val rotation by animateFloatAsState(
        targetValue = if (pressed) 30f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "scanRotation",
    )
    val description = stringResource(R.string.native_shell_scan)
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = MaterialShapes.Cookie9Sided.toShape(),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 6.dp,
        modifier = Modifier
            .size(56.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                rotationZ = rotation
            }
            .guideAnchor(HomeTour.SCAN_FAB)
            .testTag(AppShellTags.SCAN)
            .semantics { role = Role.Button },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.QrCodeScanner,
                contentDescription = description,
                modifier = Modifier
                    .size(26.dp)
                    .graphicsLayer { rotationZ = -rotation },
            )
        }
    }
}

@Preview(name = "App shell bar", showBackground = true, backgroundColor = 0xFF0C0C0E)
@Composable
private fun AppBottomBarPreview() {
    EinundzwanzigTheme { AppBottomBar(currentTab = HomeTab, onAction = {}) }
}

@Preview(name = "App shell", showBackground = true, backgroundColor = 0xFF0C0C0E, heightDp = 400)
@Composable
private fun AppShellScaffoldPreview() {
    EinundzwanzigTheme {
        AppShellScaffold(currentTab = WalletTab, onAction = {}) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Badges") }
        }
    }
}
