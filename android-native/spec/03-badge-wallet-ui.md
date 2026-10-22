# 03 — Badge Wallet UI (Wallet, Collection, Details, Share Card, World Map, Crest, Event-Badge Tour)

Source files read in full:

- `lib/screens/badge_wallet.dart` (1457 lines): `BadgeWalletScreen`, `_CollectionScreen`, `_BadgeCardTile`, `BadgeArtPainter`
- `lib/screens/badge_details.dart` (529): `BadgeDetailsScreen`
- `lib/widgets/badge_share_card.dart` (292): `BadgeShareCard`, `kShareCardSize`, `kAppStoreUrl`
- `lib/screens/badge_world_map_screen.dart` (280): `BadgeWorldMapScreen`
- `lib/widgets/meetup_crest_watermark.dart` (182): `MeetupCrestWatermark`, `MeetupCrestFace`
- `lib/tours/event_badge_tour.dart` (87): `EventBadgeTour`

Helpers I followed and read: `lib/models/badge.dart` (all 1558 lines; owned by the badge model/storage domain, summarized here only where the UI depends on it), `lib/tours/more_tours.dart` (`WalletTour`), `lib/services/guide_service.dart`, `lib/mixins/guide_service_host.dart`, `lib/widgets/guide_overlay.dart` (target wait/skip logic), `lib/services/meetup_calendar_service.dart`, `lib/services/portal_api_service.dart` (`_get`, `getAllMeetupEvents`), `lib/widgets/empty_state.dart`, `lib/widgets/pressable.dart`, `lib/utils/share_origin.dart`, `lib/theme.dart`, `lib/models/user.dart` (`UserProfile.load`), `lib/services/nostr_service.dart` (`shortenNpub`), `lib/services/reputation_publisher.dart` (`publishInBackground`), `badge-verifier.html` (consumes the JSON export), and the entry points in `app_shell.dart`, `meetup_verification.dart`, `radar.dart`, `event_calendar_screen.dart`.

I ran the vectors in section 10 with the local Dart SDK 3.13.5 (pure `dart:core`/`dart:convert`) and Python `hashlib`.

---

## 1. Overview and user-visible features

The Badge Wallet is the user's local collection of attendance badges. A badge is a `MeetupBadge`: an organizer's Schnorr signature, optionally bound by the collector's claim signature. The UI groups and searches the collection and lets the user drill into a single badge. From there the badge can be shared as a fixed-size PNG card. Other actions: show all located badges on an OpenStreetMap world map (shareable as an image), share the reputation as text, as a QR screen (another domain) or as a JSON export, remove duplicate scans, and merge archived "former collections" back in. Every card gets a deterministic generative artwork seeded from `"<meetupName>:<blockHeight>"`. A soft-masked meetup crest from the portal (or the badge's own cover image for event badges) is drawn on top.

Feature checklist (all must exist in the native app):

Wallet (bottom tab "Badges" and pushed route)
1. AppBar title `BADGE WALLET` (hard-coded). When the collection is non-empty it gets the suffix ` (N)`, where N = all stored badges including organizer markers and duplicates.
2. **World map button** (`public_rounded`, tooltip `mapButton`). Shown only when at least 1 badge exists. Opens the world map with all badges.
3. **Compact/normal view toggle** (`grid_view` when compact, otherwise `view_comfy`). Tooltip is hard-coded `'Normal'` when compact, otherwise `'Kompakt'`. Visible only when there are **more than 6** badges (at least 7). It only affects the year-grouped grid (see 2.1).
4. **Former collections (archive) button** (`history_rounded`, tooltip `walletFormerTitle`). Always visible, even when the wallet is empty.
5. **Duplicate cleanup button** (`cleaning_services_rounded`, tooltip `walletCleanupTitle`). Visible only when the union rule finds duplicates.
6. **Share reputation button** (`share`, tooltip `walletShareTitle`). Shown when at least 1 badge exists. Opens a bottom sheet with three options:
   - Share as text: hard-coded German summary. Clipboard fallback on error.
   - Show QR code: pushes `ReputationQRScreen` (another domain).
   - Export as JSON: `exportBadgesForReputation`, version "4.0". Clipboard fallback on error.
   - Cancel.
7. **Search field**, shown only when there are **at least 6** badges. It is a case-insensitive substring match on `meetupName`, with a clear (x) button while the query is non-empty.
8. **Grouping chips**: "By meetup" (default) and "By year". Not persisted.
9. Meetup grouping: one **collection card** per meetup with:
   - crest
   - name and `×N`
   - "since Mon YYYY · last <ago>"
   - one dot per badge (only when N is at most 24)
   - chevron

   Tapping opens the **Collection screen**, a 2-column grid of the meetup's badges, newest first.
10. Year grouping: collapsible **sections** (year, newest first). Each section has a header with a count chip and an expand/collapse icon. The badge grid has 2 columns in normal view and 3 in compact view.
11. Organizer marker badges (`isOrganizer`) always form their own group/section labelled `walletOrganizerSection` ("Von dir erstellt"), which is always placed **last**.
12. Badge card (normal): generative art, crest watermark, bottom gradient, verified icon, `#<global index + 1>` pill, name, date and block. The pill also shows a shield icon for organizer markers, or a `location_off` icon when `presenceVerified == false` (not for organizers).
13. Compact card: art, fainter crest, verified icon, `#n`, short name (the text before the first comma, uppercased) and date.
14. Tapping a card opens **Badge Details**.
15. **Empty state** (no badges): pulsing icon, `walletNoBadges` / `walletNoBadgesSub`, and a button `emptyFindMeetup` that pushes the meetup calendar screen.
16. **No search results** state: `search_off` icon plus `walletNoResults`.
17. **Wallet tour** (first open): four spotlight steps (badge list, map, view toggle, share). It is ended when the screen is disposed.
18. Archive sheet: each entry shows "N Badges" or "unreadable", the date and time, a localized reason and the shortened npub, plus a "Merge" button that is disabled when the entry is unreadable. Merging asks for confirmation, then shows a success snackbar with the number of newly added badges or an error snackbar, and republishes the reputation.
19. Cleanup dialog: lists affected meetups as "Name (Nx)". On confirm it removes exactly those objects and shows a "Removed N duplicates" snackbar. If saving fails it shows the error snackbar `badgesUnreadableShort`. It also republishes the reputation.

Badge Details
20. The AppBar title is the meetup name in uppercase, plus a share icon (tooltip `badgeShare`).
21. Hero card:
    - 168×168 crest face. The fallback is the same generative art as the wallet card.
    - A verified plaque at the bottom right.
    - Name, `₿ Block <grouped>`.
    - Either "PROOF OF ATTENDANCE" plus the proof description, or for organizer markers "ORGANIZER RECORD" plus the organizer description.
22. Section **Timestamp**: meetup date, block at scan, and the scan time (only when `claimTimestamp > 0`).
23. Section **Badge details**: meetup name, transmission (Rolling QR / NFC tag / raw value) and meetup event ID.
24. Section **Cryptographic proof**:
    - signature type (Schnorr v2 in green, HMAC Legacy v1 in orange, none in grey)
    - organizer npub, shortened
    - Nostr event ID, shortened (only when `sigId` is non-empty)
    - claim binding (bound in green, not bound in red)
    - "claimed later" note (only when `isRetroactive`)
    - "presence unverified" note (only when `!presenceVerified`)
25. **Verification hash card** (16 hex characters). Tapping copies the hash to the clipboard and shows the snackbar `badgeHashCopied` for 2 s.
26. Buttons: "SHARE BADGE" (primary) and "CLOSE" (pops the screen).
27. **Share badge as image**: renders the off-screen 360×560 share card at 3× (a 1080×1680 PNG) and shares it with the subject = meetup name and the text `"<name> · dd.MM.yyyy\n<badgeShareTagline>"`. Errors show the snackbar `repShareError(e)`.

Share card (image only, never shown on screen)
28. Fixed 360×560 logical layout: art, crest (opacity 0.26), gradient, the chip "EVENT-BADGE"/"MEETUP-BADGE", a green verified icon when `isNostrSigned`, name and date, then a footer with block, scan time, checksum, promo text, the app store URL and branding.

World map
29. The title is `mapTitle`. Only badges with `lat != 0 || lng != 0` are shown.
30. Empty state when no badge has a location.
31. OSM raster map. Rotation is disabled. The camera fits all pins once.
32. Stats overlay with the number of located badges ("Meetups") and the number of distinct names ("Cities").
33. Tapping a pin shows an info card (name and date) that can be closed.
34. "Share as image" button plus an AppBar share icon. Both are disabled while sharing (the icon becomes a spinner). The capture covers the map and the stats overlay at 2.5×. The share text is `mapShareText(count)`. Errors are silent.

Event-badge tour (lives in the event editor of the calendar domain)
35. A spotlight tour in the "new event" editor. It has 2 steps, plus 4 more when the user may issue event badges. The step texts come from the `guideEv*` keys.

Haptics: none in this domain. The tab-switch haptic belongs to the app shell.

---

## 2. Screens and UX flows

Theme colors (from `lib/theme.dart`; keep these exact hex values in the Material 3 color scheme or as custom tokens):

| Token | Hex | Use |
|---|---|---|
| cDark | `#0C0C0E` | scaffold background |
| cCard | `#131315` | cards, sheets, dialogs |
| cSurface | `#0F0F11` | neutral snackbars |
| cOrange | `#F7931A` | accent |
| cOrangeLight | `#FFB347` | small date/₿ icons on cards |
| cCyan (actually warm gold) | `#D4A017` | Details section color, share-text icon |
| cPurple | `#9B45E8` | JSON icon, hash card |
| cText | `#FFFFFF` | |
| cTextSecondary | `#9CA0A8` | |
| cTextTertiary | `#868A94` | |
| cBorder | `#1C1C22` | dividers |
| cTileBorder | `#202028` | thin borders |
| cGreen | `#22C55E` | success |
| cRed | `#E53935` | error |
| kTileRadius | 10 dp | |

The app font is Rajdhani (Google Fonts). Places that use `fontFamily: 'monospace'` use the **platform monospace**, not Inconsolata: in Compose that is `FontFamily.Monospace`. Snackbars are floating with a cCard background unless overridden, and their text is white, 13 sp.

### 2.1 BadgeWalletScreen

**Purpose:** browse and manage the local badge collection.

**Entry points**
- `AppShell` bottom tab index 1. The label is `navWalletTab` ("Badges") and the icons are `style_rounded`/`style_outlined`. The tab is hosted in an `IndexedStack`, so its state (query, grouping, compact, collapsed) lives as long as the shell does.
- `MeetupVerificationScreen` does `pushReplacement(BadgeWalletScreen())` in three cases: after a badge was saved, after a duplicate scan was rejected (snackbar `verifyBadgeDuplicate`), and from the "already collected" button `verifyOpenWallet`. Each of these creates a **new** wallet instance with default UI state.
- No constructor arguments. Data comes from the global list `myBadges` (native: `BadgeRepository.badges: StateFlow<List<MeetupBadge>>`).

**Data freshness:** Flutter reads the global list during build, so the tab only refreshes when the shell rebuilds (tab tap) or after the wallet's own `setState`. Native should observe the repository flow. That is strictly better and has no compatibility impact.

**Tour hook:**
- `initState`: wait 600 ms. If the screen is still mounted and `guide_completed_wallet` is not true, call `GuideService.startTour(GuideTour.wallet, WalletTour.steps())`.
- `dispose`: if the active tour is `wallet`, call `finishTourNow()`. This marks it completed and the write is not awaited.

Flutter quirk: because the tab lives in an `IndexedStack`, the wallet's `initState` runs when the **shell** is first built, not when the user opens the tab. On a fresh install the wallet is empty, so none of the targets exist. The steps time out and are skipped (first step after 800 ms, the others after 6 s each), and the tour is marked completed silently unless the home tour replaced it first. **Recommendation:** start the wallet tour the first time the wallet is actually visible (tab selected or route shown), 600 ms after first composition, and only when no other tour is active. The persisted key is the same.

**WalletTour steps** (`lib/tours/more_tours.dart`):

| # | Target | titleKey / bodyKey | Options |
|---|---|---|---|
| 1 | firstBadgeKey: the first collection card (meetup mode) or the first section column (year mode) | `guideWalletBadgesTitle` / `guideWalletBadgesBody` | waitTimeout 800 ms |
| 2 | mapKey: the map IconButton | `guideWalletMapTitle` / `guideWalletMapBody` | scrollIntoView false |
| 3 | viewKey: the compact toggle (exists only with more than 6 badges) | `guideWalletViewTitle` / `guideWalletViewBody` | scrollIntoView false |
| 4 | shareKey: the share IconButton | `guideWalletShareQrTitle` / `guideWalletShareQrBody` | scrollIntoView false |

The `GuideStep` defaults are: blockOutside true, interactiveTarget true, waitForTarget false, waitTimeout 6 s, holePadding 10, holeRadius 14, scrollIntoView true. The overlay skips a step whose target cannot be measured once the timeout has elapsed. The timer does not run while the target's route is covered.

**Layout, top to bottom**

1. **AppBar** (transparent, theme title style Rajdhani 20 w700). The title is `"BADGE WALLET"`, followed by `" (${n})"` when n > 0. Actions, in this order:
   1. Map: when n ≥ 1. Icon `public_rounded`, tooltip `mapButton`. Pushes `BadgeWorldMapScreen(badges = all badges)`.
   2. View toggle: when n > 6. Toggles `compact`.
   3. History: always shown. Opens the former-collections flow (2.1.3).
   4. Cleanup: when `duplicates(all).isNotEmpty()`. Opens the cleanup dialog (2.1.4).
   5. Share: when n ≥ 1. Opens the share sheet (2.1.2).
2. **Body when n == 0:** `EmptyState`:
   - icon `collections_bookmark_outlined`, accent cOrange
   - pulsing circle 96 dp: background accent at 10 % alpha, 1.5 dp border at 30 % alpha, 40 dp icon; scale 1.0 to 1.06 over 2400 ms, repeating in reverse; static when the system has "remove animations" on
   - title `walletNoBadges` (19 sp w800)
   - subtitle `walletNoBadgesSub` (14 sp, cTextSecondary)
   - an orange button 48 dp high, radius 12, black label `emptyFindMeetup` (w800 14 sp), pressed scale 0.97, 100 ms in / 80 ms out. It pushes `CalendarScreen()` with no arguments.

   Note: when the badge file is *unreadable*, `myBadges` is also empty and Flutter shows this same empty state. The home screen shows the unreadable warning instead. Native may add a dedicated unreadable state using `badgesUnreadableBody`, but must never offer anything destructive there.
3. **Body when n ≥ 1:** a column with the library controls and then the grouped list.

**Library controls** (padding 12,10,12,6)
- If n ≥ 6, a search TextField:
  - dense, filled with cCard, radius 12, border cTileBorder 0.5 dp, focused border cOrange 1 dp, content padding vertical 10 / horizontal 12
  - hint `walletSearchHint`
  - prefix `search_rounded` in orange, 20 dp
  - suffix: when the raw query is non-empty, an IconButton `clear_rounded` (cTextTertiary, 18 dp) that clears the field and sets the query to `""`
  - `onChanged` updates the query

  It is followed by a 10 dp gap.
