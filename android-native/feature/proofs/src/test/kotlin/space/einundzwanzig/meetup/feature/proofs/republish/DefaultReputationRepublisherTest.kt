package space.einundzwanzig.meetup.feature.proofs.republish

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.data.badges.model.FormerCollection
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProof
import space.einundzwanzig.meetup.data.reputation.publish.PublishResult
import space.einundzwanzig.meetup.data.reputation.publish.ReputationPublisher

class DefaultReputationRepublisherTest {

    private class Call(val badges: List<MeetupBadge>, val platformProofs: Map<String, PlatformProof>?, val force: Boolean)

    private class FakePublisher : ReputationPublisher {
        val calls = mutableListOf<Call>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun publish(
            badges: List<MeetupBadge>,
            platformProofs: Map<String, PlatformProof>?,
            force: Boolean,
        ): PublishResult {
            gate?.await()
            calls += Call(badges, platformProofs, force)
            return PublishResult.UpToDate
        }

        override fun publishInBackground(badges: List<MeetupBadge>) = error("not used")
    }

    private class FakeBadges(loadedInitially: Boolean) : BadgeRepository {
        var loadCalls = 0
        override val badges = MutableStateFlow<List<MeetupBadge>>(emptyList())
        override val loaded = MutableStateFlow(loadedInitially)
        override val badgesUnreadable: StateFlow<Boolean> = MutableStateFlow(false)
        override val savedEvents: SharedFlow<Unit> = MutableSharedFlow()
        override suspend fun loadBadges(): List<MeetupBadge> {
            loadCalls++
            loaded.value = true
            return badges.value
        }
        override suspend fun saveBadges(badges: List<MeetupBadge>, replaceUnreadable: Boolean, discardLocked: Boolean) = error("not used")
        override suspend fun update(transform: (current: List<MeetupBadge>) -> List<MeetupBadge>?) = error("not used")
        override suspend fun keepAside(badge: MeetupBadge) = error("not used")
        override suspend fun stageIncoming(badges: List<MeetupBadge>) = error("not used")
        override suspend fun promoteIncoming(formerNpub: String) = error("not used")
        override suspend fun discardIncoming() = error("not used")
        override suspend fun formerCollections(): List<FormerCollection> = error("not used")
        override suspend fun mergeFormer(index: Int) = error("not used")
        override suspend fun resetAll() = error("not used")
    }

    @Test
    fun `loads the wallet first when not loaded, then publishes forced with fresh proofs`() = runTest {
        val publisher = FakePublisher()
        val badges = FakeBadges(loadedInitially = false)
        DefaultReputationRepublisher(publisher, badges, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))).republishForced()
        advanceUntilIdle()
        assertEquals(1, badges.loadCalls)
        val call = publisher.calls.single()
        assertTrue(call.force)
        assertNull(call.platformProofs) // null = getProofsForPublishing() fresh (spec 06 §14 R4)
    }

    @Test
    fun `does not reload an already loaded wallet`() = runTest {
        val publisher = FakePublisher()
        val badges = FakeBadges(loadedInitially = true)
        DefaultReputationRepublisher(publisher, badges, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))).republishForced()
        advanceUntilIdle()
        assertEquals(0, badges.loadCalls)
        assertEquals(1, publisher.calls.size)
    }

    @Test
    fun `runs in the application scope, independent of the caller`() = runTest {
        val publisher = FakePublisher().apply { gate = CompletableDeferred() }
        val badges = FakeBadges(loadedInitially = true)
        val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val caller = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val republisher = DefaultReputationRepublisher(publisher, badges, appScope)
        caller.launch {
            republisher.republishForced()
            awaitCancellation()
        }
        advanceUntilIdle()
        caller.cancel() // the screen's ViewModel is cleared while the publish is in flight
        publisher.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, publisher.calls.size)
        appScope.cancel()
    }
}
