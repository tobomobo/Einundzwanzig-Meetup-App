package space.einundzwanzig.meetup.feature.home.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.navigation.AdminPanel
import space.einundzwanzig.meetup.core.navigation.BitcoinDashboard
import space.einundzwanzig.meetup.core.navigation.CommunityHub
import space.einundzwanzig.meetup.core.navigation.Converter
import space.einundzwanzig.meetup.core.navigation.EventCalendar
import space.einundzwanzig.meetup.core.navigation.Glossary
import space.einundzwanzig.meetup.core.navigation.Intro
import space.einundzwanzig.meetup.core.navigation.LocalNavigator
import space.einundzwanzig.meetup.core.navigation.MeetupCalendar
import space.einundzwanzig.meetup.core.navigation.MeetupDetails
import space.einundzwanzig.meetup.core.navigation.MeetupSelection
import space.einundzwanzig.meetup.core.navigation.MyEvents
import space.einundzwanzig.meetup.core.navigation.MyTrustNetwork
import space.einundzwanzig.meetup.core.navigation.Navigator
import space.einundzwanzig.meetup.core.navigation.PlebrapLibrary
import space.einundzwanzig.meetup.core.navigation.PortalArea
import space.einundzwanzig.meetup.core.navigation.PortalMeetups
import space.einundzwanzig.meetup.core.navigation.ReputationCard
import space.einundzwanzig.meetup.core.navigation.ReputationQr
import space.einundzwanzig.meetup.core.navigation.RollingQr
import space.einundzwanzig.meetup.core.ui.component.AppSnackbarHost
import space.einundzwanzig.meetup.core.ui.component.LocalAppSnackbarHostState
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.core.ui.component.showAppSnackbar
import space.einundzwanzig.meetup.core.ui.guide.LocalGuideController
import space.einundzwanzig.meetup.core.ui.guide.LocalGuideTargetRegistry
import space.einundzwanzig.meetup.core.ui.guide.guideAnchor
import space.einundzwanzig.meetup.core.ui.theme.EinundzwanzigTheme
import space.einundzwanzig.meetup.core.ui.util.SafeUrlLauncher
import space.einundzwanzig.meetup.data.meetups.portal.PortalLoginFailure
import space.einundzwanzig.meetup.data.community.CommunityLinks
import space.einundzwanzig.meetup.feature.home.FavCardUi
import space.einundzwanzig.meetup.feature.home.HomeAction
import space.einundzwanzig.meetup.feature.home.HomeEvent
import space.einundzwanzig.meetup.feature.home.HomeMessage
import space.einundzwanzig.meetup.feature.home.HomeTour
import space.einundzwanzig.meetup.feature.home.HomeUiState
import space.einundzwanzig.meetup.feature.home.HomeViewModel
import space.einundzwanzig.meetup.feature.home.R
import space.einundzwanzig.meetup.feature.home.SessionUi
import space.einundzwanzig.meetup.feature.home.tiles.TileId
import space.einundzwanzig.meetup.feature.settings.sheet.SettingsSheet
import java.time.LocalDate
import space.einundzwanzig.meetup.core.l10n.R as L10nR

/** Test tags of the dashboard. */
object HomeTags {
    const val SCREEN = "home_screen"
    const val REFRESH = "home_refresh"
    const val REFRESHING = "home_refreshing"
    const val GLOSSARY = "home_glossary"
    const val SETTINGS = "home_settings"
    const val AVATAR = "home_avatar"
    const val SCORE_PILL = "home_score_pill"
    const val INTEGRITY = "home_integrity_warning"
    const val LIVE_SESSION = "home_live_session"
    const val HOME_MEETUP_CTA = "home_meetup_cta"
    const val CAROUSEL = "home_carousel"
    const val COUNTDOWN_LOADING = "home_countdown_loading"
    const val TILE_AREA = "home_tile_area"
    const val AVAILABLE_HEADER = "home_available_header"
    const val PIN_BUTTON = "home_pin_button"
    const val EDIT_TOOLBAR = "home_edit_toolbar"
    const val CUSTOMIZE_SHEET = "home_customize_sheet"
    const val CUSTOMIZE_DONE = "home_customize_done"
    const val RESET_CONFIRM = "home_reset_confirm"
    const val RESET_BACKUP = "home_reset_backup"
    const val RESET_DONE = "home_reset_done"
    const val RESET_RUNNING = "home_reset_running"
    const val TOUR_PROMPT = "home_tour_prompt"
    fun tile(id: String) = "home_tile_$id"
    fun favCard(key: String) = "home_fav_$key"
    fun favAction(key: String, action: String) = "home_fav_${key}_$action"
    fun customizeRow(id: String) = "home_customize_$id"
}

