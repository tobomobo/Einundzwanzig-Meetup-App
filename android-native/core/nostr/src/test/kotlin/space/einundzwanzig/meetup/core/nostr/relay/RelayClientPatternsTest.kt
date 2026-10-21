package space.einundzwanzig.meetup.core.nostr.relay

import app.cash.turbine.test
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.dartStringOrNull
import space.einundzwanzig.meetup.core.common.log.RelayParseTally
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.core.nostr.NostrFilter
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelayConnector
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelaySocket
import space.einundzwanzig.meetup.core.nostr.toJsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The Dart service patterns on top of [RelayClient]: fire-and-linger, inactivity, multi-REQ, fallbacks. */
class RelayClientPatternsTest {

    private val alice = KeyPair.fromPrivateKeyHex("0".repeat(63) + "1")

    private fun note(content: String, kind: Int = 1, tags: List<List<String>> = emptyList()) =
        EventIds.sign(alice, 1_700_000_000, kind, tags, content)

    private fun TestScope.client(connector: RelayConnector) = RelayClient(connector, UnconfinedTestDispatcher(testScheduler))

    private fun scripted(onFrame: (msg: JsonArray, socket: FakeRelaySocket) -> Unit) = FakeRelayConnector { url ->
        FakeRelaySocket(url) { frame, socket -> onFrame(DartJson.decode(frame).jsonArray, socket) }
    }

    // ---- fire-and-linger ----------------------------------------------------------------

    @Test
    fun fireAndLingerSendsWaitsTwoSecondsClosesAndReportsSent() = runTest {
        val connector = scripted { _, _ -> } // never answers with OK
        val start = currentTime
        val result = client(connector).publish("wss://r", note("x"), RelayPublishOptions(awaitOk = false))
        assertEquals(RelayPublishStatus.SENT, result.status)
        assertTrue(result.succeeded)
        assertFalse(result.accepted)
        assertEquals(2_000L, currentTime - start)
        val socket = connector.sockets.single() as FakeRelaySocket
        assertEquals(1, socket.sent.size)
        assertTrue(socket.closedByClient)
    }

    @Test
    fun fireAndLingerCountsARelayThatClosesDuringTheLinger() = runTest {
        val connector = scripted { _, s -> s.finish() }
        val result = client(connector).publish("wss://r", note("x"), RelayPublishOptions(awaitOk = false))
        assertEquals(RelayPublishStatus.SENT, result.status)
    }

    @Test
    fun fireAndLingerFailuresAreNotSent() = runTest {
        val down = client(FakeRelayConnector.unreachable())
            .publish("wss://r", note("x"), RelayPublishOptions(awaitOk = false))
        assertEquals(RelayPublishStatus.CONNECT_FAILED, down.status)
        assertFalse(down.succeeded)

        val closedBeforeSend = client(FakeRelayConnector { url -> FakeRelaySocket(url).also { it.finish() } })
            .publish("wss://r", note("x"), RelayPublishOptions(awaitOk = false))
        assertEquals(RelayPublishStatus.CONNECT_FAILED, closedBeforeSend.status)
    }

    @Test
    fun publishAllWithFireAndLingerIsSequentialLikeDart() = runTest {
        val connector = scripted { _, _ -> }
        val start = currentTime
        val results = client(connector).publishAll(
            listOf("wss://a", "wss://b", "wss://c"), note("x"), RelayPublishOptions(awaitOk = false), RelayFanOut.SEQUENTIAL,
        )
        assertEquals(3, results.count { it.succeeded })
        assertEquals(6_000L, currentTime - start)
    }

    // ---- inactivity timeout -------------------------------------------------------------

