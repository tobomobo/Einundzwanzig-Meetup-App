package space.einundzwanzig.meetup.data.reputation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.nostr.relay.RelayClient
import space.einundzwanzig.meetup.core.nostr.relay.RelayConfig
import space.einundzwanzig.meetup.data.proofs.platform.PlatformProofVerifier
import space.einundzwanzig.meetup.data.proofs.platform.VerifyLevel
import space.einundzwanzig.meetup.data.proofs.platform.VerifyMessage
import space.einundzwanzig.meetup.data.reputation.internal.DefaultReputationFetcher
import space.einundzwanzig.meetup.data.reputation.verify.ReputationProofVerifier

/** `fetchByNpub` (spec 04 §5.3) and the verify-string wiring (§8.10). */
@OptIn(ExperimentalCoroutinesApi::class)
class ReputationFetcherTest {

    private val relayA = RelayConfig.DEFAULT_RELAYS[0]
    private val relayB = RelayConfig.DEFAULT_RELAYS[1]
    private val section = Fixtures["reputationEvent30078"]
    private val fixtureEvent = section.getValue("event").jsonObject
    private val tags = listOf(listOf("d", "einundzwanzig-reputation"), listOf("v", "2"), listOf("client", "einundzwanzig-meetup-app"))

    private inner class Harness(scope: TestScope) {
        val time = FakeTime(FIXTURE_SECONDS * 1000 + 60_000)
        val relays = FakeRelays()
        private val dispatcher = UnconfinedTestDispatcher(scope.testScheduler)
        val fetcher = DefaultReputationFetcher(
            relayClient = RelayClient(relays.connector, dispatcher),
            relayConfig = RelayConfig(InMemoryKeyValueStore(), relays.connector),
            time = time,
            log = testLog(),
            cpu = dispatcher,
        )
        val verifier = ReputationProofVerifier(PlatformProofVerifier(dispatcher), fetcher)
    }

    private fun repEvent(content: String, createdAt: Long = FIXTURE_SECONDS, key: space.einundzwanzig.meetup.core.crypto.KeyPair = KEY1, eventTags: List<List<String>> = tags) =
        signed(key, 30078, content, eventTags, createdAt).json()

    @Test
    fun fetchesAndParsesTheFixtureEvent() = runTest {
        val h = Harness(this)
        h.relays.add(relayA, fixtureEvent)
        val rep = h.fetcher.fetchByNpub(KEY1.npub)!!
        assertEquals(section.getValue("expected_parsed").jsonObject.getValue("eventId").jsonPrimitive.content, rep.eventId)
        assertEquals(7, rep.boundBadges)
    }

    @Test
    fun reqFrameMatchesFixture() = runTest {
        val h = Harness(this)
        h.relays.reachable += relayA
        assertNull(h.fetcher.fetchByPubkeyHex(KEY1.publicKeyHex))
        val req = h.relays.reqs.first { it.first == relayA }.second
        assertEquals("REQ", req[0].jsonPrimitive.content)
        assertTrue(Regex("rep-fetch-[0-9a-f]{16}").matches(req[1].jsonPrimitive.content))
        assertEquals(DartJson.encode(section.getValue("req_filter")), DartJson.encode(req[2]))
        assertEquals(3, req.size)
    }

    @Test
    fun invalidInputs() = runTest {
        val h = Harness(this)
        h.relays.add(relayA, fixtureEvent)
        assertNull(h.fetcher.fetchByNpub(""))
        assertNull(h.fetcher.fetchByNpub("npub1invalid"))
        assertNull(h.fetcher.fetchByNpub("nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"))
        assertNull(h.fetcher.fetchByPubkeyHex(""))
    }

    @Test
    fun badSignatureAndWrongAuthorAreIgnored() = runTest {
        val h = Harness(this)
        val tampered = JsonObject(fixtureEvent + ("content" to JsonPrimitive("{\"version\":2}")))
        h.relays.add(relayA, tampered, repEvent("{}", key = KEY2))
        assertNull(h.fetcher.fetchByNpub(KEY1.npub))
    }

