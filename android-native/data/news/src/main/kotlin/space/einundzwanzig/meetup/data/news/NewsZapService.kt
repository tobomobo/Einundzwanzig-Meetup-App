package space.einundzwanzig.meetup.data.news

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import space.einundzwanzig.meetup.core.common.log.AppLogger
import space.einundzwanzig.meetup.core.common.log.DiagnosticLog
import space.einundzwanzig.meetup.core.common.log.describeError
import space.einundzwanzig.meetup.data.profile.LightningAddressLookup
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Why a zap failed (Dart `ZapError`, spec 11 §3.6). The screen maps it to a string:
 * [NoLightningAddress] → `newsZapNoAddress`, [UnsupportedAddress] → `newsZapUnsupportedAddress`,
 * [AmountOutOfRange] → `newsZapAmountRange`, everything else → `newsZapFailed`.
 */
enum class ZapError {
    NoLightningAddress,
    UnsupportedAddress,
    LnurlUnreachable,
    AmountOutOfRange,
    SigningFailed,
    InvoiceFailed,
}

/** Outcome of [NewsZapService.createZapInvoice] (Dart `ZapResult`). */
sealed interface ZapResult {
    /** `true` for [Success]. */
    val ok: Boolean get() = this is Success

    /** The bolt11 invoice for [Success], else `null`. */
    val invoice: String? get() = (this as? Success)?.invoice

    /** A bolt11 invoice to hand to a wallet ([ZapInvoiceLauncher.open]). */
    data class Success(override val invoice: String) : ZapResult

    /** No invoice; see [error]. */
    data class Failure(val error: ZapError) : ZapResult
}

/**
 * NIP-57 zaps to article authors over LNURL-pay / LUD-16 (Dart `NewsZapService`, spec 11 §5
 * N2/N3, §6.8, §6.9, §8.10). No method throws except for cancellation.
 */
interface NewsZapService {

    /**
     * The author's lightning address from the newest kind-0 profile on the read targets
     * (delegates to `:data:profile` `LightningAddressLookup`): trimmed `lud16` with `@`,
     * [LUD06_MARKER] for lud06-only profiles (unsupported, show `newsZapUnsupportedAddress`), or `null`.
     */
    suspend fun fetchLightningAddress(pubkeyHex: String): String?

    /**
     * Builds a zap invoice for [amountSats] to the author of [articleAddress]:
     * 1. lightning address ([fetchLightningAddress]); none → [ZapError.NoLightningAddress], no `@`
     *    or not exactly `user@domain` → [ZapError.UnsupportedAddress];
     * 2. `GET https://<domain>/.well-known/lnurlp/<user>` ([HTTP_TIMEOUT]); non-200, bad JSON,
     *    `tag != "payRequest"` or no `callback` → [ZapError.LnurlUnreachable]; amount outside
     *    `minSendable` (default 1000) .. `maxSendable` (default 1e11) msat → [ZapError.AmountOutOfRange];
     * 3. if `allowsNostr == true`: sign kind 9734 with [zapRequestTags] and content [comment]
     *    (signer error → [ZapError.SigningFailed]);
     * 4. `GET <callback>?amount=<msat>&nostr=<request JSON>` (or `&comment=` cut to
     *    `commentAllowed` without zap support); non-200 or empty `pr` → [ZapError.InvoiceFailed].
     *
     * [amountSats] ≤ 0 → [ZapError.AmountOutOfRange]. The invoice is not checked against the
     * request (Dart parity, spec 11 §14 risk 13).
     */
    suspend fun createZapInvoice(
        authorPubkey: String,
        articleAddress: String,
        amountSats: Long,
        comment: String? = null,
    ): ZapResult

    companion object {
        /**
         * Result of [fetchLightningAddress] for a profile with only `lud06` (`"lud06:"`); compare
         * with `==`. Re-exported so callers need no `:data:profile` dependency.
         */
        const val LUD06_MARKER: String = LightningAddressLookup.LUD06_MARKER

        /** Amounts offered by the zap sheet. */
        val ZAP_AMOUNTS_SATS: List<Long> = listOf(21L, 210L, 2100L, 21000L)

        /** Kind of a zap request. */
        const val KIND_ZAP_REQUEST: Int = 9734

        /** Budget of each LNURL request. */
        val HTTP_TIMEOUT: Duration = 12.seconds

        /**
         * Tags of the zap request: `[["relays", <NEWS_RELAYS…>], ["amount", "<msat>"], ["p", author], ["a", address]]`.
         */
        fun zapRequestTags(authorPubkey: String, articleAddress: String, amountMsat: Long): List<List<String>> =
            listOf(
                listOf("relays") + LightningAddressLookup.NEWS_RELAYS,
                listOf("amount", amountMsat.toString()),
                listOf("p", authorPubkey),
                listOf("a", articleAddress),
            )
    }
}

/**
 * Hands a zap invoice to a wallet (spec 11 §2.4 step 4, §11): a plain `ACTION_VIEW` on
 * `lightning:<bolt11>` without a chooser (unlike V4V). No `resolveActivity` check, so no
 * `<queries>` manifest entry is needed.
 */
object ZapInvoiceLauncher {
    private const val TAG = "NewsZap"

    /** The `ACTION_VIEW lightning:<bolt11>` intent. */
    fun intent(bolt11: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse("lightning:$bolt11"))

    /**
     * Starts the wallet. Returns `false` when no app handles `lightning:` (or the start failed);
     * then show the `newsZapNoWallet` sheet with the invoice and `newsZapCopyInvoice`. A
     * non-Activity [context] gets `FLAG_ACTIVITY_NEW_TASK`.
     *
     * Any start failure, including a `SecurityException` from the wallet (e.g. a non-exported
     * activity), returns `false` → fallback sheet; failures other than "no wallet" are logged
     * to [log] at debug level.
     *
     * @param log diagnostic log for start failures; defaults to the static [AppLogger], pass the
     *   injected [DiagnosticLog] where available.
     */
    fun open(context: Context, bolt11: String, log: DiagnosticLog = AppLogger): Boolean = try {
        val intent = intent(bolt11)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: Exception) {
        log.debug(TAG, "Wallet-Start fehlgeschlagen: ${describeError(e)}")
        false
    }
}