/** Einundzwanzig's Nostr profile (spec 12 §2.5.10 `_openNostr`). */
private const val EINUNDZWANZIG_NPUB = "npub1qv02xpsc3lhxxx5x7xswf88w3u7kykft9ea7t78tz7ywxf7mxs9qrxujnc"

/**
 * Stateful dashboard (spec 12 §2.5): wires the ViewModel, navigation, snackbars, the photo picker,
 * the settings sheet (gear), the customize sheet, the reset dialogs and the home tour.
 */
@Composable
fun HomeRoute(viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val appHost = LocalAppSnackbarHostState.current
    val localHost = remember { SnackbarHostState() }
    val host = appHost ?: localHost
    val scope = rememberCoroutineScope()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showCustomize by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.onAvatarPicked(uri.toString())
    }
    val texts = rememberMessageTexts()
    val currentTexts by rememberUpdatedState(texts)

    LifecycleStartEffect(viewModel) {
        viewModel.onStart()
        onStopOrDispose { viewModel.onStop() }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { viewModel.onPause() }
    }

    fun show(message: HomeMessage) {
        scope.launch {
            val spec = currentTexts(message)
            val result = host.showAppSnackbar(spec.text, spec.tone, spec.durationMillis, spec.action)
            if (message == HomeMessage.PortalHint && result == SnackbarResult.ActionPerformed) {
                viewModel.togglePortalConnection()
            }
        }
    }

    LaunchedEffect(viewModel, navigator) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is HomeEvent.Show -> show(event.message)
                HomeEvent.HideMessage -> host.currentSnackbarData?.dismiss()
                is HomeEvent.Navigate -> navigator.navigate(event.key)
                is HomeEvent.OpenUrl -> if (!SafeUrlLauncher.open(context, event.url)) show(HomeMessage.CouldNotOpen(event.url))
                HomeEvent.OpenNostrProfile -> openNostrProfile(context)
                HomeEvent.ResetToIntro -> navigator.resetTo(Intro)
            }
        }
    }

    HomeTourGate(LocalGuideController.current, LocalGuideTargetRegistry.current)

    HomeScreen(
        state = state,
        snackbarHostState = if (appHost == null) localHost else null,
        onAction = { action ->
            handleAction(action, navigator, viewModel, context, ::show) { what ->
                when (what) {
                    Panel.SETTINGS -> showSettings = true
                    Panel.CUSTOMIZE -> showCustomize = true
                    Panel.PICKER -> picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
        },
    )

    SettingsSheet(
        visible = showSettings,
        onDismissRequest = { showSettings = false },
        onResetApp = {
            showSettings = false
            viewModel.startReset()
        },
        onRestoreFinished = viewModel::onRestoreFinished,
    )
    if (showCustomize) {
        CustomizeSheet(
            order = state.tileOrder,
            hidden = state.hiddenTiles,
            isVisible = state::isTileVisible,
            onSave = viewModel::onCustomizeSaved,
            onDismiss = { showCustomize = false },
        )
    }
}

private enum class Panel { SETTINGS, CUSTOMIZE, PICKER }

private fun handleAction(
    action: HomeAction,
    navigator: Navigator,
    viewModel: HomeViewModel,
    context: Context,
    show: (HomeMessage) -> Unit,
    open: (Panel) -> Unit,
) {
    when (action) {
        HomeAction.Refresh -> viewModel.refreshAll(fromGesture = false)
        HomeAction.PullRefresh -> viewModel.refreshAll(fromGesture = true)
        HomeAction.OpenGlossary -> navigator.navigate(Glossary)
        HomeAction.OpenSettings -> open(Panel.SETTINGS)
        HomeAction.OpenReputationCard -> navigator.navigate(ReputationCard)
        HomeAction.PickAvatar -> open(Panel.PICKER)
        HomeAction.DismissIntegrityWarning -> viewModel.dismissIntegrityWarning()
        HomeAction.OpenLiveSession -> navigator.navigate(RollingQr)
        HomeAction.SelectHomeMeetup -> navigator.navigate(MeetupSelection)
        HomeAction.OpenCustomize -> {
            viewModel.cancelEdit()
            open(Panel.CUSTOMIZE)
        }
        is HomeAction.OpenMeetupEvents -> navigator.navigate(
            MeetupCalendar(initialSearch = action.card.city, initialMeetupId = action.card.meetupId),
        )
        is HomeAction.OpenMeetupChat -> viewModel.openMeetupChat(action.card)
        is HomeAction.OpenMeetupInfo -> action.card.meetupId?.let { navigator.navigate(MeetupDetails(it)) }
        is HomeAction.TileTap -> when (action.tile) {
            TileId.EventChats -> navigator.navigate(MyEvents)
            TileId.Reputation -> navigator.navigate(ReputationQr)
            TileId.TrustNetwork -> navigator.navigate(MyTrustNetwork)
            TileId.Community -> navigator.navigate(CommunityHub)
            TileId.Events -> navigator.navigate(EventCalendar.forDay(LocalDate.now()))
            TileId.BtcDashboard -> navigator.navigate(BitcoinDashboard)
            TileId.Converter -> navigator.navigate(Converter)
            TileId.News -> viewModel.openNews()
            TileId.Portal -> navigator.navigate(PortalMeetups)
            TileId.Shoutout -> openExternal(context, CommunityLinks.SHOUTOUT, show)
            TileId.Podcast -> openExternal(context, CommunityLinks.PODCAST, show)
            TileId.SatoshiDuell -> viewModel.openSatoshiDuell()
            TileId.PortalArea -> navigator.navigate(PortalArea)
            TileId.Nostr -> viewModel.openNostr()
            TileId.Organisator -> navigator.navigate(AdminPanel)
            TileId.PlebRap, TileId.TrustScore, TileId.PortalConnect, TileId.HomeMeetup -> Unit
        }
        HomeAction.PlebrapToggle -> viewModel.plebrapToggle()
        HomeAction.PlebrapNext -> viewModel.plebrapNext()
        HomeAction.PlebrapLibrary -> navigator.navigate(PlebrapLibrary)
        is HomeAction.TileLongPress -> viewModel.onTileLongPress(action.tile)
        HomeAction.CancelEdit -> viewModel.cancelEdit()
        is HomeAction.TogglePin -> viewModel.togglePin(action.tile)
        is HomeAction.MoveTile -> viewModel.moveTile(action.dragged, action.target)
        HomeAction.ToggleAvailable -> viewModel.toggleAvailable()
        HomeAction.ResetConfirm -> viewModel.onResetConfirm()
        HomeAction.ResetCreateBackup -> viewModel.onResetCreateBackup()
        HomeAction.ResetSkipBackup -> viewModel.onResetSkipBackup()
        HomeAction.ResetNow -> viewModel.onResetNow()
        HomeAction.ResetCancel -> viewModel.onResetCancel()
    }
}

private fun openExternal(context: Context, url: String, show: (HomeMessage) -> Unit) {
    if (!SafeUrlLauncher.open(context, url)) show(HomeMessage.CouldNotOpen(url))
}

/** `nostr:` link to a Nostr app; without one (or on failure) njump.me in the browser. */
private fun openNostrProfile(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostr:$EINUNDZWANZIG_NPUB")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        SafeUrlLauncher.open(context, CommunityLinks.NOSTR_PROFILE)
    } catch (e: SecurityException) {
        SafeUrlLauncher.open(context, CommunityLinks.NOSTR_PROFILE)
    }
}

