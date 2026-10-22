package space.einundzwanzig.meetup.feature.meetups.portal

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import space.einundzwanzig.meetup.core.ui.component.Tone
import space.einundzwanzig.meetup.data.meetups.portal.PortalErrorKind
import space.einundzwanzig.meetup.data.meetups.portal.PortalMeetup
import space.einundzwanzig.meetup.data.meetups.portal.PortalResult
import space.einundzwanzig.meetup.feature.meetups.FakePortal
import space.einundzwanzig.meetup.feature.meetups.MainDispatcherRule
import space.einundzwanzig.meetup.feature.meetups.common.UiText
import space.einundzwanzig.meetup.core.l10n.R as L10nR

class PortalMeetupsViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val portal = FakePortal()
    private val npub = "npub1" + "q".repeat(58)

    private fun leader(id: Any?, name: String, nostr: String, creator: Boolean) = buildJsonObject {
        when (id) {
            is Int -> put("id", JsonPrimitive(id))
            is String -> put("id", JsonPrimitive(id))
        }
        put("name", JsonPrimitive(name))
        put("nostr", JsonPrimitive(nostr))
        put("is_creator", JsonPrimitive(creator))
    }

    @Test
    fun `without token the login view is shown and nothing loads`() {
        val vm = PortalMeetupsViewModel(portal)
        val s = vm.state.value
        assertFalse(s.checking)
        assertFalse(s.connected)
        assertEquals(0, portal.getMyMeetupsCalls)
    }

    @Test
    fun `with token the meetups load and leader cards load their admins`() {
        portal.token = "t"
        portal.myMeetups = listOf(PortalMeetup(1, "A", "a", isLeader = true), PortalMeetup(2, "B", "b"))
        portal.leaders[1] = listOf(leader(7, "Hal", npub, false), leader(3, "Sat", npub, true))
        val vm = PortalMeetupsViewModel(portal)
        val s = vm.state.value
        assertTrue(s.connected)
        assertFalse(s.loadingMeetups)
        assertEquals(listOf(1, 2), s.cards.map { it.meetup.id })
        val card = s.cards[0]
        assertFalse(card.leadersLoading)
        assertEquals(listOf(LeaderRow(7, "Hal", npub, false), LeaderRow(3, "Sat", npub, true)), card.leaders)
        assertTrue(s.cards[1].leaders.isEmpty())
    }

    @Test
    fun `duplicate meetup ids from the portal are shown once`() {
        portal.token = "t"
        portal.myMeetups = listOf(PortalMeetup(1, "A", "a"), PortalMeetup(2, "B", "b"), PortalMeetup(1, "A", "a"))
        val vm = PortalMeetupsViewModel(portal)
        assertEquals(listOf(1, 2), vm.state.value.cards.map { it.meetup.id })
    }

    @Test
    fun `a token of another identity is deleted and the login view returns`() {
        portal.token = "t"
        portal.tokenMatches = false
        portal.myMeetups = listOf(PortalMeetup(1, "A", "a", isLeader = true))
        val vm = PortalMeetupsViewModel(portal)
        assertFalse(vm.state.value.connected)
        assertFalse(vm.state.value.loadingMeetups)
        assertEquals(1, portal.deleteCalls)
        assertTrue(vm.state.value.cards.isEmpty())
        // Nothing of the foreign identity is loaded or requested with its token.
        assertEquals(0, portal.getMyMeetupsCalls)
        assertEquals(0, portal.getLeadersCalls)
    }

    @Test
    fun `login success connects and loads`() {
        portal.myMeetups = listOf(PortalMeetup(5, "X", "x"))
        val vm = PortalMeetupsViewModel(portal)
        vm.onAction(PortalMeetupsAction.Login)
        val s = vm.state.value
        assertTrue(s.connected)
        assertFalse(s.loggingIn)
        assertEquals(1, s.cards.size)
    }

    @Test
    fun `login failure shows the prefixed portal error in red`() = runTest {
        portal.loginResult = PortalResult(false, 0, "Netzwerkfehler", PortalErrorKind.LOGIN_FAILED)
        val vm = PortalMeetupsViewModel(portal)
        vm.onAction(PortalMeetupsAction.Login)
        assertFalse(vm.state.value.connected)
        val m = vm.messages.first()
        assertEquals(Tone.Error, m.tone)
        assertEquals(
            UiText.Prefixed(UiText.Res(L10nR.string.portalLoginFailed), UiText.Portal("Netzwerkfehler", PortalErrorKind.LOGIN_FAILED)),
            m.text,
        )
    }

    @Test
    fun `logout disconnects and clears the list`() {
        portal.token = "t"
        portal.myMeetups = listOf(PortalMeetup(5, "X", "x"))
        val vm = PortalMeetupsViewModel(portal)
        vm.onAction(PortalMeetupsAction.Logout)
        assertFalse(vm.state.value.connected)
        assertTrue(vm.state.value.cards.isEmpty())
        assertEquals(1, portal.logoutCalls)
    }

    @Test
    fun `refresh reloads the meetups`() {
        portal.token = "t"
        val vm = PortalMeetupsViewModel(portal)
        portal.myMeetups = listOf(PortalMeetup(9, "N", "n"))
        vm.onAction(PortalMeetupsAction.Refresh)
        assertFalse(vm.state.value.refreshing)
        assertEquals(listOf(9), vm.state.value.cards.map { it.meetup.id })
        assertEquals(2, portal.getMyMeetupsCalls)
    }

    @Test
    fun `invalid npub is rejected without a request`() = runTest {
        portal.token = "t"
        portal.myMeetups = listOf(PortalMeetup(1, "A", "a", isLeader = true))
        val vm = PortalMeetupsViewModel(portal)
        vm.onAction(PortalMeetupsAction.AskAddLeader(1))
        assertEquals(PortalDialog.AddLeader(1), vm.state.value.dialog)
        vm.onAction(PortalMeetupsAction.ConfirmAddLeader("npub1short"))
        assertNull(vm.state.value.dialog)
        assertTrue(portal.added.isEmpty())
        assertEquals(UiText.Res(L10nR.string.ldNpubInvalid), vm.messages.first().text)
    }

    @Test
    fun `empty input closes the dialog silently`() {
        portal.token = "t"
        val vm = PortalMeetupsViewModel(portal)
        vm.onAction(PortalMeetupsAction.AskAddLeader(1))
        vm.onAction(PortalMeetupsAction.ConfirmAddLeader("   "))
        assertNull(vm.state.value.dialog)
        assertTrue(portal.added.isEmpty())
    }

    @Test
    fun `valid npub is added trimmed and the admins reload`() = runTest {
        portal.token = "t"
        portal.myMeetups = listOf(PortalMeetup(1, "A", "a", isLeader = true))
        val vm = PortalMeetupsViewModel(portal)
        portal.leaders[1] = listOf(leader(7, "Hal", npub, false))
        vm.onAction(PortalMeetupsAction.AskAddLeader(1))
        vm.onAction(PortalMeetupsAction.ConfirmAddLeader("  $npub "))
        assertEquals(listOf(1 to npub), portal.added)
        val m = vm.messages.first()
        assertEquals(UiText.Res(L10nR.string.ldAdded), m.text)
        assertEquals(Tone.Success, m.tone)
        assertEquals(1, vm.state.value.cards[0].leaders.size)
        assertFalse(vm.state.value.cards[0].busy)
    }

    @Test
    fun `add failure shows ldFailed with the portal error`() = runTest {
        portal.token = "t"
        portal.myMeetups = listOf(PortalMeetup(1, "A", "a", isLeader = true))
        portal.writeResult = PortalResult(false, 403, "Keine Berechtigung", PortalErrorKind.FORBIDDEN)
        val vm = PortalMeetupsViewModel(portal)
        vm.onAction(PortalMeetupsAction.AskAddLeader(1))
        vm.onAction(PortalMeetupsAction.ConfirmAddLeader(npub))
        val m = vm.messages.first()
        assertEquals(Tone.Error, m.tone)
        assertEquals(UiText.Prefixed(UiText.Res(L10nR.string.ldFailed), UiText.Portal("Keine Berechtigung", PortalErrorKind.FORBIDDEN)), m.text)
    }

    @Test
    fun `remove needs an int id and a confirmation`() = runTest {
        portal.token = "t"
        portal.myMeetups = listOf(PortalMeetup(1, "A", "a", isLeader = true))
        portal.leaders[1] = listOf(leader("x", "NoId", npub, false), leader(7, "Hal", npub, false))
        val vm = PortalMeetupsViewModel(portal)
        val (noId, hal) = vm.state.value.cards[0].leaders
        assertNull(noId.userId)
        vm.onAction(PortalMeetupsAction.AskRemoveLeader(1, noId))
        assertNull(vm.state.value.dialog)
        vm.onAction(PortalMeetupsAction.AskRemoveLeader(1, hal))
        assertEquals(PortalDialog.RemoveLeader(1, 7), vm.state.value.dialog)
        vm.onAction(PortalMeetupsAction.ConfirmRemoveLeader)
        assertEquals(listOf(1 to 7), portal.removed)
        assertEquals(UiText.Res(L10nR.string.ldRemoved), vm.messages.first().text)
    }

    @Test
    fun `remove failure shows ldFailed with the portal error and frees the card`() = runTest {
        portal.token = "t"
        portal.myMeetups = listOf(PortalMeetup(1, "A", "a", isLeader = true))
        portal.leaders[1] = listOf(leader(7, "Hal", npub, false))
        val vm = PortalMeetupsViewModel(portal)
        portal.writeResult = PortalResult(false, 422, "Letzter Organisator", PortalErrorKind.SERVER_MESSAGE)
        vm.onAction(PortalMeetupsAction.AskRemoveLeader(1, vm.state.value.cards[0].leaders.single()))
        vm.onAction(PortalMeetupsAction.ConfirmRemoveLeader)
        val m = vm.messages.first()
        assertEquals(Tone.Error, m.tone)
        assertEquals(
            UiText.Prefixed(UiText.Res(L10nR.string.ldFailed), UiText.Portal("Letzter Organisator", PortalErrorKind.SERVER_MESSAGE)),
            m.text,
        )
        assertFalse(vm.state.value.cards[0].busy)
        assertEquals(1, vm.state.value.cards[0].leaders.size)
    }

    @Test
    fun `leader row parsing follows the Dart rules`() {
        val row = LeaderRow.fromJson(
            buildJsonObject {
                put("id", JsonPrimitive(1.0))
                put("is_creator", JsonPrimitive("true"))
            },
        )
        assertEquals(LeaderRow(null, "", "", false), row)
    }
}
