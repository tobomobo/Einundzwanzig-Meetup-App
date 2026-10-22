# :feature:home

Home dashboard tab (spec 12 §2.5) plus the scan sheet of the app shell (spec 12 §2.4).

## Entries and public API

- `fun EntryProviderScope<NavKey>.homeEntries()` registers `HomeTab` → `HomeRoute()`.
- `ScanSheet(onDismissRequest, onScanBadge, onCheckReputation)` / `ScanSheetContent(...)`: the
  sheet of the shell's central scan button ("Badge scannen", "Reputation prüfen"). The shell plays
  the medium haptic, opens the sheet and pushes `BadgeScanner` / `ReputationQr` from the callbacks.
- `HomeRefreshRequests` (`@Singleton`, inject in the shell): call `request()` when the Home tab
  is tapped (also when already selected) and when the scanner opened from the scan sheet returns
  (Flutter `refreshAfterScan`).
- `HomeTour`: the 21 tour steps and anchor ids. The shell must attach
  `Modifier.guideAnchor(HomeTour.BADGE_TAB | NEARBY_TAB | SCAN_FAB | EVENTS_TAB | HOME_TAB)` to its
  bar items; otherwise those steps skip after their timeout.

## Screens and pieces

| Piece | Spec |
|---|---|
| Logo bar (refresh/hourglass → `LoadingIndicator`, glossary "?", gear) | §2.5.2 |
| Profile header: avatar (local file → Nostr picture → initial, Photo Picker via `LocalPictureImporter`), nickname, score pill → `ReputationCard` | §2.5.3, F14 |
| Root warning card (dismissible), live session card (pulsing dot, `evt:` medal, 1 s countdown) → `RollingQr` | §2.5.4 |
| Home-meetup CTA → `MeetupSelection`; favorites `HorizontalPager` with cover-flow crest, countdown, EVENTS/Chat/Info bar, page dots | §2.5.5, §8.3 |
| Chat lookup with `chatSearching` / `chatNoRoom` / `chatOffline` / `chatRelayUnavailable` | §8.6 |
| Tile engine (row packing, row heights, "Verfügbar" section with row count, all 17 drawable tiles incl. BTC 60 s poll, converter, news, SatoshiDuell, PlebRap mini player, Nostr dot) | §2.5.6, §2.5.8 |
| Edit mode (long press, pin button, drag onto another tile, TalkBack move actions, floating toolbar) | §2.5.7 |
| Customize sheet (reorder, hide/add, save on FERTIG) | §2.5.9 |
| Settings sheet (`:feature:settings`), restore → full reload | §2.6 |
| Reset flow (confirm → backup offer → confirm again → wipe → `resetTo(Intro)`) | §2.5.12 |
| Portal organizer check (silent login, hint/mismatch snackbars) | §2.5.11, §8.7 |
| First-run tour question + home tour start (20 × 500 ms anchor poll) | §2.4, §2.9.1 |
| Nickname empty/"Anon" → `IdentitySetup`, loading continues after the return | §2.5.10 |

## Code

- `HomeViewModel` (`HomeUiState`, `HomeAction`, `HomeEvent`/`HomeMessage`), `ui/HomeScreen.kt` (`HomeRoute`, `HomeScreen`).
- `data/HomeDataSource`: one seam over the data modules (no logic of its own); tests fake it.
- `tiles/TileLayoutEngine` (pure Dart algorithms), `tiles/TileOrderRepository` (`tile_order`,
  `tile_hidden`, the four `mig_*` migrations; this module owns those keys).
- `organizer/PortalOrganizerChecker` (state machine over `OrganizerPorts`).
- `reset/ResetAppUseCase` (`AppResetter`): NIP-46 → badges → rolling-QR session → profile caches →
  migration state → all prefs → identity (keys, NIP-46 key, vault, recovery copies) → portal token →
  mempool/currency/locale/tour in-memory state → widget meetup.

## Deviations

- The customize sheet locks the non-removable tiles too (spec 12 §14 risk 4): `event_chats` can no
  longer be hidden there and lost for good.
- The customize sheet, tile labels, device warning and "Netzwerk & Kurs" are localized
  (`native_home_*`, German text identical to Flutter).
- Trust score follows `BadgeRepository.badges` reactively (fixes the `refreshAfterScan` race, §14 risk 8).
- News/events counters and SatoshiDuell also reload on a manual refresh (§14 risk 7/9).
- Return to Home (any route, app resume) reloads user, favorites, live session, chat unread and my
  events (superset of Flutter's per-route reloads); the favorites also reload when the profile flow
  reports changed favorites.
- The meetup directory refresh runs in the background instead of blocking the badge load.
- Widget launch targets are handled by the app (`DeepLinks.forWidgetTarget`, ROUTES.md), not here.
- Badge sync `retryIfDirty` on resume is done by `:data:badgesync` itself.

## Dependencies

`:core:data`, `:data:identity`, `:data:settings`, `:data:meetups`, `:data:calendar`, `:data:badges`,
`:data:trust`, `:data:admin`, `:data:chat`, `:data:news`, `:data:community`, `:data:mempool`,
`:data:proofs`, `:data:issuance`, `:data:profile`, `:data:reputation`, `:data:migration`,
`:feature:settings`, `:feature:backup`, `activity-compose`.

## Tests

`tools/gradlew-locked :feature:home:testDebugUnitTest`: tile engine vectors and migrations
(spec 12 §10 items 1–3), date/format vectors (items 4, 6–8), the organizer state machine (item 11),
ViewModel flows with fakes, Robolectric UI tests of the dashboard, scan sheet and customize sheet.
