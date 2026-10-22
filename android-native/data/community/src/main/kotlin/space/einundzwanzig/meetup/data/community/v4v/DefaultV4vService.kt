package space.einundzwanzig.meetup.data.community.v4v

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import space.einundzwanzig.meetup.core.common.dart.DartUri
import space.einundzwanzig.meetup.core.common.dart.dartToInt
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.DartNumber
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.describeError
import space.einundzwanzig.meetup.core.network.HttpResult
import space.einundzwanzig.meetup.core.network.NetworkDefaults
import space.einundzwanzig.meetup.core.network.getCapped
import space.einundzwanzig.meetup.data.community.internal.dartToString
import space.einundzwanzig.meetup.data.community.internal.isPresent
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** [V4vService] over the shared [OkHttpClient]; byte-for-byte the request sequence of `V4VService`. */
@Singleton
internal class DefaultV4vService(
    private val client: OkHttpClient,
    private val log: DiagnosticLog,
    /** `https://<domain>/.well-known/lnurlp/<user>`; replaceable in tests (loopback server). */
    private val metadataUrl: (user: String, domain: String) -> String,
    private val timeout: Duration = TIMEOUT,
) : V4vService {

    @Inject
    constructor(client: OkHttpClient, log: DiagnosticLog) : this(client, log, ::lnurlpMetadataUrl)

    override suspend fun createInvoice(amountSats: Long, address: String, comment: String?): V4vResult {
        if (amountSats <= 0) return V4vResult.Failure(V4vError.INVALID_AMOUNT)
        val parts = address.split('@')
        if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return V4vResult.Failure(V4vError.INVALID_ADDRESS)
        }
        val (user, domain) = parts
        return try {
            requestInvoice(user, domain, amountSats, comment)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Dart: every exception in step 1/2 (network, timeout, jsonDecode, `as` casts) → unreachable.
            log.debug(TAG, "Fehler: ${describeError(e)}")
            V4vResult.Failure(V4vError.UNREACHABLE)
        }
    }

    private suspend fun requestInvoice(user: String, domain: String, amountSats: Long, comment: String?): V4vResult {
        // Step 1: LNURL-pay metadata
        val metaResp = client.get(metadataUrl(user, domain))
        if (metaResp.status != 200) {
            log.debug(TAG, "meta HTTP ${metaResp.status}")
            return V4vResult.Failure(V4vError.UNREACHABLE)
        }
        val meta = metaResp.jsonObject()
        val tag = meta["tag"]
        val isPayRequest = tag is JsonPrimitive && tag.isString && tag.content == "payRequest"
        if (!isPayRequest || !meta["callback"].isPresent) return V4vResult.Failure(V4vError.NOT_LNURLP)

        val callback = meta["callback"].dartToString()
        val minSendable = meta["minSendable"].numAsIntOrNull() ?: DEFAULT_MIN_SENDABLE_MSAT
        val maxSendable = meta["maxSendable"].numAsIntOrNull() ?: DEFAULT_MAX_SENDABLE_MSAT
        val commentAllowed = meta["commentAllowed"].numAsIntOrNull() ?: 0L

        val amountMsat = amountSats * 1000
        if (amountMsat < minSendable) return V4vResult.Failure(V4vError.BELOW_MIN)
        if (amountMsat > maxSendable) return V4vResult.Failure(V4vError.ABOVE_MAX)

        // Step 2: callback with the amount → invoice
        val callbackUrl = buildLnurlCallbackUrl(callback, amountMsat, comment, commentAllowed)
        val invResp = client.get(callbackUrl)
        if (invResp.status != 200) {
            log.debug(TAG, "callback HTTP ${invResp.status}")
            return V4vResult.Failure(V4vError.INVOICE_FAILED)
        }
        val inv = invResp.jsonObject()
        val status = inv["status"]
        val statusText = if (status.isPresent) status.dartToString() else ""
        if (statusText.uppercase() == "ERROR") {
            val reason = inv["reason"]
            return V4vResult.Failure(if (reason.isPresent) reason.dartToString() else V4vError.INVOICE_FAILED)
        }
        val pr = inv["pr"]
        if (!pr.isPresent) return V4vResult.Failure(V4vError.INVOICE_FAILED)
        val invoice = pr.dartToString()
        if (invoice.isEmpty()) return V4vResult.Failure(V4vError.INVOICE_FAILED)
        return V4vResult.Success(invoice)
    }

    /** GET with the 15 s budget; transport failures throw (→ `unreachable`, like the Dart catch-all). */
    private suspend fun OkHttpClient.get(url: String): HttpResult.Response =
        when (val r = getCapped(url, maxBytes = NetworkDefaults.SMALL_BODY_MAX_BYTES, timeout = timeout)) {
            is HttpResult.Response -> r
            is HttpResult.Failure -> throw r.cause
            is HttpResult.TooLarge -> throw IllegalStateException("LNURL response over ${r.maxBytes} bytes (HTTP ${r.status})")
        }

    /** `jsonDecode(body) as Map<String, dynamic>`. */
    private fun HttpResult.Response.jsonObject(): JsonObject =
        DartJson.decode(text()) as? JsonObject ?: throw IllegalStateException("LNURL response is not a JSON object")

    internal companion object {
        const val TAG = "V4VService"
        val TIMEOUT: Duration = 15.seconds
        const val DEFAULT_MIN_SENDABLE_MSAT = 1_000L
        const val DEFAULT_MAX_SENDABLE_MSAT = 100_000_000_000L

        fun lnurlpMetadataUrl(user: String, domain: String): String = "https://$domain/.well-known/lnurlp/$user"
    }
}