- A row of two chips with 8 dp spacing: `walletGroupMeetup` (icon `place_rounded`), active when `groupByMeetup`, and `walletGroupYear` (icon `event_rounded`), active when `!groupByMeetup`.
  - Chip: padding h12 v7, radius 20.
  - Active: background orange at 16 % alpha, border orange at 60 % alpha 1 dp, icon and text orange, w700.
  - Inactive: background cCard, border cTileBorder 0.5 dp, icon cTextTertiary, text cTextSecondary, w500.
  - Icon 15 dp, text 12.5 sp.

**Filtering and grouping:** see section 8.1. If the resulting groups are empty, show the centred column (padding 32): icon `search_off_rounded` (cTextTertiary, 40), 12 dp gap, `walletNoResults` (cTextTertiary, 14 sp).

**Meetup mode (groupByMeetup = true):** `LazyColumn`, padding 12,8,12,24. Each group renders one **collection card**. The first card carries the tour target `firstBadgeKey`.

Collection card (bottom padding 10):
- Container: padding 14, cCard, radius 10, border 0.5 dp. The border is cTileBorder for the organizer group, otherwise orange at 30 % alpha.
- Row:
  - A 52×52 box clipped to radius 10, containing `MeetupCrestWatermark(meetupName = groupKey, imageUrl = cover.coverUrl or null, opacity = 1.0, widthFactor = 1.0)`. `cover` is the first badge in the group (newest first) with a non-empty `coverUrl`, otherwise the first badge. If there is no crest the box stays empty and transparent.
  - 13 dp gap.
  - Expanded column:
    - Row: the title, then 8 dp, then `"×N"` (orange, 14 sp, w900). The title is `walletOrganizerSection.uppercase()` (cTextSecondary) for the organizer group, otherwise `key.uppercase()` (cText). Title style: 14.5 sp, w800, letterSpacing 0.3, 1 line, ellipsis.
    - 4 dp, then the subtitle (cTextTertiary, 11.5 sp, 1 line, ellipsis); see 8.4.
    - 8 dp, then the count dots: when N > 24, nothing. Otherwise N circles of 5×5 dp in orange at 65 % alpha, spacing 3, run spacing 3 (FlowRow).
  - 6 dp gap, then `chevron_right_rounded` (cTextTertiary, 20).
- Tap pushes the **Collection screen** with:
  - title = `walletOrganizerSection` for the organizer group, otherwise the key (`'?'` if empty)
  - badges = the group sorted ascending by date and then reversed, so newest first
  - allBadges = the full collection, used for numbering

**Year mode (groupByMeetup = false):** `LazyColumn`, padding 12,4,12,24. Each group is a section; the first section's column carries `firstBadgeKey`.
- Section header: tapping toggles the key in the in-memory `collapsed` set. Padding 2,14,2,8. Row:
  - Icon: `shield_outlined` (cTextSecondary) for the organizer group, otherwise `event_rounded` (orange); size 15.
  - 7 dp gap.
  - Label: `walletOrganizerSection.uppercase()` or the year string. 11.5 sp, letterSpacing 1.2, w700, cTextSecondary or orange, 1 line.
  - 8 dp gap.
  - Count chip: padding h7 v2, orange at 14 % alpha, radius 8, text `"$count"` in orange 11 sp w800.
  - 4 dp gap.
  - `expand_more_rounded` when collapsed, `expand_less_rounded` when expanded (cTextTertiary, 20).
- When not collapsed, a non-scrolling grid follows:
  - normal: 2 columns, aspect ratio (width/height) 0.80, spacing 12
  - compact: 3 columns, aspect ratio 0.75, spacing 8

  Each cell is a normal or compact card. The card number is the index of this **object** in the full list, plus 1.

**Normal badge card** (a static builder shared with the Collection screen)
- Box with radius 16, background cCard, 1 dp border in orange at 40 % alpha, shadow orange at 12 % alpha (blur 12, offset 0,4). Clipped.
- Layers, from bottom to top:
  1. `BadgeArt(seed = "${meetupName}:${blockHeight}")` (8.6).
  2. `MeetupCrestWatermark(meetupName, imageUrl = coverUrl or null)` with the default opacity 0.20 and widthFactor 1.05.
  3. Vertical gradient: stops 0 / 0.35 / 1.0, colors transparent / black at 15 % / black at 80 %.
  4. Content, padding 12, a Column:
     - Row, SpaceBetween:
       - Left: a circle (padding 6, black at 50 %, border orange at 50 %) containing `verified` in orange, 20 dp. It is shown for **every** badge, signed or not.
       - Right: a pill (padding h8 v3, black at 60 %, radius 10) containing:
         - for organizer badges: `shield_outlined` (cTextSecondary, 11) and 4 dp
         - for `!presenceVerified && !isOrganizer`: `location_off_rounded` (cTextTertiary, 11) and 4 dp
         - `"#${index+1}"` in orange, 11 sp, bold
     - Spacer.
     - Name: `meetupName.uppercase()`, white, size 11 sp if `meetupName.length > 18` (UTF-16 length of the original string), otherwise 14 sp. w900, letterSpacing 0.3, lineHeight 1.2. Shadows: (1,1) blur 6 black and (0,0) blur 12 black at 54 %. Max 2 lines, ellipsis.
     - 6 dp.
     - Row: `calendar_today` (10, cOrangeLight), 4 dp, `"d.M.yyyy"` (unpadded; white at 70 %, 11 sp, w600).
     - 2 dp.
     - Row: `"₿"` (cOrangeLight, 10 sp, bold), 4 dp, `"Block ${formatBlockWallet(blockHeight)}"` (white at 50 %, 10 sp, w500, monospace, ellipsis).
- Tap pushes `BadgeDetailsScreen(badge)`.

**Compact card**
- Radius 12, cCard, border orange at 30 % alpha with width 0.8, no shadow.
- Layers: art (same seed); watermark with opacity **0.17** and widthFactor **0.95**; gradient with stops 0 / 0.5 / 1.0 and colors transparent / black at 20 % / black at 80 %.
- Content, padding 8:
  - Row SpaceBetween: `verified` (orange, 16) and `"#n"` (orange at 80 %, 9 sp, bold).
  - Spacer.
  - shortName = `meetupName.split(',').first().trim().uppercase()`. Size 9 sp if `shortName.length > 12`, otherwise 11 sp. w900, height 1.2, shadow (1,1) blur 4 black, max 2 lines.
  - 3 dp, then `"d.M.yyyy"` in white at 54 %, 9 sp.
- No organizer or presence markers on compact cards.
- Tap opens the details.

#### 2.1.1 Collection screen (`_CollectionScreen`, private route)
- Scaffold background cDark. The AppBar background is cDark with elevation 0. The title is a column:
  - `title.uppercase()` (cText, 15 sp, w800, 1 line)
  - `walletCollectionCount(badges.size)` (cTextTertiary, 10.5 sp)
- A grid with 2 columns, aspect ratio 0.80, spacing 12, padding 12,12,12,24. Every cell is a **normal** card, regardless of the compact setting. The index is `allBadges.indexOf(badge)` by identity; `-1` would render as `#0`.
- No actions. The back button returns to the wallet.

#### 2.1.2 Share sheet (`_showShareOptions`)
- Modal bottom sheet, background cCard.
- Flutter applies padding **100 on all sides** to the content, which looks like a bug (it squeezes the tiles). Native should use normal sheet padding (16–24).
- Column:
  - Title `walletShareReputation` (orange, 16 sp, bold), then 20 dp.
  - ListTile with `share` icon in cCyan: title `walletShareText` (white), subtitle `walletShareTextSub` (cTextSecondary, 12 sp). Dismisses the sheet, then calls `shareAllBadgesText()`.
  - ListTile with `qr_code_2` icon in orange: `walletShowQr` / `walletShowQrSub`. Dismisses, then pushes `ReputationQRScreen()` (no arguments; reputation/QR domain).
  - ListTile with `code` icon in cPurple: `walletExportJson` / `walletExportJsonSub`. Dismisses, then calls `shareReputationJson()`.
  - 10 dp, then a TextButton `cancel` (grey) that dismisses.

`shareAllBadgesText()`:
1. Return if the wallet is empty.
2. Load the profile via `UserProfile.load()`. npub precedence (highest first): **external signer npub** (`SigningService.npub()`, only when `SigningService.isExternalSigner` and the value is non-null and non-empty) > **SecureKeyStore npub** (only when `SecureKeyStore.hasKey()` and `getNpub()` is non-null and non-empty) > prefs `nostr` (default `""`). Implementation order: start with prefs `nostr`, override with the key-store npub, then override with the external-signer npub. In the mixed state (external signer mode plus a leftover local key) the `Nostr: <npub>` line and the export's `identity.nostr_npub` therefore carry the external signer npub.
3. Build the exact text from 8.7.
4. Send `ACTION_SEND` text/plain with `EXTRA_TEXT` and `EXTRA_SUBJECT = "Meine Einundzwanzig Meetup Reputation"`.
5. On an exception, copy the text to the clipboard and show a snackbar `walletReputationCopied` with an **orange** background.

`shareReputationJson()`:
1. Return if empty.
2. Load the profile.
3. Build `json = ReputationExport.export(all, npub, nickname, telegram, twitter)` (8.8).
4. Share it as text/plain with `EXTRA_SUBJECT = "Einundzwanzig Reputation (v4, signiert)"`.
5. On an exception, copy to the clipboard and show a snackbar `walletJsonCopied` with an orange background.

#### 2.1.3 Former collections (archive) flow
1. Tap the history button and run `formers = BadgeRepository.formerCollections()`. This is asynchronous and decrypts every blob; Flutter shows no spinner. Native should show a small progress indicator or keep the call fast.
2. If the list is empty, show a snackbar `walletFormerNone` (background cSurface, floating) and stop.
3. Otherwise open a modal bottom sheet: cCard background, top radius 16, SafeArea, shrink-wrapped list, padding 16,16,16,8.
   - Header row: `history_rounded` (orange, 20), 10 dp, `walletFormerTitle` (cText, 16 sp, w700).
   - 8 dp, then the hint `walletFormerHint` (cTextTertiary, 11.5 sp, line height 1.35), then 8 dp.
   - For each entry, **newest first**:
     - title: `walletFormerEntry(badges.size)` when readable (`badges != null`), otherwise `walletFormerUnreadable`. 13.5 sp, w600, cText or cTextTertiary.
     - subtitle, joined by `" · "`:
       - the date and time formatted `dd.MM.yyyy HH:mm` in **device local time**
       - the reason label: `replaced` maps to `walletFormerReasonReplaced`, `rotated` to `walletFormerReasonRotated`, `cleared` to `walletFormerReasonCleared`; any other value is shown raw
       - only when the npub is non-empty: `shortenNpub(npub)` (8.3)
       - style cTextSecondary, 11.5 sp
     - trailing: a TextButton `walletFormerMerge` (orange w700, or cTextTertiary when disabled), enabled only when readable. It closes the sheet and opens the confirmation.
4. Confirmation AlertDialog:
   - background cCard
   - title `walletFormerMergeTitle` (cText, w700)
   - body `walletFormerMergeBody` (cTextSecondary, line height 1.35)
   - actions: `dialogCancelMixed` (cTextTertiary) returns false; `walletFormerMerge` (orange, w700) returns true.
5. If confirmed, run the merge:
   1. `before = all.map(unionKey).toSet()`
   2. `ok = BadgeRepository.mergeFormer(entry.index)` (storage domain; returns false when the entry is unreadable, the current file is unreadable, or saving fails)
   3. If `!ok`: snackbar `walletFormerMergeFailed`, background **cRed**, floating. Stop.
   4. `loaded = BadgeRepository.loadBadges()`, then `added = loaded.count { unionKey(it) !in before }`.
   5. Publish `loaded` as the new global collection.
   6. Log diag `"Frühere Sammlung zusammengeführt: $added neu, gesamt ${loaded.size}."` (tag `Wallet`).
   7. `ReputationPublisher.publishInBackground(loaded)`.
   8. Snackbar `walletFormerMerged(added)`, background **cGreen**, floating.

   The archive entry remains after merging.

#### 2.1.4 Duplicate cleanup flow
1. `dups = duplicates(all)` (8.2). If it is empty, show a snackbar `walletCleanupNone` (cSurface, floating). This is only reachable through a race, because the button is hidden when there are no duplicates.
2. Count per meetup name: `name = meetupName.trim().ifEmpty { "?" }`. Keep the insertion order of the first occurrence in `dups`.
3. AlertDialog (cCard, radius 16):
   - title row: `cleaning_services_rounded` (orange, 20), 10 dp, `walletCleanupTitle` (cText, 16 sp, w700)
   - content:
     - `walletCleanupBody(dups.size)` (cTextSecondary, 13 sp, line height 1.4)
     - 12 dp
     - one row per name: `remove_circle_outline_rounded` (cRed, 14), 8 dp, `"$name (${count}x)"` (cText, 12.5 sp, 1 line, ellipsis), 4 dp bottom padding
     - 12 dp
     - `walletCleanupHint` (cTextTertiary, 11.5 sp)
   - actions: `dialogCancel` (cTextSecondary) and `walletCleanupConfirm` (orange, w700)
4. Confirm closes the dialog and runs remove(dups):
   1. `kept = all.filter { b -> dups.none { it === b } }` (**identity**), `removed = all.size - kept.size`.
   2. If `!BadgeRepository.saveBadges(kept)`:
      - log warn `"Doppelte Badges nicht entfernt, Speichern fehlgeschlagen."`
      - snackbar `badgesUnreadableShort` (cRed, floating)
      - nothing in memory changes
   3. Otherwise:
      - the global collection becomes `kept`
      - diag log `"$removed doppelte Badge(s) entfernt. Neu: ${kept.size}."`
      - `ReputationPublisher.publishInBackground(kept)`
      - snackbar `walletCleanupDone(removed)` (cGreen, floating)

Why this is safe (from the code comments): a duplicate has the same `meetupEventId`, and the network attendance confirmation (kind 30079, d-tag = `attendanceKey(meetupEventId, signerNpub, isEvent)` = `<meetupEventId>@<last 12 chars of signer npub>`, or `ev|<meetupEventId>` for event badges, plain `meetupEventId` only for the legacy empty-signer case; spec 05 §6.4/§7.3, coattendance_service.dart:184/279) is a replaceable event that exists only once per meetup and organizer anyway. Removing a duplicate therefore changes only the local list and the counters/proof hash in the replaceable reputation event.

### 2.2 BadgeDetailsScreen

**Entry:** a tap on a wallet card (normal or compact) or a Collection-screen card, and `RadarScreen._openBadgeDetails(badge)` (radar domain). Argument: `badge: MeetupBadge` (the object itself, not an ID).

