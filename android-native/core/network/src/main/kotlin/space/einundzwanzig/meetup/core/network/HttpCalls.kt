package space.einundzwanzig.meetup.core.network

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.MalformedURLException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

/** Content type of [postJson]/[sendJson] bodies when the caller sets none (Dart `http` adds the charset too). */
const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"

/**
 * `GET url` with a whole-call [timeout] (connect + headers + body, like Dart's
 * `http.get(...).timeout(...)`) and a body cap of [maxBytes] (decoded bytes).
 *
 * The body is streamed and reading stops as soon as it exceeds [maxBytes] ([HttpResult.TooLarge]);
 * a larger `Content-Length` is rejected before reading. Non-2xx responses are returned as
 * [HttpResult.Response] with their (capped) body. Cancelling the coroutine cancels the call.
 */
suspend fun OkHttpClient.getCapped(
    url: String,
    headers: Map<String, String> = emptyMap(),
    maxBytes: Long,
    timeout: Duration,
): HttpResult {
    val request = try {
        buildRequest(url, headers) { get() }
    } catch (e: IOException) {
        return HttpResult.Failure(e)
    }
    return executeCapped(request, maxBytes, timeout)
}

/**
 * `POST url` with the JSON text [json] as body; see [getCapped] for timeout and cap semantics.
 * The content type is the caller's `Content-Type` header if set (a missing charset becomes
 * `utf-8`, as with Dart `http`), else [JSON_CONTENT_TYPE].
 */
suspend fun OkHttpClient.postJson(
    url: String,
    json: String,
    headers: Map<String, String> = emptyMap(),
    maxBytes: Long,
    timeout: Duration,
): HttpResult = sendJson("POST", url, json, headers, maxBytes, timeout)

/**
 * Sends [method] (`POST`, `PUT`, `PATCH`, `DELETE`, ...) with an optional JSON body. A `null`
 * [json] sends no body (an empty one for methods that require a body, like Dart `http`).
 * See [postJson] for the content type and [getCapped] for timeout and cap semantics.
 */
suspend fun OkHttpClient.sendJson(
    method: String,
    url: String,
    json: String?,
    headers: Map<String, String> = emptyMap(),
    maxBytes: Long,
    timeout: Duration,
): HttpResult {
    val upper = method.uppercase()
    val body: RequestBody? = when {
        json != null -> {
            val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
            json.toRequestBody((contentType ?: JSON_CONTENT_TYPE).toMediaTypeOrNull())
        }
        upper in METHODS_REQUIRING_BODY -> ByteArray(0).toRequestBody(null)
        else -> null
    }
    val request = try {
        buildRequest(url, headers) { method(upper, body) }
    } catch (e: IOException) {
        return HttpResult.Failure(e)
    }
    return executeCapped(request, maxBytes, timeout)
}

/**
 * Executes [request] asynchronously with a whole-call [timeout] (`Duration.ZERO` or negative =
 * none) and reads at most [maxBytes] of the body. Never throws for network problems.
 *
 * The timeout covers the time the call waits in the dispatcher queue, too (OkHttp's own call
 * timeout only starts once the call runs): a timed-out call is cancelled and reported as
 * [HttpResult.Failure] with an `InterruptedIOException` ([HttpResult.Failure.isTimeout]).
 */
suspend fun OkHttpClient.executeCapped(request: Request, maxBytes: Long, timeout: Duration): HttpResult {
    require(maxBytes >= 0) { "maxBytes must be >= 0" }
    val call = newCall(request)
    if (!timeout.isPositive()) return call.awaitCapped(maxBytes)
    call.timeout().timeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    // Cancelling the coroutine cancels the call (invokeOnCancellation), so nothing keeps running.
    return withTimeoutOrNull(timeout) { call.awaitCapped(maxBytes) }
        ?: HttpResult.Failure(InterruptedIOException("timeout"))
}

private suspend fun Call.awaitCapped(maxBytes: Long): HttpResult = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resume(HttpResult.Failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { readCapped(it, maxBytes) }
                } catch (e: IOException) {
                    HttpResult.Failure(e)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                    return
                }
                continuation.resume(result)
            }
        },
    )
}

private fun readCapped(response: Response, maxBytes: Long): HttpResult {
    val body = response.body
    val declared = body.contentLength()
    if (declared > maxBytes) return HttpResult.TooLarge(response.code, maxBytes)
    val source = body.source()
    val bytes = if (maxBytes == Long.MAX_VALUE) {
        source.readByteArray()
    } else {
        // request() buffers until maxBytes + 1 bytes are available or the stream ends.
        if (source.request(maxBytes + 1)) return HttpResult.TooLarge(response.code, maxBytes)
        source.buffer.readByteArray()
    }
    return HttpResult.Response(
        status = response.code,
        headers = response.headers,
        body = bytes,
        url = response.request.url.toString(),
    )
}

/** Builds a request; invalid URLs, header names/values or methods become an [IOException]. */
@Throws(IOException::class)
private inline fun buildRequest(
    url: String,
    headers: Map<String, String>,
    configure: Request.Builder.() -> Request.Builder,
): Request {
    val httpUrl: HttpUrl = url.toHttpUrlOrNull() ?: throw MalformedURLException("Not an http(s) URL")
    return try {
        val headerList = Headers.Builder().apply { headers.forEach { (name, value) -> add(name, value) } }.build()
        Request.Builder().url(httpUrl).headers(headerList).configure().build()
    } catch (e: IllegalArgumentException) {
        throw IOException("Invalid request: ${e.message}", e)
    }
}

private val METHODS_REQUIRING_BODY = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")
