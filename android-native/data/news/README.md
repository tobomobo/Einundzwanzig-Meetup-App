# :data:news

News domain without UI: the RSS feed of media.einundzwanzig.space (parsing, RAM cache, unread
counter), the NIP-23 full text, NIP-25 likes (plus the shared "read targets" relay list) and
NIP-57 zaps to article authors over LNURL-pay. Spec: `spec/11-chat-news-community.md` §2.2–§2.4,
§3.4–§3.6, §4, §5 N1–N3, §6.1, §6.6–§6.9, §8.3–§8.5, §8.7, §8.10, §10.4.
Package root: `space.einundzwanzig.meetup.data.news`.

```kotlin
implementation(project(":data:news"))
```

Ports `lib/services/news_service.dart`, `news_reactions_service.dart`, `news_zap_service.dart`.
Reuses `:data:profile` `LightningAddressLookup` (lud16 lookup + read targets),
`:core:common` `DartUri` (byte-exact callback URL; same rules as the V4V builder in
`:data:community`, kept local so news does not depend on the community domain — both belong in a
future `:core:network` LNURL client, spec §13), `:core:nostr` `RelayClient`/`EventGuard`, `:core:network` `getCapped`, `:core:data` `KeyValueStore`.

## Public API (inject the interfaces; all `@Singleton`, bound in `di.NewsModule`)

| API | Purpose (Dart origin) |
|---|---|
| `NewsService` | `fetchArticles(limit = 50)` (feed, no cache; NewsScreen + pull-to-refresh, widget uses `limit = 1`), `cachedArticles(limit = 50)` (10-min RAM cache, only non-empty results; dashboard tile), `unreadCount()` (first call stores the newest `publishedAt`, returns 0), `markRead()` (dashboard News tile only, not the hub card), `fetchArticleContent(guid): String?` (NIP-23 Markdown, newest wins). Constants `KEY_LAST_READ_TS` = `news_last_read_ts`, `CACHE_TTL`, `FEED_TIMEOUT` (12 s), `ARTICLE_TIMEOUT` (12 s), `ARTICLE_CONNECT_TIMEOUT` (6 s) |
| `NewsArticle(id, author, title, summary, image, content, publishedAt, link)` + `publishedAtMillis` | RSS item. `author` = Dart field `pubkey` (an author NAME). `publishedAt` in unix seconds; format the local date as `d.M.yyyy` |
| `NewsArticles` | `authorLabel(a)` (`"Einundzwanzig"` fallback), `authorPubkey(a)` (Dart: 2nd `:` part, unvalidated), `zapAuthorPubkey(a)` (same but `""` unless 64-hex — use it for like/zap, spec §14 risk 12), `articleUrl(a)` (http(s) link or `NewsLinks.WEBSITE_URL`), `shareText(a)` (`"title\n\nurl"`), `newest(list)` (dashboard tile headline) |
| `NewsLinks` | `FEED_URL`, `WEBSITE_URL` ("Auf der Webseite öffnen"/fallback), `WRITE_URL` ("Artikel schreiben"), `FEED_USER_AGENT`, `FEED_ACCEPT`, `ARTICLE_RELAYS` |
| `ArticleAddress.parse(guid): ArticleAddress(pubkey, d)?` | `30023:<pubkey>:<d>` (d may contain `:`); `KIND_LONG_FORM` |
| `RssParser` | `parse(xml, nowSeconds)`, `parseDate(s, nowSeconds)` (UTC, zone suffix ignored), `clean(s)`, `stripHtml(s)`, `UNTITLED` = `"(ohne Titel)"` |
| `NewsReactionsService` | `readTargets()` (news relays ∪ `relay.nostr.band` ∪ active relays — the list event chat, RSVP and the zap-receipt check share), `writeTargets()`, `hasLikedLocally(address)` (no network; call first so the heart is filled instantly), `fetchLikes(address): ArticleLikes`, `like(address, authorPubkey): Boolean` (`p` tag only for a 64-hex author; overlapping calls for the same address share one sign + publish; the lock is per address, so a slow signer for one article does not block likes of others). Companion: `KEY_LIKED_REACTIONS` = `news_liked_reactions`, `LIKED_CAP` (500), `NEWS_RELAYS`, `READ_ONLY_RELAYS`, `likeTags(...)` (drops a non-hex `p`), `isPositive(content)`, `likedEntry(pubkey, address)`, timeouts |
| `ArticleLikes(count, mine)` | `withMine()`, `EMPTY`. UI: label = count if loaded and > 0, else `newsLike`; no un-like |
| `NewsZapService` | `fetchLightningAddress(pubkeyHex)` (delegates to `:data:profile`; compare with `NewsZapService.LUD06_MARKER` (`"lud06:"`) = unsupported), `createZapInvoice(authorPubkey, articleAddress, amountSats, comment = article.title): ZapResult`. Companion: `LUD06_MARKER`, `ZAP_AMOUNTS_SATS` (21/210/2100/21000), `KIND_ZAP_REQUEST`, `HTTP_TIMEOUT` (12 s), `zapRequestTags(...)` |
| `ZapResult` (`Success(invoice)` / `Failure(error)`, `ok`, `invoice`), `enum ZapError` | `NoLightningAddress` → `newsZapNoAddress`, `UnsupportedAddress` → `newsZapUnsupportedAddress`, `AmountOutOfRange` → `newsZapAmountRange`, `LnurlUnreachable`/`SigningFailed`/`InvoiceFailed` → `newsZapFailed` |
| `ZapInvoiceLauncher` | `open(context, bolt11, log = AppLogger): Boolean` (any start failure, incl. `SecurityException`, → `false`; pass the injected `DiagnosticLog`) — plain `ACTION_VIEW lightning:<bolt11>` (no chooser, unlike V4V); `false` → show the `newsZapNoWallet` sheet with invoice + `newsZapCopyInvoice`. `intent(bolt11)` |

