package space.einundzwanzig.meetup.feature.wallet

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import space.einundzwanzig.meetup.core.navigation.BadgeCollection
import space.einundzwanzig.meetup.core.navigation.BadgeDetails
import space.einundzwanzig.meetup.data.badges.model.BadgeIdentity
import space.einundzwanzig.meetup.feature.wallet.collection.CollectionViewModel
import space.einundzwanzig.meetup.feature.wallet.details.BadgeDetailsViewModel
import space.einundzwanzig.meetup.feature.wallet.map.WorldMapViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenViewModelsTest {

    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `collection shows the group newest first and follows the repository`() = runTest {
        val old = badge("1", name = "Ulm", date = "2025-01-01T19:00:00.000", meetupEventId = "u1")
        val new = badge("2", name = "Ulm", date = "2026-01-01T19:00:00.000", meetupEventId = "u2")
        val repo = FakeBadgeRepository(listOf(old, badge("3", name = "Berlin"), new))
        val vm = CollectionViewModel(BadgeCollection("Ulm"), repo)
        backgroundScope.launchCollect(vm.state)
        assertEquals(listOf("2", "1"), vm.state.value.badges.map { it.id })
        assertEquals(3, vm.state.value.all.size)
        repo.set(listOf(old))
        assertEquals(listOf("1"), vm.state.value.badges.map { it.id })
    }

    @Test
    fun `organizer collection uses the organizer key`() {
        val marker = badge("o", isOrganizer = true)
        val vm = CollectionViewModel(BadgeCollection(BadgeCollection.ORGANIZER_GROUP_KEY), FakeBadgeRepository(listOf(badge("a"), marker)))
        assertTrue(vm.state.value.isOrganizer)
        assertSame(marker, vm.state.value.badges.single())
    }

    @Test
    fun `details resolves the exact duplicate by index and computes the hash`() {
        val a = badge("a", meetupEventId = "m1")
        val dup = badge("a2", meetupEventId = "m1")
        val repo = FakeBadgeRepository(listOf(a, dup))
        val vm = BadgeDetailsViewModel(BadgeDetails(BadgeIdentity.unionKey(dup), 1), repo)
        assertSame(dup, vm.state.value.badge)
        assertEquals(dup.verificationHash(), vm.state.value.hash)
        assertEquals(16, vm.state.value.hash.length)
    }

    @Test
    fun `details of a removed badge is null`() {
        val vm = BadgeDetailsViewModel(BadgeDetails("evt:gone", 0), FakeBadgeRepository(listOf(badge("a"))))
        assertNull(vm.state.value.badge)
        assertTrue(vm.state.value.loaded)
    }

    @Test
    fun `details share is single flight`() = runTest {
        val a = badge("a")
        val vm = BadgeDetailsViewModel(BadgeDetails(BadgeIdentity.unionKey(a), 0), FakeBadgeRepository(listOf(a)))
        backgroundScope.launchCollect(vm.state)
        assertTrue(vm.beginShare())
        assertFalse(vm.beginShare())
        assertTrue(vm.state.value.sharing)
        vm.endShare()
        assertFalse(vm.state.value.sharing)
    }

    @Test
    fun `world map keeps only located badges and counts cities`() = runTest {
        val ulm = badge("1", name = "Ulm", lat = 48.4, lng = 10.0)
        val repo = FakeBadgeRepository(listOf(ulm, badge("2", name = "Ulm", lat = 48.4, lng = 10.0, meetupEventId = "x"), badge("3", name = "Nowhere")))
        val vm = WorldMapViewModel(repo)
        backgroundScope.launchCollect(vm.state)
        assertEquals(2, vm.state.value.located.size)
        assertEquals(1, vm.state.value.cityCount)
        vm.select(ulm)
        assertSame(ulm, vm.state.value.selected)
        assertTrue(vm.beginShare())
        assertNull(vm.state.value.selected)
        assertTrue(vm.state.value.sharing)
        assertFalse(vm.beginShare())
        vm.endShare()
        assertFalse(vm.state.value.sharing)
    }

    @Test
    fun `world map drops a selection that left the wallet`() = runTest {
        val ulm = badge("1", name = "Ulm", lat = 48.4, lng = 10.0)
        val repo = FakeBadgeRepository(listOf(ulm))
        val vm = WorldMapViewModel(repo)
        backgroundScope.launchCollect(vm.state)
        vm.select(ulm)
        repo.set(emptyList())
        assertNull(vm.state.value.selected)
        assertTrue(vm.state.value.located.isEmpty())
    }
}

/** Keeps a `WhileSubscribed` state flow hot for the test. */
@OptIn(ExperimentalCoroutinesApi::class)
fun CoroutineScope.launchCollect(flow: Flow<*>) = launch(UnconfinedTestDispatcher()) { flow.collect {} }
