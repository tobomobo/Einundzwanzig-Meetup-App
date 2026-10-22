# :feature:wallet

The badge wallet UI of spec 03: wallet tab, collection grid, badge details with the share
image, and the OSM world map. Dart sources: `lib/screens/badge_wallet.dart`,
`badge_details.dart`, `badge_world_map_screen.dart`, `lib/widgets/badge_share_card.dart`.

```kotlin
implementation(project(":feature:wallet"))
// in the app's entryProvider:
walletEntries()
```

## Entries — `walletEntries()`

| NavKey | Route | Spec |
|---|---|---|
| `WalletTab` | `WalletRoute(showBack = false)` | 03 §2.1, 12 §2.4 |
| `BadgeWallet` | `WalletRoute(showBack = true)` (fresh UI state) | 03 §2.1 |
| `BadgeCollection(groupKey, query)` | `BadgeCollectionRoute(key)` | 03 §2.1.1 |
| `BadgeDetails(unionKey, collectionIndex)` | `BadgeDetailsRoute(key)` | 03 §2.2, §2.3 |
| `BadgeWorldMap` | `BadgeWorldMapRoute()` | 03 §2.4 |

Outgoing navigation: `MeetupCalendar()` (empty state), `ReputationQr` (share sheet).

## Screens

- **Wallet** (`WalletScreen` + `WalletViewModel`): large flexible app bar `BADGE WALLET (n)` with
  map, compact/normal, former collections, duplicate cleanup and share actions (visibility rules
  of §2.1); search (≥ 6 badges, cleared when the field disappears) and a "Nach Meetup" /
  "Nach Jahr" pair of `ToggleButton`s with `ButtonGroupDefaults` connected shapes, both pinned
  above the scrolling list as in Flutter, collection cards or collapsible year sections with normal/compact
  cards, `#n` numbering by object identity. Badge cards announce one merged description (name,
  date, `#n`, organizer / unverified marker) and clear their children's semantics. States: loading (store not read yet), empty
  (`walletNoBadges`, `emptyFindMeetup`), unreadable (`badgesUnreadableBody`, no actions), no
  results. Share sheet (text / QR / JSON with clipboard fallback), former collections sheet with
  merge confirmation, cleanup dialog. Sheet choices play the sheet's hide motion before they are
  reported. Cleanup and merge call
  `ReputationPublisher.publishInBackground`. Cleanup uses `BadgeRepository.update` (atomic,
  removes exactly the shown objects). Wallet tour (`GuideTour.Wallet`, `WalletTour`).
- **Collection** (`CollectionScreen` + `CollectionViewModel`): group rebuilt from the live
  collection with the key's query; 2-column grid of normal cards.
- **Details** (`BadgeDetailsScreen` + `BadgeDetailsViewModel`): hero with crest face / badge art,
  timestamp, details and proof sections, hash card (tap copies, `badgeHashCopied` 2 s), share
  button and app-bar share. The share image is `BadgeShareCard` captured with
  `rememberComposableCapturer` at 360 × 560 dp × 3 (dark theme forced) and shared as
  `einundzwanzig_badge.png`; errors show `repShareError`.
- **World map** (`BadgeWorldMapScreen` + `WorldMapViewModel`): `:core:map` `OsmMap` with
  `BitcoinBadgeMarker`s, stats bar, info card, fit once (padding 60, max zoom 12, single badge
  zoom 11), share as image via `MapSnapshotState.capture` (2.5×, 300 ms settle) with
  `mapShareText`. OSM attribution is shown.

`domain/`: `WalletLibrary` (filter, grouping, duplicates, numbering, key resolution),
`BadgeFormat` (all formatters of §8.3/§8.4), `ReputationShare` (share text §8.7 and the v4 JSON
export via `BadgeProofs.exportBadgesForReputation`).

Crests: `WalletCrestSource` adapts `:data:meetups` `PortalLogoRegistry` to core:ui's
`MeetupCrestSource` and is bound as the app's `MeetupCrestSource` singleton (`WalletCrestModule`,
`@Binds`). Each wallet route falls back to it through `ProvideWalletCrests` (Hilt
`WalletCrestEntryPoint`) unless the app shell already provides `LocalMeetupCrestSource`.

App wiring (owner of `:app`):

```kotlin
// in the entryProvider
walletEntries()
// once around the NavDisplay (inject MeetupCrestSource, or use the entry point)
CompositionLocalProvider(LocalMeetupCrestSource provides meetupCrestSource) { NavDisplay(...) }
```

## Dependencies

`:data:badges` (repository, model, proofs, `DartString`), `:data:reputation`
(`publishInBackground`), `:data:identity` (`UserProfileRepository` for npub/nickname/handles),
`:data:meetups` (crest registry), `:core:map`.

## Notes / deviations

- The event-badge tour (03 §2.6) lives with its consumer in `:feature:events`
  (`eventBadgeTourSteps`), because features must not depend on each other.
- The wallet tour starts the first time the wallet is visible **with badges** (600 ms after the
  first frame, only if no other tour is active), so an empty wallet does not burn the tour.
  Navigation 3 drops a covered entry's content, so the tour is **paused** while the wallet is
  covered (collection, details, map), resumed on return, and finished only when the entry is
  popped (`WalletViewModel.onCleared`). A wallet tab left for another tab keeps the tour paused.
- Search uses a `TextFieldState` owned by the field (no StateFlow round trip for the IME).
- Badge details: a share without a chooser target shows `repShareError` with the native,
  localized detail `native_wallet_no_share_target` (Flutter has no such case);
  the world map stays silent as in Flutter and only logs.
- `Normal`/`Kompakt` tooltips are localized native strings (`native_wallet_view_*`).
- Collection-card "vor 0 Jahren" quirk (days 360–364) is kept for parity.

## Tests

`tools/gradlew-locked :feature:wallet:testDebugUnitTest`: domain vectors (block,
ago labels, share text, JSON checksum `745487f0`, grouping, identity duplicates), ViewModels with
fakes (wallet, collection, details, world map, crest source), Robolectric Compose UI tests of
every screen, sheet and dialog, and route tests (`WalletRouteTest`: tour start/pause/resume/finish,
share-text clipboard fallback, outgoing NavKeys (collection with query, details index of a
duplicate, map, QR, calendar), details share error and hash copy).
