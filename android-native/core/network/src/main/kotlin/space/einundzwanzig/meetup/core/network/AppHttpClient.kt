package space.einundzwanzig.meetup.core.network

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Client-wide limits of the shared [OkHttpClient]. */
object NetworkDefaults {
    /**
     * Connect/read/write timeout of the shared client. It must be at least the largest per-call
     * budget in the specs (Tor/onion mempool hosts and Blossom uploads: 45 s), otherwise OkHttp
     * would cut slow Tor circuits earlier than the Flutter app did (spec 13 §5.4). The real
     * per-request budget is the call timeout passed to the helpers.
     */
    val SOCKET_TIMEOUT: Duration = 45.seconds

    /** Body cap used by the specs for small JSON documents (NIP-05, LNURL, zap metadata): 64 KiB. */
    const val SMALL_BODY_MAX_BYTES: Long = 64L * 1024

    /**
     * Parallel requests per host. ≥ 6 so the six parallel mempool dashboard requests are not
     * queued behind OkHttp's default of 5 (spec 13 §5.6).
     */
    const val MAX_REQUESTS_PER_HOST: Int = 16
}

/**
 * Builds the shared client.
 *
 * - Timeouts: connect/read/write = [NetworkDefaults.SOCKET_TIMEOUT]; **no client-wide call
 *   timeout** (it would also cut streaming media and long downloads). Every helper sets a
 *   per-call timeout; WebSocket users bound their handshake themselves.
 * - **Not for WebSockets**: OkHttp runs a WebSocket's reader loop inside the dispatcher callback,
 *   so every open socket holds one of this client's dispatcher slots for its whole lifetime and
 *   relay sockets would starve HTTP calls. Use [buildWebSocketClient] (Hilt: `@WebSocketClient`).
 * - Cleartext only where [CleartextPolicy] allows it (`*.onion`, `*.local`, loopback).
 * - Default `User-Agent: Einundzwanzig-Meetup-App/<versionName> (Android)` unless the request
 *   sets one. Interceptors also apply to WebSocket handshakes.
 * - No cache, no cookies, redirects followed (Dart `http` followed up to 5), no ping interval
 *   (relay sockets do their own liveness handling).
 */
internal fun buildAppOkHttpClient(userAgent: String): OkHttpClient {
    val guard = CleartextGuard()
    return OkHttpClient.Builder()
        .connectTimeout(NetworkDefaults.SOCKET_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .readTimeout(NetworkDefaults.SOCKET_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .writeTimeout(NetworkDefaults.SOCKET_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = NetworkDefaults.MAX_REQUESTS_PER_HOST })
        .addInterceptor(guard)
        .addInterceptor(UserAgentInterceptor(userAgent))
        .addNetworkInterceptor(guard)
        .build()
}

/**
 * Derives the WebSocket client from the shared [base] client.
 *
 * OkHttp (`RealWebSocket.connect`) runs the reader loop of an open WebSocket inside the
 * dispatcher callback of its handshake call, so a socket occupies a dispatcher slot until it
 * closes. The specs keep one socket per relay per operation open in parallel plus long-lived
 * chat/news subscriptions; on the shared dispatcher (64 slots, [NetworkDefaults.MAX_REQUESTS_PER_HOST]
 * per host) they would queue every later HTTP call. This client therefore has its **own,
 * unbounded** dispatcher, while connection pool, timeouts and interceptors (User-Agent,
 * cleartext policy) are shared with [base].
 */
internal fun buildWebSocketClient(base: OkHttpClient): OkHttpClient =
    base.newBuilder()
        .dispatcher(
            Dispatcher().apply {
                maxRequests = Int.MAX_VALUE
                maxRequestsPerHost = Int.MAX_VALUE
            },
        )
        .build()
