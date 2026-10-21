package space.einundzwanzig.meetup.core.nostr.relay

import app.cash.turbine.test
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.dartStringOrNull
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.nostr.NostrFilter
import space.einundzwanzig.meetup.core.nostr.toJsonObject
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelayConnector
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelaySocket
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RelayClientTest {

    private val alice = KeyPair.fromPrivateKeyHex("0".repeat(63) + "1")
    private val bob = KeyPair.fromPrivateKeyHex("0".repeat(63) + "2")
    private val filter = NostrFilter(kinds = listOf(1), authors = listOf(alice.publicKeyHex))

    private fun note(content: String, key: KeyPair = alice, kind: Int = 1, createdAt: Long = 1_700_000_000) =
        EventIds.sign(key, createdAt, kind, emptyList(), content)

    /** A relay that answers every REQ through [answer]. */
    private fun relay(answer: (subId: String, socket: FakeRelaySocket) -> Unit) = FakeRelayConnector { url ->
        FakeRelaySocket(url) { frame, socket ->
            val msg = DartJson.decode(frame).jsonArray
            if (msg[0].dartStringOrNull() == "REQ") answer(msg[1].dartStringOrNull()!!, socket)
        }
    }

    private fun TestScope.client(connector: RelayConnector) = RelayClient(connector, UnconfinedTestDispatcher(testScheduler))

    @Test
    fun queryCollectsVerifiedMatchingEventsUntilEose() = runTest {
        val good = note("hallo")
        val connector = relay { sub, s ->
            s.push(listOf("EVENT", sub, good.toJsonObject()))
            s.push(listOf("EVENT", sub, JsonObject(good.toJsonObject() + ("sig" to JsonPrimitive("00".repeat(64))))))
            s.push(listOf("EVENT", sub, note("anderer autor", bob).toJsonObject()))
            s.push(listOf("EVENT", sub, note("falsche art", kind = 7).toJsonObject()))
            s.push(listOf("EVENT", "fremdes-abo", note("anderes abo").toJsonObject()))
            s.pushFrame("invalid JSON")
            s.push(listOf("NOTICE", "hi"))
            s.push(listOf("EOSE", sub))
            s.push(listOf("EVENT", sub, note("nach eose").toJsonObject()))
        }
        val seen = mutableListOf<JsonObject>()
        val result = client(connector).query("wss://r.example", filter) { seen += it }

        assertEquals(RelayQueryStatus.EOSE, result.status)
        assertEquals(listOf(good.toJsonObject()), result.events)
        assertEquals(result.events, seen)
        assertEquals(1, result.unparsedFrames)
        assertTrue(result.isComplete)
        val socket = connector.sockets.single() as FakeRelaySocket
        assertTrue(socket.closedByClient)
        val req = socket.sentJson.single().jsonArray
        assertEquals("REQ", req[0].dartStringOrNull())
        assertTrue(req[1].dartStringOrNull()!!.matches(Regex("q-[0-9a-f]{16}")))
        assertEquals(filter.toJson(), req[2])
    }

    @Test
    fun partialAnswerSurvivesTheResponseTimeout() = runTest {
        val good = note("eins")
        val connector = relay { sub, s -> s.push(listOf("EVENT", sub, good.toJsonObject())) }
        val result = client(connector).query(
            "wss://r.example",
            filter,
            RelayQueryOptions(responseTimeout = 2.seconds, subscriptionId = "nam-1"),
        )
        assertEquals(RelayQueryStatus.TIMEOUT, result.status)
        assertEquals(listOf(good.toJsonObject()), result.events)
        assertTrue(result.hasAnswer)
        assertFalse(result.isComplete)
        assertEquals("""["REQ","nam-1",${DartJson.encode(filter.toJson())}]""", (connector.sockets.single() as FakeRelaySocket).sent.single())
    }

    @Test
    fun closedEndsTheQueryWithItsReason() = runTest {
        val connector = relay { sub, s -> s.push(listOf("CLOSED", sub, "auth-required: bitte anmelden")) }
        val result = client(connector).query("wss://r.example", filter)
        assertEquals(RelayQueryStatus.CLOSED, result.status)
        assertEquals("auth-required: bitte anmelden", result.closedReason)
        assertFalse(result.hasAnswer)

        val ignoring = client(connector).query(
            "wss://r.example",
            filter,
            RelayQueryOptions(stopOnClosed = false, responseTimeout = 1.seconds),
        )
        assertEquals(RelayQueryStatus.TIMEOUT, ignoring.status)
    }

    @Test
    fun connectionLossBeforeEoseIsDisconnected() = runTest {
        val good = note("eins")
        val connector = relay { sub, s ->
            s.push(listOf("EVENT", sub, good.toJsonObject()))
            s.finish(java.io.IOException("reset"))
        }
        val result = client(connector).query("wss://r.example", filter)
        assertEquals(RelayQueryStatus.DISCONNECTED, result.status)
        assertEquals(listOf(good.toJsonObject()), result.events)
        assertEquals("reset", result.error?.message)
    }

    @Test
    fun connectFailuresAreReportedNotThrown() = runTest {
        val refused = client(FakeRelayConnector.unreachable()).query("wss://down.example", filter)
        assertEquals(RelayQueryStatus.CONNECT_FAILED, refused.status)
        assertTrue(refused.error is RelayConnectException)

        val stalled = client(FakeRelayConnector { awaitCancellation() })
            .query("wss://slow.example", filter, RelayQueryOptions(connectTimeout = 3.seconds))
        assertEquals(RelayQueryStatus.CONNECT_FAILED, stalled.status)
        assertEquals(3_000, testScheduler.currentTime)
    }

    @Test
    fun maxEventsStopsEarly() = runTest {
        val connector = relay { sub, s -> repeat(5) { s.push(listOf("EVENT", sub, note("n$it", createdAt = 1_700_000_000L + it).toJsonObject())) } }
        val result = client(connector).query("wss://r.example", filter, RelayQueryOptions(maxEvents = 3))
        assertEquals(RelayQueryStatus.LIMIT, result.status)
        assertEquals(3, result.events.size)
    }

    @Test
    fun verificationAndFilterMatchingCanBeSwitchedOff() = runTest {
        val unsigned = jsonObjectOf("kind" to 24133, "content" to "x")
        val connector = relay { sub, s ->
            s.push(listOf("EVENT", sub, unsigned))
            s.push(listOf("EVENT", sub, "kein objekt"))
            s.push(listOf("EOSE", sub))
        }
        val result = client(connector).query(
            "wss://r.example",
            filter,
            RelayQueryOptions(verifySignatures = false, matchFilters = false),
        )
        assertEquals(listOf(unsigned), result.events)
    }

    @Test
    fun queryAllKeepsRelayOrderInBothFanOuts() = runTest {
        val connector = FakeRelayConnector { url ->
            if (url.contains("down")) throw RelayConnectException(url, null)
            FakeRelaySocket(url) { frame, s ->
                val sub = DartJson.decode(frame).jsonArray[1].dartStringOrNull()
                s.push(listOf("EVENT", sub, note(url).toJsonObject()))
                s.push(listOf("EOSE", sub))
            }
        }
        val relays = listOf("wss://a.example", "wss://down.example", "wss://c.example")
        for (fanOut in RelayFanOut.entries) {
            connector.connects.clear()
            val results = client(connector).queryAll(relays, filter, fanOut = fanOut)
            assertEquals(relays, results.map { it.relay })
            assertEquals(listOf(RelayQueryStatus.EOSE, RelayQueryStatus.CONNECT_FAILED, RelayQueryStatus.EOSE), results.map { it.status })
            assertEquals("wss://c.example", (results[2].events.single()["content"] as JsonPrimitive).content)
            if (fanOut == RelayFanOut.SEQUENTIAL) assertEquals(relays, connector.connects)
        }
    }

    // ---- publish ------------------------------------------------------------------------

    private fun ackingRelay(reply: (eventId: String, socket: FakeRelaySocket) -> Unit) = FakeRelayConnector { url ->
        FakeRelaySocket(url) { frame, socket ->
            val msg = DartJson.decode(frame).jsonArray
            if (msg[0].dartStringOrNull() == "EVENT") reply((msg[1] as JsonObject)["id"].dartStringOrNull()!!, socket)
        }
    }

    @Test
    fun publishWaitsForTheMatchingOk() = runTest {
        val ev = note("veröffentlichen")
        val connector = ackingRelay { id, s ->
            s.push(listOf("OK", "other-id", false, "nicht meins"))
            s.push(listOf("OK", id, true, ""))
        }
        val result = client(connector).publish("wss://r.example", ev)
        assertEquals(RelayPublishStatus.ACCEPTED, result.status)
        assertTrue(result.accepted)
        val socket = connector.sockets.single() as FakeRelaySocket
        assertEquals(ev.toEventMessage(), socket.sent.single())
        assertTrue(socket.closedByClient)
    }

    @Test
    fun publishReportsRejectionTimeoutAndDisconnect() = runTest {
        val ev = note("x")
        val rejected = client(ackingRelay { id, s -> s.push(listOf("OK", id, false, "blocked: spam")) }).publish("wss://r", ev)
        assertEquals(RelayPublishStatus.REJECTED, rejected.status)
        assertEquals("blocked: spam", rejected.message)

        val silent = client(ackingRelay { _, s -> s.push(listOf("OK", "other", true, "")) })
            .publish("wss://r", ev, RelayPublishOptions(ackTimeout = 5.seconds))
        assertEquals(RelayPublishStatus.TIMEOUT, silent.status)

        val anyOk = client(ackingRelay { _, s -> s.push(listOf("OK", "other", true, "")) })
            .publish("wss://r", ev, RelayPublishOptions(matchEventId = false))
        assertEquals(RelayPublishStatus.ACCEPTED, anyOk.status)

        val dropped = client(ackingRelay { _, s -> s.finish() }).publish("wss://r", ev)
        assertEquals(RelayPublishStatus.DISCONNECTED, dropped.status)

        val down = client(FakeRelayConnector.unreachable()).publish("wss://r", ev)
        assertEquals(RelayPublishStatus.CONNECT_FAILED, down.status)
    }

    @Test
    fun publishRawAndPublishAll() = runTest {
        val ev = note("roh")
        val connector = ackingRelay { id, s -> s.push(listOf("OK", id, id == ev.id, "")) }
        val raw = client(connector).publishRaw("wss://r", ev.toJsonObject())
        assertTrue(raw.accepted)
        val all = client(connector).publishAll(listOf("wss://a", "wss://b"), ev, fanOut = RelayFanOut.SEQUENTIAL)
        assertEquals(listOf("wss://a", "wss://b"), all.map { it.relay })
        assertTrue(all.all { it.accepted })
    }

    // ---- subscribe ----------------------------------------------------------------------

    @Test
    fun subscribeEmitsStoredEventsEoseAndLiveEvents() = runTest {
        lateinit var socket: FakeRelaySocket
        lateinit var subId: String
        val stored = note("alt")
        val connector = relay { sub, s ->
            socket = s
            subId = sub
            s.push(listOf("EVENT", sub, stored.toJsonObject()))
            s.push(listOf("EOSE", sub))
        }
        client(connector).subscribe("wss://r", filter, RelayQueryOptions(subscriptionId = "live-1")).test {
            assertEquals(RelaySubscriptionEvent.Event(stored.toJsonObject()), awaitItem())
            assertEquals(RelaySubscriptionEvent.EndOfStoredEvents, awaitItem())
            assertEquals("live-1", subId)
            val live = note("neu", createdAt = 1_700_000_100)
            socket.push(listOf("EVENT", subId, live.toJsonObject()))
            socket.push(listOf("EVENT", subId, note("fremd", bob).toJsonObject()))
            socket.push(listOf("NOTICE", "achtung"))
            assertEquals(RelaySubscriptionEvent.Event(live.toJsonObject()), awaitItem())
            assertEquals(RelaySubscriptionEvent.Notice("achtung"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue("cancelling the collector closes the socket", socket.closedByClient)
    }

    @Test
    fun subscribeCompletesOnCloseAndThrowsOnFailure() = runTest {
        lateinit var socket: FakeRelaySocket
        val connector = relay { _, s -> socket = s }
        client(connector).subscribe("wss://r", filter).test {
            socket.finish()
            awaitComplete()
        }
        client(connector).subscribe("wss://r", filter).test {
            socket.finish(java.io.IOException("weg"))
            assertTrue(awaitError() is RelayConnectionLostException)
        }
        client(FakeRelayConnector.unreachable()).subscribe("wss://r", filter).test {
            assertTrue(awaitError() is RelayConnectException)
        }
        client(relay { sub, s -> s.push(listOf("CLOSED", sub, "nope")) }).subscribe("wss://r", filter).test {
            assertEquals(RelaySubscriptionEvent.Closed("nope"), awaitItem())
            awaitComplete()
        }
    }

    // ---- NIP-42 hook --------------------------------------------------------------------

    /** Minimal authenticator: answers the first AUTH, waits for its OK, forwards the rest. */
    private class TestAuthenticator(private val accept: Boolean) : RelayAuthenticator {
        override suspend fun authenticate(socket: RelaySocket, onMessage: (JsonArray) -> Unit): RelaySocket? {
            val ready = kotlinx.coroutines.CompletableDeferred<Boolean>()
            socket.listen(
                onFrame = { frame ->
                    val msg = DartJson.decode(frame).jsonArray
                    when {
                        msg[0].dartStringOrNull() == "AUTH" -> socket.send("""["AUTH",{"id":"auth-id"}]""")
                        msg[0].dartStringOrNull() == "OK" && msg[1].dartStringOrNull() == "auth-id" ->
                            ready.complete(msg[2] == JsonPrimitive(true))
                        else -> onMessage(msg)
                    }
                },
                onDone = { ready.complete(false) },
            )
            return if (ready.await()) socket else null.also { socket.close() }
        }
    }

    private fun authRelay(accept: Boolean) = FakeRelayConnector { url ->
        FakeRelaySocket(url) { frame, s ->
            val msg = DartJson.decode(frame).jsonArray
            when (msg[0].dartStringOrNull()) {
                "AUTH" -> s.push(listOf("OK", "auth-id", accept, ""))
                "REQ" -> {
                    val sub = msg[1].dartStringOrNull()
                    s.push(listOf("EVENT", sub, note("geschützt").toJsonObject()))
                    s.push(listOf("EOSE", sub))
                }
            }
        }.also { it.push(listOf("AUTH", "challenge")) }
    }

    @Test
    fun authenticatorRunsBeforeTheRequest() = runTest {
        val connector = authRelay(accept = true)
        val result = client(connector).query("wss://group", filter, RelayQueryOptions(authenticator = TestAuthenticator(true)))
        assertEquals(RelayQueryStatus.EOSE, result.status)
        assertEquals(1, result.events.size)
        val sent = (connector.sockets.single() as FakeRelaySocket).sent
        assertTrue(sent[0].startsWith("[\"AUTH\""))
        assertTrue(sent[1].startsWith("[\"REQ\""))
    }

    @Test
    fun failedAuthenticationIsReported() = runTest {
        val connector = authRelay(accept = false)
        val result = client(connector).query("wss://group", filter, RelayQueryOptions(authenticator = TestAuthenticator(false)))
        assertEquals(RelayQueryStatus.AUTH_FAILED, result.status)
        val publish = client(authRelay(accept = false))
            .publish("wss://group", note("x"), RelayPublishOptions(authenticator = TestAuthenticator(false)))
        assertEquals(RelayPublishStatus.AUTH_FAILED, publish.status)
        assertNull(publish.error)
        assertTrue((connector.sockets.single() as FakeRelaySocket).closedByClient)
    }

    @Test
    fun defaultTimeoutsFollowRelayConfig() {
        assertEquals(8.seconds, RelayQueryOptions().connectTimeout)
        assertEquals(8.seconds, RelayQueryOptions().responseTimeout)
        assertEquals(5.seconds, RelayPublishOptions().connectTimeout)
        assertEquals(5.seconds, RelayPublishOptions().ackTimeout)
        assertEquals(100.milliseconds, RelayQueryOptions(responseTimeout = 100.milliseconds).responseTimeout)
    }
}
