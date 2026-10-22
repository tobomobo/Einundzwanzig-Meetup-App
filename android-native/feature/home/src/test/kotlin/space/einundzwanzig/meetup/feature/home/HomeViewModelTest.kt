package space.einundzwanzig.meetup.feature.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import space.einundzwanzig.meetup.core.navigation.IdentitySetup
import space.einundzwanzig.meetup.core.navigation.MeetupChat
import space.einundzwanzig.meetup.core.navigation.News
import space.einundzwanzig.meetup.data.calendar.model.MyMeetupDate
import space.einundzwanzig.meetup.data.chat.ChatRelayUnavailable
import space.einundzwanzig.meetup.data.chat.ChatRoom
import space.einundzwanzig.meetup.data.meetups.portal.PortalResult
import space.einundzwanzig.meetup.data.mempool.BitcoinDashboardData
import space.einundzwanzig.meetup.feature.home.data.FavoriteMeetupInfo
import space.einundzwanzig.meetup.feature.home.data.FavoritesSnapshot
import space.einundzwanzig.meetup.feature.home.data.LiveSession
import space.einundzwanzig.meetup.feature.home.data.MyEventsSnapshot
import space.einundzwanzig.meetup.feature.home.data.NewsCounters
import space.einundzwanzig.meetup.feature.home.tiles.TileId
import space.einundzwanzig.meetup.feature.home.tiles.TileOrderRepository
import java.time.Duration

class HomeViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val scope = CoroutineScope(main.dispatcher)
    private val fx = VmFixture()

    @After
    fun tearDown() = scope.cancel()

    private fun HomeViewModel.events(): MutableList<HomeEvent> {
        val list = mutableListOf<HomeEvent>()
        scope.launch { uiEvents.collect { list += it } }
        return list
    }

    private fun messages(events: List<HomeEvent>) = events.filterIsInstance<HomeEvent.Show>().map { it.message }

    @Test
    fun `initial load fills header, score and counters`() {
        fx.data.user = profile(nickname = "Satoshi")
        fx.data.news = NewsCounters(4, "Halving")
        fx.data.today = 2
        fx.data.myEvents = MyEventsSnapshot(listOf(calendarEvent("a")), unread = 3)
        val vm = fx.create()
        val s = vm.state.value
        assertEquals("Satoshi", s.nickname)
        assertEquals("3.3", s.scorePill)
        assertEquals("3.3", s.reputationValue)
        assertEquals(4, s.unreadNews)
        assertEquals("Halving", s.latestNewsTitle)
        assertEquals(2, s.eventsToday)
        assertEquals(1, s.myEventsCount)
        assertEquals(3, s.myEventsUnread)
        assertTrue(s.layoutLoaded)
        assertFalse(s.organizerVisible)
        assertFalse(s.hasHome)
        assertEquals(1, fx.data.loadBadgeCalls)
        assertEquals(1, fx.data.reVerifyCalls)
        assertEquals(1, fx.data.humanityCalls)
        assertEquals(1, fx.data.organicCalls)
        assertEquals(1, fx.data.publishedNews)
        assertTrue(s.nostrHasNew)
    }

    @Test
    fun `unreadable badges warn once, show dashes and skip admin re-verification`() {
        fx.data.unreadableFlow.value = true
        val vm = fx.create()
        val events = vm.events()
        val s = vm.state.value
        assertEquals("0.0", s.scorePill)
        assertEquals("—", s.reputationValue)
        assertTrue(s.badgesUnreadable)
        assertEquals(0, fx.data.reVerifyCalls)
        assertEquals(listOf(HomeMessage.BadgesUnreadable), messages(events))
        vm.refreshAll(fromGesture = true)
        assertEquals(1, messages(events).count { it == HomeMessage.BadgesUnreadable })
    }

    @Test
    fun `anonymous user is sent to identity setup and loading continues after the return`() {
        fx.data.user = profile(nickname = "Anon")
        val vm = fx.create()
        val events = vm.events()
        assertEquals(HomeEvent.Navigate(IdentitySetup), events.first())
        assertEquals(0, fx.data.loadBadgeCalls)
        // Home was not resumed yet when IdentitySetup was pushed: the next resume is the return.
        fx.data.user = profile(nickname = "Hal")
        vm.onResume()
        assertEquals(1, fx.data.loadBadgeCalls)
        assertEquals("Hal", vm.state.value.nickname)
        assertEquals(1, events.count { it == HomeEvent.Navigate(IdentitySetup) })
    }

    @Test
    fun `favorite cards are mapped with countdown, info and RSVP count`() {
        fx.data.user = profile(favorites = listOf("107", "108"))
        fx.data.favorites = FavoritesSnapshot(
            listOf(fx.data.card("107", NOW.plus(Duration.ofDays(1))), fx.data.card("108", null)),
            loaded = true,
            dates = listOf(meetupDate(NOW, 5)),
        )
        fx.data.infos = mapOf("107" to FavoriteMeetupInfo("107", "AT", "https://x/logo.png"))
        fx.data.rsvps = listOf(MyMeetupDate("107", "Wien", meetupDate(NOW, 5)), MyMeetupDate("107", "Wien", meetupDate(NOW, 6)))
        val vm = fx.create()
        val s = vm.state.value
        assertTrue(s.hasHome)
        assertEquals(listOf("107", "108"), s.favCards.map { it.key })
        val first = s.favCards[0]
        assertEquals(1L, first.daysUntil)
        assertEquals("23.10.2026", first.dateText)
        assertEquals("AT", first.country)
        assertEquals("107", first.meetupId)
        assertEquals("https://x/logo.png", first.crestUrl)
        assertNull(s.favCards[1].daysUntil)
        assertNull(s.favCards[1].meetupId)
        assertEquals("DE", s.favCards[1].country)
        assertFalse(s.countdownLoading)
        assertEquals(2, s.myEventsCount)
    }

    @Test
    fun `fallback cards and countdown loading show while favorites are still loading`() {
        fx.data.user = profile(favorites = listOf("107", "108"))
        val gate = CompletableDeferred<Unit>()
        fx.data.favoritesGate = gate
        fx.data.favorites = FavoritesSnapshot(
            listOf(fx.data.card("108", NOW.plus(Duration.ofDays(1))), fx.data.card("107", null)),
            loaded = true,
        )
        val vm = fx.create()
        val loading = vm.state.value
        assertTrue(loading.hasHome)
        assertTrue(loading.countdownLoading)
        assertEquals(listOf("107", "108"), loading.favCards.map { it.key })
        assertEquals("Label 107", loading.favCards[0].label)
        assertNull(loading.favCards[0].daysUntil)
        gate.complete(Unit)
        val done = vm.state.value
        assertFalse(done.countdownLoading)
        assertEquals(listOf("108", "107"), done.favCards.map { it.key })
        assertEquals(1L, done.favCards[0].daysUntil)
    }

    @Test
    fun `reloading the same favorites keeps the carousel page, a changed list resets it`() {
        fx.data.user = profile(favorites = listOf("107", "108"))
        fx.data.favorites = FavoritesSnapshot(
            listOf(fx.data.card("107", NOW.plus(Duration.ofDays(1))), fx.data.card("108", null)),
            loaded = true,
        )
        val vm = fx.create()
        val epoch = vm.state.value.favEpoch
        vm.onResume() // first
        vm.onResume() // back from e.g. the chat of the 2nd favorite
        assertEquals(epoch, vm.state.value.favEpoch)
        fx.data.favorites = FavoritesSnapshot(
            listOf(fx.data.card("108", NOW), fx.data.card("107", NOW.plus(Duration.ofDays(1)))),
            loaded = true,
        )
        vm.onResume()
        assertEquals(listOf("108", "107"), vm.state.value.favCards.map { it.key })
        assertEquals(epoch + 1, vm.state.value.favEpoch)
    }

    @Test
    fun `failed favorites load falls back to cards without dates`() {
        fx.data.user = profile(home = "Berlin")
        fx.data.favorites = FavoritesSnapshot(emptyList(), loaded = false)
        val vm = fx.create()
        val cards = vm.state.value.favCards
        assertEquals(listOf("Berlin"), cards.map { it.key })
        assertEquals("Label Berlin", cards[0].label)
        assertNull(cards[0].daysUntil)
    }

    @Test
    fun `favorites reload when the profile changes`() {
        fx.data.user = profile(favorites = listOf("107"))
        val vm = fx.create()
        val before = fx.data.favoritesCalls
        fx.data.user = profile(favorites = listOf("107", "200"))
        assertEquals(before + 1, fx.data.favoritesCalls)
        assertTrue(vm.state.value.hasHome)
    }

    @Test
    fun `chat without room shows the no-room snackbar`() {
        val vm = fx.create()
        val events = vm.events()
        val card = FavCardUi("107", "Wien", "Wien")
        vm.openMeetupChat(card)
        assertEquals(
            listOf(HomeEvent.Show(HomeMessage.ChatSearching), HomeEvent.HideMessage, HomeEvent.Show(HomeMessage.ChatNoRoom("Wien"))),
            events,
        )
    }

    @Test
    fun `chat relay errors distinguish offline`() {
        val vm = fx.create()
        val events = vm.events()
        fx.data.roomError = ChatRelayUnavailable(offline = true)
        vm.openMeetupChat(FavCardUi("107", "Wien", "Wien"))
        fx.data.roomError = ChatRelayUnavailable(offline = false)
        vm.openMeetupChat(FavCardUi("107", "Wien", "Wien"))
        assertEquals(
            listOf(HomeMessage.ChatSearching, HomeMessage.ChatOffline, HomeMessage.ChatSearching, HomeMessage.ChatRelayUnavailable),
            messages(events),
        )
    }

    @Test
    fun `found chat room opens the meetup chat`() {
        val vm = fx.create()
        val events = vm.events()
        fx.data.room = ChatRoom(h = "wien", name = "Wien")
        vm.openMeetupChat(FavCardUi("107", "Wien", "Wien"))
        assertEquals(HomeEvent.Navigate(MeetupChat("wien", "Wien")), events.last())
    }

    @Test
    fun `news opens with mark-as-read and resets the counter`() {
        fx.data.news = NewsCounters(5, null)
        val vm = fx.create()
        val events = vm.events()
        vm.openNews()
        assertEquals(0, vm.state.value.unreadNews)
        assertEquals(HomeEvent.Navigate(News(markAsRead = true)), events.last())
    }

    @Test
    fun `nostr tile remembers the visit`() {
        val vm = fx.create()
        val events = vm.events()
        assertTrue(vm.state.value.nostrHasNew)
        vm.openNostr()
        assertFalse(vm.state.value.nostrHasNew)
        assertEquals(fx.time.nowSeconds(), fx.data.lastSeen)
        assertEquals(HomeEvent.OpenNostrProfile, events.last())
    }

    @Test
    fun `satoshiduell link carries the npub`() {
        val vm = fx.create()
        val events = vm.events()
        vm.openSatoshiDuell()
        assertEquals(HomeEvent.OpenUrl("https://satoshiduell.de/?npub=npub1me"), events.last())
    }

    @Test
    fun `edit mode pins and moves tiles and persists`() = runBlocking {
        val vm = fx.create()
        vm.onTileLongPress(TileId.News)
        assertEquals("news", vm.state.value.editTileId)
        vm.togglePin(TileId.News)
        assertNull(vm.state.value.editTileId)
        assertFalse("news" in vm.state.value.hiddenTiles)
        assertFalse("news" in fx.store.getStringList(TileOrderRepository.KEY_HIDDEN)!!)

        vm.moveTile(TileId.Converter, TileId.EventChats)
        assertEquals(listOf("home_meetup", "converter", "event_chats"), vm.state.value.tileOrder.take(3))
        assertEquals(vm.state.value.tileOrder, fx.store.getStringList(TileOrderRepository.KEY_ORDER))

        vm.togglePin(TileId.EventChats) // not removable
        assertFalse("event_chats" in vm.state.value.hiddenTiles)
    }

    @Test
    fun `customize sheet result is saved`() = runBlocking {
        val vm = fx.create()
        vm.onCustomizeSaved(listOf("reputation", "home_meetup"), setOf("news"))
        assertEquals(listOf("reputation", "home_meetup"), fx.store.getStringList(TileOrderRepository.KEY_ORDER))
        assertEquals(listOf("news"), fx.store.getStringList(TileOrderRepository.KEY_HIDDEN))
    }

    @Test
    fun `available section toggles and organizer shows for admins and review demo`() {
        val vm = fx.create()
        assertTrue(vm.state.value.availableCollapsed)
        vm.toggleAvailable()
        assertFalse(vm.state.value.availableCollapsed)
        fx.data.user = profile(reviewDemo = true)
        assertTrue(vm.state.value.organizerVisible)
        assertTrue(vm.state.value.pinnedRows.flatten().contains(TileId.Organisator))
    }

    @Test
    fun `reset without backup wipes and goes to the intro`() {
        val vm = fx.create()
        val events = vm.events()
        vm.startReset()
        assertEquals(ResetStep.CONFIRM, vm.state.value.resetStep)
        vm.onResetConfirm()
        assertEquals(ResetStep.BACKUP_OFFER, vm.state.value.resetStep)
        vm.onResetSkipBackup()
        assertEquals(1, fx.resetter.calls)
        assertEquals(HomeEvent.ResetToIntro, events.last())
    }

    @Test
    fun `reset aborts when the requested backup fails`() {
        fx.backup.result = false
        val vm = fx.create()
        vm.startReset()
        vm.onResetConfirm()
        vm.onResetCreateBackup()
        assertEquals(1, fx.backup.exports)
        assertEquals(ResetStep.NONE, vm.state.value.resetStep)
        assertEquals(0, fx.resetter.calls)
    }

    @Test
    fun `reset after a successful backup needs a second confirmation`() {
        val vm = fx.create()
        val events = vm.events()
        vm.startReset()
        vm.onResetConfirm()
        vm.onResetCreateBackup()
        assertEquals(ResetStep.BACKUP_DONE, vm.state.value.resetStep)
        assertEquals(0, fx.resetter.calls)
        vm.onResetCancel()
        assertEquals(ResetStep.NONE, vm.state.value.resetStep)
        vm.startReset()
        vm.onResetConfirm()
        vm.onResetCreateBackup()
        vm.onResetNow()
        assertEquals(1, fx.resetter.calls)
        assertEquals(HomeEvent.ResetToIntro, events.last())
    }

    @Test
    fun `failed reset stays on home with an error`() {
        fx.resetter.ok = false
        val vm = fx.create()
        val events = vm.events()
        vm.startReset()
        vm.onResetConfirm()
        vm.onResetSkipBackup()
        assertEquals(ResetStep.NONE, vm.state.value.resetStep)
        assertEquals(HomeMessage.ResetFailed, messages(events).last())
    }

    @Test
    fun `manual refresh shows running and done snackbars, gesture refresh none`() {
        val vm = fx.create()
        val events = vm.events()
        vm.refreshAll(fromGesture = false)
        assertEquals(listOf(HomeMessage.RefreshRunning, HomeMessage.RefreshDone), messages(events))
        assertFalse(vm.state.value.refreshing)
        events.clear()
        vm.refreshAll(fromGesture = true)
        assertTrue(messages(events).isEmpty())
        assertEquals(3, fx.data.loadBadgeCalls)
    }

    @Test
    fun `portal hint shows once for non-admins with an external signer`() {
        fx.ports.token = false
        fx.ports.external = true
        val vm = fx.create()
        val events = vm.events()
        vm.refreshAll(fromGesture = true)
        assertEquals(1, messages(events).count { it == HomeMessage.PortalHint })
    }

    @Test
    fun `portal connection toggle reports login failure`() {
        fx.data.loginResult = PortalResult(ok = false, statusCode = 401, error = "nein")
        val vm = fx.create()
        val events = vm.events()
        vm.togglePortalConnection()
        assertEquals(
            listOf(HomeEvent.Show(HomeMessage.PortalConnecting), HomeEvent.HideMessage, HomeEvent.Show(HomeMessage.PortalLoginFailed("nein"))),
            events,
        )
        fx.data.connected = true
        vm.togglePortalConnection()
        assertEquals(1, fx.data.logouts)
    }

    @Test
    fun `live session card shows the remaining time, expired sessions hide`() {
        fx.data.session = LiveSession("evt:abc", "Konferenz", fx.time.nowSeconds() + 3725)
        val vm = fx.create()
        val s = vm.state.value.session!!
        assertTrue(s.isEvent)
        assertEquals("1h 02m", s.timeLeft)
        fx.data.session = LiveSession("m", "Alt", fx.time.nowSeconds() - 1)
        vm.onResume()
        vm.onResume()
        assertNull(vm.state.value.session)
    }

    @Test
    fun `refresh requests reload badges, favorites and my events`() {
        val vm = fx.create()
        val badgeCalls = fx.data.loadBadgeCalls
        val myEvents = fx.data.myEventsCalls
        fx.refresh.request()
        assertEquals(badgeCalls + 1, fx.data.loadBadgeCalls)
        assertEquals(myEvents + 1, fx.data.myEventsCalls)
        assertTrue(vm.state.value.layoutLoaded)
    }

    @Test
    fun `return to home reloads favorites, chat and my events`() {
        fx.data.user = profile(favorites = listOf("107"))
        val vm = fx.create()
        val fav = fx.data.favoritesCalls
        val chat = fx.data.chatUnreadCalls
        vm.onResume() // first
        assertEquals(fav, fx.data.favoritesCalls)
        vm.onResume() // returning
        assertEquals(fav + 1, fx.data.favoritesCalls)
        assertEquals(chat + 1, fx.data.chatUnreadCalls)
    }

    @Test
    fun `btc and converter tiles follow the dashboard and currency`() {
        val vm = fx.create()
        assertEquals("––", vm.state.value.btc.blockText)
        assertNull(vm.state.value.converterText)
        fx.data.dashboardFlow.value = BitcoinDashboardData(
            blockHeight = 950_000, feeLow = 1, feeMedium = 2, feeHigh = 3, priceEur = 56_200.0, priceUsd = 60_000.0,
            supply = 0, hashrateEhs = 0.0, difficultyChangePct = 0.0, difficultyRemainingBlocks = 0, lnCapacityBtc = 0.0,
            lnNodeCount = 0, lnChannelCount = 0, updatedAtMillis = 0, sourcesOk = 6,
        )
        val s = vm.state.value
        assertEquals("950.000", s.btc.blockText)
        assertTrue(s.btc.live)
        assertEquals("56.200 €", s.btc.priceText)
        assertEquals("1 € = 1.779 sats", s.converterText)
        vm.onStart()
        assertEquals(1, fx.data.dashboardRefreshes)
        vm.onStop()
    }

    @Test
    fun `plebrap and duell tiles`() {
        fx.data.duell = space.einundzwanzig.meetup.data.proofs.satoshiduell.DuellStatus(myTurn = 2, waiting = 1, lobby = 3)
        val vm = fx.create()
        assertEquals(5, vm.state.value.duell.count)
        fx.data.plebrapIndex.value = 0
        fx.data.plebrapPlaying.value = true
        assertTrue(vm.state.value.plebrap.title!!.isNotEmpty())
        assertTrue(vm.state.value.plebrap.playing)
        vm.plebrapToggle()
        vm.plebrapNext()
        assertEquals(1, fx.data.toggles)
        assertEquals(1, fx.data.nexts)
    }

    @Test
    fun `avatar pick stores the local picture or reports the error`() {
        val vm = fx.create()
        val events = vm.events()
        vm.onAvatarPicked("content://x")
        assertEquals("/files/profile_picture/p.jpg", vm.state.value.avatarLocal)
        fx.data.importError = IllegalStateException("kaputt")
        vm.onAvatarPicked("content://y")
        assertEquals(HomeMessage.ImageLoadError("kaputt"), messages(events).last())
    }

    @Test
    fun `profile picture comes from nostr when there is no local one`() {
        fx.data.pictureUrl = "https://pic"
        val vm = fx.create()
        assertEquals("https://pic", vm.state.value.avatarUrl)
        assertNull(vm.state.value.avatarLocal)
    }

    @Test
    fun `integrity warning can be dismissed`() {
        fx.data.compromised = true
        val vm = fx.create()
        assertTrue(vm.state.value.showIntegrityWarning)
        vm.dismissIntegrityWarning()
        assertFalse(vm.state.value.showIntegrityWarning)
    }
}