    /** Dart AdminRegistry: `ws.stream.timeout(5 s)` restarts on every frame. */
    @Test
    fun inactivityTimeoutRestartsOnEveryFrame() = runTest {
        lateinit var socket: FakeRelaySocket
        val connector = scripted { _, s -> socket = s }
        val options = RelayPublishOptions(ackTimeout = Duration.INFINITE, ackInactivityTimeout = 5.seconds, matchEventId = false)
        val job = launch {
            val result = client(connector).publish("wss://r", note("x"), options)
            assertEquals(RelayPublishStatus.ACCEPTED, result.status)
        }
        // 4 s pauses keep the wait alive well past 5 s in total; unparsed frames count too.
        advanceTimeBy(4_000)
        socket.push(listOf("NOTICE", "hallo"))
        advanceTimeBy(4_000)
        socket.pushFrame("kein json")
        advanceTimeBy(4_000)
        socket.push(listOf("OK", "irgendeine-id", true, ""))
        job.join()
    }

    @Test
    fun inactivityTimeoutEndsTheWaitAfterSilence() = runTest {
        val connector = scripted { _, s -> s.push(listOf("NOTICE", "nur das")) }
        val options = RelayPublishOptions(ackTimeout = Duration.INFINITE, ackInactivityTimeout = 5.seconds)
        val start = currentTime
        val result = client(connector).publish("wss://r", note("x"), options)
        assertEquals(RelayPublishStatus.TIMEOUT, result.status)
        assertEquals(5_000L, currentTime - start)
    }

    // ---- multi-REQ ----------------------------------------------------------------------

    private val received = NostrFilter(kinds = listOf(9735), tags = mapOf("p" to listOf("aa")), limit = 3).toJson()
    private val requested = NostrFilter(kinds = listOf(9734), authors = listOf("bb"), limit = 3).toJson()
    private val sent = NostrFilter(kinds = listOf(9735), tags = mapOf("P" to listOf("aa")), limit = 3).toJson()

    @Test
    fun queryMultiSendsAllReqsOnOneSocketAndWaitsForEveryEose() = runTest {
        val recv = note("r", 9735, listOf(listOf("p", "aa")))
        val sentZap = note("s", 9735, listOf(listOf("p", "bb"), listOf("P", "aa")))
        val connector = scripted { msg, s ->
            if (msg[0].dartStringOrNull() != "REQ") return@scripted
            when (val sub = msg[1].dartStringOrNull()!!) {
                "zap-recv-1" -> {
                    s.push(listOf("EVENT", sub, recv.toJsonObject()))
                    s.push(listOf("EVENT", sub, sentZap.toJsonObject())) // no #p aa → not this sub's answer
                    s.push(listOf("EOSE", sub))
                }
                "zap-req-1" -> s.push(listOf("EOSE", sub))
                "zap-sent-1" -> {
                    s.push(listOf("EVENT", sub, sentZap.toJsonObject()))
                    s.push(listOf("EVENT", "fremd", recv.toJsonObject()))
                    s.pushFrame("kaputt")
                    s.push(listOf("EOSE", sub))
                }
            }
        }
        val tally = RelayParseTally("HumanityProof", "Zap-Suche")
        val seen = mutableListOf<String>()
        val result = client(connector).queryMulti(
            "wss://r",
            listOf(
                RelaySubscriptionRequest("zap-recv-1", listOf(received)),
                RelaySubscriptionRequest("zap-req-1", listOf(requested)),
                RelaySubscriptionRequest("zap-sent-1", listOf(sent)),
            ),
            RelayQueryOptions(responseTimeout = 12.seconds, parseTally = tally),
        ) { sub, _ -> seen += sub }

        assertEquals(RelayQueryStatus.EOSE, result.status)
        assertTrue(result.isComplete)
        assertEquals(listOf("zap-recv-1", "zap-sent-1"), result.events.map { it.subscriptionId })
        assertEquals(listOf(recv.toJsonObject()), result.eventsFor("zap-recv-1"))
        assertEquals(listOf(sentZap.toJsonObject()), result.eventsFor("zap-sent-1"))
        assertEquals(seen, result.events.map { it.subscriptionId })
        assertEquals(
            mapOf("zap-recv-1" to RelayQueryStatus.EOSE, "zap-req-1" to RelayQueryStatus.EOSE, "zap-sent-1" to RelayQueryStatus.EOSE),
            result.subscriptionStatus,
        )
        assertEquals(1, connector.sockets.size)
        val socket = connector.sockets.single() as FakeRelaySocket
        assertEquals(listOf("zap-recv-1", "zap-req-1", "zap-sent-1"), socket.sentJson.map { it.jsonArray[1].dartStringOrNull() })
        assertTrue(socket.closedByClient)
        assertEquals(8, result.framesReceived)
        assertEquals(1, result.unparsedFrames)
        assertEquals(8, tally.seen)
        assertEquals(1, tally.failures)
    }

