# :feature:news

News list and article detail (spec 11 §2.3–§2.4, §8.6 via `core:ui` `MarkdownView`).

## Screens

| NavKey | Route / Screen | Spec |
|---|---|---|
| `News(markAsRead)` | `NewsRoute` / `NewsScreen` (`NewsViewModel`) | 11 §2.3 |
| `NewsArticleDetails(articleId, listIndex)` | `ArticleDetailRoute` / `ArticleDetailScreen` (`ArticleDetailViewModel`) | 11 §2.4 |

- **News**: action tiles "Artikel schreiben" / "Auf der Webseite öffnen", pull-to-refresh
  (expressive `LoadingIndicator`), loading / empty / card list, `newsSource` footer. With
  `markAsRead = true` (Home tile, widget) it calls `NewsService.markRead()` and
  `HomeWidgetStore.markNewsSeen()` once on open.
- **Article detail**: cover, title, author/date, NIP-23 Markdown body (summary + indicator while
  loading; summary + "Auf der Webseite lesen" without full text), like (NIP-25, no un-like),
  zap (amount sheet 21/210/2100/21000 → `lightning:` intent → copy-invoice fallback sheet),
  share (system sheet, clipboard + snackbar fallback), not-found state for unknown ids.
- **Zap without a NIP-23 author key** (`ArticleDetailUiState.canZap` =
  `NewsArticles.zapAuthorPubkey(article).isNotEmpty()` is false): the Zap button stays enabled, and
  a tap shows the `newsZapNoAddress` error snackbar right away. No amount sheet opens and no zap
  starts (spec 11 §14 risk 12, `:data:news` wiring note). Dart opens the amount sheet first and
  shows the same error only after an amount is picked.
- **Like accessibility**: the like button is exposed as one Button node, "`newsLike`, <count>".
  When liked, it carries the `native_news_liked` state description and has no click action,
  because NIP-25 has no un-like.
- Route events (snackbars, `OpenInvoice`) are collected only while the screen is STARTED. An
  invoice that arrives in the background opens the wallet when the user comes back.
  `ZapInvoiceLauncher.open` receives the injected `DiagnosticLog` (`ArticleDetailViewModel.log`).
- Sheets close with their hide animation before the zap or copy action runs. If the hide is
  cancelled (for example, the route leaves composition), the action does not run.

The detail resolves its article through `NewsArticleStore` (the list currently shown), falling
back to `NewsService.cachedArticles()` (process death), per ROUTES.md "Arguments instead of models".

## Entries

```kotlin
fun EntryProviderScope<NavKey>.newsEntries()
```

Requires :app to install `rememberViewModelStoreNavEntryDecorator()` (one ViewModelStore per
entry). The routes pass no custom `hiltViewModel` key, so each ViewModel belongs to its NavEntry.
`NewsViewModel` init runs `markRead()`/`markNewsSeen()` once per `News(markAsRead = true)` entry,
and a reopened article gets a fresh `ArticleDetailViewModel`.

## Dependencies

- `:data:news` — `NewsService`, `NewsReactionsService`, `NewsZapService`, `NewsArticles`, `NewsLinks`, `ZapInvoiceLauncher`
- `:data:calendar` — `HomeWidgetStore.markNewsSeen()`
- `:core:common` — `DiagnosticLog` (injected, via the feature convention plugin)

Native-only strings live in `res/values{,-en,-es}/strings.xml` with the `native_news_` prefix.

## Tests

ViewModel tests (`NewsViewModelTest`, `ArticleDetailViewModelTest`, `NewsArticleStoreTest`), screen
tests (`NewsScreenTest`, including pull-to-refresh; `ArticleDetailScreenTest`) and route tests
(`ArticleDetailRouteTest`: share fallback to the clipboard, no-wallet invoice sheet, `newsZapNoAddress`
snackbar for a zap failure and for a link-only article, the `newsLikeFailed` snackbar, and Markdown
links through `Navigator.openUrl`).
