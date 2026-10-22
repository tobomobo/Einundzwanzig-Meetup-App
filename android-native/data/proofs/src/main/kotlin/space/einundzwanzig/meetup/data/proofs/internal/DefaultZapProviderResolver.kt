package space.einundzwanzig.meetup.data.proofs.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import space.einundzwanzig.meetup.core.common.DefaultDispatcher
import space.einundzwanzig.meetup.core.common.TimeSource
import space.einundzwanzig.meetup.core.common.json.DartJson
import space.einundzwanzig.meetup.core.common.json.dartIntOrNull
import space.einundzwanzig.meetup.core.common.json.jsonObjectOf
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.crypto.DartUtf8
import space.einundzwanzig.meetup.core.data.prefs.KeyValueStore
import space.einundzwanzig.meetup.core.network.HttpResult
import space.einundzwanzig.meetup.core.network.getCapped
import space.einundzwanzig.meetup.data.badges.text.DartString
import space.einundzwanzig.meetup.data.profile.LightningAddressLookup
import space.einundzwanzig.meetup.data.proofs.zap.ZapProviderResolver
import space.einundzwanzig.meetup.data.proofs.zap.ZapProviderResolver.Companion.CACHE_PREFIX
import space.einundzwanzig.meetup.data.proofs.zap.ZapProviderResolver.Companion.CACHE_TTL
import space.einundzwanzig.meetup.data.proofs.zap.ZapProviderResolver.Companion.HTTP_TIMEOUT
import space.einundzwanzig.meetup.data.proofs.zap.ZapProviderResolver.Companion.MAX_BODY_BYTES
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** Port of the provider half of `ZapReceiptValidator`. */
internal class DefaultZapProviderResolver @Inject constructor(
    private val store: KeyValueStore,
    private val lightningAddresses: LightningAddressLookup,
    private val http: OkHttpClient,
    private val time: TimeSource,
    private val log: DiagnosticLog,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) : ZapProviderResolver {

    /** Recipient (as given) → provider key. Only non-empty keys are stored. */
    private val memory = ConcurrentHashMap<String, String>()

    override suspend fun isFromRecipientProvider(
        receiptPubkey: String,
        recipientPubkey: String,
        senderPubkey: String,
    ): Boolean = withContext(dispatcher) { fromRecipientProvider(receiptPubkey, recipientPubkey, senderPubkey) }

    private suspend fun fromRecipientProvider(receiptPubkey: String, recipientPubkey: String, senderPubkey: String): Boolean {
        if (receiptPubkey.isEmpty() || recipientPubkey.isEmpty()) return false
        val receipt = DartString.lower(receiptPubkey)
        val recipient = DartString.lower(recipientPubkey)
        if (receipt == recipient) return false
        if (senderPubkey.isNotEmpty() && receipt == DartString.lower(senderPubkey)) return false
        val provider = providerFor(recipientPubkey)
        if (provider.isNullOrEmpty()) {
            log.debug(TAG, "Kein LNURL-Provider für ${recipientPubkey.take(8)}… ermittelbar — Quittung zählt nicht")
            return false
        }
        return DartString.lower(provider) == receipt
    }

    override suspend fun providerPubkeyFor(recipientPubkey: String): String? =
        withContext(dispatcher) { providerFor(recipientPubkey) }

    private suspend fun providerFor(recipientPubkey: String): String? {
        memory[recipientPubkey]?.let { return it.ifEmpty { null } }

        val key = CACHE_PREFIX + recipientPubkey
        try {
            val raw = store.getString(key)
            if (raw != null) {
                val j = DartJson.decode(raw) as JsonObject
                val tsElement = j["ts"]
                // Dart `j['ts'] as int? ?? 0`: a non-int value throws → resolve again.
                val ts = if (tsElement == null || tsElement is JsonNull) {
                    0L
                } else {
                    tsElement.dartIntOrNull() ?: throw IllegalArgumentException("ts")
                }
                val pk = j["pk"].dartToStringOrEmpty()
                if (pk.isNotEmpty() && time.nowMillis() - ts < CACHE_TTL.inWholeMilliseconds) {
                    memory[recipientPubkey] = pk
                    return pk
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Unreadable cache entry: resolve again.
        }

        val pk = try {
            resolveProvider(recipientPubkey).orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug(TAG, "Provider-Auflösung fehlgeschlagen: $e")
            ""
        }
        if (pk.isEmpty()) return null
        memory[recipientPubkey] = pk
        try {
            store.setString(key, DartJson.encode(jsonObjectOf("pk" to pk, "ts" to time.nowMillis())))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Cache write errors are ignored (Dart).
        }
        return pk
    }

    private suspend fun resolveProvider(recipientPubkey: String): String? {
        val lud16 = lightningAddresses.fetchLightningAddress(recipientPubkey)
        if (lud16 == null || !lud16.contains('@')) return null
        val parts = lud16.split('@')
        if (parts.size != 2) return null
        val user = DartString.trim(parts[0])
        val domain = DartString.lower(DartString.trim(parts[1]))
        if (user.isEmpty() || domain.isEmpty() || !DOMAIN.matches(domain)) return null
        return providerFromLnurlp("https://$domain/.well-known/lnurlp/$user")
    }

    override suspend fun providerPubkeyFromLnurlp(url: String): String? =
        withContext(dispatcher) { providerFromLnurlp(url) }

    private suspend fun providerFromLnurlp(url: String): String? {
        val response = when (val r = http.getCapped(url, maxBytes = MAX_BODY_BYTES, timeout = HTTP_TIMEOUT)) {
            is HttpResult.Failure -> throw r.cause
            is HttpResult.TooLarge -> return null
            is HttpResult.Response -> r
        }
        if (response.status != 200) return null
        val meta = DartJson.decode(DartUtf8.decodeStrict(response.body)) as? JsonObject
            ?: throw IOException("LNURL metadata is not a JSON object")
        val allowsNostr = meta["allowsNostr"]
        if (!(allowsNostr is JsonPrimitive && !allowsNostr.isString && allowsNostr.content == "true")) return null
        val pk = DartString.lower(meta["nostrPubkey"].dartToStringOrEmpty())
        if (!PUBKEY.matches(pk)) return null
        return pk
    }

    private companion object {
        const val TAG = "ZapReceipt"
        val DOMAIN = Regex("^[a-z0-9.-]+$")
        val PUBKEY = Regex("^[0-9a-f]{64}$")
    }
}
