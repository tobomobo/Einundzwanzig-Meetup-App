package space.einundzwanzig.meetup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.result.LocalResultEventBus
import androidx.navigation3.runtime.result.rememberResultEventBus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.url.SafeUrl
import space.einundzwanzig.meetup.core.navigation.BadgeScanner
import space.einundzwanzig.meetup.core.navigation.Destination
import space.einundzwanzig.meetup.core.navigation.HomeTab
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.NearbyMeetups
import space.einundzwanzig.meetup.core.navigation.ReputationQr
import space.einundzwanzig.meetup.core.navigation.Splash
import space.einundzwanzig.meetup.core.navigation.TopLevelDestination
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarController
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarControllerEffect
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.LocalAppSnackbarController
import space.einundzwanzig.meetup.core.ui.component.LocalAppSnackbarHostState
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.core.ui.component.showAppSnackbar
import space.einundzwanzig.meetup.core.ui.crest.LocalMeetupCrestSource
import space.einundzwanzig.meetup.core.ui.crest.MeetupCrestSource
import space.einundzwanzig.meetup.core.ui.guide.GuideController
import space.einundzwanzig.meetup.core.ui.guide.GuideHost
import space.einundzwanzig.meetup.core.ui.haptics.LocalHapticsEnabled
import space.einundzwanzig.meetup.core.ui.nostr.LocalNostrProfileSource
import space.einundzwanzig.meetup.core.ui.nostr.NostrProfileSource
import space.einundzwanzig.meetup.feature.backup.BackupHost
import space.einundzwanzig.meetup.feature.home.ScanSheet
import space.einundzwanzig.meetup.navigation.AppBackStacks
import space.einundzwanzig.meetup.navigation.AppNavHost
import space.einundzwanzig.meetup.navigation.AppNavigator
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Everything the root needs from the app's singletons (built by `MainActivity`). */
class AppRootDependencies(
    val snackbarController: AppSnackbarController,
    val guideController: GuideController,
    val profileSource: NostrProfileSource,
    val crestSource: MeetupCrestSource,
    /** Flutter `HomeScreen.refreshAfterScan()`. */
    val requestHomeRefresh: () -> Unit,
    /** NIP-46 `auth_url`s to show (`SigningService.authUrlEvents`). */
    val authUrls: Flow<String>,
    val acknowledgeAuthUrl: (String) -> Unit,
    /** Reads and removes the pending widget target (`e21_widget/pending_target`). */
    val consumeWidgetTarget: suspend () -> Destination?,
)

/** Duration of the NIP-46 approval snackbar (spec 12 §2.2). */
internal const val AUTH_URL_SNACKBAR_MILLIS: Long = 60_000

/** Max content width on tablets and foldables (spec 12 F3). */
private val MaxContentWidth = 420.dp

/**
 * Root of the UI (spec 12 §2.1–§2.4): composition locals for the features, the root snackbar host
 * (+ app-lifetime queue), the backup host, the spotlight guide host, the NavDisplay with the app
 * shell, the scan sheet, the NIP-46 approval snackbar and the widget launch targets.
 */
@Composable
fun AppRoot(
    stacks: AppBackStacks,
    navigator: AppNavigator,
    deps: AppRootDependencies,
    hapticsEnabled: Boolean,
    featureProvider: (NavKey) -> NavEntry<NavKey>,
    onFirstRoute: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val resultBus = rememberResultEventBus()
    var scanSheetOpen by rememberSaveable { mutableStateOf(false) }
    var refreshHomeOnReturn by rememberSaveable { mutableStateOf(false) }

    CompositionLocalProvider(
        LocalNavigator provides navigator,
        LocalResultEventBus provides resultBus,
        LocalHapticsEnabled provides hapticsEnabled,
        LocalAppSnackbarHostState provides snackbarHostState,
        LocalAppSnackbarController provides deps.snackbarController,
        LocalNostrProfileSource provides deps.profileSource,
        LocalMeetupCrestSource provides deps.crestSource,
    ) {
        GuideHost(deps.guideController) {
            ConstrainedWidth {
                val shellFrame: @Composable (TopLevelDestination, @Composable () -> Unit) -> Unit = remember(navigator) {
                    { tab, content ->
                        AppShellScaffold(
                            currentTab = tab,
                            onAction = { action ->
                                handleShellAction(action, navigator, deps.requestHomeRefresh) { scanSheetOpen = true }
                            },
                            content = content,
                        )
                    }
                }
                AppNavHost(
                    stacks = stacks,
                    onBack = navigator::back,
                    featureProvider = featureProvider,
                    shellFrame = shellFrame,
                )
                // Inside the 420 dp column (spec 12 F3): snackbars never span a wide window.
                AppSnackbarHost(
                    snackbarHostState,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .imePadding()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = if (stacks.top is TopLevelDestination) 72.dp else 0.dp),
                )
            }
        }
        AppSnackbarControllerEffect(deps.snackbarController, snackbarHostState)
        BackupHost(snackbarHostState)

        if (scanSheetOpen) {
            ScanSheet(
                onDismissRequest = { scanSheetOpen = false },
                onScanBadge = {
                    refreshHomeOnReturn = true
                    navigator.navigate(BadgeScanner)
                },
                onCheckReputation = { navigator.navigate(ReputationQr) },
            )
        }
    }

    // Home refreshes when the scanner opened from the scan sheet returns (Flutter refreshAfterScan).
    RefreshOnReturnEffect(
        stacks = stacks,
        pending = { refreshHomeOnReturn },
        onReturned = {
            refreshHomeOnReturn = false
            deps.requestHomeRefresh()
        },
    )

    // Report the first destination; MainActivity also releases the system splash for storage errors.
    LaunchedEffect(Unit) {
        snapshotFlow { stacks.top }.filter { it != null && it != Splash }.first()
        onFirstRoute()
    }

    AuthUrlSnackbarEffect(deps.authUrls, deps.acknowledgeAuthUrl, snackbarHostState, navigator::openUrlChecked)
    WidgetTargetEffect(stacks, navigator, deps.consumeWidgetTarget)
}

