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
- **Zap without a NIP-23 author key**: the Zap button is disabled (`ArticleDetailUiState.canZap`
  = `NewsArticles.zapAuthorPubkey(article).isNotEmpty()`), per spec 11 §14 risk 12 and the
  `:data:news` wiring note. This is a deliberate change from Dart, which opens the amount sheet
  and only then fails with `newsZapNoAddress`.
- Route events (snackbars, `OpenInvoice`) are collected only while the screen is STARTED. An
  invoice that arrives in the background opens the wallet when the user comes back.
  `ZapInvoiceLauncher.open` receives the injected `DiagnosticLog` (`ArticleDetailViewModel.log`).
- Sheets close with their hide animation before the zap or copy action runs.

The detail resolves its article through `NewsArticleStore` (the list currently shown), falling
back to `NewsService.cachedArticles()` (process death), per ROUTES.md "Arguments instead of models".

## Entries

```kotlin
fun EntryProviderScope<NavKey>.newsEntries()
```

## Dependencies

- `:data:news` — `NewsService`, `NewsReactionsService`, `NewsZapService`, `NewsArticles`, `NewsLinks`, `ZapInvoiceLauncher`
- `:data:calendar` — `HomeWidgetStore.markNewsSeen()`
- `:core:common` — `DiagnosticLog` (injected, via the feature convention plugin)

Native-only strings live in `res/values{,-en,-es}/strings.xml` with the `native_news_` prefix.

## Tests

ViewModel tests (`NewsViewModelTest`, `ArticleDetailViewModelTest`, `NewsArticleStoreTest`), screen
tests (`NewsScreenTest`, `ArticleDetailScreenTest`) and route tests (`ArticleDetailRouteTest`: share
fallback to the clipboard, no-wallet invoice sheet, Markdown links through `Navigator.openUrl`).
