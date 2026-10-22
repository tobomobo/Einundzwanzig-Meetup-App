package space.einundzwanzig.meetup.data.reputation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.data.testing.InMemorySecureStore
import space.einundzwanzig.meetup.core.nostr.NostrEvent
import space.einundzwanzig.meetup.data.badges.model.MeetupBadge
import space.einundzwanzig.meetup.data.badges.security.BadgeSigner
import space.einundzwanzig.meetup.data.badges.store.BadgeRepository
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProof
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProofRepository
import space.einundzwanzig.meetup.data.reputation.internal.DefaultReputationQrGenerator
import space.einundzwanzig.meetup.data.reputation.internal.ReputationPublishStartupTask
import space.einundzwanzig.meetup.data.reputation.publish.PublishResult
import space.einundzwanzig.meetup.data.reputation.publish.ReputationPublisher
import space.einundzwanzig.meetup.data.reputation.qr.QrParseResult
import space.einundzwanzig.meetup.data.reputation.qr.ReputationQr
import space.einundzwanzig.meetup.data.reputation.qr.ReputationQrCodec
import space.einundzwanzig.meetup.data.trust.ZoneIdProvider
import space.einundzwanzig.meetup.data.trust.score.TrustScoreCalculator
import java.time.ZoneOffset
import java.util.TimeZone