### Wiring notes for `:feature:news` / home

- **Dashboard tile**: `unreadCount()` + `cachedArticles()` → `NewsArticles.newest(...)?.title`. On tap:
  widget `markNewsSeen()`, `markRead()` (fire and forget; launching it from a `viewModelScope` is
  fine because the write itself runs in the injected `@ApplicationScope` and completes even when the
  caller is cancelled), set unread 0, open NewsScreen.
- **ArticleDetail**: in parallel `fetchArticleContent(article.id)`, `hasLikedLocally(article.id)`
  (→ `ArticleLikes(count, true)`), `fetchLikes(article.id)`. Like:
  `like(article.id, NewsArticles.zapAuthorPubkey(article))` (`authorPubkey` is safe too, the service drops a non-hex `p`; a double tap or a recreated screen does not send a second event; publish + remember run in
  `@ApplicationScope`, so leaving the screen does not lose an accepted like), on `true` → `likes.withMine()`, else
  `newsLikeFailed`. Zap: disable when `zapAuthorPubkey` is `""`; else
  `createZapInvoice(pk, article.id, sats, comment = article.title)` → `ZapInvoiceLauncher.open`.
- Open external links (`WRITE_URL`, `WEBSITE_URL`, `articleUrl`, Markdown links) through
  `:core:common` `SafeUrl`.

## Behaviour notes

- Feed: `GET FEED_URL` with `Accept` and the fixed UA `Einundzwanzig-Meetup-App/1.0 (Android)`,
  12 s, non-200/any error → `[]`, body decoded as UTF-8 with U+FFFD for malformed bytes, 8 MiB cap.
- RSS parser = the Dart regexes, with ASCII-explicit classes (`\b`, `\w`, `\d`, `\s` and the
  case-insensitive flag are spelled out) so Android's ICU engine behaves like Dart.
- All relay reads go through `RelayClient` (signature verification + filter binding), REQ filters
  in Dart key order: `{"kinds":[30023],"authors":[pk],"#d":[d],"limit":1}` (sub `article`),
  `{"kinds":[7],"#a":[address],"limit":500}` (sub `likes`). "Newest wins" uses
  `EventGuard.replaceableCreatedAt` (≤ now + 600 s), ties → later arrival.
- Like: kind 7, content `"+"`, tags `[["a",addr],["p",author]?,["k","30023"]]` (`p` only if 64 hex), published to all
  write targets in parallel (connect 5 s, 8 s total, early end when every relay answered).
  Success iff ≥ 1 `OK true`; then `"<signerPubkey>|<address>"` is appended (cap 500, oldest out).
- Zap request (only if LNURL `allowsNostr == true`): kind 9734, content = comment, tags
  `[["relays",<3 news relays>],["amount","<msat>"],["p",author],["a",address]]`, sent as
  `nostr=<encodeQueryComponent(event JSON)>` (key order `id,pubkey,created_at,kind,tags,content,sig`),
  never published. Without `allowsNostr`: `comment=` cut to `commentAllowed` UTF-16 units.

## Tests

`tools/gradlew-locked :data:news:testDebugUnitTest` — 62 JVM tests (4 on Robolectric).

