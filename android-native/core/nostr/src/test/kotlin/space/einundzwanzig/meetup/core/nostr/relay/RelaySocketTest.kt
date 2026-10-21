package space.einundzwanzig.meetup.core.nostr.relay

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.dartStringOrNull
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.core.nostr.NostrFilter
import space.einundzwanzig.meetup.core.nostr.testing.FakeRelaySocket
import space.einundzwanzig.meetup.core.nostr.toJsonObject
import kotlinx.serialization.json.jsonArray
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * Port of `test/relay_socket_test.dart` plus the transport rules of spec 07 §5.1 (frame cap,
 * buffering, single listener) against a real OkHttp WebSocket on MockWebServer.
 */
class RelaySocketTest {

    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val connector = OkHttpRelayConnector(client)

    @Before
    fun setUp() = server.start(InetAddress.getByName("127.0.0.1"), 0)

    @After
    fun tearDown() {
        server.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private val url get() = "ws://127.0.0.1:${server.port}/"

    /** Server side of one upgraded connection: frames received, a latch for its close. */
    private class ServerSide(val onText: (WebSocket, String) -> Unit = { _, _ -> }) : WebSocketListener() {
        val received = CopyOnWriteArrayList<String>()
        val closed = CountDownLatch(1)

        @Volatile
        var socket: WebSocket? = null
        val opened = CountDownLatch(1)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            opened.countDown()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received += text
            onText(webSocket, text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            closed.countDown()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closed.countDown()
        }
    }

    private fun upgrade(side: ServerSide) = server.enqueue(MockResponse.Builder().webSocketUpgrade(side).build())

    @Test
    fun throwingOnCloseHandlerDoesNotEscapeClose() {
        val socket = FakeRelaySocket()
        val after = CopyOnWriteArrayList<String>()
        socket.invokeOnClose { throw IllegalStateException("Handler kaputt") }
        socket.invokeOnClose { after += "zweiter" }
        socket.close() // must not throw
        assertTrue(socket.isClosed)
        assertEquals(listOf("zweiter"), after)
        // registered after the end: runs immediately, still contained
        socket.invokeOnClose { throw IllegalStateException("auch kaputt") }
    }

    @Test
    fun connectThrowsForAnUnreachableRelay() = runBlocking {
        // Dart: RelaySocket.connect('ws://127.0.0.1:1').timeout(5 s) must throw, not return.
        try {
            withTimeout(5.seconds) { connector.connect("ws://127.0.0.1:1") }
            fail("connect returned for an unreachable relay")
        } catch (e: RelayConnectException) {
            assertEquals("ws://127.0.0.1:1", e.url)
        } catch (_: TimeoutCancellationException) {
            // also acceptable: connect did not return
        }
    }

    @Test
    fun invalidUrlThrowsRelayConnectException() = runBlocking {
        try {
            connector.connect("wss://re lay.example")
            fail("expected failure")
        } catch (e: RelayConnectException) {
            assertTrue(e.cause is IllegalArgumentException)
        }
    }

    @Test
    fun stalledHandshakeIsAbortedByTheCallersTimeout() = runBlocking {
        val stalled = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val disconnected = CountDownLatch(1)
        thread {
            runCatching {
                stalled.accept().use { s ->
                    // read the upgrade request but never answer it
                    val buf = ByteArray(1024)
                    while (s.getInputStream().read(buf) >= 0) Unit
                }
            }
            disconnected.countDown()
        }
        try {
            withTimeout(300) { connector.connect("ws://127.0.0.1:${stalled.localPort}") }
            fail("handshake never completes")
        } catch (_: TimeoutCancellationException) {
        }
        assertTrue("server sees the aborted handshake", disconnected.await(2, TimeUnit.SECONDS))
        stalled.close()
    }

    @Test
    fun framesArriveInOrderIncludingThoseBeforeListen() = runBlocking {
        val side = ServerSide { ws, text ->
            if (text == "go") {
                ws.send("eins")
                ws.send("x".repeat(RelaySocket.MAX_MESSAGE_CHARS + 1)) // dropped (M4)
                ws.send("x".repeat(RelaySocket.MAX_MESSAGE_CHARS)) // exactly at the limit: kept
                ws.send("zwei")
                ws.close(1000, "bye")
            }
        }
        upgrade(side)
        val socket = connector.connect(url)
        assertTrue(socket.send("go"))
        Thread.sleep(300) // frames arrive before anybody listens
        val frames = withTimeout(5.seconds) { socket.frames.toList() }
        assertEquals(listOf("eins", RelaySocket.MAX_MESSAGE_CHARS, "zwei"), frames.map { if (it.length > 10) it.length else it })
        assertTrue(socket.isClosed)
        assertNull(socket.awaitClosed())
        assertFalse(socket.send("zu spät"))
    }

    @Test
    fun localCloseEndsTheStreamAndTheServerSeesIt() = runBlocking {
        val side = ServerSide()
        upgrade(side)
        val socket = connector.connect(url)
        val done = CountDownLatch(1)
        var doneError: Throwable? = IllegalStateException("not called")
        socket.listen(onFrame = {}, onDone = { doneError = it; done.countDown() })
        socket.close()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertNull(doneError)
        assertTrue("server sees the close", side.closed.await(2, TimeUnit.SECONDS))
        socket.close() // idempotent
    }

    @Test
    fun endToEndQueryOverOkHttp() = runBlocking {
        val key = KeyPair.generate()
        val event = EventIds.sign(key, 1_700_000_000, 1, emptyList(), "über OkHttp")
        val side = ServerSide { ws, text ->
            val msg = DartJson.decode(text).jsonArray
            val sub = msg[1].dartStringOrNull()
            ws.send(DartJson.encode(listOf("EVENT", sub, event.toMap())))
            ws.send(DartJson.encode(listOf("EOSE", sub)))
        }
        upgrade(side)
        val relayClient = RelayClient(connector)
        val result = relayClient.query(url, NostrFilter(kinds = listOf(1), authors = listOf(key.publicKeyHex)))
        assertEquals(RelayQueryStatus.EOSE, result.status)
        assertEquals(listOf(event.toJsonObject()), result.events)
        assertTrue("socket closed after the query", side.closed.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun probeSucceedsOnlyWithAHandshake() = runBlocking {
        upgrade(ServerSide())
        val config = RelayConfig(space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore(), connector)
        assertTrue(config.probeRelay(url))
        server.enqueue(MockResponse.Builder().code(200).body("kein websocket").build())
        assertFalse(config.probeRelay(url))
        assertFalse(config.probeRelay("ws://127.0.0.1:1", timeout = 2.seconds))
    }

    // ---- BufferedRelaySocket rules (no network) ----------------------------------------

    @Test
    fun singleListenerOnDoneOnceAndReentrantOrder() {
        val events = mutableListOf<String>()
        lateinit var socket: FakeRelaySocket
        socket = FakeRelaySocket("wss://fake") { frame, s -> s.pushFrame("antwort auf $frame") }
        socket.pushFrame("a")
        socket.pushFrame("b")
        socket.listen(
            onFrame = { f ->
                events += f
                if (f == "a") socket.send("a") // answer arrives synchronously, must come after "b"
            },
            onDone = { events += "done:$it" },
        )
        assertEquals(listOf("a", "b", "antwort auf a"), events)
        try {
            socket.listen({}, {})
            fail("second listener must be rejected")
        } catch (_: IllegalStateException) {
        }
        socket.finish()
        socket.finish(RuntimeException("zweimal"))
        socket.pushFrame("nach dem Ende")
        assertEquals(listOf("a", "b", "antwort auf a", "done:null"), events)
    }

    @Test
    fun listenerAfterEndStillGetsBufferedFramesThenDone() {
        val socket = FakeRelaySocket()
        socket.pushFrame("x")
        socket.finish(java.io.IOException("reset"))
        val events = mutableListOf<String>()
        socket.listen({ events += it }, { events += "done:${it?.message}" })
        assertEquals(listOf("x", "done:reset"), events)
        var closedWith: Throwable? = null
        socket.invokeOnClose { closedWith = it }
        assertEquals("reset", closedWith?.message)
    }
}
