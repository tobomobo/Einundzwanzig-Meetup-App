package space.einundzwanzig.meetup.data.mempool

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.DiagnosticLogger
import space.einundzwanzig.meetup.core.data.prefs.PrefValue
import space.einundzwanzig.meetup.core.data.testing.InMemoryKeyValueStore
import space.einundzwanzig.meetup.data.mempool.internal.BlockHeightStore
import space.einundzwanzig.meetup.data.mempool.internal.MempoolConfigImpl
import space.einundzwanzig.meetup.data.mempool.internal.MempoolServiceImpl
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Settable wall clock. */
class FakeTime(var now: Long = 1_700_000_000_000L) : TimeSource {
    override fun nowMillis(): Long = now
}

/**
 * One request as the server saw it, with the host the app originally addressed and the
 * whole-call timeout the app set on the call ([timeoutMs], `0` = none).
 */
class Seen(val origHost: String, val path: String, val request: RecordedRequest) {
    val timeoutMs: Long get() = request.headers["X-Call-Timeout-Ms"]?.toLong() ?: -1
}

/**
 * A MockWebServer that answers for **every** host: the client rewrites each request to the
 * server and keeps the original host in `X-Orig-Host`, so the real host constants
 * (mempool.space, mirrors, onion) can be exercised.
 */
class FakeMempool : AutoCloseable {
    val server = MockWebServer()
    val seen = CopyOnWriteArrayList<Seen>()

    /** Responder: (origHost, path) → response; default 404. */
    @Volatile
    var respond: (host: String, path: String) -> MockResponse = { _, _ -> status(404, "not found") }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val host = request.headers["X-Orig-Host"] ?: "?"
                val path = request.url.encodedPath.removePrefix("/api")
                seen += Seen(host, path, request)
                return respond(host, path)
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    val client: OkHttpClient by lazy {
        val base = "http://127.0.0.1:${server.port}".toHttpUrl()
        OkHttpClient.Builder()
            .dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 16 })
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val orig = chain.request().url
                val target = base.newBuilder().encodedPath(orig.encodedPath).encodedQuery(orig.encodedQuery).build()
                val timeoutMs = TimeUnit.NANOSECONDS.toMillis(chain.call().timeout().timeoutNanos())
                chain.proceed(
                    chain.request().newBuilder()
                        .url(target)
                        .header("X-Orig-Host", orig.host)
                        .header("X-Call-Timeout-Ms", timeoutMs.toString())
                        .build(),
                )
            }
            .build()
    }

    /** All (path) requests sent to [host]. */
    fun pathsFor(host: String): List<String> = seen.filter { it.origHost == host }.map { it.path }

    override fun close() {
        server.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        fun ok(body: String): MockResponse = MockResponse.Builder().code(200).body(body).build()
        fun status(code: Int, body: String): MockResponse = MockResponse.Builder().code(code).body(body).build()

        const val FEES = """{"fastestFee":12,"halfHourFee":8,"hourFee":6,"economyFee":3,"minimumFee":1}"""
        const val PRICES = """{"time":1700000000,"USD":103000,"EUR":95000,"GBP":81000,"CAD":140000,"CHF":84000,"AUD":155000,"JPY":15000000}"""
        const val HASHRATE = """{"hashrates":[],"difficulty":[],"currentHashrate":812345678901234567890,"currentDifficulty":1.0}"""
        const val DIFFICULTY = """{"progressPercent":50.0,"difficultyChange":3.71,"remainingBlocks":1234}"""
        const val LIGHTNING = """{"latest":{"total_capacity":512345678901,"node_count":15000,"channel_count":50000},"previous":{}}"""

        /** A healthy instance answering height [height]. */
        fun healthy(height: Int = 900_000): (String, String) -> MockResponse = { _, path ->
            when (path) {
                MempoolService.PATH_TIP_HEIGHT -> ok(height.toString())
                MempoolService.PATH_FEES -> ok(FEES)
                MempoolService.PATH_PRICES -> ok(PRICES)
                MempoolService.PATH_HASHRATE -> ok(HASHRATE)
                MempoolService.PATH_DIFFICULTY -> ok(DIFFICULTY)
                MempoolService.PATH_LIGHTNING -> ok(LIGHTNING)
                else -> status(404, "not found")
            }
        }
    }
}

/** Wires the real implementations against a [FakeMempool]. */
class TestEnv(
    initialPrefs: Map<String, PrefValue> = emptyMap(),
) : AutoCloseable {
    val fake = FakeMempool()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val time = FakeTime()
    val log = DiagnosticLogger(timeSource = time, scope = scope)
    val store = InMemoryKeyValueStore(initialPrefs)
    internal val config = MempoolConfigImpl(store, log, scope)
    internal val heights = BlockHeightStore(store, log)
    internal val service = MempoolServiceImpl(fake.client, config, heights, time, log, scope, Dispatchers.IO)

    fun logMessages(): List<String> = log.entries.value.map { it.message }

    override fun close() {
        scope.cancel()
        fake.close()
    }
}