Flutter has no Dart unit tests for this domain; the tests implement the spec §10.4 vectors:

- `RssParserTest` (9): the RSS date vectors (`1792226373`, `1792152977`, `+0200` ignored, garbage → now),
  the live sample item (+ address split), CDATA/entity order, dc:creator/img/link/id fallbacks,
  skipped items, tag-prefix safety, Unicode whitespace.
- `NewsArticlesTest` (6): `ArticleAddress` (`30023:pk:a:b` → `a:b`), author pubkey / zap pubkey,
  `articleUrl`, label, newest, `ArticleLikes.withMine`.
- `NewsServiceTest` (13, MockWebServer + fake relays): headers, limit, errors, malformed UTF-8,
  cache TTL / empty not cached, unread first-run/count/markRead (also when the caller is cancelled), wrong pref type, full text
  newest-wins + REQ bytes, forged/future/empty, bad guid, 12 s overall budget.
- `NewsReactionsServiceTest` (17): like tag layout, non-hex author → no `p` tag, overlapping likes sign/publish once, a suspended signer for article A does not block a like of article B, likes vector `["+","","🤙","-"," - "]` → 3,
  duplicate pubkeys, local-only +1, identity switch, publish targets/OK handling, no resend,
  timeout and early finish, signer failure, 500 cap, like cancelled after an OK is still remembered.
- `NewsZapServiceTest` (13, MockWebServer): zap tags for 2100 sat, byte-exact `nostr=` URL and
  verifiable zap request, comment truncation `V4V –` → `V4V+%E2%80%93`, `'`/`*` → `%27`/`%2A` on the wire (Dart
  `encodeQueryComponent`; OkHttp keeps the escapes), `?`/`&` separator, every
  `ZapError` path (incl. non-finite `minSendable`/`maxSendable`).
- `ZapInvoiceLauncherTest` (4, Robolectric): `ACTION_VIEW lightning:<bolt11>` without chooser, no
  wallet → `false`, wallet → started with `FLAG_ACTIVITY_NEW_TASK` from a non-Activity context,
  `SecurityException` → `false` and logged to the passed `DiagnosticLog`.

## Deviations from the Dart code

- `like()` adds the `p` tag only for a 64-hex author, so a link-only article gets no bogus `p` tag
  whatever the caller passes (spec §14 risk 12). Valid articles produce identical events.
- Overlapping `like()` calls for the same identity + address share one in-flight sign/publish
  (Dart relied on the widget's `liking` flag, which does not survive a configuration change;
  spec §14 risk 6).
- A non-number or non-finite (`1e400`) `minSendable`/`maxSendable` gives `LnurlUnreachable`; Dart
  threw an uncaught cast / `toInt()` error (stuck zap spinner).
- `news_last_read_ts` / `news_liked_reactions` of the wrong type: unread count returns 0 and the
  like is not remembered (same visible result as Dart's caught `TypeError`).
- `RelayClient` ignores frames with a foreign subscription id and ends a relay on `CLOSED`
  (Dart waited for EOSE/close). Like publishing ends a relay on disconnect/timeout instead of
  waiting for the overall timer; the accepted set is the same.
- LNURL responses are read as UTF-8 and capped at 64 KiB (`NetworkDefaults.SMALL_BODY_MAX_BYTES`);
  the feed is capped at 8 MiB.
- Debug logs use `take(8)` on pubkeys (Dart `substring(0, 8)` threw on short keys).
- `htmlToMarkdown` (unused in Dart) and the `dTag`/`topics` compat fields are not ported.

## Known gaps

- Relay `NOTICE` frames during a like publish are not logged (Dart logs `NOTICE von <url>: <msg>`,
  `news_reactions_service.dart:352-355`; spec 11 §6.7). `:core:nostr`'s `RelayClient.publish` returns
  only `RelayPublishResult(status, message)` and does not surface NOTICEs, so a "keine Antwort" line
  cannot be explained. Fix needs a hook in `:core:nostr` (e.g. `RelayPublishResult.notices` or
  `RelayPublishOptions.onNotice`); then log `log.debug(TAG, "NOTICE von $url: $msg")` in
  `DefaultNewsReactionsService.publishAndRemember`.
- No `ConnectivityManager` offline distinction (Dart has none for news either).
- The zap invoice is not validated against the request (amount / description hash), as in Dart
  (spec §14 risk 13).
- The Markdown renderer for article bodies belongs to the UI (`:feature:news` / a markdown module).