    @Test
    fun queryMultiTimesOutWithPartialStatus() = runTest {
        val connector = scripted { msg, s ->
            val sub = msg[1].dartStringOrNull()!!
            if (sub == "a") s.push(listOf("EOSE", sub)) else s.push(listOf("CLOSED", sub, "auth-required: x"))
        }
        val result = client(connector).queryMulti(
            "wss://r",
            listOf(
                RelaySubscriptionRequest("a", listOf(received)),
                RelaySubscriptionRequest("b", listOf(sent)),
                RelaySubscriptionRequest("c", listOf(requested)),
            ),
            RelayQueryOptions(responseTimeout = 12.seconds, stopOnClosed = false),
        )
        assertEquals(RelayQueryStatus.TIMEOUT, result.status)
        assertEquals(RelayQueryStatus.EOSE, result.subscriptionStatus["a"])
        assertEquals(RelayQueryStatus.TIMEOUT, result.subscriptionStatus["b"])

        val closing = client(connector).queryMulti(
            "wss://r",
            listOf(RelaySubscriptionRequest("a", listOf(received)), RelaySubscriptionRequest("b", listOf(sent))),
        )
        assertEquals(RelayQueryStatus.EOSE, closing.status)
        assertEquals(RelayQueryStatus.CLOSED, closing.subscriptionStatus["b"])
        assertEquals("auth-required: x", closing.closedReasons["b"])

        val down = client(FakeRelayConnector.unreachable())
            .queryMulti("wss://r", listOf(RelaySubscriptionRequest("a", listOf(received))))
        assertEquals(RelayQueryStatus.CONNECT_FAILED, down.status)
        assertEquals(mapOf("a" to RelayQueryStatus.CONNECT_FAILED), down.subscriptionStatus)
    }

    @Test(expected = IllegalArgumentException::class)
    fun queryMultiRejectsDuplicateSubscriptionIds() = runTest {
        client(FakeRelayConnector.unreachable()).queryMulti(
            "wss://r",
            listOf(RelaySubscriptionRequest("a", listOf(received)), RelaySubscriptionRequest("a", listOf(sent))),
        )
    }

    // ---- frames, tally, onEvent, auth errors ---------------------------------------------

    @Test
    fun queryCountsFramesAndFeedsTheTally() = runTest {
        val connector = scripted { msg, s ->
            val sub = msg[1].dartStringOrNull()!!
            s.pushFrame("{}")
            s.pushFrame("[]")
            s.push(listOf("NOTICE", "x"))
            s.push(listOf("EOSE", sub))
        }
        val tally = RelayParseTally("Test", "Abfrage")
        val result = client(connector).query("wss://r", listOf(received), RelayQueryOptions(parseTally = tally))
        assertEquals(4, result.framesReceived)
        assertEquals(2, result.unparsedFrames)
        assertEquals(4, tally.seen)
        assertEquals(2, tally.failures)
    }

    @Test
    fun queryAllForwardsEventsWithTheirRelay() = runTest {
        val ev = note("hi")
        val connector = scripted { msg, s ->
            val sub = msg[1].dartStringOrNull()!!
            s.push(listOf("EVENT", sub, ev.toJsonObject()))
            s.push(listOf("EOSE", sub))
        }
        val seen = mutableListOf<Pair<String, JsonObject>>()
        client(connector).queryAll(listOf("wss://a", "wss://b"), NostrFilter(kinds = listOf(1))) { relay, event ->
            seen += relay to event
        }
        assertEquals(setOf("wss://a", "wss://b"), seen.map { it.first }.toSet())
        assertTrue(seen.all { it.second == ev.toJsonObject() })
    }

