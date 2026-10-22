package space.einundzwanzig.meetup.feature.community

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import space.einundzwanzig.meetup.data.proofs.satoshiduell.DuellStatus
import space.einundzwanzig.meetup.feature.community.di.NpubSource
import space.einundzwanzig.meetup.feature.community.hub.CommunityHubViewModel

class CommunityHubViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `loads the duell status on start`() {
        val client = FakeDuellClient(DuellStatus(myTurn = 2, waiting = 1, lobby = 3))
        val vm = CommunityHubViewModel(client) { "npub1x" }
        assertEquals(DuellStatus(2, 1, 3), vm.state.value.duell)
        assertEquals(5, vm.state.value.shownDuell.actionCount)
    }

    @Test
    fun `status is null while loading and shows EMPTY`() {
        val vm = CommunityHubViewModel(
            object : space.einundzwanzig.meetup.data.proofs.satoshiduell.SatoshiDuellClient by FakeDuellClient() {
                override suspend fun fetchStatus(forceRefresh: Boolean): DuellStatus = kotlinx.coroutines.awaitCancellation()
            },
            NpubSource { null },
        )
        assertEquals(null, vm.state.value.duell)
        assertEquals(DuellStatus.EMPTY, vm.state.value.shownDuell)
    }

    @Test
    fun `status error falls back to EMPTY`() {
        val vm = CommunityHubViewModel(FakeDuellClient(fail = true)) { null }
        assertEquals(DuellStatus.EMPTY, vm.state.value.duell)
    }

    @Test
    fun `refresh reloads the status`() {
        val client = FakeDuellClient()
        val vm = CommunityHubViewModel(client) { null }
        client.status = DuellStatus(1, 0, 0)
        vm.refresh()
        assertEquals(2, client.calls)
        assertEquals(DuellStatus(1, 0, 0), vm.state.value.duell)
    }

    @Test
    fun `satoshiduell link carries the npub`() = runTest {
        val vm = CommunityHubViewModel(FakeDuellClient()) { "npub1abc" }
        vm.openSatoshiDuell()
        assertEquals("https://satoshiduell.de/?npub=npub1abc", vm.urls.first())
    }

    @Test
    fun `satoshiduell link is plain without npub or when the signer fails`() = runTest {
        val empty = CommunityHubViewModel(FakeDuellClient()) { "" }
        empty.openSatoshiDuell()
        assertEquals("https://satoshiduell.de/", empty.urls.first())
        val failing = CommunityHubViewModel(FakeDuellClient()) { error("locked") }
        failing.openSatoshiDuell()
        assertEquals("https://satoshiduell.de/", failing.urls.first())
    }
}
