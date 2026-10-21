package space.einundzwanzig.meetup.data.news.internal

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import space.einundzwanzig.meetup.core.common.dart.dartToInt
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.DartNumber
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.describeError
import space.einundzwanzig.meetup.core.network.HttpResult
import space.einundzwanzig.meetup.core.network.NetworkDefaults
import space.einundzwanzig.meetup.core.network.getCapped
import space.einundzwanzig.meetup.data.identity.SigningService
import space.einundzwanzig.meetup.data.news.NewsZapService
import space.einundzwanzig.meetup.data.news.NewsZapService.Companion.HTTP_TIMEOUT
import space.einundzwanzig.meetup.data.news.NewsZapService.Companion.KIND_ZAP_REQUEST
import space.einundzwanzig.meetup.data.news.NewsZapService.Companion.zapRequestTags
import space.einundzwanzig.meetup.data.news.ZapError
import space.einundzwanzig.meetup.data.news.ZapResult
import space.einundzwanzig.meetup.data.profile.LightningAddressLookup
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/** Port of `lib/services/news_zap_service.dart`; the lud16 lookup lives in `:data:profile`. */
@Singleton
internal class DefaultNewsZapService(
    private val lookup: LightningAddressLookup,
    private val signing: SigningService,
    private val client: OkHttpClient,
    private val log: DiagnosticLog,
    /** `https://<domain>/.well-known/lnurlp/<user>`; replaceable in tests (loopback server). */
    private val metadataUrl: (user: String, domain: String) -> String,
    private val timeout: Duration = HTTP_TIMEOUT,
) : NewsZapService {

    @Inject
    constructor(
        lookup: LightningAddressLookup,
        signing: SigningService,
        client: OkHttpClient,
        log: DiagnosticLog,
    ) : this(lookup, signing, client, log, ::lnurlpMetadataUrl)

    override suspend fun fetchLightningAddress(pubkeyHex: String): String? = lookup.fetchLightningAddress(pubkeyHex)

    override suspend fun createZapInvoice(
        authorPubkey: String,
        articleAddress: String,
        amountSats: Long,
        comment: String?,
    ): ZapResult {
        if (amountSats <= 0) return ZapResult.Failure(ZapError.AmountOutOfRange)

        val address = fetchLightningAddress(authorPubkey) ?: return ZapResult.Failure(ZapError.NoLightningAddress)
        if (!address.contains('@')) return ZapResult.Failure(ZapError.UnsupportedAddress)
        val parts = address.split('@')
        if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return ZapResult.Failure(ZapError.UnsupportedAddress)
        }
        val (user, domain) = parts

        // Step 2: LNURL-pay metadata.
        val meta: JsonObject = try {
            val resp = get(metadataUrl(user, domain))
            if (resp.status != 200) {
                log.debug(TAG, "lnurlp HTTP ${resp.status}")
                return ZapResult.Failure(ZapError.LnurlUnreachable)
            }
            resp.jsonObject()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "lnurlp nicht erreichbar", describeError(e))
            return ZapResult.Failure(ZapError.LnurlUnreachable)
        }

        val tag = meta["tag"]
        val isPayRequest = tag is JsonPrimitive && tag.isString && tag.content == "payRequest"
        val callbackValue = meta["callback"]
        if (!isPayRequest || callbackValue == null || callbackValue is JsonNull) {
            return ZapResult.Failure(ZapError.LnurlUnreachable)
        }

        val amountMsat = amountSats * 1000
        val min: Long
        val max: Long
        try {
            min = meta["minSendable"].numAsIntOrNull() ?: DEFAULT_MIN_SENDABLE_MSAT
            max = meta["maxSendable"].numAsIntOrNull() ?: DEFAULT_MAX_SENDABLE_MSAT
        } catch (e: RuntimeException) {
            // Dart threw an uncaught cast error here (stuck spinner); treat the server as unusable.
            // Also covers non-finite limits (`1e400`, 30-digit integers → Infinity), whose
            // `toInt()` throws UnsupportedOperationException.
            log.warn(TAG, "lnurlp-Grenzen unlesbar", e.message)
            return ZapResult.Failure(ZapError.LnurlUnreachable)
        }
        if (amountMsat < min || amountMsat > max) {
            log.debug(TAG, "Betrag $amountMsat msat ausserhalb von $min..$max")
            return ZapResult.Failure(ZapError.AmountOutOfRange)
        }

        // Without zap support it is a plain payment without receipt.
        val allowsNostr = meta["allowsNostr"].let { it is JsonPrimitive && !it.isString && it.content == "true" }

        // Step 3: sign the zap request.
        var zapRequestJson: String? = null
        if (allowsNostr) {
            try {
                val signed = signing.signEvent(
                    KIND_ZAP_REQUEST,
                    zapRequestTags(authorPubkey, articleAddress, amountMsat),
                    comment ?: "",
                )
                zapRequestJson = signed.toJson()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(TAG, "Zap-Anfrage konnte nicht signiert werden", e)
                return ZapResult.Failure(ZapError.SigningFailed)
            }
        }

        // Step 4: fetch the invoice.
        try {
            val callback = dartToString(callbackValue)
            // `commentAllowed` is only read when the comment is actually sent (Dart order).
            val commentAllowed = if (zapRequestJson == null && !comment.isNullOrEmpty()) {
                meta["commentAllowed"].numAsIntOrNull() ?: 0L
            } else {
                0L
            }
            val url = buildZapCallbackUrl(callback, amountMsat, comment, commentAllowed, nostr = zapRequestJson)
            val resp = get(url)
            if (resp.status != 200) {
                log.debug(TAG, "callback HTTP ${resp.status}")
                return ZapResult.Failure(ZapError.InvoiceFailed)
            }
            val json = resp.jsonObject()
            val prValue = json["pr"]
            val pr = if (prValue == null || prValue is JsonNull) "" else dartToString(prValue)
            if (pr.isEmpty()) {
                val reason = json["reason"]
                log.debug(TAG, "callback ohne pr: ${if (reason == null || reason is JsonNull) "keine Begruendung" else dartToString(reason)}")
                return ZapResult.Failure(ZapError.InvoiceFailed)
            }
            log.debug(TAG, "Zap-Rechnung ueber $amountSats sat erstellt (Zaps unterstuetzt: $allowsNostr)")
            return ZapResult.Success(pr)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(TAG, "Rechnung konnte nicht abgerufen werden", describeError(e))
            return ZapResult.Failure(ZapError.InvoiceFailed)
        }
    }

    /** GET with the per-request budget; transport failures throw (the callers map them). */
    private suspend fun get(url: String): HttpResult.Response =
        when (val r = client.getCapped(url, maxBytes = NetworkDefaults.SMALL_BODY_MAX_BYTES, timeout = timeout)) {
            is HttpResult.Response -> r
            is HttpResult.Failure -> throw r.cause
            is HttpResult.TooLarge -> throw IllegalStateException("LNURL-Antwort ueber ${r.maxBytes} Bytes (HTTP ${r.status})")
        }

    /** `jsonDecode(body) as Map<String, dynamic>`. */
    private fun HttpResult.Response.jsonObject(): JsonObject =
        DartJson.decode(text()) as? JsonObject ?: throw IllegalStateException("LNURL-Antwort ist kein JSON-Objekt")

    internal companion object {
        const val TAG: String = "NewsZap"
        const val DEFAULT_MIN_SENDABLE_MSAT: Long = 1_000L
        const val DEFAULT_MAX_SENDABLE_MSAT: Long = 100_000_000_000L

        fun lnurlpMetadataUrl(user: String, domain: String): String = "https://$domain/.well-known/lnurlp/$user"

        /** Dart `toString()` of a decoded JSON value (strings verbatim, numbers as written). */
        fun dartToString(e: JsonElement): String = when (e) {
            is JsonNull -> "null"
            is JsonPrimitive -> e.content
            else -> e.toString()
        }

        /**
         * Dart `(json[key] as num?)?.toInt()`: `null` for missing/`null`, the truncated number,
         * and an [IllegalStateException] (Dart cast error) for any other type.
         */
        fun JsonElement?.numAsIntOrNull(): Long? {
            if (this == null || this is JsonNull) return null
            val number = (this as? JsonPrimitive)?.takeIf { !it.isString }?.let { runCatching { DartJson.numberOf(it) }.getOrNull() }
                ?: throw IllegalStateException("type '${dartToString(this)}' is not a subtype of type 'num?'")
            return when (number) {
                is DartNumber.Int -> number.value
                is DartNumber.Double -> number.value.dartToInt()
            }
        }
    }
}
