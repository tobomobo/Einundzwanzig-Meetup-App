package space.einundzwanzig.meetup.core.network

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.MalformedURLException
import java.net.UnknownServiceException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HttpCallsTest {

    private val server = MockWebServer()
    private val client = buildAppOkHttpClient("Einundzwanzig-Meetup-App/9.9.9 (Android)")

    @Before
    fun setUp() {
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    /** A loopback URL (cleartext is allowed for 127.0.0.1 by the app policy). */
    private fun url(path: String) = "http://127.0.0.1:${server.port}$path"

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    @Test
    fun getReturnsStatusBodyHeadersAndFinalUrl() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).addHeader("X-Test", "1").body("{\"names\":{}}").build())

        val result = client.getCapped(url("/.well-known/nostr.json?name=bob"), maxBytes = 65_536, timeout = 5.seconds)

        val response = result as HttpResult.Response
        assertEquals(200, response.status)
        assertTrue(response.isSuccessful)
        assertEquals("{\"names\":{}}", response.text())
        assertEquals("1", response.header("x-test"))
        assertEquals(url("/.well-known/nostr.json?name=bob"), response.url)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun sendsDefaultUserAgentAndCustomHeaders() = runBlocking {
        server.enqueue(ok("x"))
        client.getCapped(url("/"), mapOf("Accept" to "application/json"), maxBytes = 10, timeout = 5.seconds)
        val request = server.takeRequest()
        assertEquals("Einundzwanzig-Meetup-App/9.9.9 (Android)", request.headers["User-Agent"])
        assertEquals("application/json", request.headers["Accept"])
    }

    @Test
    fun requestUserAgentWins() = runBlocking {
        // Spec 13: mempool needs this exact UA to pass the Cloudflare WAF.
        server.enqueue(ok("850000"))
        client.getCapped(
            url("/api/blocks/tip/height"),
            mapOf("User-Agent" to "21Meetup/1.3 (Einundzwanzig Meetup App)"),
            maxBytes = 64,
            timeout = 5.seconds,
        )
        assertEquals("21Meetup/1.3 (Einundzwanzig Meetup App)", server.takeRequest().headers["User-Agent"])
    }

    @Test
    fun nonSuccessStatusIsAResponseWithBody() = runBlocking {
        server.enqueue(MockResponse.Builder().code(429).body("rate limited").build())
        val response = client.getCapped(url("/"), maxBytes = 1024, timeout = 5.seconds) as HttpResult.Response
        assertEquals(429, response.status)
        assertFalse(response.isSuccessful)
        assertEquals("rate limited", response.text())
        assertNull(HttpResult.Failure(java.io.IOException()).responseWithStatus(200))
        assertNull(response.responseWithStatus(200))
        assertEquals(response, response.responseWithStatus(429))
    }

    @Test
    fun bodyExactlyAtTheCapIsAccepted() = runBlocking {
        server.enqueue(ok("a".repeat(64)))
        val response = client.getCapped(url("/"), maxBytes = 64, timeout = 5.seconds) as HttpResult.Response
        assertEquals(64, response.body.size)
    }

    @Test
    fun declaredContentLengthAboveTheCapIsRejected() = runBlocking {
        server.enqueue(ok("a".repeat(65_537)))
        val result = client.getCapped(url("/"), maxBytes = 65_536, timeout = 5.seconds)
        assertEquals(HttpResult.TooLarge(200, 65_536), result)
    }

    @Test
    fun streamedBodyAboveTheCapIsRejected() = runBlocking {
        // Chunked: no Content-Length, the cap must be enforced while reading.
        server.enqueue(MockResponse.Builder().code(200).chunkedBody("b".repeat(100_000), 1024).build())
        val result = client.getCapped(url("/"), maxBytes = 65_536, timeout = 5.seconds)
        assertEquals(HttpResult.TooLarge(200, 65_536), result)
    }

    @Test
    fun capAppliesToDecompressedBytes() = runBlocking {
        val raw = ByteArrayOutputStream()
        GZIPOutputStream(raw).use { it.write(ByteArray(1_000_000)) }
        server.enqueue(
            MockResponse.Builder().code(200)
                .addHeader("Content-Encoding", "gzip")
                .body(Buffer().write(raw.toByteArray()))
                .build(),
        )
        val result = client.getCapped(url("/"), maxBytes = 65_536, timeout = 5.seconds)
        assertEquals(HttpResult.TooLarge(200, 65_536), result)
    }

    @Test
    fun utf8IsDecodedRegardlessOfCharsetAndMalformedBytesAreReplaced() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json; charset=iso-8859-1")
                .body(Buffer().write("Grüße".encodeToByteArray() + byteArrayOf(0xC3.toByte())))
                .build(),
        )
        val response = client.getCapped(url("/"), maxBytes = 1024, timeout = 5.seconds) as HttpResult.Response
        assertEquals("Grüße�", response.text())
    }

    @Test
    fun callTimeoutCoversHeadersAndReportsTimeout() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("late").headersDelay(3, TimeUnit.SECONDS).build())
        val started = System.nanoTime()
        val result = client.getCapped(url("/"), maxBytes = 1024, timeout = 300.milliseconds)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(result is HttpResult.Failure)
        assertTrue((result as HttpResult.Failure).isTimeout)
        assertTrue("took $elapsedMs ms", elapsedMs < 2_500)
    }

    @Test
    fun callTimeoutCoversTheBody() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("slow body").bodyDelay(3, TimeUnit.SECONDS).build())
        val result = client.getCapped(url("/"), maxBytes = 1024, timeout = 300.milliseconds)
        assertTrue((result as HttpResult.Failure).isTimeout)
    }

    @Test
    fun timeoutCoversTimeQueuedInASaturatedDispatcher() = runBlocking {
        // A call whose callback never returns holds its dispatcher slot, as an open WebSocket does.
        val saturated = client.newBuilder().dispatcher(Dispatcher().apply { maxRequests = 1 }).build()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.enqueue(ok("blocker"))
        saturated.newCall(Request.Builder().url(url("/blocker")).build()).enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) = entered.countDown()

                override fun onResponse(call: Call, response: Response) {
                    entered.countDown()
                    response.use { release.await(10, TimeUnit.SECONDS) }
                }
            },
        )
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(1, saturated.dispatcher.runningCallsCount())

            val started = System.nanoTime()
            val result = saturated.getCapped(url("/queued"), maxBytes = 64, timeout = 300.milliseconds)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertTrue(result.toString(), (result as HttpResult.Failure).isTimeout)
            assertTrue("took $elapsedMs ms", elapsedMs < 2_500)
        } finally {
            release.countDown()
            saturated.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun webSocketClientHasItsOwnUnboundedDispatcherAndSharesThePool() {
        val ws = buildWebSocketClient(client)
        assertNotSame(client.dispatcher, ws.dispatcher)
        assertEquals(Int.MAX_VALUE, ws.dispatcher.maxRequests)
        assertEquals(Int.MAX_VALUE, ws.dispatcher.maxRequestsPerHost)
        assertSame(client.connectionPool, ws.connectionPool)
        assertEquals(client.interceptors, ws.interceptors)
        assertEquals(client.networkInterceptors, ws.networkInterceptors)
        ws.dispatcher.executorService.shutdown()
    }

    @Test
    fun openWebSocketDoesNotOccupyTheHttpDispatcher() = runBlocking {
        val ws = buildWebSocketClient(client)
        val opened = CountDownLatch(1)
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {}).build())
        val socket = ws.newWebSocket(
            Request.Builder().url(url("/relay")).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = opened.countDown()
            },
        )
        try {
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            assertEquals("the socket's reader loop holds a slot of its own dispatcher", 1, ws.dispatcher.runningCallsCount())
            assertEquals(0, client.dispatcher.runningCallsCount())
            server.enqueue(ok("free"))
            val response = client.getCapped(url("/"), maxBytes = 64, timeout = 5.seconds) as HttpResult.Response
            assertEquals("free", response.text())
        } finally {
            socket.cancel()
            ws.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun connectionRefusedIsAFailureButNotATimeout() = runBlocking {
        val port = server.port
        server.close()
        val result = client.getCapped("http://127.0.0.1:$port/", maxBytes = 1024, timeout = 5.seconds)
        assertTrue(result is HttpResult.Failure)
        assertFalse((result as HttpResult.Failure).isTimeout)
    }

    @Test
    fun invalidUrlsAreFailures() = runBlocking {
        for (bad in listOf("not a url", "wss://relay.example", "ftp://x.example/", "")) {
            val result = client.getCapped(bad, maxBytes = 1, timeout = 1.seconds)
            assertTrue(bad, (result as HttpResult.Failure).cause is MalformedURLException)
        }
    }

    @Test
    fun invalidHeaderValueIsAFailure() = runBlocking {
        val result = client.getCapped(url("/"), mapOf("X-Bad" to "a\nb"), maxBytes = 1, timeout = 1.seconds)
        assertTrue(result is HttpResult.Failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun postJsonSendsUtf8JsonBody() = runBlocking {
        server.enqueue(MockResponse.Builder().code(201).body("{\"token\":\"t\"}").build())
        val body = "{\"k1\":\"abc\",\"device_name\":\"Pixel ✓\"}"
        val response = client.postJson(
            url("/api/mobile/token"),
            body,
            mapOf("Accept" to "application/json"),
            maxBytes = 65_536,
            timeout = 5.seconds,
        ) as HttpResult.Response
        assertEquals(201, response.status)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("application/json; charset=utf-8", request.headers["Content-Type"])
        assertEquals("application/json", request.headers["Accept"])
        assertEquals(body, request.body?.utf8())
    }

    @Test
    fun callerContentTypeGetsUtf8CharsetLikeDartHttp() = runBlocking {
        server.enqueue(ok("{}"))
        client.postJson(url("/"), "{}", mapOf("Content-Type" to "application/json"), maxBytes = 64, timeout = 5.seconds)
        assertEquals("application/json; charset=utf-8", server.takeRequest().headers["Content-Type"])
    }

    @Test
    fun sendJsonSupportsPatchAndDeleteWithoutBody() = runBlocking {
        server.enqueue(ok("{}"))
        server.enqueue(MockResponse.Builder().code(204).build())
        server.enqueue(ok("{}"))

        client.sendJson("patch", url("/api/meetup-events/1"), null, maxBytes = 64, timeout = 5.seconds)
        val delete = client.sendJson("DELETE", url("/api/meetup-events/1"), null, maxBytes = 64, timeout = 5.seconds)
        client.sendJson("PUT", url("/x"), "{\"status\":\"attending\"}", maxBytes = 64, timeout = 5.seconds)

        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("0", patch.headers["Content-Length"])
        assertEquals(204, (delete as HttpResult.Response).status)
        assertEquals("DELETE", server.takeRequest().method)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("{\"status\":\"attending\"}", put.body?.utf8())
    }

    @Test
    fun cleartextToPublicHostIsBlockedBeforeConnecting() = runBlocking {
        val result = client.getCapped("http://mempool.space/api/blocks/tip/height", maxBytes = 64, timeout = 5.seconds)
        assertTrue((result as HttpResult.Failure).cause is UnknownServiceException)
    }

    @Test
    fun cleartextRedirectHopIsBlockedBeforeAnyRequestByte() = runBlocking {
        // 127.0.0.2 is loopback but not on the cleartext allow-list.
        val other = MockWebServer()
        other.start(InetAddress.getByName("0.0.0.0"), 0)
        try {
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "http://127.0.0.2:${other.port}/").build())
            val result = client.getCapped(url("/"), maxBytes = 64, timeout = 5.seconds)
            assertTrue(result.toString(), (result as HttpResult.Failure).cause is UnknownServiceException)
            assertEquals(0, other.requestCount)
        } finally {
            other.close()
        }
    }

    @Test
    fun cancellingTheCoroutineCancelsTheCall() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("never").headersDelay(10, TimeUnit.SECONDS).build())
        val call = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            client.getCapped(url("/"), maxBytes = 64, timeout = 30.seconds)
        }
        server.takeRequest()
        val started = System.nanoTime()
        call.cancel()
        withTimeout(2_000) { runCatching { call.await() } }
        assertTrue(call.isCancelled)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        assertEquals("the OkHttp call was cancelled too", 0, waitForIdle())
    }

    private fun waitForIdle(): Int {
        repeat(50) {
            if (client.dispatcher.runningCallsCount() == 0) return 0
            Thread.sleep(20)
        }
        return client.dispatcher.runningCallsCount()
    }
}