    @Test
    fun throwingAuthenticatorIsReportedWithItsError() = runTest {
        val boom = IllegalStateException("Signierer weg")
        val connector = FakeRelayConnector { url -> FakeRelaySocket(url) }
        val auth = RelayAuthenticator { _, _ -> throw boom }
        val query = client(connector).query("wss://g", listOf(received), RelayQueryOptions(authenticator = auth))
        assertEquals(RelayQueryStatus.AUTH_FAILED, query.status)
        assertSame(boom, query.error)
        assertTrue((connector.sockets.single() as FakeRelaySocket).closedByClient)
        val publish = client(connector).publish("wss://g", note("x"), RelayPublishOptions(authenticator = auth))
        assertEquals(RelayPublishStatus.AUTH_FAILED, publish.status)
        assertSame(boom, publish.error)
        client(connector).subscribe("wss://g", listOf(received), RelayQueryOptions(authenticator = auth)).test {
            val error = awaitError()
            assertTrue(error is RelayAuthException)
            assertSame(boom, error.cause)
        }
    }

    // ---- first reachable subscription -----------------------------------------------------

    @Test
    fun subscribeFirstReachableFallsBackOnConnectFailure() = runTest {
        val ev = note("live")
        val connector = FakeRelayConnector { url ->
            if (url == "wss://down") throw RelayConnectException(url, java.io.IOException("refused"))
            FakeRelaySocket(url) { frame, s ->
                val msg = DartJson.decode(frame).jsonArray
                s.push(listOf("EVENT", msg[1].dartStringOrNull(), ev.toJsonObject()))
            }
        }
        client(connector).subscribeFirstReachable(
            listOf("wss://down", "wss://up", "wss://unused"),
            listOf(NostrFilter(kinds = listOf(1)).toJson()),
            RelayQueryOptions(connectTimeout = 4.seconds, subscriptionId = "evtchat"),
        ).test {
            assertEquals("wss://up" to RelaySubscriptionEvent.Event(ev.toJsonObject()), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("wss://down", "wss://up"), connector.connects)
        val req = (connector.sockets.single() as FakeRelaySocket).sentJson.single().jsonArray
        assertEquals("evtchat", req[1].dartStringOrNull())
    }

    @Test
    fun subscribeFirstReachableThrowsWhenNoRelayAnswers() = runTest {
        client(FakeRelayConnector.unreachable())
            .subscribeFirstReachable(listOf("wss://a", "wss://b"), listOf(received))
            .test {
                val error = awaitError()
                assertTrue(error is RelayConnectException)
                assertEquals("wss://b", (error as RelayConnectException).url)
            }
        client(FakeRelayConnector.unreachable()).subscribeFirstReachable(emptyList(), listOf(received)).test {
            awaitComplete()
        }
    }

    // ---- connect/timeout race -------------------------------------------------------------

    /** A connect that finishes after the timeout fired must not leak the socket. */
    @Test
    fun socketReturnedAfterTheConnectTimeoutIsTornDown() = runTest {
        val late = FakeRelaySocket("wss://late")
        val connector = FakeRelayConnector {
            withContext(NonCancellable) { delay(2_000) }
            late
        }
        val result = client(connector).query("wss://late", listOf(received), RelayQueryOptions(connectTimeout = 1.seconds))
        assertEquals(RelayQueryStatus.CONNECT_FAILED, result.status)
        assertTrue(late.closedByClient)

        val probeSocket = FakeRelaySocket("wss://late")
        val probing = RelayConfig(
            InMemoryKeyValueStore(),
            FakeRelayConnector {
                withContext(NonCancellable) { delay(2_000) }
                probeSocket
            },
        )
        assertFalse(probing.probeRelay("wss://late", 1.seconds))
        assertTrue(probeSocket.closedByClient)
    }
}
