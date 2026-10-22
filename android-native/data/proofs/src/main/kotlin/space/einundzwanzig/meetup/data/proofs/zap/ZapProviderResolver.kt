package space.einundzwanzig.meetup.data.proofs.zap

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * Step 5 of the zap-receipt audit (Dart `ZapReceiptValidator.isFromRecipientProvider` /
 * `providerPubkeyFor` / `providerPubkeyFromLnurlp`): the receipt must be signed by the LNURL
 * provider (`nostrPubkey`) of the recipient's **current** lud16.
 *
 * Positive results are cached in memory (process lifetime) and in the pref
 * `zap_provider_pubkey_<recipient>` (`{"pk","ts"}`, [CACHE_TTL]). Failures are never cached.
 */
interface ZapProviderResolver {

    /**
     * `false` if either key is empty, the receipt is self-issued (issuer == recipient or
     * issuer == non-empty sender, case-insensitive), or no provider can be resolved; otherwise
     * whether the provider key equals [receiptPubkey] (case-insensitive). The self-issued checks
     * return before any network call.
     */
    suspend fun isFromRecipientProvider(receiptPubkey: String, recipientPubkey: String, senderPubkey: String = ""): Boolean

    /** The provider pubkey (64 lowercase hex) of [recipientPubkey] (cache key as given), or `null`. */
    suspend fun providerPubkeyFor(recipientPubkey: String): String?

    /**
     * Reads `nostrPubkey` from LNURL-pay metadata at [url]: HTTP 200, body ≤ 64 KiB, UTF-8 JSON
     * object, `allowsNostr` literally `true`, `nostrPubkey` lowercased matching `^[0-9a-f]{64}$`.
     * @return the key or `null` for a well-formed negative answer.
     * @throws java.io.IOException on network errors/timeouts, and parse exceptions for bad JSON
     *   (as in Dart, where [providerPubkeyFor] catches them).
     */
    suspend fun providerPubkeyFromLnurlp(url: String): String?

    companion object {
        /** Pref key prefix of the provider cache. */
        const val CACHE_PREFIX: String = "zap_provider_pubkey_"

        /** Lifetime of a cached provider key. */
        val CACHE_TTL: Duration = 7.days

        /** Whole-request timeout of the LNURL metadata fetch. */
        val HTTP_TIMEOUT: Duration = 6.seconds

        /** Body cap of the LNURL metadata. */
        const val MAX_BODY_BYTES: Long = 64L * 1024
    }
}
