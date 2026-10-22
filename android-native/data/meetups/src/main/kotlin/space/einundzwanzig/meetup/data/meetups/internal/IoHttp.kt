package space.einundzwanzig.meetup.data.meetups.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import space.einundzwanzig.meetup.core.network.HttpResult
import space.einundzwanzig.meetup.core.network.getCapped
import space.einundzwanzig.meetup.core.network.sendJson
import kotlin.time.Duration

/*
 * The `:core:network` helpers enforce their whole-call timeout with `withTimeoutOrNull`, which uses
 * the delay source of the calling context. Running them on the injected IO dispatcher keeps the
 * timeout on the wall clock (also under a test scheduler) and the call off the caller's thread.
 */

/** [getCapped] on [io]. */
internal suspend fun OkHttpClient.getCappedOn(
    io: CoroutineDispatcher,
    url: String,
    headers: Map<String, String> = emptyMap(),
    maxBytes: Long,
    timeout: Duration,
): HttpResult = withContext(io) { getCapped(url, headers, maxBytes, timeout) }

/** [sendJson] on [io]. */
internal suspend fun OkHttpClient.sendJsonOn(
    io: CoroutineDispatcher,
    method: String,
    url: String,
    json: String?,
    headers: Map<String, String> = emptyMap(),
    maxBytes: Long,
    timeout: Duration,
): HttpResult = withContext(io) { sendJson(method, url, json, headers, maxBytes, timeout) }
