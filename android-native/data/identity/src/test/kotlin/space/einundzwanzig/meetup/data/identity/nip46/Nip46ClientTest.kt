package space.einundzwanzig.meetup.data.identity.nip46

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.crypto.Nip19
import space.einundzwanzig.meetup.core.crypto.Nip44
import space.einundzwanzig.meetup.data.identity.FakeTime
import space.einundzwanzig.meetup.data.identity.Fixtures
import space.einundzwanzig.meetup.data.identity.queryAll
import space.einundzwanzig.meetup.data.identity.queryParam
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Port of `test/nip46_client_test.dart`: the counterpart really decrypts the requests with NIP-44
 * and encrypts its answers, so key derivation, kind-24133 framing and id matching all run.
 */
class Nip46ClientTest {

    private val relayA = "wss://relay-a.test"
    private val relayB = "wss://relay-b.test"
    private val shortLimit = 300.milliseconds

    private lateinit var signer: FakeSigner
    private lateinit var clientKey: String
    private val timers = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        signer = FakeSigner()
        clientKey = Nip46Client.generateClientKey()
    }

    @After
    fun tearDown() {
        timers.cancel()
    }

    private val clientPub: String get() = KeyPair.fromPrivateKeyHex(clientKey).publicKeyHex

    private fun build(
        relays: List<String> = listOf(relayA),
        paired: Boolean = true,
        onAuthUrl: ((String) -> Unit)? = null,
    ) = Nip46Client(
        clientSecretKeyHex = clientKey,
        relays = relays,
        remoteSignerPubkey = if (paired) signer.pubkey else null,
        onAuthUrl = onAuthUrl,
        timeouts = Nip46Timeouts.uniform(shortLimit),
        openRelay = signer.opener,
        timeSource = FakeTime(System.currentTimeMillis()),
    )

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.simpleName}, got $e", e)
        }
        fail("expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    private suspend fun eventually(check: () -> Boolean) {
        repeat(100) {
            if (check()) return
            delay(10)
        }
        assertTrue("condition not reached", check())
    }

    private fun answerLater(link: () -> FakeLink, payload: () -> JsonObject, after: kotlin.time.Duration) {
        timers.launch {
            delay(after)
            link().push(signer.wrap(payload(), clientPub))
        }
    }

    // ---------------- RPC-Weg ----------------

    @Test
    fun signEventRequestArrivesDecryptedAndAnswerComesBack() = runBlocking {
        val unsigned = jsonObjectOf(
            "kind" to 21000,
            "created_at" to 1700000000,
            "tags" to listOf(listOf("d", "badge")),
            "content" to "Badge-Signatur",
        )
        signer.respond = { request ->
            val inner = DartJson.decode(request["params"]!!.jsonArray[0].jsonPrimitive.content).jsonObject
            val result = JsonObject(
                inner + mapOf(
                    "id" to JsonPrimitive("a".repeat(64)),
                    "pubkey" to JsonPrimitive("b".repeat(64)),
                    "sig" to JsonPrimitive("c".repeat(128)),
                ),
            )
            jsonObjectOf("id" to request["id"], "result" to DartJson.encode(result))
        }
        val client = build()
        val signed = client.signEvent(unsigned)

        assertEquals(1, signer.requests.size)
        assertEquals("sign_event", signer.requests.single()["method"]!!.jsonPrimitive.content)
        val received = DartJson.decode(signer.requests.single()["params"]!!.jsonArray[0].jsonPrimitive.content).jsonObject
        assertEquals("21000", received["kind"]!!.jsonPrimitive.content)
        assertEquals("Badge-Signatur", received["content"]!!.jsonPrimitive.content)
        assertEquals("c".repeat(128), signed["sig"]!!.jsonPrimitive.content)
        assertEquals("21000", signed["kind"]!!.jsonPrimitive.content)
        client.close()
    }

    @Test
    fun getPublicKeyReturnsTheUserKey() = runBlocking {
        val userKey = KeyPair.generate().publicKeyHex
        signer.respond = { r -> jsonObjectOf("id" to r["id"], "result" to userKey) }
        val client = build()
        assertEquals(userKey, client.getPublicKey())
        client.close()
    }

    @Test
    fun connectRequestsPermissionsForEverySignedKind() = runBlocking {
        val client = build()
        client.connect("geheim123")
        val params = signer.requests.single()["params"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(4, params.size)
        assertEquals(signer.pubkey, params[0])
        assertEquals("geheim123", params[1])
        assertEquals(DartJson.encode(Nip46ClientMetadata.values), params[3])
        val perms = params[2]
        assertTrue(perms.contains("get_public_key"))
        for (kind in Nip46Client.SIGNED_KINDS) assertTrue("kind $kind fehlt", perms.contains("sign_event:$kind"))
        client.close()
    }

    @Test
    fun connectWithoutSecretKeepsMetadataInFourthPosition() = runBlocking {
        val client = build()
        client.connect()
        val params = signer.requests.single()["params"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(4, params.size)
        assertEquals(signer.pubkey, params[0])
        assertEquals("", params[1])
        assertEquals(Nip46Client.REQUESTED_PERMS, params[2])
        assertEquals(Fixtures.identity["nip46"]!!.jsonObject["clientMetadataJson"]!!.jsonObject["value"]!!.jsonPrimitive.content, params[3])
        client.close()
    }

    @Test
    fun pingDetectsALiveSession() = runBlocking {
        signer.respond = { r -> jsonObjectOf("id" to r["id"], "result" to "pong") }
        val client = build()
        assertTrue(client.ping())
        client.close()
    }

    @Test
    fun pingIsFalseWhenTheSignerIsSilent() = runBlocking {
        signer.respond = { null }
        val client = build()
        assertFalse(client.ping())
        client.close()
    }

    @Test
    fun closedDropsTheLinkAndTheNextRequestReconnects() = runBlocking {
        signer.respond = { r -> jsonObjectOf("id" to r["id"], "result" to "pong") }
        val client = build()
        assertTrue(client.ping())
        assertEquals(1, signer.links.size)
        val first = signer.links.single()
        first.pushRaw(DartJson.encode(listOf("CLOSED", "sub", "auth-required: login")))
        eventually { first.closed }
        assertTrue(client.ping())
        assertTrue(signer.links.size > 1)
        client.close()
    }

    // ---------------- Fehlerwege ----------------

    @Test
    fun remoteErrorIsPropagated() = runBlocking {
        signer.respond = { r -> jsonObjectOf("id" to r["id"], "error" to "Nutzer hat abgelehnt") }
        val client = build()
        val e = assertThrows<Nip46RemoteException> { runBlocking { client.connect() } }
        assertEquals("Nutzer hat abgelehnt", e.message)
        client.close()
    }

    @Test
    fun delayedAnswerWithinTheDeadlineUsesTheSameSocket() = runBlocking {
        signer.respond = { r ->
            answerLater({ signer.links.single() }, { jsonObjectOf("id" to r["id"], "result" to "sealed") }, shortLimit / 2)
            null
        }
        val client = build()
        assertEquals("sealed", client.nip44Encrypt(signer.pubkey, "probe"))
        assertEquals(1, signer.requests.size)
        assertEquals(1, signer.links.size)
        client.close()
    }

    @Test
    fun silentRequestsAreSentExactlyOnce() = runBlocking {
        for (method in listOf("nip44_encrypt", "nip44_decrypt", "sign_event")) {
            for (acknowledged in listOf(true, false)) {
                signer = FakeSigner()
                signer.respond = { null }
                signer.acknowledge = acknowledged
                val client = build()
                assertThrows<Nip46TimeoutException> {
                    runBlocking {
                        when (method) {
                            "nip44_encrypt" -> client.nip44Encrypt(signer.pubkey, "probe")
                            "nip44_decrypt" -> client.nip44Decrypt(signer.pubkey, "sealed")
                            else -> client.signEvent(jsonObjectOf("kind" to 30078))
                        }
                    }
                }
                assertEquals("$method ACK=$acknowledged", 1, signer.requests.size)
                client.close()
            }
        }
    }

    @Test
    fun duplicateRejectionOfOneRelayCountsOnce() = runBlocking {
        signer.rejectingRelays = setOf(relayA)
        signer.rejectReason = "invalid: denied"
        signer.acknowledge = false
        signer.respond = { r ->
            val rejected = signer.links.first { it.url == relayA }
            val frame = rejected.sentFrames.first { it.startsWith("[\"EVENT\"") }
            val eventId = DartJson.decode(frame).jsonArray[1].jsonObject["id"]!!.jsonPrimitive.content
            rejected.pushRaw(DartJson.encode(listOf("OK", eventId, false, "invalid: denied")))
            answerLater({ signer.links.first { it.url == relayB } }, { jsonObjectOf("id" to r["id"], "result" to "sealed") }, shortLimit / 3)
            null
        }
        val client = build(relays = listOf(relayA, relayB))
        assertEquals("sealed", client.nip44Encrypt(signer.pubkey, "probe"))
        client.close()
    }

    @Test
    fun lateSocketEndDoesNotRemoveTheReplacementLink() = runBlocking {
        signer.respond = { r -> jsonObjectOf("id" to r["id"], "result" to "sealed") }
        val client = build()
        client.nip44Encrypt(signer.pubkey, "first")
        val old = signer.links.single()
        old.beforeClose = CompletableDeferred()
        old.pushRaw(DartJson.encode(listOf("CLOSED", "sub", "auth-required: closed")))
        client.nip44Encrypt(signer.pubkey, "second")
        assertEquals(2, signer.links.size)
        old.beforeClose!!.complete(Unit)
        delay(20)
        client.nip44Encrypt(signer.pubkey, "third")
        assertEquals(2, signer.links.size)
        client.close()
    }

    @Test
    fun cryptoRejectionIsNotRetried() = runBlocking {
        signer.respond = { r -> jsonObjectOf("id" to r["id"], "error" to "denied") }
        val client = build()
        assertThrows<Nip46RemoteException> { runBlocking { client.nip44Decrypt(signer.pubkey, "sealed") } }
        assertEquals(1, signer.requests.size)
        client.close()
    }

    @Test
    fun blockedRelayIsSkippedDuringTheCooldown() = runBlocking {
        for (reason in listOf("blocked: pubkey not allowed", "rate-limited: slow down", "banned: too many rate-limit violations")) {
            signer = FakeSigner()
            signer.rejectingRelays = setOf(relayA)
            signer.rejectReason = reason
            signer.respond = { r -> jsonObjectOf("id" to r["id"], "result" to "sealed") }
            val client = build(relays = listOf(relayA, relayB))
            client.nip44Encrypt(signer.pubkey, "probe")
            val blocked = signer.links.first { it.url == relayA }
            eventually { blocked.closed }
            val sent = blocked.sentFrames.size
            client.nip44Encrypt(signer.pubkey, "probe")
            assertEquals(reason, sent, blocked.sentFrames.size)

            // A reconnect of the remaining link must not bypass the two-minute pause.
            signer.links.first { it.url == relayB }.pushRaw(DartJson.encode(listOf("CLOSED", "sub", "error: disconnected")))
            client.nip44Encrypt(signer.pubkey, "probe")
            assertEquals(reason, 1, signer.links.count { it.url == relayA })
            assertEquals(reason, 2, signer.links.count { it.url == relayB })
            client.close()
        }
    }

    @Test
    fun nip44LogsFrameAndDurationButNoPayload() = runBlocking {
        for (method in listOf("nip44_encrypt", "nip44_decrypt")) {
            val before = AppLogger.entries.value.size
            signer.respond = { r -> jsonObjectOf("id" to r["id"], "result" to "result") }
            val client = build()
            if (method == "nip44_encrypt") {
                client.nip44Encrypt(signer.pubkey, "private-badge-fixture")
            } else {
                client.nip44Decrypt(signer.pubkey, "private-badge-fixture")
            }
            val log = AppLogger.entries.value.drop(before).filter { it.tag == "Nip46Transport" }.joinToString("\n") { it.message }
            assertTrue(log, log.contains("$method:"))
            assertTrue(log.contains("Rahmen-Bytes"))
            assertTrue(log.contains("Relay-Verbindungen"))
            assertTrue(log.contains("Antwort nach"))
            assertFalse(log.contains("Nutzlast"))
            assertFalse(log.contains("private-badge-fixture"))
            client.close()
        }
    }

    @Test
    fun pendingApprovalIsNotRequestedTwice() = runBlocking {
        signer.respond = { r -> jsonObjectOf("id" to r["id"], "result" to "auth_url", "error" to "https://signer.test/approve") }
        val client = build()
        val e = assertThrows<Nip46TimeoutException> { runBlocking { client.signEvent(jsonObjectOf("kind" to 1)) } }
        assertTrue(e.waitingForApproval)
        assertEquals(1, signer.requests.size)
        client.close()
    }

    @Test
    fun silenceEndsInTimeoutWithTheMethodName() = runBlocking {
        signer.respond = { null }
        val client = build()
        val e = assertThrows<Nip46TimeoutException> {
            runBlocking { client.signEvent(jsonObjectOf("kind" to 1, "content" to "", "tags" to emptyList<Any>())) }
        }
        assertEquals("sign_event", e.method)
        client.close()
    }

    @Test
    fun foreignSenderIsDroppedAndCounted() = runBlocking {
        signer.respond = { r ->
            signer.pushFromStranger(jsonObjectOf("id" to r["id"], "result" to "ack"), clientPub)
            null
        }
        val client = build()
        val e = assertThrows<Nip46TimeoutException> { runBlocking { client.connect() } }
        assertTrue(e.foreignSender > 0)
        assertEquals(0, e.undecryptable)
        client.close()
    }

    @Test
    fun rejectionByAllRelaysReportsTheReasonInsteadOfWaiting() = runBlocking {
        signer.rejectingRelays = setOf(relayA)
        signer.rejectReason = "blocked: pubkey not allowed"
        val client = build()
        val e = assertThrows<Nip46RelayRejectedException> { runBlocking { client.connect() } }
        val latency = signer.rejectionMark!!.elapsedNow()
        assertEquals("blocked: pubkey not allowed", e.reason)
        assertTrue(e.message.contains("blocked: pubkey not allowed"))
        assertTrue("die Ablehnung muss sofort durchschlagen ($latency)", latency < shortLimit)
        assertTrue("ein abgewiesenes Ereignis erreicht den Signer nie", signer.requests.isEmpty())
        client.close()
    }

    @Test
    fun oneRejectingRelayDoesNotFailTheRequest() = runBlocking {
        signer.rejectingRelays = setOf(relayA)
        val client = build(relays = listOf(relayA, relayB))
        client.connect()
        assertEquals(1, signer.requests.size)
        client.close()
    }

    @Test
    fun unpairedClientSendsNothing() = runBlocking {
        val client = build(paired = false)
        assertThrows<Nip46Exception> {
            runBlocking { client.signEvent(jsonObjectOf("kind" to 1, "content" to "", "tags" to emptyList<Any>())) }
        }
        assertTrue(signer.requests.isEmpty())
        client.close()
    }

    @Test
    fun unreachableRelayGivesAClearError() = runBlocking {
        signer.failingRelays = setOf(relayA)
        val client = build()
        val e = assertThrows<Nip46Exception> { runBlocking { client.connect() } }
        assertTrue(e.message, e.message.contains("erreichbar"))
        client.close()
    }

    @Test
    fun socketDyingAfterSubscribeFailsImmediately() = runBlocking {
        signer.deadAfterOpen = setOf(relayA)
        val client = build()
        val e = assertThrows<Nip46Exception> { runBlocking { client.connect() } }
        assertTrue(e.message, e.message.contains("erreichbar"))
        assertTrue(signer.requests.isEmpty())
        client.close()
    }

    @Test
    fun closeDoesNotLeavePendingRequestsHanging() = runBlocking {
        signer.respond = { null }
        val client = build()
        val pending = async { runCatching { client.connect() } }
        delay(20)
        client.close()
        val result = pending.await()
        assertTrue(result.exceptionOrNull() is Nip46Exception)
    }

    // ---------------- Mehrere Relays ----------------

    @Test
    fun aDeadRelayDoesNotDisturbTheOther() = runBlocking {
        signer.failingRelays = setOf(relayA)
        val client = build(relays = listOf(relayA, relayB))
        client.connect()
        assertEquals(listOf(relayB), signer.links.map { it.url })
        client.close()
    }

    @Test
    fun duplicateAnswersFromTwoRelaysGiveExactlyOneResult() = runBlocking {
        signer.answerFromAllRelays = true
        val client = build(relays = listOf(relayA, relayB))
        client.connect()
        delay(50)
        assertEquals(2, signer.links.size)
        client.close()
    }

    @Test
    fun parallelRequestsOpenEachRelayOnce() = runBlocking {
        val client = build(relays = listOf(relayA, relayB))
        (1..3).map { async(Dispatchers.Default) { client.connect() } }.awaitAll()
        assertEquals(listOf(relayA, relayB), signer.links.map { it.url }.sorted())
        client.close()
    }

    @Test
    fun theRequestGoesToEveryOpenRelay() = runBlocking {
        val client = build(relays = listOf(relayA, relayB))
        client.connect()
        for (link in signer.links) assertEquals(1, link.sentFrames.count { it.startsWith("[\"EVENT\"") })
        client.close()
    }

    // ---------------- auth_url ----------------

    @Test
    fun interimAuthUrlIsReportedAndTheRequestStaysOpen() = runBlocking {
        val urls = CopyOnWriteArrayList<String>()
        var first = true
        signer.respond = { r ->
            if (first) {
                first = false
                timers.launch {
                    delay(30)
                    for (link in signer.links) link.push(signer.wrap(jsonObjectOf("id" to r["id"], "result" to "ack"), clientPub))
                }
                jsonObjectOf("id" to r["id"], "result" to "auth_url", "error" to "https://nsec.app/auth?x=1")
            } else {
                null
            }
        }
        val client = build(onAuthUrl = { urls += it })
        client.connect()
        assertEquals(listOf("https://nsec.app/auth?x=1"), urls.toList())
        client.close()
    }

    // ---------------- Kopplung über nostrconnect ----------------

    @Test
    fun signerIdentifiesWithTheOneTimeSecret() = runBlocking {
        val secret = "abcdef0123456789"
        val client = build(paired = false)
        val pairing = async { client.awaitPairing(secret) }
        delay(20)
        for (link in signer.links) link.push(signer.wrap(jsonObjectOf("id" to "x", "result" to secret), clientPub))
        assertEquals(signer.pubkey, pairing.await())
        assertEquals(signer.pubkey, client.remoteSignerPubkey)
        assertTrue(client.isPaired)
        client.close()
    }

    @Test
    fun secretEchoWhileAnotherRelayIsStillOpeningPairs() = runBlocking {
        val secret = "abcdef0123456789"
        val slowB = Nip46RelayOpener { url ->
            if (url == relayB) {
                delay(250)
                throw java.io.IOException("langsam (Test)")
            }
            signer.opener.open(url)
        }
        val client = Nip46Client(
            clientSecretKeyHex = clientKey,
            relays = listOf(relayA, relayB),
            timeouts = Nip46Timeouts.uniform(2_000.milliseconds),
            openRelay = slowB,
            timeSource = FakeTime(System.currentTimeMillis()),
        )
        val pairing = async { client.awaitPairing(secret) }
        eventually { signer.links.isNotEmpty() && signer.links[0].sentFrames.isNotEmpty() }
        // Relay B is still connecting: the echo on A must not be dropped.
        signer.links[0].push(signer.wrap(jsonObjectOf("id" to "x", "result" to secret), clientPub))
        assertEquals(signer.pubkey, pairing.await())
        client.close()
    }

    @Test
    fun closeWhileRelaysAreOpeningFailsCallersWithNip46Exception() = runBlocking {
        val hanging = Nip46RelayOpener { _ -> kotlinx.coroutines.awaitCancellation() }
        val client = Nip46Client(
            clientSecretKeyHex = clientKey,
            relays = listOf(relayA),
            remoteSignerPubkey = signer.pubkey,
            timeouts = Nip46Timeouts.uniform(5_000.milliseconds),
            openRelay = hanging,
            timeSource = FakeTime(System.currentTimeMillis()),
        )
        val rpc = async { runCatching { client.getPublicKey() } }
        val unpaired = Nip46Client(
            clientSecretKeyHex = Nip46Client.generateClientKey(),
            relays = listOf(relayA),
            timeouts = Nip46Timeouts.uniform(5_000.milliseconds),
            openRelay = hanging,
            timeSource = FakeTime(System.currentTimeMillis()),
        )
        val pairing = async { runCatching { unpaired.awaitPairing("s") } }
        delay(50)
        val mark = TimeSource.Monotonic.markNow()
        client.close()
        unpaired.close()
        val rpcError = rpc.await().exceptionOrNull()
        val pairingError = pairing.await().exceptionOrNull()
        // Not a silent CancellationException in a coroutine that was not cancelled.
        assertTrue("got $rpcError", rpcError is Nip46Exception && rpcError !is kotlinx.coroutines.CancellationException)
        assertTrue("got $pairingError", pairingError is Nip46Exception)
        assertTrue(mark.elapsedNow() < 2_000.milliseconds)
        // After close a new pairing fails at once.
        assertThrows<Nip46Exception> { runBlocking { unpaired.awaitPairing("s") } }
        Unit
    }

    @Test
    fun wrongSecretDoesNotPair() = runBlocking {
        val client = build(paired = false)
        val pairing = async { runCatching { client.awaitPairing("richtig") } }
        delay(20)
        for (link in signer.links) link.push(signer.wrap(jsonObjectOf("id" to "x", "result" to "falsch"), clientPub))
        assertTrue(pairing.await().exceptionOrNull() is Nip46TimeoutException)
        assertFalse(client.isPaired)
        client.close()
    }

    // ---------------- Bunker-Adresse ----------------

    @Test
    fun fullAddressIsParsed() {
        val p = BunkerPointer.parse(" bunker://${"ab".repeat(32)}?relay=wss://a.test&relay=wss://b.test&secret=deadbeef ")
        assertEquals("ab".repeat(32), p.remoteSignerPubkey)
        assertEquals(listOf("wss://a.test", "wss://b.test"), p.relays)
        assertEquals("deadbeef", p.secret)
    }

    @Test
    fun repeatedSecretTakesTheLastValueLikeDart() {
        // Dart uri.queryParameters (Uri.splitQueryString): the last value of a repeated key wins.
        val p = BunkerPointer.parse("bunker://${"ab".repeat(32)}?relay=wss://a.test&secret=a&secret=b")
        assertEquals("b", p.secret)
        // A pair with an empty key is no "secret" parameter.
        assertEquals("c", BunkerPointer.parse("bunker://${"ab".repeat(32)}?relay=wss://a.test&=x&secret=c").secret)
    }

    @Test
    fun invalidPercentEscapesStayLiteralLikeDart() {
        // Verified with the Dart SDK: Uri.parse keeps invalid escapes (incl. non-ASCII digits) as '%'.
        val base = "bunker://${"ab".repeat(32)}?relay=wss://a.test&secret="
        assertEquals("%zz1", BunkerPointer.parse(base + "%zz1").secret)
        assertEquals("%\u06631", BunkerPointer.parse(base + "%\u06631").secret)
        assertEquals("50%", BunkerPointer.parse(base + "50%").secret)
        assertEquals("A1", BunkerPointer.parse(base + "%41%31").secret)
    }

    @Test
    fun npubSignerKeyIsAccepted() {
        val keys = KeyPair.generate()
        val p = BunkerPointer.parse("bunker://${Nip19.encodeNpub(keys.publicKeyHex)}?relay=wss://a.test")
        assertEquals(keys.publicKeyHex, p.remoteSignerPubkey)
    }

    @Test
    fun persistFormDropsTheSecret() {
        val p = BunkerPointer.parse("bunker://${"ab".repeat(32)}?relay=wss://a.test&secret=deadbeef")
        val uri = p.toBunkerUri()
        assertFalse(uri.contains("secret"))
        assertTrue(uri.contains("relay=wss%3A%2F%2Fa.test"))
        assertEquals(listOf("wss://a.test"), BunkerPointer.parse(uri).relays)
    }

    @Test
    fun missingRelayWrongSchemeAndGarbageAreRejected() {
        for (bad in listOf(
            "bunker://${"ab".repeat(32)}",
            "bunker://${"ab".repeat(32)}?relay=http://a.test",
            "https://example.com",
            "bunker://nichthex?relay=wss://a.test",
            "bunker://${"ab".repeat(31)}?relay=wss://a.test",
            "",
        )) {
            assertThrows<Nip46Exception> { BunkerPointer.parse(bad) }
        }
    }

    @Test
    fun nostrConnectUriCarriesSecretPermissionsAndRelays() {
        val uri = BunkerPointer.buildNostrConnectUri(
            clientPubkeyHex = "cd".repeat(32),
            relays = listOf("wss://a.test"),
            secret = "abc123",
            perms = Nip46Client.REQUESTED_PERMS,
            appName = Nip46ClientMetadata.NAME,
            appUrl = Nip46ClientMetadata.URL,
            appImage = Nip46ClientMetadata.IMAGE,
        )
        assertTrue(uri.startsWith("nostrconnect://${"cd".repeat(32)}?"))
        assertTrue(uri.contains("secret=abc123"))
        assertTrue(uri.contains("sign_event%3A21000"))
        assertTrue(uri.contains("name=Einundzwanzig%20Meetup"))
        assertEquals(Nip46ClientMetadata.URL, queryParam(uri, "url"))
        assertEquals(Nip46ClientMetadata.IMAGE, queryParam(uri, "image"))
        assertEquals(listOf("wss://a.test"), queryAll(uri)["relay"])
    }

    @Test
    fun appNameStaysIntactForNativeUriParsers() {
        for (name in listOf("Einundzwanzig Meetup", "Meetup + Café & Bitcoin")) {
            val uri = BunkerPointer.buildNostrConnectUri("cd".repeat(32), listOf("wss://a.test"), "abc123", Nip46Client.REQUESTED_PERMS, name)
            val encodedName = uri.split("&name=").last()
            assertEquals(name, java.net.URLDecoder.decode(encodedName.replace("+", "%2B"), Charsets.UTF_8))
            assertFalse(encodedName.contains("+"))
            assertEquals(name, queryParam(uri, "name"))
        }
    }

    @Test
    fun nostrConnectEncodesImageAndAppUrlsWithoutLosingParameters() {
        val appUrl = "https://example.org/app?lang=de&source=signer"
        val image = "https://example.org/icon.png?v=2&size=192"
        val uri = BunkerPointer.buildNostrConnectUri(
            "cd".repeat(32),
            listOf("wss://a.test", "wss://b.test"),
            "abc123",
            Nip46Client.REQUESTED_PERMS,
            "Einundzwanzig Meetup",
            appUrl,
            image,
        )
        assertEquals(appUrl, queryParam(uri, "url"))
        assertEquals(image, queryParam(uri, "image"))
        assertEquals(listOf("wss://a.test", "wss://b.test"), queryAll(uri)["relay"])
    }

    // ---------------- Fixture vectors (spec 01 §10.6) ----------------

    @Test
    fun fixtureVectorsMatch() {
        val nip46 = Fixtures.identity["nip46"]!!.jsonObject
        assertEquals(nip46["requestedPerms"]!!.jsonObject["value"]!!.jsonPrimitive.content, Nip46Client.REQUESTED_PERMS)
        assertEquals(
            nip46["clientMetadataJson"]!!.jsonObject["value"]!!.jsonPrimitive.content,
            DartJson.encode(Nip46ClientMetadata.values),
        )
        val connect = nip46["nostrConnectUri"]!!.jsonObject
        val input = connect["input"]!!.jsonObject
        assertEquals(
            connect["expected"]!!.jsonPrimitive.content,
            BunkerPointer.buildNostrConnectUri(
                input["clientPubkeyHex"]!!.jsonPrimitive.content,
                input["relays"]!!.jsonArray.map { it.jsonPrimitive.content },
                input["secret"]!!.jsonPrimitive.content,
                Nip46Client.REQUESTED_PERMS,
                input["appName"]!!.jsonPrimitive.content,
                input["appUrl"]!!.jsonPrimitive.content,
                input["appImage"]!!.jsonPrimitive.content,
            ),
        )
        for (case in nip46["appNameEncoding"]!!.jsonObject["cases"]!!.jsonArray) {
            val name = case.jsonObject["name"]!!.jsonPrimitive.content
            val uri = BunkerPointer.buildNostrConnectUri("cd".repeat(32), listOf("wss://a.test"), "s", "p", name)
            assertEquals(case.jsonObject["encoded"]!!.jsonPrimitive.content, uri.substringAfter("&name="))
        }
        val parse = nip46["bunkerParse"]!!.jsonObject
        for (valid in parse["valid"]!!.jsonArray) {
            val v = valid.jsonObject
            val p = BunkerPointer.parse(v["input"]!!.jsonPrimitive.content)
            assertEquals(v["remoteSignerPubkey"]!!.jsonPrimitive.content, p.remoteSignerPubkey)
            assertEquals(v["relays"]!!.jsonArray.map { it.jsonPrimitive.content }, p.relays)
            assertEquals(v["secret"]!!.jsonPrimitive.content, p.secret)
            assertEquals(parse["twoRelaysPersistForm"]!!.jsonPrimitive.content, p.toBunkerUri())
        }
        val persist = parse["persistForm"]!!.jsonObject
        assertEquals(
            persist["toBunkerUri"]!!.jsonPrimitive.content,
            BunkerPointer.parse(persist["input"]!!.jsonPrimitive.content).toBunkerUri(),
        )
        for (bad in parse["invalid"]!!.jsonArray) assertThrows<Nip46Exception> { BunkerPointer.parse(bad.jsonPrimitive.content) }

        val t = nip46["timeoutsSeconds"]!!.jsonObject
        val d = Nip46Timeouts()
        assertEquals(t["connect"]!!.jsonPrimitive.content.toLong(), d.connect.inWholeSeconds)
        assertEquals(t["pairing"]!!.jsonPrimitive.content.toLong(), d.pairing.inWholeSeconds)
        assertEquals(t["sign_event"]!!.jsonPrimitive.content.toLong(), d.sign.inWholeSeconds)
        assertEquals(t["ping"]!!.jsonPrimitive.content.toLong(), d.ping.inWholeSeconds)
        assertEquals(t["relayOpen"]!!.jsonPrimitive.content.toLong(), d.relayOpen.inWholeSeconds)
        assertEquals(t["relayCooldownAfterBlock"]!!.jsonPrimitive.content.toLong(), d.relayCooldown.inWholeSeconds)
    }

    @Test
    fun subscriptionUsesKind24133ThePTagAndASixtySecondSkew() = runBlocking {
        val time = FakeTime(1_786_000_000_000L)
        val client = Nip46Client(clientKey, listOf(relayA), signer.pubkey, null, Nip46Timeouts.uniform(shortLimit), signer.opener, time)
        client.connect()
        val req = DartJson.decode(signer.links.single().sentFrames.first()).jsonArray
        assertEquals("REQ", req[0].jsonPrimitive.content)
        assertTrue(req[1].jsonPrimitive.content.matches(Regex("nip46-[0-9a-f]{16}")))
        assertEquals("""{"kinds":[24133],"#p":["$clientPub"],"since":1785999940}""", DartJson.encode(req[2]))
        val event = DartJson.decode(signer.links.single().sentFrames[1]).jsonArray[1].jsonObject
        assertEquals(JsonArray(listOf(JsonArray(listOf(JsonPrimitive("p"), JsonPrimitive(signer.pubkey))))), event["tags"])
        assertEquals("24133", event["kind"]!!.jsonPrimitive.content)
        client.close()
    }
}