**Layout**
- Scaffold background cDark.
- AppBar title `meetupName.uppercase()`. Action: a `share` IconButton (tooltip `badgeShare`) that calls `shareBadgeImage()`.
- An off-screen `BadgeShareCard(badge)` (section 2.3) is mounted so it can be rasterized. Flutter places it at `left = -2 × 360`. Native should render it off-screen to a bitmap on demand (section 11).
- A scrollable column, padding 20, children stretched:
  1. **Hero card**:
     - Container: cCard, 1.5 dp border in orange at 50 %, shadow orange at 15 % (blur 30, offset 0,8), radius 20, clipped. Inner padding vertical 36, horizontal 24. Column:
     - A 168×168 box clipped to radius 18 containing `MeetupCrestFace(meetupName, imageUrl = coverUrl or null, fallback = BadgeArt(seed "${meetupName}:${blockHeight}") filling the box)`.
     - A plaque positioned at right −6 / bottom −6: a circle in cCard with padding 5, containing `verified_rounded` (30, orange). It is always shown.
     - 20 dp, then the name uppercase (cText, 22 sp, w900, letterSpacing 1.5, centred).
     - 8 dp, then a centred Row: `"₿"` (orange, 14 sp, bold), 6 dp, `"Block ${formatBlockDetails(blockHeight)}"` (orange, 15 sp, w700, monospace, letterSpacing 1.0). When the block height is 0 this reads "Block unbekannt".
     - 16 dp, Divider (cBorder), 12 dp.
     - Title: `badgeOrganizerTitle` (cTextSecondary) if `isOrganizer`, otherwise `badgeProofOfAttendance` (orange). Bold, letterSpacing 1.5.
     - 6 dp, then `badgeOrganizerDesc` or `badgeProofDesc` (centred, cTextSecondary, 12 sp).
  2. 20 dp.
  3. **Section card** "Timestamp": title `badgeTimestamp`, icon `access_time_rounded`, color orange. Rows:
     - `badgeMeetupDate`: `dd.MM.yyyy` from the badge date's own fields
     - `badgeBlockAtScan`: `blockHeight > 0 ? formatBlockDetails(h) : badgeUnknown`, monospace, orange if > 0 else cTextTertiary
     - only when `claimTimestamp > 0`, `badgeScanTime`: `formatScanTime(claimTimestamp)` = `"dd.MM.yyyy  HH:mm"` in **device local time**, with **two** spaces between date and time
  4. 12 dp, then the section "Badge details": `badgeDetailsTitle`, icon `badge_rounded`, color cCyan (gold). Rows:
     - `badgeMeetup` = meetupName
     - `badgeTransmission` = `rolling_qr` maps to `badgeRollingQr`, `nfc` to `badgeNfcTag`, anything else is shown raw (for example `organizer`)
     - `badgeMeetupId` = meetupEventId, or `"—"` (U+2014) when empty
  5. 12 dp, then the section "Cryptographic proof": `badgeProofTitle`, icon `security_rounded`, color cGreen. Rows:
     - `badgeSignatureType`: `sigVersion == 2` gives `badgeSchnorrSig` (cGreen); `== 1` gives the hard-coded `"HMAC (Legacy v1)"` (cOrange); otherwise `badgeNoSignature` (cTextTertiary). This uses `sigVersion` only and does not check the format.
     - `badgeOrganizerNpub`: `shortNpubDetails(signerNpub)`, monospace (8.3).
     - When `sigId` is non-empty, the hard-coded label `"Nostr Event-ID"`: `sigId.take(12) + "…" + sigId.takeLast(8)`, monospace. (Flutter throws a RangeError if `sigId.length < 12`. Native: show `sigId` unchanged when it has fewer than 20 characters.)
     - `badgeClaimBinding`: `isClaimed ? badgeBound (cGreen) : badgeNotBound (cRed)`, where `isClaimed = claimSig.isNotEmpty && claimPubkey.isNotEmpty`.
     - When `isRetroactive`: `badgeNote` = `badgeClaimedLater` (cOrange).
     - When `!presenceVerified`: `badgeUnverified` = `badgeUnverifiedInfo` (cTextTertiary).
  6. 12 dp, then the **Hash card**:
     - Container: padding 14, cCard, radius 14, border cBorder.
     - Row: `fingerprint` (14, cPurple), 8 dp, `badgeVerificationHash` (cPurple, 11 sp, w800, letterSpacing 1.2), spacer, `copy` (13, cTextTertiary).
     - 10 dp, then the hash text (cTextSecondary, 10 sp, monospace, line height 1.5). `hash = getVerificationHash()`, 16 lowercase hex characters (7.1).
     - Tapping the card copies the hash to the clipboard and shows a snackbar `badgeHashCopied` (background cCard, 2 s).
  7. 24 dp, then an ElevatedButton with icon `share` and label `badgeShareCaps`: background orange, foreground black, vertical padding 16, w800, letterSpacing 0.5. It calls `shareBadgeImage()`.
  8. 12 dp, then a TextButton with icon `close` and label `badgeClose` (cTextSecondary). It pops the screen.

Section card template: radius 14, cCard, border `color` at 25 % alpha. The header has padding 14,12,14,8 and contains the icon (14), 8 dp and `title.uppercase()` (color, 11 sp, w800, letterSpacing 1.2). It is followed by a 1 dp Divider (cBorder) and the rows. Row: padding h14 v10. The label column is 130 dp wide (cTextSecondary, 12 sp, w500). The value fills the rest (`valueColor ?? cText`, 12 sp, w600, optionally monospace, wraps).

**shareBadgeImage()**
1. Wait one frame so the card is laid out and drawn. Native: render the card off-screen; see 11.
2. Rasterize the 360×560 dp card at scale 3.0, giving a 1080×1680 px PNG. (The code comments mention 360×480 and 1080×1440, but the constant is `Size(360, 560)`.)
3. If capture yields nothing, return silently.
4. Share one file `einundzwanzig_badge.png` (mime `image/png`) with:
   - subject = `meetupName`
   - text = `"${meetupName} · ${dd}.${MM}.${yyyy}\n${badgeShareTagline}"`, using the badge date's own fields, zero-padded
