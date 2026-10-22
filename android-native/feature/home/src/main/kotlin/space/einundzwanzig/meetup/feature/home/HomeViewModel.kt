package space.einundzwanzig.meetup.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.dart.toStringAsFixed
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.navigation.IdentitySetup
import space.einundzwanzig.meetup.core.navigation.MeetupChat
import space.einundzwanzig.meetup.core.navigation.News
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.calendar.model.FavoriteMeetupCard
import space.einundzwanzig.meetup.data.chat.ChatRelayUnavailable
import space.einundzwanzig.meetup.data.community.CommunityLinks
import space.einundzwanzig.meetup.data.community.plebrap.PlebSongs
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.mempool.BitcoinDashboardData
import space.einundzwanzig.meetup.data.mempool.currency.CurrencyFormat
import space.einundzwanzig.meetup.data.mempool.format.BtcFormat
import space.einundzwanzig.meetup.data.trust.ZoneIdProvider
import space.einundzwanzig.meetup.feature.backup.BackupLauncher
import space.einundzwanzig.meetup.feature.home.data.HomeDataSource
import space.einundzwanzig.meetup.feature.home.data.LiveSession
import space.einundzwanzig.meetup.feature.home.organizer.PortalOrganizerChecker
import space.einundzwanzig.meetup.feature.home.reset.AppResetter
import space.einundzwanzig.meetup.feature.home.tiles.TileId
import space.einundzwanzig.meetup.feature.home.tiles.TileLayout
import space.einundzwanzig.meetup.feature.home.tiles.TileLayoutEngine
import space.einundzwanzig.meetup.feature.home.tiles.TileOrderRepository
import java.time.Instant
import java.time.ZonedDateTime
import javax.inject.Inject

