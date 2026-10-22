# :feature:bitcoin

Bitcoin tool screens (spec `spec/13-bitcoin-tools.md` §2). All data logic comes from `:data:mempool`.

```kotlin
// :app
entryProvider { bitcoinEntries() }
```

## Screens

| NavKey | Route / screen | Spec | ViewModel |
|---|---|---|---|
| `BitcoinDashboard` | `BitcoinDashboardRoute` / `BitcoinDashboardScreen` | 13 §2.1 | `BitcoinDashboardViewModel` (`MempoolService.getDashboardData`, `lastDashboard`, `CurrencyRepository.current`, `MempoolConfig.hostFlow`) |
| `Converter` | `ConverterRoute` / `ConverterScreen` (+ currency `ModalBottomSheet`) | 13 §2.2 | `ConverterViewModel` (pure `ConverterState` reducer from `:data:mempool`, `getPrices`, `CurrencyRepository.set`) |
| `MempoolSettings` | `MempoolSettingsRoute` / `MempoolSettingsScreen` | 13 §2.3 | `MempoolSettingsViewModel` (`MempoolConfig`, `MempoolHosts`, `testHost`, `HostTestFailure`) |

Behaviour notes:

- Dashboard: cached `lastDashboard` first, `LoadingIndicator` only without any data, pull-to-refresh
  (expressive indicator), 60 s auto-refresh only while the entry is on screen
  (`repeatOnLifecycle(STARTED)`), 2 s status-dot glow, red blocked/offline banner with
  "Datenquelle ändern" → `MempoolSettings`; the dashboard reloads when it becomes visible again.
  The price row and footer observe currency/host changes (spec allows this improvement).
- Converter: amounts, unit and premium are not persisted; only the currency (via
  `CurrencyRepository.set`, which also updates the widget). The currency is read once at creation.
  Text fields keep their own cursor; the ViewModel bumps a revision when it rewrites a field.
  A +/- `IconButton` next to the premium field negates it (`TogglePremiumSign`), since the
  decimal keyboard may lack a minus key. The currency sheet is a `selectableGroup` of radio rows.
- Mempool settings: radio-card group, Tor hint, custom URL + save (snackbar `mempoolSaved`, 4 s),
  connection test with classified result, "Aktive Quelle" footer.
  The UI is seeded synchronously from `MempoolConfig`; a late load never overwrites user edits.
- Persisting writes (`CurrencyRepository.set`, `MempoolConfig.setMode` + `clearLastDashboard`) run in
  the `@ApplicationScope` scope so popping the screen cannot cancel them half-way.

Not ported: `PosScreen` (dead code, spec 13 §2.6). Home tiles, the currency dialog and the tour
steps (§2.4, §2.5, §2.7) belong to `:feature:home` / `:feature:settings`; they use
`CurrencyFormat`, `BtcFormat.converterTile` and `MempoolService.lastDashboard` directly.

Native-only strings (`res/values{,-en,-es}`): `native_bitcoin_status_*` (status-dot
descriptions), `native_bitcoin_toggle_unit`, `native_bitcoin_toggle_sign` (premium +/- button: Compose has no
signed number keyboard, so the minus key may be missing on some keyboards).
Hard-coded labels (`BLOCK`, `FEES  L·M·H`, …) are in `ui/BitcoinLabels`, as in Flutter.

## Dependencies

`:data:mempool` (plus the feature convention: core common/ui/l10n/navigation).

## Tests

```
tools/gradlew-locked :feature:bitcoin:testDebugUnitTest
```

63 tests: ViewModel tests with fakes (dashboard load/refresh/visibility/glow, converter math and
revisions, settings select/save/test classification) and Robolectric Compose UI tests for all
three screens.
