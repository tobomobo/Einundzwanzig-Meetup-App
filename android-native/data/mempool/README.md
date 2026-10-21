# :data:mempool

Bitcoin network data from a configurable mempool.space-compatible instance, the app-wide
display currency, converter math and the home-screen widget feed. Port of
`lib/services/mempool.dart`, `mempool_config.dart`, `currency_service.dart`, the Bitcoin part of
`widget_service.dart` and the pure logic of `converter_screen.dart`. Spec: `spec/13-bitcoin-tools.md`.
No UI (screens and tiles belong to a `:feature:*` module).

```kotlin
implementation(project(":data:mempool"))
```

Package root: `space.einundzwanzig.meetup.data.mempool`. All services are Hilt `@Singleton`s.

## Mempool (root package)

| API | Purpose |
|---|---|
| `interface MempoolService` | `lastDashboard: StateFlow<BitcoinDashboardData?>`, `suspend getBlockHeight(): Int` (raw, never throws, `0` = unknown, 20 s cap), `suspend getPrices(): Map<String, Double>` (never throws, empty = failure), `suspend testHost(raw): Int` (throws `MempoolHttpException`/`IOException`; rethrow `CancellationException` before classifying), `suspend getDashboardData()` (6 parallel sources, single-flight in the app scope, monotonic height, last-good values, clearnet-only mirror fallback, never throws), `clearLastDashboard()`, `resetClient()`, `clearState()` |
| `MempoolService.USER_AGENT`, `SUPPORTED_CURRENCIES`, `PATH_*` | `21Meetup/1.3 (Einundzwanzig Meetup App)`, `EUR USD GBP CHF CAD AUD JPY`, endpoint paths |
| `interface MempoolConfig` | `host` (session override ?: persisted), `hostFlow`, `persistedHost`, `apiBase`, `mode` (from persisted host only), `isOnion`, `suspend ensureLoaded()`, `suspend setHost(raw)`, `suspend setMode(mode, custom)`, `setSessionOverride(host?)`, `suspend resetToDefaults()` |
| `enum MempoolMode` | `CLEARNET`, `TOR`, `CUSTOM` |
| `object MempoolHosts` | `CLEARNET`, `TOR`, `FALLBACKS`, `normalize(raw)`, `looksValid(raw)`, `isOnion(host)`, `timeoutFor(host)`, `label(host)` (dashboard footer, `first12…last12`) |
| `data class BitcoinDashboardData` | All dashboard values; `isLive`, `isDead`, `priceIn(code)`, `moscowTime`; companion `moscowTime(usd)`, `circulatingSupply(height)`, `SOURCES_TOTAL = 6`. `updatedAtMillis` is epoch millis |
| `class MempoolHttpException(path, statusCode, body)` | `snippet` (≤120 chars + `…`), `looksBlocked`, message `HTTP 500 bei /v1/prices — …` |
| `sealed interface HostTestFailure` | `of(error, rawHost)` → `Blocked(code)` / `Http(code)` / `OnionUnreachable` / `Other(typeName)` for the settings connection-test text (rethrow `CancellationException` before classifying; `of` itself rethrows it as a safety net); `errorTypeName(e)` |
| `object MempoolPrefs` | Key names `mempool_host`, `fiat_currency`, `mempool_last_height` (shared `KeyValueStore`) |

## Currency — `…currency`

| API | Purpose |
|---|---|
| `interface CurrencyRepository` | `current: StateFlow<String>` (default `EUR`, follows the store), `suspend load()`, `suspend read()`, `suspend set(code)` (ignores unsupported, persists, pushes the last dashboard to the widget), `suspend reset()` |
| `object CurrencyFormat` | `FALLBACK`, `supported`, `isSupported(code)`, `symbol(code)` (`€ $ £ ¥ CA$ A$`, else the code), `groupInt(Long/Int)` (`.` grouping, negatives), `formatPrice(price, code)` (`95.000 €`) |

`load()` also runs as an `AppStartupTask` (order 0). Call it yourself before the first frame if
a flash of `€` matters.

## Formatting — `…format`

| API | Purpose |
|---|---|
| `object BtcFormat` | `DASH` (`––`, 2×U+2013), `NO_TIME` (`--:--`), `SATS_PER_BTC`, `fmtInt`, `fmtSats`, `hhmm(millis, zone)`, `updatedLabel(d)`, `fees(d)` (`3·8·12`), `feesRow(d)`, `hashrate(ehs)`, `intOrDash(v)`, `priceOrDash(p)`, `lnCapacity(btc)`, `signedPercent(pct)`, `difficultyRow(d)` (`+3.7% / 1234 blk`), `difficultyWidget(d)`, `satsPerUnit(price)`, `converterTile(price, code)` (`1 € = 1.053 sats` or `null` → `tileConverter`) |
| `fun dartTryParseDouble(s)` | Dart `double.tryParse` |

Use `toStringAsFixed`/`dartRound`/`toDartString` from `:core:common` (`core.common.dart`) for anything else.

## Converter — `…converter`

`data class ConverterState(currency, …)`: immutable reducer of the converter screen.
Events: `onFiatChanged`, `onCryptoChanged`, `swapUnit`, `onCurrencyPicked` (persist via
`CurrencyRepository.set` in the ViewModel), `onPremiumText`, `onSlider(Float/Double)`,
`onLoading`, `onPricesLoaded(prices, nowMillis)` (empty = error, old prices kept), `recalculated`.
Derived: `btcPrice`, `currentFiat`, `fiatWithPremium`, `satsWithPremium`, `sliderValue`,
`premiumLabel`, `premiumResultText`, `premiumSatsText`, `premiumBaseText`. Constants
`PREMIUM_LIMIT = 20.0`, `DIVISIONS = 80` (Compose `steps = 79`). Load prices with
`MempoolService.getPrices()` (no fallback, no cached prices — parity).

## Home-screen widget — `…widget`

| API | Purpose |
|---|---|
| `interface BitcoinWidgetSink` | **Implemented by the widget domain**, contributed with `@Binds @IntoSet`. Receives `BitcoinWidgetValues` |
| `data class BitcoinWidgetValues` | The 10 `HomeWidgetPreferences` strings; `toMap()` with the exact keys (`block`, `priceFiat`, `priceSymbol`, `priceEur`, `moscow`, `fees`, `hashrate`, `supply`, `difficulty`, `updated`); `of(d, currency, nowMillis, zone)` |
| `interface BitcoinWidgetBridge` | `publish(d)` (skips dead data; home tile after every refresh), `refreshBitcoin()` (widget refresh button: fetch, skip-if-dead with log) |

## App reset

Call `MempoolConfig.resetToDefaults()`, `CurrencyRepository.reset()` and
`MempoolService.clearState()` (the in-memory state survives `KeyValueStore.clear()` otherwise;
config and currency also follow a store wipe on their own).

## Tests

```
tools/gradlew-locked :data:mempool:testDebugUnitTest
```

73 JVM tests: port of `test/mempool_web_headers_test.dart` (native branch), all §10.2 vectors
(normalize/looksValid, Moscow time, supply, grouping, symbols, toStringAsFixed, round,
difficulty/hashrate/LN labels, HTTP exception, host label, converter, premium, slider, tile)
and the §10.3 MockWebServer scenarios (single-flight, mirror order, no Tor/custom fallback,
monotonic height, last-good values, prefs migration values, currency, widget skip-if-dead).