/**
 * Dart `(json[key] as num?)?.toInt()`: `null` for a missing/`null` value, the truncated value for
 * a number, and a thrown cast error for anything else (which the caller turns into `unreachable`).
 */
private fun JsonElement?.numAsIntOrNull(): Long? {
    if (this == null || this is JsonNull) return null
    val number = (this as? JsonPrimitive)?.let { runCatching { DartJson.numberOf(it) }.getOrNull() }
        ?: throw IllegalStateException("type '${this.dartToString()}' is not a subtype of type 'num?'")
    return when (number) {
        is DartNumber.Int -> number.value
        is DartNumber.Double -> number.value.dartToInt()
    }
}

/**
 * Builds an LNURL-pay callback URL byte-exactly like the Dart code (spec 11 §5 / N3 / N5), shared
 * by V4V (`V4VService`) and the news zap (`NewsZapService`, step 4):
 *
 * `<callback>{? or &}amount=<msat>`, then
 * - `&nostr=<Uri.encodeQueryComponent(nostr)>` when [nostr] (the signed kind-9734 zap request
 *   JSON) is non-null; no comment is sent then (zap rule);
 * - otherwise `&comment=<Uri.encodeQueryComponent(c)>` when [comment] is non-empty and
 *   [commentAllowed] > 0, where `c` is [comment] cut to [commentAllowed] UTF-16 code units.
 *
 * The separator is `&` when [callback] already contains `?`, else `?`.
 */
fun buildLnurlCallbackUrl(
    callback: String,
    amountMsat: Long,
    comment: String?,
    commentAllowed: Long,
    nostr: String? = null,
): String {
    val sep = if (callback.contains('?')) '&' else '?'
    val url = StringBuilder("$callback${sep}amount=$amountMsat")
    if (nostr != null) {
        url.append("&nostr=").append(DartUri.encodeQueryComponent(nostr))
    } else if (!comment.isNullOrEmpty() && commentAllowed > 0) {
        val c = if (comment.length > commentAllowed) comment.substring(0, commentAllowed.toInt()) else comment
        url.append("&comment=").append(DartUri.encodeQueryComponent(c))
    }
    return url.toString()
}
