package space.einundzwanzig.meetup.data.news.internal

import space.einundzwanzig.meetup.core.common.dart.DartUri

/**
 * Builds the LNURL-pay callback URL of the news zap byte-exactly like
 * `lib/services/news_zap_service.dart` (spec 11 §2.4 step 4, §5):
 *
 * `<callback>{? or &}amount=<msat>`, then
 * - `&nostr=<Uri.encodeQueryComponent(nostr)>` when [nostr] (the signed kind-9734 JSON) is
 *   non-null; no comment is sent then (zap rule);
 * - otherwise `&comment=<Uri.encodeQueryComponent(c)>` when [comment] is non-empty and
 *   [commentAllowed] > 0, where `c` is [comment] cut to [commentAllowed] UTF-16 code units.
 *
 * `Uri.encodeQueryComponent` keeps only `A-Z a-z 0-9 - . _ ~`, so the result is already in the
 * form OkHttp keeps verbatim (e.g. `'` is `%27` in Dart and here).
 *
 * Same rules as `:data:community` `buildLnurlCallbackUrl` (V4V); kept local so `:data:news` does
 * not depend on the community domain. Both should move to `:core:network` (spec §13).
 */
internal fun buildZapCallbackUrl(
    callback: String,
    amountMsat: Long,
    comment: String?,
    commentAllowed: Long,
    nostr: String?,
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
