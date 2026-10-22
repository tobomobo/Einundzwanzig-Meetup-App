# :core:ui

Design system and shared composables: the brand theme (Material 3 Expressive with the Flutter
app's colours, Rajdhani and Inconsolata), shared components, haptics, screenshot and clipboard
protection for secrets, Nostr avatar and npub chip, Markdown, QR rendering, the scanner overlay,
badge art, the meetup crest, the spotlight guide framework, offscreen capture, and the app's single
FileProvider with share helpers.

```kotlin
implementation(project(":core:ui"))   // feature modules get it from the convention plugin
```

Package root: `space.einundzwanzig.meetup.core.ui`. Exposes `:core:l10n` and `coil-compose` as `api`.
There is no Hilt in this module. Data seams are `CompositionLocal` interfaces that data modules
implement and the app shell provides (`NostrProfileSource`, `MeetupCrestSource`, `TourProgressStore`).

## App shell wiring (`:app`)

```kotlin
EinundzwanzigTheme(themeMode = settings.themeMode) {
    CompositionLocalProvider(
        LocalHapticsEnabled provides settings.hapticEnabled,        // pref `haptic_enabled`, default true
        LocalAppSnackbarHostState provides snackbarHostState,       // render with AppSnackbarHost
        LocalNostrProfileSource provides profileSource,             // :data:profile
        LocalMeetupCrestSource provides crestSource,                 // :data:meetups (CrestRepository)
    ) {
        GuideHost(guideController) { NavDisplay(...) }               // GuideController(store, appScope)
    }
}
```

`GuideController(store: TourProgressStore, scope)`: `:data:settings` implements `TourProgressStore`.
Provide one app-wide instance, for example from a Hilt module in `:app`.

## Theme — `…ui.theme`

| API | Notes |
|---|---|
| `EinundzwanzigTheme(themeMode = ThemeMode.DARK, dynamicColor = false) { }` | `MaterialExpressiveTheme` with brand colours, `MotionScheme.expressive()`, brand shapes (6/10/14/20/28) |
| `ThemeMode { DARK, LIGHT, SYSTEM }` | Dark is the brand default because the Flutter app was dark-only |
| `MaterialTheme.extendedColors` → `ExtendedColors` | `success/onSuccess/successContainer/onSuccessContainer`, `community` (gold `cCyan`) + `communityContainer`, `nostr`, `nostrOnTile`, `course`, `textTertiary`, `bitcoin`, `purple` (`cPurple`, Podcast), `duellGold` (SatoshiDuell `#FFC93C`), `amber` (Flutter `Colors.amber` `#FFC107`, warning/caution), `qrBackground`/`qrModule` (fixed light QR card + dark modules in every theme), `onScrim` (content on `colorScheme.scrim`), `bitcoinOnScrim` (`cOrangeLight` accent on a scrim, same in both themes) |
| `BrandColors` | Raw Flutter palette (`cOrange`, `cCard`, …). Use it only where a colour must not follow the theme, such as a scrim. Features should use the colour scheme |
| Colour mapping | `primary` = cOrange, `background/surface` = cDark, `surfaceContainerLow` = cSurface, `surfaceContainer` = cCard, `surfaceContainerHigh` = cCardHover, `onSurfaceVariant` = cTextSecondary, `outlineVariant` = cTileBorder, `tertiary` = gold |
| Typography | Flutter slots exact: displayLarge 34, displayMedium 26, titleLarge 18 (w700), titleMedium 15 (w600), bodyLarge 15/1.5, bodyMedium 13/1.5, bodySmall 11/1.4, labelSmall 10 (w600, ls 1). The other slots use the M3 baseline in Rajdhani. The styles carry no colours |
| `Rajdhani`, `Inconsolata`, `MonoStyle` | Bundled fonts (OFL) |
| `Dimens` | `TileGap 10`, `TileRadius 10`, `TileShape`, `Hairline 0.5`, `GlassRadius 20`, `ScreenPadding 20` |
| `shadowForElevation(e, accent)`, `Modifier.elevationShadow(e, shape, accent)` | Port of `shadows.dart` |

## Motion — `…ui.motion`

`Modifier.pressScale(interactionSource, pressedScale = 0.97f)` and `Pressable(onClick, onLongClick = null) { }`
(port of Flutter `Pressable`; claims a long press only when one is given), `ExpressiveSprings`,
`isReducedMotion(context)`, `rememberReducedMotion()`, and `LocalReducedMotion` (override for tests and previews).

## Components — `…ui.component`

| API | Notes |
|---|---|
| `GlassCard(onClick?, contentPadding, borderColor?, cornerRadius, gradient, glowing) { }` | Flutter `GlassCard` |
| `Tile(onClick?, onLongClick?, …) { }` | Dashboard/list tile: `cCard`, radius 10, hairline border, press scale |
| `StatusChip(label, color, icon?)`, `StatusPill(label, color)`, `SectionHeader(title, actionLabel?, onAction?, color?)` | Flutter widgets |
| `EmptyState(icon, title, subtitle, actionLabel?, onAction?, accentColor)` | Pulsing icon that stays still with reduced motion |
| `InfoBanner(...)`, `enum Tone { Neutral, Info, Success, Warning, Error }`, `Tone.accentColor()`, `Tone.defaultIcon()` | Inline warning and hint banners |
| `AppTopBar`, `AppMediumTopBar`, `AppLargeTopBar`, `BackNavigationButton`, `AppTopBarDefaults` | Flexible M3E top bars with brand colours; `AppLargeTopBar(titleModifier = …)` targets the title text only (tour anchors) |
| `ConfirmDialog(title, text, confirmLabel, onConfirm, onDismiss, destructive, icon, dismissible)` | Brand dialog |
| `AppLoadingIndicator`, `AppContainedLoadingIndicator`, `FullScreenLoading`, `LoadingPlaceholder(height)` | Expressive `LoadingIndicator` instead of spinners |
| `MonoText`, `SatsAmount(amount)` | Inconsolata |
| `AppSnackbarHost(hostState)`, `SnackbarHostState.showAppSnackbar(message, tone, durationMillis, …)`, `showLongDismissable(...)`, `AppSnackbarDurations` (1/2/3/6/8/10 s), `LocalAppSnackbarHostState` | Exact Flutter display times (scaled by the accessibility timeout) |

## Formatting and URLs — `…ui.format`, `…ui.util`

- `Formats.groupInt` (`95.000`), `sats`, `blockHeight` (`---` for ≤ 0), `dateShort` (`d.M.yyyy`),
  `datePadded`, `time`, `dateTimePadded` (two spaces). For Dart rounding use `Double.dartRound()` from `:core:common`.
- `SafeUrl.isHttpUrl / httpHost` and `SafeUrlLauncher.open(context, url)`: port of
  `safe_url_launcher.dart`. Only http(s) URLs with a host are opened (audit M2).
- `Context.findActivity()`.

## Haptics — `…ui.haptics` (spec 12 §8.11, spec 00 item 7)

`val haptics = rememberHaptics()` returns `Haptics`, which offers `light()`, `medium()`, `heavy()`,
`success()` (KEYBOARD_TAP, 90 ms, VIRTUAL_KEY), `error()` (CONTEXT_CLICK, 110 ms, CONTEXT_CLICK),
and the Dart aliases `lightImpact()`, `mediumImpact()`, `heavyImpact()`. The constants are the
ones the Flutter engine uses. Every call checks `LocalHapticsEnabled`, which the shell provides
from `haptic_enabled`. Spec 12 calls this `HapticService`; here it is a view-bound class because
Android haptics need a `View`. ViewModels emit events, and the screen plays them.

## Secrets — `…ui.security` (spec 01 §9 M3, spec 14 §6 / F7)

| API | Use |
|---|---|
| `SecureScreenEffect(enabled = true)` | Put it inside every composable that shows an nsec or ncryptsec. Sets `FLAG_SECURE` on the activity window and on the hosting dialog/sheet window, reference counted |
| `SecureDialog(onDismissRequest, properties = SecureWindows.dialogProperties()) { }` | Dialog for secrets: its window is created `SecureOn` and it applies `SecureScreenEffect` itself |
| `SecureWindows.dialogProperties(...)` / `SecureWindows.bottomSheetProperties(...)` | **Required** for every `AlertDialog`/`ModalBottomSheet` that shows a secret, because Compose dialogs are separate windows (F7). Also call `SecureScreenEffect()` in the content |
| `SecretClipboard.copySecret(context, secret, label)` | Sensitive clip (`EXTRA_IS_SENSITIVE`), cleared after 45 s if unchanged. If the app is in the background at that time, the clear happens on the next resume within 10 min |
| `SecretClipboard.copyPlain(context, text, label)` | npub, pairing URIs, the setup sheet's ncryptsec (plain copies, as in Flutter) |
| `ScreenSecure.acquire(view): () -> Unit` | Non-Compose escape hatch (reference counted) |

Device authentication (BiometricPrompt) lives in `:data:identity`, not here.

## Nostr — `…ui.nostr` (spec 07 §2.3/§2.4/§8.7)

- `NostrAvatar(fallbackText, backgroundColor = primary, radius = 20.dp, pubkeyHex = null, pictureLoader = null, shape = CircleShape)`
  clears the old picture immediately on an identity change and discards late results. `pubkeyHex = null`
  means the active identity. It falls back to a letter (or `?`) when the image fails.
- `AvatarImage(model, fallbackText, size, shape, …)`: the stateless variant for local files and URIs (home picture precedence).
- `NpubChip(npub, head = 10, tail = 8, style, showIcon, showName, displayNameLoader, onOpenProfile)`:
  tap opens njump.me (light haptic), long press or the chip copies (medium haptic) and shows the `npubCopied` snackbar for 2 s.
- `NpubFormat.shorten` (10…8 with `…`), `NpubFormat.shortenNpub` (`NostrService.shortenNpub`: 12 + `...` + 8), `profileUrl`.
- `interface NostrProfileSource { activePubkeyHex(); pictureUrl(hex); displayNameForNpub(npub) }` and
  `LocalNostrProfileSource`. `:data:profile` implements it. `AvatarPictureKey` is a semantics key for tests.

## Markdown — `…ui.markdown` (spec 11 §8.6)

`MarkdownView(data, onLinkClick = null)` implements the exact Flutter block algorithm. Only http(s)
links are followed. Data-URI images are allowed up to 3 MiB of base64, HEIC/HEIF shows a placeholder,
and network images are decoded at most 1080 px wide. A parse failure falls back to selectable plain
text. `MarkdownParser.parse/inline/plainFallback`, `MdBlock`, `MdSpan` and `MarkdownImages` are public
for tests.

## QR — `…ui.qr`

- `QrCode(content, size, errorCorrection = L, padding = 10.dp, backgroundColor, moduleColor, contentDescription)`
  replaces qr_flutter. It encodes UTF-8 bytes without ECI, which matches the bytes of the `qr` package.
  Keep it on a light background.
- `QrMatrix.encode(content, ec)`, `QrErrorCorrection`, `DrawScope.drawQrMatrix`.
- `ScannerOverlay(windowSize = 260.dp, accent = primary)` is drawing only and never consumes
  touches. The camera stays in the feature. `DrawScope.drawScannerOverlay`.

## Badge art and crest — `…ui.badge`, `…ui.crest` (spec 03 §8.6, §2.5)

- `BadgeArt(seed)`, `BadgeArtSpec.from(seed)` / `seedFor(meetupName, blockHeight)`, and `DrawScope.drawBadgeArt(spec)`
  reproduce `BadgeArtPainter` bit for bit (vector in spec 03 §10.4).
- `MeetupCrestWatermark(meetupName, opacity = 0.20f, widthFactor = 1.05f, imageUrl = null)`: on first
  composition it triggers `MeetupCrestSource.ensureLoaded()` when there is no own image.
  `MeetupCrestFace(meetupName, imageUrl, contentDescription, fallback)` never fetches.
- `interface MeetupCrestSource { crestUrl(meetupName, imageUrl): Flow<String?>; ensureLoaded() }` and
  `LocalMeetupCrestSource`. `:data:meetups` implements the §2.5 URL resolution (absolutized).
- `CrestWatermarkImage(url, …)` and `CrestFaceImage(url, …)` are the pure renderers.
  `CrestWatermarkGeometry`, `crestInitials`, and `CrestMonogram` are also available.

## Guide / spotlight tours — `…ui.guide` (spec 12 §2.9, §3.3, §3.4, §8.9)

| API | Notes |
|---|---|
| `GuideHost(controller) { content }` | Provides `LocalGuideController` and `LocalGuideTargetRegistry` and draws the overlay above all screens |
| `GuideWindowHost { }` | Second overlay inside a `ModalBottomSheet`/`Dialog` window (settings tour) |
| `Modifier.guideAnchor("home.glossary")`, `Modifier.guideScrollContainer(state)` | Targets. Drags on the blocked area scroll the target's container |
| `GuideStep(targetId?, title, body, hint?, blockOutside, interactiveTarget, advanceOnTargetTap, completeWhen, autoAdvance, waitTimeoutMillis = 6000, holePadding = 10.dp, holeRadius = 14.dp, scrollIntoView)` | Dart `GuideStep` |
| `GuideController` | `state: StateFlow<GuideState>`, `startTour(tour, steps, force)`, `nextStep`, `previousStep`, `skipStep`, `reportTargetTapped`, `reportStepCompleted`, `pause/resume`, `finishTour()`, `endTour()`, `finishTourNow()`, `finishIfActive(tour)`, `wasTourCompleted`, `shouldAskForOnboarding`, `markOnboardingAsked`, `declineAllTours`, `resetTour`, `resetAllTours` |
| `GuideTour` | Ten tours. `persistName` is the Dart enum name (`myMeetups`, `portalArea`) |
| `TourProgressStore` (+ `completedKey(tour)` = `guide_completed_<name>`, `ONBOARDING_ASKED_KEY`) | Implemented by `:data:settings`. `InMemoryTourProgressStore` is for tests and previews |
| `StartTourEffect(tour, enabled, force, delayMillis) { steps }`, `FinishTourOnDispose(tour)` | The Flutter screen patterns |

## Capture and share — `…ui.share`

- `rememberComposableCapturer().capture(width, height, pixelRatio = 3f) { content }` returns a `Bitmap`
  (Flutter `RepaintBoundary.toImage`). Inside the capture, `LocalAllowHardwareBitmaps` is false.
  Pass it to every `ImageRequest.allowHardware(...)` you build.
- `ShareHelper`: `writePng`, `writeFile` (to `cacheDir/share/`), `shareDir`, `backupDir`
  (`cacheDir/backup/`), `uriFor(file)`, `shareFile`, `shareImage`, `shareText` (each returns whether
  a chooser started), plus `shareFileForResult`/`shareTextForResult` returning `ShareResult`
  (`Success(chosenComponent)`, `Dismissed`, `Unavailable`; `isNotDismissed` = Dart `status != dismissed`).
- The manifest declares the app's only FileProvider `ShareFileProvider` (`${applicationId}.fileprovider`,
  paths `share/` and `backup/`) and the non-exported `ShareChooserReceiver`. The app must not declare
  another provider with this authority.

## Native-only strings

`res/values{,-en,-es}/strings.xml`: `native_markdown_image_placeholder`, `native_npub_copy`,
`native_npub_open_profile`, `native_avatar_description`, `native_snackbar_dismiss`, `native_guide_tip`.

## Tests

`tools/gradlew-locked :core:ui:testDebugUnitTest` runs 143 JVM/Robolectric tests. Ported Dart
tests: `nostr_avatar_test` (spec 07 §10.8), `snackbar_theme_test`, `guide_service_host_test`.
Also covered: the badge-art vector (spec 03 §10.4), Markdown golden cases (spec 11), SafeUrl,
haptic mapping, FLAG_SECURE and the clipboard, share and FileProvider, the chooser result,
QR byte compatibility, spotlight geometry and ticker, crest, and formats.