5. On any exception: snackbar `repShareError(e.toString())` with background `Colors.red` (#F44336).

Note: crest images in the card are network images. If they have not finished loading when the user taps share, the PNG has no crest. Native should preload the crest (Coil `ImageRequest` with `allowHardware(false)`) and draw it synchronously, or wait up to about 1 s for it.

### 2.3 BadgeShareCard (`lib/widgets/badge_share_card.dart`)

Constants (keep verbatim):
- `kShareCardSize = 360 × 560` (logical dp)
- `kAppStoreUrl = "zapstore.dev/apps/space.einundzwanzig.meetup"`
- Footer site text `"21meetup.space"`, brand `"EINUNDZWANZIG"`

Parameter `collectorName: String = ""`. No caller passes it today, so the "Collected by" row never renders. Keep the parameter.

Layout (background cDark):
- **Image part** (all space above the footer; a stack filling it):
  1. `BadgeArt(seed = "${meetupName}:${blockHeight}")`. It **must** use the same seed as the wallet so the shared picture matches the app.
  2. `MeetupCrestWatermark(meetupName, imageUrl = coverUrl or null, opacity = 0.26)` with the default widthFactor 1.05.
  3. Vertical gradient: colors transparent / cDark at 65 % / cDark, stops 0.35 / 0.78 / 1.0.
  4. Header row at top 20, left 22, right 22:
     - A chip: padding h9 v4, orange at 18 %, radius 6, border orange at 50 % 0.5 dp. Text `"EVENT-BADGE"` if `isEvent`, otherwise `"MEETUP-BADGE"` (hard-coded, orange, 9 sp, w900, letterSpacing 1.2).
     - Spacer.
     - When `isNostrSigned`: `verified_rounded` (cGreen, 18).
  5. Title block at left 22, right 22, bottom 18:
     - name uppercase (cText, 26 sp, line height 1.1, w900, letterSpacing 0.5, max 2 lines)
     - 6 dp
     - `dd.MM.yyyy` (orange, 14 sp, w700, letterSpacing 0.8)
- **Footer**: cCard, padding 22,14,22,16. Column:
  - When collectorName is non-empty: footRow(`person_rounded`, `shareCardCollectedBy`, collectorName), then 7 dp.
  - When `blockHeight > 0`: footRow(`link_rounded`, `shareCardBlock`, the grouped block, monospace, accent), then 7 dp. Grouping is the same as `formatBlockWallet` but empty when ≤ 0.
  - When `claimTimestamp > 0`: footRow(`schedule_rounded`, `shareCardScanned`, `"dd.MM.yyyy  HH:mm"` in local time, monospace), then 7 dp.
  - When the hash is non-empty: footRow(`fingerprint_rounded`, `shareCardChecksum`, hash, monospace). The value is `hash.take(16) + "…"` when the hash is longer than 16; the hash is always exactly 16, so in practice it is shown as is.
  - 12 dp, a 0.5 dp line in cTileBorder, 12 dp.
  - `shareCardPromo` (cText, 11.5 sp, line height 1.4, w600).
  - 8 dp, then a row: `download_rounded` (orange, 13), 6 dp, `kAppStoreUrl` (orange, 10.5 sp, w700, monospace, 1 line).
  - 10 dp, then a row: `bolt_rounded` (orange, 13), 5 dp, `"EINUNDZWANZIG"` (cTextSecondary, 10 sp, w900, letterSpacing 1.4), spacer, `"21meetup.space"` (cTextTertiary at 80 %, 9.5 sp, letterSpacing 0.4).
- footRow(icon, label, value): icon (cTextTertiary, 13), 7 dp, `"$label  "` (two trailing spaces; cTextTertiary, 10.5 sp, w600, letterSpacing 0.3), then the value filling the rest, right-aligned, 1 line, ellipsis (orange if accent else cText, 11.5 sp, w700, optionally monospace).

### 2.4 BadgeWorldMapScreen

**Entry:** the wallet AppBar map button with `badges = full collection` (including organizer markers, which have coordinates from their session).

**State:** `sharing: Boolean`, `selected: MeetupBadge?`, `didFit: Boolean`.

- `located = badges.filter { it.lat != 0.0 || it.lng != 0.0 }`
- `cityCount = located.map { it.meetupName }.toSet().size`

**Layout**
- Scaffold background cDark. The AppBar background is cDark with elevation 0; the title is `mapTitle` (cText, 16 sp, w800, letterSpacing 0.5).
- AppBar action, only when `located` is non-empty: an `ios_share_rounded` icon (cText, 20). While sharing it becomes an 18×18 circular progress indicator (orange, stroke 2) and is disabled. Tapping calls `shareImage()`.
- **Empty** (`located` is empty): a centred column with padding 40: `public_off_rounded` (cTextTertiary, 56), 20 dp, `mapEmpty` (cText, 17 sp, w700, centred), 10 dp, `mapEmptySub` (cTextSecondary, 13 sp, line height 1.5, centred).
- **Map**: a column.
  1. A capturable area that fills the remaining height. It is a stack of:
     - The map:
       - initial center = the first located badge
       - initial zoom 11 when `located.size == 1`, otherwise 4
       - every gesture except rotation is allowed
       - OSM raster tiles
       - a marker per located badge: a 40×40 circle in orange with a 2 dp white border, shadow orange at 50 % (blur 8, spread 1), and a `bolt_rounded` icon (black, 20). Tapping sets `selected`.
     - The stats bar at top 12, left 12, right 12:
       - padding h16 v12, cDark at 85 %, radius 14, border orange at 30 % 0.5 dp
       - a Row with space-around: stat(`located.size`, `mapStatMeetups`), a 0.5×32 divider in cTileBorder, stat(`cityCount`, `mapStatCities`)
       - stat = value (orange, 22 sp, w900) above label (cText, 11 sp, w500)
     - When `selected != null`, an info card at bottom 12, left 12, right 12:
       - padding 14, cCard, radius 14, border orange at 40 % 0.5 dp, shadow black at 30 % blur 12
       - Row: a 38 dp circle in orange at 15 % with `bolt_rounded` (orange, 20); 12 dp; a column with `meetupName` (cText, 15 sp, w700) and `"d.M.yyyy"` (cTextTertiary, 12 sp); then an IconButton `close_rounded` (cTextTertiary, 18) that sets `selected = null`.
  2. Below the capturable area: SafeArea (bottom only), padding 16, a full-width ElevatedButton:
     - orange background, black foreground, vertical padding 16, radius 14
     - icon `ios_share_rounded` (18), label `mapShareButton` (w700, 15 sp)
     - disabled while sharing; calls `shareImage()`
- **Camera fit**, once only (`didFit`), after the first frame: with one point, `move(point, zoom 11)`; otherwise fit the bounds of all points with **padding 60** and **maxZoom 12**. Errors are ignored. It never runs again, so user zoom is not reset on recomposition.
- No marker clustering. Badges at the same spot overlap.
- No OSM attribution is shown in Flutter. **Native should add the attribution "© OpenStreetMap contributors"** (OSM tile policy). This is UI only.

**shareImage()**
1. Set `selected = null` so the info card is not captured, and set `sharing = true`.
2. Wait **300 ms** so tiles can be drawn.
3. Capture the stack (map and stats bar) at a pixel ratio of **2.5** relative to logical pixels, so the size is roughly (width dp × 2.5) × (map-area height dp × 2.5).
4. If capture fails, set `sharing = false` and return.
5. Share the file `"badge_worldmap_${System.currentTimeMillis()}.png"` (`image/png`) with text `mapShareText(located.size)` and no subject.
6. Any exception is swallowed silently.
7. Finally set `sharing = false`.

### 2.5 MeetupCrestWatermark / MeetupCrestFace

These are used only inside this domain.

**MeetupCrestWatermark** (stateful; a "background" crest). Parameters:
- `meetupName: String`
- `opacity: Float = 0.20f`
- `widthFactor: Float = 1.05f`
- `imageUrl: String? = null`

Usages and values:

| Usage | opacity | widthFactor |
|---|---|---|
| Normal wallet card | 0.20 | 1.05 |
| Compact card | 0.17 | 0.95 |
| Collection card (52×52) | 1.0 | 1.0 |
| Share card | 0.26 | 1.05 |

- On first composition (`ensureLogos`): if the portal logo registry is empty **and** `imageUrl` is empty, trigger `CrestRepository.ensureLoaded()`. This is a **process-wide single shared fetch** (a static Future), so 20 cards trigger one request. After it completes, recompose. In Flutter the future is never reset, so it runs at most once per process even if it produced nothing. Native: expose the registry as a `StateFlow` and allow at most one in-flight fetch, plus an optional retry on the next app start.
- URL resolution:
  1. `url = imageUrl.trim()`. A watermark does **not** absolutize its own `imageUrl` in Flutter. Native should absolutize anyway; it is harmless because the URL is only displayed.
  2. If empty: `city = meetupName.split(',').first().trim()`. If the city is empty, render nothing. Otherwise `url = absoluteImageUrl(logoFor(city))`.
  3. If still empty, render nothing.
- Rendering. Let the parent box be W×H and `side = W × widthFactor`.
  - The image box is side×side, with `contentScale = Fit`.
  - It is aligned at Flutter `Alignment(0.15, -0.25)`, so `left = (W - side) × (1 + 0.15) / 2` and `top = (H - side) × (1 - 0.25) / 2`. These values can be negative; the overflow is clipped by the parent card.
  - Apply the opacity.
  - Apply a mask (`BlendMode.DstIn`) with a radial gradient centred in the side×side box. The radius is `0.62 × side` (Flutter's radius is a fraction of the shortest side). Colors white, white, transparent; stops 0.0, 0.55, 1.0.
  - Compose: `graphicsLayer { compositingStrategy = Offscreen; alpha = opacity }` with `drawWithContent { drawContent(); drawRect(Brush.radialGradient(0f to White, 0.55f to White, 1f to Transparent, center, radius = 0.62f*side), blendMode = DstIn) }`.
- While loading, and on error: render **nothing** (no placeholder, no broken-image icon).

**MeetupCrestFace** (stateless; the crest is the subject). Parameters: `meetupName`, `fallback: @Composable`, `imageUrl: String?`.
- If `imageUrl.trim()` is non-empty, `url = absoluteImageUrl(own)`.
- Otherwise use the city lookup above. An empty city means fallback.
- If the URL is empty, show the fallback. While loading and on error, also show the fallback.
- Fit inside the box.
- It does **not** trigger the logo fetch itself. If the registry is still empty, it shows the fallback until something recomposes it. Native: observe the registry flow so the face updates as soon as the logos arrive.

### 2.6 EventBadgeTour (consumed by the calendar/event-editor domain)

It runs in `EventEditorScreen` ("new event"). The persisted tour ID is **`GuideTour.events`** (prefs key `guide_completed_events`).

Start: after `EventBadgeAuthService.myRight()` resolves:
1. If `events` is already completed, stop.
2. Wait **400 ms**.
3. Call `startTour(events, EventBadgeTour.steps(mayCreateBadge = right != EventBadgeRight.none))`, where `EventBadgeRight ∈ {none, adminRegistry, portalLeader}`.

Dispose: if the active tour is `events`, call `finishTour()` (marks it completed).

Targets (the editor must expose these anchors):

| Key | Wraps |
|---|---|
| `basicsKey` | title and location input block (always present) |
| `whenWhereKey` | start/end date button block (always present) |
| `switchKey` | the "badge for this event" Switch (present; disabled when not allowed) |
| `imageKey` | the "badge image" label (present only when the switch is ON) |
| `locationKey` | the "badge location" label (switch ON) |
| `issuersKey` | the "badge issuers" label (switch ON) |

Steps:

| # | Target | title / body / hint | Options |
|---|---|---|---|
| 1 | basicsKey | `guideEvBasicsTitle` / `guideEvBasicsBody` | defaults |
| 2 | whenWhereKey | `guideEvWhenWhereTitle` / `guideEvWhenWhereBody` | defaults |
| 3* | switchKey | `guideEvBadgeSwitchTitle` / `guideEvBadgeSwitchBody` / hint `guideEvBadgeSwitchHint` | defaults. Must **not** block until switched (no `completeWhen`). |
| 4* | imageKey | `guideEvBadgeImageTitle` / `guideEvBadgeImageBody` | waitForTarget = true, waitTimeout = **900 ms** |
| 5* | locationKey | `guideEvBadgeLocationTitle` / `guideEvBadgeLocationBody` | waitForTarget, 900 ms |
| 6* | issuersKey | `guideEvBadgeIssuersTitle` / `guideEvBadgeIssuersBody` | waitForTarget, 900 ms |

\* Only when `mayCreateBadge`. If the switch stays off, steps 4–6 time out after 900 ms and are skipped.

### 2.7 Localized string keys per screen

All keys exist in `app_de.arb`, `app_en.arb` and `app_es.arb`. German values are quoted here so mappings can be checked; placeholders are in braces. None of them use ICU plural forms; keep the literal text, even "1 Badges".

**Wallet:**

| Key | German value |
|---|---|
| `mapButton` | "Auf der Karte ansehen" |
| `walletFormerTitle` | "Frühere Sammlungen" |
| `walletCleanupTitle` | "Duplikate bereinigen" |
| `walletShareTitle` | "Reputation teilen" |
| `walletShareReputation` | "REPUTATION TEILEN" |
| `walletShareText` | "Als Text teilen" |
| `walletShareTextSub` | "Lesbar für alle (wird im Web kopiert)" |
| `walletShowQr` | "QR-Code anzeigen" |
| `walletShowQrSub` | "Zum Scannen vor Ort" |
| `walletExportJson` | "Als JSON exportieren" |
| `walletExportJsonSub` | "Signierter Export mit Schnorr-Beweis" (the export is NOT actually signed; see 9) |
| `cancel` | "Abbrechen" |
| `walletReputationCopied` | "Reputation in Zwischenablage kopiert" |
| `walletJsonCopied` | "JSON-Daten in Zwischenablage kopiert" |
| `walletFormerNone` | "Keine früheren Sammlungen." |
| `walletFormerHint` | |
| `walletFormerEntry` | "{count} Badges" |
| `walletFormerUnreadable` | "Nicht lesbar, kein passender Schlüssel" |
| `walletFormerReasonReplaced` | "Beim Identitätswechsel ersetzt" |
| `walletFormerReasonRotated` | "Ältere Sicherung" |
| `walletFormerReasonCleared` | "Geleert" |
| `walletFormerMerge` | "Zusammenführen" |
| `walletFormerMergeTitle` | "Zur aktuellen Identität hinzufügen?" |
| `walletFormerMergeBody` | |
| `dialogCancelMixed` | "Abbrechen" |
| `walletFormerMergeFailed` | "Zusammenführen nicht möglich." |
| `walletFormerMerged` | "{count} Badges zusammengeführt." |
| `walletCleanupNone` | "Keine Duplikate gefunden." |
| `walletCleanupBody` | "{count} doppelte Badge(s) gefunden:" |
| `walletCleanupHint` | |
| `dialogCancel` | "ABBRECHEN" |
| `walletCleanupConfirm` | "Entfernen" |
| `badgesUnreadableShort` | "Nicht lesbar — nichts gelöscht" |
| `walletCleanupDone` | "{count} Duplikate entfernt." |
| `walletSearchHint` | "Meetup suchen…" |
| `walletGroupMeetup` | "Nach Meetup" |
| `walletGroupYear` | "Nach Jahr" |
| `walletNoResults` | "Keine Badges gefunden." |
| `walletOrganizerSection` | "Von dir erstellt" |
| `walletCollectionCount` | "{count} Badges" |
| `walletSince` | "seit {month}" |
| `walletLastVisit` | "zuletzt {ago}" |
| `walletAgoToday` | "heute" |
| `walletAgoYesterday` | "gestern" |
| `walletAgoDays` | "vor {days} Tagen" |
| `walletAgoMonths` | "vor {months} Monaten" |
| `walletAgoYears` | "vor {years} Jahren" |
| `walletNoBadges` | "Noch keine Badges gesammelt" |
| `walletNoBadgesSub` | |
| `emptyFindMeetup` | "Meetup finden" |
| `guideWallet*` | 8 keys, see the tour table |

Hard-coded wallet strings (not localized): `"BADGE WALLET"`, `'Normal'`, `'Kompakt'`, the share subjects, the share text body (8.7), and the month abbreviations (8.4).

**Details:**

| Key | German value |
|---|---|
| `badgeShare` | "Badge teilen" |
| `badgeShareCaps` | "BADGE TEILEN" |
| `badgeClose` | "SCHLIESSEN" |
| `badgeTimestamp` | "Zeitstempel" |
| `badgeMeetupDate` | "Meetup-Datum" |
| `badgeBlockAtScan` | "₿ Blockhöhe beim Scan" |
| `badgeScanTime` | "Scan-Zeitpunkt" |
| `badgeDetailsTitle` | "Badge-Details" |
| `badgeMeetup` | "Meetup" |
| `badgeTransmission` | "Übertragungsweg" |
| `badgeRollingQr` | "Rolling QR-Code" |
| `badgeNfcTag` | "NFC-Tag" |
| `badgeMeetupId` | "Meetup-ID" |
| `badgeProofTitle` | "Kryptographischer Beweis" |
| `badgeSignatureType` | "Signaturtyp" |
| `badgeSchnorrSig` | "Schnorr (Nostr v2) ✓" |
| `badgeNoSignature` | "Keine Signatur" |
| `badgeOrganizerNpub` | "Organisator (npub)" |
| `badgeClaimBinding` | "Claim-Binding" |
| `badgeBound` | "Gebunden ✓" |
| `badgeNotBound` | "Nicht gebunden" |
| `badgeNote` | "Hinweis" |
| `badgeClaimedLater` | "Nachträglich geclaimed" |
| `badgeUnverified` | "Präsenz ungeprüft" |
| `badgeUnverifiedInfo` | |
| `badgeVerificationHash` | "VERIFIKATIONS-HASH" |
| `badgeHashCopied` | "Hash kopiert" |
| `badgeUnknown` | "unbekannt" |
| `badgeProofOfAttendance` | "PROOF OF ATTENDANCE" |
| `badgeProofDesc` | |
| `badgeOrganizerTitle` | "ORGANISATOR-NACHWEIS" |
| `badgeOrganizerDesc` | |
| `badgeShareTagline` | "Vor Ort dabei gewesen — bestätigt über Nostr." |
| `repShareError` | "Fehler beim Teilen: {msg}" |

Hard-coded details strings: `"HMAC (Legacy v1)"`, `"Nostr Event-ID"`, `"—"`, `"Block "`, `"₿"`.

**Share card:**

| Key | German value |
|---|---|
| `shareCardCollectedBy` | "Gesammelt von" |
| `shareCardBlock` | "Block" |
| `shareCardScanned` | "Gescannt" |
| `shareCardChecksum` | "Prüfsumme" |
| `shareCardPromo` | |

Hard-coded share-card strings: `"EVENT-BADGE"`, `"MEETUP-BADGE"`, `"EINUNDZWANZIG"`, `"21meetup.space"`, `kAppStoreUrl`.

**World map:**

| Key | German value |
|---|---|
| `mapTitle` | "Meine Badge-Weltkarte" |
| `mapEmpty` | |
| `mapEmptySub` | |
| `mapStatMeetups` | "Meetups" |
| `mapStatCities` | "Städte" |
| `mapShareButton` | "Als Bild teilen" |
| `mapShareText` | "Hier war ich überall! 🌍 {count} Meetups auf meiner Einundzwanzig Badge-Weltkarte." |

**Tours:**
- `guideWalletBadgesTitle`/`Body`, `guideWalletMapTitle`/`Body`, `guideWalletViewTitle`/`Body`, `guideWalletShareQrTitle`/`Body`
- `guideEvBasicsTitle`/`Body`, `guideEvWhenWhereTitle`/`Body`, `guideEvBadgeSwitchTitle`/`Body`/`Hint`, `guideEvBadgeImageTitle`/`Body`, `guideEvBadgeLocationTitle`/`Body`, `guideEvBadgeIssuersTitle`/`Body`

Tours resolve strings by **key name at runtime**. Native should store string resource IDs in the step definitions instead.

---

## 3. Data models

### 3.1 MeetupBadge

Owned by the badge model domain; listed here because every field is displayed. The JSON keys are exact (`toJson` / `fromJson`).

| Field | Kotlin type | Default when missing | JSON key | Used here |
|---|---|---|---|---|
| id | String | required | `id` | verification hash, proofId |
| meetupName | String | required | `meetupName` | everywhere |
| date | DartDateTime (see 3.2) | required, `DateTime.parse` | `date` (Dart `toIso8601String()`) | display, grouping, sort, hash |
| iconPath | String | required | `iconPath` | not displayed |
| coverUrl | String | `""` | `coverUrl` | crest image override (event badges) |
| blockHeight | Int | `0` | `blockHeight` | display, art seed, hash |
| signerNpub | String | `""` | `signerNpub` | details |
| meetupEventId | String | `""` | `meetupEventId` | details, identity key |
| delivery | String | `"nfc"` | `delivery` | details (`rolling_qr`, `nfc`, `organizer`, ...) |
| sig | String (128 hex) | `""` | `sig` | export, proofId |
| sigId | String (64 hex) | `""` | `sigId` | details, export, identity |
| adminPubkey | String (64 hex) | `""` | `adminPubkey` | export, isNostrSigned |
| sigVersion | Int | `0` | `sigVersion` | details label, export |
| sigContent | String | `""` | `sigContent` | not shown, not exported |
| claimSig | String | `""` | `claimSig` | isClaimed, export |
| claimEventId | String | `""` | `claimEventId` | export |
| claimPubkey | String | `""` | `claimPubkey` | isClaimed, export |
| claimTimestamp | Int (unix seconds) | `0` | `claimTimestamp` | scan time, union tiebreak |
| isRetroactive | Boolean | `false` | `isRetroactive` | details note, export |
| presenceVerified | Boolean | **`true`** | `presenceVerified` | card marker, details note, export |
| isOrganizer | Boolean | `false` | `isOrganizer` | grouping, hero text, pill icon |
| isEvent | Boolean | `false` | `isEvent` | share-card chip |
| lat | Double | `0` (JSON num) | `lat` | map |
| lng | Double | `0` | `lng` | map |

Derived properties (verbatim semantics):
- `hasCryptoProof = sig.isNotEmpty() && sigVersion > 0`
- `isNostrSigned = sigVersion == 2 && sig matches ^[0-9a-fA-F]{128}$ && adminPubkey matches ^[0-9a-fA-F]{64}$ && BigInteger(sig,16) != 0`
- `isClaimed = claimSig.isNotEmpty() && claimPubkey.isNotEmpty()`
- `isFullyBound = hasCryptoProof && isClaimed`
- `proofId`, `claimProofId`, `getVerificationHash()`: see 7.
- `identityKey(b)`, in this order:
  1. `"evt:${meetupEventId}"` if `meetupEventId` is non-empty
  2. otherwise `"sig:${sigId}"` if `sigId` is non-empty
  3. otherwise `"nd:${DartString.lower(DartString.trim(meetupName))}|${yyyy}-${MM}-${dd}"` (Dart `trim().toLowerCase()`; spec 02 §8.14 — not Kotlin `trim().lowercase()`), using the date's own fields, zero-padded
- `unionKey(b) = if (isOrganizer) "org:" + identityKey(b) else identityKey(b)`

**Equality:** Dart `MeetupBadge` does **not** override `==`, so list operations in the UI (`contains`, `indexOf`) use **object identity**. If the Kotlin class is a `data class`, the UI must use identity explicitly (`===`, `indexOfFirst { it === b }`, `IdentityHashMap`). See 14.

### 3.2 DartDateTime (needed for byte-exact hashes and the export)

`MeetupBadge.date` must round-trip **exactly** like Dart's `DateTime`, because `getVerificationHash()`, the legacy `proofId`, and the export `date` field all use `date.toIso8601String()`. Recommended model:

```kotlin
/** Mirrors Dart DateTime: wall-clock fields with microsecond precision + isUtc flag. */
data class DartDateTime(val local: java.time.LocalDateTime /* nanos multiple of 1000 */, val isUtc: Boolean) {
    fun toInstant(zone: ZoneId = ZoneId.systemDefault()): Instant =
        if (isUtc) local.toInstant(ZoneOffset.UTC) else local.atZone(zone).toInstant()
    val year get() = local.year; val month get() = local.monthValue; val day get() = local.dayOfMonth
    fun toIso8601String(): String  // see algorithm below
    companion object { fun parse(s: String): DartDateTime; fun now(): DartDateTime /* local, micro precision */ }
}
```

`toIso8601String()`, matching Dart:
- `yyyy` is zero-padded to 4 digits; years outside −9999..9999 use 6 digits with a sign.
- Format: `yyyy-MM-ddTHH:mm:ss.mmm` + (`uuu` only if `microsecond != 0`) + (`Z` only if isUtc).

`parse(s)`, matching Dart `DateTime.parse`:
- Accepted form: `[+-]?yyyy[yy]-?MM-?dd`, optionally followed by `[T ]HH(:?mm(:?ss([.,]fraction)?)?)?`, optionally followed by `Z|z|[+-]HH(:?mm)?`.
- The fraction is **truncated** to 6 digits (microseconds).
- With a zone, the value is converted to UTC and `isUtc = true`.
- Without a zone it is a local wall-clock time with `isUtc = false`.

Display (`date.day`, `.month`, `.year`) uses the object's **own fields**: UTC fields when isUtc, wall-clock fields otherwise. **Never convert to the device zone for display.** Comparisons and sorting (`date.compareTo`) and differences (`now.difference(d).inDays`) use the instant.

New badges are created with `DateTime.now()`, a local value with microseconds, for example `2026-10-11T19:03:12.345678`. Badges from older sources or tests may be UTC (`...Z`).

### 3.3 FormerCollection (archive entry view model)

| Field | Type | Source |
|---|---|---|
| index | Int | position in the **stored** list (oldest first); passed to `mergeFormer(index)` |
| at | Instant | `entry["at"]` (unix **seconds**) × 1000; displayed in local time |
| reason | String | `entry["reason"]`: `replaced` / `rotated` / `cleared` (or any other value) |
| npub | String | `entry["npub"]`, may be `""` |
| badges | `List<MeetupBadge>?` | the decrypted `entry["blob"]`; `null` means no key on the device opens it |

`formerCollections()` returns entries **newest first** (the stored list reversed). Stored format (prefs key `badges_former`, plus a mirror in the secure store under the same key): a JSON array `[{"at":<int sec>,"reason":"…","npub":"…","blob":"<b64 iv>:<b64 ct>"}]`, holding at most 10 entries and at most 500 × 1024 characters in total. This belongs to the storage domain.

### 3.4 UI state (native, not persisted)

```kotlin
data class WalletUiState(
    val badges: List<MeetupBadge> = emptyList(),
    val query: String = "",           // raw text; filter uses trim()
    val groupByMeetup: Boolean = true,
    val compact: Boolean = false,
    val collapsed: Set<String> = emptySet(),   // group keys (year strings or ORGANIZER_GROUP_KEY)
)
data class WorldMapUiState(val located: List<MeetupBadge>, val cityCount: Int,
                           val selected: MeetupBadge? = null, val sharing: Boolean = false, val didFit: Boolean = false)
```

---

## 4. Persistence

This domain writes nothing of its own except the tour-completion flags. The UI state above is in-memory only. Flutter keeps it per screen instance; the tab instance lives as long as the app shell.

| Key (exact) | Store | Format | Writer | Reader |
|---|---|---|---|---|
| `guide_completed_wallet` | SharedPreferences (native: same-named DataStore Preferences key) | bool | GuideService when the tour finishes, is skipped through, or the wallet is disposed while active; `declineAllTours()` sets all to true | wallet start |
| `guide_completed_events` | prefs | bool | GuideService (event editor tour) | event editor |
| `guide_onboarding_asked` | prefs | bool | shell onboarding prompt | shell |

`resetAllTours()` (Settings, "Tour wiederholen") removes every `guide_completed_<tourName>` key and `guide_onboarding_asked`. Tour names come from `GuideTour.name`: `onboarding, home, settings, events, portal, wallet, reputation, scan, myMeetups, portalArea`.

Read-only dependencies:
- **UserProfile** (for share text and export): prefs `nickname` (default **`"Anon"`**, so the export identity is almost never "anonymous"), `telegram`, `twitter`, `nostr` (npub fallback), overridden by the SecureKeyStore npub and the external signer npub.
- **Badge storage** (storage domain), via `loadBadges` / `saveBadges` / `formerCollections` / `mergeFormer`:
  - prefs `badges_encrypted`, `badges_encrypted_preserved`, `badges_encrypted_preserved_older`, `badges_pending_encrypted`, `badges_pending` (legacy plaintext), `badges` (legacy plaintext), `badges_encrypted_incoming`, `badges_former`
  - secure storage `badge_encryption_key`, `badge_encryption_key_BACKUP`, `badge_encryption_key_suspect`, `badge_pending_key`, `badge_pending_key_suspect`, `badges_encrypted`, `badges_former`, `badge_reset_pending`
  - blob format: `base64(iv12):base64(AES-256-GCM ciphertext‖tag)` over a JSON array of `toJson()`

No migrations in this domain.

Caches:
- Flutter keeps crest images in memory only (ImageCache). Native may use the Coil memory and disk cache.
- flutter_map 7 does not persist tiles. Native (osmdroid) caches tiles on disk, which the OSM tile policy encourages.

---

## 5. Network

| What | URL | Method / headers | Timeout / retry | Notes |
|---|---|---|---|---|
| OSM raster tiles | `https://tile.openstreetmap.org/{z}/{x}/{y}.png` | GET, `User-Agent: flutter_map (space.einundzwanzig.meetup)` | flutter_map defaults; no retry | maxNativeZoom 19; map maxZoom unbounded (the fit caps at 12). Native: set a UA that identifies the app, for example `space.einundzwanzig.meetup/<version> (Android)` (OSM policy forbids library-default UAs), and show attribution. |
| Crest / cover images | portal logos `https://portal.einundzwanzig.space/...` (relative paths are absolutized) or event cover URLs (any host, typically a Blossom upload) | GET (Image.network; no auth) | none; failure means render nothing or the fallback | http is also allowed by Flutter. Native: allow https; http only if a network security config permits it, otherwise it simply fails and nothing is shown. |
| Portal events (fills the logo registry) | `GET https://portal.einundzwanzig.space/api/meetup-events/{yyyy-MM-dd}` for **today**, the **1st of next month** and the **1st of the month after** (sequential) | `Accept: application/json`, plus `Authorization: Bearer <portal token>` if logged in | 15 s each; non-200 or exception gives null and the date is skipped; no retry | Response: `{"data":[…]}` or `[…]`, deduplicated by `id`. For each event with `start >= now − 6 h` (portal start parsed as UTC when no zone is given): `name = meetup.name` (nested `meetup` map or flat key `"meetup.name"`, default `"Meetup"`) and `logo = meetup.logo` / `"meetup.logo"`; if the logo is non-empty, `portalLogos[name] = logo`. If no events are found, fall back to the iCal feed `https://portal.einundzwanzig.space/stream-calendar` (provides no logos). Owned by the calendar/portal domain. |

Registry helpers (verbatim semantics):

```dart
static String absoluteImageUrl(String url) {
  final u = url.trim();
  if (u.isEmpty || u.startsWith('http://') || u.startsWith('https://')) return u;
  return 'https://portal.einundzwanzig.space${u.startsWith('/') ? '' : '/'}$u';
}
static String logoFor(String titleOrCity) {
  final q = titleOrCity.trim().toLowerCase();
  if (q.isEmpty) return '';
  for (final entry in portalLogos.entries) {          // insertion order (LinkedHashMap)
    final name = entry.key.toLowerCase();
    if (name == q || name.contains(q) || q.contains(name)) return absoluteImageUrl(entry.value);
  }
  return '';
}
```

Limitation: only meetups with an upcoming event in about the next 2–3 months ever get a crest. The meetup list (`Meetup.logoUrl` in `meetup_service.dart`) would be a better source. Native may additionally seed the registry from it; that is display only and safe.

Sharing does not touch the network. There are no rate limits in this domain.

---

## 6. Nostr

This domain neither builds nor signs nor subscribes to events directly. Indirect effects:
- After a cleanup or a former-collection merge it calls `ReputationPublisher.publishInBackground(badges)`. That publishes the replaceable reputation event **kind 30078** with tag `["d","einundzwanzig-reputation"]` (protocol version 2). The counters and proof hash change. Fire-and-forget; errors are only logged. (Reputation domain.)
- `saveBadges` and `mergeFormer` invoke the `MeetupBadge.onBadgesSaved` hook, which triggers the encrypted badge sync (sync domain).
- Attendance confirmation events (kind 30079, d-tag = `attendanceKey(meetupEventId, signerNpub, isEvent)` = `<meetupEventId>@<last 12 chars of signer npub>`, or `ev|<meetupEventId>` for event badges, plain `meetupEventId` only for the legacy empty-signer case; spec 05 §6.4/§7.3, coattendance_service.dart:184/279) are not touched by cleanup. That is why removing duplicates is safe.
- Displayed values come from Nostr events made elsewhere:
  - `sig`/`sigId`/`adminPubkey`: the organizer's kind-21000 event (tags `["t","badge"]`, `["m",<meetup>]`)
  - `claimSig`/`claimEventId`/`claimPubkey`: the attendee's kind-21002 claim (tags `["t","badge_claim"]`, `["p",<organizer>]`)

  This domain does no verification.
- `isNostrSigned` (format check only) drives the green verified icon on the share card.

---

## 7. Crypto and encoding (byte-exact)

All hashes are SHA-256 over **UTF-8** bytes, written as **lowercase hex**. Use `java.security.MessageDigest("SHA-256")`.

### 7.1 Verification hash (details hash card, share-card checksum, export `hash`)

```dart
String getVerificationHash() {
  final data = '$id-$meetupName-${date.toIso8601String()}-$blockHeight';
  final bytes = utf8.encode(data);
  final digest = sha256.convert(bytes);
  return digest.toString().substring(0, 16);
}
```

`blockHeight` is the decimal Int with no grouping. The date string must be Dart's `toIso8601String()` (3.2).

### 7.2 Legacy proof and claim proof (used by the JSON export; owned by the model domain)

```dart
String get proofId {
  if (sigId.isNotEmpty) return sigId;
  if (sig.isNotEmpty) return sig.substring(0, 64);          // throws if sig.length < 64
  final data = '$id-$meetupName-${date.toIso8601String()}-$blockHeight';
  return sha256.convert(utf8.encode(data)).toString().substring(0, 64);
}
String get claimProofId {
  if (!isClaimed) return '';
  final data = '$sig|$claimSig|$claimPubkey';
  return sha256.convert(utf8.encode(data)).toString();
}
static String generateBadgeProof(List<MeetupBadge> badges) {      // "badge_proof"
  if (badges.isEmpty) return '';
  final sorted = List<MeetupBadge>.from(badges)..sort((a, b) => a.date.compareTo(b.date));
  final proofChain = sorted.map((b) => b.proofId).join('|');
  return sha256.convert(utf8.encode(proofChain)).toString();
}
static String generateBadgeProofV2(List<MeetupBadge> badges) {    // "badge_proof_v2"
  final bound = badges.where((b) => b.isFullyBound).toList();
  if (bound.isEmpty) return '';
  bound.sort((a, b) => a.claimTimestamp.compareTo(b.claimTimestamp));
  final proofChain = bound.map((b) => b.claimProofId).join('|');
  return sha256.convert(utf8.encode(proofChain)).toString();
}
```

Note: Dart's `List.sort` is not guaranteed stable. The SDK (`lib/internal/sort.dart`) calls `_doSort(a, 0, a.length - 1, compare)` with an inclusive `right` index and uses the stable insertion sort when `(right - left) <= _INSERTION_SORT_THRESHOLD` (= 32), that is for lists of **33 or fewer** elements; above that it uses an unstable dual-pivot quicksort. So for up to 33 elements (including exactly 33 with tied keys) Dart's order equals Kotlin's stable `sortedWith`; only for lists of **34 or more** elements can ties (equal dates or claim timestamps) produce a different chain order than Dart. Ties are practically impossible for dates (microsecond precision) but possible for `claimTimestamp` (seconds). This is accepted: these hashes are only compared against themselves (`verifyBadgeProof`) and published as informational fields.

### 7.3 JSON export checksum

```dart
final jsonString = jsonEncode(data);   // compact, insertion order, WITHOUT 'checksum'
final checksum = sha256.convert(utf8.encode(jsonString)).toString().substring(0, 8);
data['checksum'] = checksum;
return const JsonEncoder.withIndent('  ').convert(data);
```

The Kotlin JSON writer must reproduce Dart's `jsonEncode` exactly:
- separators `,` and `:` with no spaces
- keys in insertion order
- string escaping: `"` becomes `\"`, `\` becomes `\\`, and `\b \t \n \f \r` use the short escapes
- other characters below 0x20 become `\u00XX` (lowercase hex)
- unpaired surrogates become `\uXXXX`
- **everything else raw**: `/` is NOT escaped, non-ASCII is NOT escaped, DEL 0x7F is raw
- ints as decimal; `true`/`false`

Pretty output uses an indent of 2 spaces with `"key": value`, `[]` / `{}` for empty containers, and newline-separated elements. Do not use `org.json` (it escapes `/`). Write a small ordered emitter, or use kotlinx.serialization `JsonObject` with `Json { prettyPrint = true; prettyPrintIndent = "  " }`. Verify that its empty array output is `[]` and that strings are escaped as above.

### 7.4 Generative art seed

`seedBytes = SHA-256(UTF-8("${meetupName}:${blockHeight}"))`, 32 bytes, read as unsigned values 0..255. See 8.6.

Nothing else (encryption, keys, bech32) is done in this domain. The npub strings shown are already bech32 and are only shortened.

---

## 8. Business rules and algorithms

### 8.1 Filter and grouping (`_filteredBadges`, `_buildGroups`)

```text
q = DartString.lower(DartString.trim(query))         // Dart trim().toLowerCase() — NOT Kotlin lowercase(Locale.ROOT):
                                                     // Java applies final sigma (ΟΔΟΣ → οδος, Dart οδοσ) and maps U+0130 İ → "i\u0307"
                                                     // (Dart "i"); see spec 02 §8.14
filtered = if q.isEmpty: all else all.filter { DartString.lower(it.meetupName).contains(q) }

ORGANIZER_GROUP_KEY = "\u0000organizer"
for b in filtered (in collection order):
   name = b.meetupName.trim()
   key = if b.isOrganizer: ORGANIZER_GROUP_KEY
         else if groupByMeetup: (name.ifEmpty { "?" })
         else b.date.year.toString()                 // own fields
   map.getOrPut(key){[]}.add(b)                      // LinkedHashMap
each list: sort by date DESC (instant)
entries:
  groupByMeetup: sort by list.size DESC, then key ASC (plain UTF-16 code-unit compareTo, case-sensitive)
  year:          sort by key DESC (string compare)
move the entry with ORGANIZER_GROUP_KEY (if any) to the END
```

The query filter applies to organizer markers too. If the search field is hidden (fewer than 6 badges) but a query was typed earlier, Flutter keeps filtering. Native: clear the query when the field disappears; this is an acceptable UX fix.

### 8.2 Duplicates (cleanup) and union rule

```text
kept = unionByIdentity(all, [])        // returns the chosen OBJECTS (no copies)
duplicates = all.filter { b -> kept.none { it === b } }    // IDENTITY
```

`unionByIdentity(base, extra)` (model domain):
- Build a LinkedHashMap from `unionKey` to the badge.
- For each badge in base and then extra: if the key exists, store `preferVariant(existing, new)`, otherwise store the new badge.
- Return the values in first-key-insertion order.

`preferVariant(a, b)`, in this order:
1. the badge with a non-empty `claimSig` wins
2. otherwise the one with `presenceVerified == true`
3. otherwise the older `date` (instant)
4. otherwise the smaller `claimTimestamp`
5. otherwise the smaller `id` (String compareTo)
6. otherwise `a`

Organizer markers never collide with participant badges, because of the `org:` prefix.

The cleanup button is visible iff `duplicates.isNotEmpty()`. It is recomputed on every render; O(n).

### 8.3 Formatting helpers

- `formatBlockWallet(h)` (wallet card; also the share card, where ≤ 0 gives `""` and the row is hidden):

  ```text
  if h <= 0 return "---"
  s = h.toString(); insert '.' before index i>0 when (s.length - i) % 3 == 0
  ```

  Examples: 7 → "7", 999 → "999", 1000 → "1.000", 875000 → "875.000", 1234567 → "1.234.567", 0 → "---", −5 → "---".
- `formatBlockDetails(h)`: `h == 0` gives `badgeUnknown` ("unbekannt"). Otherwise the same grouping, done in 3-digit chunks from the right; equivalent output for positive numbers.
- `dateShort(d)` = `"${d.day}.${d.month}.${d.year}"`, unpadded, for example `15.3.2025`. Used on wallet cards, the compact card, the map info card and the share text.
- `datePadded(d)` = `"%02d.%02d.%d"`, for example `15.03.2025`. Used in the details "Meetup date", the share-card date and the share-image text.
- `scanTime(ts)` = local `DateTime.fromMillisecondsSinceEpoch(ts*1000)` formatted `"dd.MM.yyyy  HH:mm"` (two spaces, 24 h). Used in details and the share card.
- Former entries use `DateFormat('dd.MM.yyyy HH:mm')` (one space) in local time.
- `shortNpubDetails(npub)`: if `npub.length < 20`, return `"—"` when empty, otherwise the npub unchanged. Else `npub.take(12) + "…" + npub.takeLast(8)` (U+2026).
- `NostrService.shortenNpub(npub, chars = 8)` (archive sheet): if `npub.length < chars*2+5` (that is, < 21), return it unchanged. Else `npub.substring(0, 4+chars) + "..." + npub.takeLast(chars)` (three ASCII dots).
- `sigId` short form: `take(12) + "…" + takeLast(8)`.

### 8.4 Collection subtitle

```text
since = walletSince(monthYear(first.date))           // first = oldest in group
if count == 1 -> since
else -> "$since · ${walletLastVisit(agoLabel(last.date))}"   // last = newest
monthYear(d) = MONTHS_DE[d.month-1] + " " + d.year
MONTHS_DE = [Jan, Feb, Mär, Apr, Mai, Jun, Jul, Aug, Sep, Okt, Nov, Dez]   // hard-coded German, ALL locales
agoLabel(d): days = floor-toward-zero((now - d.instant) / 1 day)   // Dart Duration.inDays truncates
   days <= 0 -> walletAgoToday            (includes future dates)
   days == 1 -> walletAgoYesterday
   days < 30 -> walletAgoDays(days)
   months = days / 30 (int)  ; months < 12 -> walletAgoMonths(months)
   else -> walletAgoYears(days / 365)
```

Quirk: days 360–364 give months = 12 and years = 0, so the label reads "vor 0 Jahren". Vectors are in 10.3. Native may fix this to "vor 1 Jahren" or similar; it is display only. Document whichever choice is made.

### 8.5 Numbering

The card label is `"#${indexInFullCollection + 1}"`. The index is the position of the **same object** in the full, unfiltered collection, so "#3" means the same badge in every grouping and filter. The Collection screen uses the full list it captured at navigation time.

### 8.6 Generative art (BadgeArtPainter): implement exactly

```text
h = SHA-256(UTF-8(seed)), byte(i) = h[i % 32] (0..255)
round(x) = Dart round: half AWAY from zero  (use Math.round for x>=0; NOT kotlin.math.round which is half-even)
color(o, alpha) = RGB( clamp(round(byte(o)*0.5 + 0.5*247), 100, 255),
                       clamp(round(byte(o+1)*0.35 + 0.2*147), 30, 200),
                       clamp(round(byte(o+2)*0.2), 0, 80) ) with alpha (float 0..1, exact double)
Canvas w×h:
1. Background: linear gradient from (0,0) to (w,h), colors [color(0,0.3), color(3,0.15), color(6,0.25)], stops evenly 0, .5, 1; fill rect.
2. shapeCount = 5 + byte(9) % 6      (5..10)
   for i in 0 until shapeCount:
     idx = 10 + i*3
     cx = byte(idx)/255.0 * w ; cy = byte(idx+1)/255.0 * h
     r  = 10 + byte(idx+2)/255.0 * (w*0.35)
     type = byte(idx) % 4
     paint.color = color(idx, 0.08 + (byte(idx+2) % 10) * 0.01)
     paint.style = if byte(idx+1) % 3 == 0 STROKE else FILL ; strokeWidth = 1.5
     type 0: circle(cx, cy, r)
     type 1: path (cx, cy-0.6r) → (cx+0.4r, cy) → (cx, cy+0.6r) → (cx-0.4r, cy) → close
     type 2: hexagon: for j in 0..5: a = (π/3)*j − π/6; p = (cx + 0.5r·cos a, cy + 0.5r·sin a); moveTo first, lineTo rest, close
     type 3: 4 lines, color(idx, 0.06), strokeWidth 1.0 (ignores paint above):
             for l in 0..3: off = l*r*0.3; line (cx−r+off, cy−r) → (cx+r+off, cy+r)
3. Grid: white @ alpha 0.03, strokeWidth 0.5; g = 12 + byte(30) % 8 (12..19)
   vertical lines at x = 0, g, 2g, … while x < w (full height); horizontal at y = 0, g, … while y < h
```

Units are logical pixels (dp), so the pattern scales with the card size. Implement it as `fun DrawScope.drawBadgeArt(spec: BadgeArtSpec)` with a pure `BadgeArtSpec.from(seed)` so it can be unit-tested (vector in 10.4). It is used on wallet cards, compact cards, the details hero fallback and the share card.

### 8.7 Share-as-text template (exact; hard-coded German, verified with Dart)

```kotlin
val text = "🏆 MEINE EINUNDZWANZIG REPUTATION\n\n" +
  "Total Badges: ${all.size}\n" +
  "Meetups besucht: ${all.map { it.meetupName }.toSet().size}\n" +
  (if (npub.isNotEmpty()) "Nostr: $npub" else "") + "\n\n" +
  "📍 Besuchte Meetups:\n" +
  all.joinToString("\n") { "  • ${it.meetupName} (${it.date.day}.${it.date.month}.${it.date.year})" } + "\n\n" +
  "✅ Proof of Attendance\n" +
  "Verifizierbar über die Einundzwanzig Meetup App\n\n" +
  "---\n" +
  "Exportiert am ${now.day}.${now.month}.${now.year}\n    "   // NOTE trailing "\n" + 4 spaces
```

The list follows the collection order and includes organizer markers and duplicates. The bullet is U+2022 preceded by two spaces. The subject is `"Meine Einundzwanzig Meetup Reputation"`.

### 8.8 Reputation JSON export (`MeetupBadge.exportBadgesForReputation`; consumed by `badge-verifier.html`)

Key order is exact:

```text
identity = LinkedHashMap; add in order if non-empty: "nickname", "nostr_npub", "telegram", "twitter"
data = {
 "version": "4.0",
 "identity": identity.ifEmpty { {"status":"anonymous"} },
 "total_badges": all.size,
 "verified_badges": count(hasCryptoProof),
 "bound_badges": count(isFullyBound),
 "meetups_visited": distinct meetupName count,
 "unique_signers": distinct non-empty signerNpub count,
 "badge_proof": generateBadgeProof(all),
 "badge_proof_v2": generateBadgeProofV2(all),
 "stats": {"total": all.size, "crypto_proof": count(hasCryptoProof),
           "claimed": count(isFullyBound), "retroactive": count(isFullyBound && isRetroactive),
           "fully_trusted": claimed - retroactive},
 "badges": [ for b in all (collection order): {
     "meetup": meetupName, "date": date.toIso8601String(), "block": blockHeight,
     "signer_npub": signerNpub, "sig_version": sigVersion, "is_bound": isFullyBound,
     "is_retroactive": isRetroactive, "presence_verified": presenceVerified,
     if hasCryptoProof: "sig", "sig_id", "admin_pubkey",
     if isClaimed:      "claim_sig", "claim_event_id", "claim_pubkey",
     "hash": getVerificationHash() } ],
 "exported_at": DartDateTime.now().toIso8601String()      // local, with micros if non-zero
}
checksum = sha256(compactJson(data)).hex.take(8); data["checksum"] = checksum
output = pretty(data, indent = 2)
```

`badge-verifier.html` reads `data.badges[]` with the fields `meetup`, `date`, `block`, `sig_version`, `signer_npub`, `sig`, `sig_id`, `admin_pubkey` (and optionally `sig_content`, which the app never exports). For `sig_version == 2` it verifies BIP-340 over `sig_id` with `admin_pubkey`. It also uses `data.identity.nostr_npub`. **Do not rename any key.**

### 8.9 Other constants

| Constant | Value |
|---|---|
| Search visible | count ≥ 6 |
| Compact toggle visible | count > 6 |
| Count dots | count ≤ 24 |
| Normal card small font | `meetupName.length > 18` |
| Compact small font | `shortName.length > 12` |
| Wallet tour delay | 600 ms (first step wait 800 ms) |
| Event tour delay | 400 ms (optional steps 900 ms) |
| Map zoom | single 11; multi initial 4; fit padding 60; maxZoom 12 |
| Map share | wait 300 ms; scale 2.5 |
| Share card | 360×560 dp @ 3× |
| Archive | at most 10 entries (storage) |

---

## 9. Security measures to preserve

1. **No data loss in UI flows** (SecurityAudit M7 lineage, badge storage hardening):
   - Cleanup removes exactly the objects the dialog showed (identity), only after a successful `saveBadges`. A failed save changes nothing and shows `badgesUnreadableShort`.
   - Merge works only through an explicit confirmation dialog.
   - `mergeFormer` refuses when the current file is unreadable (`badgesUnreadable`) and never overwrites an unreadable file.
   - The archive entry is kept after merging.
   - Former collections are **never** merged automatically, because they may belong to a different identity.
   - The wallet must never offer a "clear" or "delete" action.
2. **Unreadable state:** when the badge file cannot be decrypted, the UI shows an empty list but **must not** call `saveBadges` with a partial list. `saveBadges` refuses anyway unless `replaceUnreadable` is set; the wallet never passes it.
3. **Export honesty:** the JSON export is labelled "signiert"/"Schnorr-Beweis", but the current code does **not** sign it. It contains the per-badge organizer and claim signatures plus an 8-hex SHA-256 checksum that anyone can recompute. `SecurityChangelog.md` describes an older async signed export with co-signature fields that no longer exists. Native must produce the same unsigned format for compatibility. Recommendation: correct the subtitle wording (string change only).
4. **Legacy v1 HMAC badges** (SecurityAudit Fix 3: the shared secret is public) must be shown as Legacy in orange, never as verified. Keep `"HMAC (Legacy v1)"` and the orange color. The share card's green verified mark depends on `isNostrSigned` (strict format check), not on `sigVersion`.
5. **Organizer markers** are visibly separated: own section, shield icon, "ORGANIZER RECORD" wording. This avoids implying a cryptographic attendance proof for unsigned self-created markers. Do not label them "Proof of attendance".
6. **Presence-unverified badges** must keep their marker (`location_off`) and the details note.
7. **Privacy:**
   - The share card shows **no collector name** by default.
   - The npub is shortened in the UI.
   - The world-map image reveals location history; it is shared only on explicit user action, and only badges with coordinates are drawn.
   - The coordinates on badges are the organizer-signed ones (Security Audit H1: unsigned `la`/`lo` are not trusted). The UI only displays them.
8. **External images:** cover URLs come from event creators (any host). Loading them leaks IP and timing to that host; this is accepted in Flutter. Native: no cookies or auth headers on image requests; https preferred.
9. **Share files** must go through `FileProvider` from the app cache dir with a temporary read grant. No storage permission and no world-readable files.
10. The clipboard copy of the hash and JSON contains no secrets. No private key material ever appears in this domain.

---

## 10. Tests and vectors to port

### 10.1 Existing Dart tests relevant to this domain

- `test/guide_service_host_test.dart`:
  - `finishTourNow` resets the active tour synchronously: `isActive == false`, `activeTour == null`, `currentStep == null`.
  - `finishTourNow` persists completion: after `startTour(GuideTour.wallet, …, force = true)` and `finishTourNow()`, `wasTourCompleted(wallet) == true`.
  - Disposing a host ends its active tour without errors.
  - `finishGuideTourIfActive(portal)` does not end an active `events` tour.
  - `guide` throws before `didChangeDependencies`. Native equivalent: the host requires an injected GuideService.
- `test/badge_storage_copy_test.dart`, the parts driven by the archive sheet and merge:
  - After `stageIncoming([b])` and `promoteIncoming(formerNpub: 'npub1alt')`, `formerCollections().single.npub == 'npub1alt'`.
  - With a readable blob plus an unreadable `badges_encrypted_preserved = 'alt:blob'`: `former.map(reason) == ['replaced','replaced']`; exactly one entry is readable with ids `['bleibt']` and one has `badges == null`. This drives the "unreadable" row and the disabled merge button.
  - "Archiv: frühere Sammlung lässt sich wieder vereinigen": `mergeFormer(former.single.index) == true`, the loaded ids contain both `a` and `b`, and the archive still has length 1 afterwards.
  - Rotation: three `replaceUnreadable` saves leave `former.map(reason) == ['rotated']`. Clearing with `saveBadges([], discardLocked: true)` makes `former.first.reason == 'cleared'`. Order is newest first.
  - Clearing without a reset gives `['cleared','replaced']`, newest first.
  - Reset clears the archive in both places.
- `test/backup_restore_badges_test.dart`:
  - A rotating-QR variant (same `meetupEventId`, different id) collapses to one entry: 2 unique union keys out of 3 inputs.
  - Different events that share an id stay separate.
- `test/badge_reputation_regression_test.dart`:
  - Participant badge and organizer marker stay separate (`org:` prefix).
  - Rescans (copies with different ids) do not inflate.
  - Monthly visits with the same id and different `meetupEventId` both count.

  These validate the duplicate detection used by the cleanup dialog.
- `test/badge_model_test.dart`:
  - `isNostrSigned`, `isClaimed`, `isFullyBound`, `hasCryptoProof` truth tables.
  - `proofId` fallbacks.
  - `claimProofId` length 64 and deterministic.
  - `generateBadgeProof` deterministic and order-independent; empty list gives `''`.
  - `generateBadgeProofV2` only includes bound badges.
  - `getReputationStats`: total 4, crypto_proof 3, claimed 2, fully_trusted 2, retroactive 0; the retroactive case gives retroactive 1, fully_trusted 1.

  All of these feed the export.
- `test/share_origin_test.dart`: iOS popover anchor only. **Not applicable on Android**; no port needed.

### 10.2 New vectors (computed with Dart 3.13.5 and Python hashlib)

**Dart `DateTime.parse(s).toIso8601String()`**

| Input | Output | isUtc |
|---|---|---|
| `2025-03-15T00:00:00.000Z` | `2025-03-15T00:00:00.000Z` | true |
| `2026-10-11T19:03:12.345678` | `2026-10-11T19:03:12.345678` | false |
| `2026-10-11T19:03:12.345000` | `2026-10-11T19:03:12.345` | false |
| `2026-10-11T19:03:12Z` | `2026-10-11T19:03:12.000Z` | true |
| `2026-10-11T19:03:12+02:00` | `2026-10-11T17:03:12.000Z` | true (day 11) |
| `2026-10-11 19:03` | `2026-10-11T19:03:00.000` | false |
| `2026-10-11T19:03:12.1234567` | `2026-10-11T19:03:12.123456` | false (truncated) |
| `2026-10-11T19:03:12,5` | `2026-10-11T19:03:12.500` | false |
| `20261011T190312` | `2026-10-11T19:03:12.000` | false |
| `2026-10-11` | `2026-10-11T00:00:00.000` | false |

Also: `DateTime.utc(2025,3,15)` gives `2025-03-15T00:00:00.000Z`, and the local `DateTime(2026,10,11,19,3,12,0,7)` gives `2026-10-11T19:03:12.000007`.

**Verification hash**
- Badge A: id `badge-001`, name `Aschaffenburg`, date `DateTime.utc(2025,3,15)`, block 875000.
  - Input string: `badge-001-Aschaffenburg-2025-03-15T00:00:00.000Z-875000`
  - SHA-256: `48dd2de552fae3a0c0fe8445594869fd52428f3a74980493a9e0fa75b4165934`
  - **Hash: `48dd2de552fae3a0`**
- Badge B: id `old-badge`, name `München, DE`, local date `2026-10-11T19:03:12.345678`, block 0.
  - Input string: `old-badge-München, DE-2026-10-11T19:03:12.345678-0`
  - SHA-256: `f38c551862271ffe70e896d95e9adfd9a4bb18b32400da422b838a006c3a7471`
  - **Hash: `f38c551862271ffe`**. Because B has no sig or sigId, its `proofId` is this full 64-character hash.

**Proofs** (A additionally has `sig='a'*128`, `sigId='c'*64`, `adminPubkey='b'*64`, `sigVersion=2`, `claimSig='d'*128`, `claimPubkey='e'*64`, `claimEventId=''`, `claimTimestamp=1700000000`, `signerNpub='npub1testadmin'`)
- `claimProofId(A)` = sha256(`'a'*128 + '|' + 'd'*128 + '|' + 'e'*64`) = `e8570ebcc5503c00ab0f3308646e1f1b0c163b72cd99b26aca97f60e4cb880be`
- `badge_proof([A,B])` = sha256(`'c'*64 + '|' + f38c55…7471`) = `a1b78251c23903f98a7cd1ef4ddebc405b656aa90b8fa4e84fff2d94600e2f07`
- `badge_proof_v2([A,B])` = sha256(claimProofId(A)) = `75f4c3ecb24216b8d7673b76601895da7f170a7e879520e43024ad5294692827`

**Export** for `[A, B]`, with identity nickname `Anon`, npub `npub1xyz`, empty telegram and twitter, and `exported_at` fixed to `2026-10-20T12:00:00.000`. The compact JSON (one line) is:

```
{"version":"4.0","identity":{"nickname":"Anon","nostr_npub":"npub1xyz"},"total_badges":2,"verified_badges":1,"bound_badges":1,"meetups_visited":2,"unique_signers":1,"badge_proof":"a1b78251c23903f98a7cd1ef4ddebc405b656aa90b8fa4e84fff2d94600e2f07","badge_proof_v2":"75f4c3ecb24216b8d7673b76601895da7f170a7e879520e43024ad5294692827","stats":{"total":2,"crypto_proof":1,"claimed":1,"retroactive":0,"fully_trusted":1},"badges":[{"meetup":"Aschaffenburg","date":"2025-03-15T00:00:00.000Z","block":875000,"signer_npub":"npub1testadmin","sig_version":2,"is_bound":true,"is_retroactive":false,"presence_verified":true,"sig":"aaaa…(128)","sig_id":"cccc…(64)","admin_pubkey":"bbbb…(64)","claim_sig":"dddd…(128)","claim_event_id":"","claim_pubkey":"eeee…(64)","hash":"48dd2de552fae3a0"},{"meetup":"München, DE","date":"2026-10-11T19:03:12.345678","block":0,"signer_npub":"","sig_version":0,"is_bound":false,"is_retroactive":false,"presence_verified":true,"hash":"f38c551862271ffe"}],"exported_at":"2026-10-20T12:00:00.000"}
```

(The `…(n)` placeholders stand for the full repeated-character strings.)

- **checksum = `745487f0`**
- The pretty output ends with `  "exported_at": "2026-10-20T12:00:00.000",\n  "checksum": "745487f0"\n}`. It uses 2-space indent and `"key": value` on every line; nested objects and arrays open on the same line as their key.

**JSON escaping:** `jsonEncode({'a':'ä/ü"\\\n\t\u0001\u007f€😀'})` yields `{"a":"ä/ü\"\\\n\t\u0001<raw DEL>€😀"}`, so `/`, non-ASCII and DEL are left unescaped.

**Share text** (2 badges A and B, no npub, export date 20.10.2026), as a JSON-quoted string:

```
"🏆 MEINE EINUNDZWANZIG REPUTATION\n\nTotal Badges: 2\nMeetups besucht: 2\n\n\n📍 Besuchte Meetups:\n  • Aschaffenburg (15.3.2025)\n  • München, DE (11.10.2026)\n\n✅ Proof of Attendance\nVerifizierbar über die Einundzwanzig Meetup App\n\n---\nExportiert am 20.10.2026\n    "
```

### 10.3 Formatting vectors

- **Block (wallet):** −5 → `---`, 0 → `---`, 7 → `7`, 999 → `999`, 1000 → `1.000`, 875000 → `875.000`, 1234567 → `1.234.567`.
- **agoLabel(days):**

  | Days | Result |
  |---|---|
  | −3, 0 | today |
  | 1 | yesterday |
  | 2 | days(2) |
  | 29 | days(29) |
  | 30, 59 | months(1) |
  | 60 | months(2) |
  | 359 | months(11) |
  | 360, 364 | **years(0)** |
  | 365, 729 | years(1) |
  | 730 | years(2) |

  Duration.inDays truncates: 23 h 59 min gives 0, and 30 h in the future gives −1.
- **Group key sort** (Dart `String.compareTo`): `["\u0000organizer","?","Berlin","Ulm","Zürich","aachen","Ärzte"]`. Uppercase sorts before lowercase and umlauts sort after `z`; this is the same as Kotlin `String.compareTo`. In the app the organizer key is then moved to the end.
- **Case mapping:** Dart `'straße münchen'.toUpperCase()` gives `STRAßE MÜNCHEN` (ß is kept), while Kotlin `uppercase()` gives `STRASSE`. Display only. To be pixel-identical use `DartString.upper` (spec 02 §8.14), which also keeps Dart's other differences (no special casing, older Unicode table); never `uppercase()` / `lowercase(Locale.ROOT)` for keys or comparisons.

### 10.4 Generative art vector

Seed `"Aschaffenburg:875000"` gives SHA-256 `1301d904d84e84964275b1b51b87b762e69c82b0645e30c2f012f3d2ec7002c8`.

- Background: color(0,0.3) = **(133,30,43,a 0.3)**, color(3,0.15) = **(126,105,16,a 0.15)**, color(6,0.25) = **(190,82,13,a 0.25)**
- shapeCount = **8**
- gridSize = **14**

Shapes (cx and cy are fractions of w and h; r = 10 + k·w·0.35):

| i | idx | type | cx | cy | k | color (rgba) | style |
|---|---|---|---|---|---|---|---|
| 0 | 10 | 1 rhombus | 0.694118 | 0.709804 | 0.105882 | (212,93,5,0.15) | fill |
| 1 | 13 | 3 lines | 0.529412 | 0.717647 | 0.384314 | lines (191,93,20,0.06) | (stroke) |
| 2 | 16 | 2 hexagon | 0.901961 | 0.611765 | 0.509804 | (239,84,26,0.08) | stroke |
| 3 | 19 | 0 circle | 0.690196 | 0.392157 | 0.368627 | (212,64,19,0.12) | fill |
| 4 | 22 | 0 circle | 0.188235 | 0.760784 | 0.941176 | (148,97,48,0.08) | fill |
| 5 | 25 | 2 hexagon | 0.070588 | 0.952941 | 0.823529 | (133,114,42,0.08) | stroke |
| 6 | 28 | 0 circle | 0.925490 | 0.439216 | 0.007843 | (242,69,0,0.10) | fill |
| 7 | 31 | 0 circle | 0.784314 | 0.074510 | 0.003922 | (224,36,0,0.09) | fill |

Rounding checks for `color()`:

| byte | r, g, b |
|---|---|
| 0 | 124, 30, 0 |
| 1 | 124, 30, 0 |
| 2 | **125**, 30, 0 (half-up; banker's rounding would give 124) |
| 3 | 125, 30, 1 |
| 254 | 251, 118, 51 |
| 255 | 251, 119, 51 |

Alpha `0.08 + 7*0.01 = 0.15000000000000002` (a double; use it as is).

### 10.5 Suggested native unit tests

- `WalletLibraryTest`:
  - grouping order (meetup by count, then key; year descending; organizer last; `'?'` for blank names)
  - filter trimming and case
  - collection subtitle (count 1 versus more than 1)
  - count dots hidden above 24
- `DuplicateDetectionTest`:
  - two structurally **identical** badges count as 1 duplicate (identity semantics)
  - a rolling-QR variant with a claim beats one without
  - an organizer marker is not a duplicate of the participant badge
  - `removeDuplicates` keeps the order of `all`
- `DartDateTimeTest`: all the parse/format rows above.
- `VerificationHashTest`, `ReputationExportTest` (checksum `745487f0` with an injected clock) and `ShareTextTest`.
- `BadgeArtSpecTest`: the 10.4 vector.
- `GuideTourTest`: the wallet tour persists `guide_completed_wallet`; the event tour step count is 2 or 6 depending on the right.
- `CrestRepositoryTest`: `absoluteImageUrl` (`/storage/x.png` → `https://portal.einundzwanzig.space/storage/x.png`, `x.png` → `…/x.png`, `https://a` unchanged, `""` → `""`), and `logoFor` with exact, contains, contained-in and first-insertion-wins cases.

---

## 11. Android capabilities and recommended APIs

| Need | Recommendation |
|---|---|
| Network (tiles, images, portal) | `INTERNET` (already declared). OkHttp shared client. |
| Image loading (crests, covers) | **Coil 3** (`AsyncImage` / `SubcomposeAsyncImage` with an error/loading slot for the fallback). Memory and disk cache. Custom `ImageRequest` without auth headers. |
| Map | **osmdroid** (`org.osmdroid:osmdroid-android`) in `AndroidView`, OSM Mapnik tile source, `Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID`, rotation gestures disabled, `zoomToBoundingBox(bbox, animate = false, borderSizePx = 60dp)` capped at zoom 12, single point at zoom 11, `CopyrightOverlay` for attribution. Alternative: MapLibre Native Android with an OSM raster style and `MapSnapshotter`. Markers: custom `Marker` drawable (orange circle, white border, bolt). |
| Map screenshot | osmdroid `MapView` is a normal View: `mapView.drawToBitmap()`, then draw the stats bar onto the same `Canvas`, or capture the Compose container with `rememberGraphicsLayer()` and `toImageBitmap()` after verifying that the AndroidView content is captured. Scale to about 2.5 px per dp. |
| Share-card PNG | Compose: render `BadgeShareCard` off-screen in a fixed `360.dp × 560.dp` box under `CompositionLocalProvider(LocalDensity provides Density(3f, fontScale = 1f))`, so the output is 1080×1680 independent of the device. Use `rememberGraphicsLayer()`, `drawWithContent { layer.record { this@drawWithContent.drawContent() }; }` and `layer.toImageBitmap()`, or a hidden `ComposeView` attached to the window and `View.drawToBitmap()`. Encode with `Bitmap.compress(PNG, 100, …)`. Preload crest images (`allowHardware(false)` for software canvases). |
| Share | `Intent.ACTION_SEND` + `Intent.createChooser`. Text: `type = "text/plain"`, `EXTRA_TEXT`, `EXTRA_SUBJECT`. Image: write to `cacheDir/shared/<name>.png`, `FileProvider.getUriForFile(ctx, "${applicationId}.fileprovider", file)`, `type = "image/png"`, `EXTRA_STREAM`, `EXTRA_TEXT`, `EXTRA_SUBJECT`, `FLAG_GRANT_READ_URI_PERMISSION`, `ClipData.newUri` for the grant. Clean up old files. |
| Clipboard | `ClipboardManager.setPrimaryClip(ClipData.newPlainText(...))`. Keep the in-app snackbar even on Android 13+, where the system also shows a toast. |
| Persistence for tour flags | Jetpack DataStore Preferences with the same key names. If migrating the existing Flutter install, read `FlutterSharedPreferences.xml` keys prefixed `flutter.` through the platform-migration domain. |
| Hashing | `java.security.MessageDigest.getInstance("SHA-256")`. |
| JSON | kotlinx.serialization `JsonObject` (ordered) or a hand-written Dart-compatible emitter (7.3). |
| Animations | Compose `rememberInfiniteTransition` for the empty-state pulse. Respect `Settings.Global.ANIMATOR_DURATION_SCALE == 0` (reduce motion). |
| Material 3 Expressive | Use M3 `TopAppBar`, `ModalBottomSheet`, `AlertDialog`, `FilterChip` (group chips), `SnackbarHost`, `LazyVerticalGrid`, `LazyColumn`. Keep the custom dark palette tokens (section 2). |

No camera, NFC, location, Keystore or notification permissions are needed in this domain. The map shows only stored coordinates and does not use the device location.

---

## 12. Cross-domain dependencies

**Calls from this domain to others**

- **Badge model and storage** (`MeetupBadge`, `BadgeRepository`):
  - `badges` (StateFlow of the global collection)
  - `loadBadges()`, `saveBadges(list)`, `formerCollections()`, `mergeFormer(index)`
  - `unionByIdentity`, `unionKey`, `identityKey`, `getVerificationHash`, `generateBadgeProof(V2)`, `getReputationStats`, `countVerifiedBadges`, `countBoundBadges`
  - `isNostrSigned`, `isClaimed`, `isFullyBound`, `hasCryptoProof`
  - `badgesUnreadable`
  - the `DartDateTime` model
- **Reputation publisher**: `publishInBackground(badges)` (kind 30078) after cleanup or merge.
- **Badge sync**: triggered implicitly through the `onBadgesSaved` hook inside `saveBadges`.
- **User profile / identity**: `UserProfile.load()` gives `nickname`, `telegramHandle`, `twitterHandle`, `nostrNpub` (resolution through SecureKeyStore, the external signer and prefs).
- **Nostr utils**: `NostrService.shortenNpub(npub, chars = 8)`.
- **Calendar / portal**: `MeetupCalendarService.portalLogos`, `fetchMeetupsPortalFirst()`, `logoFor()`, `absoluteImageUrl()`, and `PortalApiService.getAllMeetupEvents()` / `getToken()`. Proposed native home: a `CrestRepository` in this domain that wraps the calendar repository.
- **Navigation targets**:
  - `ReputationQRScreen` (reputation/QR domain; no arguments)
  - `CalendarScreen` (calendar domain; called with no arguments from the empty state; it optionally accepts `initialSearch` / `initialMeetupId`)
- **Guide / tour framework**: `GuideService` (`wasTourCompleted`, `startTour`, `finishTourNow`, `finishTour`, `activeTour`), `GuideStep`, the overlay with anchor registration (replacing Flutter GlobalKeys), and the `GuideTour` enum.
- **Logger**: `AppLogger.diag/warn` with tag `Wallet`.

**Calls from other domains into this one**

- **App shell**: bottom-nav tab 1 hosts `WalletScreen`.
- **Scan / verification** (`MeetupVerificationScreen`): navigates to the wallet after a saved badge, a duplicate, or "open wallet".
- **Radar screen**: `BadgeDetailsScreen(badge)`.
- **Event editor** (calendar domain): `EventBadgeTour.steps(mayCreateBadge)` and anchors `basics`, `whenWhere`, `switch`, `image`, `location`, `issuers`; `EventBadgeAuthService.myRight()` decides `mayCreateBadge`.
- **Possible reuse**: `BadgeArt` and the crest composables could serve other screens (home tiles, reputation card). Today they are used only here.

---

## 13. Proposed Kotlin package / module structure and public API

```
:feature:wallet  (package space.einundzwanzig.meetup.feature.wallet)
  WalletRoute.kt                 // nav destinations: Wallet, Collection(key), BadgeDetails(badgeRef), WorldMap
  WalletViewModel.kt
  WalletScreen.kt                // top bar, library controls, grouped list, empty/no-result states
  CollectionScreen.kt
  components/BadgeCard.kt        // NormalBadgeCard, CompactBadgeCard, CollectionCard, SectionHeader, CountDots
  components/ShareReputationSheet.kt
  components/FormerCollectionsSheet.kt
  components/CleanupDialog.kt
  domain/WalletLibrary.kt        // pure grouping/filter/duplicates/numbering
  domain/BadgeFormat.kt          // all formatters (8.3, 8.4)
  domain/ReputationShare.kt      // share text (8.7) + JSON export (8.8)
  domain/DartJson.kt             // Dart-compatible compact/pretty JSON emitter
  details/BadgeDetailsScreen.kt
  details/BadgeDetailsViewModel.kt
  share/BadgeShareCard.kt        // fixed-size composable
  share/ComposableRasterizer.kt  // offscreen render -> PNG bytes
  share/ShareLauncher.kt         // intents + FileProvider + clipboard fallback
  map/BadgeWorldMapScreen.kt
  map/WorldMapViewModel.kt
  map/OsmMapView.kt              // osmdroid wrapper + snapshot
:core:ui  (package space.einundzwanzig.meetup.core.ui)
  badgeart/BadgeArtSpec.kt       // pure, testable
  badgeart/BadgeArt.kt           // DrawScope.drawBadgeArt + @Composable BadgeArt(seed)
  crest/CrestRepository.kt
  crest/MeetupCrestWatermark.kt
  crest/MeetupCrestFace.kt
:feature:guide (owned by guide domain) — tours/WalletTour.kt, tours/EventBadgeTour.kt live here or in :feature:wallet / :feature:calendar
```

Key public APIs:

```kotlin
// --- domain/WalletLibrary.kt
object WalletLibrary {
    const val ORGANIZER_GROUP_KEY = "\u0000organizer"
    fun filter(all: List<MeetupBadge>, query: String): List<MeetupBadge>
    fun groups(filtered: List<MeetupBadge>, groupByMeetup: Boolean): List<Pair<String, List<MeetupBadge>>>
    fun duplicates(all: List<MeetupBadge>): List<MeetupBadge>          // identity-based (8.2)
    fun globalIndex(all: List<MeetupBadge>, badge: MeetupBadge): Int    // indexOfFirst { it === badge }
    fun cleanupSummary(dups: List<MeetupBadge>): LinkedHashMap<String, Int>
    fun showSearch(count: Int) = count >= 6
    fun showCompactToggle(count: Int) = count > 6
}

// --- domain/BadgeFormat.kt
object BadgeFormat {
    fun blockWallet(h: Int): String
    fun blockDetails(h: Int, unknown: String): String
    fun blockShareCard(h: Int): String                 // "" when <= 0
    fun dateShort(d: DartDateTime): String             // d.M.yyyy
    fun datePadded(d: DartDateTime): String            // dd.MM.yyyy
    fun scanTime(epochSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String // "dd.MM.yyyy  HH:mm"
    fun formerTime(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String      // "dd.MM.yyyy HH:mm"
    fun shortNpubDetails(npub: String): String
    fun shortSigId(sigId: String): String
    fun monthYearDe(d: DartDateTime): String
    fun agoLabel(now: Instant, then: Instant, s: WalletStrings): String
    fun collectionSubtitle(groupDesc: List<MeetupBadge>, now: Instant, s: WalletStrings): String
}

// --- domain/ReputationShare.kt
object ReputationShare {
    const val TEXT_SUBJECT = "Meine Einundzwanzig Meetup Reputation"
    const val JSON_SUBJECT = "Einundzwanzig Reputation (v4, signiert)"
    fun summaryText(all: List<MeetupBadge>, npub: String, today: DartDateTime): String
    fun exportJson(all: List<MeetupBadge>, userNpub: String, nickname: String = "", telegram: String = "",
                   twitter: String = "", exportedAt: DartDateTime = DartDateTime.now()): String
}

// --- core/ui/badgeart
data class BadgeArtSpec(val bg: List<RgbA>, val shapes: List<Shape>, val gridSize: Int) {
    data class RgbA(val r: Int, val g: Int, val b: Int, val a: Double)
    data class Shape(val type: Int, val cxF: Double, val cyF: Double, val rK: Double, val color: RgbA,
                     val stroke: Boolean, val lineColor: RgbA)
    companion object { fun from(seed: String): BadgeArtSpec; fun seedFor(b: MeetupBadge) = "${b.meetupName}:${b.blockHeight}" }
}
fun DrawScope.drawBadgeArt(spec: BadgeArtSpec)
@Composable fun BadgeArt(seed: String, modifier: Modifier = Modifier)

// --- core/ui/crest
interface CrestRepository {
    val logos: StateFlow<Map<String, String>>          // LinkedHashMap semantics (insertion order)
    suspend fun ensureLoaded()                          // single shared in-flight fetch, no-op if non-empty
    fun logoFor(titleOrCity: String): String
    fun absoluteImageUrl(url: String): String
}
@Composable fun MeetupCrestWatermark(meetupName: String, modifier: Modifier = Modifier,
    opacity: Float = 0.20f, widthFactor: Float = 1.05f, imageUrl: String? = null)
@Composable fun MeetupCrestFace(meetupName: String, imageUrl: String?, modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit)

// --- share
interface ShareLauncher {
    /** Returns false if sharing threw (caller then uses clipboard fallback). */
    fun shareText(text: String, subject: String?): Boolean
    fun sharePng(png: ByteArray, fileName: String, text: String?, subject: String?)
    fun copyToClipboard(label: String, text: String)
}
interface ComposableRasterizer {
    suspend fun renderPng(widthDp: Int, heightDp: Int, scale: Float, content: @Composable () -> Unit): ByteArray
}

// --- view models
class WalletViewModel(
    private val badges: BadgeRepository, private val publisher: ReputationPublisher,
    private val profile: UserProfileRepository, private val share: ShareLauncher, private val guide: GuideService,
) : ViewModel() {
    val state: StateFlow<WalletUiState>
    fun onQueryChange(q: String); fun clearQuery()
    fun setGroupByMeetup(v: Boolean); fun toggleCompact(); fun toggleCollapsed(key: String)
    fun shareText(); fun shareJson()
    suspend fun loadFormer(): List<FormerCollection>
    suspend fun mergeFormer(entry: FormerCollection): MergeResult   // Failed | Merged(added)
    fun duplicates(): List<MeetupBadge>
    suspend fun removeDuplicates(dups: List<MeetupBadge>): CleanupResult  // SaveFailed | Removed(n)
    fun maybeStartTour()   // 600 ms delay, wallet tour if not completed and nothing active
    fun onDispose()        // finishTourNow if wallet tour active
}
class BadgeDetailsViewModel(badge: MeetupBadge, rasterizer: ComposableRasterizer, share: ShareLauncher) {
    val verificationHash: String
    suspend fun shareImage(context: Context): Result<Unit>   // 1080x1680 PNG "einundzwanzig_badge.png"
    fun copyHash()
}
class WorldMapViewModel(badges: List<MeetupBadge>) {
    val state: StateFlow<WorldMapUiState>
    fun select(b: MeetupBadge?); suspend fun share(snapshot: suspend () -> ByteArray?)
}

// --- tours
object WalletTour { enum class Anchor { FirstBadge, Map, View, Share }; fun steps(): List<GuideStep> }
object EventBadgeTour { enum class Anchor { Basics, WhenWhere, Switch, Image, Location, Issuers }
                        fun steps(mayCreateBadge: Boolean): List<GuideStep> }
```

Navigation: pass a badge reference to Details (for example `unionKey + id`, or an in-memory handle through a shared ViewModel) rather than serializing the whole badge. The details screen must show the exact object that was tapped, including duplicates, which share a union key; an in-memory handle avoids ambiguity.

---

## 14. Risks and compatibility pitfalls

1. **Date round-trip:** `getVerificationHash`, the legacy `proofId` and the export `date`/`exported_at` depend on Dart's `toIso8601String()`, which has optional microseconds, `Z` only for UTC and no offset for local values. Using `java.time` defaults such as `LocalDateTime.toString()` (drops `.000`, prints nanoseconds) or `Instant.toString()` silently breaks every hash and union `nd:` key across devices. Use `DartDateTime` (3.2) everywhere the badge date is stored, serialized or hashed.
2. **Display timezone:** Dart shows a UTC badge date in UTC fields. Converting to the device zone would shift days and years, which changes the displayed date, the year grouping and `nd:` identity keys.
3. **Identity versus structural equality:** with a Kotlin `data class`, `contains`/`indexOf`/`minus` make exact-duplicate badges indistinguishable. The cleanup button would then not appear, and the wrong item could be removed or numbered. Always use identity (`===`).
4. **Rounding in the art painter:** `kotlin.math.round` is half-even, but Dart is half-away-from-zero. Wrong rounding changes colors for even bytes. Also do not convert alpha through `(a*255).toInt()` too early; use the float alpha.
5. **JSON export byte-exactness:** key order, Dart escaping (no `\/`, raw Unicode) and 2-space pretty printing determine the checksum. `org.json` and Gson (which by default HTML-escapes `<`, `>`, `=`, `'`, `&` as `<` and similar) produce different output. `badge-verifier.html` depends on the key names.
6. **"Signed" wording:** keep the export format (unsigned) for compatibility, but do not add a signature field silently. A new field would alter the checksum semantics for other tools only if they recompute it, and none currently do. Any change must be additive and versioned.
7. **Dart sort stability:** proof hashes can differ for tied sort keys in lists of 34 or more elements (Dart uses stable insertion sort up to 33 elements). This is acceptable (8.6/7.2); document it and do not "fix" it in one implementation only if hashes are ever compared across apps.
8. **Wallet-tour timing quirk** in Flutter (IndexedStack). Native should start it on first visibility; the persisted key `guide_completed_wallet` is unchanged.
9. **Share-card size:** the comments say 360×480 / 1080×1440, but the code is **360×560 / 1080×1680**. Follow the code. Force fontScale 1 and density 3 for deterministic output.
10. **Crest loading:**
    - The registry fills only from upcoming portal events, only once per process, and only if it is empty.
    - `MeetupCrestFace` does not trigger a fetch.
    - Relative cover URLs are not absolutized in the watermark.
    - Matching is a substring match in either direction, first insertion wins, so short names can match the wrong crest.

    Native should keep the same matching but make the registry observable. Changes are display only.
11. **OSM policy:** Flutter shows no attribution and uses the UA `flutter_map (space.einundzwanzig.meetup)`. Native must set an identifying UA and show attribution, or risk being blocked.
12. **Former-archive index:** `FormerCollection.index` is the position in the **stored (oldest-first)** list, while the sheet shows newest first. Passing the display position to `mergeFormer` merges the wrong archive.
13. **Unreadable collection** looks like an empty wallet. Never wire an empty-state action that writes an empty list.
14. **Crash guards** to add natively without changing behavior: `sigId.length < 12` (details), `sig.length < 64` in `proofId` (export). Flutter throws a RangeError in both. Native should fall back gracefully, for example use the whole string, but the hash inputs for valid data must stay unchanged.
15. **Hard-coded German strings:** "BADGE WALLET", Normal/Kompakt, share subjects and body, German month abbreviations in every locale, "HMAC (Legacy v1)", "Nostr Event-ID". Keep them for parity, or localize consciously. They are not part of any wire format except as human-readable text.
16. **`presenceVerified` defaults to true** when missing from JSON, and `delivery` defaults to `"nfc"`. Mapping a missing value to false would mark old badges as unverified.
17. **Year grouping and `isOrganizer`:** organizer markers always go to their own group, even in year mode, and that group stays last. Also, the compact toggle has no effect in meetup mode (collection cards). This is intended; do not "fix" it by applying compact to collection cards without a design decision.
