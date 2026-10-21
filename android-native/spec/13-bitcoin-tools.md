# 13 — Bitcoin Tools (Mempool data, Bitcoin dashboard, converter, display currency, Mempool server settings)

Porting spec for the native Android rewrite (Kotlin + Jetpack Compose + Material 3 Expressive).
Source of truth: Flutter app at commit `623bdc9` (app version `1.7.2+31`).

| Dart file (read completely) | Lines | Role |
|---|---|---|
| `lib/services/mempool.dart` | 659 | HTTP client, block height, prices, dashboard aggregation, fallback mirrors, single-flight, monotonic height |
| `lib/services/mempool_config.dart` | 160 | Host selection (clearnet / Tor onion / custom), normalization, validation, timeouts, session override |
| `lib/screens/mempool_settings_screen.dart` | 319 | "Mempool server" settings screen, including the connection test |
| `lib/screens/bitcoin_dashboard_screen.dart` | 290 | Full-screen Bitcoin dashboard |
| `lib/screens/converter_screen.dart` | 506 | Fiat ↔ sats/BTC converter with premium/discount |
| `lib/services/currency_service.dart` | 107 | App-wide display currency (Issue #66), symbols, number grouping |
| `lib/screens/pos.dart` | 13 | Dead stub "LIGHTNING / POS" (not reachable) |

Helpers and consumers read to understand behavior: `lib/services/widget_service.dart` (Bitcoin part), `lib/main.dart` (widget background callback, `CurrencyService.load()`), `lib/screens/home_screen.dart` (tiles `converter` and `btc_dashboard`, `_BtcDashboardTileContent`, settings rows, currency dialog, app reset), `lib/tours/home_tour.dart`, `lib/services/rolling_qr_service.dart`, `lib/services/coattendance_service.dart`, `lib/screens/meetup_verification.dart` (block-height consumers), `android/app/src/main/kotlin/.../MeetupWidgetProvider.kt`, `android/app/src/main/res/xml/network_security_config.xml`, `lib/theme.dart`, `lib/l10n/app_{de,en,es}.arb`, `test/mempool_web_headers_test.dart`.

There are no Nostr events, signatures, hashes, QR payloads or backup fields in this domain. Compatibility depends on four things: (a) the same SharedPreferences values for in-place upgrades, (b) the same HTTP API usage against mempool instances, (c) the same block-height contract that badges and Rolling QR rely on, and (d) the same home-screen widget data keys.

---

## 1. Overview and user-visible features

The domain gets Bitcoin network data from a configurable mempool.space-compatible instance and shows it in several places. It also provides an app-wide display-currency setting.

### 1.1 Feature list (every user-visible item)

**Bitcoin dashboard (full screen)**
1. Title "Bitcoin" (hardcoded), refresh icon button in the app bar.
2. When the screen opens it shows the cached last dataset (`lastDashboard`) right away, then fetches. If there is no cached data yet it shows a centered orange spinner.
3. Auto-refresh every 60 s while the screen exists. Pull-to-refresh is also available.
4. Status dot (10×10) next to the "last updated" time:
   - grey (`cTextTertiary`): no data object yet
   - red (`cRed`): 0 of 6 sources delivered ("dead")
   - orange (`cOrange`): 1–5 of 6 sources ("partial")
   - green (`cGreen`): 6 of 6 ("live")
   - "fresh" pulse: for 2 s after every completed load the dot is fully opaque with a glow (box shadow, dot color alpha 0.6, blur 8). Otherwise the dot color is drawn at alpha 0.55.
5. Time label `HH:mm` (24h, zero-padded, device local time) of `updatedAt`, or `--:--` (ASCII hyphens) when there is no data or the data is dead.
6. Dead-state error banner (red card): cloud-off icon. Title and body depend on `blocked` (blocked vs offline). The raw error code (e.g. `HTTP 403`, `TimeoutException`) is shown in monospace if non-empty. The button "Datenquelle ändern" opens the Mempool settings and reloads on return.
7. Partial-state note: centered orange text `"{dashPartial} ({ok}/{total})"`, e.g. `Nur teilweise geladen (4/6)`.
8. Big "BLOCK" label and block height (64 sp, orange, extra-bold, `.` thousands grouping) or `––` (two U+2013).
9. Rows (label left, value right, monospace orange value):
   - `FEES  L·M·H` (two spaces) → `low·medium·high` sat/vB, or `––` if `feeHigh <= 0`
   - `MOSCOW` → Moscow time (sats per USD formatted like a clock, `18:27`)
   - `{CUR}/BTC` (e.g. `EUR/BTC`, `CHF/BTC`) → integer price with `.` grouping, or `––`
   - `SUPPLY` → circulating BTC computed from the height, `.` grouping, or `––`
   - `HASHRATE` → `"{EH/s, 0 decimals} EH/s"` or `––`
   - `DIFFICULTY` → `"+3.7% / 1234 blk"` (sign `+` for ≥ 0), or `––` if block height is unknown
10. Lightning section header (bolt icon + `LIGHTNING` + hairline divider). Rows `KAPAZITÄT` (`"{grouped int} BTC"`), `NODES`, `CHANNELS`, each `––` when ≤ 0.
11. Footer: `"{dashSource}: {hostLabel}"`. This is the host actually in use, including a session fallback mirror, with the scheme stripped. Hosts longer than 28 chars are shortened as `first12…last12`.

**Converter ("Wechselrechner")**
12. Fiat amount field with a currency chip. The chip opens a bottom sheet with 7 currencies. The choice is persisted app-wide.
13. A decorative swap icon between the fields. It has no action.
14. Crypto amount field with a `sats`/`BTC` unit toggle chip. Toggling converts the value already in the field.
15. Bidirectional live conversion. The side edited last is the input and the other side is recalculated. `,` and `.` are both accepted as the decimal separator. Only `[0-9.,]` can be typed.
16. Premium/discount card: a manual percent field (signed decimal, any value) and a slider from −20 % to +20 % in 0.5 % steps. When the base amount is > 0 it shows the adjusted fiat, the resulting sats, and the base fiat.
17. Rate info: `1 BTC = 95.000 EUR`, `Aktualisiert: HH:mm`, `Kurs von mempool.space`. While loading it shows "Lade Kurs …".
18. Offline banner (red) when the price fetch returns nothing, with an inline "Kurs aktualisieren" retry button.
19. App-bar refresh button (tooltip `convRefresh`), disabled while loading.

**Mempool server settings**
20. Intro text. Three selectable option cards: Standard (Clearnet) `mempool.space`, Tor/Onion (official mempool.space onion), Eigene Instanz (custom).
21. Tor hint card (purple), shown only when Tor is selected.
22. Custom: URL text field (URL keyboard, no autocorrect, hint `https://mempool.mein-node.local`) and a full-width "Speichern" button. IME submit also saves. On success a snackbar "Gespeichert" appears and the field is rewritten to the normalized host. An invalid URL shows a red hint "Das sieht nicht nach einer gültigen Adresse aus."
23. "Verbindung testen" button: spinner and "Teste …" while running. Shows a green (`"Verbindung steht · Block 912345"`) or red result hint with a classified error.
24. Footer "Aktive Quelle:\n{host}" (monospace, 11 sp).

**App-wide display currency** (dialog in Home → Settings → APP section)
25. Row "Währung" with subtitle `"EUR · Euro"`. The dialog lists the 7 currencies with symbol, `code · name`, and a check on the selected one. A tap persists the choice and closes the dialog. The choice applies to the converter, both home tiles, the dashboard price row and the home-screen widget, which is updated immediately.

**Home tiles** (rendered by the home domain, content defined here)
26. `btc_dashboard` tile (span 2): Bitcoin logo watermark, "Bitcoin" / "Netzwerk & Kurs", price in the selected currency (`95.000 €`), a status dot (green when data is not dead), `BLOCK` and the height. It refreshes itself on creation and every 60 s, and it pushes data to the home-screen widget on every refresh. A tap opens the dashboard.
27. `converter` tile (span 1): label "Umrechnen", value `1 € = 1.053 sats` (or "Rechner" while no price is known), sub "Kurs & Sats". A tap opens the converter.

**Background and invisible behavior**
28. Automatic fallback to public mirrors (`mempool.emzy.de`, `mempool.ninja`) when mempool.space delivers nothing. This only happens in Clearnet mode and lasts only for the session. It is never used in Tor or custom mode (privacy).
29. Monotonic block height: the dashboard never shows a lower height than one seen before, even across processes or isolates.
30. Last-good-value retention: a failed source keeps its previous value instead of blanking the tile.
31. Block-height service for other domains: Rolling QR, co-attendance, badge scan.
32. Home-screen widget data feed (keys `block`, `priceFiat`, `priceSymbol`, `priceEur`, `moscow`, `fees`, `hashrate`, `supply`, `difficulty`, `updated`).
33. Diagnostic log lines for every failure (tag `Mempool`), with noise suppression for repeated network errors.

**Not present in this domain** (explicitly checked): no haptics, no share actions, no confirmation dialogs, no clipboard actions, no notifications.

**Dead code:** `PosScreen` (`lib/screens/pos.dart`) is not referenced anywhere. Do not port it, or port it only as an unreachable stub (see §2.6).

---

## 2. Screens & UX flows

Theme constants referenced below come from `lib/theme.dart`: `cDark #0C0C0E` (scaffold), `cCard #131315`, `cSurface #0F0F11`, `cOrange #F7931A`, `cPurple #9B45E8`, `cGreen #22C55E`, `cRed #E53935`, `cCyan #D4A017` (warm gold), `cText #FFFFFF`, `cTextSecondary #9CA0A8`, `cTextTertiary #868A94`, `cBorder #1C1C22`, `cTileBorder #202028`, `kTileRadius = 10`, `fontMono` = Google Fonts **Inconsolata**. Map these onto the M3 color scheme or extended colors from the theme domain.

### 2.1 BitcoinDashboardScreen

- **Purpose:** full network overview in a "Blockclock" style.
- **Entry points (no arguments):**
  1. Home tile `btc_dashboard` tap → push.
  2. Home-screen widget Bitcoin area (`widget_btc_area`). `WidgetRouterActivity` stores target `"bitcoin"`, and Home polls `getLaunchTarget` over MethodChannel `einundzwanzig/widget` and pushes the dashboard. Natively: deep link or intent extra `open=bitcoin` → navigate to the dashboard route.
- **State:** `data: BitcoinDashboardData?` (initialized from `MempoolService.lastDashboard`), `loading = true`, `fresh = false`, a 60 s periodic timer.
- **load():** `d = await getDashboardData()`. Then set `data = d`, `loading = false`, `fresh = true`, and after 2 s set `fresh = false`. `getDashboardData()` never throws.
- **Layout (top to bottom), list padding `(24, 8, 24, 40)`:**
  1. App bar: title `Bitcoin` (hardcoded, 16 sp w700), action IconButton `refresh_rounded` (20 dp, `cTextSecondary`) → `load()`. It has no tooltip; add a content description natively (suggestion: reuse `convRefresh` or add a new key).
  2. If `data == null && loading`: full-screen centered `CircularProgressIndicator(color: cOrange)` (M3 Expressive: `LoadingIndicator`). Nothing else is shown.
  3. Otherwise a pull-to-refresh container (indicator orange on `cCard`) whose `onRefresh` awaits `load()`, wrapping a scrollable column:
     - Centered row: status dot (10 dp, see §1 item 4) + 8 dp + time label (monospace 14 sp, `cTextSecondary`), `HH:mm` if `data != null && !data.isDead`, else `--:--`.
     - If `data != null && data.isDead`: 20 dp gap + **error banner** (padding 14, `cRed` α0.10 background, `cRed` α0.35 0.8 dp border, radius 10):
       - Row: `cloud_off_rounded` (18, red) + title (`dashBlockedTitle` if `data.blocked` else `dashOfflineTitle`, 14 sp w700 `cText`)
       - Body (`dashBlockedBody` / `dashOfflineBody`, 12 sp, line height 1.4)
       - If `data.lastError` is not empty: 6 dp + `lastError` in monospace 11 sp `cTextTertiary`
       - 10 dp + left-aligned text button with `dns_rounded` icon (16) and label `dashChangeServer` (12 sp w600, orange). On tap: push `MempoolSettingsScreen`, await the pop, then `load()` if still mounted.
     - Else if `data != null && !data.isLive`: 12 dp + centered orange 12 sp text `"${dashPartial} (${sourcesOk}/${sourcesTotal})"`.
     - 28 dp gap. Centered `BLOCK` (13 sp, letterSpacing 3, w600, secondary). 8 dp. Centered block height: `fmtInt(blockHeight)` if `> 0` else `––`, 64 sp w800 orange, letterSpacing 1, line height 1.0. 32 dp gap.
     - If `data != null`, the rows. Each row has vertical padding 11. Label: 13 sp, letterSpacing 1.5, w600, secondary. Value: 20 sp w800 orange monospace.

       | Label (hardcoded) | Value |
       |---|---|
       | `FEES  L·M·H` | `feeHigh > 0` ? `"$feeLow·$feeMedium·$feeHigh"` (U+00B7) : `––` |
       | `MOSCOW` | `data.moscowTime` (see §8.6, may be `--:--`) |
       | `"${currency}/BTC"` (currency = `CurrencyService.current` read at build) | `priceIn(cur) > 0` ? `fmtInt(dartRound(priceIn(cur)))` : `––` |
       | `SUPPLY` | `supply > 0` ? `fmtInt(supply)` : `––` |
       | `HASHRATE` | `hashrateEhs > 0` ? `"${toStringAsFixed(hashrateEhs,0)} EH/s"` : `––` |
       | `DIFFICULTY` | `blockHeight > 0` ? `"${pct >= 0 ? '+' : ''}${toStringAsFixed(pct,1)}% / ${remainingBlocks} blk"` : `––` |

       Then 20 dp. Header row: `bolt_rounded` (16, orange), 6 dp, `LIGHTNING` (12 sp, letterSpacing 2, w700, orange), 10 dp, an expanded 0.5 dp line in `cTileBorder`. 8 dp. Rows:

       | Label | Value |
       |---|---|
       | `KAPAZITÄT` (U+00C4) | `lnCapacityBtc > 0` ? `"${fmtInt(dartRound(lnCapacityBtc))} BTC"` : `––` |
       | `NODES` | `lnNodeCount > 0` ? `fmtInt(lnNodeCount)` : `––` |
       | `CHANNELS` | `lnChannelCount > 0` ? `fmtInt(lnChannelCount)` : `––` |

     - 24 dp. Centered footer `"${dashSource}: ${hostLabel()}"` (11 sp, tertiary). `hostLabel` is defined in §8.10.
- **Notes:**
  - The dashboard does **not** observe currency changes. The price-row currency is re-read only on the next rebuild. Natively, observing the `StateFlow` is fine (a harmless improvement).
  - The difficulty row's guard is `blockHeight > 0`, not difficulty availability. If difficulty never loaded and nothing is cached, it shows `+0.0% / 0 blk`.
  - Dead data still carries the previous values (fees, prices, etc. come from the in-memory `lastDashboard`, and the height from the persisted max). The rows therefore show stale values under the red banner while the time shows `--:--`.
- **Localized keys:** `dashPartial`, `dashOfflineTitle`, `dashOfflineBody`, `dashBlockedTitle`, `dashBlockedBody`, `dashChangeServer`, `dashSource`. All other labels are hardcoded (see §14). Strings appear in §2.8.
- **Tour/guide:** none on the screen itself (the Home tour targets the tile, see §2.7).

### 2.2 ConverterScreen

- **Purpose:** convert fiat ↔ satoshi/bitcoin at the mempool price, with an optional trading premium/discount.
- **Entry point (no arguments):** Home tile `converter` tap → push.
- **State:**
  - `currency: String` = `CurrencyService.current.value` at creation. It does not observe later changes.
  - `cryptoIsBtc = false` (false = sats).
  - `fiatIsInput = true` (the side edited last).
  - `prices: Map<String, Double> = {}`, `loading = true`, `error = false`, `updated: DateTime? = null`.
  - `premiumPercent = 0.0`, plus three text controllers: fiat, crypto, premium.
- **On create:** `loadPrices()`.
- **loadPrices():** set `loading = true, error = false`. Then `p = await MempoolService.getPrices()`. This uses the **currently configured host including any session override, with no fallback loop, and without using `lastDashboard`**.
  - If `p` is empty: `loading = false, error = true`. `prices` keeps its old value, so after an earlier success the rate info still shows.
  - Else: `prices = p, loading = false, updated = now`, then `recalculate()`.
- **btcPrice** = `prices[currency] ?? 0`.
- **Layout:** scroll column, padding 20, stretch:
  1. App bar title `convTitle`. Action IconButton `refresh_rounded` with tooltip `convRefresh`, disabled while `loading`, → `loadPrices()`.
  2. If `error`: **offline banner** (margin bottom 16, padding 12, red α0.1 background, red α0.3 0.5 dp border, radius 10). Row: `cloud_off_rounded` 18 red, 10 dp, expanded text `convOffline` (12 sp red), and a TextButton `convRefresh` (orange) → `loadPrices()`.
  3. **Fiat field card** (`cCard`, `cTileBorder` 0.5 dp, radius 10, padding h16 v14). Label `convYouPay.uppercase()` (10 sp w700, letterSpacing 1.2, tertiary). Below it a row with:
     - an expanded borderless TextField: decimal keyboard, input filter `[0-9.,]` (allow-list per character), 26 sp w700, hint `0`, `onChanged → onFiatChanged`
     - 10 dp gap, then the **currency chip** (`cSurface`, border, radius 10, padding h12 v8): currency code (15 sp w700) + `expand_more_rounded` (18). A tap opens the currency sheet.
  4. Vertical padding 8, centered 38 dp circle (`cCard`, `cTileBorder` border) with `swap_vert_rounded` (20, orange). **Decorative only, no action.**
  5. **Crypto field card**, same style. The label is `convUnitBtc` or `convUnitSats`, uppercased. The trailing **unit toggle chip** (orange α0.12 background, orange α0.4 border) shows `BTC` or `sats` + `swap_horiz_rounded` (16). A tap calls `swapUnit()`.
  6. 20 dp. **Premium card** (§2.2.1). 20 dp. **Rate info** (§2.2.2).
- **onFiatChanged:** `fiatIsInput = true`, `recalculate()`.
- **onCryptoChanged:** `fiatIsInput = false`, `recalculate()`.
- **recalculate()** (exact, see §8.8): returns immediately if `btcPrice <= 0`. That means text typed before prices load is converted only when `loadPrices` succeeds.
- **swapUnit():** toggle `cryptoIsBtc`. If the crypto text parses: switching to BTC sets it to `toStringAsFixed(raw / 1e8, 8)`, switching to sats sets it to `dartRound(raw * 1e8).toString()`. The fiat field and `fiatIsInput` are unchanged and no recalc happens.
- **Currency bottom sheet:** modal, `cDark` background, top radius 20, scroll-controlled, max height 80 % of the screen, inside SafeArea.
  - Content: 12 dp, drag handle 40×4 (tertiary, radius 2), 16 dp, title `convSelectCurrency` (16 sp w700), 12 dp.
  - One ListTile per currency in `supportedCurrencies` order (EUR, USD, GBP, CHF, CAD, AUD, JPY). The selected one is orange w700 with `check_rounded`.
  - On tap: close the sheet, set `currency = cur`, call `CurrencyService.set(cur)` (persist + widget update), then `recalculate()`. The direction is preserved: if fiat was the input, the fiat value stays and the crypto side is recomputed with the new price, and vice versa.
  - M3: use `ModalBottomSheet` with `ListItem` + trailing check.

#### 2.2.1 Premium/discount card
`cCard`, `cTileBorder` 0.5, radius 10, padding 16:
- Row: `trending_up_rounded` (16, orange), 8 dp, expanded `convPremiumTitle` (14 sp w700, 1 line, ellipsis), 8 dp, then an 82 dp wide box (`cSurface`, radius 8, border) holding a TextField:
  - controller `premiumCtrl`, keyboard number with `signed: true, decimal: true`, **no input filter**
  - centered, orange 15 sp w800, hint `0,0`, suffix `%` (orange w700 13 sp), no border, dense, content padding v8 h6
  - `onChanged(v)`: `premiumPercent = tryParseDouble(v.replace(',', '.')) ?: 0.0`
- 4 dp, then hint `convPremiumHint` (11 sp tertiary). 8 dp.
- Slider: `value = premiumPercent.coerceIn(-20.0, 20.0)`, min −20, max 20, **divisions 80** (Compose: `steps = 79`). The value-indicator label is `"${if (p > 0) "+" else ""}${toStringAsFixed(p, 1)}%"`, which uses the unclamped `premiumPercent`. Active track orange, inactive `cSurface`, thumb orange, overlay orange α0.15, track height 3.
  - `onChanged(v)`: `premiumPercent = dartRound(v * 2) / 2.0`. `premiumCtrl.text = if (premiumPercent == 0.0) "" else dartDoubleToString(premiumPercent)` (e.g. `2.5`, `-0.5`, `3.0`, `20.0`).
- Axis labels row (spaceBetween, 10 sp tertiary): `−20%` (U+2212 minus), `0`, `+20%`.
- Only if `base > 0` (`base = currentFiat`):
  - 14 dp, 0.5 dp divider, 14 dp.
  - Row: `convPremiumResult` (13 sp secondary) ↔ `"${toStringAsFixed(withP,2)} ${currency}"` (20 sp w800 orange).
  - 8 dp. Row: `convPremiumSats` ↔ `"${fmtSats(satsWithP)} sats"` (16 sp w700 `cText`).
  - 6 dp. Row: `convPremiumBase` (11 sp tertiary) ↔ `"${toStringAsFixed(base,2)} ${currency}"` (12 sp tertiary).
- `currentFiat`, `withP` and `satsWithP` are defined in §8.8. With a premium below −100 % the result becomes negative and `fmtSats` prints a leading `-`.

#### 2.2.2 Rate info
- If `loading`: centered, padding 8, `convLoading` (13 sp secondary).
- Else if `btcPrice <= 0`: nothing.
- Else, a centered column:
  - `convRateInfo(groupInt(dartRound(btcPrice)), currency)` → `"1 BTC = 95.000 EUR"`. It uses the **code**, not the symbol. 13 sp w600 secondary.
  - 4 dp, then, if `updated != null`, `convUpdated(HH:mm)` (11 sp tertiary).
  - 2 dp, then `convSource` (11 sp tertiary).

- **Localized keys:** `convTitle`, `convRefresh`, `convOffline`, `convYouPay`, `convUnitSats`, `convUnitBtc`, `convSelectCurrency`, `convPremiumTitle`, `convPremiumHint`, `convPremiumResult`, `convPremiumSats`, `convPremiumBase`, `convLoading`, `convRateInfo{price,cur}`, `convUpdated{time}`, `convSource`. `convSwap` exists in the ARB but is **unused**. Hardcoded: `sats`, `BTC`, `0`, `0,0`, `%`, `−20%`, `0`, `+20%`, ` sats`.
- **No persistence** of entered amounts, unit toggle or premium. These reset every time the screen opens. Only the currency persists.

### 2.3 MempoolSettingsScreen

- **Purpose:** choose the data source for all mempool calls.
- **Entry points (no arguments):**
  1. Home → Settings bottom sheet → section `settingsSecNetwork` ("NETZWERK") → row (`dns_rounded`, orange) `settingsMempool` / `settingsMempoolSub`. The tap closes the settings sheet and pushes this screen.
  2. Dashboard error banner button `dashChangeServer` (the dashboard reloads when this screen pops).
- **State:** `mode = CLEARNET`, `customText`, `testing = false`, `testResult = ""`, `testOk = false`.
- **On create:** `await MempoolConfig.ensureLoaded()`, then `mode = MempoolConfig.mode`. If `mode == CUSTOM`, `customText = MempoolConfig.host`. Caveat: `host` includes the session override, see the bug in §14.
- **selectedHost:** CLEARNET → `clearnetHost`. TOR → `torHost`. CUSTOM → `normalize(customText)`. With an empty field this normalizes to `https://mempool.space`, so the test then hits mempool.space.
- **select(m)** (option card tap):
  1. `mode = m`, `testResult = ""` (UI updates immediately).
  2. If `m == CUSTOM && !looksValid(customText)`, stop here. Nothing is persisted and the UI just shows the custom field.
  3. Otherwise `await MempoolConfig.setMode(m, custom = customText)`, then `MempoolService.lastDashboard = null` (old values belong to the old source).
  - Quirk: `looksValid("")` is **true**, because `""` normalizes to the clearnet host. Selecting "Eigene Instanz" with an empty field therefore persists `https://mempool.space`, and `mode` then reports CLEARNET on the next open.
- **saveCustom()** (Save button or IME action):
  - If `!looksValid(customText)`: `testOk = false`, `testResult = mempoolInvalidUrl`. The text appears in the red result hint.
  - Else: `setMode(CUSTOM, customText)`, `lastDashboard = null`, `customText = MempoolConfig.host` (normalized), `testResult = ""`, and a snackbar `mempoolSaved` (background `cCard`).
- **test():**
  - Set `testing = true`, `testResult = ""`, `testOk = false`. `host = selectedHost`. The host does **not** need to be saved first.
  - `height = MempoolService.testHost(host)`. On success: `testOk = true`, `testResult = "${mempoolTestOk} · Block $height"`, where the height is **not** grouped and the separator is U+00B7.
  - On exception:
    - `MempoolHttpException` with `looksBlocked` → `"${mempoolTestBlocked} (HTTP ${code})"`
    - `MempoolHttpException` otherwise → `"${mempoolTestFail} — HTTP ${code}"` (U+2014 em dash, with spaces)
    - other exception and `host.contains(".onion")` → `mempoolTestOnionFail`
    - other exception → `"${mempoolTestFail} — ${exceptionTypeName}"`. Dart prints e.g. `TimeoutException`, `ClientException`, `SocketException`. Natively use the Kotlin simple class name, e.g. `SocketTimeoutException`, `UnknownHostException`.
  - Finally `testing = false`.
- **Layout:** list padding `(16, 8, 16, 40)`.
  1. App bar title `mempoolTitle` (16 sp w700).
  2. `mempoolIntro` (13 sp secondary, line height 1.45). 20 dp.
  3. Three option cards (margin bottom 10, `cCard`, radius 12 = `kTileRadius + 2`). Border: orange 1.4 dp when selected, `cBorder` 0.5 dp otherwise. Each card is a ListTile with:
     - leading 38 dp circle filled with the accent at α0.12 and the icon (19 dp) in the accent
     - title 14 sp (w700 selected / w500), subtitle 12 sp secondary
     - trailing `check_circle_rounded` orange (selected) or `circle_outlined` tertiary, 20 dp

     | Mode | Icon | Accent | Title | Subtitle |
     |---|---|---|---|---|
     | CLEARNET | `public_rounded` | orange | `mempoolClearnetTitle` | literal `mempool.space` |
     | TOR | `shield_rounded` | purple | `mempoolTorTitle` | `mempoolTorSub` |
     | CUSTOM | `dns_rounded` | green | `mempoolCustomTitle` | `mempoolCustomSub` |

     M3: a radio-card list (`ListItem` + `RadioButton` semantics, `Modifier.selectableGroup()`).
  4. If TOR: 12 dp + hint card (`info_outline_rounded`, purple, `mempoolTorHint`).
  5. If CUSTOM: 14 dp + TextField (URL keyboard, autocorrect off, filled `cCard`, hint `https://mempool.mein-node.local`, `onSubmitted → saveCustom`). 10 dp, full-width filled button `mempoolSave` → `saveCustom`.
  6. 22 dp, full-width outlined button. While testing it is disabled and shows a 14 dp spinner (stroke 2, orange) and `mempoolTesting`. Otherwise it shows `network_check_rounded` (18) and `mempoolTest`.
  7. If `testResult` is not empty: 12 dp + hint card. OK uses `check_circle_rounded` in green; failure uses `error_outline_rounded` in red. The text is `testResult`.
  8. 24 dp + `"${mempoolActive}:\n${MempoolConfig.host}"` (monospace 11 sp tertiary, line height 1.5). It is recomputed on every rebuild.
- **Hint card style:** padding 12, accent α0.09 background, accent α0.3 0.7 dp border, radius 10. Row: icon 17 dp in the accent, 9 dp, expanded text 12 sp secondary, line height 1.4.
- **Localized keys:** `mempoolTitle`, `mempoolIntro`, `mempoolClearnetTitle`, `mempoolTorTitle`, `mempoolTorSub`, `mempoolTorHint`, `mempoolCustomTitle`, `mempoolCustomSub`, `mempoolSave`, `mempoolSaved`, `mempoolInvalidUrl`, `mempoolTest`, `mempoolTesting`, `mempoolTestOk`, `mempoolTestFail`, `mempoolTestBlocked`, `mempoolTestOnionFail`, `mempoolActive`. Settings entry row: `settingsSecNetwork`, `settingsMempool`, `settingsMempoolSub`.

### 2.4 Display-currency setting (Home settings sheet → section `settingsSecApp`)

This UI is owned by the home/settings domain; its logic and API come from this domain.
- **Row:** icon `currency_exchange_rounded` (green), title `settingsCurrencyTitle`, subtitle `"$cur · ${currencyName(cur)}"`, trailing chevron. It observes `CurrencyService.current`. A tap opens the dialog.
- **Dialog:** `cCard` background, radius 16, max height 80 %, scrollable. It observes `current`.
  - Header (padding 20/20/20/4): `currency_exchange_rounded` (20, orange) + `convSelectCurrency` (16 sp w700).
  - Hint (padding 20/0/20/10): `settingsCurrencyHint` (12 sp secondary). Then a divider.
  - Options for each code in `supported`, each with padding h20 v14 and an orange α0.08 background when selected:
    - a 44 dp wide symbol column (orange w700 when selected, else secondary, 16 sp)
    - 8 dp, then expanded `"$code · ${currencyName(code)}"` (15 sp, orange w700 when selected)
    - `check_circle_rounded` when selected
    - On tap: `await CurrencyService.set(code)`, then close the dialog.
  - 8 dp bottom.
- **currencyName:** `EUR→curEUR`, `USD→curUSD`, `CHF→curCHF`, `GBP→curGBP`, `CAD→curCAD`, `AUD→curAUD`, `JPY→curJPY`, default → the code itself.

### 2.5 Home tiles (content contract; the grid itself belongs to the home domain)

Tile ids, default order and visibility (both are visible by default, i.e. not in `_defaultHidden`):
- `_defaultOrder` = `['home_meetup','event_chats','reputation','trust_network','community','nostr','converter','btc_dashboard','news','portal','events','shoutout','podcast','satoshiduell','portal_area','plebrap','organisator']`
- `_TileDef(id: 'converter', label: 'Rechner', span: 1)` and `_TileDef(id: 'btc_dashboard', label: 'Bitcoin', span: 2)`.

**`btc_dashboard` tile** (`_BtcDashboardTileContent`, accent orange, background opacity 0.07):
- On create: `d = lastDashboard`, `load()`, and a periodic 60 s `load()`.
- `load()`: `d = await getDashboardData()`, update the UI, **then always `WidgetService.updateBitcoin(d)`**. This happens even when `d.isDead`; see §14.
- Layout: a stack.
  - Background: `assets/icons/bitcoin.svg` 104×104 at opacity 0.10, right −18, vertically centered.
  - Left column: SVG logo 22 + 8 dp + `Bitcoin` (16 sp w700, hardcoded), 4 dp, `Netzwerk & Kurs` (13 sp secondary, **hardcoded German**). If `d != null` and `priceIn(cur) > 0`: 8 dp + `CurrencyService.formatPrice(p, cur)` (13 sp w600 secondary). This part observes currency changes.
  - Right column, right-aligned: a row with a 7 dp dot (green α0.7 if `d != null && !d.isDead`, else tertiary), 6 dp, and `BLOCK` (10 sp tertiary, letterSpacing 2). 4 dp. Below: `fmtInt(height)` or `––` (26 sp w800 orange monospace, line height 1.0).
- Tap → push the dashboard.

**`converter` tile** (accent `cCyan` = warm gold, opacity 0.07, watermark `swap_vert_rounded`):
- `cur = current`, `price = lastDashboard?.priceIn(cur) ?: 0`, `satsPerUnit = price > 0 ? dartRound(1e8 / price) : 0`.
- The hero content has icon `swap_vert_rounded`, label `tileActConvert` (shown uppercase), value size 17 and sub `tileConverterSub`. The value is:
  - `"1 ${symbol(cur)} = ${groupInt(satsPerUnit)} sats"` if `satsPerUnit > 0`
  - `tileConverter` otherwise
- The tile does **not** fetch on its own. It depends on `lastDashboard`, which the `btc_dashboard` tile or the dashboard fills. It redraws on a currency change through a listener on `CurrencyService.current`.
- Tap → push the converter.

### 2.6 PosScreen (dead code)
`Scaffold` with app bar `LIGHTNING / POS` (no back button, `automaticallyImplyLeading: false`) and a centered `21.000 SATS` (40 sp w900 orange). Nothing references it. Recommendation: **do not port**.

### 2.7 Tours / guide hooks
The Home tour (`lib/tours/home_tour.dart`) targets the tiles, not the screens:
- Step 11: `umrechnerKey` (`guide_umrechner`) → `guideHomeUmrechnerTitle` / `guideHomeUmrechnerBody`, `waitTimeout: 220 ms` (optional tile).
- Step 12: `bitcoinKey` (`guide_bitcoin`) → `guideHomeBitcoinTitle` / `guideHomeBitcoinBody`, `waitTimeout: 220 ms`.
- Mapping in home: `'btc_dashboard': HomeTour.bitcoinKey`, `'converter': HomeTour.umrechnerKey`. The tour domain owns this. This domain only needs to expose stable tile ids.

### 2.8 String table (DE source, EN for reference; ES exists in `app_es.arb` for every key)

| Key | DE (`app_de.arb`) | EN |
|---|---|---|
| `settingsSecNetwork` | NETZWERK | NETWORK |
| `settingsMempool` | Mempool-Server | Mempool server |
| `settingsMempoolSub` | Quelle der Bitcoin-Daten | Source of the Bitcoin data |
| `mempoolTitle` | Mempool-Server | Mempool server |
| `mempoolIntro` | Von hier holt die App Blockhöhe, Gebühren, Kurs und Lightning-Daten. Standard ist mempool.space. Wer über Tor surft, sollte die Onion-Adresse wählen — mempool.space weist Anfragen von Tor-Exit-Knoten oft ab. | This is where the app fetches … |
| `mempoolClearnetTitle` | Standard (Clearnet) | Default (clearnet) |
| `mempoolTorTitle` | Tor / Onion | Tor / onion |
| `mempoolTorSub` | Offizielle .onion von mempool.space | Official .onion of mempool.space |
| `mempoolTorHint` | Funktioniert nur, wenn Orbot im VPN-Modus läuft und diese App einschließt. Ohne Orbot ist eine .onion-Adresse nicht erreichbar. Tor ist langsamer — die Daten brauchen etwas länger. | Only works while Orbot is running in VPN mode … |
| `mempoolCustomTitle` | Eigene Instanz | Your own instance |
| `mempoolCustomSub` | Eigener Node (Umbrel, Start9, RaspiBlitz …) | Your own node (Umbrel, Start9, RaspiBlitz …) |
| `mempoolSave` / `mempoolSaved` | Speichern / Gespeichert | Save / Saved |
| `mempoolInvalidUrl` | Das sieht nicht nach einer gültigen Adresse aus. | That does not look like a valid address. |
| `mempoolTest` / `mempoolTesting` | Verbindung testen / Teste … | Test connection / Testing … |
| `mempoolTestOk` / `mempoolTestFail` | Verbindung steht / Keine Verbindung | Connection works / No connection |
| `mempoolTestBlocked` | Der Server weist die Anfrage ab. Bei Tor: Onion-Adresse wählen. | The server is rejecting the request. On Tor: choose the onion address. |
| `mempoolTestOnionFail` | Onion nicht erreichbar. Läuft Orbot im VPN-Modus und ist diese App eingeschlossen? | Onion not reachable. Is Orbot running in VPN mode with this app included? |
| `mempoolActive` | Aktive Quelle | Active source |
| `dashSource` | Daten | Data |
| `dashPartial` | Nur teilweise geladen | Only partially loaded |
| `dashOfflineTitle` / `dashOfflineBody` | Keine Verbindung / Es konnten keine Daten geladen werden. Prüfe deine Internetverbindung — oder wähle eine andere Datenquelle. | No connection / No data could be loaded. … |
| `dashBlockedTitle` / `dashBlockedBody` | Server weist Anfragen ab / mempool.space blockt diese IP-Adresse. Das passiert typischerweise über Tor, weil sich viele Nutzer einen Exit-Knoten teilen. Abhilfe: Onion-Adresse oder eigene Instanz verwenden. | Server is rejecting requests / … |
| `dashChangeServer` | Datenquelle ändern | Change data source |
| `tileConverter` / `tileConverterSub` | Rechner / Kurs & Sats | Converter / Rate & sats |
| `tileActConvert` | Umrechnen | Convert |
| `convTitle` | Wechselrechner | Converter |
| `convYouPay` | Betrag | Amount |
| `convRateInfo` | 1 BTC = {price} {cur} | 1 BTC = {price} {cur} |
| `convUpdated` | Aktualisiert: {time} | Updated: {time} |
| `convRefresh` | Kurs aktualisieren | Refresh rate |
| `convOffline` | Kurs konnte nicht geladen werden. Bist du online? | Could not load rate. Are you online? |
| `convLoading` | Lade Kurs … | Loading rate … |
| `convSwap` (unused) | Tauschen | Swap |
| `convSelectCurrency` | Währung wählen | Select currency |
| `convUnitSats` / `convUnitBtc` | Satoshi / Bitcoin | Satoshi / Bitcoin |
| `convSource` | Kurs von mempool.space | Rate from mempool.space |
| `convPremiumTitle` | Auf-/Abschlag | Premium / Discount |
| `convPremiumHint` | Für Trades: Prozent-Aufschlag (+) oder Abschlag (−) auf den Kurs. | For trades: percentage premium (+) or discount (−) on the rate. |
| `convPremiumResult` | Mit Auf-/Abschlag | With premium/discount |
| `convPremiumBase` | Basiskurs | Base rate |
| `convPremiumSats` | Ergebnis in Sats | Result in sats |
| `settingsCurrencyTitle` | Währung | Currency |
| `settingsCurrencyHint` | Gilt für Umrechner, Startseite, Bitcoin-Dashboard und Widget. Kurse von mempool.space. | Applies to the converter, home screen, Bitcoin dashboard and widget. Rates from mempool.space. |
| `curEUR` … `curJPY` | Euro, US-Dollar, Schweizer Franken, Britisches Pfund, Kanadischer Dollar, Australischer Dollar, Japanischer Yen | Euro, US dollar, Swiss franc, British pound, Canadian dollar, Australian dollar, Japanese yen |
| `guideHomeUmrechnerTitle` / `Body` | Umrechner / Schnell zwischen EUR und Sats umrechnen. | Converter / Quickly convert between EUR and sats. |
| `guideHomeBitcoinTitle` / `Body` | Bitcoin Kurs / Aktueller Preis, Netzwerk-Stats und Blockhöhe. | Bitcoin Price / Current price, network stats and block height. |

Placeholders: `convRateInfo` has `{price}` and `{cur}` (both plain strings). `convUpdated` has `{time}`. In Android resources use `%1$s` / `%2$s`.

---

## 3. Data models

None of the models in this domain is serialized to JSON or persisted except as noted in §4. All are in-memory.

### 3.1 `MempoolMode`
`enum MempoolMode { clearnet, tor, custom }`. It is **derived** from the persisted host and never stored itself:
```dart
static MempoolMode get mode {
  if (_host == clearnetHost) return MempoolMode.clearnet;
  if (_host == torHost) return MempoolMode.tor;
  return MempoolMode.custom;
}
```
`_host` is the **persisted** host. The session override is ignored here, which matters for the fallback logic.

### 3.2 `MempoolHttpException`
| Field | Type | Notes |
|---|---|---|
| `path` | String | API path such as `/blocks/tip/height` (without `/api`) |
| `statusCode` | int | HTTP status. `200` is used for the "not a number" case in `testHost` |
| `snippet` | String | Derived from the body (see below) |

```dart
static String _snip(String body) {
  final flat = body.replaceAll(RegExp(r'\s+'), ' ').trim();
  return flat.length > 120 ? '${flat.substring(0, 120)}…' : flat;   // U+2026
}
bool get looksBlocked =>
    statusCode == 403 || statusCode == 429 || statusCode == 503 ||
    snippet.toLowerCase().contains('cloudflare') ||
    snippet.toLowerCase().contains('<!doctype html');
@override String toString() => 'HTTP $statusCode bei $path — $snippet';
```
Note that `looksBlocked` inspects only the first 120 chars of the collapsed body. Kotlin: `\s+` in Dart matches Unicode whitespace similar to Java's `\s` (ASCII only by default). Use `Regex("\\s+")`, which is enough for HTML bodies. Use `String.length` in UTF-16 units, the same as Dart.

### 3.3 `BitcoinDashboardData`
| Field | Dart type | Default | Meaning |
|---|---|---|---|
| `blockHeight` | int | required | tip height (monotonic, see §8.3) |
| `feeLow` | int | required | sat/vB, `economyFee` (fallback `minimumFee`) |
| `feeMedium` | int | required | sat/vB, `halfHourFee` |
| `feeHigh` | int | required | sat/vB, `fastestFee` |
| `priceEur` | double | required | BTC/EUR |
| `priceUsd` | double | required | BTC/USD (for Moscow time) |
| `prices` | Map<String,double> | `{}` | BTC price per supported currency |
| `supply` | int | required | computed whole BTC in circulation |
| `hashrateEhs` | double | required | EH/s |
| `difficultyChangePct` | double | required | e.g. −3.7 |
| `difficultyRemainingBlocks` | int | required | blocks until retarget |
| `lnCapacityBtc` | double | required | Lightning capacity in BTC |
| `lnNodeCount` | int | required | |
| `lnChannelCount` | int | required | |
| `updatedAt` | DateTime | required | `DateTime.now()` at the end of the fetch (local time) |
| `sourcesOk` | int | `0` | sources that succeeded in this run |
| `sourcesTotal` | int | `6` | always 6 |
| `lastError` | String | `''` | last failure's short code: `"HTTP 403"` or the exception type name |
| `blocked` | bool | `false` | any failure looked like WAF/rate-limit blocking |

Derived:
```dart
bool get isLive => sourcesOk == sourcesTotal;
bool get isDead => sourcesOk == 0;
double priceIn(String code) {
  final p = prices[code];
  if (p != null && p > 0) return p;
  if (code == 'EUR') return priceEur;
  if (code == 'USD') return priceUsd;
  return 0;
}
String get moscowTime { ... }   // §8.6
```
There is no `==` override (identity equality). A Kotlin `data class` is fine.

### 3.4 Supported currencies (ordered list, used for validation and UI order)
```dart
static const List<String> supportedCurrencies = ['EUR','USD','GBP','CHF','CAD','AUD','JPY'];
```
`CurrencyService.fallback = 'EUR'`. Symbols:

| Code | Symbol |
|---|---|
| EUR | `€` (U+20AC) |
| USD | `$` |
| GBP | `£` (U+00A3) |
| JPY | `¥` (U+00A5) |
| CAD | `CA$` |
| AUD | `A$` |
| CHF and anything else | the code itself (`CHF`) |

### 3.5 Mempool API response shapes consumed (JSON keys exact)
| Endpoint | Keys read | Conversion |
|---|---|---|
| `/blocks/tip/height` | plain-text integer body | `int.tryParse(body.trim())`. Values `null` or `<= 0` are invalid |
| `/v1/prices` | `EUR`,`USD`,`GBP`,`CHF`,`CAD`,`AUD`,`JPY` (top-level numbers; `time` ignored) | only keys whose value is a JSON number are kept, `toDouble()` |
| `/v1/fees/recommended` | `economyFee` (fallback `minimumFee`), `halfHourFee`, `fastestFee` | `num?.toInt()` (truncation toward zero), missing → 0. `hourFee` is ignored |
| `/v1/mining/hashrate/3d` | `currentHashrate` (H/s) | `/ 1e18` → EH/s, missing → 0 |
| `/v1/difficulty-adjustment` | `difficultyChange` (percent), `remainingBlocks` | missing → 0.0 / 0 |
| `/v1/lightning/statistics/latest` | `latest` object (or the root object if `latest` is absent or JSON `null`) → `total_capacity` (sats), `node_count`, `channel_count`. Dart: `d['latest'] as Map<String, dynamic>? ?? d`. A `latest` value that is present but not an object (number, string, array, bool) is NOT a fallback: the cast throws, so the source fails (sentinel `[-1,-1,-1]` → previous Lightning values are kept, `_lastError` = the exception's runtime type name, e.g. `_TypeError`, and `sourcesOk` is not incremented) | capacity `/ 1e8` → BTC, missing → 0 |

A JSON root that is not an object (cast failure) counts as a failure of that source.

---

## 4. Persistence

### 4.1 Keys (exact)

| Logical key (Dart) | Type | Writer | Reader | Semantics |
|---|---|---|---|---|
| `mempool_host` | String | `MempoolConfig.setHost` (from settings screen `select`/`saveCustom`) | `MempoolConfig.ensureLoaded` (lazy, first network call or settings screen) | Normalized base URL without `/api` and without a trailing slash. Absent or blank → `https://mempool.space`. The loaded value is **re-normalized** on read. |
| `fiat_currency` | String | `CurrencyService.set` | `CurrencyService.load` (at app start, before the first frame), `CurrencyService.read` (widget path) | One of the 7 codes. Unknown or absent → `EUR`. |
| `mempool_last_height` | int (stored as **Long**) | `MempoolService._saveLastHeight` (only if strictly greater than the known value) | `MempoolService._loadLastHeight` (with `reload()` to see other isolates' writes) | Highest block height ever seen by the dashboard aggregation. Never decreases. |

None of these keys is in the encrypted backup (`backup_service.dart` does not touch them). All three are wiped by the app reset (`SharedPreferences.clear()` in `home_screen._performReset`). However, the Flutter app keeps the in-memory statics (`MempoolConfig._host`, `CurrencyService.current`, `MempoolService._lastKnownHeight`, `lastDashboard`) until the process restarts. Natively, also reset the in-memory state (see §14).

### 4.2 Physical storage in the Flutter app (needed for in-place upgrade migration)

The app uses the **legacy** `SharedPreferences.getInstance()` API (shared_preferences 2.5.4 / shared_preferences_android 2.4.20):
- File: `FlutterSharedPreferences` (`/data/data/space.einundzwanzig.meetup/shared_prefs/FlutterSharedPreferences.xml`)
- Key prefix: `flutter.`, i.e. `flutter.mempool_host`, `flutter.fiat_currency`, `flutter.mempool_last_height`
- `setString` → `putString`. `setInt` → **`putLong`**, so read `mempool_last_height` with `getLong` (a `getInt` would throw `ClassCastException`).

**Migration (first native start after upgrading from Flutter, same `applicationId = space.einundzwanzig.meetup`):**
- No per-domain migration. The central `:data:migration` (spec 14 §4.7) copies these three keys, like every other `flutter.*` key, into the shared `:core:data` `KeyValueStore` (DataStore `app_prefs`) under the names without `flutter.`; `mempool_last_height` arrives as `IntValue(Long)`. Do not add a `SharedPreferencesMigration` and do not keep reading `FlutterSharedPreferences.xml`: that file is deleted at cleanup and by the app reset (spec 14 §4.7, F6), so a domain still reading it would lose or resurrect values.
- Re-validate on read, as Flutter does:
  - `mempool_host` → `normalize()`
  - `fiat_currency` → must be in the supported list, else EUR
  - `mempool_last_height` → max with the RAM value

### 4.3 Home-screen widget store (written from this domain's data; the widget domain owns the widget)
`HomeWidget.saveWidgetData` writes into the Android SharedPreferences file **`HomeWidgetPreferences`** (no key prefix). The keys and value formats written by `WidgetService.updateBitcoin(d)` are below. All are strings; `fmtInt` uses `.` grouping and `––` is U+2013 ×2.

| Key | Value |
|---|---|
| `block` | `d.blockHeight > 0 ? fmtInt(blockHeight) : '––'` |
| `priceFiat` | `p = d.priceIn(cur)` with `cur = CurrencyService.read()`. `p > 0 ? fmtInt(dartRound(p)) : '––'` |
| `priceSymbol` | `CurrencyService.symbol(cur)` |
| `priceEur` | legacy: `d.priceEur > 0 ? fmtInt(dartRound(priceEur)) : '––'` |
| `moscow` | `d.moscowTime` (`--:--` with ASCII hyphens if no USD price) |
| `fees` | `"${feeLow}·${feeMedium}·${feeHigh}"`, always written, even `0·0·0` |
| `hashrate` | `hashrateEhs > 0 ? "${toStringAsFixed(h,0)} EH/s" : '––'` |
| `supply` | `supply > 0 ? fmtInt(supply) : '––'` |
| `difficulty` | `difficultyRemainingBlocks > 0 ? "${pct>=0?'+':''}${toStringAsFixed(pct,1)}% / ${remaining}" : '––'` (**no** ` blk` suffix here, and the guard is `remainingBlocks`, not height) |
| `updated` | `HH:mm` of **now** (the native provider renders `"Stand $updated"`) |

After writing, it calls `HomeWidget.updateWidget(name: 'MeetupWidgetProvider', androidName: 'MeetupWidgetProvider')`. The native provider reads `priceFiat ?: priceEur`, `priceSymbol` (default `€`) and renders `"$price $priceSymbol"`.

---

## 5. Network

### 5.1 Hosts
| Constant | Value |
|---|---|
| `clearnetHost` | `https://mempool.space` |
| `torHost` | `http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion` (official, from mempool.space's `onion-location` header; **http**, not https) |
| `fallbackHosts` | `https://mempool.emzy.de`, `https://mempool.ninja` (in this order) |
| custom | any normalized URL, possibly with port and path prefix (e.g. `https://umbrel.local:3006`, `https://node.example.com/mempool`) |

`apiBase = "$host/api"`. Every URL is `apiBase + path`. `host = sessionOverride ?: persistedHost`.

### 5.2 Endpoints (all `GET`)
| Path (appended to `apiBase`) | Used by | Response |
|---|---|---|
| `/blocks/tip/height` | `getBlockHeight()`, dashboard source #1, `testHost()` | text/plain integer |
| `/v1/prices` | `getPrices()` (converter, dashboard source #3) | JSON object |
| `/v1/fees/recommended` | dashboard source #2 "Fees" | JSON object |
| `/v1/mining/hashrate/3d` | dashboard source #4 "Hashrate" | JSON object (also contains `hashrates`/`difficulty` arrays, which are ignored) |
| `/v1/difficulty-adjustment` | dashboard source #5 "Difficulty" | JSON object |
| `/v1/lightning/statistics/latest` | dashboard source #6 "Lightning" | JSON `{ "latest": {...}, "previous": {...} }` |

### 5.3 Headers (native branch; the web/CORS branch is irrelevant on Android)
- **Central GET (`_get`, all dashboard and price calls and `getBlockHeight`):**
  ```
  User-Agent: 21Meetup/1.3 (Einundzwanzig Meetup App)
  Accept: application/json, text/plain, */*
  Cache-Control: no-cache
  ```
- **`testHost`:**
  ```
  User-Agent: 21Meetup/1.3 (Einundzwanzig Meetup App)
  Accept: text/plain, */*
  ```
  There is **no** `Cache-Control` here.
- Keep the User-Agent string **byte-identical**. The Dart default UA was being blocked by Cloudflare WAF; this custom UA is known to pass. The test asserts it contains `21Meetup`.
- No cache-buster query parameter natively. The web adds `?_=<unix seconds>`; the native URL is unchanged.
- No cookies, no auth.

### 5.4 Timeouts
| Call | Timeout |
|---|---|
| `_get` default | `isOnion(host) ? 45 s : 20 s`, where `isOnion` = `host.contains('.onion')` on the **effective** host |
| `getBlockHeight` | `min(default, 20 s)`, so effectively **always 20 s** (the onion 45 s is capped so a badge scan with one retry stays below ~45 s) |
| `testHost(raw)` | `normalize(raw).contains('.onion') ? 45 s : 20 s` |

The Dart timeout covers the whole call including the body (`client.get(...).timeout(...)`). In OkHttp use `callTimeout` and set **`connectTimeout`, `readTimeout` and `writeTimeout` to 0 or to at least the call timeout**. OkHttp's 10 s defaults would otherwise cut slow Tor circuits much earlier than the Flutter app does.

### 5.5 Status handling
Only status **200** counts as success. Anything else throws `MempoolHttpException(path, status, body)`. Redirects are followed by the client (Dart `http` follows up to 5 for GET; OkHttp follows by default). Response bodies are decoded as text. All payloads used are ASCII numbers or JSON.

### 5.6 Client lifecycle
- One shared client (keep-alive) for all `_get` calls. It is **reset** (pool closed, new client) when:
  1. the effective host differs from the host the client was built for (checked at the start of every `_get`),
  2. a dashboard run had 0 successful sources,
  3. a fallback host is switched to or abandoned.
  Rationale: Orbot toggling or a network change leaves keep-alive sockets dead.
- Natively: `OkHttpClient` singleton. On reset call `connectionPool.evictAll()` (cheaper than a new client). Optionally also evict on `ConnectivityManager.NetworkCallback.onAvailable/onLost` (improvement, not required).
- `testHost` uses a **throw-away client** (no pooled sockets) so the test reflects the current network. Natively: `client.newBuilder().connectionPool(ConnectionPool()).build()`, or send the request with `Connection: close`.
- The Flutter `http` package uses HTTP/1.1. OkHttp may negotiate HTTP/2 with mempool.space. That is fine, with no observable API difference. With async `enqueue` keep OkHttp's `Dispatcher.maxRequestsPerHost` ≥ 6, because the default of 5 would queue the 6th dashboard request. Alternatively use blocking `execute()` on `Dispatchers.IO`, which the per-host limit does not apply to.

### 5.7 Caching, retries, rate limits
- **No HTTP cache.** Do not install an OkHttp `Cache`. `Cache-Control: no-cache` stays as a request header.
- **No automatic retries** inside `getBlockHeight`/`getPrices`. Callers retry:
  - Rolling QR session creation: if the height is `0`, wait 2 s and retry once. If it is still 0, keep 0; there is **no hardcoded fallback height**.
  - Badge scan (`meetup_verification`): retry once after 1 s, then fall back to the tag's `block_height`.
  - Co-attendance organizer marker: single call.
- **Fallback hosts** (dashboard only, clearnet mode only): sequential, see §8.2.
- **Load:** the dashboard aggregation is 6 parallel GETs per run. Single-flight merges concurrent callers. The home tile (60 s) and the dashboard (60 s) each run a timer, so the worst case is about 2 runs/min (12 req/min) plus converter price loads. mempool.space returns 429 when rate-limited, which is classified as blocked. Natively, keep at most this request rate. It is fine to centralize the 60 s ticker in the repository and only tick while a consumer is STARTED.

### 5.8 Cleartext policy (must be ported verbatim as `res/xml/network_security_config.xml`)
```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors><certificates src="system" /></trust-anchors>
    </base-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">onion</domain>
    </domain-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">local</domain>
    </domain-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
        <domain includeSubdomains="false">localhost</domain>
    </domain-config>
</network-security-config>
```
The loopback entry belongs to the PlebRap/audio domain but lives in the same file. A custom instance `http://192.168.x.y:port` is **intentionally blocked** (cleartext to raw LAN IPs is not allowed). Such a call fails at runtime, and the error type name is shown in the test result. OkHttp enforces the NSC automatically (`UnknownServiceException: CLEARTEXT communication to … not permitted`).

### 5.9 Tor
There is no SOCKS proxy in the app. Tor mode relies on **Orbot in VPN mode** including this app. Orbot resolves `.onion` through its DNS and routes the traffic. OkHttp's default `Dns.SYSTEM` uses the same OS resolver path as dart:io, so behavior is identical. Optional enhancement (not required for parity): detect Orbot and offer a SOCKS5 proxy `127.0.0.1:9050`.

---

## 6. Nostr

**None.** This domain does not publish, subscribe to, sign or verify any Nostr events, and uses no relays or NIPs. Block heights from this domain are embedded into badges and Rolling-QR payloads by other domains (field `b` / `block_height`). The Nostr/badge specs own those formats. This domain only guarantees the `getBlockHeight()` contract (§8.1).

---

## 7. Crypto & encoding

No cryptography, hashing, KDFs or binary encodings. The byte-exact concerns here are **number formatting and URL normalization**. These are user-visible and appear in widget strings.

### 7.1 Host normalization (verbatim; must match exactly because the persisted value and mode detection depend on it)
```dart
static String normalize(String raw) {
  var s = raw.trim();
  if (s.isEmpty) return clearnetHost;
  // add scheme if missing: .onion -> http, otherwise https
  if (!s.startsWith('http://') && !s.startsWith('https://')) {
    s = s.contains('.onion') ? 'http://$s' : 'https://$s';
  }
  while (s.endsWith('/')) { s = s.substring(0, s.length - 1); }
  if (s.toLowerCase().endsWith('/api')) { s = s.substring(0, s.length - 4); }
  while (s.endsWith('/')) { s = s.substring(0, s.length - 1); }
  return s;
}

static bool looksValid(String raw) {
  final s = normalize(raw);
  final uri = Uri.tryParse(s);
  return uri != null && uri.host.isNotEmpty && uri.host.contains('.');
}
```
Notes:
- `trim()` in Dart strips Unicode whitespace. Kotlin `String.trim()` strips chars `<= ' '` plus `Char.isWhitespace`, which is close enough.
- The scheme check is **case-sensitive** (`HTTPS://x.y` becomes `https://HTTPS://x.y` and is invalid). The `.onion` check is case-sensitive. The `/api` strip is case-insensitive and happens once.
- `looksValid` uses Dart's `Uri` host extraction. Implement it in pure Kotlin so it is unit-testable without Robolectric:
  1. Take the authority: the text after `://` up to the first `/`, `?` or `#`.
  2. Drop userinfo up to the last `@`.
  3. If the authority starts with `[`, the host is the content up to `]` (IPv6, which never contains `.` and is therefore invalid).
  4. Otherwise drop `:port` after the last `:`.
  5. The host must be non-empty and contain `.`.
  The vectors in §10.2 must pass.

### 7.2 Integer grouping (German style, `.` every 3 digits)
```dart
// CurrencyService.groupInt — handles negatives
static String groupInt(int v) {
  final neg = v < 0;
  final s = v.abs().toString();
  final buf = StringBuffer();
  for (var i = 0; i < s.length; i++) {
    if (i > 0 && (s.length - i) % 3 == 0) buf.write('.');
    buf.write(s[i]);
  }
  return neg ? '-$buf' : buf.toString();
}
static String formatPrice(double price, [String? code]) {
  final c = code ?? current.value;
  return '${groupInt(price.round())} ${symbol(c)}';
}
```
`_fmtInt` in the dashboard, home tile and widget service is the same algorithm without negative handling (only used for non-negative values). The converter's `_fmtSats` handles negatives like `groupInt`. **Do not use `NumberFormat`/`DecimalFormat` with a locale.** The grouping is always `.` regardless of the app language (also in EN/ES).

### 7.3 Dart numeric semantics that must be replicated in Kotlin

| Dart | Behavior | Kotlin replacement |
|---|---|---|
| `double.round()` | half **away from zero** (`2.5→3`, `-2.5→-3`) | `fun dartRound(x: Double): Long = if (x >= 0) floor(x + 0.5).toLong() else -floor(-x + 0.5).toLong()`. **Not** `kotlin.math.round` (half-even) and **not** `Math.round` (half-up toward +∞, which differs for negatives) |
| `x.toStringAsFixed(n)` | exact binary value, ties away from zero, keeps the `-` on negative zero results (`-0.04 → "-0.0"`), always `.` | the shared `Double.toStringAsFixed(fractionDigits)` from `:core:common` (`space.einundzwanzig.meetup.core.common.dart`, DartCompat.kt) (= `BigDecimal(abs(x)).setScale(n, HALF_UP).toPlainString()`, prefixed with `-` if `x < 0` or `x` is `-0.0`); no local copy. **Never** `String.format` (locale comma in DE, and HALF_UP on the shortest repr: `1.005 → "1.01"` in Java vs `"1.00"` in Dart) |
| `double.toString()` | `2.5`, `3.0`, `-0.5`, `20.0` | Kotlin `Double.toString()` gives the same output for the slider values (multiples of 0.5 within ±20) |
| `double.tryParse(s)` | accepts `1.`, `.5`, `+5`, `1e3`, `NaN`, `Infinity`, surrounding whitespace; rejects `1.2.3`, `.`, `-`, `0x10`, `1d`, `1_000` | `s.trim().toDoubleOrNull()`, and additionally reject strings that end in `d`/`D`/`f`/`F` or contain `x`/`X`/`p`, because Java accepts `1d` and hex floats. For the two main fields the `[0-9.,]` filter makes this moot. For the premium field, implement `dartTryParseDouble` with a regex `^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$` (plus `NaN`/`Infinity` if desired) |
| `int.tryParse(s)` | accepts `+900000`, `0x10` (hex) and `-1`; rejects `9e5` and `900000.0` | `s.toIntOrNull()` (no hex). The difference is irrelevant because heights ≤ 0 are rejected and servers return decimal |
| `num.toInt()` on JSON fees | truncates toward zero | `(json as JsonPrimitive).double.toInt()`, or `long` if integral |

---

## 8. Business rules & algorithms

### 8.1 `getBlockHeight()`: contract used by other domains
```dart
static Future<int> getBlockHeight() async {
  try {
    final r = await _get('/blocks/tip/height', maxTimeout: _blockHeightMaxTimeout); // 20 s cap
    final h = int.tryParse(r.body.trim());
    if (h == null || h <= 0) {
      AppLogger.diag(_tag, 'Blockhöhe: unlesbare Antwort (kein Integer): "${r.body.length > 60 ? '${r.body.substring(0, 60)}…' : r.body}"');
      return 0;
    }
    return h;
  } catch (e) {
    _logFailure('Blockhöhe', e);
    return 0; // offline or error
  }
}
```
- It **never throws**. It returns `0` on any failure. Rolling QR, co-attendance and badge verification depend on `0` meaning unknown.
- It returns the **raw** server value. It does **not** apply the monotonic guard and does **not** update `mempool_last_height`; only the dashboard aggregation does.
- It uses the effective host (session override included) and goes through `_get` (client reset on host change).

### 8.2 `getDashboardData()`: single-flight and mirror fallback
```dart
static Future<BitcoinDashboardData> getDashboardData() {
  final running = _inFlight;
  if (running != null) return running;           // share the running fetch
  final f = _fetchDashboard();
  _inFlight = f;
  f.whenComplete(() { if (identical(_inFlight, f)) _inFlight = null; });
  return f;
}

static Future<BitcoinDashboardData> _fetchDashboard() async {
  var data = await _fetchFromCurrentHost();
  if (data.sourcesOk > 0) return data;
  if (MempoolConfig.mode != MempoolMode.clearnet) return data;   // Tor/custom: NEVER fall back
  final original = MempoolConfig.host;            // effective host (may already be a mirror)
  for (final fb in MempoolConfig.fallbackHosts) {
    if (fb == original) continue;
    AppLogger.warn(_tag, 'Keine Daten von $original — Fallback $fb');
    MempoolConfig.setSessionOverride(fb);
    resetClient();
    data = await _fetchFromCurrentHost();
    if (data.sourcesOk > 0) {
      AppLogger.diag(_tag, 'Fallback OK: $fb (${data.sourcesOk}/6 Quellen)');
      return data;                                 // override STAYS for the session
    }
  }
  MempoolConfig.setSessionOverride(null);
  resetClient();
  return data;                                     // dead data of the last attempt
}
```
Kotlin single-flight: hold a `Deferred` created in an **application-scoped** `CoroutineScope(SupervisorJob() + Dispatchers.IO)`, guarded by a `Mutex`. A caller that gets cancelled (screen closed) must not cancel the shared fetch other callers await. The worst-case wall time in clearnet mode is 3 sequential rounds × 20 s.

The session override is in memory only (never persisted). It lasts until the process dies, or until a later run fails on all mirrors.

### 8.3 `_fetchFromCurrentHost()`: aggregation, source counting, guards
1. `prev = lastDashboard` (RAM). Reset `_lastError = ''` and `_lastBlocked = false`. Set `ok = 0`.
2. Run 6 sources **in parallel**:
   - #0 `getBlockHeight()`: success if the result is `> 0`.
   - #1 Fees via `safe()`: success if no exception. Failure value `[-1,-1,-1]`.
   - #2 `getPrices()`: success if the map is non-empty. It is not wrapped in `safe()` because it catches internally; a failure must not be counted as success.
   - #3 Hashrate via `safe()`: failure value `-1.0`.
   - #4 Difficulty via `safe()`: failure value `[NaN, NaN]`.
   - #5 Lightning via `safe()`: failure value `[-1,-1,-1]`.
   - `safe(source, f, fallback)`: try `f()`. On success increment `ok`; on exception call `_logFailure(source, e)` and return `fallback`.
   - A source "succeeds" even if its JSON lacks the fields (the values become 0). A JSON parse or cast error is a failure.
3. **Monotonic height:**
   ```dart
   final lastKnown = await _loadLastHeight();        // max(RAM, prefs-after-reload)
   final height = height0 > lastKnown ? height0 : lastKnown;
   if (height0 > lastKnown) await _saveLastHeight(height0);
   else if (height0 == 0 && lastKnown > 0) diag('Blockhöhe NICHT geladen — zeige letzten bekannten Stand ($lastKnown). Das Widget behält damit den frischesten Wert, egal welcher Aufrufer zuletzt schreibt.');
   else if (height0 > 0 && height0 < lastKnown) diag('Veraltete Blockhöhe verworfen: Server meldet $height0, bekannt ist bereits $lastKnown.');
   ```
   `_saveLastHeight(h)` returns if `h <= RAM value`. Otherwise it updates RAM and persists. Prefs failures are swallowed.
4. **Last-good-value guards** (error sentinels −1/NaN are distinct from a real 0):
   ```dart
   final fees = fees0[0] >= 0 ? fees0 : [prev?.feeLow ?? 0, prev?.feeMedium ?? 0, prev?.feeHigh ?? 0];
   final hashrate = hashrate0 >= 0 ? hashrate0 : (prev?.hashrateEhs ?? 0.0);
   final diff = !diff0[0].isNaN ? diff0 : [prev?.difficultyChangePct ?? 0.0, (prev?.difficultyRemainingBlocks ?? 0).toDouble()];
   final ln = ln0[0] >= 0 ? ln0 : [prev?.lnCapacityBtc ?? 0.0, (prev?.lnNodeCount ?? 0).toDouble(), (prev?.lnChannelCount ?? 0).toDouble()];
   ```
5. If `ok == 0`: warn `'ALLE Quellen fehlgeschlagen @ $host'`, plus `' — Server weist Anfragen ab (Cloudflare/Rate-Limit). Bei Tor: Onion-Adresse in den Einstellungen wählen.'` if blocked, then `resetClient()`. Else if `ok < 6`: diag `'Nur $ok von 6 Quellen geliefert.'`.
6. Build the data:
   - `blockHeight = height`
   - fees from step 4
   - `priceEur = prices['EUR'] ?? prev?.priceEur ?? 0`
   - `priceUsd = prices['USD'] ?? prev?.priceUsd ?? 0`
   - `prices = prices.isNotEmpty ? prices : (prev?.prices ?? {})`
   - `supply = height > 0 ? circulatingSupply(height) : (prev?.supply ?? 0)`
   - hashrate, diff (remaining → `toInt()`), ln (counts → `toInt()`)
   - `updatedAt = now`, `sourcesOk = ok`, `sourcesTotal = 6`, `lastError = _lastError`, `blocked = _lastBlocked`
7. If `ok > 0`: `lastDashboard = data`. A dead result never replaces a good cached dataset.

Note that `_lastError` and `_lastBlocked` are static and also written by `getBlockHeight` and `getPrices` called from other places. They are reset only at the start of `_fetchFromCurrentHost`.

### 8.4 Failure logging and noise suppression
```dart
static void _logFailure(String source, Object e) {
  if (e is MempoolHttpException) {
    _lastError = 'HTTP ${e.statusCode}';
    if (e.looksBlocked) _lastBlocked = true;
    AppLogger.diag(_tag, '$source FEHLGESCHLAGEN — HTTP ${e.statusCode} @ ${MempoolConfig.host}${e.path} · Antwort: ${e.snippet}');
  } else {
    final type = e.runtimeType.toString();
    _lastError = type;
    final sig = '$type@${MempoolConfig.host}';
    final now = DateTime.now();
    if (_lastNetSig == sig && _lastNetSigAt != null && now.difference(_lastNetSigAt!) < const Duration(seconds: 30)) return;
    _lastNetSig = sig; _lastNetSigAt = now;
    AppLogger.diag(_tag, '$source FEHLGESCHLAGEN — $type @ ${MempoolConfig.host} · $e (weitere gleichartige Fehler dieses Abrufs werden unterdrueckt)');
  }
}
```
Source names are `Blockhöhe`, `Fees`, `Preise`, `Hashrate`, `Difficulty`, `Lightning`. The log tag is `Mempool`. Other diag lines:
- `HTTP-Client neu aufgebaut für $host`
- `Verbindungstest OK: $host -> Block $h`
- `Keine Daten von $original — Fallback $fb` (warn)
- `Fallback OK: $fb ($ok/6 Quellen)`

Natively, `_lastError` for non-HTTP errors becomes the Kotlin exception simple name (`SocketTimeoutException`, `UnknownHostException`, `SSLHandshakeException`, `SerializationException` …). It is shown verbatim in the dashboard banner.

### 8.5 Circulating supply (no API; verbatim)
```dart
static int _circulatingSupply(int height) {
  var reward = 50.0;
  var supply = 0.0;
  var h = height;
  while (h > 0 && reward >= 0.00000001) {
    final blocksInEra = h > 210000 ? 210000 : h;
    supply += blocksInEra * reward;
    h -= blocksInEra;
    reward /= 2;
  }
  return supply.round();
}
```
Use `Double` arithmetic and `dartRound`. The result is whole BTC (e.g. `840000 → 19687500`, `900000 → 19875000`).

### 8.6 Moscow time (verbatim)
```dart
String get moscowTime {
  if (priceUsd <= 0) return '--:--';                 // ASCII hyphen-minus
  final satsPerDollar = (100000000 / priceUsd).round();
  final s = satsPerDollar.toString().padLeft(4, '0');
  final head = s.substring(0, s.length - 2);
  final tail = s.substring(s.length - 2);
  return '$head:$tail';
}
```
The head can have more than 2 digits when USD < 10,000 (`9999 → "100:01"`).

### 8.7 Status mapping
- Dashboard dot: `null → grey`, `isDead → red`, `isLive → green`, else orange.
- Home tile dot: `d != null && !isDead → green α0.7`, else grey.
- Time label: `HH:mm` unless `null` or dead.

### 8.8 Converter math (verbatim; `_satsPerBtc = 100000000`)
```dart
double get _btcPrice => _prices[_currency] ?? 0;

double get _currentFiat {
  final direct = double.tryParse(_fiatCtrl.text.replaceAll(',', '.'));
  if (direct != null) return direct;
  final raw = double.tryParse(_cryptoCtrl.text.replaceAll(',', '.'));
  if (raw != null && _btcPrice > 0) {
    final btc = _cryptoIsBtc ? raw : raw / _satsPerBtc;
    return btc * _btcPrice;
  }
  return 0;
}
double get _fiatWithPremium => _currentFiat * (1 + _premiumPercent / 100);

void _recalculate() {
  if (_btcPrice <= 0) return;
  if (_fiatIsInput) {
    final fiat = double.tryParse(_fiatCtrl.text.replaceAll(',', '.'));
    if (fiat == null) { _cryptoCtrl.text = ''; return; }
    final btc = fiat / _btcPrice;
    _cryptoCtrl.text = _formatCrypto(btc);       // BTC: toStringAsFixed(8); sats: (btc*1e8).round()
  } else {
    final raw = double.tryParse(_cryptoCtrl.text.replaceAll(',', '.'));
    if (raw == null) { _fiatCtrl.text = ''; return; }
    final btc = _cryptoIsBtc ? raw : raw / _satsPerBtc;
    _fiatCtrl.text = (btc * _btcPrice).toStringAsFixed(2);   // no grouping, '.' decimal
  }
}
// premium card
final satsWithP = (_btcPrice > 0) ? (withP / _btcPrice * _satsPerBtc).round() : 0;
```
- `replaceAll(',', '.')` replaces **every** comma, so `1,2,3` becomes `1.2.3`, which does not parse.
- Clearing the edited field (unparseable) clears the other field.
- Only the *other* field is rewritten programmatically. Natively, keep the edited field's `TextFieldValue` (cursor) untouched.

### 8.9 Premium slider rounding
`premium = dartRound(v * 2) / 2.0`. Text = `""` if 0, else the Dart-style `toString`. The manual field accepts any value; the slider position is clamped to ±20 but the math uses the unclamped value.

### 8.10 Host label (dashboard footer)
```dart
String _hostLabel() {
  final h = MempoolConfig.host.replaceFirst('https://', '').replaceFirst('http://', '');
  if (h.length > 28) return '${h.substring(0, 12)}…${h.substring(h.length - 12)}';
  return h;
}
```

### 8.11 Currency rules
- `read()`: the stored code if it is in `supported`, else `EUR`. Errors fall back to EUR.
- `load()`: `current = read()`. Called in `main` before `runApp`, so there is no € flash.
- `set(code)`:
  1. Ignore codes that are not supported.
  2. `current = code` (notify).
  3. Persist (errors swallowed).
  4. If `lastDashboard != null`, update the widget with it (fire and forget).
- Supported list = `MempoolService.supportedCurrencies` (single source of truth).

### 8.12 Widget refresh rules (cross-domain behavior driven by this domain)
- `refreshBitcoin()` (background refresh button): `d = getDashboardData()`. **If `d.isDead`, do not write** and log `'Bitcoin-Widget nicht aktualisiert — keine Quelle erreichbar (${lastError.isEmpty ? 'offline' : lastError}). Alte Werte bleiben stehen.'`. Otherwise call `updateBitcoin(d)`.
- Home tile `load()` calls `updateBitcoin(d)` **unconditionally** (inconsistent, see §14).
- `CurrencyService.set` calls `updateBitcoin(lastDashboard)` if it is not null.

---

## 9. Security & privacy measures to preserve

1. **No silent clearnet fallback for Tor or custom users.** Mirrors are only used when the persisted mode is CLEARNET. A Tor user's requests must never go to a clearnet mirror (deanonymization or IP leak). This is the most important rule here.
2. **Session-only mirrors.** The override is never persisted, so the user's stored choice stays authoritative.
3. **Strict cleartext policy.** Only `*.onion`, `*.local` and loopback may use http. Everything else requires TLS with system trust anchors. No user CAs (default for targetSdk ≥ 24).
4. **Custom host persisted only after validation** (`looksValid`). This prevents saving garbage on every keystroke.
5. **Non-default User-Agent** (avoids WAF bot blocking), with no device or user identifiers in it. No cookies, no tracking params.
6. **Block height is never invented.** On failure it is `0`. There is no hardcoded fallback height; the old `850000` constant was removed on purpose (comment in `rolling_qr_service.dart`). Consumers decide.
7. **Monotonic height** guards against stale CDN or rollback responses for the dashboard and widget display.
8. **Honest status UI.** Do not show green/fresh when nothing loaded (the v1.3.0 fix).
9. **Diagnostics without silent catches.** Every failure is logged with status code and a ≤120-char body snippet. Logs contain the configured host (possibly a private `.local` or onion address); the log domain handles export and redaction.
10. `SecurityAudit.md` and `SecurityChangelog.md` contain **no findings** specific to this domain. The only related item is SecurityChangelog Fix 2: badge `created_at` must not fall back to `block_height`, which belongs to the badge domain. It reinforces that block height is not a timestamp.

---

## 10. Tests & vectors to port

### 10.1 Existing test: `test/mempool_web_headers_test.dart` (7 tests)
| # | Group | Test | Expectation | Native port |
|---|---|---|---|---|
| 1 | Web | `noCacheHeaders(isWeb: true)` | empty | N/A on Android. Keep the helper with an `isWeb` param only if a shared KMP module is planned; otherwise drop |
| 2 | Web | `platformHeaders(isWeb: true)` | empty | N/A |
| 3 | Web | `cacheBusted(https://mempool.space/api/blocks/tip/height, isWeb: true)` | has query `_`; path and host unchanged | N/A |
| 4 | Web | `cacheBusted(https://mempool.space/api/x?a=1, isWeb: true)` | `a=1` kept and `_` added (`…/x?a=1&_=<unixSec>`) | N/A |
| 5 | Native | `noCacheHeaders(isWeb: false)` | contains `Cache-Control` (`no-cache`) | **Port**: request built by `MempoolService` has `Cache-Control: no-cache` (MockWebServer `takeRequest()`) |
| 6 | Native | `platformHeaders(isWeb: false)` | contains `User-Agent` containing `21Meetup` | **Port**: UA == `21Meetup/1.3 (Einundzwanzig Meetup App)` exactly |
| 7 | Native | `cacheBusted(uri, isWeb: false)` | URL unchanged | **Port**: request URL has no query parameters |
| 8 | Default | default param equals `kIsWeb` | — | N/A |

Additional native assertions: `Accept: application/json, text/plain, */*` on dashboard/price/height calls. `testHost` sends `Accept: text/plain, */*` and **no** `Cache-Control`.

### 10.2 New pure-function vectors (computed with Dart 3 from the verbatim code)

**`normalize(raw)` → result, `looksValid(raw)`**
| raw | normalize | valid |
|---|---|---|
| `""` | `https://mempool.space` | true |
| `"   "` | `https://mempool.space` | true |
| `mempool.space` | `https://mempool.space` | true |
| `https://mempool.space/` | `https://mempool.space` | true |
| `https://mempool.space/api` | `https://mempool.space` | true |
| `https://mempool.space/api/` | `https://mempool.space` | true |
| `https://mempool.space/API//` | `https://mempool.space` | true |
| `mempool.space/api/v1` | `https://mempool.space/api/v1` | true |
| `umbrel.local:3006` | `https://umbrel.local:3006` | true |
| `http://umbrel.local:3006/` | `http://umbrel.local:3006` | true |
| `mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion` | `http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion` (= torHost) | true |
| `HTTPS://Mempool.Space` | `https://HTTPS://Mempool.Space` | **false** |
| `localhost:8080` | `https://localhost:8080` | false |
| `192.168.1.50:3006` | `https://192.168.1.50:3006` | true |
| `https://node.example.com/mempool/api` | `https://node.example.com/mempool` | true |
| `mempool` | `https://mempool` | false |
| `https://[::1]:3006` | `https://[::1]:3006` | false |
| `ftp://x.y` | `https://ftp://x.y` | false |
| `https://` | `https:` | false |
| `https://api` | `https:` | false |
| `https://.` | `https://.` | true |
| `foo.onion.example.com` | `http://foo.onion.example.com` | true |
| `https://x.y//api//` | `https://x.y` | true |

**Mode derivation:** `setHost("mempool.space")` → mode CLEARNET. `setHost(torHost without scheme)` → TOR. `setHost("")` → CLEARNET. `setHost("https://mempool.emzy.de")` → CUSTOM.

**`moscowTime(priceUsd)`**
`0 → --:--`, `-1 → --:--`, `100000 → 10:00`, `54733 → 18:27`, `54735 → 18:27`, `103000 → 09:71`, `120000 → 08:33`, `66666 → 15:00`, `9999 → 100:01`, `1000 → 1000:00`, `100 → 10000:00`, `1e8 → 00:01`, `2e8 → 00:01` (0.5 rounds up), `3e8 → 00:00`.

**`circulatingSupply(height)`**
`0→0`, `1→50`, `209999→10499950`, `210000→10500000`, `210001→10500025`, `420000→15750000`, `630000→18375000`, `840000→19687500`, `870000→19781250`, `900000→19875000`, `945000→20015625`, `1050000→20343750`, `6929999→21000000`, `6930000→21000000`, `7000000→21000000`, `13440000→21000000`.

**`groupInt`** (`fmtInt` is identical for v ≥ 0)
`0→0`, `12→12`, `123→123`, `1234→1.234`, `12345→12.345`, `123456→123.456`, `1234567→1.234.567`, `95000→95.000`, `1000000→1.000.000`, `-1→-1`, `-1234→-1.234`, `-123456→-123.456`.

**`symbol`**: `EUR→€`, `USD→$`, `GBP→£`, `JPY→¥`, `CAD→CA$`, `AUD→A$`, `CHF→CHF`, `XYZ→XYZ`.
**`formatPrice(95000.4,'EUR')`** → `95.000 €`. **`formatPrice(84000,'CHF')`** → `84.000 CHF`.

**`toStringAsFixed`** (Dart reference)
| x | f0 | f1 | f2 | f8 |
|---|---|---|---|---|
| 0.125 | 0 | 0.1 | 0.13 | 0.12500000 |
| 1.005 | 1 | 1.0 | 1.00 | 1.00500000 |
| 2.5 | 3 | 2.5 | 2.50 | |
| -2.5 | -3 | -2.5 | -2.50 | |
| -0.04 | -0 | -0.0 | -0.04 | |
| -0.05 | -0 | -0.1 | -0.05 | |
| 812.5 | 813 | | | |
| 1234.5 | 1235 | 1234.5 | 1234.50 | |
| 0.005 / 0.015 / 0.025 | | | 0.01 / 0.01 / 0.03 | |
| 2.675 | | 2.7 | 2.67 | |
| 1e-9 | | | | 0.00000000 |
| 1.23456789012 | | | | 1.23456789 |
| -0.0 | | -0.0 | -0.00 | |

**`dartRound`**: `2.5→3`, `-2.5→-3`, `0.5→1`, `-0.5→-1`, `3.5→4`, `1.4999999→1`.

**Difficulty label**: `3.71 → "+3.7% / 1234 blk"`, `-3.75 → "-3.8% / 1234 blk"`, `0.0 → "+0.0% / 1234 blk"`, `-0.04 → "-0.0% / 1234 blk"`.

**Hashrate**: `currentHashrate = 812345678901234567890` → `812 EH/s`. **LN capacity**: `total_capacity = 512345678901` sats → `dartRound(5123.45678901) = 5123` → `5.123 BTC`.

**`MempoolHttpException`**: body `"  <!DOCTYPE html>\n<html>\n   <head><title>Just a moment...</title>  "` → snippet `<!DOCTYPE html> <html> <head><title>Just a moment...</title>`, `looksBlocked = true` (also with status 200). Body of 130 × `x` → snippet length 121 (120 + `…`). Status 403, 429 or 503 → blocked. Status 500 with JSON body → not blocked. `toString()` → `HTTP 500 bei /v1/prices — {…}`.

**`hostLabel`**
`https://mempool.space → mempool.space`, torHost → `mempoolhqx4i…o2r6ad.onion`, `https://mempool.emzy.de → mempool.emzy.de`, a 28-char host stays as is, a 29+ char host is shortened.

**Converter** (price EUR = 95000)
| Input | Unit | Result |
|---|---|---|
| fiat `10` | sats | crypto `10526` |
| fiat `10,5` | sats | `11053` |
| fiat `0.01` | sats | `11` |
| fiat `1000000` | BTC | `10.52631579` |
| fiat `1.` | sats | `1053` |
| sats `1000` | — | fiat `0.95` |
| sats `21000` | — | fiat `19.95` |
| sats `1` | — | fiat `0.00` |
| sats `12345,6` | — | fiat `11.73` |
| BTC `1` / `0.00021` / `0,5` | — | fiat `95000.00` / `19.95` / `47500.00` |
| swapUnit sats→BTC `21000` / `1` / `123456789` | — | `0.00021000` / `0.00000001` / `1.23456789` |
| swapUnit BTC→sats `0.00021` / `0,5` | — | `21000` / `50000000` |

**Premium** (base fiat 100 EUR, price 95000): `0 → 100.00 EUR, 105.263 sats`. `2.5 → 102.50 EUR, 107.895 sats`. `-3 → 97.00 EUR, 102.105 sats`. `20 → 120.00 EUR, 126.316 sats`. `-150 → -50.00 EUR, -52.632 sats`.

**Slider rounding** (`v → premium, text, label`): `0.24 → 0.0, "", "0.0%"`. `0.25 → 0.5, "0.5", "+0.5%"`. `-0.25 → -0.5, "-0.5", "-0.5%"`. `0.74 → 0.5`. `0.75 → 1.0, "1.0", "+1.0%"`. `19.99 → 20.0, "20.0", "+20.0%"`. `-19.75 → -20.0, "-20.0", "-20.0%"`.

**Converter tile sats per unit**: price `95000 → 1053` (`1 € = 1.053 sats`), `84000 → 1190`, `15000000` (JPY) `→ 7`, `0 → tileConverter`.

### 10.3 Recommended new native tests (MockWebServer + fake prefs)
1. All 6 sources OK → `sourcesOk = 6`, `isLive`. Values parsed (fees from `economyFee`, using `minimumFee` when `economyFee` is missing).
2. Fees 500 and others OK → `sourcesOk = 5`. Fees keep the previous `lastDashboard` values, or 0 without a previous dataset. `lastError = "HTTP 500"`, `blocked = false`.
3. Height endpoint 403 HTML → `blocked = true`, `lastError = "HTTP 403"`.
4. Prices return `{}` or JSON without known keys → counted as a failure (not success).
5. Monotonic: stored `mempool_last_height = 900010`, server returns 900000 → `blockHeight = 900010`, store unchanged. Server returns 900020 → store becomes 900020. Server fails → 900010.
6. All fail in clearnet mode → the mirror attempt order is mempool.space, then emzy, then ninja. When one mirror succeeds, its host is used for the following calls (session override). When all fail, the override is cleared and the result is dead.
7. All fail in Tor or custom mode → **no** mirror request is made.
8. Concurrent `getDashboardData()` ×3 → MockWebServer sees exactly 6 requests.
9. `lastDashboard` is not replaced by a dead result.
10. `getBlockHeight()` returns 0 for non-integer, 0, negative, timeout and HTTP error responses. The timeout is capped at 20 s even for onion hosts.
11. `testHost`: a non-integer 200 response raises `MempoolHttpException(200)`. It uses a fresh client and onion → 45 s.
12. `CurrencyRepository`: an unknown stored code → EUR. `set("XYZ")` is a no-op. `set("CHF")` persists the value and triggers the widget sink.
13. Migration: `FlutterSharedPreferences` with `flutter.mempool_host = "https://umbrel.local:3006/api/"` (String) and `flutter.mempool_last_height = 912345L` (Long) → config host `https://umbrel.local:3006`, mode CUSTOM, last height 912345.

---

## 11. Android capabilities, permissions, libraries

| Need | Recommendation |
|---|---|
| Network | `android.permission.INTERNET` (already present). Optional `ACCESS_NETWORK_STATE` if pool eviction on network change is added |
| Cleartext for onion/local | `res/xml/network_security_config.xml` exactly as in §5.8, referenced via `android:networkSecurityConfig` |
| HTTP | **OkHttp** (singleton; `callTimeout` per request; connect/read/write timeouts 0; `dispatcher.maxRequestsPerHost >= 6`; no `Cache`). A call-level timeout can be applied via `client.newBuilder().callTimeout(...)` per host class, or with coroutine `withTimeout` plus `call.cancel()` |
| JSON | `kotlinx.serialization.json` with `JsonObject` access (lenient, number → double/long) |
| Concurrency | Coroutines: `coroutineScope { awaitAll(6 × async) }`, application-scope `Deferred` + `Mutex` for single-flight |
| Persistence | shared `:core:data` `KeyValueStore` (DataStore `app_prefs`), filled once by `:data:migration` (§4.2, spec 14 §4.7). One instance per process |
| UI | Compose M3 / M3 Expressive: `Scaffold`, `TopAppBar`, `PullToRefreshBox`, `LoadingIndicator`/`CircularProgressIndicator`, `ModalBottomSheet`, `ListItem`, `RadioButton` cards, `Slider(valueRange=-20f..20f, steps=79)`, `OutlinedTextField`/`BasicTextField` with `KeyboardOptions(keyboardType = KeyboardType.Decimal / Uri)`, `SnackbarHost`, `Dialog`. Monospace font: Inconsolata (downloadable Google Font or bundled) |
| Periodic refresh while visible | `LaunchedEffect` + `repeatOnLifecycle(Lifecycle.State.STARTED)` with a 60 s `delay` loop (Flutter timers keep running briefly in the background; the native version only refreshes while visible, which is acceptable) |
| Home-screen widget | Owned by the widget domain (keep `MeetupWidgetProvider` class name, or Glance with the same provider component name so placed widgets survive the upgrade). This domain exposes `BitcoinWidgetSink` |
| Widget refresh button | A broadcast receiver from the widget domain calls `MempoolService.getDashboardData()` via `goAsync()` or a one-off expedited WorkManager job, then applies the skip-if-dead rule |
| Tor | None (Orbot VPN). Optional SOCKS proxy enhancement |

No camera, NFC, location, keystore, notification or biometric capabilities are needed in this domain.

---

## 12. Cross-domain dependencies

### 12.1 This domain calls
| Domain | What | Why |
|---|---|---|
| Logging/diagnostics (`AppLogger`) | `diag(tag, msg)`, `warn(tag, msg)` | Failure, fallback and client-reset logs (tag `Mempool`; `WidgetService` for widget messages) |
| Home-screen widget (`WidgetService` / `MeetupWidgetProvider`) | `updateBitcoin(BitcoinDashboardData)` | `CurrencyService.set` pushes the new currency to the widget. The home tile pushes every refresh |
| Theme/design system | colors, Inconsolata mono, tile radius | Visuals |
| l10n | string keys in §2.8 | |
| Navigation shell | routes to dashboard, converter, mempool settings | |

### 12.2 Other domains call this domain
| Caller (domain) | API | Contract |
|---|---|---|
| Rolling QR (organizer session) `rolling_qr_service.dart` | `getBlockHeight()` | 0 = unknown. The caller retries once after 2 s and never invents a value. The height goes into the signed session payload (`b`) |
| Co-attendance `coattendance_service.dart` | `getBlockHeight()` | Used for the organizer marker badge `blockHeight` when the session had none |
| Badge scan / verification `meetup_verification.dart` | `getBlockHeight()` | Scan-time height. Retry once after 1 s, then fall back to the tag's `block_height` |
| Home dashboard (tiles grid, settings sheet, tour) | `getDashboardData()`, `lastDashboard`, `CurrencyRepository.current/set/symbol/groupInt/formatPrice`, `supportedCurrencies`, navigation to `MempoolSettingsScreen`, `BitcoinDashboardScreen`, `ConverterScreen` | Tiles `btc_dashboard` and `converter`, the currency dialog, the mempool settings row |
| Home-screen widget (background refresh, `widgetBackgroundCallback` with `homewidget://refresh`) | `getDashboardData()` (skip if dead), `CurrencyRepository.read()`, formatting helpers | Widget values (§4.3) |
| Widget router (`open=bitcoin`) | navigation to `BitcoinDashboardScreen` | |
| App start (`main.dart`) | `CurrencyService.load()` before the first frame | |
| App reset (`home_screen._performReset`) | should call `MempoolConfig.resetToDefaults()`, `CurrencyRepository.reset()`, `MempoolService.clearState()` (native improvement) | |

---

## 13. Proposed Kotlin package/module structure and public API

```
:core:bitcoin            (pure Kotlin/Android lib, no Compose)
  space.einundzwanzig.meetup.bitcoin.mempool
    MempoolMode.kt
    MempoolHosts.kt            // constants + normalize/looksValid (pure, unit-tested)
    MempoolConfig.kt           // persisted host + session override
    MempoolHttpException.kt
    BitcoinDashboardData.kt
    MempoolService.kt          // interface
    MempoolServiceImpl.kt      // OkHttp + coroutines
    BlockHeightStore.kt        // monotonic last height (prefs)
    Supply.kt                  // circulatingSupply()
  space.einundzwanzig.meetup.bitcoin.currency
    CurrencyRepository.kt
    CurrencyFormat.kt          // symbol, groupInt, formatPrice
  space.einundzwanzig.meetup.bitcoin.format
    DartNum.kt                 // dartTryParseDouble only; dartRound, toStringAsFixed, toDartString come from :core:common core.common.dart
    BtcFormat.kt               // fmtInt, fmtSats, moscowTime, hostLabel, hhmm, difficultyLabel, feesLabel
  space.einundzwanzig.meetup.bitcoin.widget
    BitcoinWidgetSink.kt       // interface implemented by the widget domain
    BitcoinWidgetValues.kt     // pure mapping BitcoinDashboardData -> Map<String,String> (§4.3)

:feature:bitcoin-tools   (Compose UI)
  space.einundzwanzig.meetup.bitcoin.ui.dashboard   BitcoinDashboardScreen, BitcoinDashboardViewModel
  space.einundzwanzig.meetup.bitcoin.ui.converter   ConverterScreen, ConverterViewModel, ConverterState (pure reducer)
  space.einundzwanzig.meetup.bitcoin.ui.settings    MempoolSettingsScreen, MempoolSettingsViewModel, CurrencyPickerDialog
  space.einundzwanzig.meetup.bitcoin.ui.tiles       BtcDashboardTileContent, ConverterTileContent
```

Key signatures:

```kotlin
enum class MempoolMode { CLEARNET, TOR, CUSTOM }

object MempoolHosts {
    const val CLEARNET = "https://mempool.space"
    const val TOR = "http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion"
    val FALLBACKS = listOf("https://mempool.emzy.de", "https://mempool.ninja")
    fun normalize(raw: String): String
    fun looksValid(raw: String): Boolean
    fun isOnion(host: String): Boolean = host.contains(".onion")
    fun timeoutFor(host: String): Duration = if (isOnion(host)) 45.seconds else 20.seconds
}

interface MempoolConfig {
    val host: String                      // sessionOverride ?: persistedHost
    val hostFlow: StateFlow<String>
    val apiBase: String get() = "$host/api"
    val mode: MempoolMode                 // from persistedHost only
    suspend fun ensureLoaded()
    suspend fun setHost(raw: String)      // normalize + persist (+ clear session override, see §14)
    suspend fun setMode(mode: MempoolMode, custom: String = "")
    fun setSessionOverride(host: String?) // null/blank clears; value normalized
    suspend fun resetToDefaults()         // app wipe
}

class MempoolHttpException(val path: String, val statusCode: Int, body: String) : IOException() {
    val snippet: String
    val looksBlocked: Boolean
    override val message: String get() = "HTTP $statusCode bei $path — $snippet"
}

data class BitcoinDashboardData(
    val blockHeight: Int, val feeLow: Int, val feeMedium: Int, val feeHigh: Int,
    val priceEur: Double, val priceUsd: Double, val prices: Map<String, Double> = emptyMap(),
    val supply: Long, val hashrateEhs: Double, val difficultyChangePct: Double,
    val difficultyRemainingBlocks: Int, val lnCapacityBtc: Double, val lnNodeCount: Int,
    val lnChannelCount: Int, val updatedAt: java.time.LocalDateTime /* or epochMillis */,
    val sourcesOk: Int = 0, val sourcesTotal: Int = 6, val lastError: String = "", val blocked: Boolean = false,
) {
    val isLive: Boolean get() = sourcesOk == sourcesTotal
    val isDead: Boolean get() = sourcesOk == 0
    fun priceIn(code: String): Double
    val moscowTime: String
}

interface MempoolService {
    companion object {
        const val USER_AGENT = "21Meetup/1.3 (Einundzwanzig Meetup App)"
        val SUPPORTED_CURRENCIES = listOf("EUR","USD","GBP","CHF","CAD","AUD","JPY")
        fun circulatingSupply(height: Int): Long
    }
    val lastDashboard: StateFlow<BitcoinDashboardData?>
    suspend fun getBlockHeight(): Int                 // never throws; 0 = unknown
    suspend fun getPrices(): Map<String, Double>      // never throws; empty = failure
    suspend fun testHost(rawHost: String): Int        // throws MempoolHttpException / IOException
    suspend fun getDashboardData(): BitcoinDashboardData  // single-flight, never throws
    fun clearLastDashboard()                          // settings: source changed
    fun resetClient()
}

interface CurrencyRepository {
    val current: StateFlow<String>                    // default "EUR"
    suspend fun load()
    suspend fun read(): String
    suspend fun set(code: String)                     // ignores unsupported; persists; pushes widget
    suspend fun reset()
}

object CurrencyFormat {
    fun symbol(code: String): String
    fun groupInt(v: Long): String
    fun formatPrice(price: Double, code: String): String
}

interface BitcoinWidgetSink { suspend fun updateBitcoin(d: BitcoinDashboardData) }

// Converter pure state (unit-testable, no Android deps)
data class ConverterState(
    val currency: String, val cryptoIsBtc: Boolean = false, val fiatIsInput: Boolean = true,
    val fiatText: String = "", val cryptoText: String = "", val premiumText: String = "",
    val premiumPercent: Double = 0.0, val prices: Map<String, Double> = emptyMap(),
    val loading: Boolean = true, val error: Boolean = false, val updatedAt: LocalTime? = null,
) {
    val btcPrice: Double
    val currentFiat: Double
    val fiatWithPremium: Double
    val satsWithPremium: Long
    fun onFiatChanged(text: String): ConverterState
    fun onCryptoChanged(text: String): ConverterState
    fun swapUnit(): ConverterState
    fun onCurrencyPicked(code: String): ConverterState
    fun onPremiumText(text: String): ConverterState
    fun onSlider(v: Float): ConverterState
    fun recalculated(): ConverterState
}
```

DI: provide `MempoolConfig`, `MempoolService`, `CurrencyRepository` as `@Singleton` (Hilt) in the application component. The widget receiver and the UI then share the same instance in-process. Keep the persisted monotonic height regardless, because process death resets RAM.

---

## 14. Risks / compatibility pitfalls

1. **Prefs migration types.** `mempool_last_height` is stored by Flutter as a **Long** under `flutter.mempool_last_height` in `FlutterSharedPreferences`. Reading it as Int crashes, and forgetting the `flutter.` prefix silently loses the user's custom node or currency.
2. **Number formatting locale.** All numbers use `.` grouping and `.` decimals regardless of language. `String.format`, `NumberFormat.getInstance()` or Compose `pluralStringResource` number formatting would produce `95,000`/`95 000`/`19,95` and break visual parity and the widget strings. Use the helpers in §7.
3. **Rounding.** Dart `round()` and `toStringAsFixed` differ from Kotlin `round()` (half-even), `Math.round` (negatives) and `String.format` (`1.005`). Display differences are visible in prices, sats and difficulty. Use `dartRound` and the BigDecimal-based `toStringAsFixed` with the negative-zero fix.
4. **OkHttp default timeouts** (10 s connect/read) are shorter than Flutter's 20/45 s whole-call limits. Tor users would see far more failures. Also keep `maxRequestsPerHost ≥ 6`.
5. **Single-flight cancellation.** If the shared fetch runs in a ViewModel scope, closing the dashboard would cancel the home tile's or widget's fetch. Use an application scope.
6. **Mirror fallback must stay clearnet-only** (privacy). It must be keyed on the **persisted** mode, not on the effective host.
7. **Session override bug in Flutter.** `setHost()` does not clear `_sessionOverride`. After a fallback mirror kicks in, a user who switches to Tor or custom still talks to the mirror for the rest of the session. The "Aktive Quelle" footer and the settings custom field can show the mirror. **Recommendation:** clear the override in `setHost`. This is safe; nothing persisted or on the wire changes.
8. **App reset leaves in-memory state.** The Flutter reset clears prefs but not `MempoolConfig._host`, `_loaded`, `CurrencyService.current` or `_lastKnownHeight`. Natively, reset the repositories too.
9. **Inconsistent widget dead-guard.** The home tile writes dead data into the widget (with `updated = now` and prev-filled values, or `0·0·0` fees when there is no prev). The background refresh does not. **Recommendation:** apply the skip-if-dead rule in both paths. This matches the documented intent ("a failed refresh must not overwrite good values").
10. **Monotonic height and non-mainnet custom nodes.** A testnet/signet custom instance has lower heights, which the dashboard discards forever because the guard has no reset on host change. Badge and Rolling-QR calls use the raw `getBlockHeight()` and get the custom node's height. Keep this for parity, and do not "fix" it by resetting the store on host change without coordinating.
11. **`looksValid` quirks are user-visible and must match.** Empty input is valid (resets to clearnet). `HTTPS://…` uppercase is invalid. Raw LAN IPs are valid but then blocked by the cleartext policy over http. Hosts with spaces are valid in Dart but OkHttp's `HttpUrl` rejects them. Natively, wrap URL building in try/catch and treat it as a source failure (`IllegalArgumentException` → logged, `lastError = "IllegalArgumentException"`).
12. **`getBlockHeight` contract** (never throws, 0 on failure, 20 s cap, raw value) is relied on by signed badge/QR flows. Changing it (e.g. throwing, or returning the monotonic value) changes badge data across users.
13. **The converter does not use the fallback or the cached prices.** If mempool.space blocks the IP and no dashboard run has set a mirror override this session, the converter shows the offline banner. Keep it (parity), or let the converter reuse `lastDashboard.prices` as an initial value (harmless improvement).
14. **Hardcoded, non-localized labels** (`Bitcoin`, `BLOCK`, `FEES  L·M·H`, `MOSCOW`, `SUPPLY`, `HASHRATE`, `DIFFICULTY`, `LIGHTNING`, `KAPAZITÄT`, `NODES`, `CHANNELS`, `blk`, `EH/s`, `Netzwerk & Kurs`, widget `Stand`). They appear identically in EN and ES today. Keep them as non-translatable resources (`translatable="false"`), or add new keys deliberately.
15. **Exact characters.** `––` is two U+2013. `·` is U+00B7. `…` is U+2026. The minus in `−20%` is U+2212. The em dash in test messages is U+2014. `--:--` is ASCII (the widget's own fallback `––:––` uses en dashes). Mixing them causes visible differences.
16. **Widget provider identity.** If the widget domain replaces `space.einundzwanzig.meetup.MeetupWidgetProvider` or the `HomeWidgetPreferences` keys, placed widgets break on upgrade. This domain must feed the same keys (§4.3) unless the widget domain migrates them.
17. **Background refresh no longer isolate-separated.** In Flutter the widget refresh ran in a fresh isolate with `lastDashboard == null`. Natively it shares the process, so `prev` values are available. This is a behavioral improvement, but tests that assume "no prev" in the background path will differ.
18. **Currency list is fixed to what `/v1/prices` returns.** If a custom instance lacks some currencies, the price for that currency is 0. The UI hides the rate and shows `––` with no error. Do not add currencies without checking the API.
19. **`PosScreen`** is dead code. Porting it as a reachable screen would add an unfinished feature.
