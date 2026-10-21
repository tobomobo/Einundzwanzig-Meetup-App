package space.einundzwanzig.meetup.data.reputation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.dart.DartDateTime
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.nostr.NostrEvents
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.data.identity.SigningMode
import space.einundzwanzig.meetup.data.identity.profile.UserProfile
import space.einundzwanzig.meetup.data.proofs.humanity.HumanityProofRepository
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProof
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProofRepository
import space.einundzwanzig.meetup.data.proofs.zap.ZapStats
import space.einundzwanzig.meetup.data.proofs.zap.ZapVerificationService
import space.einundzwanzig.meetup.data.reputation.internal.DefaultPublishThrottleStore
import space.einundzwanzig.meetup.data.reputation.internal.DefaultReputationPublisher
import space.einundzwanzig.meetup.data.reputation.publish.PublishResult
import space.einundzwanzig.meetup.data.socialgraph.social.SocialAnalysis
import space.einundzwanzig.meetup.data.socialgraph.social.SocialGraphService
import space.einundzwanzig.meetup.data.socialgraph.social.SocialStats
import space.einundzwanzig.meetup.data.trust.ZoneIdProvider
import space.einundzwanzig.meetup.data.trust.reputation.RelayReputationMath
import java.time.ZoneOffset

/** `ReputationPublisher.publish` order of operations (spec 04 §5.2) and the native FIXes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReputationPublisherTest {

    private val relayA = RelayConfig.DEFAULT_RELAYS[0]
    private val relayB = RelayConfig.DEFAULT_RELAYS[1]

    private class FakeSocial(var stats: SocialStats = SocialStats(120, 42, 2), var delayMs: Long = 0) : SocialGraphService {
        override suspend fun getMyFollows(forceRefresh: Boolean): Set<String> = emptySet()
        override suspend fun fetchContactList(pubkeyHex: String): Set<String> = emptySet()
        override suspend fun analyze(targetPubkeyHex: String): SocialAnalysis = SocialAnalysis.empty()
        override suspend fun getMyStats(): SocialStats {
            delay(delayMs)
            return stats
        }
    }

    private class FakeZaps(var stats: ZapStats = ZapStats(12, 5, 4, 3, 6, true), var error: Exception? = null) : ZapVerificationService {
        override suspend fun analyzeZapActivity(pubkeyHex: String, useCache: Boolean): ZapStats = stats
        override suspend fun getMyStats(): ZapStats {
            error?.let { throw it }
            return stats
        }
    }

    private inner class Harness(scope: TestScope) {
        val time = FakeTime()
        val signer = FakeSigner(time = time)
        val relays = FakeRelays().apply { reachable += relayA; reachable += relayB }
        val prefs = InMemoryKeyValueStore()
        val social = FakeSocial()
        val zaps = FakeZaps()
        var humanityProof: JsonObject? = jsonObjectOf("verified" to true, "method" to "lightning_zap", "first_zap_at" to 1_739_905_680L)
        var proofsError: Exception? = null
        var savedProofs = linkedMapOf("telegram" to PlatformProof("telegram", "satoshi", "f".repeat(128), 1_760_000_000L))
        val dispatcher = UnconfinedTestDispatcher(scope.testScheduler)

        val publisher = DefaultReputationPublisher(
            signing = signer.service,
            profiles = FakeProfiles(UserProfile(nickname = "Satoshi")),
            socialGraph = social,
            zaps = zaps,
            humanity = proxy<HumanityProofRepository> { name, _ ->
                if (name == "getProofForPublishing") humanityProof else throw UnsupportedOperationException(name)
            },
            platformProofs = proxy<PlatformProofRepository> { name, _ ->
                if (name == "getProofsForPublishing") {
                    proofsError?.let { throw it }
                    LinkedHashMap(savedProofs)
                } else {
                    throw UnsupportedOperationException(name)
                }
            },
            relayClient = RelayClient(relays.connector, dispatcher),
            relayConfig = RelayConfig(prefs, relays.connector),
            throttle = DefaultPublishThrottleStore(prefs),
            time = time,
            zone = ZoneIdProvider { ZoneOffset.UTC },
            log = testLog(),
            scope = scope.backgroundScope,
            cpu = dispatcher,
        )

        fun publishedEvents() = relays.published.map { (relay, frame) ->
            relay to NostrEvents.fromJsonOrNull((DartJson.decode(frame) as JsonArray)[1])!!
        }
    }

    private val date = DartDateTime.utc(2025, 9, 1)
    private val badges = listOf(
        signedBadge("b1", "Wien", date, bound = true),
        signedBadge("b2", "Graz", date, adminPubkey = "c".repeat(64)),
        markerBadge("m1", "Linz", date),
    )

    @Test
    fun noKey() = runTest {
        val h = Harness(this)
        h.signer.keyPair = null
        assertEquals(PublishResult.NoKey, h.publisher.publish(badges))
        assertTrue(h.relays.published.isEmpty())
    }

    @Test
    fun noCountableBadges() = runTest {
        val h = Harness(this)
        assertEquals(PublishResult.NoBadges, h.publisher.publish(emptyList()))
        assertEquals(PublishResult.NoBadges, h.publisher.publish(listOf(markerBadge("m1", "Linz", date)), force = true))
        assertTrue(h.signer.signedEvents.isEmpty())
    }

    @Test
    fun publishesToEveryRelaySequentiallyWithProofsLoaded() = runTest {
        val h = Harness(this)
        val result = h.publisher.publish(badges)
        val signed = h.signer.signedEvents.single()
        assertEquals(PublishResult.Sent(2, signed.id), result)
        assertTrue(result.success)
        assertEquals("Reputation auf 2 Relays aktualisiert", result.germanMessage)

        val events = h.publishedEvents()
        assertEquals(listOf(relayA, relayB), events.map { it.first })
        val event = events.first().second
        assertTrue(event.isValid())
        assertEquals(30078, event.kind)
        assertEquals(
            listOf(listOf("d", "einundzwanzig-reputation"), listOf("v", "2"), listOf("client", "einundzwanzig-meetup-app")),
            event.tags,
        )
        // Frame: ["EVENT", {id, pubkey, created_at, kind, tags, content, sig}].
        assertEquals(signed.toEventMessage(), h.relays.published.first().second)

        val content = DartJson.decode(event.content) as JsonObject
        assertEquals(
            listOf("version", "identity", "stats", "proof", "updated_at", "social", "lightning", "humanity_proof", "platform_proofs"),
            content.keys.toList(),
        )
        // Basis: the two signed non-organizer badges (marker excluded, spec 04 §8.8 FIX).
        assertEquals(2, content.getValue("stats").jsonObject.getValue("total_badges").jsonPrimitive.content.toInt())
        assertEquals("satoshi", content.getValue("platform_proofs").jsonObject.getValue("telegram").jsonObject.getValue("username").jsonPrimitive.content)

        // Throttle armed with the Flutter keys.
        assertEquals(PrefValue.IntValue(h.time.millis), h.prefs.get("reputation_last_publish"))
        assertEquals(PrefValue.StringValue(RelayReputationMath.changeHash(2, 1, 2, 2)), h.prefs.get("reputation_last_hash"))
    }

    @Test
    fun throttleAndForce() = runTest {
        val h = Harness(this)
        assertTrue(h.publisher.publish(badges) is PublishResult.Sent)
        h.time.millis += 10 * 60 * 1000L
        assertEquals(PublishResult.UpToDate, h.publisher.publish(badges)) // same counts
        assertEquals(1, h.signer.signedEvents.size)

        val more = badges + signedBadge("b3", "Bern", date)
        h.time.millis -= 9 * 60 * 1000L // only 1 minute after the first publish
        assertEquals(PublishResult.UpToDate, h.publisher.publish(more))
        h.time.millis += 5 * 60 * 1000L
        assertTrue(h.publisher.publish(more) is PublishResult.Sent)

        assertTrue(h.publisher.publish(more, force = true) is PublishResult.Sent)
        assertEquals(3, h.signer.signedEvents.size)
    }

    @Test
    fun identitySwitchIsNotThrottled() = runTest {
        val h = Harness(this)
        assertTrue(h.publisher.publish(badges) is PublishResult.Sent)
        h.signer.keyPair = KEY2
        assertTrue(h.publisher.publish(badges) is PublishResult.Sent)
    }

    @Test
    fun noRelayDoesNotArmTheThrottle() = runTest {
        val h = Harness(this)
        h.relays.reachable.clear()
        val result = h.publisher.publish(badges)
        assertEquals(PublishResult.NoRelay(h.signer.signedEvents.single().id), result)
        assertFalse(result.success)
        assertEquals("Konnte an keinen Relay senden", result.germanMessage)
        assertNull(h.prefs.get("reputation_last_publish"))
        assertNull(h.prefs.get("reputation_last_hash"))
    }

    @Test
    fun slowSocialAndFailingZapsArePublishedWithout() = runTest {
        val h = Harness(this)
        h.social.delayMs = 60_000
        h.zaps.error = IllegalStateException("relay down")
        h.humanityProof = null
        assertTrue(h.publisher.publish(badges) is PublishResult.Sent)
        val content = DartJson.decode(h.publishedEvents().first().second.content) as JsonObject
        // Timeout → SocialStats.empty() (followCount 0 → omitted); error → null; no humanity.
        assertEquals(listOf("version", "identity", "stats", "proof", "updated_at", "platform_proofs"), content.keys.toList())
    }

    @Test
    fun explicitProofsOverrideTheRepository() = runTest {
        val h = Harness(this)
        h.publisher.publish(badges, platformProofs = emptyMap(), force = true)
        val content = DartJson.decode(h.publishedEvents().first().second.content) as JsonObject
        assertFalse(content.containsKey("platform_proofs"))
    }

    @Test
    fun signerFailureIsAnError() = runTest {
        val h = Harness(this)
        h.signer.failSigning = IllegalStateException("abgelehnt")
        val result = h.publisher.publish(badges, force = true)
        assertTrue(result is PublishResult.Error)
        assertEquals("Publish-Fehler: abgelehnt", result.germanMessage)
        assertTrue(h.relays.published.isEmpty())
    }

    @Test
    fun publishInBackgroundRunsInTheApplicationScope() = runTest {
        val h = Harness(this)
        h.publisher.publishInBackground(badges)
        settle()
        assertEquals(2, h.relays.published.size)
    }

    @Test
    fun proofLoadFailureSendsNothing() = runTest {
        // Spec 04 R-5: a publish without platform_proofs would delete them from the relays.
        val h = Harness(this)
        h.proofsError = IllegalStateException("secure store locked")
        val result = h.publisher.publish(badges, force = true)
        assertTrue(result is PublishResult.Error)
        assertTrue(h.signer.signedEvents.isEmpty())
        assertTrue(h.relays.published.isEmpty())
        assertNull(h.prefs.get("reputation_last_publish"))
    }

    @Test
    fun throttleAppliesWhenPubkeyHexIsEmpty() = runTest {
        val h = Harness(this)
        h.signer.pubkeyHexOverride = ""
        assertTrue(h.publisher.publish(badges) is PublishResult.Sent)
        assertEquals(PrefValue.StringValue(KEY1.publicKeyHex), h.prefs.get("reputation_last_pubkey"))
        val more = badges + signedBadge("b3", "Bern", date)
        h.time.millis += 60 * 1000L
        assertEquals(PublishResult.UpToDate, h.publisher.publish(more)) // within 5 minutes
        assertEquals(1, h.signer.signedEvents.size)
        h.time.millis += 5 * 60 * 1000L
        assertTrue(h.publisher.publish(more) is PublishResult.Sent)
        assertEquals(PublishResult.UpToDate, h.publisher.publish(more)) // same hash
    }

    @Test
    fun publishInBackgroundIsSkippedWithAmber() = runTest {
        val h = Harness(this)
        h.signer.mode = SigningMode.AMBER
        h.publisher.publishInBackground(badges)
        settle()
        assertTrue(h.signer.signedEvents.isEmpty())
        assertTrue(h.relays.published.isEmpty())
        // Explicit publishes still go out.
        assertTrue(h.publisher.publish(badges, force = true) is PublishResult.Sent)
    }

    @Test
    fun publishInBackgroundRunsWithNip46() = runTest {
        val h = Harness(this)
        h.signer.mode = SigningMode.NIP46
        h.publisher.publishInBackground(badges)
        settle()
        assertEquals(1, h.signer.signedEvents.size)
    }
}