/** Bottom-bar actions (spec 12 §2.4): a Home tap (also a re-tap) refreshes Home like Flutter. */
internal fun handleShellAction(
    action: ShellAction,
    navigator: AppNavigator,
    requestHomeRefresh: () -> Unit,
    openScanSheet: () -> Unit,
) {
    when (action) {
        is ShellAction.SelectTab -> {
            navigator.selectTab(action.tab)
            if (action.tab == HomeTab) requestHomeRefresh()
        }
        ShellAction.Scan -> openScanSheet()
        ShellAction.Nearby -> navigator.navigate(NearbyMeetups)
    }
}

/** Calls [onReturned] once a tab is visible again while [pending] (scanner opened from the scan sheet). */
@Composable
internal fun RefreshOnReturnEffect(stacks: AppBackStacks, pending: () -> Boolean, onReturned: () -> Unit) {
    val currentPending by rememberUpdatedState(pending)
    val currentOnReturned by rememberUpdatedState(onReturned)
    LaunchedEffect(stacks) {
        snapshotFlow { stacks.top }.collect { top ->
            if (currentPending() && top is TopLevelDestination) currentOnReturned()
        }
    }
}

/** Flutter's `Center(Container(maxWidth 420, vertical hairline borders))` around every route. */
@Composable
private fun ConstrainedWidth(content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        val wide = maxWidth > MaxContentWidth
        val border = MaterialTheme.colorScheme.outlineVariant
        Box(
            Modifier
                .widthIn(max = MaxContentWidth)
                .fillMaxHeight()
                .then(
                    if (wide) {
                        Modifier.drawBehind {
                            val w = 0.5.dp.toPx()
                            drawLine(border, Offset(0f, 0f), Offset(0f, size.height), w)
                            drawLine(border, Offset(size.width, 0f), Offset(size.width, size.height), w)
                        }
                    } else {
                        Modifier
                    },
                ),
        ) { content() }
    }
}

/**
 * NIP-46 `auth_url` handler (spec 12 §2.2): orange snackbar `bunkerAuthNeeded`, 60 s, close icon,
 * action `bunkerAuthAction` opens the URL (http/https only). Never logs the full URL, never opens
 * it automatically.
 */
@Composable
internal fun AuthUrlSnackbarEffect(
    authUrls: Flow<String>,
    acknowledgeAuthUrl: (String) -> Unit,
    host: SnackbarHostState,
    openUrl: (String) -> Boolean,
) {
    val message = stringResource(L10nR.string.bunkerAuthNeeded)
    val action = stringResource(L10nR.string.bunkerAuthAction)
    LaunchedEffect(authUrls, host) {
        authUrls.collect { url ->
            val result = host.showAppSnackbar(
                message = message,
                tone = Tone.Warning,
                durationMillis = AUTH_URL_SNACKBAR_MILLIS,
                actionLabel = action,
                withDismissAction = true,
            )
            // Acknowledged only once the user has dealt with it (action, close or 60 s): if the
            // activity is recreated meanwhile, the new collector gets the replayed URL again.
            acknowledgeAuthUrl(url)
            if (result == SnackbarResult.ActionPerformed && !openUrl(url)) {
                AppLogger.warn(
                    "Nip46",
                    "Freigabe-Seite nicht geöffnet (Schema nicht http(s) oder kein Browser), Host: ${SafeUrl.hostForLog(url)}",
                )
            }
        }
    }
}

/**
 * Home-screen widget targets (spec 12 §2.5.10, spec 14 §3.2): on every resume while the shell is
 * shown, read and clear the pending target once and open its destination.
 */
@Composable
internal fun WidgetTargetEffect(
    stacks: AppBackStacks,
    navigator: AppNavigator,
    consumeWidgetTarget: suspend () -> Destination?,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val consume by rememberUpdatedState(consumeWidgetTarget)
    LaunchedEffect(lifecycleOwner, stacks) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow { stacks.shellActive }.distinctUntilChanged().collect { shell ->
                if (!shell) return@collect
                val destination = runCatching { consume() }
                    .onFailure { AppLogger.warn("Widget", "Widget-Ziel nicht lesbar", it) }
                    .getOrNull()
                // Spec 14 §3.1.5: guard against double routing (two quick taps, resume after resume).
                if (destination != null && stacks.top != destination) navigator.navigate(destination)
            }
        }
    }
}
