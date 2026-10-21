# 12 — Shell, Home Dashboard, Settings, Diagnostics Log, Guide/Tours, Theme & l10n

Porting spec for the native Android (Kotlin + Jetpack Compose + Material 3 Expressive) rewrite of the
Flutter app "Einundzwanzig Meetup" (`applicationId = space.einundzwanzig.meetup`, Flutter version `1.7.2+31`).

Source files covered (all read completely):

| Dart file | Lines | Content |
|---|---|---|
| `lib/main.dart` | 367 | Startup, error handlers, NIP-46 auth_url snackbar, widget bg callback, `MyApp`, `SplashScreen` |
| `lib/screens/app_shell.dart` | 402 | Bottom navigation shell, scan FAB + scan sheet, first-run home tour prompt |
| `lib/screens/home_screen.dart` | 3711 | Dashboard (tiles, favorites carousel, edit mode, customize sheet, refresh, portal organizer check, reset flow, settings bottom sheet, language/currency dialogs, BTC tile) |
| `lib/screens/settings_screen.dart` | 114 | Standalone settings screen — **dead code** (never navigated to) |
| `lib/screens/log_screen.dart` | 184 | Diagnostic log viewer |
| `lib/services/app_logger.dart` | 315 | Persistent ring-buffer logger + `RelayParseTally` |
| `lib/services/diagnostics_service.dart` | 123 | Environment fingerprint written at app start |
| `lib/services/locale_controller.dart` | 59 | Language selection & persistence |
| `lib/services/guide_service.dart` | 299 | Spotlight tour state machine + persistence |
| `lib/widgets/guide_overlay.dart` | 917 | Spotlight overlay rendering, target measuring, tooltip placement, string-key resolution |
| `lib/mixins/guide_service_host.dart` | 36 | Helper to finish a tour on screen dispose |
| `lib/tours/home_tour.dart` | 231 | Home tour steps + target keys |
| `lib/tours/more_tours.dart` | 168 | Wallet / Community / PortalArea / MyMeetups / WoT tours |
| `lib/tours/settings_tour.dart` | 81 | Settings sheet tour |
| `lib/theme.dart` | 128 | Colors, fonts, tile constants, ThemeData |
| `lib/widgets/glass_card.dart` | 195 | `GlassCard`, `StatusChip`, `SectionHeader` |
| `lib/widgets/empty_state.dart` | 144 | `EmptyState` with pulsing icon |
| `lib/widgets/pressable.dart` | 97 | Press-scale wrapper |
| `lib/widgets/shadows.dart` | 42 | `shadowForElevation` |
| `lib/services/haptic_service.dart` | 84 | Haptic patterns + on/off setting |
| `lib/features.dart` | 43 | Feature flags (`kNfcEnabled=false`, `kWebLocalNsecLoginEnabled=false`) |
| `lib/utils/share_origin.dart` | 43 | iOS share-sheet anchor helper |
| `lib/services/safe_url_launcher.dart` | 33 | http(s)-only external link launcher (Security Audit M2) |
| `lib/l10n/level_labels.dart` | 32 | Internal trust level → localized label |
| `lib/test_main.dart` | 18 | Smoke-test entry point (blue screen) — **do not port** |
| `l10n.yaml`, `lib/l10n/app_{de,en,es}.arb` | — | Localization config and resources |

Helpers read to understand behavior (owned by other domains, referenced here): `widget_service.dart`,
`MainActivity.kt`, `WidgetRouterActivity.kt`, `MeetupWidgetProvider.kt`, `currency_service.dart`,
`news_service.dart` (unread logic), `satoshiduell_service.dart`, `device_integrity_service.dart`,
`nostr_profile_service.dart` (local picture), `models/user.dart`, `meetup_service.dart` (favorite resolution),
`portal_api_service.dart` (rawGetStatus/logout/isGoing), `rolling_qr_service.dart` (`MeetupSession`),
`trust_score_service.dart` (`level`, `promotionProgress`), `meetup_calendar_service.dart` (image URLs),
`AndroidManifest.xml`, shared_preferences_android 2.4.20 / home_widget 0.6.0 plugin sources.

---

## 1. Overview and user-visible features

This domain is the **frame of the app**: process start, crash/error logging, splash routing, the bottom
navigation shell with the central scan button, the **Home dashboard** (a configurable tile grid with a fixed
favorites carousel), the **settings bottom sheet** (account, backup, network, language, currency, haptics,
diagnostics log, tour reset, V4V, factory reset), the **diagnostic log** viewer, the **spotlight tour
("Guide") system** used across the app, the **design system** (colors, fonts, tile metrics, shared widgets,
haptics), and the **localization** setup (de/en/es, German is the template and fallback).

Complete feature list (each line = one user-visible behavior):

