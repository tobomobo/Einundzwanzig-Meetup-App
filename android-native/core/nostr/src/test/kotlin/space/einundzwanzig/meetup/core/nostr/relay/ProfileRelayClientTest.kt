package space.einundzwanzig.meetup.core.nostr.relay

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.jsonArrayOf
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Port of `test/profile_relay_client_test.dart` (MockWebServer instead of a dart:io server). */
class ProfileRelayClientTest {

    private val server = MockWebServer()
    private val okHttp = OkHttpClient()

    /** Responds to the client's REQ (decoded). */
    private var respond: (WebSocket, JsonArray) -> Unit = { _, _ -> }
    private val closed = CountDownLatch(1)

    @Before
    fun setUp() {
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) =
                        respond(webSocket, DartJson.decode(text) as JsonArray)

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = closed.countDown()

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = closed.countDown()

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed.countDown()
                },
            ).build(),
        )
    }

    @After
    fun tearDown() {
        server.close()
        okHttp.dispatcher.executorService.shutdown()
        okHttp.connectionPool.evictAll()
    }

    private fun query(timeout: Duration = 2.seconds) = runBlocking {
        ProfileRelayClient(okHttp, timeout).query("ws://127.0.0.1:${server.port}", "test-author")
    }

    private fun awaitServerClosed() = assertTrue("server sees the socket closed", closed.await(2, TimeUnit.SECONDS))

    @Test
    fun reqContainsAuthorAndMetadataKindsIgnoresOtherSubscriptionsClosesOnEose() {
        var request: JsonArray? = null
        respond = { socket, req ->
            request = req
            socket.send("invalid JSON")
            socket.send(DartJson.encode(listOf("EOSE", "other")))
            socket.send(DartJson.encode(listOf("EVENT", "other", mapOf("ignored" to true))))
            socket.send(DartJson.encode(listOf("EVENT", "profile", mapOf("kind" to 0))))
            socket.send(DartJson.encode(listOf("EOSE", "profile")))
        }
        assertEquals(listOf(jsonObjectOf("kind" to 0)), query())
        assertEquals(
            jsonArrayOf("REQ", "profile", jsonObjectOf("kinds" to listOf(0, 10002), "authors" to listOf("test-author"), "limit" to 2)),
            request,
        )
        assertEquals(
            """["REQ","profile",{"kinds":[0,10002],"authors":["test-author"],"limit":2}]""",
            DartJson.encode(ProfileRelayClient.reqFrame("test-author")),
        )
        awaitServerClosed()
    }

    @Test
    fun partialResultsSurviveDeadlineWithoutEoseAndSocketCloses() {
        respond = { socket, _ -> socket.send(DartJson.encode(listOf("EVENT", "profile", mapOf("kind" to 0)))) }
        assertEquals(listOf(jsonObjectOf("kind" to 0)), query(150.milliseconds))
        awaitServerClosed()
    }

    @Test
    fun boundsResponsesFromARelayThatIgnoresTheLimit() {
        respond = { socket, _ ->
            repeat(20) { socket.send(DartJson.encode(listOf("EVENT", "profile", mapOf("number" to it)))) }
        }
        assertEquals(ProfileRelayClient.MAX_EVENTS, query().size)
        awaitServerClosed()
    }

    @Test
    fun oversizedFramesTerminateQuerySafely() {
        respond = { socket, _ -> socket.send("x".repeat(ProfileRelayClient.MAX_MESSAGE_LENGTH + 1)) }
        assertEquals(emptyList<Any>(), query())
        awaitServerClosed()
    }

    @Test
    fun noiseFramesAreBoundedEvenWithoutMetadata() {
        respond = { socket, _ -> repeat(ProfileRelayClient.MAX_MESSAGES + 1) { socket.send("noise") } }
        assertEquals(emptyList<Any>(), query())
        awaitServerClosed()
    }

    @Test
    fun connectionRefusalProducesEmptyResultsWithoutUnhandledErrors() = runBlocking {
        assertEquals(emptyList<Any>(), ProfileRelayClient(okHttp, 100.milliseconds).query("ws://127.0.0.1:1", "test-author"))
    }

    @Test
    fun deadlineIncludesAStalledWebSocketHandshake() = runBlocking {
        val stalled = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val disconnected = CountDownLatch(1)
        thread {
            runCatching {
                stalled.accept().use { s ->
                    val buf = ByteArray(1024)
                    while (s.getInputStream().read(buf) >= 0) Unit // read the upgrade request, never answer
                }
            }
            disconnected.countDown()
        }
        try {
            val result = ProfileRelayClient(okHttp, 150.milliseconds).query("ws://127.0.0.1:${stalled.localPort}", "test-author")
            assertEquals(emptyList<Any>(), result)
            assertTrue(disconnected.await(2, TimeUnit.SECONDS))
        } finally {
            stalled.close()
        }
    }

    @Test
    fun invalidUrlYieldsEmpty() = runBlocking {
        assertEquals(emptyList<Any>(), ProfileRelayClient(okHttp).query("not a url", "x"))
        assertEquals(6.seconds, ProfileRelayClient.DEFAULT_TIMEOUT)
    }
}
