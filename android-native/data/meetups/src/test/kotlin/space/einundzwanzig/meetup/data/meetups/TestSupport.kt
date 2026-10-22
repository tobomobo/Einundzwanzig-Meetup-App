package space.einundzwanzig.meetup.data.meetups

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.log.DiagnosticLogger
import space.einundzwanzig.meetup.core.crypto.EventIds
import space.einundzwanzig.meetup.core.crypto.KeyPair
import space.einundzwanzig.meetup.data.identity.SigningService
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Settable wall clock. */
class FakeTime(var now: Long = 1_800_000_000_000L) : TimeSource {
    override fun nowMillis(): Long = now
}

/** A test logger that never touches disk. */
fun testLog() = DiagnosticLogger(timeSource = FakeTime())

/** A request as the server saw it plus the host the app addressed. */
class Seen(val host: String, val request: RecordedRequest) {
    val path: String get() = request.url.encodedPath
    val query: String? get() = request.url.encodedQuery
    val method: String get() = request.method
    val body: String get() = request.body?.utf8() ?: ""
    fun header(name: String): String? = request.headers[name]
}

/**
 * A MockWebServer answering for every host: the test client rewrites each request to it and keeps
 * the original host in `X-Orig-Host`, so production URL constants are exercised unchanged.
 */
class FakeServer : AutoCloseable {
    val server = MockWebServer()
    val seen = CopyOnWriteArrayList<Seen>()

    /** Responder: (method, path incl. query) → response; default 404. */
    @Volatile
    var respond: (method: String, pathAndQuery: String) -> MockResponse = { _, _ -> status(404, "") }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val s = Seen(request.headers["X-Orig-Host"] ?: "?", request)
                seen += s
                val pq = s.path + (s.query?.let { "?$it" } ?: "")
                return respond(request.method, pq)
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    val client: OkHttpClient by lazy {
        val base = "http://127.0.0.1:${server.port}".toHttpUrl()
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val orig = chain.request().url
                val target = base.newBuilder().encodedPath(orig.encodedPath).encodedQuery(orig.encodedQuery).build()
                chain.proceed(chain.request().newBuilder().url(target).header("X-Orig-Host", orig.host).build())
            }
            .build()
    }

    override fun close() = server.close()
}

fun status(code: Int, body: String): MockResponse = MockResponse.Builder().code(code).body(body).build()

fun json(body: String, code: Int = 200): MockResponse =
    MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

/**
 * A [SigningService] built with a dynamic proxy: only `npub()`, `pubkeyHex()` and `signEvent()` are
 * implemented (suspend functions return their value directly, without suspending).
 */
class FakeSigner(var keyPair: KeyPair? = KeyPair.generate(), var npubOverride: String? = null) {
    var signCount = 0
    var failSigning: Exception? = null
    var failNpub: Exception? = null

    val service: SigningService = Proxy.newProxyInstance(
        SigningService::class.java.classLoader,
        arrayOf(SigningService::class.java),
    ) { _, method, args ->
        when (method.name) {
            "npub" -> {
                failNpub?.let { throw it }
                npubOverride ?: keyPair?.npub
            }
            "pubkeyHex" -> keyPair?.publicKeyHex
            "signEvent" -> {
                failSigning?.let { throw it }
                signCount++
                @Suppress("UNCHECKED_CAST")
                EventIds.sign(
                    keyPair!!,
                    createdAt = 1_800_000_000L,
                    kind = args[0] as Int,
                    tags = args[1] as List<List<String>>,
                    content = args[2] as String,
                )
            }
            "toString" -> "FakeSigner"
            "hashCode" -> 0
            "equals" -> false
            else -> throw UnsupportedOperationException(method.name)
        }
    } as SigningService
}

/** Real registry without calendar/network loading, used by directory tests. */
internal fun testLogos() = space.einundzwanzig.meetup.data.meetups.internal.PortalLogoRegistryImpl(
    java.util.Optional.empty(), testLog(),
)