/** Resolved snackbar of a [HomeMessage]. */
private data class MessageSpec(val text: String, val tone: Tone, val durationMillis: Long, val action: String? = null)

@Composable
private fun rememberMessageTexts(): (HomeMessage) -> MessageSpec {
    val refreshRunning = stringResource(L10nR.string.refreshRunning)
    val refreshDone = stringResource(L10nR.string.refreshDone)
    val unreadable = stringResource(L10nR.string.badgesUnreadableBody)
    val hint = stringResource(L10nR.string.portalConnectForOrganizer)
    val connect = stringResource(L10nR.string.portalConnect)
    val mismatch = stringResource(L10nR.string.portalTokenMismatch)
    val connecting = stringResource(L10nR.string.portalConnecting)
    val connected = stringResource(L10nR.string.portalConnected)
    val loginFailed = stringResource(L10nR.string.portalLoginFailed)
    val searching = stringResource(L10nR.string.chatSearching)
    val offline = stringResource(L10nR.string.chatOffline)
    val relay = stringResource(L10nR.string.chatRelayUnavailable)
    val resetFailed = stringResource(R.string.native_home_reset_failed)
    val resources = LocalResources.current
    return remember(refreshRunning, unreadable, hint, mismatch, searching, resetFailed, resources) {
        { m ->
            when (m) {
                HomeMessage.RefreshRunning -> MessageSpec(refreshRunning, Tone.Neutral, 2_000)
                HomeMessage.RefreshDone -> MessageSpec(refreshDone, Tone.Success, 4_000)
                HomeMessage.BadgesUnreadable -> MessageSpec(unreadable, Tone.Warning, 8_000)
                HomeMessage.PortalHint -> MessageSpec(hint, Tone.Warning, 8_000, connect)
                HomeMessage.TokenMismatch -> MessageSpec(mismatch, Tone.Warning, 6_000)
                HomeMessage.PortalConnecting -> MessageSpec(connecting, Tone.Neutral, 8_000)
                HomeMessage.PortalConnected -> MessageSpec(connected, Tone.Success, 4_000)
                is HomeMessage.PortalLoginFailed -> {
                    val cause = PortalLoginFailure.cause(m.error).let {
                        if (it == PortalLoginFailure.TOKEN_NOT_STORED) resources.getString(R.string.native_home_token_not_stored) else it
                    }
                    MessageSpec("$loginFailed: $cause", Tone.Error, 4_000)
                }
                HomeMessage.ChatSearching -> MessageSpec(searching, Tone.Neutral, 4_000)
                HomeMessage.ChatOffline -> MessageSpec(offline, Tone.Neutral, 5_000)
                HomeMessage.ChatRelayUnavailable -> MessageSpec(relay, Tone.Neutral, 5_000)
                is HomeMessage.ChatNoRoom -> MessageSpec(resources.getString(L10nR.string.chatNoRoom, m.label), Tone.Neutral, 4_000)
                is HomeMessage.ImageLoadError ->
                    MessageSpec(resources.getString(L10nR.string.homeImageLoadError, m.detail), Tone.Error, 4_000)
                is HomeMessage.CouldNotOpen -> MessageSpec(resources.getString(L10nR.string.homeCouldNotOpen, m.url), Tone.Error, 4_000)
                HomeMessage.ResetFailed -> MessageSpec(resetFailed, Tone.Error, 6_000)
            }
        }
    }
}