/**
 * Home dashboard (spec 12 §2.5): data loading and lifecycle (§2.5.10), tile engine and edit mode
 * (§2.5.6–§2.5.9), favorites carousel (§8.3–§8.6), portal organizer check (§2.5.11, §8.7) and
 * the reset flow (§2.5.12).
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val data: HomeDataSource,
    private val organizer: PortalOrganizerChecker,
    private val tiles: TileOrderRepository,
    private val resetter: AppResetter,
    private val backup: BackupLauncher,
    refreshRequests: HomeRefreshRequests,
    private val time: TimeSource,
    private val zones: ZoneIdProvider,
    private val log: DiagnosticLog,
) : ViewModel() {

    private val mutableState = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = mutableState.asStateFlow()

    private val events = Channel<HomeEvent>(Channel.BUFFERED)

    /** One-shot effects ([HomeEvent]). */
    val uiEvents: Flow<HomeEvent> = events.receiveAsFlow()

    private var user: UserProfile? = null
    private var lastFavKeys: List<String>? = null
    private var favMeetupDates = 0
    private var myEvents = 0
    private var pictureRequest = 0
    private var unreadableShown = false
    private var portalHintShown = false
    private var identitySetupAsked = false
    private var identityReturn: CompletableDeferred<Unit>? = null
    private var pausedSinceIdentitySetup = false
    private var resumedAtIdentitySetup = false
    private var isResumed = false
    private var resumedOnce = false

    private var favoritesJob: Job? = null
    private var socialJob: Job? = null
    private var sessionJob: Job? = null
    private var midnightJob: Job? = null
    private var btcJob: Job? = null
    private var lastBtcPollMillis: Long? = null
    private val tileDashboard = MutableStateFlow<BitcoinDashboardData?>(null)
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            val layout = safe("Kachel-Reihenfolge") { tiles.load() } ?: TileLayout(
                TileLayoutEngine.DEFAULT_ORDER,
                TileLayoutEngine.DEFAULT_HIDDEN,
            )
            mutableState.update { it.copy(tileOrder = layout.order, hiddenTiles = layout.hidden, layoutLoaded = true) }
        }
        viewModelScope.launch {
            combine(data.badges, data.badgesUnreadable) { badges, unreadable -> badges to unreadable }
                .collect { (badges, unreadable) -> applyBadges(badges, unreadable) }
        }
        viewModelScope.launch { data.profile.collect { onProfileChanged(it) } }
        viewModelScope.launch {
            combine(data.dashboard, data.currency, tileDashboard) { cached, cur, polled ->
                Triple(polled ?: cached, cached, cur)
            }.collect { (d, cached, cur) ->
                val price = d?.priceIn(cur) ?: 0.0
                mutableState.update {
                    it.copy(
                        btc = BtcUi(
                            blockText = d?.let { dd -> BtcFormat.intOrDash(dd.blockHeight.toLong()) } ?: BtcFormat.DASH,
                            live = d != null && !d.isDead,
                            priceText = if (d != null && price > 0) CurrencyFormat.formatPrice(price, cur) else null,
                        ),
                        converterText = BtcFormat.converterTile(cached?.priceIn(cur) ?: 0.0, cur),
                    )
                }
            }
        }
        viewModelScope.launch {
            combine(data.plebrapIndex, data.plebrapLoading, data.plebrapPlaying) { index, loading, playing ->
                val song = index?.let { PlebSongs.all.getOrNull(it) }
                PlebrapUi(song?.title, song?.artist, playing, loading)
            }.collect { p -> mutableState.update { it.copy(plebrap = p) } }
        }
        viewModelScope.launch { refreshRequests.requests.collect { refreshAfterScan() } }
        viewModelScope.launch { loadTileCounters() }
        viewModelScope.launch { safe("News-Widget") { data.publishLatestNews() } }
        viewModelScope.launch { loadDuell() }
        armMidnightRefresh()
        viewModelScope.launch { loadAll() }
    }

    // ---- lifecycle ------------------------------------------------------------------------

    /** Screen visible (`STARTED`): BTC tile polls every 60 s (spec 12 §8.8). */
    fun onStart() {
        if (btcJob?.isActive == true) return
        btcJob = viewModelScope.launch {
            while (isActive) {
                val elapsed = lastBtcPollMillis?.let { time.nowMillis() - it }
                if (elapsed == null || elapsed < 0 || elapsed >= BTC_POLL_MS) {
                    lastBtcPollMillis = time.nowMillis()
                    safe("BTC-Kachel") { data.refreshDashboard() }?.let { tileDashboard.value = it }
                }
                val remaining = lastBtcPollMillis?.let { BTC_POLL_MS - (time.nowMillis() - it) } ?: BTC_POLL_MS
                delay(remaining.coerceIn(1L, BTC_POLL_MS))
            }
        }
    }

    fun onStop() {
        btcJob?.cancel()
        btcJob = null
    }

    /**
     * `RESUMED`: Dart's app-resume work plus the per-route return reloads (selection, chat,
     * calendar, admin panel, rolling QR all come back through here).
     */
    fun onResume() {
        isResumed = true
        val waiter = identityReturn
        // Back from IdentitySetup: Flutter awaited the pushed route, then reloaded the user.
        if (waiter != null && (pausedSinceIdentitySetup || !resumedAtIdentitySetup)) {
            identityReturn = null
            resumedOnce = true
            waiter.complete(Unit)
            return
        }
        if (!resumedOnce) {
            resumedOnce = true
            return
        }
        viewModelScope.launch {
            loadUser(skipOrgCheck = true, refreshDirectory = false)
            loadNextHomeMeetup()
            checkActiveSession()
            loadSocial()
        }
        armMidnightRefresh()
    }

    fun onPause() {
        isResumed = false
        if (identityReturn != null) pausedSinceIdentitySetup = true
    }

    // ---- loading --------------------------------------------------------------------------

    private suspend fun loadAll() {
        loadUser(skipOrgCheck = false)
        val u = user
        if (u != null && (u.nickname == UserProfile.DEFAULT_NICKNAME || u.nickname.isEmpty()) && !identitySetupAsked) {
            identitySetupAsked = true
            val waiter = CompletableDeferred<Unit>()
            identityReturn = waiter
            pausedSinceIdentitySetup = false
            resumedAtIdentitySetup = isResumed
            events.send(HomeEvent.Navigate(IdentitySetup))
            waiter.await()
            loadUser(skipOrgCheck = false)
        }
        loadBadges()
        reVerifyAdmin()
        viewModelScope.launch { safe("Humanity") { data.reverifyHumanity() } }
        viewModelScope.launch { checkActiveSession() }
        loadSocial()
        viewModelScope.launch { safe("Organisatoren") { data.syncOrganicAdmins() } }
        viewModelScope.launch {
            val compromised = safe("Integritaet") { data.deviceCompromised() } ?: false
            mutableState.update { it.copy(deviceCompromised = compromised) }
        }
        loadNextHomeMeetup()
        viewModelScope.launch { loadProfilePicture() }
        viewModelScope.launch { checkNostrNew() }
    }

    private suspend fun loadUser(skipOrgCheck: Boolean, refreshDirectory: Boolean = true) {
        val u = safe("Profil") { data.loadUser() } ?: return
        applyUser(u)
        if (refreshDirectory && (u.homeMeetupId.isNotEmpty() || u.favoriteMeetupIds.isNotEmpty())) {
            viewModelScope.launch { safe("Meetup-Liste") { data.refreshMeetupDirectory() } }
        }
        if (!skipOrgCheck) viewModelScope.launch { checkPortalOrganizer() }
    }

    private fun applyUser(u: UserProfile) {
        val previous = user
        user = u
        if (previous != null && previous.nostrNpub != u.nostrNpub) {
            pictureRequest++
            mutableState.update { it.copy(avatarLocal = null, avatarUrl = null) }
            viewModelScope.launch { loadProfilePicture() }
        }
        mutableState.update {
            it.copy(
                nickname = u.nickname,
                organizerVisible = u.isAdmin || u.isReviewDemo,
                hasHome = u.homeMeetupId.isNotEmpty() || u.favoriteMeetupIds.isNotEmpty(),
            )
        }
    }

    private fun onProfileChanged(u: UserProfile) {
        applyUser(u)
        val known = lastFavKeys ?: return
        if (favKeysOf(u) != known) {
            loadNextHomeMeetup()
            loadSocial()
        }
    }

    private suspend fun loadBadges() {
        val unreadable = safe("Badges") { data.loadBadges() } ?: data.badgesUnreadable.value
        if (unreadable && !unreadableShown) {
            unreadableShown = true
            events.send(HomeEvent.Show(HomeMessage.BadgesUnreadable))
        }
    }

    private fun applyBadges(badges: List<MeetupBadge>, unreadable: Boolean) {
        if (unreadable) {
            mutableState.update {
                it.copy(scorePill = "0.0", reputationValue = "—", countedBadges = 0, badgesUnreadable = true)
            }
            return
        }
        val score = try {
            data.trustScore(badges)
        } catch (e: RuntimeException) {
            log.warn(TAG, "Trust Score nicht berechnet", e)
            null
        }
        val text = (score ?: 0.0).toStringAsFixed(1)
        val counted = badges.count { it.isNostrSigned && !it.isOrganizer }
        mutableState.update {
            it.copy(
                scorePill = text,
                reputationValue = score?.toStringAsFixed(1) ?: "—",
                countedBadges = counted,
                badgesUnreadable = false,
            )
        }
    }

    /** `_reVerifyAdminStatus`; skipped while the badges are unreadable (would revoke a vouch admin). */
    private suspend fun reVerifyAdmin() {
        if (data.badgesUnreadable.value) return
        safe("Admin-Pruefung") { data.reVerifyAdmin(data.badges.value, user?.homeMeetupId.orEmpty()) }
    }

    private suspend fun checkActiveSession() {
        val s = safe("Live-Session") { data.loadSession() }
        if (s == null || time.nowSeconds() > s.expiresAtSeconds) {
            sessionJob?.cancel()
            mutableState.update { it.copy(session = null) }
            return
        }
        startSessionTicker(s)
    }

    private fun startSessionTicker(s: LiveSession) {
        sessionJob?.cancel()
        mutableState.update {
            it.copy(session = SessionUi(s.meetupName, s.isEvent, HomeFormat.sessionTimeLeft(s.expiresAtSeconds - time.nowSeconds())))
        }
        sessionJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                val now = time.nowSeconds()
                if (now > s.expiresAtSeconds) {
                    mutableState.update { it.copy(session = null) }
                    break
                }
                mutableState.update { st ->
                    st.copy(session = st.session?.copy(timeLeft = HomeFormat.sessionTimeLeft(s.expiresAtSeconds - now)))
                }
            }
        }
    }

    private fun favKeysOf(u: UserProfile): List<String> = when {
        u.favoriteMeetupIds.isNotEmpty() -> u.favoriteMeetupIds
        u.homeMeetupId.isNotEmpty() -> listOf(u.homeMeetupId)
        else -> emptyList()
    }

    /** `_loadNextHomeMeetup` + `_loadMeetupRsvps` (latest call wins). */
    private fun loadNextHomeMeetup() {
        favoritesJob?.cancel()
        favoritesJob = viewModelScope.launch {
            val u = user ?: return@launch
            val keys = favKeysOf(u)
            lastFavKeys = keys
            if (keys.isEmpty()) {
                favMeetupDates = 0
                mutableState.update {
                    it.copy(favCards = emptyList(), countdownLoading = false, myEventsCount = myEvents)
                }
                return@launch
            }
            // Flutter renders a fallback card (home meetup, no date) while the portal loads, so the
            // carousel is never an empty gap and the next-event line shows its progress bar.
            mutableState.update {
                it.copy(countdownLoading = true, favCards = it.favCards.ifEmpty { fallbackCards(keys) })
            }
            val snapshot = safe("Favoriten") { data.loadFavorites() }
            if (snapshot == null) {
                mutableState.update { st ->
                    st.copy(countdownLoading = false, favCards = st.favCards.ifEmpty { fallbackCards(keys) })
                }
                return@launch
            }
            val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(time.nowMillis()), zones.zone())
            val cards = snapshot.cards.map { toUi(it, now) }.ifEmpty { fallbackCards(keys) }
            // Only a changed card list/order resets the carousel to page 0; a plain reload after
            // returning from a pushed route (chat, details, calendar) keeps the current page.
            mutableState.update { st ->
                val keysChanged = cards.map { it.key } != st.favCards.map { it.key }
                st.copy(
                    favCards = cards,
                    favEpoch = if (keysChanged) st.favEpoch + 1 else st.favEpoch,
                    countdownLoading = false,
                )
            }
            val rsvps = safe("Zusagen") { data.loadMeetupRsvps(snapshot.dates) } ?: return@launch
            favMeetupDates = rsvps.size
            log.diag(TAG, "Zusagen der Favoriten: ${rsvps.size} Termin(e).")
            mutableState.update { it.copy(myEventsCount = myEvents + favMeetupDates) }
        }
    }

    private fun fallbackCards(keys: List<String>): List<FavCardUi> = keys.map { key ->
        val city = data.favoriteCity(key)
        val info = data.favoriteInfo(key, city)
        FavCardUi(
            key = key,
            label = data.favoriteLabel(key),
            city = city,
            meetupId = info?.meetupId,
            country = info?.country ?: DEFAULT_COUNTRY,
            crestUrl = info?.crestUrl.orEmpty(),
        )
    }

    private fun toUi(card: FavoriteMeetupCard, now: ZonedDateTime): FavCardUi {
        val info = data.favoriteInfo(card.favKey, card.city)
        val start = card.next?.start
        return FavCardUi(
            key = card.favKey,
            label = card.label,
            city = card.city,
            meetupId = info?.meetupId,
            country = info?.country ?: DEFAULT_COUNTRY,
            crestUrl = info?.crestUrl.orEmpty(),
            daysUntil = start?.let { HomeFormat.daysUntil(it, now) },
            dateText = start?.let { HomeFormat.favDate(it.atZone(now.zone).toLocalDate()) }.orEmpty(),
        )
    }

    /** Chat unread of the favorites, then "Meine Termine" — sequential (one relay prompt at a time). */
    private fun loadSocial() {
        socialJob?.cancel()
        socialJob = viewModelScope.launch {
            val u = user
            if (u != null) {
                val keys = LinkedHashSet<String>().apply {
                    if (u.homeMeetupId.isNotEmpty()) add(u.homeMeetupId)
                    addAll(u.favoriteMeetupIds)
                }.toList()
                val unread = if (keys.isEmpty()) {
                    emptyMap()
                } else {
                    safeDebug("Ungelesen-Abfrage fehlgeschlagen") { data.chatUnread(keys) }
                }
                if (unread != null) mutableState.update { it.copy(chatUnread = unread) }
            }
            val mine = safeDebug("Zusagen konnten nicht geladen werden") { data.loadMyEvents() }
            if (mine != null) {
                myEvents = mine.events.size
                mutableState.update { it.copy(myEventsCount = myEvents + favMeetupDates, myEventsUnread = mine.unread) }
            }
        }
    }

    private suspend fun loadTileCounters() {
        val news = safe("News-Zaehler fehlgeschlagen") { data.newsCounters() }
        if (news != null) mutableState.update { it.copy(unreadNews = news.unread, latestNewsTitle = news.latestTitle) }
        val today = safe("Event-Zaehler fehlgeschlagen") { data.eventsToday() }
        if (today != null) mutableState.update { it.copy(eventsToday = today) }
        log.diag("Dashboard", "Kachel-Zaehler: ${news?.unread ?: 0} News, ${today ?: 0} Event(s) heute.")
    }

    private suspend fun loadDuell() {
        val s = safe("SatoshiDuell") { data.duellStatus() } ?: return
        mutableState.update { it.copy(duell = DuellUi(s.myTurn, s.waiting, s.lobby)) }
    }

    private suspend fun loadProfilePicture() {
        val request = ++pictureRequest
        val npub = user?.nostrNpub.orEmpty()
        val local = safe("Profilbild") { data.localPicture() }
        if (request != pictureRequest) return
        if (!local.isNullOrEmpty()) {
            mutableState.update { it.copy(avatarLocal = local, avatarUrl = null) }
            return
        }
        mutableState.update { it.copy(avatarLocal = null) }
        if (npub.isEmpty()) return
        val url = safeDebug("Profilbild nicht geladen") { data.profilePicture(npub) }
        if (request == pictureRequest && user?.nostrNpub == npub) mutableState.update { it.copy(avatarUrl = url) }
    }

    private suspend fun checkNostrNew() {
        val lastSeen = safe("Nostr") { data.nostrLastSeen() } ?: 0L
        mutableState.update { it.copy(nostrHasNew = HomeFormat.isNostrNew(lastSeen, time.nowSeconds())) }
    }

    private fun armMidnightRefresh() {
        midnightJob?.cancel()
        midnightJob = viewModelScope.launch {
            while (isActive) {
                val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(time.nowMillis()), zones.zone())
                delay(HomeFormat.millisUntilMidnightRefresh(now))
                loadNextHomeMeetup()
            }
        }
    }

    // ---- portal ---------------------------------------------------------------------------

    private suspend fun checkPortalOrganizer() {
        val r = organizer.check()
        if (r.showHint && !portalHintShown && user?.isAdmin != true) {
            portalHintShown = true
            events.send(HomeEvent.Show(HomeMessage.PortalHint))
        }
        if (r.showTokenMismatch) events.send(HomeEvent.Show(HomeMessage.TokenMismatch))
    }

    /** `_togglePortalConnection` (snackbar action of the organizer hint). */
    fun togglePortalConnection() {
        viewModelScope.launch {
            if (safe("Portal") { data.portalConnected() } == true) {
                safe("Portal-Abmeldung") { data.portalLogout() }
                return@launch
            }
            events.send(HomeEvent.Show(HomeMessage.PortalConnecting))
            val res = safe("Portal-Anmeldung") { data.portalLogin() }
            events.send(HomeEvent.HideMessage)
            if (res?.ok == true) {
                events.send(HomeEvent.Show(HomeMessage.PortalConnected))
                checkPortalOrganizer()
            } else {
                events.send(HomeEvent.Show(HomeMessage.PortalLoginFailed(res?.error.orEmpty())))
            }
        }
    }

    // ---- refresh --------------------------------------------------------------------------

    /** `_refreshAll` (logo-bar button, or pull-to-refresh with [fromGesture] = no snackbars). */
    fun refreshAll(fromGesture: Boolean) {
        if (state.value.refreshing) return
        mutableState.update { it.copy(refreshing = true, gestureRefreshing = fromGesture) }
        refreshJob = viewModelScope.launch {
            if (!fromGesture) events.send(HomeEvent.Show(HomeMessage.RefreshRunning))
            try {
                loadUser(skipOrgCheck = true)
                viewModelScope.launch { loadProfilePicture() }
                loadBadges()
                checkPortalOrganizer() // FIRST: clears the admin cache on revocation
                reVerifyAdmin() // THEN WoT/vouch verification on a clean cache
                loadNextHomeMeetup()
                loadSocial()
                // Native: the counters also follow a manual refresh (spec 12 §14 risk 7).
                viewModelScope.launch { loadTileCounters() }
                viewModelScope.launch { loadDuell() }
                viewModelScope.launch { checkActiveSession() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug(TAG, "Aktualisierung unvollständig: $e")
            }
            mutableState.update { it.copy(refreshing = false, gestureRefreshing = false) }
            if (!fromGesture) events.send(HomeEvent.Show(HomeMessage.RefreshDone))
        }
    }

    /** `refreshAfterScan` (tab tap / scanner return): badges before the score, then the rest. */
    fun refreshAfterScan() {
        viewModelScope.launch {
            loadBadges()
            loadNextHomeMeetup()
            checkPortalOrganizer()
            loadSocial()
        }
    }

    /** Restore finished in the settings sheet: Flutter reloads everything (`_loadAll`). */
    fun onRestoreFinished() {
        viewModelScope.launch { loadAll() }
    }

    // ---- header / tiles -------------------------------------------------------------------

    fun dismissIntegrityWarning() = mutableState.update { it.copy(integrityDismissed = true) }

    /** Gallery pick for the avatar (only offered without a Nostr picture). */
    fun onAvatarPicked(uri: String) {
        viewModelScope.launch {
            try {
                val path = data.importPicture(uri)
                pictureRequest++
                mutableState.update { it.copy(avatarLocal = path, avatarUrl = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(TAG, "Profilbild nicht übernommen", e)
                events.send(HomeEvent.Show(HomeMessage.ImageLoadError(e.message ?: e.toString())))
            }
        }
    }

    /** `_openMeetupChat`: room lookup with snackbars, then the chat. */
    fun openMeetupChat(card: FavCardUi) {
        viewModelScope.launch {
            events.send(HomeEvent.Show(HomeMessage.ChatSearching))
            val room = try {
                data.findChatRoom(card.key)
            } catch (e: ChatRelayUnavailable) {
                events.send(HomeEvent.HideMessage)
                events.send(HomeEvent.Show(if (e.offline) HomeMessage.ChatOffline else HomeMessage.ChatRelayUnavailable))
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Chat", "Raumsuche fehlgeschlagen", e)
                events.send(HomeEvent.HideMessage)
                events.send(HomeEvent.Show(HomeMessage.ChatRelayUnavailable))
                return@launch
            }
            events.send(HomeEvent.HideMessage)
            if (room == null) {
                events.send(HomeEvent.Show(HomeMessage.ChatNoRoom(card.label)))
            } else {
                events.send(HomeEvent.Navigate(MeetupChat(room.h, room.displayName)))
            }
        }
    }

    /** `_openNews`: the counter resets; News marks the feed (and the widget) read itself. */
    fun openNews() {
        mutableState.update { it.copy(unreadNews = 0) }
        viewModelScope.launch { events.send(HomeEvent.Navigate(News(markAsRead = true))) }
    }

    /** `_openNostr`: remembers the visit (clears the dot), then opens the profile. */
    fun openNostr() {
        mutableState.update { it.copy(nostrHasNew = false) }
        viewModelScope.launch {
            safe("Nostr") { data.setNostrLastSeen(time.nowSeconds()) }
            events.send(HomeEvent.OpenNostrProfile)
        }
    }

    /** SatoshiDuell tile: deep link with the own npub when there is one. */
    fun openSatoshiDuell() {
        viewModelScope.launch {
            val npub = safe("SatoshiDuell") { data.npub() }
            events.send(HomeEvent.OpenUrl(CommunityLinks.satoshiDuell(npub)))
        }
    }

    fun plebrapToggle() {
        viewModelScope.launch { safe("PlebRap") { data.plebrapToggle() } }
    }

    fun plebrapNext() {
        viewModelScope.launch { safe("PlebRap") { data.plebrapNext() } }
    }

    // ---- edit mode / customize ------------------------------------------------------------

    fun onTileLongPress(tile: TileId) = mutableState.update { it.copy(editTileId = tile.id) }

    fun cancelEdit() = mutableState.update { it.copy(editTileId = null) }

    fun toggleAvailable() = mutableState.update { it.copy(availableCollapsed = !it.availableCollapsed) }

    fun togglePin(tile: TileId) {
        val s = state.value
        if (!tile.removable) return
        val hidden = TileLayoutEngine.togglePin(s.hiddenTiles, tile)
        mutableState.update { it.copy(hiddenTiles = hidden, editTileId = null) }
        save(TileLayout(s.tileOrder, hidden))
        log.diag("Dashboard", "Kachel \"${tile.id}\" ${if (tile.id in hidden) "geloest" else "angeheftet"}.")
    }

    fun moveTile(dragged: TileId, target: TileId) {
        val s = state.value
        val order = TileLayoutEngine.moveTile(s.tileOrder, dragged.id, target.id)
        mutableState.update { it.copy(tileOrder = order, editTileId = null) }
        if (order == s.tileOrder) return
        save(TileLayout(order, s.hiddenTiles))
        log.diag("Dashboard", "Kachel \"${dragged.id}\" vor \"${target.id}\" einsortiert.")
    }

    /** "FERTIG" in the customize sheet. */
    fun onCustomizeSaved(order: List<String>, hidden: Set<String>) {
        mutableState.update { it.copy(tileOrder = order, hiddenTiles = hidden, editTileId = null) }
        save(TileLayout(order, hidden))
    }

    private fun save(layout: TileLayout) {
        viewModelScope.launch { safe("Kachel-Reihenfolge speichern") { tiles.save(layout) } }
    }

    // ---- reset ----------------------------------------------------------------------------

    /** Settings sheet "App zurücksetzen". */
    fun startReset() = mutableState.update { it.copy(resetStep = ResetStep.CONFIRM) }

    fun onResetConfirm() = mutableState.update { it.copy(resetStep = ResetStep.BACKUP_OFFER) }

    fun onResetCancel() = mutableState.update { it.copy(resetStep = ResetStep.NONE) }

    /** "Backup erstellen": the reset continues only after a successful backup and a second confirmation. */
    fun onResetCreateBackup() {
        mutableState.update { it.copy(resetStep = ResetStep.NONE) }
        viewModelScope.launch {
            val ok = try {
                backup.export()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(TAG, "Backup vor dem Zurücksetzen fehlgeschlagen", e)
                false
            }
            if (ok) mutableState.update { it.copy(resetStep = ResetStep.BACKUP_DONE) }
        }
    }

    fun onResetSkipBackup() = performReset()

    fun onResetNow() = performReset()

    private fun performReset() {
        mutableState.update { it.copy(resetStep = ResetStep.RUNNING) }
        viewModelScope.launch {
            val ok = resetter.reset()
            if (ok) {
                events.send(HomeEvent.ResetToIntro)
            } else {
                mutableState.update { it.copy(resetStep = ResetStep.NONE) }
                events.send(HomeEvent.Show(HomeMessage.ResetFailed))
            }
        }
    }

    // ---- helpers --------------------------------------------------------------------------

    private suspend fun <T> safe(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(TAG, "$what fehlgeschlagen", e)
        null
    }

    private suspend fun <T> safeDebug(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.debug(TAG, "$what: $e")
        null
    }

    private companion object {
        const val TAG = "Home"
        const val DEFAULT_COUNTRY = "DE"
        const val BTC_POLL_MS = 60_000L
    }
}