### Startup / shell
- F1  Splash: black screen, orange bolt icon (56dp in 88dp circle with orange glow) + small orange spinner; fades in over 800 ms; after ≥800 ms and session check routes (fade 500 ms) to Intro (not onboarded) or AppShell.
- F2  Immersive-sticky system UI: Android navigation bar hidden (appears on edge swipe), status bar visible, transparent, light icons.
- F3  App content is constrained to max width 420dp, centered, with 0.5dp vertical hairline borders (`cBorder`) left/right (relevant on tablets/foldables ≥600dp; phones are portrait-locked by MainActivity).
- F4  Global "remote signer needs approval" snackbar (NIP-46 `auth_url`) that can appear anywhere: orange, 60 s, close icon, action "ÖFFNEN"/"OPEN" opens the URL in the browser (http/https only).
- F5  Bottom bar: Home tab, Badges tab, [center scan FAB], Events tab, "In der Nähe/Nearby" action (opens a route, not a tab). Active tab shows filled icon + white label; inactive outlined + tertiary gray.
- F6  Tapping a tab gives a light haptic (if enabled); tapping Home (even if already active) refreshes dashboard data.
- F7  Scan FAB (orange gradient circle, 62dp, overlapping bar) → medium haptic → bottom sheet with 2 options: "Badge scannen" (QR from meetup) and "Reputation prüfen" (verify another person's trust score). Both open full-screen routes sliding up from bottom.
- F8  First-run tour prompt dialog ("Willkommen!" / "Möchtest du eine kurze Tour…", buttons "Nein, danke" / "Tour starten"), non-dismissible. "No" disables ALL tours app-wide.
- F9  Home tour (21 spotlight steps) starts automatically once the dashboard targets exist (polls up to 10 s).

### Home dashboard
- F10 Logo bar: Einundzwanzig SVG logo (16dp high) left; right: refresh icon (becomes hourglass while running), help "?" (Glossary), gear (Settings sheet).
- F11 Manual refresh button: snackbar "Aktualisiere Daten …" (2 s) → reloads everything → green snackbar "Alles aktualisiert".
- F12 Pull-to-refresh on the tile area (works even when content is shorter than the screen); no snackbars in this mode.
- F13 Profile header: 40dp circular avatar (Nostr kind:0 picture, or locally picked gallery image, or first letter of nickname / "?"), nickname (ellipsized), orange trust-score pill showing the score with 1 decimal (monospace) + chevron → opens Reputation card screen.
- F14 Tapping the avatar when there is no Nostr picture opens the gallery picker (max 400×400, quality 80) and stores the chosen image locally; failure → snackbar "Bild konnte nicht geladen werden: {msg}".
- F15 Root/jailbreak warning card (orange-outlined) with hard-coded German text and a close "×" (dismissed for the lifetime of the screen only).
- F16 Live session card (when a Rolling-QR badge session is active): pulsing green dot, "LIVE" chip, medal icon for event sessions (`meetupId` starts with `evt:`), meetup name (or "Meetup aktiv"), remaining time "Hh MMm" updating every second, arrow → Rolling QR screen.
- F17 Home-meetup area (fixed, not part of the grid):
  - no favorite: CTA card "HOME MEETUP / Wähle deinen Stammtisch / Dein regelmäßiges Meetup auswählen" → meetup selection screen;
  - with favorites: horizontally swipeable cards (one per favorite meetup, sorted by next event date, cities without event last), page dots if >1.
- F18 Each favorite card: perspective "cover-flow" crest image fading to the right, "HOME MEETUP" chip + country code, meetup label in capitals (font shrinks with length, up to 2 lines), next-event line ("Heute!" / "Morgen" / "in N Tagen" + "· d.m.yyyy", colored by proximity; "Kein Termin geplant" if none; thin progress bar while loading), and an action bar with three equal fields: EVENTS (calendar filtered to that meetup), Chat (with unread badge, "99+" cap), Info (meetup details; disabled/greyed when the meetup record cannot be resolved).
- F19 Opening a meetup chat: snackbar "Chatraum wird gesucht …" (4 s), then either opens the chat room, or shows "Für {city} gibt es … noch keinen Chatraum…" (4 s), or a connectivity error ("Keine Internetverbindung…" vs "Das Chat-Relay hat gerade nicht geantwortet…", 5 s).
- F20 Tile grid ("pinned" tiles) fills the remaining vertical space exactly (rows share height, min 120dp per row; beyond that it scrolls).
- F21 "Verfügbar/Available" collapsible section below the grid (collapsed by default) with all un-pinned tiles, dimmed (opacity 0.8) and plain-styled, fully functional; header shows a count (number of ROWS) and an expand/collapse chevron.
- F22 Tiles (see table §2.6.8): My events, Reputation, Trust network, Community, Converter (live "1 € = 1.779 sats"), Bitcoin (block height + price, auto-refresh 60 s), News (latest headline + red unread counter), My meetups (portal), Events (count of today's events), Shoutout (external link), Podcast (external link), SatoshiDuell (open duels, deep-link with npub), Portal area, PlebRap mini-player (play/pause/next/library), Nostr (opens Einundzwanzig's Nostr profile, "new" dot after 24 h), Organizer (admin panel; only for admins / review demo).
- F23 Every tile press: scale-down to 0.97 (100 ms in / 80 ms out, disabled with "reduce animations") + selection-click haptic.
- F24 Edit mode: long-press a tile → it gets an orange frame/glow, a "move" icon and (if removable) a pin button; tap pin = pin/unpin (moves between grid and Available); drag it onto another tile = reorder; tap the marked tile again = cancel. Mode ends automatically after any action. The Available section auto-expands during edit mode.
- F25 Customize sheet (long-press the home-meetup card or the "Verfügbar" header): German-only UI "ANPASSEN" / "FERTIG", reorderable list of active tiles with switches to hide (home_meetup & reputation locked with 🔒), list of available tiles with switches to add; changes saved only on "FERTIG".
- F26 Tile layout persistence across restarts, migrations for new tiles.
- F27 Badge storage unreadable: snackbar (8 s) "Deine Badges konnten nicht gelesen werden. Es wurde nichts gelöscht…", trust score shows "—" / 0.0.
- F28 Portal organizer auto-detection on start/refresh; silent auto-login with local key; one-time orange snackbar (8 s, action "Anmelden") for external-signer users without portal connection; orange snackbar (6 s) when the stored portal token belongs to another key.
- F29 Home-screen widget deep links ("news", "bitcoin", "meetup") open the matching screen when the app is launched/resumed from the widget.
- F30 The "Heute/Morgen" countdown flips exactly at local midnight (timer + recompute on resume).
- F31 The Android home-screen widget's meetup city/countdown is updated whenever the favorites carousel is recomputed.

### Settings sheet (gear icon)
- F32 Bottom sheet (85% height, draggable 50–95%), header "Einstellungen / App & Account verwalten".
- F33 ACCOUNT: "Profil bearbeiten" → profile edit screen.
- F34 DATEN & SICHERHEIT: "Backup erstellen" (encrypted backup flow), "Backup laden" (restore flow; afterwards the dashboard reloads everything).
- F35 NETZWERK: "Nostr-Relays" → relay settings; "Mempool-Server" → mempool source settings.
- F36 APP: Language row (flag + name) → dialog System/Deutsch/English/Español (applies immediately); Currency row ("EUR · Euro") → dialog with 7 currencies (EUR, USD, GBP, CHF, CAD, AUD, JPY) and hint text; Haptics switch ("Aktiv"/"Deaktiviert"); "Diagnose-Log" → log screen; "Tour wiederholen" → resets all tours + orange snackbar "Touren zurückgesetzt…".
- F37 UNTERSTÜTZEN: "V4V" → Value-for-Value screen.
- F38 GEFAHRENZONE: "App zurücksetzen" → 3-step confirmation with mandatory backup offer → full wipe → Intro screen.
- F39 Footer: "21Meetup · Version 1.7.2" (app versionName).
- F40 Settings tour (7 steps) runs automatically the first time the sheet is opened; closing the sheet ends (and marks done) the tour.

### Diagnostic log screen
- F41 Monospace terminal-style list "HH:MM:SS  LEVEL  tag: message", chronological, selectable text, level colors (ERROR red, WARN orange, SEC purple, DEBUG gray, INFO secondary).
- F42 App bar actions: NIP-44 probe (key icon; snackbar success/failure), filter WARN/ERROR only, verbose-mode toggle (snackbar), copy to clipboard ("Log kopiert"), share (subject "21Meetup Diagnose-Log"; falls back to clipboard).
- F43 Footer: "N Einträge" and "Log leeren" (clears immediately, no confirmation). Empty state: "Keine Einträge".
- F44 All log-screen texts are hard-coded German (not localized).

### Cross-cutting
- F45 Spotlight tours on Home, Settings, Badge wallet, Community hub, Portal area, My meetups (+ events/event-badge tours owned elsewhere): dark scrim (85%), pulsing orange hole around the target, tooltip card with title, "Schritt X von Y", body, optional hint (turns green "Erledigt" when done), buttons Zurück / Tour beenden / Weiter, and an "ÜBERSPRINGEN" chip top-right.
- F46 Language: German (default/fallback), English, Spanish; "System" follows device language (unsupported device languages fall back to German).
- F47 Display currency is app-wide (converter, tiles, BTC dashboard, widget).
- F48 Haptics can be globally disabled.

---

## 2. Screens & UX flows

### 2.1 Process start (`main.dart`)

`main()` wraps `_start` in `runZonedGuarded`; zone errors → `AppLogger.error('Zone', 'Unbehandelter Fehler', error, stack)` (wrapped in try/catch).

`_start()` exact order:
1. `WidgetsFlutterBinding.ensureInitialized()`
2. `await AppLogger.init()` — loads persisted log (keeps entries logged before init).
3. `_installErrorHandlers()`:
   - `FlutterError.onError` → `AppLogger.error('Flutter', details.library ?? 'widget', details.exception, details.stack)` then default presentation.
   - `PlatformDispatcher.onError` → `AppLogger.error('Async', 'Unbehandelter Fehler', error, stack)`; returns `kReleaseMode` (release: handled, keep running so the tester can export the log).
4. `DiagnosticsService.logEnvironment()` — **not awaited** (background).
5. Android only: `HomeWidget.registerInteractivityCallback(widgetBackgroundCallback)` — background callback: if `uri.host == 'refresh'` → `WidgetService.refreshBitcoin()` then `WidgetService.refreshNews()` (widget domain).
6. `await LocaleController.load()`
7. `await CurrencyService.load()` (before first frame so no "€" flashes)
8. `await MeetupBadge.loadBadges()` — **must be the first secure-storage access** (comment: migration from flutter_secure_storage must not start in parallel; a failed read must not write a new key over the old).
9. `BadgeSyncService.init(); BadgeSyncService.retryIfDirty();` (not awaited)
10. System UI: `SystemUiMode.immersiveSticky`, overlays `[top]`; overlay style: statusBarColor transparent, statusBarIconBrightness light, navigationBarColor transparent, navigationBarIconBrightness light.
11. `_installNip46AuthUrlHandler()`
12. `runApp(MyApp())`

`MyApp`: `ChangeNotifierProvider(GuideService)` ABOVE `MaterialApp` (spotlight must be above every route/dialog/sheet). `MaterialApp(title: 'Einundzwanzig Meetup', theme: appTheme, locale: LocaleController.locale (null=system), scaffoldMessengerKey: rootMessengerKey, builder: GuideOverlay(child: Center(Container(maxWidth 420, vertical 0.5 cBorder borders, child))), home: SplashScreen)`.

**Native mapping**: `Application.onCreate` + `MainActivity`:
- Install `Thread.setDefaultUncaughtExceptionHandler` that writes `AppLogger.error("Async"/"Flutter-equivalent tag", ...)` and **flushes synchronously** before delegating to the previous handler (the Dart app debounces persistence 2 s; a native crash kills the process — see §14).
- Coroutine `CoroutineExceptionHandler` at app scope → `AppLogger.error("Async", "Unbehandelter Fehler", e)`.
- Startup sequence identical: logger init → handlers → diagnostics (background) → locale → currency → badges load → badge sync init/retry → UI.
- Use `WindowInsetsControllerCompat.hide(navigationBars())` with `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, `enableEdgeToEdge()` with light status bar icons on dark background.

### 2.2 NIP-46 auth_url global handler

`SigningService.onNip46AuthUrl = (url) { ... }` (callback slot owned by signing domain):
- If no messenger/context yet: `AppLogger.warn('Nip46', 'Freigabe-Aufforderung des Signers kam an, aber die App war noch nicht bereit sie zu zeigen (Host: ${_hostOf(url)}).')` and return.
- Else show snackbar: text `bunkerAuthNeeded`, background `Colors.orange` (#FF9800), duration 60 s, `showCloseIcon: true`, action label `bunkerAuthAction` (text color black) → `launchHttpUrl(url)`; if it returns false → `AppLogger.warn('Nip46', 'Freigabe-Seite nicht geöffnet (Schema nicht http(s) oder kein Browser), Host: ${_hostOf(url)}')`.
- `_hostOf(url)`: `Uri.parse(url).host`, empty or parse error → `'unbekannt'`.
- **Never log the full auth_url** (contains approval token; Security Audit N5). Do **not** auto-open (web requires user gesture; native: keep the button for consistency and safety).

Native: an app-scoped `SharedFlow<UiEvent.SignerApprovalNeeded(url)>` collected by a root `SnackbarHost` (one host above NavHost). Snackbar with `SnackbarDuration.Indefinite` + manual 60 s timeout and dismiss action.

### 2.3 Splash

`SplashScreen`: background `cDark`; FadeTransition 800 ms easeOut on content: 88×88 circle with `BoxShadow(cOrange@0.2, blur 40)`, `Icons.bolt` 56 orange; 24dp gap; `CircularProgressIndicator(color cOrange, strokeWidth 2)`.
`_checkSession()`: `await SecureKeyStore.ensureMigrated()` → `user = await UserProfile.load()` → `await delay(800ms)` → `pushReplacement` with fade (500 ms) to:
- `IntroScreen` if `!user.isOnboarded` (onboarding domain),
- else `AppShell`.

`isOnboarded = hasCustomNickname || isVerified` where `hasCustomNickname = nickname.trim() not empty && != 'Anon'`, `isVerified = isNostrVerified || isAdminVerified` (identity domain; test vector in §10).

Native: Android 12+ `SplashScreen` API (`installSplashScreen()` + `setKeepOnScreenCondition` until the session check finishes), then navigate. Keep the ≥800 ms minimum only if desired visually; the routing decision is the compatibility-relevant part.

### 2.4 AppShell (`app_shell.dart`)

**Purpose**: root scaffold after onboarding. Entry: Splash (onboarded), Intro/identity setup completion (onboarding domain).

**State**: `_currentIndex` ∈ {0 Home, 1 Badges, 3 Events} (index 2 = FAB placeholder, taps ignored). Body = `IndexedStack(index: _currentIndex > 2 ? _currentIndex - 1 : _currentIndex, children: [HomeScreen(key: _homeKey), BadgeWalletScreen(), EventsHubScreen()])` — **all three tabs stay alive** (keep state; native: keep each tab's back stack/state saved, e.g. `NavHost` with `saveState/restoreState` or three always-composed layers).

**Bottom bar** (custom, not Material NavigationBar):
- Total height `56 + bottomInset + 16`; bar itself `56 + bottomInset` at the bottom, `ClipRect + BackdropFilter blur σ=24`, color `cDark @ 0.92`, top border 0.5 `cBorder`, bottom padding = system bottom inset.
- Row `spaceAround`: `_navItem(0, home_rounded/home_outlined, navHome, key homeTabKey)`, `_navItem(1, style_rounded/style_outlined, navWalletTab, key badgeTabKey)`, `SizedBox(width 60)`, `_navItem(3, event_rounded/event_outlined, navEvents, key eventsTabKey)`, `_navAction(near_me_rounded/near_me_outlined, navNearby, _openNearby, key nearbyTabKey)`.
- Nav item: width 60, icon 24 (`active ? filled : outlined`), 2dp gap, label 10sp (active w600 `cText`, inactive w400 `cTextTertiary`). Nav action always looks inactive (outlined icon, tertiary).
- FAB: positioned `bottom: bottomInset + 10`, centered; 62×62 circle, `gradientOrange`, border 3dp `cDark`, shadow `shadowForElevation(4, accent: cOrange)` = `BoxShadow(cOrange@0.10, blur 16, spread -4, offset (0,6))`; icon `qr_code_scanner_rounded` 26 black; key `scanFabKey`.

**Actions**:
- `_onTabTap(i)`: ignore i==2; haptic light if `prefs.haptic_enabled ?? true` (reads prefs directly each time); set index; if i==0 → `_homeKey.currentState?.refreshAfterScan()`.
- `_openNearby()`: haptic light (same check); push `NearbyMeetupsScreen()` (nearby domain).
- `_openScanner()`: if `haptic_enabled ?? true` → `HapticService.medium()`; modal bottom sheet (transparent bg) `_ScanSheet`:
  - container: padding (16,12,16, bottomInset+24), `cCard`, top radius 16; grabber 40×4 `cTextTertiary` r2; 20dp gap;
  - option 1 `_ScanOption(icon qr_code_rounded, iconColor cOrange, title scanBadge, subtitle scanBadgeSub)` → pop sheet → push (slide-up `Offset(0,1)→0`, easeOutCubic) `MeetupVerificationScreen(meetup: Meetup(id:"global", city:"GLOBAL", country:"", telegramLink:"", lat:0, lng:0))`; after return → `HomeScreen.refreshAfterScan()` (verification screen itself replaces to the wallet on success);
  - 8dp; option 2 `(workspace_premium_rounded, Colors.amber #FFC107, scanReputation, scanReputationSub)` → pop → push (slide-up) `ReputationQRScreen()`;
  - 16dp bottom gap.
  - `_ScanOption`: padding 16×14, `cSurface`, radius `kTileRadius` (10), border 0.5 `cTileBorder`; icon box 40×40 `iconColor@0.12` radius 8 with icon 20; 14dp; title 14 w700 `cText`; 2dp; subtitle 11 `cTextTertiary`; trailing chevron 18 tertiary.
  - (Comment says "drei Optionen" but there are exactly two.)

**Home tour start** (`_checkHomeTour`, post-first-frame):
1. If `guide.wasTourCompleted(home)` → return.
2. If `guide.shouldAskForOnboarding()` (pref `guide_onboarding_asked` != true): `markOnboardingAsked()` **before** showing the dialog; show `_askForTour()`:
   AlertDialog, `barrierDismissible: false`, bg `cCard`, radius 16 with 1.5 `cOrange` side; title row lightbulb_outline (26, orange) + `guideWelcomeTitle` (19 bold); content `guideWelcomeBody` (15, secondary, height 1.4); actions TextButton `guideNoThanks` (secondary) → false, ElevatedButton (orange bg, black fg, padding 20×12) `guideStart` bold → true.
   If result != true → `guide.declineAllTours()` and return.
3. Poll: `for attempt in 1..20 { delay 500 ms; ready = HomeTour.glossaryKey mounted && HomeTour.homeMeetupKey mounted; AppLogger.debug('Guide', 'Dashboard-Tour, Versuch $attempt/20 — Ziele bereit: $ready'); if ready { guide.startTour(home, HomeTour.steps()); return } }`
4. Else `AppLogger.warn('Guide', 'Ziel-Widgets nach 10000 ms nicht gefunden — Dashboard-Tour startet nicht.')`.
Note: if the user previously answered "yes" but never completed the tour (app killed), next start runs the tour directly without asking.

### 2.5 HomeScreen (`home_screen.dart`)

Entry: AppShell tab 0 (always alive). Public method `refreshAfterScan()` is called by the shell.

#### 2.5.1 Layout (top → bottom)
`Padding(LTRB 16, statusBarTop + 12, 16, 12)` → `Column(crossAxis start)`:
1. Logo bar (§2.5.2)
2. 14dp
3. Profile header (§2.5.3)
4. 18dp
5. If `_deviceCompromised && !_dismissedIntegrityWarning`: device warning card + 10dp (`kTileGap`)
6. If `_activeSession != null`: active session card + 10dp
7. Home-meetup area wrapped in tour key `homeMeetupKey` (§2.5.5)
8. 10dp
9. `Expanded` → `LayoutBuilder(c)`:
   - `hasAvailable = _buildTileRows(excludeHomeMeetup: true, pinned: false).isNotEmpty`
   - `minPinned = c.maxHeight < 120 ? c.maxHeight : 120` (guard against clamp ArgumentError when keyboard/snackbar shrinks space)
   - `pinnedHeight = hasAvailable ? (c.maxHeight - 34).clamp(minPinned, c.maxHeight) : c.maxHeight`
   - `RefreshIndicator(onRefresh: _refreshAll(fromGesture: true), color cOrange, backgroundColor cCard, displacement 28)` → `SingleChildScrollView(AlwaysScrollableScrollPhysics)` → `Column(stretch)[ _buildScaledTileBlock(pinnedHeight), _buildAvailableSection(), SizedBox(8) ]`.

Effect: the pinned block fills the visible area minus 34dp (so the "Verfügbar" header peeks at the bottom); the Available section is reached by scrolling.

#### 2.5.2 Logo bar
Row: `SvgPicture.asset('assets/images/einundzwanzig_logo.svg', height: 16)`, Spacer, then header icons (padding 8, size 18, color `cTextTertiary`):
- refresh: `_refreshing ? hourglass_empty_rounded (no-op) : refresh_rounded → _refreshAll()`
- `help_outline_rounded` (tour key `glossaryKey`) → push `GlossaryScreen()` (glossary domain). Deliberately LEFT of the gear.
- `settings_rounded` (tour key `settingsKey`) → `_showSettings()`.

#### 2.5.3 Profile header
Row:
- Avatar 40×40 circle, fill `cCard`, border 1 `cTileBorder`, ClipOval. Priority: `_localProfilePic` (file path; `FileImage`) → `_profilePicUrl` (network) → fallback (cCard bg, centered first char of nickname uppercased or `'?'`, 18 w700 `cTextSecondary`). Any image error → fallback. Tap: only if `_profilePicUrl == null` → `_pickLocalProfilePicture()`.
- 12dp; nickname `Expanded`, 16 w700 `cText`, ellipsis.
- Score pill → push `ReputationCardScreen()`: padding 12×7, gradient TL→BR `[cOrange, cOrange@0.78]`, radius 12; text `(_trustScore?.totalScore ?? 0.0).toStringAsFixed(1)` black 17 w900 mono; 6dp; chevron_right 16 `black54`. (Shows "0.0" when badges are unreadable.)

`_loadProfilePicture()` (staleness-guarded by an incrementing request counter and the npub at call time):
1. `local = NostrProfileService.getLocalPicture()` (pref `local_profile_picture`); if stale → return.
2. If local non-empty → `_localProfilePic = local; _profilePicUrl = null`; return.
3. `_localProfilePic = null`; if `npub` empty → return.
4. `pk = Nip19.decodePubkey(npub)` (bech32 → 32-byte hex), `url = NostrProfileService.fetchProfilePicture(pk)` (kind:0 `picture`, profile domain); if not stale → `_profilePicUrl = url`. Errors swallowed.
When `_loadUser` sees a different npub, it bumps the counter and clears both pictures.

`_pickLocalProfilePicture()`: `ImagePicker.pickImage(gallery, maxWidth 400, maxHeight 400, imageQuality 80)`; on success `NostrProfileService.setLocalPicture(path)`; bump counter, set local, clear URL. Error → snackbar `homeImageLoadError(e.toString())`.
Native: Photo Picker (`ActivityResultContracts.PickVisualMedia`), downscale to ≤400px JPEG q80 and **copy into app files dir** (Flutter stores an image_picker cache path that the OS may purge).

#### 2.5.4 Device warning & live session cards
Device warning (`_checkDeviceIntegrity` → `DeviceIntegrityService.check()`; `isCompromised` = root indicators found): padding 12, `cCard`, radius 10, border 0.5 `cOrange@0.3`; row: `warning_amber_rounded` 18 `Colors.orange`, 10dp, text `DeviceIntegrityService.warningMessage` (hard-coded German: "Dieses Gerät zeigt Anzeichen von Root/Jailbreak. Kryptographische Schlüssel könnten weniger geschützt sein. Für maximale Sicherheit ein nicht modifiziertes Gerät verwenden.") 11 `orange.shade200`; close_rounded 16 `orange.shade300` → `_dismissedIntegrityWarning = true` (not persisted).

Live session card (`_checkActiveSession`: `RollingQRService.loadSession()`; shown if non-null and `!isExpired`; `isExpired = nowSec > expiresAt`):
- `AnimatedBuilder(_pulseController 2000 ms repeat reverse)`: padding 14, `cCard`, radius 10, border 0.5 `cGreen@0.25`.
- Row: dot 10×10 color `green@(0.5 + 0.5*v)` + glow `green@(0.3*v)` blur 8; 14dp; chip (padding 7×3, `green@0.15`, r4) text `statusLive` 9 w800 `green.shade300`; 10dp; if `meetupId.startsWith('evt:')` → `military_tech_rounded` 15 orange + 6dp; name (`meetupName` or `statusMeetupActive`) 13 w600 ellipsis; 8dp; `_sessionTimeLeft` 11 tertiary mono; 8dp; arrow_forward_ios 14 `green@0.4`.
- `_sessionTimeLeft = '${r.inHours}h ${(r.inMinutes % 60).toString().padLeft(2,'0')}m'` recomputed every 1 s (`Timer.periodic`); empty string until first tick. When expired → cancel timer, hide card.
- Tap → push `RollingQRScreen()`; on return `_checkActiveSession()`.

#### 2.5.5 Home-meetup area (favorites carousel)
`hasHome = user.homeMeetupId.isNotEmpty || user.favoriteMeetupIds.isNotEmpty`.

**No favorite (CTA)**: GestureDetector (onTap `_selectHomeMeetup`, onLongPress `_showReorderSheet`): padding 22, radius 10, border 1.5 `cOrange@0.35`, gradient TL→BR `[cOrange@0.10, #141416]`; row: 54×54 box radius 14 `cOrange@0.14` with `add_location_rounded` 28 orange; 16dp; column: `homeMeetupLabel` (orange 10 w800 ls 1.2), 5dp, `homeMeetupChoose` (17 w800), 3dp, `homeMeetupChooseSub` (12.5 secondary); chevron 24 orange.
`_selectHomeMeetup`: push `MeetupSelectionScreen()`; after return **await** `_loadUser()` THEN `_loadNextHomeMeetup()` (order matters so the widget doesn't get stale favorites).

**Favorites**: `cards = _favCards.isNotEmpty ? _favCards : [FavCard(key: homeMeetupId, label: labelFor(homeMeetupId), city: cityFor(homeMeetupId), event: _nextHomeMeetup)]`. GestureDetector(onLongPress `_showReorderSheet`) → Column: `SizedBox(height 152, PageView(controller, onPageChanged → _favPage))`; if >1 card: 8dp + centered dots (`AnimatedContainer 200 ms`, margin h3, size `active 18×6 / 6×6`, color `active cOrange / cTextTertiary@0.4`, radius 3).

**Fav card** (`_favCardContent`): `meetup = MeetupService.resolveFavorite(card.key) ?? _meetupForCity(card.city)` (`_meetupForCity`: first of cached list then fallback `allMeetups` with `city == city`).
Container padding LTRB(16,14,16,0), radius 10, gradient TL→BR `[cOrange@0.13, cOrange@0.04, #141416]` stops `[0, .45, 1]`, border 1.0 `cOrange@0.30`, shadow `cOrange@0.05 blur 20 offset (0,5)`.
- Top (`Expanded` Row, center): crest (size 60) · 14dp · column(center): [chip `homeMeetupLabel` (pad 8×3, `cOrange@0.20`, r6, orange 10 w900 ls1.1) · 8dp · country text `meetup?.country ?? 'DE'` (12 w600 secondary)] · 5dp · label `card.label.toUpperCase()` maxLines 2 ellipsis, w900, ls -0.5, height 1.05, size by length: `>20 → 13`, `>15 → 15`, `>10 → 17`, else `22` · 5dp · next-event line.
- Bottom: action bar (§ below).

Crest (`_crestCoverFlow(city, meetup, size)`): URL = `MeetupCalendarService.absoluteImageUrl(meetup.logoUrl.isNotEmpty ? meetup.logoUrl : meetup.coverImagePath)` (relative paths get `https://portal.einundzwanzig.space/` prefixed); if empty → `MeetupCalendarService.logoFor(city)`; if still empty → nothing. Box `size*1.12 × size`; transform origin centerLeft, perspective `Matrix4.identity()..setEntry(3,2,0.0024)..rotateY(-0.55)`; ShaderMask `dstIn` linear L→R `[white, white70, white24, transparent]` stops `[0, .35, .7, 1]`; rounded 10; image `size×size` cover; error → nothing.
Native: `Modifier.graphicsLayer { rotationY = -31.5f (=-0.55 rad); cameraDistance = …; transformOrigin = TransformOrigin(0f, 0.5f) }` + `drawWithContent` with `BlendMode.DstIn` gradient (use `CompositingStrategy.Offscreen`); image loading via Coil.

Next-event line (`_favNextEvent`):
- `_countdownLoading` → `LinearProgressIndicator` 90×14 orange on transparent.
- `event == null` → `event_busy_rounded` 13 tertiary + `homeMeetupNoDate` (12 tertiary, 1 line).
- else `days = _daysUntil(event.startTime)`; label `days <= 0 ? homeMeetupToday : days == 1 ? homeMeetupTomorrow : homeMeetupInDays(days)`; color `days <= 0 → cOrange; days <= 3 → cOrange@0.8; else cTextSecondary`, 12.5 w800; then `'· ${d}.${m}.${yyyy}'` (**no zero padding**, e.g. `· 5.3.2026`) 12 tertiary, 1 line.

Action bar (`_favActions`): top border 0.5 `cTileBorder`; three `Expanded` fields separated by `0.5×26` `cTileBorder` dividers:
1. `event_rounded`, `btnEvents`, accent orange → push `CalendarScreen(initialSearch: card.city, initialMeetupId: meetup?.id)`.
2. `forum_rounded`, `btnChat`, accent `cNostr`, badge `_chatUnread[card.key] ?? 0` → `_openMeetupChat(card.key, card.label)`.
3. `info_outline_rounded`, `btnInfo`, accent secondary; `onTap = meetup == null ? null : push MeetupDetailsScreen(meetup)`.
Field: InkWell r8, vertical padding 11, centered row: icon 15 (disabled → `cTextTertiary@0.5`) with optional badge (positioned right -6 top -5, pad 4×1, minWidth 13, orange r7, text `badge > 99 ? '99+' : '$badge'` black 8.5 w900 height 1.3), 6dp, label 11.5 w800 ls 0.2 (disabled color as icon).

#### 2.5.6 Tile engine
Tile definition `_TileDef(id, label (German, used only by the customize sheet), span 1|2|3 (thirds), builder, visible() default true, removable default true)`.

Definitions in this exact list order (`_tileDefs`):

| id | label (hard-coded) | span | removable | visible | tour key |
|---|---|---|---|---|---|
| `trust_score` | Trust Score | 2 | **false** | true | trustScoreKey |
| `home_meetup` | Home Meetup | 3 | **false** | true | homeMeetupKey (rendered separately) |
| `event_chats` | Meine Termine | 3 | **false** | true | — |
| `reputation` | Reputation | 1 | true | true | reputationKey |
| `community` | Community | 2 | true | true | communityKey |
| `trust_network` | Vertrauensnetzwerk | 2 | true | true | wotKey |
| `events` | Events | 1 | true | true | eventsKey |
| `shoutout` | Shoutout | 1 | true | true | shoutoutKey |
| `podcast` | Podcast | 1 | true | true | podcastKey |
| `satoshiduell` | SatoshiDuell | 2 | true | true | — |
| `portal_area` | Portal | 2 | true | true | — |
| `plebrap` | PlebRap | 3 | true | true | — |
| `nostr` | Nostr | 1 | true | true | — |
| `portal_connect` | Portal | 2 | true | true | portalConnectKey |
| `converter` | Rechner | 1 | true | true | umrechnerKey |
| `btc_dashboard` | Bitcoin | 2 | true | true | bitcoinKey |
| `news` | News | 2 | true | true | newsKey |
| `portal` | Meine Meetups | 2 | true | true | myMeetupsKey |
| `organisator` | Organisator | 3 | true | `user.isAdmin \|\| user.isReviewDemo` | — |

Constants:
```dart
static const _defaultOrder = ['home_meetup', 'event_chats', 'reputation', 'trust_network', 'community', 'nostr', 'converter', 'btc_dashboard', 'news', 'portal', 'events', 'shoutout', 'podcast', 'satoshiduell', 'portal_area', 'plebrap', 'organisator'];
static const _defaultHidden = {'news', 'shoutout', 'podcast', 'nostr', 'portal', 'events', 'satoshiduell', 'portal_area', 'plebrap'};
```
**Only ids in `_defaultOrder` can ever be drawn.** `trust_score` and `portal_connect` are NOT in it → they are effectively **dead tiles** (never rendered; their tour steps always auto-skip). Keep the ids reserved for persisted-data compatibility.

"Pinned" = not in `_hiddenTiles`; "Available" = in `_hiddenTiles`.

Row packing (`_buildTileRows({excludeHomeMeetup, pinned})`) — reproduce exactly:
```dart
final visibleTiles = _tileOrder
  .map((id) => _tileDefs.where((t) => t.id == id).firstOrNull)
  .where((t) => t != null && t.visible() && (_hiddenTiles.contains(t.id) != pinned))
  .cast<_TileDef>()
  .where((t) => !excludeHomeMeetup || t.id != 'home_meetup')
  .toList();
final rows = <Widget>[]; int i = 0;
while (i < visibleTiles.length) {
  final tile = visibleTiles[i];
  if (tile.span == 3) { rows.add(Row([Expanded(tile)])); i++; }
  else {
    final row = <_TileDef>[tile]; int rowSpan = tile.span;
    while (i + row.length < visibleTiles.length && rowSpan < 3) {
      final next = visibleTiles[i + row.length];
      if (next.span == 3) break;
      if (rowSpan + next.span > 3) break;
      row.add(next); rowSpan += next.span;
    }
    rows.add(Row(stretch, [for j: if (j>0) SizedBox(width: 10), Expanded(flex: row[j].span, tile)]));
    i += row.length;
  }
}
```
Note: a row containing a single span-2 tile is `Expanded(flex: 2)` alone → **full width**. Rows never reorder tiles to fill gaps (greedy, order-preserving).

Pinned block (`_buildScaledTileBlock(targetHeight)`): `minRowHeight = 120`; `gaps = (n-1)*10`; `perRow = (targetHeight - gaps)/n`; `rowHeight = perRow >= 120 ? perRow : 120`; every row gets exactly `rowHeight` (block can exceed target; it scrolls).

Available section (`_buildAvailableSection`): if no rows → nothing. Else: 10dp; section header (tour key `customizeKey`) `tilesAvailable` uppercased, `count: rows.length` (**number of rows, not tiles**), `collapsed: _availableCollapsed`, tap toggles; long-press → customize sheet. If `!_availableCollapsed || _editTileId != null`: each row `Opacity(0.8, SizedBox(height: 124, row))` separated by 10dp.
Section header: padding LTRB(2,2,2,8): label 10.5 ls1.4 w700 tertiary; if count: 7dp + count same style; Spacer; chevron `expand_more_rounded`(collapsed)/`expand_less_rounded` 18 tertiary.

Tile container (`_tile`): `Pressable(onTap: tap == null ? null : () { HapticService.light(); tap(); })`.
- Pinned style (set via `_buildingPinnedTile` flag at build time): radius 10, gradient TL→BR `[cOrange@0.13, cOrange@0.04, #141416]` stops `[0,.45,1]`, border 1.0 `cOrange@0.30`, shadow `cOrange@0.05 blur 20 offset (0,5)`.
- Available style: `cCard`, radius 10, border 0.5 `cTileBorder`.
- ClipRRect r10 → Stack: optional icon watermark (`Positioned(right -18, bottom -18)`, size 84, `accentColor@0.10`); optional asset watermark (`right -12, bottom -12`, opacity 0.13, 100×100 contain); content `Positioned.fill(Padding LTRB 16,16,16,18)`.
- `opacity` parameter is ignored (legacy).

Hero content (`_heroContent(icon, accent, label, value, sub?, valueColor?, valueSize=24, trailing?)`): Column(start, min):
Row[icon 16 accent · 6dp · Expanded(FittedBox scaleDown, label.toUpperCase() 12 ls1.1 w800 accent, 1 line) · trailing?] · 7dp · value (maxLines 2 ellipsis, `valueSize`, w800, height 1.12, color `valueColor ?? cText`) · if sub non-empty: 3dp + sub (1 line ellipsis, 12.5 `cTextSecondary`).

#### 2.5.7 Edit mode (`_wrapEditable(tile, child)`)
- Tour key from `_tourKeys[tile.id]` wraps the child (so the overlay measures the full tile).
- Not editing (`_editTileId == null`): `GestureDetector(onLongPress: _editTileId = tile.id)`.
- This tile selected: tap → cancel (`_editTileId = null`); visual: Container radius 10 border 2 orange + shadow `cOrange@0.25 blur 14`, clipped child; `open_with_rounded` 15 orange at (top 9, left 9); if removable: pin button at (top -9, right -7), 30×30 circle, fill `isPinned ? cOrange : cCard`, border 1.5 orange, icon `push_pin_rounded` (black) / `push_pin_outlined` (orange) 15 → `_togglePin(tile)`. The selected tile is `Draggable<String>(data: id, feedback: Opacity(0.9, SizedBox(190×96, child)), childWhenDragging: DottedPlaceholder())`.
- Other tiles while editing: `DragTarget<String>(onWillAccept: data != tile.id, onAccept: _moveTile(data, tile.id))`; while hovered: border 1.5 `cOrange@0.7` radius 10 and child opacity 0.6.
- `_moveTile(dragged, target)`: no-op if same or not found; `order.removeAt(from); order.insert(to, dragged)` where `to` = index of target in the ORIGINAL list (before removal); set order, exit edit mode, save, `AppLogger.diag('Dashboard', 'Kachel "$draggedId" vor "$targetId" einsortiert.')`.
- `_togglePin(tile)`: no-op if `!removable`; toggle membership in `_hiddenTiles`; exit edit mode; save; `AppLogger.diag('Dashboard', 'Kachel "${tile.id}" ${hidden ? "geloest" : "angeheftet"}.')`.
- `DottedPlaceholder`: `cOrange@0.04` fill, radius 10, border 1 `cOrange@0.45` (solid, despite the name).
- Native: implement with `Modifier.pointerInput { detectDragGesturesAfterLongPress }` on the selected tile and hit-testing tile bounds (collected with `onGloballyPositioned`) for drop targets.

#### 2.5.8 Tile catalogue (builders)

| id | Accent / watermark | Content | Tap |
|---|---|---|---|
| `event_chats` (My events) | accent `cNostr`, watermark `event_available_rounded` | hero: icon event_available, label `tileEventChats`, value `'${_myEvents.length + _favMeetupDates.length}'`, valueColor orange if `_myEventsUnread > 0`; sub: both empty → `tileEventChatsNone`; unread>0 → `tileEventChatsUnread(n)`; else `tileEventChatsSub` | push `MyEventsScreen(events: _myEvents, meetupDates: _favMeetupDates→MyMeetupDate(favKey,label,event,attendees), onOpenMeetupChat: _openMeetupChat, onChanged: _loadMyEvents)`; on return `_loadMyEvents()` |
| `reputation` | amber, `workspace_premium_rounded` | label `tileReputation`, value `score?.totalScore.toStringAsFixed(1) ?? '—'`, valueSize 26 (white), sub `counted > 0 ? tileReputationBadges(counted) : tileReputationCheck` where `counted = myBadges.where(isNostrSigned && !isOrganizer).length` | push `ReputationQRScreen()` |
| `trust_network` | watermark color orange, `account_tree_rounded` | icon account_tree (accent `cGreen`), label `tileActEncounters`, value `tileTrustNetwork` (17), sub `tileTrustNetworkSub` | push `MyNetworkScreen()` |
| `community` | watermark `cCyan` (gold), `hub_rounded` | icon hub (green), label `tileActExchange`, value `tileCommunity` (17), sub `tileCommunityPortal` | push `CommunityHubScreen()` |
| `events` | `hasToday ? cOrange : cTextTertiary`, `event_rounded` | label `tileEvents`; value `hasToday ? '$_eventsToday' : tileEventsCalendar`; size 26/16; valueColor `cText`/`cTextSecondary`; icon accent orange/secondary; sub `hasToday ? tileEventsToday : null` | push `EventCalendarScreen(initialDay: DateTime.now())`; on return `_checkActiveSession(); _loadMyEvents();` |
| `btc_dashboard` | orange, no watermark | `_BtcDashboardTileContent` (below) | push `BitcoinDashboardScreen()` |
| `converter` | `cCyan`, `swap_vert_rounded` | price = `MempoolService.lastDashboard?.priceIn(cur) ?? 0`; `satsPerUnit = price > 0 ? round(100000000 / price) : 0`; value `satsPerUnit > 0 ? '1 ${symbol(cur)} = ${groupInt(satsPerUnit)} sats' : tileConverter` (17); label `tileActConvert`; sub `tileConverterSub` | push `ConverterScreen()` |
| `news` | orange, `article_rounded` | label `tileNews`; value latest title or `tileNewsSub` (size 14; color text vs secondary); trailing red pill (pad 6×1, `cRed` r8, white 10.5 w800) with `_unreadNews` if > 0 | `_openNews()` |
| `portal` (My meetups) | orange, `groups_rounded` | icon groups (accent `cCyan`), label `tileActLookup`, value `tilePortal` (17), sub `tilePortalSub`, trailing chevron 16 | push `PortalMeetupsScreen()` |
| `shoutout` | orange, `campaign_rounded` | label `tileActSend`, value `tileShoutout` (17), sub `tileShoutoutSend` | `_openUrl('https://shoutout.einundzwanzig.space')` |
| `podcast` | `cPurple`, `podcasts_rounded` | icon accent `cPurpleLight`, label `tileActListen`, value `tilePodcast` (17), sub `tilePodcastListen` | `_openUrl('https://einundzwanzig.space/podcast/')` |
| `plebrap` | orange, `graphic_eq_rounded`; no tap on tile itself | Row: hero (label literal `'PlebRap'`, value = current song title (17, text) or `chPlebrapSub` (14, secondary), sub = artist) · play/pause 40dp orange circle (spinner black stroke 2 while `PlebrapAudio.loading`; icon pause/play 24 black) → `PlebrapAudio.toggle` · 8dp · `skip_next_rounded` 26 → `PlebrapAudio.next` · 6dp · `queue_music_rounded` 24 secondary → push `PlebrapPlayerScreen()` | — |
| `satoshiduell` | gold `#FFC93C`, asset watermark `assets/images/satoshiduell.png` | header row bolt 16 gold + literal `'SATOSHIDUELL'` (12 ls1.1 w800 gold); value `n = myTurn + lobby`: `n > 0 ? '$n'` (26, 1 line, text) `: chDuellSub` (14, 2 lines, secondary); status line (12.5, 1 line) = joined with `'  ·  '` (tertiary) of: `'⚡ $myTurn ${sdShortTurn}'` (gold w700) if >0, `'$lobby ${sdShortLobby}'` (orange w600) if >0, `'$waiting ${sdShortWait}'` (green) if >0 | `npub = SigningService.npub()`; open `https://satoshiduell.de/?npub=$npub` or `https://satoshiduell.de/` |
| `portal_area` | orange, `public_rounded` | label literal `'Portal'`, value `chPortalSub` (17) | push `PortalAreaScreen()` |
| `nostr` | `cNostr`, no watermark | Stack: Column[`assets/images/nostr_icon.png` 30×30 opacity `new ? 1.0 : 0.55`, 12dp, `tileNostr` 16 w700, 3dp, `tileNostrCommunity` 13 secondary]; if new: 7dp orange dot at top-right | `_openNostr()` |
| `organisator` | orange, `admin_panel_settings_rounded` | label `tileActManage`, value `tileOrganizer` (17), sub `tileOrganizerPanel`, trailing chevron | push `AdminPanelScreen()`; on return `_checkActiveSession()` |
| `trust_score` (dead) | plain card, chevron | 52×52 orange gradient plaque with score (`'—'` if unreadable), `tileTrustScore`, level row (lock + `badgesUnreadableShort` if unreadable; else level icon/color + `localizedLevel` or `levelNew`; verified icon if `meetsPromotionThreshold`), progress bar (`promotionProgress`, 3.5 high) if not met | push `ReputationCardScreen()` |
| `portal_connect` (dead) | green/red | switch-style tile `portalConnected`/`portalConnect`, sub `portalTileSub` | `_togglePortalConnection()` |

Level visuals: `VETERAN → Colors.amber, Icons.bolt`; `ETABLIERT → Colors.green, Icons.shield`; `AKTIV → cCyan, local_fire_department`; `STARTER → cOrange, eco`; default/`NEU`/null → `cTextTertiary, fiber_new`.

`_BtcDashboardTileContent` (stateful): init `_d = MempoolService.lastDashboard`; `_load()` immediately and every **60 s** (`Timer.periodic`): `d = await MempoolService.getDashboardData(); setState; WidgetService.updateBitcoin(d)` (writes widget even when `d.isDead`!).
UI: Stack[ background `assets/icons/bitcoin.svg` 104×104 opacity 0.10 at right -18 vertically centered; Row(center)[ Expanded Column(center,start)[Row[bitcoin.svg 22, 8dp, literal `'Bitcoin'` 16 w700], 4dp, literal `'Netzwerk & Kurs'` 13 secondary, if d!=null and `priceIn(cur) > 0`: 8dp + `CurrencyService.formatPrice(p, cur)` 13 w600 secondary (listens to currency)], Column(end)[Row[7dp dot (`d != null && !d.isDead ? cGreen@0.7 : cTextTertiary`), 6dp, literal `'BLOCK'` 10 ls2 tertiary], 4dp, block height `_fmtInt(blockHeight)` (thousands '.') or `'––'` — orange 26 w800 height 1.0 mono] ] ].

#### 2.5.9 Customize sheet (`_CustomizeSheet`)
Opened by `_showReorderSheet()`: modal bottom sheet, `isScrollControlled`, top radius 20; args: copies of `_tileOrder`, `_hiddenTiles`, `tileDefs.where(visible())`; `onSave(order, hidden)` → set state + `_saveTileOrder()`.
- `_requiredTiles = {'home_meetup', 'reputation'}` (lock icon; NOT the same as `removable=false`!).
- Container padding LTRB(16,12,16,32), maxHeight 85% screen. Grabber 40×4. 16dp. Row: `'ANPASSEN'` (13 w700 ls0.5) · Spacer · TextButton `'FERTIG'` (w700) → `onSave(_order,_hidden)` + pop. 4dp. Hint `'Halten & ziehen zum Sortieren  ·  🔒 = Pflicht  ·  ✕ = ausblenden'` (10 tertiary). 16dp.
- Scrollable: section header `'AKTIV'` + `'Alle Kacheln können verschoben werden'`; reorderable list of `visibleTiles = _order.where(def exists && !hidden)`; row: margin bottom 4, padding 10×12, `cCard` r8 border 0.5; drag handle 16, icon (by id: trust_score shield, home_meetup home, reputation workspace_premium, community hub, events event, shoutout campaign, podcast podcasts, organisator admin_panel_settings, else widgets_rounded) 15 secondary, label (13 w600; locked → secondary color), trailing lock 13 or `Switch(value: true)` → hide.
  Reorder (`onReorderItem(oldI, newI)` — newI already adjusted for removal): `oldOrderIdx = _order.indexOf(visible[oldI]); newOrderIdx = _order.indexOf(visible[newI]); item = _order.removeAt(oldOrderIdx); _order.insert(newOrderIdx, item);`
- If `availableTiles = tileDefs.where(removable && hidden)` non-empty: 20dp, header `'VERFÜGBAR'` + `'Schalter aktivieren zum Hinzufügen'`, rows (`cSurface`, border `cBorder`, padding 14×6, tertiary icon/label, `Switch(false)` → show).
- Dismissing by swipe/back discards changes.
- All strings here are **hard-coded German** (port as-is or localize — see §14).

#### 2.5.10 Data loading & lifecycle

`initState` order: add currency listener (→ setState); `_loadTileCounters()` (no await); pulse controller; `_initTileDefs()`; `_loadTileOrder()`; `_loadAll()`; `_loadAppVersion()` (PackageInfo version → settings footer); `WidgetService.refreshNews()`; add lifecycle observer; `_pollWidgetTarget()`; `_scheduleMidnightRefresh()`.

On app **resume**: `_pollWidgetTarget(); _loadNextHomeMeetup(); _scheduleMidnightRefresh(); _checkActiveSession(); _loadChatUnread(); BadgeSyncService.retryIfDirty();`

`_loadAll()`:
```
await _loadUser();
if (nickname == 'Anon' || nickname.isEmpty) { push IdentitySetupScreen(); await _loadUser(); }
await _loadBadges(); await _calculateTrustScore(); await _reVerifyAdminStatus();
// fire-and-forget, in this order:
_loadIdentityData(); _checkActiveSession(); _loadChatUnread(); _loadMyEvents();
_syncOrganicAdminsInBackground(); _checkDeviceIntegrity(); _loadNextHomeMeetup();
_loadProfilePicture(); _checkNostrNew();
```

`_loadUser({skipOrgCheck=false})`: `u = UserProfile.load()`; if any favorite/home → `m = MeetupService.fetchMeetups()` (network, every call); if empty → fallback `allMeetups`; cache list; `_homeMeetup = m.firstWhere(city == u.homeMeetupId)` (legacy, only used for widget routing fallback); if npub changed → reset picture state; set user; then `if (!skipOrgCheck) _checkPortalOrganizer();` and `_refreshPortalConnected()` (both not awaited).

`_loadBadges()`: `badges = MeetupBadge.loadBadges(); unreadable = MeetupBadge.badgesUnreadable; if (!unreadable) await BadgeClaimService.ensureBadgesClaimed(badges);` set global `myBadges`; if unreadable and not yet shown → snackbar `badgesUnreadableBody` (8 s floating) once per screen instance; `if (!unreadable && badges.isNotEmpty) ReputationPublisher.publishInBackground(badges)`.

`_calculateTrustScore()`: `unreadable → _trustScore = null`; `myBadges empty → calculateScore(badges: [], firstBadgeDate: null)`; else `firstBadgeDate = min(date)`, `calculateScore(badges: myBadges, firstBadgeDate, coAttestorMap: null)` (trust-score domain).

`_reVerifyAdminStatus()`: skip if `MeetupBadge.badgesUnreadable` (would permanently revoke vouch admin). `v = await _user.reVerifyAdmin(myBadges)`; if `v.isAdmin && (v.source == 'trust_score' || v.source == 'vouch_consensus')` → `PromotionClaimService.publishAdminClaim(badges: myBadges, meetupName: homeMeetupId.isNotEmpty ? homeMeetupId : 'Unbekannt')` (errors swallowed) + `AppLogger.info('Admin', 'Organisator-Status bestaetigt (${v.source}).')`. On exception: `adminViaVouch = false; isAdminVerified = isAdmin` (in memory only, not saved).

`_loadIdentityData()`: `HumanityProofService.getStatus()`; if `needsReverification` → `reverifyIfNeeded()` (side effect: persists refreshed status). Errors swallowed.

`_syncOrganicAdminsInBackground()`: `PromotionClaimService.syncOrganicAdmins()` (errors swallowed).

`_checkNostrNew()`: `lastSeen = prefs.nostr_last_seen ?? 0` (unix seconds); `if lastSeen < now - 86400 → _nostrHasNew = true`.
`_openNostr()`: set `nostr_last_seen = now (seconds)`; clear dot; try `launchUrl('nostr:npub1qv02xpsc3lhxxx5x7xswf88w3u7kykft9ea7t78tz7ywxf7mxs9qrxujnc', external)`; if false or throws → `https://njump.me/npub1qv02xpsc3lhxxx5x7xswf88w3u7kykft9ea7t78tz7ywxf7mxs9qrxujnc`.

`_openUrl(url)`: `launchUrl(external)`; if false → snackbar `homeCouldNotOpen(url)`. (Only used with hard-coded URLs.)

`_loadTileCounters()` (once, at init only — not on refresh/resume):
- News: `n = NewsService.unreadCount()`, `articles = NewsService.cachedArticles()`, latest = article with max `publishedAt` → `title.trim()`. Warn log on failure `'News-Zaehler fehlgeschlagen'`.
- Events today: local day `[00:00, +1 day)`; `Future.wait([MeetupCalendarService().fetchMeetupsPortalFirst(), CalendarEventService.fetchEvents()])`; count portal meetup events with `startTime` today + Nostr calendar events with `start` today (courses excluded). `AppLogger.diag('Dashboard', 'Kachel-Zaehler: $_unreadNews News, $count Event(s) heute.')`; warn `'Event-Zaehler fehlgeschlagen'`.

`_openNews()`: `WidgetService.markNewsSeen()`; `NewsService.markRead()` (no await); `_unreadNews = 0`; push `NewsScreen()`.

`_loadNextHomeMeetup()` — see §8.3 (algorithm). Also writes the Android widget and triggers `_loadMeetupRsvps(events)`.

`_loadMeetupRsvps(allEvents)`, `_loadMyEvents()`, `_loadChatUnread()`, `_findRoomFor()`, `_openMeetupChat()` — see §8.4–8.6.

`refreshAfterScan()` (shell, after scan, tab re-select): parallel, un-awaited: `_loadBadges(); _calculateTrustScore(); _loadNextHomeMeetup(); _checkPortalOrganizer(); _refreshPortalConnected(); _loadMyEvents();` (note: trust score is computed from the *previous* `myBadges` because `_loadBadges` isn't awaited — native port should sequence badges → score).

`_refreshAll({fromGesture=false})` (manual refresh):
```
if (_refreshing) return; _refreshing = true;
if (!fromGesture) snackbar(refreshRunning, bg cCard, 2 s, floating);
try {
  await _loadUser(skipOrgCheck: true); _loadProfilePicture();
  await _loadBadges(); await _calculateTrustScore();
  await _checkPortalOrganizer();   // FIRST: clears admin cache on revocation
  await _reVerifyAdminStatus();    // THEN WoT/vouch verification on clean cache
  _loadNextHomeMeetup(); _loadMyEvents(); _loadChatUnread();
} catch (_) {}
_refreshing = false;
if (!fromGesture) snackbar(refreshDone, bg Colors.green.shade700 (#388E3C), floating, default 4 s);
```

Widget launch target routing: MethodChannel `einundzwanzig/widget` → `getLaunchTarget` (Kotlin reads & removes `pending_target` from SharedPreferences file `e21_widget`). Polled at init and on every resume, guarded by `_routingWidgetTarget`; wait 250 ms then `_routeWidgetTarget(t)`:
- `'news'` → `_openNews()`
- `'bitcoin'` → push `BitcoinDashboardScreen()`
- `'meetup'` → `frontCity = _favCards.first.city ?? _homeMeetup?.city`; if non-empty: `key = _favCards.first.key ?? user.homeMeetupId` → push `CalendarScreen(initialSearch: frontCity, initialMeetupId: MeetupService.resolveFavorite(key)?.id)`; else push `CalendarScreen()`.
Native: the widget's click `PendingIntent` can directly target `MainActivity` with an extra; to stay robust against MIUI intent replay, keep the router-activity + "read-once pending target" pattern, or use unique `data` URIs per area + `onNewIntent` handling and clear the intent after consumption.

Midnight refresh: timer to next local midnight + 2 s → `_loadNextHomeMeetup()` and reschedule; also re-armed on resume (Doze-safe).

#### 2.5.11 Portal organizer detection (`_checkPortalOrganizer`) and connection toggle
See §8.7 for the exact state machine. UI pieces:
- `_showPortalHint()` once per screen instance and only if `!_user.isAdmin`: snackbar `portalConnectForOrganizer`, bg `Colors.orange.shade800` (#EF6C00), 8 s, floating, action `portalConnect` (white) → `_togglePortalConnection()`.
- Token-mismatch with external signer: snackbar `portalTokenMismatch`, bg `orange.shade800`, 6 s.
- `_refreshPortalConnected()`: `_portalConnected = PortalApiService.tokenMatchesCurrentKey()`.
- `_togglePortalConnection()`: if connected → `PortalApiService.logout()`, connected=false. Else snackbar `portalConnecting` (cCard, 8 s, floating) → `res = loginWithNostr()` → hide snackbar → ok: connected=true, snackbar `portalConnected` (green.shade700, floating), then `_checkPortalOrganizer()`; error: snackbar `'${portalLoginFailed}: ${res.error ?? ''}'` (cRed, floating).

#### 2.5.12 Reset flow (`_resetApp`, `_performReset`)
1. AlertDialog (default style): title `resetTitle`, content `resetBody`, actions `resetCancel` → false, `resetConfirm` (red bold) → true. Dismiss = false.
2. If confirmed: AlertDialog `barrierDismissible: false`, bg `cCard`, radius 16; title row shield_outlined 22 orange + `resetBackupTitle` (16 w700); content `resetBackupBody` (13 secondary height 1.5); `actionsOverflowDirection: down`; actions: ElevatedButton.icon (orange, black fg, radius 10, icon cloud_upload_rounded 16) `resetBackupCreate` → 'backup'; TextButton `resetBackupSkip` (cRed) → 'skip'; TextButton `resetCancel` (secondary) → 'cancel'.
3. null/'cancel' → abort. 'backup' → `ok = await BackupService.createBackup(context)`; `!ok` → abort (never reset without the successful backup the user asked for); then AlertDialog (cCard r16) title `resetBackupTitle`, content `resetBackupDone`, actions `resetCancel` → false, `resetNowConfirm` (cRed bold) → true; false/dismiss → abort.
4. `_performReset()` — exact order (each step's failure isolation matters):
```dart
try { await SigningService.disconnectNip46(); } catch (_) {}   // close relays + in-memory session key
myBadges.clear();
await MeetupBadge.resetAll();          // badge data in BOTH places BEFORE prefs.clear()
final p = await SharedPreferences.getInstance();
await p.clear();                       // all "flutter."-prefixed prefs
try { await SecureKeyStore.deleteKeys(); } catch (_) {}            // nostr_nsec_key, nostr_npub_key, nostr_priv_hex
try { await SecureKeyStore.deleteNip46ClientKey(); } catch (_) {}  // nip46_client_sk (idempotent)
try { await LocalKeyVault.clearAll(); } catch (_) {}               // easyauth_* incl. password-encrypted ncryptsec copy
try { await PortalApiService.logout(); } catch (_) {}              // DELETE /api/mobile/token + local token delete
await NostrProfileService.clearCache();                           // picture/metadata/relay caches + local_profile_picture
pushAndRemoveUntil(IntroScreen)
```
Settings sheet entry: "App zurücksetzen" (§2.6).

### 2.6 Settings bottom sheet (`HomeScreen._showSettings`)
Entry: gear icon on Home. Reads `guide` before showing and `haptic = prefs.haptic_enabled ?? true`.
`showModalBottomSheet(isScrollControlled, backgroundColor cDark, top radius 24)` → `StatefulBuilder` → `DraggableScrollableSheet(initial 0.85, min 0.5, max 0.95, expand false)`:
- 12dp, grabber 40×4 tertiary r2, 20dp.
- Header (padding h24): icon box (padding 10, `gradientOrange`, r14, settings_rounded 22 black) · 14dp · Column[`settingsHeaderTitle` 20 w800, `settingsHeaderSub` 13 secondary].
- 20dp, then `ListView(padding LTRB 20,0,20,40)`:

| Group (label key) | Row | Icon / color | Title / subtitle keys | Action | Tour key |
|---|---|---|---|---|---|
| `settingsSecAccount` | Profile | person_rounded / cOrange | `settingsProfile` / `settingsProfileSub` | pop; push `ProfileEditScreen()` | profileKey |
| `settingsSecData` | Backup | cloud_upload_rounded / cCyan | `settingsBackup` / `settingsBackupSub` | pop; `BackupService.createBackup(context)` | backupKey |
| | Restore | restore_rounded / cCyan | `settingsBackupLoad` / `settingsBackupLoadSub` | pop; `await BackupService.restoreBackup(context)`; then `_loadAll()` (always, even if aborted) | — |
| `settingsSecNetwork` | Relays | hub_rounded / cPurple | `settingsRelays` / `settingsRelaysSub` | pop; push `RelaySettingsScreen()` | relaysKey |
| | Mempool | dns_rounded / cOrange | `settingsMempool` / `settingsMempoolSub` | pop; push `MempoolSettingsScreen()` | — |
| `settingsSecApp` | Language | language_rounded / cGreen | `settingsLanguageTitle` / `'${flag}  ${LocaleController.displayName(current)}'` (two spaces) + chevron | language dialog (sheet stays open) | languageKey |
| | Currency | currency_exchange_rounded / cGreen | `settingsCurrencyTitle` / `'$cur · ${currencyName}'` + chevron | currency dialog | — |
| | Haptics | vibration_rounded / cGreen | `settingsHaptic` / `settingsHapticOn`\|`settingsHapticOff`; trailing `Switch(activeThumbColor cOrange)` | `prefs.setBool('haptic_enabled', v)` | hapticKey |
| | Diagnose-Log | bug_report_rounded / cCyan | `settingsLogTitle` / `settingsLogSub` | pop; push `LogScreen()` | — |
| | Restart tours | replay_rounded / cOrange | `settingsRestartGuide` / `settingsRestartGuideSub` | pop; `guide.resetAllTours()`; snackbar `settingsGuideReset` (bg cOrange) | restartKey |
| `v4vSectionTitle` | V4V | bolt_rounded / cOrange | literal `'V4V'` / `v4vSectionSubtitle` | pop; push `V4VScreen()` | — |
| `settingsSecDanger` | Reset | delete_forever_rounded / cRed (danger) | `settingsReset` / `settingsResetSub` | pop; `_resetApp()` | resetKey |

- Groups separated by 18dp; after the last group 20dp + centered `_appVersion.isEmpty ? '' : '21Meetup · Version $_appVersion'` (11 tertiary) + 8dp.
- Group: label (padding left 6 bottom 8; 10 w700 ls1.4 tertiary) + card (`cCard`, r16, border 0.5 `cTileBorder`, clip).
- Row (`_sRowCustom`): InkWell, padding 14×12; icon box (pad 8, `color@0.12`, r10, icon 20); 14dp; title 14 w600 (`danger ? cRed : cText`), 2dp, subtitle 11 tertiary; trailing (default chevron 18 tertiary; danger: `cRed@0.5`).
- Divider between rows: 0.5 high `cTileBorder`, left margin 56.
- `whenComplete` (sheet closed): `if (guide.activeTour == settings) guide.finishTour()` — marks the settings tour completed.
- 650 ms after `showModalBottomSheet` is called: `guide.startTour(settings, SettingsTour.steps())` (no-op if completed).

Language dialog (`_showLanguagePopup`): Dialog bg `cCard` r16, Column(min): header row (padding LTRB 20,20,20,8) language_rounded 20 orange + `settingsLanguageChoose` (16 w700); Divider `cBorder` 1; options `('🌐','System',null)`, `('🇩🇪','Deutsch',de)`, `('🇬🇧','English',en)`, `('🇪🇸','Español',es)`; 8dp.
Option: InkWell → `await LocaleController.setLocale(value)`; pop. Padding 20×14, selected bg `cOrange@0.08`; flag 22sp; 16dp; label 15 (selected orange w700, else text w500); selected check_circle_rounded 20 orange. Selection compare by `languageCode` (null == null for System).
Flag mapping `_flagFor`: de 🇩🇪, en 🇬🇧, es 🇪🇸, else 🌐.

Currency dialog (`_showCurrencyPopup`): Dialog `cCard` r16, maxHeight 80% screen, scrollable: header (LTRB 20,20,20,4) currency_exchange_rounded 20 orange + `convSelectCurrency`; hint `settingsCurrencyHint` (LTRB 20,0,20,10; 12 secondary h1.4); Divider; one option per `CurrencyService.supported` = `['EUR','USD','GBP','CHF','CAD','AUD','JPY']`; 8dp.
Option: tap → `await CurrencyService.set(code)`; pop. Padding 20×14; selected bg `cOrange@0.08`; symbol in 44dp box (16 w700; orange if selected else secondary); 8dp; `'$code · ${name}'` (15; selected orange w700); check icon if selected.
Names: EUR `curEUR`, USD `curUSD`, CHF `curCHF`, GBP `curGBP`, CAD `curCAD`, AUD `curAUD`, JPY `curJPY`, else code. Symbols: EUR `€`, USD `$`, GBP `£`, JPY `¥`, CAD `CA$`, AUD `A$`, CHF/other → code.

### 2.7 `SettingsScreen` (legacy standalone)
Scaffold + AppBar `settingsTitle`; section `settingsLanguage` uppercased (orange 13 bold) with tiles System (`settingsLanguageSystem`) + `LocaleController.supported` names (selected border orange 1.5, check_circle vs circle_outlined grey; tap sets locale); section `v4vSectionTitle` with a V4V ListTile → `V4VScreen()`. **Not referenced anywhere — do not port** (the bottom sheet supersedes it).

### 2.8 LogScreen (`log_screen.dart`)
Entry: settings sheet → "Diagnose-Log". All strings hard-coded German.
- Scaffold `cDark`; AppBar bg `cDark`, elevation 0, title `'Diagnose-Log'` (16 w700).
- Actions (icon size 20, default color `cTextSecondary`):
  1. key_rounded, tooltip `'NIP-44-Verbindung prüfen (Freigabe im Signer eventuell nötig)'`; disabled while probing; `ok = await SigningService.canNip44()`; snackbar `ok ? 'NIP-44-Probe erfolgreich.' : 'NIP-44-Probe fehlgeschlagen — Ursache im Diagnose-Log.'`.
  2. filter: icon `filter_alt_rounded` (orange) when active else `filter_alt_outlined`; tooltip `'Nur Warnungen/Fehler'`; toggles `_onlyProblems` (shows only `ERROR`/`WARN`; `SEC` is hidden by the filter).
  3. verbose: icon `zoom_in_rounded` (orange) when verbose else `zoom_out_map_rounded`; tooltip on: `'Ausführliches Log AN — tippen zum Ausschalten'`, off: `'Ausführliches Log einschalten (mehr Details für die Fehlersuche)'`; `AppLogger.setVerbose(!isVerbose)`; snackbar (floating) `'Ausführliches Log an — Fehler jetzt nachstellen, dann Log teilen.'` / `'Ausführliches Log aus.'`.
  4. copy_rounded, tooltip `'Kopieren'` → clipboard `AppLogger.exportText()`; snackbar `'Log kopiert'` (floating).
  5. share_rounded, tooltip `'Teilen'` → if text empty return; share `text` with subject `'21Meetup Diagnose-Log'`; on failure copy to clipboard + snackbar `'Log kopiert'`.
- Body: if filtered list empty → centered `'Keine Einträge'` (tertiary). Else container `#0D0D0F`, full width, scroll padding 12×10, `SelectableText.rich` mono 11.5 height 1.6; per entry: `'$HH:$MM:$SS  '` (tertiary), `level.padRight(5)` (level color, w700), `'  ${tag}: '` (orange), `'${message}\n'` (text). Level colors: ERROR `cRed`, WARN `cOrange`, SEC `cPurple`, DEBUG `cTextTertiary`, else `cTextSecondary`.
- Bottom bar (SafeArea, padding LTRB 12,6,12,12): `'${all.length} Einträge'` (12 tertiary, filtered count); button `'Log leeren'` (pad 18×10, `cRed@0.1`, r10, border 0.5 `cRed@0.3`, text red 13 w700) → `AppLogger.clear()` (no confirmation).
- Note: copy/share export the **full** buffer (not the filtered view) in `LogEntry.format()` format.
- Native: Android Sharesheet `Intent.ACTION_SEND` (`text/plain`, `EXTRA_SUBJECT`, `EXTRA_TEXT`) via `Intent.createChooser`; consider sharing as a `.txt` via FileProvider when > ~100 KB (Binder limit ~1 MB; 800 entries can approach it).

### 2.9 Guide (spotlight tour) system
See §3.4 (models), §8.9 (overlay algorithm), tours in §2.9.1.

Overlay is mounted ABOVE all routes, dialogs and sheets. A step with a target draws only when the target is measurable and its route is the **current** (top-most) route; otherwise the overlay is invisible but keeps full size and keeps polling.

Tooltip card: `Card(color #1E1E1E, elevation 8, radius 16, border 2 #F7931A)`, padding 20: Row[lightbulb icon (accent), 8dp, title (titleLarge, white bold)] · 6dp · `guideStepOf(index+1, total)` (white38, 11) · 10dp · body (bodyMedium, white70) · if hint: 14dp + Row[icon `check_circle` green if done else `touch_app` accent, 18; 8dp; text `done ? guideStepDone : hint` 13 w600 green/accent] · 18dp · Row[if not first: TextButton `guideBack` (white54); TextButton `guideFinishTour` (white54) → finishTour; Spacer; ElevatedButton `actionContinue` (bg accent, fg black; disabled bg white12, fg white38) enabled iff `step.isComplete` → nextStep].
`hintDone = step.completeWhen != null && step.isComplete` (no "done" tick for pure-explanation steps).
Skip chip: positioned (top `statusBarTop + 8`, right 12); hidden if the hole overlaps rect `(screenW - 172, top, 160, 44)`; Material `black@0.55` r20, InkWell → `finishTour`; text `guideSkip` (white70 bold 12), padding 14×8.

#### 2.9.1 Tours (all steps; string keys resolved from ARB)
`GuideTour` enum names (persisted!): `onboarding, home, settings, events, portal, wallet, reputation, scan, myMeetups, portalArea`. (`onboarding`, `reputation`, `scan` are currently never started but are persisted by `declineAllTours()`.)

**HomeTour.steps()** (21 steps; `_optionalTile = 220 ms`):

| # | target | titleKey / bodyKey | options |
|---|---|---|---|
| 1 | glossaryKey | guideHomeGlossaryTitle / guideHomeGlossaryBody | scrollIntoView false |
| 2 | trustScoreKey (dead tile → auto-skip) | guideHomeTrustScoreTitle / …Body | waitTimeout 220 ms |
| 3 | homeMeetupKey | guideHomeMeetupTitle / guideHomeMeetupBody | — |
| 4 | reputationKey | guideHomeReputationTitle / …Body | 220 ms |
| 5 | communityKey | guideHomeCommunityTitle / …Body | 220 ms |
| 6 | wotKey | guideHomeWotTitle / …Body | 220 ms |
| 7 | eventsKey | guideHomeEventsTitle / …Body | 220 ms |
| 8 | shoutoutKey | guideHomeShoutoutTitle / …Body | 220 ms |
| 9 | podcastKey | guideHomePodcastTitle / …Body | 220 ms |
| 10 | (none — centered dialog) | guideHomeMoreTitle / guideHomeMoreBody | tooltipAlignment center |
| 11 | portalConnectKey (dead → skip) | guideHomePortalConnectTitle / …Body | 220 ms |
| 12 | umrechnerKey | guideHomeUmrechnerTitle / …Body | 220 ms |
| 13 | bitcoinKey | guideHomeBitcoinTitle / …Body | 220 ms |
| 14 | newsKey | guideHomeNewsTitle / …Body | 220 ms |
| 15 | myMeetupsKey | guideHomeMyMeetupsTitle / …Body | 220 ms |
| 16 | customizeKey ("Verfügbar" header) | guideHomeCustomizeTitle / …Body | 220 ms |
| 17 | badgeTabKey | guideHomeBadgeWalletTitle / …Body | scrollIntoView false |
| 18 | nearbyTabKey | guideHomeNearbyTitle / …Body | scrollIntoView false |
| 19 | scanFabKey | guideHomeScanTitle / …Body | scrollIntoView false, holePadding 6, holeRadius 40 |
| 20 | eventsTabKey | guideHomeEventsTabTitle / …Body | scrollIntoView false |
| 21 | settingsKey | guideHomeSettingsTitle / guideHomeSettingsBody, hint `guideHomeSettingsBackupHint` | scrollIntoView false |

"Schritt X von 21" counts skipped steps too. Tiles in the collapsed Available section are not mounted → their steps skip after 220 ms.
Unused keys: `homeTabKey` (attached but no step), `communityTabKey` (unused).

**SettingsTour.steps()** (7): profileKey (guideSettingsProfileTitle/Body) · backupKey (guideSettingsBackupTitle/Body, hint `guideHintBackup`) · relaysKey (guideSettingsRelaysTitle/Body) · languageKey (guideSettingsLanguageTitle/Body) · hapticKey (guideSettingsHapticTitle/Body) · restartKey (guideSettingsRestartTitle/Body) · resetKey (guideSettingsResetTitle/Body). All default options (6 s wait, scrollIntoView true).

**WalletTour** (started by badge-wallet domain; `_optional = 800 ms`): firstBadgeKey (guideWalletBadgesTitle/Body, wait 800 ms) · mapKey (guideWalletMapTitle/Body, no scroll) · viewKey (guideWalletViewTitle/Body, no scroll) · shareKey (guideWalletShareQrTitle/Body, no scroll).
**CommunityTour** (tour `portal`): portalKey (guideCommunityPortalTitle/Body) · newsKey (guideCommunityNewsTitle/Body) · shoutoutKey (guidePortalShoutoutTitle/Body) · duellKey (guideCommunityFunTitle/Body).
**PortalAreaTour** (tour `portalArea`): meetupsKey (guidePaMeetups*) · coursesKey (guidePaCourses*) · mapKey (guidePaMap*) · mineKey (guidePaMine*).
**MyMeetupsTour** (tour `myMeetups`): listKey (guideMyMeetupsListTitle/Body, no scroll, wait 800 ms).
**WotTour**: tabsKey (guideWotTabs*, no scroll), refreshKey (guideWotRefresh*, no scroll) — **defined but never started** (no `GuideTour` value used for it).
(`EventBadgeTour` in `lib/tours/event_badge_tour.dart` belongs to the events domain but uses this infrastructure.)

Tour start pattern used by other screens: post-frame → `if (await guide.wasTourCompleted(tour)) return;` → `guide.startTour(tour, steps)`; on dispose → `finishGuideTourIfActive(tour)` (= `finishTourNow` only if that tour is active → marks it completed asynchronously).

### 2.10 Shared widgets
- `Pressable(child, onTap?, onLongPress?, scaleDownTo 0.97, duration 100 ms)`: reverse 80 ms; curves easeOutCubic / easeInCubic; translucent hit test; no-op wrapper if no callbacks; with system "remove animations" → plain tap detector. Does NOT claim long-press unless given (dashboard edit mode relies on this). Native: `Modifier.pressScale()` using `interactionSource.collectIsPressedAsState()` + `animateFloatAsState`.
- `EmptyState(icon, title, subtitle, actionLabel?, onAction?, accentColor = cOrange)`: centered scroll, padding 36: pulsing circle 96×96 (`accent@0.10` fill, 1.5 `accent@0.30` border, icon 40), scale `1.0 + 0.06*v` over 2400 ms reverse-repeat (static when animations disabled); 24dp; title (19 w800 ls0.3 centered); 10dp; subtitle (14 secondary h1.5 centered); if action: 26dp + Pressable button (height 48, pad h28, accent bg, r12, black 14 w800).
- `GlassCard(child, padding=20, margin, borderColor=cGlassBorder, borderRadius=20, blurAmount=0, onTap, gradient=[#1E1E24,#18181C] TL→BR, glowing=false)`: border 0.5; shadow glowing → `(borderColor ?? cOrange)@0.08 blur 24 spread -4 offset (0,8)` else `black@0.2 blur 12 offset (0,4)`; optional backdrop blur.
- `StatusChip(label, color, icon?)`: pad 10×4, `color@0.12`, r8, border 0.5 `color@0.2`; icon 12 + 4dp; text 10 w700 ls0.5.
- `SectionHeader(title, actionLabel?, onAction?, color?)`: padding bottom 14; 3×16 accent bar r2; 10dp; title 12 w700 ls1.2 (`color ?? cTextSecondary`); Spacer; action text 12 w600 `cOrange@0.7`.
- `shadowForElevation(e, accent=cOrange)`: e≤0 → none; ≤2 → `accent@0.08 blur 10 spread -3 (0,3)`; ≤4 → `accent@0.10 blur 16 spread -4 (0,6)`; else `accent@0.14 blur 24 spread -5 (0,10)`.
- `shareOriginFor(context)`: iOS iPad popover anchor (clip widget rect to screen; fallback full screen; `1×1` if size is 0). **Not needed on Android.**
- `launchHttpUrl(url)` / `launchHttpUri(uri)`: trim, `Uri.tryParse`; scheme (lowercased) must be `http`/`https` and host non-empty; `launchUrl(external)`; any exception → false.
- `localizedLevel(context, internal)`: `NEU→levelNew, STARTER→levelStarter, AKTIV→levelActive, ETABLIERT→levelEstablished, VETERAN→levelVeteran`, unknown → internal string unchanged. **Internal values stay German** (used in QR exchange/score logic).

### 2.11 Theme (`theme.dart`)
Colors (ARGB):
```
cDark #FF0C0C0E   cCard #FF131315   cCardHover #FF1A1A1D   cSurface #FF0F0F11
cOrange #FFF7931A cOrangeLight #FFFFB347 cOrangeDim #FF3D2500
cCyan #FFD4A017 (warm gold, NOT cyan)  cCourse #FF9AA0AA  cCyanDim #FF3D2E00
cPurple #FF9B45E8 cNostr #FF8B5CF6 cPurpleLight #FFBE96F2
cText #FFFFFFFF cTextSecondary #FF9CA0A8 (7.4:1) cTextTertiary #FF868A94 (5.66:1, WCAG AA)
cBorder #FF1C1C22 cTileBorder #FF202028 cGlass #0EFFFFFF cGlassBorder #14FFFFFF cGlassHeavy #1EFFFFFF
cGreen #FF22C55E cRed #FFE53935
gradientOrange = Linear TL→BR [#F7931A, #E07810]  (gradientCyan/Purple/Gold are aliases)
Guide: accent #F7931A, card #1E1E1E
Other literals: #141416 (tile gradient end), #0D0D0F (log bg), SatoshiDuell gold #FFC93C
Material: Colors.orange #FF9800, orange.shade800 #EF6C00, orange.shade200 #FFCC80, orange.shade300 #FFB74D,
          green.shade700 #388E3C, green.shade300 #81C784, green.shade400 #66BB6A, Colors.green #4CAF50, Colors.amber #FFC107
```
Fonts: base family **Rajdhani** (Google Fonts, fetched at runtime by `google_fonts`), monospace **Inconsolata** (`fontMono`). Native: bundle both as `res/font` (OFL) — no runtime download.
Metrics: `kTileGap = 10`, `kTileRadius = 10`.
ThemeData: dark, M3, `ColorScheme.dark(primary/secondary cOrange, surface cCard, error cRed)`, scaffold `cDark`.
Text theme (all Rajdhani): displayLarge w700 34 ls-0.5 h1.1 text; displayMedium w700 26 ls-0.3 h1.15; titleLarge w700 18; titleMedium w600 15; bodyLarge 15 h1.5 text; bodyMedium 13 secondary h1.5; bodySmall 11 tertiary h1.4; labelSmall 10 secondary w600 ls1.0.
AppBar: transparent, no elevation/scroll elevation, not centered, title Rajdhani 20 w700 text, icons secondary 20.
Card: cCard, elev 0, r10, side 0.5 cTileBorder. ElevatedButton: orange bg, black fg, elev 0, pad 28×14, r10, text 14 w800. OutlinedButton: text fg, side 0.5 cTileBorder, pad 24×14, r10. TextButton: orange fg, 14 w600. Input: filled cSurface, r10 border 0.5 cTileBorder, focused 1.5 orange, content pad 16×14. Divider: cBorder 0.5, space 28. SnackBar: bg cCard, text Rajdhani 13 **cText** h1.5 (contrast ≥4.5 test), r10, floating, elev 0. Dialog: cCard, no tint, r12. BottomSheet: cCard, no tint, top r16.
Native: map to a custom M3 `darkColorScheme` + `Typography`; Material 3 Expressive shapes/motion are allowed for components but keep these colors/metrics for visual parity.

### 2.12 Localization (`l10n.yaml`, ARB)
`l10n.yaml`: `arb-dir: lib/l10n`, `template-arb-file: app_de.arb`, `output-localization-file: app_localizations.dart`, `output-class: AppLocalizations`, `nullable-getter: false`.
- 3 locales: `de` (template), `en`, `es`; **1926 message keys each, identical key sets**. Supported locales order `[de, en, es]` → unsupported system languages resolve to **German**.
- Metadata: `@key` entries exist only where placeholders are present (137 in de incl. `@appTitle: {}`, 128 in en/es); no descriptions; no `format`/`optionalParameters`.
- Placeholders: ICU `{name}`; types used: `String` (53), `int` (34), `Object` (63, interpolated via toString), untyped (`convRateInfo` `{price} {cur}`, `convUpdated` `{time}`). 16 keys have >1 placeholder.
- Plurals: exactly 3 keys use ICU plural with exact-match selectors: `badgesCount` (`=0 Keine Badges, =1 1 Badge, other {count} Badges`), `eventInDays` (`in {days, plural, =0{heute} =1{1 Tag} other{{days} Tagen}}`), `mnFailedBanner` (`=1 …, other …`). No `select`.
- 35 values contain `\n`; 3 contain apostrophes (`profileNicknameAnon`, `profileEditWarnDesc`, `apHowItWorks`) — escape for Android XML.
- Key families by prefix (counts): guide 173, gl 121 (glossary), profile 81, rl 79, id 75, ev 68, cal 67, portal 66, mn 61, qr 60, wallet 45, nb 45, tile 43, verify 41, ap 41, settings 40, si 38, badge 35, writer 33, backup 32, key 32, bunker 29, hum 28, pp 25, reputation 24, …
- Guide step texts are referenced by **string key names** stored in `GuideStep.titleKey/bodyKey/hintKey` and resolved through explicit maps in `guide_overlay.dart` (unknown key → the key itself is displayed).
Native: convert ARB → `res/values/strings.xml` (German, default), `values-en/`, `values-es/`. Keep the ARB key as the resource name (camelCase is valid). Plurals with `=0/=1` must be implemented with `android.icu.text.MessageFormat` (API 24+) or explicit code, because Android `<plurals>` for German has no `zero` category. Per-app language: `AppCompatDelegate.setApplicationLocales()` (+ `locales_config.xml` for Android 13 system settings). Guide steps should reference `@StringRes` ints directly instead of string names.

Domain string keys (German value → EN):
- main: `bunkerAuthNeeded`, `bunkerAuthAction`.
- shell: `navHome`, `navWalletTab`, `navEvents`, `navNearby`, `scanBadge`, `scanBadgeSub`, `scanReputation`, `scanReputationSub`, `guideWelcomeTitle`, `guideWelcomeBody`, `guideNoThanks`, `guideStart`.
- home: `badgesUnreadableBody`, `badgesUnreadableShort`, `btnChat`, `btnEvents`, `btnInfo`, `chDuellSub`, `chPlebrapSub`, `chPortalSub`, `chatNoRoom{city:String}`, `chatOffline`, `chatRelayUnavailable`, `chatSearching`, `homeCouldNotOpen{url:Object}`, `homeImageLoadError{msg:Object}`, `homeMeetupChoose`, `homeMeetupChooseSub`, `homeMeetupInDays{days:int}` ("in {days} Tagen", no plural), `homeMeetupLabel`, `homeMeetupNoDate`, `homeMeetupToday` ("Heute!"), `homeMeetupTomorrow`, `levelNew` (+ `levelStarter/Active/Established/Veteran` via level_labels), `portalConnect`, `portalConnectForOrganizer`, `portalConnected`, `portalConnecting`, `portalLoginFailed`, `portalTileSub`, `portalTokenMismatch`, `refreshDone`, `refreshRunning`, `sdShortLobby`, `sdShortTurn`, `sdShortWait`, `statusLive`, `statusMeetupActive`, `tileActConvert`, `tileActEncounters`, `tileActExchange`, `tileActListen`, `tileActLookup`, `tileActManage`, `tileActSend`, `tileCommunity`, `tileCommunityPortal`, `tileConverter`, `tileConverterSub`, `tileEventChats`, `tileEventChatsNone`, `tileEventChatsSub`, `tileEventChatsUnread{count:int}` (no plural), `tileEvents`, `tileEventsCalendar`, `tileEventsToday`, `tileNews`, `tileNewsSub`, `tileNostr`, `tileNostrCommunity`, `tileOrganizer`, `tileOrganizerPanel`, `tilePodcast`, `tilePodcastListen`, `tilePortal`, `tilePortalSub`, `tileReputation`, `tileReputationBadges{count:int}`, `tileReputationCheck`, `tileShoutout`, `tileShoutoutSend`, `tileTrustNetwork`, `tileTrustNetworkSub`, `tileTrustScore`, `tilesAvailable`.
- reset: `resetTitle`, `resetBody`, `resetCancel`, `resetConfirm`, `resetBackupTitle`, `resetBackupBody`, `resetBackupCreate`, `resetBackupSkip`, `resetBackupDone`, `resetNowConfirm`.
- settings sheet: `settingsHeaderTitle`, `settingsHeaderSub`, `settingsSecAccount`, `settingsSecData`, `settingsSecNetwork`, `settingsSecApp`, `settingsSecDanger`, `settingsProfile(+Sub)`, `settingsBackup(+Sub)`, `settingsBackupLoad(+Sub)`, `settingsRelays(+Sub)`, `settingsMempool(+Sub)`, `settingsLanguageTitle`, `settingsLanguageChoose`, `settingsCurrencyTitle`, `settingsCurrencyHint`, `convSelectCurrency`, `curEUR/USD/CHF/GBP/CAD/AUD/JPY`, `settingsHaptic`, `settingsHapticOn`, `settingsHapticOff`, `settingsLogTitle`, `settingsLogSub`, `settingsRestartGuide`, `settingsRestartGuideSub`, `settingsGuideReset`, `v4vSectionTitle`, `v4vSectionSubtitle`, `settingsReset`, `settingsResetSub`; legacy screen: `settingsTitle`, `settingsLanguage`, `settingsLanguageSystem`.
- guide overlay: `guideSkip`, `guideStepOf{current:int,total:int}`, `guideBack`, `guideFinishTour`, `guideStepDone`, `actionContinue`, plus all title/body/hint keys resolved in `_resolveTitle/_resolveBody/_resolveHint` (onboarding, home, settings, events, event-badge, portal, portal-area, wallet, community, my-meetups, WoT, reputation families).
Hard-coded (non-localized) strings to keep or localize consciously: customize sheet texts and tile labels, `'PlebRap'`, `'SATOSHIDUELL'`, `'Portal'`, `'Bitcoin'`, `'Netzwerk & Kurs'`, `'BLOCK'`, `'V4V'`, language names, `'21Meetup · Version …'`, device-integrity warning, all LogScreen texts, widget countdown `'Heute'/'Morgen'/'in N Tagen'`.

---

## 3. Data models

### 3.1 `LogEntry` (app_logger.dart)
| field | type | JSON key | notes |
|---|---|---|---|
| time | DateTime (local) | `t` | `millisecondsSinceEpoch` int; parse `(j['t'] as num).toInt()` |
| level | String | `l` | `DEBUG` \| `INFO` \| `SEC` \| `WARN` \| `ERROR`; missing → `'INFO'` |
| tag | String | `g` | missing → `''` |
| message | String | `m` | missing → `''` |
Serialized as compact JSON object `{"t":…,"l":"…","g":"…","m":"…"}` inside a JSON array.
`format()`: `'${dd}.${MM} ${HH}:${mm}:${ss}  ${level.padRight(5)} [$tag] $message'` (two spaces after time; local time; 2-digit zero padding).
Log screen line format differs: `'HH:MM:SS  ' + level.padRight(5) + '  ' + tag + ': ' + message`.

### 3.2 `RelayParseTally(tag, label)`
`message()` → `seen++`; `failed([error])` → `failed++`, keep first error; `report()` once: if `failed == 0` silent; else `AppLogger.warn(tag, '$label: $failed von $seen Relay-Nachrichten nicht auswertbar')` and if firstError → `AppLogger.debug(tag, '$label — erster Parse-Fehler: $firstError')`; further calls no-op. Getters `seen`, `failures`.

### 3.3 `GuideStep`
| field | type | default |
|---|---|---|
| targetKey | GlobalKey? (native: target id String?) | null (= centered dialog step) |
| titleKey | String | required |
| bodyKey | String | required |
| hintKey | String? | null |
| tooltipAlignment | Alignment | bottomCenter (fallback only) |
| navigateFirst / routeName | bool / String? | false / null (reserved, unused) |
| blockOutside | bool | true |
| interactiveTarget | bool | true |
| advanceOnTargetTap | bool | false |
| completeWhen | () → bool | null |
| autoAdvance | bool | false |
| waitForTarget | bool | false (declared; overlay does not branch on it) |
| waitTimeout | Duration | 6 s |
| holePadding | EdgeInsets | all(10) |
| holeRadius | double | 14 |
| scrollIntoView | bool | true |
Derived: `requiresAction = completeWhen != null || advanceOnTargetTap`; `isComplete = completeWhen == null ? true : (try completeWhen() catch → true)`.

### 3.4 `GuideService` state
`activeTour: GuideTour?`, `steps: List<GuideStep>`, `currentStep: Int`, `isActive`, `isPaused`; derived `isVisible = isActive && !isPaused`, `currentStep` (null if out of range), `isFirstStep`, `isLastStep`, `totalSteps`, `canAdvance = currentStep?.isComplete ?? false`.
Methods: `wasTourCompleted(tour)`, `shouldAskForOnboarding()`, `markOnboardingAsked()`, `declineAllTours()`, `resetTour(tour)`, `resetAllTours()`, `startTour(tour, steps, force=false)` (no-op if steps empty or completed and !force; sets step 0, active, unpaused), `nextStep()` (last → `finishTour()`), `previousStep()`, `skipStep()` (= next), `reportTargetTapped()` (only if `advanceOnTargetTap`), `reportStepCompleted()` (only if `autoAdvance && isComplete`), `pause()`, `resume()`, `finishTour()` (await mark completed, then clear), `finishTourNow()` (clear synchronously, persist in background).

### 3.5 Home models
- `TileDef(id: String, label: String, span: Int(1..3), removable: Boolean = true, visible: () -> Boolean = { true }, builder)`.
- `FavCard(key: String /* stored favorite: portal id or legacy city */, label: String, city: String, event: CalendarEvent?)`.
- `MeetupDateEntry(favKey: String, label: String, event: CalendarEvent, attendees: Int /* -1 unknown */)` → passed as `MyMeetupDate` to the my-events screen.
- `HomeUiState` (proposed): `user`, `trustScore?`, `badgesUnreadable`, `profilePicLocal?`, `profilePicUrl?`, `deviceCompromised`, `integrityDismissed`, `activeSession?`, `sessionTimeLeft`, `favCards`, `favPage`, `countdownLoading`, `chatUnread: Map<String,Int>`, `myEvents: List<NostrCalendarEvent>`, `favMeetupDates`, `myEventsUnread`, `unreadNews`, `latestNewsTitle`, `eventsToday`, `nostrHasNew`, `portalConnected`, `tileOrder: List<String>`, `hiddenTiles: Set<String>`, `availableCollapsed = true`, `editTileId?`, `refreshing`, `appVersion`.
- External types consumed: `UserProfile` (fields `nickname, nostrNpub, homeMeetupId, favoriteMeetupIds, isAdmin (derived), adminViaPortal, adminViaVouch, adminViaSeed, isAdminVerified, isReviewDemo`), `TrustScore(totalScore: Double, level: String, meetsPromotionThreshold, promotionProgress)` with `level`: `>=40 VETERAN, >=20 ETABLIERT, >=10 AKTIV, >=3 STARTER, else NEU`, `MeetupSession(meetupId, meetupName, expiresAt (unix s), isExpired, remainingTime)`, `CalendarEvent(title, startTime (local), portalEventId: Int?, meetupId)`, `NostrCalendarEvent(start, address)`, `Meetup(id, name, city, country, logoUrl, coverImagePath, …)`, `DuellStatus(myTurn, waiting, lobby)`, `BitcoinDashboardData(blockHeight, isDead, priceIn(cur), …)`, `ChatRoom(h)`, `ChatRelayUnavailable(offline: Boolean)`, `PortalResult(ok, statusCode, error)`.

### 3.6 Locale / currency / haptics
- Locale: `Locale?` (null = system), supported `[de, en, es]`; display names `de→Deutsch, en→English, es→Español, else System`.
- Currency: String code ∈ `['EUR','USD','GBP','CHF','CAD','AUD','JPY']`, fallback `'EUR'`; `groupInt(v)` thousands separator `.` (also for negatives `-1.234`); `formatPrice(p, code) = '${groupInt(p.round())} ${symbol(code)}'`.

---

## 4. Persistence

All keys below are Flutter `shared_preferences` (legacy API) → on Android stored in the XML file **`FlutterSharedPreferences`** with every key prefixed **`flutter.`**. Encodings: bool → `putBoolean`; int → `putLong`; String → `putString`; double → `putString("VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu" + value)`; `List<String>` → `putString("VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu!" + jsonEncode(list))` (older installs may still hold `"VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu" + Base64(Java-serialized ArrayList)` without `!`). The native app ships as an update under the same applicationId; the central `:data:migration` (spec 14 §4.7) copies every key once into the shared `:core:data` `KeyValueStore` (DataStore `app_prefs`, same names without `flutter.`). This domain has **no importer of its own** and opens no DataStore file of its own. Exception: `diagnostic_log` / `diagnostic_verbose` are handed to `AppLogger.importLegacy` instead (log store below).

| key (Dart name; Android file key = `flutter.` + name) | type | default | writer | reader | notes |
|---|---|---|---|---|---|
| `diagnostic_log` | String (JSON array of LogEntry) | absent | AppLogger (debounced 2 s, max 800, only after init) | AppLogger.init | removed by `clear()` |
| `diagnostic_verbose` | bool | false | AppLogger.setVerbose | AppLogger.init | |
| `app_locale` | String language code | absent = system | LocaleController.setLocale (removed for system) | LocaleController.load | values `de`/`en`/`es` |
| `haptic_enabled` | bool | true | settings switch (direct prefs write), HapticService.setEnabled | HapticService (cached once!), AppShell (reads each tap) | |
| `fiat_currency` | String | `EUR` | CurrencyService.set | CurrencyService.read/load | invalid → EUR |
| `guide_completed_<tour>` | bool | false | GuideService (`finishTour*`, `declineAllTours`) | GuideService | `<tour>` ∈ `onboarding, home, settings, events, portal, wallet, reputation, scan, myMeetups, portalArea` (exact Dart enum `.name`, camelCase) |
| `guide_onboarding_asked` | bool | false | `markOnboardingAsked`, `declineAllTours` | `shouldAskForOnboarding` | removed by `resetAllTours` |
| `tile_order` | List<String> | absent | `_saveTileOrder`, migration `mig_trust_header_v1` | `_loadTileOrder` | tile ids |
| `tile_hidden` | List<String> | absent → `_defaultHidden` | `_saveTileOrder`, migrations | `_loadTileOrder` | "available" (un-pinned) ids |
| `mig_hide_events_v1` | bool | false | migration | migration | adds `events` to hidden |
| `mig_trust_header_v1` | bool | false | migration | migration | adds `trust_score` to hidden, removes it from saved order (writes `tile_order` only if it existed) |
| `mig_hide_new_tiles_v131` | bool | false | migration | migration | adds `satoshiduell`, `portal_area`, `plebrap` |
| `mig_hide_plebrap_v1` | bool | false | migration | migration | adds `plebrap` |
| `nostr_last_seen` | int (unix seconds) | 0 | `_openNostr` | `_checkNostrNew` | |
| `local_profile_picture` | String (file path) | absent | NostrProfileService.setLocalPicture | getLocalPicture | removed by clearCache |

Migration order in `_loadTileOrder` (each runs once, persists `tile_hidden` immediately):
```dart
final saved = prefs.getStringList('tile_order');
final savedHidden = prefs.getStringList('tile_hidden')?.toSet() ?? Set.from(_defaultHidden);
if (!(prefs.getBool('mig_hide_events_v1') ?? false)) { savedHidden.add('events'); setBool(true); setStringList('tile_hidden', savedHidden); }
if (!(prefs.getBool('mig_trust_header_v1') ?? false)) { savedHidden.add('trust_score'); saved?.remove('trust_score'); setBool(true); setStringList('tile_hidden', …); if (saved != null) setStringList('tile_order', saved); }
if (!(prefs.getBool('mig_hide_new_tiles_v131') ?? false)) { savedHidden.addAll(['satoshiduell','portal_area','plebrap']); … }
if (!(prefs.getBool('mig_hide_plebrap_v1') ?? false)) { savedHidden.add('plebrap'); … }
if (saved != null && saved.isNotEmpty) { known = merge(saved) (§8.1); _tileOrder = known; _hiddenTiles = savedHidden; }
else { _tileOrder = _defaultOrder; _hiddenTiles = _defaultHidden; }   // NOTE: in-memory default, ignoring savedHidden
```
`_saveTileOrder()` writes both `tile_order` and `tile_hidden` (list order of a Dart LinkedHashSet = insertion order).

Other storage touched by this domain:
- Android SharedPreferences file `e21_widget`, key `pending_target` (String: `news`|`bitcoin`|`meetup`), written by `WidgetRouterActivity` with `commit()`, read-and-removed by `MainActivity` channel `getLaunchTarget`.
- home_widget file `HomeWidgetPreferences` (no `flutter.` prefix): `meetupCity`, `meetupCountdown` (written by `WidgetService.updateMeetup` from Home), plus bitcoin/news keys (widget domain).
- Reset deletes (other domains' storage): see §2.5.12; `SharedPreferences.clear()` in the legacy API removes **only `flutter.`-prefixed keys** of `FlutterSharedPreferences` (not `e21_widget`, not `HomeWidgetPreferences`, not flutter_secure_storage).
- Secure storage items NOT removed by reset (residuals): `rqr_session_seed_secure`, `rqr_session_base_payload_secure` (rolling QR), badge key aliases managed by `MeetupBadge.resetAll` (badge domain decides), `signing_mode`-related prefs are cleared by `p.clear()` (they live in prefs).

Native: settings go through the shared `:core:data` `KeyValueStore` (DataStore `app_prefs`, identical key names, written once by `:data:migration`, spec 14 §4.7). The log buffer and verbose flag are **not** prefs natively: `FileLogStore` (`:core:common`) keeps them in `filesDir/diagnostic_log.jsonl` (one JSON object per line, keys `t,l,g,m`) and `filesDir/diagnostic_verbose`; the migration passes the Flutter `diagnostic_log` / `diagnostic_verbose` values to `AppLogger.importLegacy(raw, verbose)` (spec 14 row 98/99).

---

## 5. Network

This domain calls the network mostly through other domains. Direct/indirect calls initiated from Home/Shell/Settings:

| Purpose | Endpoint | Method / headers | Notes |
|---|---|---|---|
| Portal organizer check | `https://portal.einundzwanzig.space/api/my-meetups` | GET, `Accept: application/json`, `Authorization: Bearer <token>` (if any); via `PortalApiService.rawGetStatus` | returns `(status, body)`: 200 → decoded JSON; non-200 → `(code, null)` + warn `'GET /my-meetups -> HTTP n'`; network error → `(0, null)`. Body may be `{"data":[…]}` or `[…]`; list of objects with `name` |
| Portal login | `POST https://portal.einundzwanzig.space/api/mobile/token` via `PortalApiService.loginWithNostr(deviceName = 'Einundzwanzig Meetup App')` | POST, `Accept: application/json`, `Content-Type: application/json`, timeout 15 s; body `{"k1": <k1>, "event": <signed event JSON>, "device_name": <deviceName>}` | **Stateless NIP-42-style login (no pre-request, no server-issued challenge)**, see spec 09 §6: the app generates `k1` itself (32 bytes from `Random.secure()` → 64 lowercase hex), signs a **kind 22242** event with `tags = [["challenge", k1]]`, `content = ''` via `SigningService.signEvent` (local nsec or Amber/NIP-46). The portal checks event/k1 match and freshness (< 300 s). HTTP 200/201 with non-empty string `token` → `storeToken(token)`, return `PortalResult(ok=true, data=body['user'])`; other status → `PortalResult(ok=false, statusCode, error=_errorFrom(r))`; missing/empty token → `error='Kein Token vom Portal erhalten.'`; any exception → `statusCode=0`, `error='Anmeldung fehlgeschlagen: $e'`. **Not NIP-98**: no kind-27235 event and no `Authorization: Nostr …` header is involved |
| Portal logout | `DELETE https://portal.einundzwanzig.space/api/mobile/token` with `Authorization: Bearer`, `Accept: application/json` | best effort, then local token delete | |
| Portal RSVP | `PortalApiService.getRsvpCached(portalEventId)` → map; `isGoing(r)`: `status ∈ {attending, maybe}` or `going`/`is_going`/`rsvped == true`; attendees `r['attendees'] ?? r['count']` if int | portal domain |
| Meetup list | `MeetupService.fetchMeetups()` (15 s timeout) — called on every `_loadUser` when favorites exist | meetups domain |
| Meetup events | `MeetupCalendarService().fetchMeetupsPortalFirst()` (portal `/api/meetup-events/{date}` ×3 months, iCal fallback; drops events older than now−6 h) | calendar domain |
| Nostr calendar events | `CalendarEventService.fetchEvents()`, `fetchByAddress(address)` | events domain |
| News | `NewsService.unreadCount()/cachedArticles()` (10-min RAM cache; `news_last_read_ts`), `markRead()`; widget: `fetchArticles(limit: 1)` | news domain |
| Mempool | `MempoolService.getDashboardData()` every 60 s while Home is alive | bitcoin domain |
| SatoshiDuell | Supabase REST `https://uydjemquyogdemjtxyyv.supabase.co/rest/v1`, headers `apikey: sb_publishable_RESZrjWqb-l_AIiqZcE7-g_JenFWO_d`, `Authorization: Bearer <same>`, `Accept: application/json`, timeout 8 s, RAM cache 2 min (on error returns last cached or empty) | (1) `GET /profiles?npub=eq.<lowercase npub urlencoded>&select=username&limit=1`; (2) `GET /duels?select=status,creator,challenger,target_player,creator_score,challenger_score&or=(creator.eq.<q>,challenger.eq.<q>,target_player.eq.<q>)&order=created_at.desc&limit=50` with `q = urlEncodeQueryComponent('"' + username.lowercase().replace('"','') + '"')`; (3) `GET /duels?select=creator,target_player&status=eq.open&limit=100`. Counting in §8.10 |
| Profile picture | `NostrProfileService.fetchProfilePicture(hex)` (kind:0) | profile domain |
| Chat | `ChatService.findRoomForMeetupId/findRoomForCity/unreadCounts` | chat domain |
| External links | `https://shoutout.einundzwanzig.space`, `https://einundzwanzig.space/podcast/`, `https://satoshiduell.de/?npub=<npub>` / `https://satoshiduell.de/`, `nostr:npub1qv02…ujnc` → fallback `https://njump.me/npub1qv02…ujnc`, NIP-46 `auth_url` (http/https only) | external browser / app | |
| Fonts | Google Fonts runtime download (Rajdhani, Inconsolata) | implicit | eliminate natively by bundling |

No retries beyond what is stated; no rate limiting in this domain. Manual refresh is serialized by `_refreshing`.

---

## 6. Nostr

No events are constructed or signed directly in this domain's files. Indirect Nostr operations triggered here (implemented in other domains; listed so the native shell wires them identically):
- **kind:0** profile metadata fetch for the avatar `picture` (npub → hex via NIP-19 `Nip19.decodePubkey`).
- **Organizer claim publication** `PromotionClaimService.publishAdminClaim(badges, meetupName)` after portal confirmation (meetupName = `meetups.first['name'] ?? homeMeetupId`, `'Unbekannt'` if empty) and after WoT/vouch verification (meetupName = `homeMeetupId` or `'Unbekannt'`). The literal `'Unbekannt'` ends up in published content — keep it byte-identical.
- `PromotionClaimService.syncOrganicAdmins()` (reads claims), `AdminRegistry.removeFromCache(ownNpub)` on portal revocation.
- **Reputation publication** `ReputationPublisher.publishInBackground(badges)` whenever badges load readable & non-empty.
- **Badge claims** `BadgeClaimService.ensureBadgesClaimed(badges)`.
- **Badge sync** (NIP-44 encrypted) `BadgeSyncService.init()/retryIfDirty()` at start and on resume.
- **NIP-52** calendar RSVPs `EventRsvpService.loadMine()` (address → `accepted|declined|tentative`), event chats unread counts.
- **Meetup chat rooms** (group relay) lookup by portal meetup id then city; unread counts by room `h` tag.
- **NIP-46** remote signer: `auth_url` approval prompt (global snackbar), `disconnectNip46()` on reset.
- **NIP-44** probe from the log screen `SigningService.canNip44()` (may prompt the external signer).
- Einundzwanzig's npub constant: `npub1qv02xpsc3lhxxx5x7xswf88w3u7kykft9ea7t78tz7ywxf7mxs9qrxujnc`.

---

## 7. Crypto & encoding
- NIP-19 bech32 decode of the user's npub to 64-hex pubkey (profile picture). Use the shared Nostr core (secp256k1-kmp / bech32 util) — no custom crypto here.
- Number formatting: `groupInt` / `_fmtInt` insert `.` every 3 digits from the right (`(len - i) % 3 == 0`), e.g. `95000 → "95.000"`, `1779 → "1.779"`, `-1234 → "-1.234"` (groupInt only). Sats per unit: `round(100_000_000 / price)` (Dart `round()` = half away from zero; use `Math.round` carefully for .5 → Kotlin `roundToLong()` rounds half up toward +∞ for positives — identical for positive values).
- Score display: every trust-score rendering uses Dart `toStringAsFixed(1)`: Home score pill (`home_screen.dart` 1565), Home hero/reputation value (1723, `'—'` when badges unreadable), reputation tile (2477), `ReputationCardVisual` (`reputation_card_visual.dart` 131), `ReputationCardScreen` (`reputation_card_screen.dart` 281), and the QR payload field `'sc': double.parse(totalScore.toStringAsFixed(1))` (`reputation_qr.dart` 113, **wire-relevant**: the rounded value is serialized into the QR JSON). Dart `toStringAsFixed` rounds the **exact binary value** of the double (ties away from zero only for exactly representable ties) and keeps the `-` on a negative value that rounds to zero (`-0.04 → "-0.0"`). So `1.45 → "1.4"`, `0.15 → "0.1"`, `2.25 → "2.3"`. **Do not use `String.format("%.1f")`** (Java rounds the shortest decimal representation HALF_UP: `1.45 → "1.5"`, `0.15 → "0.2"`, and with the default locale it gives `,` in German). Use the shared helper from spec 14 §3.1.4 (F11) / spec 13 §7.3:
  Use the one shared helper `Double.toStringAsFixed(fractionDigits)` from `:core:common` (`space.einundzwanzig.meetup.core.common.dart`, DartCompat.kt): exact binary value via `BigDecimal(Double)` (**not** `BigDecimal.valueOf`), HALF_UP, `-` kept for negative values and `-0.0` that round to zero (`(-0.0).toStringAsFixed(1) == "-0.0"`), always `.`. Never `String.format` (any locale) and no local copy of the helper. For the QR `sc` field, compute `totalScore.toStringAsFixed(1).toDouble()` so the encoded number matches Flutter bit-for-bit. Fixture vectors: `1.45 → "1.4"`, `0.15 → "0.1"`, `2.25 → "2.3"`, `0.05 → "0.1"`, `0.0 → "0.0"`, `12.0 → "12.0"`.
- Date display in fav card: `'${day}.${month}.${year}'` without padding, independent of locale.
- Log JSON: compact `jsonEncode` (no spaces), UTF-8.

---

## 8. Business rules & algorithms

### 8.1 Tile order merge (new tiles inserted at their intended position)
```dart
final known = saved.where((id) => _defaultOrder.contains(id)).toList();   // unknown/obsolete ids dropped
for (final id in _defaultOrder) {
  if (known.contains(id)) continue;
  final idx = _defaultOrder.indexOf(id);
  var pos = 0;
  for (var i = idx - 1; i >= 0; i--) {
    final at = known.indexOf(_defaultOrder[i]);
    if (at >= 0) { pos = at + 1; break; }
  }
  known.insert(pos, id);
}
```
The merged order is NOT persisted until the next save.

### 8.2 `_daysUntil(target)` (calendar days, DST-safe)
```dart
final now = DateTime.now();
final today = DateTime(now.year, now.month, now.day);
final day = DateTime(target.year, target.month, target.day);
return (day.difference(today).inHours / 24).round();   // 0 today, 1 tomorrow, <0 past
```
Native: `ChronoUnit.DAYS.between(LocalDate.now(zone), target.atZone(zone).toLocalDate())` (equivalent). Labels: `<=0 → Heute!`, `1 → Morgen`, else `in N Tagen`; widget strings (German, hard-coded): `'Heute'`, `'Morgen'`, `'in $days Tagen'`.

### 8.3 Favorites carousel (`_loadNextHomeMeetup`)
1. `favs = favoriteMeetupIds.isNotEmpty ? favoriteMeetupIds : (homeMeetupId.isNotEmpty ? [homeMeetupId] : [])`. Empty → `_favCards = []`, loading false, return (**widget not updated** in this branch).
2. `events = await MeetupCalendarService().fetchMeetupsPortalFirst()`; `if (MeetupService.cached.isEmpty) await MeetupService.fetchMeetups()`.
3. `todayStart = local midnight today` (an event stays "next" for the whole calendar day).
4. For each `favKey` (in stored order): `city = MeetupService.cityFor(favKey)`, `label = MeetupService.labelFor(favKey)`; `upcoming = events.where(matches(e, favKey) && !e.startTime.isBefore(todayStart)).sorted(startTime asc)`; `event = upcoming.firstOrNull`; diag log `'Favorit "$label" ($favKey): ${n} Termine, naechster = …'`.
   `matches(e, key)`: `meetup = MeetupService.resolveFavorite(key)`; if non-null → `MeetupEventMatcher.resolve(e, MeetupService.cached)?.id == meetup.id`; else `MeetupEventMatcher.matchesCity(e, key)`.
   (`resolveFavorite`: exact `id` match, else case-insensitive city match (first wins, ambiguity logged); `labelFor`: unresolved → stored value; if >1 meetups share the city and `name` non-empty → name, else city; `cityFor`: resolved city or stored value.)
5. Sort cards: both without event → `a.city.compareTo(b.city)` (ordinal); without event → last; else `startTime` ascending.
6. Set `_favCards`, `_nextHomeMeetup = first.event`, `_favPage = 0`, loading false, jump PageView to 0.
7. Widget: `front = cards.first`; `countdown = event == null ? '' : days <= 0 ? 'Heute' : days == 1 ? 'Morgen' : 'in $days Tagen'`; `WidgetService.updateMeetup(city: front?.city ?? '', countdown)`.
8. `_loadMeetupRsvps(events)` (background). Any exception → loading false.

### 8.4 Portal RSVPs for favorites (`_loadMeetupRsvps`)
`favKeys = ordered set {homeMeetupId (if non-empty), ...favoriteMeetupIds}`; empty → clear. `now`, `horizon = now + 90 days`. Candidates: `portalEventId != null && !(startTime < now − 1 day) && !(startTime > horizon) && favKeys.any(matches)`. Sequentially per candidate: `r = getRsvpCached(portalEventId)`; skip unless `isGoing(r)`; `favKey = first matching key, else e.meetupId`; `label = labelFor(favKey)`; `attendees = (r['attendees'] ?? r['count'] ?? -1) is int ? that : -1`. Sort by `startTime`. Diag `'${candidates.length} Termine der Favoriten geprueft, ${out.length} davon zugesagt.'`.

### 8.5 My events (`_loadMyEvents`)
`rsvps = EventRsvpService.loadMine()`; `accepted = keys where value == accepted`; empty → `_myEvents = []`, unread 0. Else sequential `fetchByAddress`; drop null and `start < now − 1 day`; sort by `start`; `counts = EventChatService.unreadCounts(addresses)`; `_myEventsUnread = sum(counts)`. Errors → debug log `'Zusagen konnten nicht geladen werden: $e'`.
Tile count = `_myEvents.length + _favMeetupDates.length`; unread coloring only considers event chats.

### 8.6 Chat unread & room lookup
`_findRoomFor(favKey)`: `meetup = resolveFavorite(favKey)`; if non-null → `byId = ChatService.findRoomForMeetupId(meetup.id)`; return if non-null; then `ChatService.findRoomForCity(cityFor(favKey))`. `ChatRelayUnavailable` propagates (do NOT fall back to city on relay failure).
`_loadChatUnread()`: ordered favKeys (home first, dedup); for each → room → `byRoom[room.h] = favKey`; `counts = ChatService.unreadCounts(rooms)`; `_chatUnread[favKey] = count`. Errors → debug `'Ungelesen-Abfrage fehlgeschlagen: $e'`. After returning from a chat room → reload.

### 8.7 Portal organizer state machine (`_checkPortalOrganizer`) — reproduce exactly
```
diag('Portal','Organisator-Prüfung gestartet')
if !hasToken():
    if isExternalSigner: warn(...'Kein Token und ein externer Signer ist aktiv — Auto-Verbindung nicht moeglich…'); showPortalHint(); return
    diag('Kein Token — versuche automatische Verbindung (lokaler Schlüssel).')
    res = loginWithNostr(); if !res.ok: diag('…übersprungen.'); return
if !tokenMatchesCurrentKey():
    if hasToken():                               // stale token of another key
        warn('Token gehört zu anderem Schlüssel — getrennt.'); deleteToken()
        if !isExternalSigner:
            retry = loginWithNostr(); if !retry.ok: warn('Neuanmeldung nach Token-Wechsel fehlgeschlagen.'); showPortalHint(); return
        else: snackbar(portalTokenMismatch, orange.shade800, 6 s); return
    else: diag('Kein Portal-Token vorhanden — übersprungen.'); return
res = rawGetStatus('/my-meetups')
if res.status in {401, 403}:
    warn('Token abgelehnt (HTTP n) — wird erneuert.'); deleteToken()
    if isExternalSigner: showPortalHint(); return
    again = loginWithNostr(); if !again.ok: warn('Erneuerung fehlgeschlagen — keine Änderung.'); return
    res = rawGetStatus('/my-meetups')
if res.status != 200 || res.body == null || !mounted: diag('/my-meetups: keine verwertbare Antwort (HTTP n) — keine Änderung.'); return
data = body is Map ? body['data'] : body
meetups = data is List ? data.whereType<Map<String,dynamic>>() : []
diag('/my-meetups lieferte N Meetup(s) für aktuellen Schlüssel.')
if meetups.nonEmpty && !user.adminViaPortal:           // GRANT
    user.adminViaPortal = true; user.isAdminVerified = user.isAdmin; save()
    meetupName = (meetups.first['name'] ?? user.homeMeetupId).toString(); if empty → 'Unbekannt'
    try publishAdminClaim(badges: myBadges, meetupName)
    info('Portal','Organisator-Status bestaetigt (Portal).')   // no snackbar (would repeat every start)
elif meetups.isEmpty && user.adminViaPortal:            // REVOKE (only on a successful empty 200)
    user.adminViaPortal = false; user.isAdminVerified = user.isAdmin; save()
    ownNpub = SigningService.npub(); if non-null: AdminRegistry.removeFromCache(ownNpub)
catch-all: silent
```
Rules: never revoke on network errors/non-200; silent auto-login only with a **local** key (external signers would show a prompt at startup); vouch/seed admin flags untouched by the portal path; `isAdmin = adminViaPortal || adminViaVouch || adminViaSeed`.

### 8.8 Session / timers
- Live session refresh: 1 s periodic; hide when `now > expiresAt`.
- Midnight: `nextMidnight − now + 2 s`.
- BTC tile: 60 s periodic (only while the Home composable is alive; IndexedStack keeps it alive in background tabs — native: tie to `repeatOnLifecycle(STARTED)` to stop polling in background).
- Nostr "new" dot: last open older than 86 400 s.
- Home tour readiness poll: 20 × 500 ms. Settings tour start: 650 ms after sheet open.

### 8.9 Spotlight overlay algorithm (`_GuideSpotlightState`)
- New state per `(activeTour, currentStepIndex)`.
- Timers: poll every **40 ms**; first tick right after the first frame (also `_scrollTargetIntoView()`); pulse animation 1400 ms reverse-repeat; auto-advance delay **700 ms**.
- `_measure()`: target missing/unattached/no size → null; **route of target not current → null**; rect = target global rect converted into overlay coords (fallback: screen coords) inflated by `holePadding`; if not overlapping the overlay bounds → null; clamp to `[0, bounds]`; if width or height ≤ 1 → null.
- `covered` = target exists but its route isn't current.
- `_tick()`: measure; if rect found and not yet scrolled → scroll into view once; if `covered || rect != null` → reset `waitingSince = now`; `complete = step.isComplete`; `timedOut = now − waitingSince > step.waitTimeout`; setState on any change; if `targetKey != null && rect == null && !covered && timedOut && !skipRequested` → `skipStep()` (once); if `complete && autoAdvance && no pending timer` → after 700 ms `reportStepCompleted()`.
- Scroll into view (if `scrollIntoView`): find enclosing scrollable; skip if target already within viewport with 24 px margins; else `ensureVisible(alignment 0.35, 350 ms, easeInOut)`.
- Build: step with target but no hole → transparent, non-interactive, full-size placeholder (never collapse to 0×0). Else Stack: (1) scrim painter (ignores pointer): full-screen `black@0.85` layer with the rounded hole (radius `holeRadius`) punched out (`dstOut`), then stroke around the hole `width 2 + pulse`, color `#F7931A @ (0.55 + 0.45*pulse)`; (2) if `blockOutside`: barrier rectangles around the hole (top full-width, bottom full-width, left and right strips at hole height; full-screen barrier if no hole) that swallow taps and forward **vertical drags** to the target's scroll position (`jumpTo(pixels − dy)` clamped; fling: if |v| ≥ 200 → animate to `pixels − v*0.25` over 320 ms easeOutCubic); (3) if `interactiveTarget && advanceOnTargetTap`: translucent listener over the hole → `reportTargetTapped()` on pointer-up while the tap still reaches the real widget; (3b) if `!interactiveTarget`: opaque tap-swallower over the hole; (4) tooltip; (5) skip chip.
- Tooltip placement: `gap 16`, `maxWidth = min(screenW − 32, 400)`, default `maxHeight = 0.7·H`. With hole: `blockedBottom = max(padding.bottom, viewInsets.bottom)`; `above = hole.top − padding.top − 16`; `below = H − hole.bottom − blockedBottom − 16`; if `above < 200 && below < 200` → centered over everything (overlayOnHole), maxHeight 0.7H; elif `below >= 240 || below >= above` → top = `hole.bottom + 16`, maxHeight = `below − 8`; else bottom = `H − hole.top + 16`, maxHeight `above − 8`; finally `maxHeight = clamp(180, 0.8H)`. No hole → centered. Card is inside a vertical scroll.
- Native (Compose): a root-level `GuideOverlay` composable (above NavHost) + a `GuideTargetRegistry` where targets register via `Modifier.guideTarget("home.glossary")` (`onGloballyPositioned { boundsInRoot }` + a "my destination is current" flag from the NavBackStackEntry/lifecycle). **Dialogs and ModalBottomSheets are separate windows in Compose** — the settings tour runs inside the settings sheet, so either render the settings sheet in-window (custom sheet within the root composition) or host a second `GuideOverlay` inside the sheet's content; the "covered" logic must treat dialog windows as covering the base screen.

### 8.10 SatoshiDuell counting (tile)
`me = username.lowercase()`. From call 2 rows with `status ∈ {open, active}`: `isCreator = creator.lower == me`; `myScore = isCreator ? creator_score : challenger_score`; if `myScore == null` → (`status == open && isCreator` → waiting++ else myTurn++) else waiting++. From call 3: skip `creator == me`; `lobby++` if `target_player` null/empty or `== me`. Tile number `n = myTurn + lobby`. No npub / no profile → empty status (0/0/0). The tile's FutureBuilder re-invokes `fetchStatus()` on every rebuild (2-min cache) — natively, load in the ViewModel once per refresh.

### 8.11 Haptics
| API | Flutter call | Android mapping (Flutter engine) |
|---|---|---|
| `light()` / `lightImpact()` | `HapticFeedback.selectionClick` | `HapticFeedbackConstants.CLOCK_TICK` |
| `medium()` | `mediumImpact` | `KEYBOARD_TAP` |
| `heavy()` | `heavyImpact` | `CONTEXT_CLICK` |
| `success()` | medium, 90 ms, lightImpact | KEYBOARD_TAP, 90 ms, VIRTUAL_KEY |
| `error()` | heavy, 110 ms, heavy | CONTEXT_CLICK, 110 ms, CONTEXT_CLICK |
All gated by `haptic_enabled` (default true). Usage here: tile tap → light; tab tap / nearby → light; FAB → medium.

### 8.12 Logger rules
- Buffer max **800**; on overflow drop oldest. `debug()` recorded only when verbose; `info/diag` always (`diag` = INFO level); `security` → `SEC`; `warn(tag, msg, [err])` → `'$msg — ${err.runtimeType}: $err'`; `error(tag, msg, [err, stack])` → `msg` + `' — ${err.runtimeType}: $err'` + `' | bei ' + first 2 non-empty trimmed stack lines joined with ' <- '` (single line, no `\n`). `section(title)` → INFO tag `'ooo'` message `'───── $title ─────'`. `setVerbose(on)` records INFO tag `'Log'`: on `'Ausfuehrliches Log EINGESCHALTET — auch Detailmeldungen werden erfasst.'`, off `'Ausfuehrliches Log ausgeschaltet.'`.
- `measure(tag, label, action, rethrowError=false)`: success → `debug('$label ok (N ms)')`; failure → `error('$label FEHLGESCHLAGEN nach N ms', e, st)`; returns null unless rethrow.
- Persistence: debounced 2 s after last record; never before `init()` completes; `init()` merges `persisted + early (pre-init) entries`, persists immediately if early entries exist; corrupt JSON → keep only early entries.
- Never log secrets (nsec, tokens, full auth_url). Kotlin: map `runtimeType` to `e::class.simpleName`, message `e.toString()`.

### 8.13 Diagnostics fingerprint (`logEnvironment`, background at start)
Lines (tag `System`) in order: `section('APP-START')`; `'App $version (Build $buildNumber)'`; `'System: $os $osVersion'`; `'Sprache $localeName · Zeitzone $tzName (UTC±H)'` (hours abs, sign `-` if negative else `+`); location: `'Standort: Dienst AN|AUS · Berechtigung $perm'` (no permission prompt) then either warn `'Keine letzte bekannte Position vorhanden — auf Geraeten ohne Netzwerkortung (z.B. ohne Google Play Services) kann die Ortung in Gebaeuden fehlschlagen.'` or `'Letzte bekannte Position ist N Min alt (Genauigkeit X m)'`; NFC: since `kNfcEnabled == false` → `'NFC: abgeschaltet (features.dart)'`; `'Signatur-Modus: ${local|amber|nip07|nip46}'`; `'Bestand: N Badge(s) · M Favorit(en)'`; if verbose `'Ausfuehrliches Log ist AKTIV.'`. Each block failure → warn with its own message. **No PII** (no npub, coordinates, names). Native: `Build.VERSION.RELEASE/SDK_INT`, `Build.MANUFACTURER/MODEL` are fine (technical), `LocationManager.isLocationEnabled`, `checkSelfPermission`, `getLastKnownLocation` (fused or platform).

---

## 9. Security measures to preserve
1. **Logging hygiene (Audit H1, N5)**: no keys/seeds/tokens/full URLs with tokens in the log; NIP-46 `auth_url` logged as host only; diagnostics without personal data. (Existing diag lines do contain favorite labels and the SatoshiDuell username — acceptable but don't add more PII.)
2. **URL scheme whitelist (Audit M2)**: anything coming from remote data (auth_url, markdown, events, chat, portal) is opened only via `launchHttpUrl` (http/https + host). Hard-coded `nostr:`/`https:` constants may use direct intents.
3. **Reset completeness**: close NIP-46 session first; wipe badges in both storage places before prefs; delete nsec/npub/privHex, NIP-46 client key, EasyAuth blobs (password- and passkey-wrapped `ncryptsec` copies + credential id), portal token (server revoke + local), profile caches/local picture; then route to Intro with a cleared back stack. Native: additionally reset in-memory singletons (locale, currency, haptic cache, guide state, AppLogger buffer is cleared via prefs only on next start) and consider wiping the rolling-QR secure seed residuals.
4. **Backup before reset**: if the user chooses "Backup erstellen", the reset aborts unless the backup succeeded and the user confirms again.
5. **Portal token binding**: the stored token must match the current key (`tokenMatchesCurrentKey`); a foreign token is deleted; silent login only with local key; revocation only on a successful empty answer; clear own admin-cache entry on revocation; portal check before WoT re-verification on manual refresh.
6. **Unreadable badges are not "empty"**: no claim/publish, no admin re-verification (would permanently revoke), visible warning, no automatic healing writes from Home.
7. **Root/jailbreak indicator** warning (informational only; checks `/system/bin/su`, `/system/xbin/su`, `/sbin/su`, `/system/app/Superuser.apk`, `/system/app/SuperSU.apk`, `/data/local/bin/su`, `/data/local/xbin/su`, `/sbin/.magisk`, `/data/adb/magisk`, `/data/adb/modules`, busybox paths; `test-keys` only debug-logged) — owned by integrity service.
8. **Manifest hardening**: `allowBackup="false"`, `fullBackupContent="false"` (add `dataExtractionRules` excluding everything for API 31+), widget router activity `exported=false`, `noHistory`, `excludeFromRecents`, `taskAffinity=""`; widget/background receivers `exported=false`; no NDEF intent filter (NFC disabled, Audit N4); `FLAG_SECURE` while secrets are on screen (screen domain).
9. **Feature flags**: `kNfcEnabled = false` (NFC disabled entirely; do not request NFC at runtime; diagnostics log says disabled), `kWebLocalNsecLoginEnabled = false` (web only; N/A natively).
10. **Pending widget target read-once** (removed with `commit()` on read) to prevent intent replay.

---

## 10. Tests & vectors to port

| Dart test | Case | Expected |
|---|---|---|
| `app_logger_test.dart` | `warn('T','Plattformdaten nicht lesbar', UnsupportedError('Platform._version'))` | last entry level `WARN`, message contains `Platform._version` and `UnsupportedError` |
| | `error('T','Ablauf gescheitert', StateError('kaputt'), st)` with verbose off | level `ERROR`, contains `kaputt` and `'bei '`, no `\n` |
| | `error('Flutter', 'testlib', ArgumentError('Testfehler aus dem Widget-Baum'), stack)` | tag `Flutter`, level `ERROR`, contains both strings |
| `app_logger_init_race_test.dart` | prefs `diagnostic_log = [{"t":1000,"l":"INFO","g":"alt","m":"Eintrag von gestern"}]`; `warn('Zone','frueher Fehler vor init')` before init; wait 2.4 s | stored messages still `['Eintrag von gestern']`; after `init()` memory contains both; after another 2.4 s persisted contains both |
| `app_logger_volume_test.dart` | 25× `debug` with verbose off | buffer size unchanged; one `warn` adds exactly 1 (`WARN`) |
| | verbose on: 2× debug | +2 entries |
| `relay_parse_tally_test.dart` | 47 messages, 0 failures, report | no entry |
| | 47 messages, failures at i=0,16,32, report | exactly 1 `WARN` containing `3 von 47` and label `Admin-Liste von wss://relay`; `seen 47`, `failures 3` |
| | report twice | +1 only |
| | verbose on, 1 msg, failed(FormatException('ungueltiges EVENT')), report | +2: `WARN` then `DEBUG` containing `ungueltiges EVENT` |
| `guide_service_host_test.dart` | start `portal` (force) then `finishTourNow()` | `isActive false`, `activeTour null`, `currentStep null` synchronously |
| | start `wallet`, `finishTourNow()`, yield | `wasTourCompleted(wallet) == true` |
| | screen hosting a started tour is disposed | tour inactive, no error |
| | `finishGuideTourIfActive(portal)` while `events` active | `events` stays active |
| | accessing guide before dependencies | throws (Compose: N/A — use DI) |
| `snackbar_theme_test.dart` | snackbar text on themed bg and explicit `cCard` | text color `cText`, contrast ≥ 4.5 |
| `widget_test.dart` | app starts without crash | — (port as instrumentation smoke test) |
| `user_profile_test.dart` (splash routing) | `UserProfile().isOnboarded`, `nickname ''`, `'Anon'` | false |
| | `nickname 'Satoshi'`, `isNostrVerified true`, `isAdminVerified true` | true |
| `share_origin_test.dart`, `log_screen_share_origin_test.dart` | iOS share anchor | **not applicable on Android**; replace with a test that the share intent carries `EXTRA_TEXT == exportText()` and subject `21Meetup Diagnose-Log` |
| `meetup_event_matcher_test.dart` | (meetup domain) used by favorite matching | port in meetup domain; Home depends on it |

New native unit tests to add (vectors computed from the Dart algorithms):
1. Tile merge: `saved=['community','reputation','home_meetup','trust_score','x_unknown']` → `['community','nostr','converter','btc_dashboard','news','portal','events','shoutout','podcast','satoshiduell','portal_area','plebrap','organisator','reputation','trust_network','home_meetup','event_chats']`; `saved=['organisator','home_meetup']` → `['organisator','home_meetup','event_chats','reputation','trust_network','community','nostr','converter','btc_dashboard','news','portal','events','shoutout','podcast','satoshiduell','portal_area','plebrap']`; `saved=['btc_dashboard']` → `_defaultOrder` exactly.
2. Row packing with defaults (hidden = defaultHidden ∪ {trust_score}): pinned admin → `[[event_chats],[reputation,trust_network],[community,converter],[btc_dashboard],[organisator]]`; pinned non-admin → same without the last row; available → `[[nostr,news],[portal,events],[shoutout,podcast],[satoshiduell],[portal_area],[plebrap]]` (header count shows **6**).
3. Migrations on fresh install: persisted `tile_hidden` = defaultHidden ∪ {trust_score}; all 4 `mig_*` flags true; `tile_order` absent; in-memory hidden = defaultHidden.
4. `_daysUntil` (zone Europe/Berlin): now 2026-10-24 20:00, target 2026-10-25 19:00 → 1; now 2026-03-29 10:00, target 2026-03-30 09:00 → 1 (23-h day); now 2026-03-28 12:00, target 2026-03-30 08:00 → 2; same day earlier hour → 0; yesterday → -1.
5. Fav card sort: `[A(no event, city "Zürich"), B(event 2026-11-02), C(no event, "Berlin"), D(event 2026-10-30)]` → `[D, B, C, A]`.
6. Label font size: lengths 21→13, 16→15, 11→17, 10→22.
7. `groupInt`: 0→"0", 999→"999", 1000→"1.000", 95000→"95.000", 1234567→"1.234.567", -1234→"-1.234". Converter: price 56200 → sats 1779 → `"1 € = 1.779 sats"`.
8. Session time label: remaining 3725 s → `"1h 02m"`; 59 s → `"0h 00m"`.
9. Log format: `LogEntry(2026-03-05 07:08:09 local, 'WARN', 'Portal', 'x').format()` → `"05.03 07:08:09  WARN  [Portal] x"`; LogScreen line → `"07:08:09  WARN   Portal: x"`.
10. Guide: `startTour` no-op when completed unless `force`; `nextStep` on last step finishes and persists `guide_completed_<tour>`; `declineAllTours` writes all 10 tour keys + onboarding_asked; `resetAllTours` removes them.
11. Portal organizer state machine: table-driven tests over (hasToken, external, tokenMatches, status 200/401/0, list empty/non-empty, adminViaPortal) → expected (grant/revoke/no change, hint shown, login attempted).
12. Legacy prefs decoding (test lives in `:data:migration`, spec 14 §4.11): decode `"VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu!" + "[\"news\",\"podcast\"]"` → `["news","podcast"]`; `flutter.nostr_last_seen` as Long.

`test/fixtures/nip44.vectors.json` is not relevant to this domain.

---

## 11. Android capabilities / permissions and recommended APIs
| Need | API / library |
|---|---|
| UI | Jetpack Compose, Material 3 (Expressive), Navigation-Compose (type-safe routes), `ModalBottomSheet` (or custom in-window sheet for the tour), `PullToRefreshBox` |
| Splash | `androidx.core:core-splashscreen` |
| Edge-to-edge / immersive | `enableEdgeToEdge()`, `WindowInsetsControllerCompat` (hide navigation bars, transient by swipe) |
| Orientation | portrait for `smallestScreenWidthDp < 600` (already in MainActivity) |
| Settings storage | shared `:core:data` `KeyValueStore` (DataStore `app_prefs`); import done once by `:data:migration` (spec 14 §4.7) |
| Log buffer | in-memory ring buffer + `FileLogStore` JSON-lines file `filesDir/diagnostic_log.jsonl` (atomic temp-file + rename), debounced 2 s, synchronous flush in crash handler |
| Crash capture | `Thread.setDefaultUncaughtExceptionHandler`, `CoroutineExceptionHandler` |
| Localization | `res/values*/strings.xml`, `AppCompatDelegate.setApplicationLocales` (AppCompat 1.6+), `locales_config.xml`, `android.icu.text.MessageFormat` for ICU plurals |
| Fonts | bundled `res/font/rajdhani_*.ttf`, `inconsolata_*.ttf` |
| Images | Coil 3 (network crest/avatars, SVG via coil-svg for `einundzwanzig_logo.svg`, `bitcoin.svg`) |
| Photo picking | Photo Picker `ActivityResultContracts.PickVisualMedia` (no storage permission) |
| Haptics | `View.performHapticFeedback(HapticFeedbackConstants.*)` / `LocalHapticFeedback` |
| Sharing / clipboard | `Intent.ACTION_SEND` + chooser; `ClipboardManager` |
| External links | `Intent.ACTION_VIEW` (+ Custom Tabs optional) with http(s) whitelist; `<queries>` for `https`, `nostrsigner`, `lightning` (keep manifest entries) |
| Home widget bridge | AppWidget/Glance (widget domain); Home writes meetup city/countdown; deep-link targets via router activity or `onNewIntent` |
| Background polling | lifecycle-aware coroutines (`repeatOnLifecycle`), no WorkManager needed here |
| Location/NFC status in diagnostics | `LocationManager`, `ContextCompat.checkSelfPermission`; NFC disabled (`kNfcEnabled=false`) |
| Permissions used by this domain | `INTERNET` only (location permission only *checked*, never requested here) |

---

## 12. Cross-domain dependencies

**Called from this domain (outgoing):**
- Identity/profile: `UserProfile.load/save/reVerifyAdmin`, `SecureKeyStore.ensureMigrated/deleteKeys/deleteNip46ClientKey`, `LocalKeyVault.clearAll`, `IntroScreen`, `IdentitySetupScreen`, `ProfileEditScreen`, `HumanityProofService.getStatus/reverifyIfNeeded`.
- Signing: `SigningService.onNip46AuthUrl` (slot), `isExternalSigner`, `npub()`, `getMode()`, `canNip44()`, `disconnectNip46()`.
- Badges/trust: `MeetupBadge.loadBadges/resetAll/badgesUnreadable`, global `myBadges`, `BadgeClaimService.ensureBadgesClaimed`, `BadgeSyncService.init/retryIfDirty`, `ReputationPublisher.publishInBackground`, `TrustScoreService.calculateScore`, `BadgeWalletScreen`, `MeetupVerificationScreen(meetup "global")`, `ReputationQRScreen`, `ReputationCardScreen`.
- Admin: `AdminRegistry.removeFromCache`, `PromotionClaimService.publishAdminClaim/syncOrganicAdmins`, `AdminPanelScreen`.
- Portal: `PortalApiService.hasToken/loginWithNostr/tokenMatchesCurrentKey/deleteToken/rawGetStatus/logout/getRsvpCached/isGoing`, `PortalMeetupsScreen`, `PortalAreaScreen`.
- Meetups/calendar/events: `MeetupService.fetchMeetups/cached/resolveFavorite/cityFor/labelFor`, `allMeetups` fallback, `MeetupEventMatcher.resolve/matchesCity`, `MeetupCalendarService.fetchMeetupsPortalFirst/absoluteImageUrl/logoFor`, `CalendarEventService.fetchEvents/fetchByAddress`, `EventRsvpService.loadMine`, `MeetupSelectionScreen`, `MeetupDetailsScreen`, `CalendarScreen(initialSearch, initialMeetupId)`, `EventCalendarScreen(initialDay)`, `EventsHubScreen`, `MyEventsScreen`, `NearbyMeetupsScreen`.
- Chat: `ChatService.findRoomForMeetupId/findRoomForCity/unreadCounts`, `ChatRelayUnavailable`, `ChatScreen.room`, `EventChatService.unreadCounts`.
- Rolling QR: `RollingQRService.loadSession`, `MeetupSession`, `RollingQRScreen`.
- News/widget/bitcoin: `NewsService.unreadCount/cachedArticles/markRead`, `NewsScreen`, `WidgetService.refreshNews/markNewsSeen/updateMeetup/updateBitcoin/refreshBitcoin`, `MempoolService.getDashboardData/lastDashboard`, `BitcoinDashboardScreen`, `ConverterScreen`, `MempoolSettingsScreen`, `CurrencyService` (load/read/set/current/supported/symbol/groupInt/formatPrice).
- Community/fun: `CommunityHubScreen`, `SatoshiDuellService.fetchStatus`, `PlebrapAudio` (index/loading/player/toggle/next), `kPlebSongs`, `PlebrapPlayerScreen`, `V4VScreen`, `GlossaryScreen`, `MyNetworkScreen`.
- Nostr infra: `NostrProfileService.getLocalPicture/setLocalPicture/fetchProfilePicture/clearCache`, `RelaySettingsScreen`, NIP-19 decode.
- Backup: `BackupService.createBackup(context) -> bool`, `restoreBackup(context)`.
- Integrity: `DeviceIntegrityService.check/warningMessage`.
- Diagnostics: geolocation status, package info.

**Provided to other domains (incoming):**
- `AppLogger` (+ `RelayParseTally`, `measure`, `diag`, `security`, `section`) — used app-wide.
- `HapticService`, `Pressable`, `EmptyState`, `GlassCard/StatusChip/SectionHeader`, `shadowForElevation`, theme colors/typography/metrics, `fontMono`.
- `launchHttpUrl/launchHttpUri` (safe URL launching).
- `GuideService`, `GuideStep`, `GuideTour`, `GuideOverlay`, `GuideServiceHost` (`finishGuideTourIfActive`), tour key holders (`WalletTour`, `CommunityTour`, `PortalAreaTour`, `MyMeetupsTour`, `EventBadgeTour` infra).
- `LocaleController` (current locale for formatting elsewhere), `localizedLevel`.
- Feature flags `kNfcEnabled`, `kWebLocalNsecLoginEnabled`.
- `HomeScreen.refreshAfterScan()` (shell), root snackbar host (`rootMessengerKey` equivalent) for code without UI context.
- Widget deep-link consumption (targets `news`, `bitcoin`, `meetup`).

---

## 13. Proposed Kotlin package/module structure and public API

```
:core:ui            space.einundzwanzig.meetup.core.ui
  theme/ Color.kt (AppColors), Type.kt (Rajdhani/Inconsolata), Theme.kt (EinundzwanzigTheme), Dimens.kt (TileGap=10.dp, TileRadius=10.dp), Shadows.kt
  components/ Pressable.kt (Modifier.pressScale), EmptyState.kt, GlassCard.kt, StatusChip.kt, SectionHeader.kt, DottedPlaceholder.kt
:core:common        ...core.common.log   (logging lives in :core:common, package `log`)
  AppLogger.kt, DiagnosticLogger.kt, LogEntry.kt, LogStore.kt (FileLogStore), RelayParseTally.kt, DiagnosticsService.kt, crash handler
:data:settings      ...data.settings     (on :core:data KeyValueStore; no own DataStore file, no importer)
  SettingsRepository.kt, PrefKeys.kt
:core:platform      ...core.platform
  Haptics.kt, SafeUrlLauncher.kt, ShareHelper.kt, LocaleController.kt, FeatureFlags.kt
:feature:guide      ...feature.guide
  GuideTour.kt, GuideStep.kt, GuideService.kt, GuideTargetRegistry.kt, GuideOverlay.kt, GuideStrings.kt
  tours/ HomeTour.kt, SettingsTour.kt, WalletTour.kt, CommunityTour.kt, PortalAreaTour.kt, MyMeetupsTour.kt, WotTour.kt
:feature:shell      ...feature.shell
  MainActivity.kt (existing name/package kept), AppStartup.kt, SplashGate.kt, AppShell.kt, ScanSheet.kt, RootSnackbarHost.kt, WidgetLaunchTarget.kt
:feature:home       ...feature.home
  HomeScreen.kt, HomeViewModel.kt, HomeUiState.kt
  tiles/ TileId.kt, TileCatalog.kt, TileLayoutEngine.kt, TileOrderRepository.kt, TileComposables.kt, BtcTile.kt, SatoshiDuellTile.kt, PlebrapTile.kt
  favorites/ FavoritesCarousel.kt, FavCardsUseCase.kt, CrestCoverFlow.kt
  admin/ PortalOrganizerChecker.kt
  customize/ CustomizeSheet.kt, EditModeState.kt
  reset/ ResetAppUseCase.kt, ResetDialogs.kt
:feature:settings   ...feature.settings
  SettingsSheet.kt, LanguageDialog.kt, CurrencyDialog.kt
:feature:log        ...feature.log
  LogScreen.kt, LogViewModel.kt
```

Key signatures:
```kotlin
// core.common.log
data class LogEntry(val timeMs: Long, val level: String, val tag: String, val message: String) {
    fun format(zone: ZoneId = ZoneId.systemDefault()): String      // "dd.MM HH:mm:ss  LEVEL [tag] msg"
    fun toJson(): JsonObject                                         // keys t,l,g,m
    companion object { fun fromJson(o: JsonObject): LogEntry }
}
object AppLogger {
    val entries: StateFlow<List<LogEntry>>
    val isVerbose: Boolean
    suspend fun init(store: LogStore)
    suspend fun setVerbose(on: Boolean)
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun diag(tag: String, message: String)
    fun warn(tag: String, message: String, error: Throwable? = null)
    fun error(tag: String, message: String, error: Throwable? = null, stack: Array<StackTraceElement>? = error?.stackTrace)
    fun security(tag: String, message: String)
    fun section(title: String)
    suspend fun <T> measure(tag: String, label: String, rethrow: Boolean = false, action: suspend () -> T): T?
    fun exportText(): String
    suspend fun clear()
    fun flushBlocking()                                              // crash handler
}
class RelayParseTally(val tag: String, val label: String) { fun message(); fun failed(e: Any? = null); fun report(); val seen: Int; val failures: Int }
object DiagnosticsService { suspend fun logEnvironment(ctx: Context) }

// data.settings
class SettingsRepository(store: KeyValueStore) {
    val locale: Flow<String?>; suspend fun setLocale(code: String?)
    val hapticEnabled: Flow<Boolean>; suspend fun setHapticEnabled(on: Boolean)
    val fiatCurrency: Flow<String>; suspend fun setFiatCurrency(code: String)
    suspend fun isTourCompleted(t: GuideTour): Boolean; suspend fun setTourCompleted(t: GuideTour, done: Boolean)
    suspend fun onboardingAsked(): Boolean; suspend fun setOnboardingAsked(v: Boolean)
    suspend fun nostrLastSeen(): Long; suspend fun setNostrLastSeen(sec: Long)
    suspend fun clearAll()
}
// No LegacyFlutterPrefs here: the Flutter prefs import (incl. both List<String> encodings) is :data:migration (spec 14 §4.6.1/§4.7).

// core.platform
object Haptics { fun light(view: View); fun medium(view: View); fun heavy(view: View); suspend fun success(view: View); suspend fun error(view: View) }
object SafeUrlLauncher { fun launchHttpUrl(ctx: Context, url: String): Boolean; fun launchHttpUri(ctx: Context, uri: Uri): Boolean }
object LocaleController { val supported = listOf("de","en","es"); fun displayName(code: String?): String; suspend fun set(code: String?) }

// feature.guide
enum class GuideTour(val persistName: String) { Onboarding("onboarding"), Home("home"), Settings("settings"), Events("events"), Portal("portal"), Wallet("wallet"), Reputation("reputation"), Scan("scan"), MyMeetups("myMeetups"), PortalArea("portalArea") }
data class GuideStep(val targetId: String?, @StringRes val title: Int, @StringRes val body: Int, @StringRes val hint: Int? = null,
    val blockOutside: Boolean = true, val interactiveTarget: Boolean = true, val advanceOnTargetTap: Boolean = false,
    val completeWhen: (() -> Boolean)? = null, val autoAdvance: Boolean = false, val waitTimeoutMs: Long = 6000,
    val holePadding: Dp = 10.dp, val holeRadius: Dp = 14.dp, val scrollIntoView: Boolean = true)
class GuideService(private val settings: SettingsRepository) {
    val state: StateFlow<GuideState>
    suspend fun wasTourCompleted(t: GuideTour): Boolean
    suspend fun shouldAskForOnboarding(): Boolean; suspend fun markOnboardingAsked()
    suspend fun declineAllTours(); suspend fun resetTour(t: GuideTour); suspend fun resetAllTours()
    suspend fun startTour(t: GuideTour, steps: List<GuideStep>, force: Boolean = false)
    fun nextStep(); fun previousStep(); fun skipStep(); fun reportTargetTapped(); fun reportStepCompleted()
    fun pause(); fun resume(); suspend fun finishTour(); fun finishTourNow()
    fun finishIfActive(t: GuideTour)
}
class GuideTargetRegistry { fun Modifier.guideTarget(id: String): Modifier; fun boundsOf(id: String): Rect?; fun isCovered(id: String): Boolean }
@Composable fun GuideOverlay(service: GuideService, registry: GuideTargetRegistry)

// feature.home
enum class TileId(val id: String, val span: Int, val removable: Boolean) { TrustScore("trust_score",2,false), HomeMeetup("home_meetup",3,false), EventChats("event_chats",3,false), Reputation("reputation",1,true), Community("community",2,true), TrustNetwork("trust_network",2,true), Events("events",1,true), Shoutout("shoutout",1,true), Podcast("podcast",1,true), SatoshiDuell("satoshiduell",2,true), PortalArea("portal_area",2,true), PlebRap("plebrap",3,true), Nostr("nostr",1,true), PortalConnect("portal_connect",2,true), Converter("converter",1,true), BtcDashboard("btc_dashboard",2,true), News("news",2,true), Portal("portal",2,true), Organisator("organisator",3,true) }
object TileLayoutEngine {
    val DEFAULT_ORDER: List<String>; val DEFAULT_HIDDEN: Set<String>
    fun mergeOrder(saved: List<String>): List<String>
    fun packRows(order: List<String>, hidden: Set<String>, pinned: Boolean, isVisible: (TileId) -> Boolean, excludeHomeMeetup: Boolean = true): List<List<TileId>>
    fun rowHeight(targetHeight: Float, rows: Int, gap: Float = 10f, min: Float = 120f): Float
    fun moveTile(order: List<String>, dragged: String, target: String): List<String>
}
class TileOrderRepository { suspend fun load(): Pair<List<String>, Set<String>> /* runs migrations */; suspend fun save(order: List<String>, hidden: Set<String>) }
class FavCardsUseCase { suspend operator fun invoke(user: UserProfile, now: ZonedDateTime): List<FavCard> }
fun daysUntil(target: Instant, now: ZonedDateTime): Int
class PortalOrganizerChecker { suspend fun check(user: UserProfile, badges: List<MeetupBadge>): OrganizerCheckResult /* Granted, Revoked, Unchanged, ShowHint, ShowTokenMismatch */ }
class ResetAppUseCase { suspend fun performReset() }
class HomeViewModel : ViewModel() {
    val state: StateFlow<HomeUiState>; val events: SharedFlow<HomeEvent> /* snackbars, navigation */
    fun loadAll(); fun refreshAll(fromGesture: Boolean); fun refreshAfterScan(); fun onResume()
    fun onTileLongPress(id: String); fun onTogglePin(id: String); fun onMoveTile(dragged: String, target: String); fun onCustomizeSaved(order: List<String>, hidden: Set<String>)
    fun toggleAvailableCollapsed(); fun openNostr(); fun openNews(); fun togglePortalConnection(); fun openMeetupChat(favKey: String, label: String)
    fun onLocalPicturePicked(uri: Uri); fun dismissIntegrityWarning(); fun consumeWidgetTarget(target: String)
}
```

---

## 14. Risks / compatibility pitfalls
1. **Legacy prefs migration**: Flutter stores everything in `FlutterSharedPreferences` with `flutter.` prefix and special list/double prefixes; ints are Longs. If the central `:data:migration` (spec 14 §4.7) misses a key, users lose tile layout, language, currency, tour flags — and (other domains) favorites, signing mode, badges metadata. Both list encodings (JSON `!` and legacy Base64 Java-serialized) must be decodable.
2. **`guide_completed_*` names** use Dart enum names (`myMeetups`, `portalArea` camelCase). A Kotlin `name()` (`MyMeetups`) would silently re-run tours. Use explicit `persistName`.
3. **Dead tiles** `trust_score`, `portal_connect`: not in `_defaultOrder` → never drawn; keep ids reserved (persisted in `tile_hidden`), don't "fix" by adding them (would change layouts for existing users). Their tour steps intentionally skip.
4. **`removable` vs `_requiredTiles` inconsistency**: customize sheet locks `home_meetup`+`reputation`, while the dashboard pin button is suppressed for `trust_score`, `home_meetup`, `event_chats`. A user can hide `event_chats` via the sheet and then never re-pin it (no pin button, not listed under VERFÜGBAR). Decide whether to replicate or fix (recommend: treat `removable=false` tiles as locked in the sheet too, and keep `reputation` lockable as in the sheet).
5. **Available header count = rows, not tiles** (Dart quirk). Replicate or consciously change.
6. **Haptic cache bug**: the settings switch writes prefs directly; `HapticService` caches the first read, so tile/tap haptics keep the old setting until restart. Natively use one Flow-backed source of truth (intended behavior).
7. **Stale data refresh gaps**: news counter/headline and "events today" are only loaded once at Home init (not on refresh/resume); converter tile only updates on parent rebuild. Native may refresh them on manual refresh — acceptable improvement, but keep the "first ever unread = 0" news rule (news domain).
8. **`refreshAfterScan` race**: trust score is calculated before the badge reload completes in Dart. Sequence it natively.
9. **SatoshiDuell FutureBuilder** re-calls the network on every rebuild (1 Hz while a session is live), even when the tile is in the collapsed Available section (the row builder is invoked just to count rows). Don't replicate; load once per refresh in the ViewModel.
10. **BTC tile polling** continues while Home is hidden in the IndexedStack and writes the widget even with dead data (`updateBitcoin` without `isDead` check). Tie polling to lifecycle; keep the "don't overwrite widget with dead data" rule from `refreshBitcoin`.
11. **Compose windows vs global overlay**: Flutter's overlay sits above dialogs/sheets in a single surface; in Compose, dialogs and `ModalBottomSheet` are separate windows. The settings tour (inside the sheet) and the "covered" detection need special handling (§8.9).
12. **Locale fallback**: Flutter resolves unsupported device languages to German (first supported). Android resource fallback uses `values/` — put German there, not English. Per-app language must persist across reset semantics (`p.clear()` removes `app_locale`, but in-memory locale stays until restart in Dart).
13. **ICU plurals with `=0`/`=1`** cannot be mapped to Android `<plurals>`; use ICU MessageFormat. Placeholders typed `Object` are just `toString()`.
14. **Number/date formatting must be locale-independent** where the Dart code hard-codes it (`.` thousands separator, `d.m.yyyy`, `toStringAsFixed(1)` with `.`). Using `String.format` with the default locale would produce `1,5` in German, and even `String.format(Locale.ROOT, "%.1f")` rounds differently from Dart (`1.45 → "1.5"` vs Dart `"1.4"`); use the shared `Double.toStringAsFixed(fractionDigits)` from `:core:common` (`space.einundzwanzig.meetup.core.common.dart`, DartCompat.kt) (§7) for every score display and the QR `sc` field.
15. **Crash logging persistence**: AppLogger debounces 2 s; native uncaught exceptions kill the process — flush synchronously in the handler or the most valuable entries are lost.
16. **Log export size**: up to 800 entries via `ACTION_SEND` EXTRA_TEXT can hit Binder limits; fall back to a file attachment.
17. **Reset leftovers**: `SharedPreferences.clear()` does not touch `HomeWidgetPreferences`/`e21_widget`; widget keeps showing the old meetup until next update; rolling-QR secure seeds remain; in-memory locale/currency/haptic/guide state survive until restart. Native reset should wipe these explicitly (and the widget) while keeping the exact deletion **order** (NIP-46 → badges → prefs → keys → vault → portal → profile cache).
18. **`'Unbekannt'` meetupName** in organizer claims and German literals in widget countdown are data seen by other clients/widget — keep byte-identical unless the consuming domain changes too.
19. **Portal organizer logic** is security-relevant (grant/revoke). Port as a pure, unit-tested state machine; never revoke on non-200/timeouts; never silently trigger an external signer at startup.
20. **Fonts**: Flutter downloads Rajdhani/Inconsolata at runtime; first-run offline shows fallback fonts. Bundle them natively (license OFL) — visual metrics (e.g. `minRowHeight 120` tuned so "1 € = 1.779 sats" fits) depend on Rajdhani's narrow glyphs; using Roboto would overflow tiles.
21. **Avatar local path**: Flutter stores a cache path from image_picker; it may be purged. Natively copy to `filesDir`; when importing legacy prefs, validate the path exists.
22. **Widget deep-link replay** (MIUI): keep the read-once pending-target mechanism or equivalent intent consumption to avoid re-routing on task restore.
23. **Integer `nostr_last_seen`** is unix **seconds**, `diagnostic_log` `t` is **milliseconds** — don't mix.
24. **Splash min delay & routing**: route decision (`isOnboarded`) must use the same derivation as identity domain; and `IdentitySetupScreen` is pushed from Home when nickname is `Anon`/empty but the user is verified.