    @Test
    fun wrongDTagAndNonObjectContentAreIgnored() = runTest {
        val h = Harness(this)
        h.relays.add(relayA, repEvent("{}", eventTags = listOf(listOf("d", "einundzwanzig-admins"))), repEvent("[1]", createdAt = FIXTURE_SECONDS + 5))
        assertNull(h.fetcher.fetchByNpub(KEY1.npub))
    }

    @Test
    fun newestValidEventAcrossRelaysWins() = runTest {
        val h = Harness(this)
        val older = repEvent("""{"identity":{"nickname":"old"}}""", createdAt = FIXTURE_SECONDS - 100)
        val newer = repEvent("""{"identity":{"nickname":"new"}}""", createdAt = FIXTURE_SECONDS)
        val future = repEvent("""{"identity":{"nickname":"future"}}""", createdAt = FIXTURE_SECONDS + 3600)
        h.relays.add(relayA, older)
        h.relays.add(relayB, newer, future)
        assertEquals("new", h.fetcher.fetchByNpub(KEY1.npub)!!.nickname)
    }

    @Test
    fun unreachableRelaysGiveNull() = runTest {
        val h = Harness(this)
        assertNull(h.fetcher.fetchByNpub(KEY1.npub))
    }

    // ---- verify string (spec 04 §8.10) ----

    private val sig = "a".repeat(128)
    private fun verifyString(user: String = "satoshi") = "21rep::${KEY1.npub}::telegram::$user::sig=$sig"

    private fun contentWith(bound: Int, total: Int, proofUser: String?): String {
        val pp = proofUser?.let { ""","platform_proofs":{"telegram":{"username":"$it","proof_sig":"$sig","created_at":1}}""" } ?: ""
        return """{"version":2,"stats":{"total_badges":$total,"bound_badges":$bound}$pp}"""
    }

    @Test
    fun verifyFull() = runTest {
        val h = Harness(this)
        h.relays.add(relayA, repEvent(contentWith(3, 3, "satoshi")))
        val r = h.verifier.verifyProofString(verifyString())
        assertEquals(VerifyLevel.FULL, r.level)
        assertEquals(VerifyMessage.Full, r.message)
        assertTrue(r.proofInEvent)
        assertEquals(3, r.reputation!!.boundBadges)
    }

    @Test
    fun verifyPartialWithProof() = runTest {
        val h = Harness(this)
        h.relays.add(relayA, repEvent(contentWith(2, 3, "satoshi")))
        val r = h.verifier.verifyProofString(verifyString())
        assertEquals(VerifyLevel.PARTIAL, r.level)
        assertEquals(VerifyMessage.PartialWithProof(2, 3), r.message)
    }

    @Test
    fun verifyPartialWithoutProofIsCaseSensitive() = runTest {
        val h = Harness(this)
        h.relays.add(relayA, repEvent(contentWith(3, 3, "Satoshi")))
        val r = h.verifier.verifyProofString(verifyString())
        assertEquals(VerifyLevel.PARTIAL, r.level)
        assertEquals(VerifyMessage.PartialNoProof, r.message)
    }

    @Test
    fun verifySignatureOnly() = runTest {
        val h = Harness(this)
        h.relays.add(relayA, repEvent(contentWith(0, 3, "satoshi")))
        assertEquals(VerifyMessage.SignatureOnlyNoBindings, h.verifier.verifyProofString(verifyString()).message)
    }

    @Test
    fun verifyWithoutReputation() = runTest {
        val h = Harness(this)
        val r = h.verifier.verifyProofString(verifyString())
        assertEquals(VerifyLevel.SIGNATURE_ONLY, r.level)
        assertEquals(VerifyMessage.NoReputation, r.message)
    }

    @Test
    fun verifyByNpubAndBadInput() = runTest {
        val h = Harness(this)
        h.relays.add(relayA, repEvent(contentWith(1, 3, "satoshi")))
        val byNpub = h.verifier.verifyProofString(KEY1.npub)
        assertEquals(VerifyLevel.PARTIAL, byNpub.level)
        assertEquals(VerifyMessage.NpubWithBindings, byNpub.message)
        assertEquals(VerifyMessage.BadFormat, h.verifier.verifyProofString("hello").message)
        assertEquals(VerifyMessage.BadSignature, h.verifier.verifyProofString("21rep::${KEY1.npub}::telegram::satoshi::sig=${"z".repeat(128)}").message)
    }
}
