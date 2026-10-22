package space.einundzwanzig.meetup

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.navigation.DeepLinks
import space.einundzwanzig.meetup.core.navigation.EventsTab
import space.einundzwanzig.meetup.core.navigation.HomeTab
import space.einundzwanzig.meetup.core.navigation.Splash
import space.einundzwanzig.meetup.core.navigation.TopLevelDestination
import space.einundzwanzig.meetup.core.navigation.WalletTab
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarController
import space.einundzwanzig.meetup.core.ui.crest.MeetupCrestSource
import space.einundzwanzig.meetup.core.ui.guide.GuideController
import space.einundzwanzig.meetup.core.ui.nostr.NostrProfileSource
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.theme.ThemeMode
import space.einundzwanzig.meetup.data.calendar.widget.WidgetLaunchTargetStore
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.identity.nip55.ExternalSignerBridge
import space.einundzwanzig.meetup.data.meetups.location.LocationPermissionBridge
import space.einundzwanzig.meetup.data.migration.MigrationGate
import space.einundzwanzig.meetup.data.settings.SettingsRepository
import space.einundzwanzig.meetup.data.settings.di.SettingsEntryPoint
import space.einundzwanzig.meetup.data.settings.locale.LocaleController
import space.einundzwanzig.meetup.feature.admin.adminEntries
import space.einundzwanzig.meetup.feature.badges.badgesEntries
import space.einundzwanzig.meetup.feature.bitcoin.bitcoinEntries
import space.einundzwanzig.meetup.feature.chat.chatEntries
import space.einundzwanzig.meetup.feature.community.communityEntries
import space.einundzwanzig.meetup.feature.events.eventsEntries
import space.einundzwanzig.meetup.feature.home.HomeRefreshRequests
import space.einundzwanzig.meetup.feature.home.homeEntries
import space.einundzwanzig.meetup.feature.meetups.meetupsEntries
import space.einundzwanzig.meetup.feature.network.networkEntries
import space.einundzwanzig.meetup.feature.news.newsEntries
import space.einundzwanzig.meetup.feature.onboarding.onboardingEntries
import space.einundzwanzig.meetup.feature.onboarding.splash.SplashViewModel
import space.einundzwanzig.meetup.feature.onboarding.splash.SplashUiState
import space.einundzwanzig.meetup.feature.profile.profileEntries
import space.einundzwanzig.meetup.feature.proofs.proofsEntries
import space.einundzwanzig.meetup.feature.reputation.reputationEntries
import space.einundzwanzig.meetup.feature.settings.settingsEntries
import space.einundzwanzig.meetup.feature.wallet.walletEntries
import space.einundzwanzig.meetup.navigation.AppBackStacks
import space.einundzwanzig.meetup.navigation.AppNavigator
import space.einundzwanzig.meetup.navigation.ExternalUrlOpener
import space.einundzwanzig.meetup.ui.AppRoot
import space.einundzwanzig.meetup.ui.AppRootDependencies
import space.einundzwanzig.meetup.ui.MigrationWaitScreen
import space.einundzwanzig.meetup.ui.keepSystemSplash
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * The only exported activity (spec 14 §1.5; FQCN kept for launcher shortcuts).
 *
 * - `FragmentActivity` so `BiometricPrompt` works (spec 14 F8).
 * - The system splash stays until the Flutter migration gate completed (bounded, 20 s), the theme
 *   mode is known and the splash routing decided (spec 12 §2.3, spec 14 §4.7).
 * - Edge-to-edge, navigation bar hidden with transient swipe (spec 12 F2), predictive back through
 *   Navigation 3, per-app language (LocaleManager on API 33+, context wrapping below).
 * - Hosts Amber (NIP-55) foreground intents for `:data:identity` via ActivityResult.
 * - Phones are portrait-locked, tablets/foldables free (Flutter `applyPhoneOrientation`).
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var migrationGate: MigrationGate
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var localeController: LocaleController
    @Inject lateinit var externalSignerBridge: ExternalSignerBridge
    @Inject lateinit var signingService: SigningService
    @Inject lateinit var snackbarController: AppSnackbarController
    @Inject lateinit var guideController: GuideController
    @Inject lateinit var profileSource: NostrProfileSource
    @Inject lateinit var crestSource: MeetupCrestSource
    @Inject lateinit var homeRefreshRequests: HomeRefreshRequests
    @Inject lateinit var widgetLaunchTargets: WidgetLaunchTargetStore
    @Inject lateinit var locationPermissionBridge: LocationPermissionBridge

    /** One splash session check per start (feature/onboarding README): Activity-scoped. */
    private val splashViewModel: SplashViewModel by viewModels()

    private var gateDone by mutableStateOf(false)

    /** The system splash gave up waiting (20 s); the in-app wait screen stays until the gate completes. */
    private var splashTimedOut by mutableStateOf(false)
    private var themeLoaded = false
    private var firstRouteShown = false
    private var appliedLocale: String? = null

    private var launchedSignerId = 0L
    private val signerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        externalSignerBridge.onResult(launchedSignerId, result.resultCode, result.data)
    }

    private val locationLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[ACCESS_FINE_LOCATION] == true || result[ACCESS_COARSE_LOCATION] == true
        // Use the bridge's launched request: ActivityResult redelivers after Activity recreation.
        locationPermissionBridge.onResult(
            granted = granted,
            permanentlyDenied = !granted &&
                !shouldShowRequestPermissionRationale(ACCESS_FINE_LOCATION) &&
                !shouldShowRequestPermissionRationale(ACCESS_COARSE_LOCATION),
        )
    }

    override fun attachBaseContext(newBase: Context) {
        val locale = EntryPointAccessors.fromApplication(newBase.applicationContext, SettingsEntryPoint::class.java)
            .localeController()
        appliedLocale = if (locale.isManagedByPlatform) null else locale.currentBlocking()
        super.attachBaseContext(locale.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyPhoneOrientation(resources.configuration)
        val splash = installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        launchedSignerId = savedInstanceState?.getLong(STATE_SIGNER_ID) ?: 0L
        gateDone = migrationGate.isComplete
        splash.setKeepOnScreenCondition {
            // An unreadable identity stays on Splash so users can open diagnostics safely.
            // It is a finished routing check, and must be visible beneath the system splash.
            val routeVisible = firstRouteShown || (gateDone && splashViewModel.state.value == SplashUiState.StorageUnavailable)
            keepSystemSplash(gateDone, themeLoaded, routeVisible, splashTimedOut)
        }
        hideNavigationBar()

        lifecycleScope.launch {
            // Stores protect pending secrets. Keep the initial app UI behind migration as well
            // so the startup state stays clear. After 20 s only the system splash
            // gives way to the in-app wait screen; the wait itself has no limit.
            if (withTimeoutOrNull(MIGRATION_TIMEOUT) { migrationGate.await() } == null) {
                AppLogger.warn(TAG, "Datenübernahme dauert länger als 20 s, Warteanzeige statt Splash")
                splashTimedOut = true
                migrationGate.await()
                AppLogger.info(TAG, "Datenübernahme abgeschlossen, Start geht weiter")
            }
            gateDone = true
            val stored = runCatching { settings.getThemeMode() }.getOrNull()
            if (stored != null) withTimeoutOrNull(2.seconds) { settings.themeMode.first { it == stored } }
            themeLoaded = true
        }
        observeLocale()
        hostExternalSigner()
        hostLocationPermission()

        setContent {
            val ready = gateDone
            val themeMode by settings.themeMode.collectAsStateWithLifecycle()
            val haptics by settings.hapticEnabled.collectAsStateWithLifecycle()
            EinundzwanzigTheme(themeMode = themeMode) {
                SystemBarsEffect(themeMode)
                if (ready) {
                    AppContent(haptics)
                } else if (splashTimedOut) {
                    MigrationWaitScreen()
                }
            }
        }
    }

    @Composable
    private fun AppContent(hapticsEnabled: Boolean) {
        val root = rememberNavBackStack(Splash)
        val home = rememberNavBackStack()
        val wallet = rememberNavBackStack()
        val events = rememberNavBackStack()
        val stacks = remember(root, home, wallet, events) {
            AppBackStacks(root, mapOf<TopLevelDestination, MutableList<NavKey>>(HomeTab to home, WalletTab to wallet, EventsTab to events))
        }
        val context = this
        val navigator = remember(stacks) { AppNavigator(stacks) { url -> ExternalUrlOpener.open(context, url) } }
        val featureProvider: (NavKey) -> NavEntry<NavKey> = remember {
            entryProvider {
                onboardingEntries(splashViewModel = { splashViewModel })
                homeEntries()
                walletEntries()
                eventsEntries()
                badgesEntries()
                reputationEntries()
                networkEntries()
                adminEntries()
                proofsEntries()
                profileEntries()
                meetupsEntries()
                chatEntries()
                newsEntries()
                communityEntries()
                bitcoinEntries()
                settingsEntries()
            }
        }
        val deps = remember {
            AppRootDependencies(
                snackbarController = snackbarController,
                guideController = guideController,
                profileSource = profileSource,
                crestSource = crestSource,
                requestHomeRefresh = homeRefreshRequests::request,
                authUrls = signingService.authUrlEvents,
                acknowledgeAuthUrl = signingService::acknowledgeAuthUrl,
                consumeWidgetTarget = { DeepLinks.forWidgetTarget(widgetLaunchTargets.consume()?.wire) },
            )
        }
        AppRoot(
            stacks = stacks,
            navigator = navigator,
            deps = deps,
            hapticsEnabled = hapticsEnabled,
            featureProvider = featureProvider,
            onFirstRoute = { firstRouteShown = true },
        )
    }

    @Composable
    private fun SystemBarsEffect(themeMode: ThemeMode) {
        val dark = when (themeMode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
        }
        DisposableEffect(dark) {
            val style = if (dark) {
                SystemBarStyle.dark(Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            }
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            onDispose { }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_SIGNER_ID, launchedSignerId)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPhoneOrientation(newConfig)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideNavigationBar()
    }

    /** Spec 12 F2: navigation bar hidden, shown transiently by an edge swipe; status bar visible. */
    private fun hideNavigationBar() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    /** Spec 14 §1.5: portrait on phones (smallest width < 600 dp), free on tablets and foldables. */
    private fun applyPhoneOrientation(c: Configuration) {
        val o = if (c.smallestScreenWidthDp in 1..599) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (requestedOrientation != o) requestedOrientation = o
    }

    /** Per-app language (`:data:settings` README): API 33+ via LocaleManager, else recreate. */
    private fun observeLocale() {
        lifecycleScope.launch { runCatching { localeController.syncWithSystem() } }
        if (!localeController.isManagedByPlatform) {
            lifecycleScope.launch {
                localeController.appLocale.collect { if (it != appliedLocale) recreate() }
            }
        }
    }

    /** Nearby, location picker and badge presence all share this foreground permission host. */
    private fun hostLocationPermission() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                locationPermissionBridge.pendingRequest.collect { request ->
                    if (request != null && locationPermissionBridge.onLaunched(request.id)) {
                        try {
                            locationLauncher.launch(arrayOf(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION))
                        } catch (e: RuntimeException) {
                            AppLogger.warn(TAG, "Standortberechtigung konnte nicht angefragt werden", e)
                            locationPermissionBridge.onLaunchFailed(request.id)
                        }
                    }
                }
            }
        }
    }

    /** Amber NIP-55 foreground requests (`:data:identity` README, "Wiring for :app"). */
    private fun hostExternalSigner() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val host = externalSignerBridge.attachHost()
                try {
                    externalSignerBridge.pendingLaunch.collect { launch ->
                        if (launch != null && !launch.launched && externalSignerBridge.onLaunched(launch.id)) {
                            launchedSignerId = launch.id
                            try {
                                signerLauncher.launch(launch.intent)
                            } catch (e: ActivityNotFoundException) {
                                externalSignerBridge.onLaunchFailed(launch.id, e)
                            }
                        }
                    }
                } finally {
                    host.close()
                }
            }
        }
    }

    private companion object {
        const val TAG = "App"
        const val STATE_SIGNER_ID = "external_signer_launch_id"
        val MIGRATION_TIMEOUT = 20.seconds
    }
}