/**
 * Stateless dashboard (spec 12 §2.5.1): logo bar, profile header, device warning, live session,
 * home-meetup area, tile grid with the "Verfügbar" section; reset dialogs; a floating toolbar
 * while a tile is in edit mode.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
) {
    Scaffold(
        modifier = modifier.testTag(HomeTags.SCREEN),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { snackbarHostState?.let { AppSnackbarHost(it) } },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize().padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 12.dp)) {
                LogoBar(
                    refreshing = state.refreshing,
                    onRefresh = { onAction(HomeAction.Refresh) },
                    onGlossary = { onAction(HomeAction.OpenGlossary) },
                    onSettings = { onAction(HomeAction.OpenSettings) },
                )
                Spacer(Modifier.height(6.dp))
                ProfileHeader(
                    nickname = state.nickname,
                    avatarLocal = state.avatarLocal,
                    avatarUrl = state.avatarUrl,
                    scorePill = state.scorePill,
                    onAvatarClick = if (state.avatarUrl == null) ({ onAction(HomeAction.PickAvatar) }) else null,
                    onScoreClick = { onAction(HomeAction.OpenReputationCard) },
                )
                Spacer(Modifier.height(14.dp))
                if (state.showIntegrityWarning) {
                    IntegrityWarningCard(onDismiss = { onAction(HomeAction.DismissIntegrityWarning) })
                    Spacer(Modifier.height(10.dp))
                }
                state.session?.let { session ->
                    LiveSessionCard(session, onClick = { onAction(HomeAction.OpenLiveSession) })
                    Spacer(Modifier.height(10.dp))
                }
                Box(Modifier.fillMaxWidth().guideAnchor(HomeTour.HOME_MEETUP)) {
                    if (!state.hasHome) {
                        HomeMeetupCta(
                            onClick = { onAction(HomeAction.SelectHomeMeetup) },
                            onLongClick = { onAction(HomeAction.OpenCustomize) },
                        )
                    } else {
                        FavoritesCarousel(
                            cards = state.favCards,
                            epoch = state.favEpoch,
                            chatUnread = state.chatUnread,
                            countdownLoading = state.countdownLoading,
                            onEvents = { onAction(HomeAction.OpenMeetupEvents(it)) },
                            onChat = { onAction(HomeAction.OpenMeetupChat(it)) },
                            onInfo = { onAction(HomeAction.OpenMeetupInfo(it)) },
                            onLongPress = { onAction(HomeAction.OpenCustomize) },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    TileArea(state, maxHeight.value, onAction)
                }
            }
            AnimatedVisibility(
                visible = state.editTileId != null,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            ) {
                HorizontalFloatingToolbar(
                    expanded = true,
                    colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                    modifier = Modifier.testTag(HomeTags.EDIT_TOOLBAR),
                ) {
                    TextButton(onClick = { onAction(HomeAction.OpenCustomize) }) {
                        Icon(Icons.Rounded.Tune, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.native_home_edit_customize))
                    }
                    TextButton(onClick = { onAction(HomeAction.CancelEdit) }) {
                        Icon(Icons.Rounded.Check, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.native_home_edit_done))
                    }
                }
            }
        }
    }
    ResetDialogs(state.resetStep, onAction)
}

private val PreviewState = HomeUiState(
    nickname = "Satoshi",
    scorePill = "12.4",
    reputationValue = "12.4",
    countedBadges = 7,
    hasHome = true,
    favCards = listOf(
        FavCardUi("107", "Einundzwanzig Wien", "Wien", "107", "AT", "", daysUntil = 1, dateText = "24.10.2026"),
        FavCardUi("108", "Berlin", "Berlin", "108", "DE", "", daysUntil = null),
    ),
    chatUnread = mapOf("107" to 3),
    myEventsCount = 2,
    myEventsUnread = 1,
    unreadNews = 4,
    latestNewsTitle = "Der Halving-Countdown läuft",
    session = SessionUi("Einundzwanzig Wien", isEvent = false, timeLeft = "3h 12m"),
    layoutLoaded = true,
    availableCollapsed = false,
)

@Preview(name = "Home", showBackground = true, backgroundColor = 0xFF0C0C0E, heightDp = 1100)
@Composable
private fun HomeScreenPreview() {
    EinundzwanzigTheme { HomeScreen(PreviewState, onAction = {}) }
}

@Preview(name = "Home, no favorite, edit mode", showBackground = true, backgroundColor = 0xFF0C0C0E, heightDp = 1000)
@Composable
private fun HomeScreenEditPreview() {
    EinundzwanzigTheme {
        HomeScreen(
            PreviewState.copy(hasHome = false, favCards = emptyList(), session = null, deviceCompromised = true, editTileId = "reputation"),
            onAction = {},
        )
    }
}