/** `ReputationQrGenerator` (spec 04 §2.1, §8.6, §8.7, §8.11) and the background publish task. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReputationQrGeneratorTest {

    private val time = FakeTime()
    private val date = DartDateTime.fromMillisecondsSinceEpoch(time.millis - 30L * 86_400_000L, isUtc = true)

    private class Signer(var fail: Exception? = null, var canSign: Boolean = true, private val time: FakeTime) : BadgeSigner {
        override suspend fun canSign(): Boolean = canSign
        override suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String, createdAt: Long?): NostrEvent {
            fail?.let { throw it }
            return EventIds.sign(KEY1, time.nowSeconds(), kind, tags, content)
        }
    }

    private fun generator(
        signer: BadgeSigner,
        user: UserProfile = UserProfile(nickname = "Satoshi", nostrNpub = KEY1.npub),
        badgeRepository: BadgeRepository = BadgeRepository.create(InMemoryKeyValueStore(), InMemorySecureStore(), time),
    ) =
        DefaultReputationQrGenerator(
            badgeRepository = badgeRepository,
            profiles = FakeProfiles(user),
            platformProofs = proxy<PlatformProofRepository> { name, _ ->
                if (name == "getSavedProofs") listOf(PlatformProof("telegram", "satoshi", "f".repeat(128), 1L)) else throw UnsupportedOperationException(name)
            },
            calculator = TrustScoreCalculator.create(time, ZoneIdProvider { ZoneOffset.UTC }),
            signer = signer,
            time = time,
            log = testLog(),
            cpu = kotlinx.coroutines.Dispatchers.Unconfined,
        )

    private fun wallet(): List<MeetupBadge> = listOf(
        signedBadge("b1", "Wien", date, signerNpub = "npub1a", bound = true),
        signedBadge("b2", "Graz", date, signerNpub = "npub1b"),
        markerBadge("m1", "Linz", date),
        markerBadge("m1", "Linz", date), // same marker twice → 1 organized meetup
    )

    @Test
    fun emptyWallet() = runTest {
        val r = generator(Signer(time = time)).generate(emptyList())
        assertEquals(ReputationQr.Empty(UserProfile(nickname = "Satoshi", nostrNpub = KEY1.npub), 1), r)
        assertTrue(r.hasIdentity)
    }

    @Test
    fun signedQrRoundTrips() = withUtc {
        runTest {
            val r = generator(Signer(time = time)).generate(wallet()) as ReputationQr.Content
            assertTrue(r.isSigned)
            assertNull(r.signError)
            assertTrue(r.qrData.startsWith("21v3:"))
            assertEquals(1, r.platformProofCount)
            assertEquals(2, r.verifiedBadgeCount)
            assertEquals(1, r.boundBadgeCount)
            assertEquals(2, r.countedBadgeCount)
            assertEquals(1, r.organizerMeetupCount)
            assertEquals(ReputationQr.ProofStatus.PARTLY_BOUND, r.proofStatus)

            val parsed = ReputationQrCodec.parse(r.qrData) as QrParseResult.Verified
            assertEquals(KEY1.npub, parsed.signerNpub)
            assertFalse(parsed.identityMismatch)
            assertEquals(2, parsed.data.badgeCount)
            assertEquals(2, parsed.data.meetupCount)
            assertEquals(2, parsed.data.signerCount)
            assertEquals(1, parsed.data.boundBadgeCount)
            assertEquals(r.score.level, parsed.data.trustLevel)
            assertEquals(ReputationQrCodec.roundedScore(r.score.totalScore), parsed.data.trustScore, 0.0)
            assertEquals(time.millis, parsed.data.timestampMillis)
            assertEquals(mapOf("telegram" to "satoshi"), parsed.data.platformUsernames)
        }
    }

    @Test
    fun signerFailureFallsBackToUnsigned() = runTest {
        val error = IllegalStateException("abgelehnt")
        val r = generator(Signer(fail = error, time = time)).generate(wallet()) as ReputationQr.Content
        assertFalse(r.isSigned)
        assertEquals(error, r.signError)
        assertTrue(r.qrData.startsWith("21:") && r.qrData.endsWith("."))
        assertTrue(ReputationQrCodec.parse(r.qrData) is QrParseResult.SignatureInvalid)
    }

    @Test
    fun noSignerGivesUnsignedWithoutError() = runTest {
        val r = generator(Signer(canSign = false, time = time), UserProfile()).generate(wallet()) as ReputationQr.Content
        assertFalse(r.isSigned)
        assertNull(r.signError)
        assertFalse(r.hasIdentity)
    }

    @Test
    fun proofStatusTable() {
        fun status(counted: Int, verified: Int, bound: Int) = (
            ReputationQr.Content(
                UserProfile(), 0, "", false, null,
                TrustScoreCalculator.create(time).calculate(emptyList()),
                verifiedBadgeCount = verified, boundBadgeCount = bound, countedBadgeCount = counted, organizerMeetupCount = 0,
            )
            ).proofStatus
        assertEquals(ReputationQr.ProofStatus.ALL_BOUND, status(3, 3, 3))
        assertEquals(ReputationQr.ProofStatus.PARTLY_BOUND, status(3, 3, 1))
        assertEquals(ReputationQr.ProofStatus.ALL_VERIFIED, status(3, 3, 0))
        assertEquals(ReputationQr.ProofStatus.SOME_VERIFIED, status(3, 1, 0))
        assertEquals(ReputationQr.ProofStatus.NONE, status(0, 0, 0))
        assertEquals(ReputationQr.ProofStatus.PARTLY_BOUND, status(0, 2, 1))
    }

    // ---- background publish task ----

    private class RecordingPublisher : ReputationPublisher {
        val calls = mutableListOf<List<MeetupBadge>>()
        override suspend fun publish(badges: List<MeetupBadge>, platformProofs: Map<String, PlatformProof>?, force: Boolean) = PublishResult.UpToDate
        override fun publishInBackground(badges: List<MeetupBadge>) {
            calls += badges
        }
    }

    @Test
    fun startupTaskPublishesAfterLoadAndEverySave() = runTest {
        val repo = BadgeRepository.create(InMemoryKeyValueStore(), InMemorySecureStore(), time, UnconfinedTestDispatcher(testScheduler))
        val publisher = RecordingPublisher()
        val task = ReputationPublishStartupTask(repo, FakeSigner().service, publisher, backgroundScope)
        task.run()
        settle()
        assertTrue(publisher.calls.isEmpty()) // not loaded yet

        repo.loadBadges()
        settle()
        assertTrue(publisher.calls.isEmpty()) // loaded but empty

        val badge = signedBadge("b1", "Wien", date)
        assertTrue(repo.saveBadges(listOf(badge)))
        settle()
        assertEquals(1, publisher.calls.size)
        assertEquals("b1", publisher.calls.single().single().id)
    }

    @Test
    fun startupTaskPublishesExistingWalletOnce() = runTest {
        val prefs = InMemoryKeyValueStore()
        val secure = InMemorySecureStore()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        BadgeRepository.create(prefs, secure, time, dispatcher).saveBadges(listOf(signedBadge("b1", "Wien", date)))

        val repo = BadgeRepository.create(prefs, secure, time, dispatcher)
        val publisher = RecordingPublisher()
        backgroundScope.launch { ReputationPublishStartupTask(repo, FakeSigner().service, publisher, backgroundScope).watch() }
        repo.loadBadges()
        settle()
        assertEquals(1, publisher.calls.size)
    }

    @Test
    fun startupTaskDebouncesABurstAndPublishesTheFinalWallet() = runTest {
        val repo = BadgeRepository.create(InMemoryKeyValueStore(), InMemorySecureStore(), time, UnconfinedTestDispatcher(testScheduler))
        val publisher = RecordingPublisher()
        ReputationPublishStartupTask(repo, FakeSigner().service, publisher, backgroundScope).run()
        repo.loadBadges()
        settle()

        // A claim batch / merge: five saves in quick succession → one publish with all five.
        val ids = listOf("b1", "b2", "b3", "b4", "b5")
        for (i in ids.indices) {
            assertTrue(repo.saveBadges(ids.take(i + 1).map { signedBadge(it, "Wien", date) }))
            advanceTimeBy(500)
            runCurrent()
        }
        assertTrue(publisher.calls.isEmpty()) // still within the quiet period
        settle()
        assertEquals(1, publisher.calls.size)
        assertEquals(ids, publisher.calls.single().map { it.id })
    }

    @Test
    fun startupTaskRepublishesWhenTheSignerBecomesReady() = runTest {
        val prefs = InMemoryKeyValueStore()
        val secure = InMemorySecureStore()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        BadgeRepository.create(prefs, secure, time, dispatcher).saveBadges(listOf(signedBadge("b1", "Wien", date)))

        val repo = BadgeRepository.create(prefs, secure, time, dispatcher)
        val signer = FakeSigner()
        val publisher = RecordingPublisher()
        ReputationPublishStartupTask(repo, signer.service, publisher, backgroundScope).run()
        repo.loadBadges()
        settle()
        assertEquals(1, publisher.calls.size)

        // Amber / NIP-46 connected after the wallet loaded.
        signer.signerReady.emit(Unit)
        settle()
        assertEquals(2, publisher.calls.size)
    }

    @Test
    fun generateForCurrentBadgesLoadsTheWalletFirst() = withUtc {
        runTest {
            val prefs = InMemoryKeyValueStore()
            val secure = InMemorySecureStore()
            BadgeRepository.create(prefs, secure, time).saveBadges(wallet())
            val repo = BadgeRepository.create(prefs, secure, time)
            assertFalse(repo.loaded.value)
            val r = generator(Signer(time = time), badgeRepository = repo).generateForCurrentBadges()
            assertTrue(r is ReputationQr.Content)
            assertTrue(repo.loaded.value)
        }
    }

    private inline fun withUtc(block: () -> Unit) {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            block()
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
