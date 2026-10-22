# 05 — Social Graph, Co-Attendance Trust Network & Organizer/Admin

Porting spec for the native Android (Kotlin, Jetpack Compose, Material 3 Expressive) rewrite.
Source of truth: Flutter app at repo root, commit `623bdc9` (branch `t3/explain-flutter-choice`).

Files read completely for this spec:

| Dart file | Lines | Role |
|---|---|---|
| `lib/services/social_graph_service.dart` | 444 | NIP-02 follow graph, mutuals, org followers, social score |
| `lib/services/coattendance_service.dart` | 1862 | Co-attendance events (kind 30079), graph crawl, cache, BFS degrees, labels |
| `lib/screens/my_network_screen.dart` | 1541 | "Meine Vernetzung" screen (trust network UI) |
| `lib/services/admin_registry.dart` | 1013 | Organizer registry (kind 30078 `einundzwanzig-admins`), super-admin sunset |
| `lib/services/admin_status_verifier.dart` | 153 | Self admin-status verification (trust score / super admin) |
| `lib/services/promotion_claim_service.dart` | 591 | Admin claims (kind 30021), claim verification (K1) |
| `lib/screens/admin_panel.dart` | 1017 | Organizer panel: start/end meetup session |

Helpers read to understand behavior (owned by other domains, summarized where needed):
`relay_socket.dart`, `relay_config.dart`, `meetup_key.dart`, `badge_claim_service.dart`, `badge_security.dart` (verify/normalize parts),
`models/badge.dart` (fields + save/keepAside), `nostr_service.dart` helpers, `widgets/npub_chip.dart` (`shorten`), `haptic_service.dart`,
`nostr_profile_service.dart` (batch names), `screens/verify_person_screen.dart`, `meetup_location_service.dart`, `rolling_qr_service.dart` (session keys),
`app_logger.dart` (`RelayParseTally`), `features.dart`, `models/user.dart` (admin flags), `home_screen.dart` (callers), `meetup_verification.dart` (callers),
`nostr-1.5.0` package (`Event.isValid`, `Nip19`), `shared_preferences_android-2.4.20` (storage format).

---

## 1. Overview and user-visible features

This domain covers four related things:

1. **Nostr social graph (NIP-02).** Reads kind-3 contact lists from relays to work out follows, mutual follows, common contacts, how many known organizers follow someone, and a hop distance. The output feeds the reputation check UIs (scanner and reputation-verify screens, owned elsewhere) and the aggregated `social` stats in the published reputation event.
2. **Co-attendance trust network.** This is opt-in. After scanning a real, organizer-signed badge, a participant may publish a parameterized-replaceable kind-30079 event that says "my npub attended session X". Organizers publish one automatically when they start a session. The app crawls these events in up to 3 stages and builds an undirected graph (edge = shared meetup). From that it computes degree 1/2/3 contacts with bridges and shortest paths. It shows the result in "Meine Vernetzung" and answers "Person prüfen" (presence check).
3. **Organizer registry / admin status.** Organizer lists are signed kind-30078 events (`d=einundzwanzig-admins`) published by a hard-coded super admin, or by all known admins once the network has matured past the bootstrap "sunset". Lookups are cached for offline use. The domain also covers verification of the user's *own* admin status (trust score or super admin), and "organic" admin claims (kind 30021) that are verified but grant **no** rights.
4. **Organizer panel.** Starts and ends a 4-hour meetup session (rolling QR). Steps: resolve GPS, pick a portal meetup, confirm. The organizer is auto-enrolled in the co-attendance network with an organizer marker badge.

### 1.1 Complete user-visible feature list

Trust network screen ("Meine Vernetzung", `MyNetworkScreen`):
- Opens from the Home tile "Vertrauensnetzwerk" (`tileActEncounters` / `tileTrustNetwork` / `tileTrustNetworkSub`). The tile is always visible.
- Shows the cached network at once (no spinner when a cache exists), then refreshes in the background degree by degree, with live updates after each stage.
- A first-load spinner with progress text: "Netzwerk wird aufgebaut..." (`mnLoading`), then "Aktualisiere {n}. Grad …" (`mnUpdating`).
- Pull-to-refresh forces a FULL re-sync (bypasses the incremental mode). A full re-sync also happens automatically every 24 h.
- Reach card: total reach (big number), "Personen aus {n} Meetups", a 3-colour proportional distribution bar, a "{d1} direkt getroffen · {d2} über Kontakte · {d3} eine Ebene weiter" line, and a status chip in the top-right. The chip shows one of: updating spinner, "Gespeicherter Stand" with a cloud-off icon (tooltip explains the failure), or "Stand HH:mm" / "Stand dd.MM. HH:mm".
- A failed-publication banner (orange). It shows when some of the user's opted-in attendance publications reached 0 relays. It has an "Erneut senden" button with an inline spinner, and the result appears in a snackbar ("{fixed} von {total} jetzt veröffentlicht.", green if fixed>0, else red). The network reloads afterwards.
- Tree graph canvas (300 dp tall): "Du" in the centre with a pulsing glow (3 s period, reversing). Up to 8 direct contacts sit on an inner ring with their initials. Up to 3 degree-2 children per direct contact and up to 2 degree-3 grandchildren per child are drawn in the same angular sector. Lines connect each person to their BFS parent. Tapping a node (24 px tolerance) gives a light haptic and opens the person detail sheet. While the detail is open, the path to that person is highlighted and everything else is dimmed. The footer shows "{shown} von {total} direkten Kontakten · Ausschnitt" when more than 8 direct contacts exist, plus "Antippen zeigt den Weg".
- Degree filter row: 4 equal chips, each showing a count and a label (All / Direkt / 2. Grad / 3. Grad). Tapping a chip gives a light haptic.
- Search field: filters by name or npub substring, case-insensitive. It has a clear (×) button.
- One section per degree: an uppercase coloured header "TITLE · count", then a card list with avatar (initial or person icon, ring coloured by degree), name (if known), short npub (monospace), and a "how you know them" detail line. Each section shows the first 20 entries with an "Alle {n} anzeigen" expander; with an active search, all matches show. Empty filter/search results show "Niemand gefunden", or "—" when there is no query.
- Display names come from Nostr profiles (kind 0, falling back to reputation nickname). Cached names show at once and missing ones are batch-fetched. The cap is 400 names per screen session.
- "So entsteht dein Netzwerk" row and an app-bar info icon. Both open an explanation bottom sheet (intro, event note, trust hint, privacy note).
- App-bar "person search" icon: navigates to "Person prüfen" (VerifyPersonScreen).
- Person detail bottom sheet contents:
  - Avatar, name or short npub, and a degree pill.
  - npub with a copy button. Copying uses a medium haptic, and the icon turns into a green check for 2 s instead of showing a snackbar.
  - "DEIN WEG ZU {name}": Du → A → B with the most recent shared meetup per link ("gemeinsam bei Würzburg, 12.09.2026").
  - For direct contacts, all shared meetups, newest first.
  - "AUCH ERREICHBAR ÜBER": other bridges, up to 12 chips.
  - Buttons: "In Nostr öffnen" (a `nostr:` URI with an njump.me fallback) and "Schließen".
- Empty state shows a hub icon, "Noch keine Vernetzung" and the subtext, an event note info box, a "saved state" hint (if the update failed), and the failed banner (if any).
- If the user has no npub, the empty state shows. There is no special message.

Person check ("Person prüfen", `VerifyPersonScreen`, adjacent screen; the service is in this domain):
- Enter an npub (with or without a `nostr:` prefix; input is lower-cased) or scan a QR code (raw npub or reputation-QR JSON `{"id":{"np":"npub1…"}}`). It shows the degree (self / direct with shared-meetup count / N hops / none / not in network) and a tappable path. Tapping a path node opens it in Nostr. An invalid npub shows "Ungültiger npub".

Organizer panel (`AdminPanelScreen`):
- Opens from the Home tile "Organisator" (`tileActManage` / `tileOrganizer` / `tileOrganizerPanel`). The tile is visible only if `user.isAdmin || user.isReviewDemo`. On return, Home re-checks the active session.
- Status header: verified icon, then "Via Trust Score" if promotion source = `trust_score`, else "ORGANISATOR". Next to it is an `NpubChip` of the own npub (tap opens njump, long-press copies, and the display name is prepended).
- Session section with the header "MEETUP SESSION":
  - When a session is active: a green "SESSION LÄUFT" dot, a live countdown `Hh MMm SSs` that updates every second, the button "AKTIVES MEETUP ÖFFNEN" (opens the Rolling QR screen), and "Meetup vorzeitig beenden" (red text). The end action asks for confirmation ("Session beenden?") and then ends the session.
  - When no session is active: the tile "MEETUP STARTEN" with the subtitle "Generiert einen neuen kryptographischen Beweis für die nächsten 4 Stunden." It asks for confirmation ("Neues Meetup starten?") and then runs the start flow (section 2.2.3).
- Info box "SO FUNKTIONIERT'S" with 4 steps, plus a cyan "Zweiter Organisator dabei?" cross-confirmation hint.
- Start flow dialogs, with full details in section 2.2.3: a blocking spinner during GPS; a soft "continue without location" dialog with settings / retry / continue; a multiple-nearby picker bottom sheet; a searchable portal meetup picker ("own meetups first", with a star marker and a coordinate indicator); a confirmation ("Bist du hier?") showing the city in large orange capitals; "Kein Bezugspunkt" confirmation; "Standort nötig" for custom names without GPS; a manual name dialog with validation; and an error snackbar "Fehler: {msg}".

Background / invisible features (no UI, but the behavior must be preserved):
- On every Home load: `PromotionClaimService.syncOrganicAdmins()` fetches and verifies kind-30021 claims and stores them. This is information only.
- Home: when the user becomes an organizer via the portal, or is re-verified as an admin via trust score, the app publishes the user's own admin claim (kind 30021).
- Home: when portal organizer status is revoked, the app removes the own npub from the admin registry cache.
- Scanner (other domain) asks the user for co-attendance opt-in after a signed badge scan, then calls `publishAttendance`. It shows a snackbar for success or failure (`caPublished` / `caPublishFailed`).
- Badge scanner and event-badge chain check signers with `AdminRegistry.checkAdminByPubkey`. The result can be "unavailable", which is distinct from "unknown signer".
- Reputation publisher embeds `SocialStats` (`follows`, `mutuals`, `org_followers`).
- Backup includes the admin registry cache (`admin_registry` array). On restore it re-adds the entries and forces a relay re-validation.

Haptics (all go through HapticService and respect the `haptic_enabled` pref):
- Graph node tap: `light` (selectionClick).
- Filter chip tap: `light`.
- Copy npub in detail: `medium` (mediumImpact).
- VerifyPerson QR detected: `medium`.

Share actions: none in this domain. External intents are `nostr:<npub>` (fallback `https://njump.me/<npub>`), the location settings, and the app settings.

Tours / guide hooks: none. `GuideService` has no steps referencing these screens.

---

## 2. Screens & UX flows

Colour constants (Flutter `theme.dart`): `cDark #0C0C0E`, `cCard #131315`, `cSurface #0F0F11`, `cOrange #F7931A`, `cCyan #D4A017` (a warm gold, despite the name), `cGreen #22C55E`, `cRed #E53935`, `cText #FFFFFF`, `cTextSecondary #9CA0A8`, `cTextTertiary #868A94`, `cTileBorder #202028`, `kTileRadius 10`. Degree colours: 1 = `cGreen`, 2 = `cCyan` (gold), 3 = `cOrange`; "me" = `cOrange`.

### 2.1 MyNetworkScreen ("Meine Vernetzung")

**Entry point:** the Home tile `trust_network` (span 2), via `Navigator.push(MyNetworkScreen())`. It takes no arguments.

**State:**

| field | meaning |
|---|---|
| `_loading` (init `true`) | first-load spinner |
| `_net: MyNetwork?` | what is displayed |
| `_failedCount` | number of opted-in badges with publish status 0 |
| `_retrying` | retry in progress (spinner in banner) |
| `_updating` | background refresh running |
| `_doneDepth` (0..3) | last finished stage |
| `_updateFailed` | last refresh had no relay answer; showing saved state |
| `_names: Map<npub,String>` | display names |
| `_namesAsked: Set<npub>` | names already requested (each person once) |
| `_filter` (0 = all, 1..3) | degree filter |
| `_query` | search text |
| `_expanded: Set<int>` | degrees whose list is fully expanded |
| `_highlight: npub?` | person whose detail is open (path highlighted) |

Constants: `_maxNames = 400`, `_listPreview = 20`, `_graphDirect = 8`. A pulse animation controller runs for 3 s and repeats in reverse.

**Load algorithm (`_load({forceFull=false})`):**
1. `user = UserProfile.load()`. If `user.nostrNpub` is empty, set `_loading=false, _net=null` and stop. The empty state is shown. Note: the npub comes from the profile pref `nostr`, not from SigningService.
2. If `_net == null`, run `cached = CoAttendanceService.cachedNetwork(myNpub)`. If it is non-null, set `_net = cached`, `_loading = false`, and call `_loadNames(cached)`.
3. Set `_updating = true, _doneDepth = 0, _updateFailed = false`.
4. In parallel, run `failedFuture = MeetupBadge.loadBadges().then(CoAttendanceService.failedBadges)`; errors produce `[]`.
5. `net = await CoAttendanceService.buildMyNetwork(myNpub, forceFull, onStage: (partial, depth) => set _net = partial, _doneDepth = depth, _loading = false)`.
6. After completion, update state:
   - Replace `_net` with `net` unless `net.stale && net.isEmpty && _net != null`. In that case keep what is already shown.
   - `_failedCount = failed.length`, `_updating = false`, `_updateFailed = net.stale`, `_loading = false`.
   - `_loadNames(_net)`.
7. On any exception: `_loading = false, _updating = false, _updateFailed = (_net != null)`.

**`_loadNames(net)`:**
- Iterate `net.contactsByNpub.keys` (degree order 1, 2, 3; list order within each degree).
- Stop at 400 collected. Skip npubs already asked.
- Convert each to hex with `npubToHex`, skipping failures. Mark all collected npubs as asked.
- Apply `NostrProfileService.cachedDisplayNames()` (hex→name), then `NostrProfileService.fetchDisplayNames(hexes)`. Only non-empty names are applied. Both calls belong to the profile domain.

**`_retry()`:**
1. Set `_retrying = true`.
2. `badges = loadBadges()`; `fixed = CoAttendanceService.retryFailed(badges)`.
3. Set `_retrying = false`.
4. Show a SnackBar with `mnRetryResult(fixed, _failedCount)`, background green if fixed>0, else red.
5. Call `_load()` (not forced).

**Body selection (inside a RefreshIndicator whose onRefresh is `_load(forceFull:true)`; colour orange, background card):**
- if `_loading || ((_net == null || _net.isEmpty) && _updating)`: **FirstLoad**
- else if `_net == null || _net.isEmpty`: **Empty**
- else: **Content**

**FirstLoad:** a ListView (padding 20) containing a 120 dp spacer, a centred orange CircularProgressIndicator (stroke 2), 18 dp, then the text `mnLoading` if `_doneDepth==0`, else `mnUpdating(clamp(_doneDepth+1,1,3))`, in secondary 13 sp, centred.

**Empty**, in a ListView (padding 20), in order:
1. If `_updateFailed`: a savedStateHint row (cloud_off icon 14 in tertiary, plus `mnUpdateFailed` in tertiary 12 sp), then 14 dp.
2. If `_failedCount > 0`: the failedBanner.
3. 60 dp spacer, `hub_outlined` icon 56 dp tertiary, 20 dp.
4. `mnEmpty` (17 sp bold, centred), 10 dp.
5. `mnEmptySub` (13 sp secondary, centred, line height 1.5), 24 dp.
6. infoBox(`mnEventNote`, info_outline).

**Content**, in a ListView (padding L20 T8 R20 B32), in order:
1. failedBanner (if `_failedCount > 0`)
2. reachCard, 16 dp
3. graphCard, 16 dp
4. filterRow, 10 dp
5. searchField, 6 dp
6. degree sections
7. 18 dp, then the howItWorksRow

**failedBanner:**
- A container with margin-bottom 16, padding (14,12,10,12), orange background at 10 %, orange border at 45 % (0.5), radius 10.
- Row: cloud_off icon (orange 18), then `mnFailedBanner(_failedCount)` (ICU plural; 12.5 sp, line height 1.4).
- Trailing: a spinner (18×18) if `_retrying`, else a TextButton `mnRetry` (orange, bold).

**reachCard:**
- Card (radius 14, border tileBorder 0.5, padding 18/16).
- Row: `mnReachTitle` (11 sp, w600, letterSpacing 1.2, secondary) followed by the statusChip.
- Baseline row: `totalReach` (44 sp bold, height 1), then `mnReachSub(myMeetupCount)` (15 sp secondary).
- Distribution bar (height 8, radius 4): flexed segments d1 = green, d2 = gold, d3 = orange at 60 %, with 2 dp gaps between present segments. If all three are 0, the bar is a single `cSurface` fill.
- `mnReachSplit(d1,d2,d3)` (13 sp secondary).

**statusChip:**
- If `_updating`: a 10×10 orange spinner (stroke 1.6), then `mnUpdating(clamp(_doneDepth+1,1,3))`.
- Else if `_updateFailed`: Tooltip(`mnUpdateFailed`) around cloud_off (13 tertiary) plus `mnSavedState`.
- Else if `net.updatedAt != null`: `mnStandAt(time)`. The time is `HH:mm` when the date is the same local day as now, else `dd.MM. HH:mm` (zero-padded).
- Else: nothing.

**graphCard:**
- Container with a gradient from TL `#15171C` to BR `#0D0E12`, radius 14, border tileBorder 0.5, clipped.
- A 300 dp tall canvas (full width) with a tap handler.
- Footer (padding 14,0,14,12): if `degree1Count > 8`, show `mnGraphExcerpt(min(d1,8), d1)` (12 sp secondary); else a spacer. Then `mnTapShowsPath` (12 sp tertiary) on the right.

**Tree layout (`_layoutTree(net, size)`), exact:**
- `center = (w/2, h/2)`. The first node is me, at the centre, radius 19.
- `direct = byDegree[1].take(8)`. If empty, return [me] only.
- `childrenOf[parent] += contact`, iterating byDegree[2] then byDegree[3], using `contact.parent`.
- `maxR = min(w,h)/2`; `r1 = 0.48*maxR`, `r2 = 0.77*maxR`, `r3 = 0.93*maxR`.
- `sector = 2π / direct.length`; `spread = sector * (direct.length <= 3 ? 0.22 : 0.32)`.
- For each direct contact `i`:
  - `a = -π/2 + i*sector`; place it at `center + r1*(cos a, sin a)`, radius 15, with `initial` and a line from the centre.
  - Kids = `childrenOf[c1].take(3)`. For each kid `j` (count `k`): `a2 = a + (j - (k-1)/2)*spread`; place it at radius `r2`, node radius 6, line from p1.
  - Grandkids = `childrenOf[kid].take(2)`. For each grandkid `q` (count `g`): `a3 = a2 + (q-(g-1)/2)*0.16` (radians); place it at radius `r3`, node radius 3.5, line from p2.

**Painter:**
- `dimming = highlight.isNotEmpty`, where `highlight = net.pathTo(_highlight).toSet()`.
- **Lines** are drawn by degree in order 3, 2, 1. Width is deg1 1.6 / deg2 1.2 / deg3 1.0. Base alpha is 0.6 / 0.5 / 0.4. When dimming, active lines are alpha 1.0 with width 3, and inactive lines are alpha 0.12. Stroke cap is round.
- **Nodes** are drawn by degree in order 3, 2, 1. Inactive nodes are alpha 0.18 when dimming. The target is the highlighted node with no highlighted successor of greater degree whose `from == pos`; it gets a halo at radius+8 in its colour at 25 %.
  - Degree 1: fill `cCard`, stroke in the degree colour (2, or 2.5 when active and dimming). Draw the initial (13 sp bold) if present, else a radius-3 dot in `cTextSecondary`.
  - Degree 2/3: filled circle. A target gets radius+3 and a white 2 px stroke.
- **Me:** a glow circle with radius `19+6+pulse*3`, orange alpha `0.14+pulse*0.08`, blur 6. Then a solid orange circle r = 19 and the "Du" text (14 sp bold, `cDark`).

**Tap:** find the nearest node, excluding me, within 24 px. If found, call `HapticService.light()` and `_openDetail`.

**filterRow:**
- 4 Expanded chips with 6 dp gaps; each is 52 dp tall with radius 10.
- Selected chip: background orange at 14 %, orange 1 px border. Unselected: card background with tileBorder.
- Each chip shows a count (17 sp bold, in the label colour) above a label (12 sp; text colour if selected, else secondary).
- Chips: (0, totalReach, `mnFilterAll`, text), (1, d1, `mnDirectLabel`, green), (2, d2, `mnDegreeSecond`, gold), (3, d3, `mnDegreeThird`, orange).
- Tap: light haptic, then set the filter.

**searchField:**
- Hint `mnSearchHint`, search icon prefix.
- Clear suffix (tooltip `mnClose`) shown only when the query is non-empty.
- Filled `cSurface`, radius 10; the focused border is orange.

**Sections:**
- `q = query.trim().lowercase()`. Titles: 1 = `mnDegree1`, 2 = `mnDegree2`, 3 = `mnDegree3`.
- For each degree d in 1..3, skip it if the filter is set and ≠ d.
- Filter the list by `npub.lowercase().contains(q) || name?.lowercase()?.contains(q)`. Skip the section if the result is empty.
- `showAll = q.isNotEmpty || d in _expanded`; `shown = showAll ? list : list.take(20)`.
- Header: `"${title.uppercase()} · ${list.size}"` in the degree colour, 11 sp w600, letterSpacing 1.2, padding (2,14,2,8).
- Card (radius 12, border 0.5) with rows. A 0.5 top divider separates rows; the first row has none.
- If `shown < list`, add a 48 dp footer `mnShowAll(list.size)` (14 sp bold, degree colour) with a top border; tapping it adds d to `_expanded`.
- If no section is produced: centred text, `mnNoResults` if q is non-empty, else `—` (14 sp tertiary, vertical padding 28).

**contactRow** (padding h14 v11):
- Avatar 40 with a 2 px ring in the degree colour, showing the name's initial (size*0.4, bold) or a `person_outline` icon (size*0.45, secondary).
- Column:
  - The name, if known (16 sp bold, 1 line, ellipsis).
  - Short npub `NpubChip.shorten(npub, head=10, tail=8)` (`head…tail`; returned whole if length ≤ 19), monospace. It is 14 sp `cText` when there is no name, else 12 sp tertiary.
  - The detail line, if non-empty (13 sp secondary, max 2 lines, top padding 2).
- A chevron. Tapping the row opens the detail.

**Detail line rules (`_detailLine`):**
- Degree 1: `n = shared.size`. If n == 0, return ''.
  - Part 1: `n==1 ? mnOneSharedMeetup : mnSharedMeetups(n)`.
  - Part 2: `newest = newestFirst(shared).first`; `mnLastAt(date==null ? place : "$place, dd.MM.")`.
  - Joined with `" · "`.
- Degree 3: `path = net.pathTo(npub)`. If `path.size >= 3`, return `mnVia(path[1..-1].map(label).join(" → "))`. Otherwise fall through to the bridges rule.
- Otherwise: `bridges = c.bridges.map(label).sorted()` (plain String compare).
  - 0 → `''`
  - 1 → `mnVia(b0)`
  - 2 → `mnVia("$b0 ${mnAnd} $b1")`
  - more → `mnVia("$b0, $b1 +${n-2}")`
- `label(npub)`: `mnYou` if it is me, else name ?? short npub.

**howItWorksRow:** Material `cSurface`, radius 12, border 0.5, padding 14. It contains a privacy_tip icon, `mnHowItWorks` (14 sp bold) over `mnHowItWorksSub` (13 sp secondary), and an expand_more icon. Tapping it opens the info sheet.

**Info sheet** (modal bottom sheet, card colour, top radius 20, scroll-controlled, max height 80 % of screen, SafeArea, padding 20/12/20/24):
- Grabber (40×4, `#3A3A44`, radius 2), 16 dp.
- `mnHowItWorks` (20 sp bold), 10 dp.
- `mnIntro` (14 sp secondary, line height 1.5), 14 dp.
- infoBox(`mnEventNote`, event_busy_outlined), 10 dp.
- infoBox(`mnTrustHint`, lightbulb, orange icon), 10 dp.
- infoBox(`mnPrivacyNote`, privacy_tip).
- infoBox styling: `cSurface`, radius 10, border 0.5, padding 12, icon 16, text 13 sp secondary with line height 1.5.

**Detail sheet (`_openDetail`):**
- Set `_highlight = npub`. Show a modal bottom sheet (card colour, barrier black 45 %, top radius 20, max height 75 %, padding 20/12/20/20). After it is dismissed, set `_highlight = null`.
- Body, in order:
  1. Grabber, 18 dp.
  2. Header row: avatar 52, then a column. The column holds a Wrap with the title and a degree pill. The title is the name (20 sp bold) or the short npub (16 sp mono). The pill uses the degree colour at 15 %, radius 6, and shows `mnDegreeDirect` / `mnDegreeSecond` / `mnDegreeThird` (12 sp bold). Below the Wrap is the **CopyNpub** row: the short npub (13 sp mono secondary) and an IconButton (tooltip `mnCopyNpub`). On tap, the button copies the full npub to the clipboard, fires a medium haptic, and switches its icon to a green check for 2 s, then back to a tertiary copy icon.
  3. Path. `path = net.pathTo(npub)`; if it is empty and the degree is 1, use `[me, npub]`. If `path.size >= 2`:
     - 20 dp, then `mnPathTo(label(npub)).uppercase()` (section style: 11 sp w600, letterSpacing 1.2, secondary), 10 dp.
     - Path nodes and links alternate.
     - Node (me): an orange circle 28 with `mnYou` (11 sp bold, dark) and the label `mnYou`. Node (other): avatar 28 plus the name or short npub (mono if there is no name), 15 sp bold.
     - Link (height 34): a 2 px vertical line, centred in a 28 dp column, coloured by the degree of the next node (default 1). Then `mnTogetherAt(label(sharedBetween(a,b).first))` (13 sp secondary, 1 line), shown only if a shared meetup exists.
  4. Shared meetups, for degree 1 only: 20 dp, `mnSharedMeetupsList.uppercase()`, 8 dp. Each key in `newestFirst(shared)` gets a row: groups icon (green 16) plus `AttendanceKeyLabel.label(k)` (14 sp).
  5. Also-via: `others = bridges − path`, sorted by label, taking 12. If non-empty: 20 dp, `mnAlsoVia.uppercase()`, 8 dp, then a Wrap of chips (avatar 22 + label 14 sp; mono if there is no name; `cSurface`, radius 16).
  6. 24 dp, then a row of two buttons (each 48 dp tall, radius 10):
     - OutlinedButton.icon(open_in_new, `mnOpenInNostr`): closes the sheet, then opens `nostr:<npub>` as an external app. If launch returns false or throws, it opens `https://njump.me/<npub>` instead.
     - FilledButton (orange, dark text, bold) `mnClose`: pops the sheet.

**AppBar** (background `cDark`, no elevation): title `mnTitle` (18 sp w700, letterSpacing 0.3). Actions:
- info_outline (tooltip `mnHowItWorks`): opens the info sheet.
- person_search (tooltip `mnCheckPerson`): pushes `VerifyPersonScreen()`.

**String keys used on this screen** (DE source of truth in `lib/l10n/app_de.arb`; EN/ES exist):
`mnTitle`, `mnHowItWorks`, `mnCheckPerson`, `mnLoading`, `mnUpdating(degree:int)`, `mnEmpty`, `mnEmptySub`, `mnEventNote`, `mnFailedBanner(count:int, ICU plural =1/other)`, `mnRetry`, `mnRetryResult(fixed:int,total:int)`, `mnUpdateFailed`, `mnReachTitle`, `mnReachSub(meetups:int)`, `mnReachSplit(d1,d2,d3:int)`, `mnStandAt(time:String)`, `mnSavedState`, `mnGraphExcerpt(shown,total:int)`, `mnTapShowsPath`, `mnFilterAll`, `mnDirectLabel`, `mnDegreeSecond`, `mnDegreeThird`, `mnDegreeDirect`, `mnSearchHint`, `mnClose`, `mnDegree1`, `mnDegree2`, `mnDegree3`, `mnShowAll(count:int)`, `mnNoResults`, `mnOneSharedMeetup`, `mnSharedMeetups(count)`, `mnLastAt(place)`, `mnVia(names)`, `mnAnd`, `mnYou`, `mnHowItWorksSub`, `mnIntro`, `mnTrustHint`, `mnPrivacyNote`, `mnPathTo(name)`, `mnSharedMeetupsList`, `mnAlsoVia`, `mnOpenInNostr`, `mnCopyNpub`, `mnTogetherAt(place)`.

Selected DE values (to verify mapping):
- `mnTitle` = "Meine Vernetzung"
- `mnReachTitle` = "DEINE REICHWEITE"
- `mnFailedBanner` = "{count, plural, =1{1 Teilnahme ist nicht angekommen} other{{count} Teilnahmen sind nicht angekommen}} — die Verbindungen daraus fehlen im Netzwerk."
- `mnUpdating` = "Aktualisiere {degree}. Grad …"
- `mnStandAt` = "Stand {time}"
- `mnVia` = "über {names}"
- `mnAnd` = "und"
- `mnYou` = "Du"

Unused legacy keys that exist in the arb (no need to port): `mnDegree1Sub`, `mnDegree2Sub`, `mnDegree3Sub`, `mnViaContacts`, `mnViaOneContact`, `mnReachLabel`, `mnIndirectLabel`, `mnNodeDetailTitle`, `mnViaBridges`, `mnNoSharedDetail`, `mnTapHint`, `mnLegend*`.

### 2.2 AdminPanelScreen (Organizer panel)

**Entry point:** the Home tile `organisator` (span 3, visible iff `user.isAdmin || user.isReviewDemo`): `await push(AdminPanelScreen()); _checkActiveSession()`. It takes no arguments.

**State:** `_isSuperAdmin` (own npub == `AdminRegistry.superAdminNpub`; used only to insert an empty 16 dp spacer), `_adminNpub`, `_promotionSource` (`UserProfile.promotionSource`: `seed_admin` > `portal_organizer` > `trust_score` > `''`), `_sessionExpiry: DateTime?`, a 1 s countdown timer, and `_timeLeft`.

**initState:** `_loadAdminInfo()` (reads UserProfile and `NostrService.getNpub()`), then `_checkSession()`.

**`_checkSession`:**
1. Read the int pref `rqr_session_expires` (unix **seconds**, owned by RollingQRService).
2. If it is present and `now < expiry`, set `_sessionExpiry` and start the timer.
3. If it is present and expired, call `RollingQRService.endSession()` and set `_sessionExpiry = null`.
4. If it is absent, set `_sessionExpiry = null`.

**Timer:** every 1 s, compute `diff = expiry - now`. If negative, call `_checkSession()`. Otherwise set `_timeLeft = "${h}h ${mm}m ${ss}s"`, where `h = diff.inHours` (no padding), `mm = minutes%60` zero-padded to 2, and `ss = seconds%60` zero-padded to 2.

#### 2.2.1 Layout (SingleChildScrollView, padding 16/8/16/40)

AppBar title: `apOrganizer`.
1. Status row: verified icon (orange 16), then text `_promotionSource=='trust_score' ? apViaTrustScore : apOrganizer` (12 sp secondary, Expanded), then `NpubChip(_adminNpub)` if the npub is non-empty (tertiary 11 sp monospace).
2. 20 dp + 32 dp spacers.
3. Section label `apMeetupSession` (10 sp w700 tertiary, letterSpacing 1.2), 12 dp.
4. **Active session** (`_sessionExpiry != null`): a card (radius 10, border green at 30 %), padding 16, containing:
   - A row: green 8 dp dot, `apSessionRunning` (green 11 sp w700, letterSpacing 0.8), a spacer, and `_timeLeft` (mono 12 sp w600).
   - 24 dp.
   - A full-width ElevatedButton.icon(qr_code_scanner, `apOpenActiveMeetup`, bold), green background with white text. It pushes `RollingQRScreen`.
   - 8 dp.
   - TextButton `apEndMeetupEarly` (redAccent). It opens an AlertDialog with title `apSessionEndQ`, content `apSessionEndBody`, and actions `apCancel` (grey, returns false) and `apEnd` (red, returns true). On true, `_endSessionEarly()` calls `RollingQRService.endSession()`, cancels the timer, and sets `_sessionExpiry=null`.
5. **Inactive session**: the admin tile (power_settings_new, orange) with title `apStartMeetup` and subtitle `apGeneratesProof`. Tapping it opens an AlertDialog with title `apNewMeetupQ`, content `apNewMeetupBody`, and actions `apCancel` (grey, false) and an ElevatedButton `apStart` (orange, black text, true). On true, run `_startNewSession()`.
6. 16 dp; if super admin, another 16 dp; then 32 dp.
7. Info box (orange at 10 %, border orange at 30 %, radius 12, padding 20):
   - Title row: info icon plus `apHowItWorks` (orange w700 13 sp, letterSpacing 0.5).
   - 12 dp, then the steps text `apHowStep1+apHowStep2+apHowStep3+apHowStep4`. Each step string already ends with `\n` except the last. Style: bodySmall, line height 1.6, secondary.
   - 14 dp, then a nested cyan box (gold at 10 %, border at 35 %, radius 10, padding 12): handshake icon (gold 18), title `apCrossConfirmTitle` (gold w700 12.5 sp), and body `apCrossConfirmBody` (12 sp secondary, line height 1.45).

Admin tile style: card, radius 10, border 0.5, padding h16 v14, margin-bottom 8. It shows an icon (20), a title (14 sp bold) over a subtitle (11 sp tertiary, line height 1.3), and a chevron (16 tertiary).

#### 2.2.2 Dialog catalogue

| Dialog | Title / content keys | Actions → result |
|---|---|---|
| Loading (GPS / creating) | none; centred orange spinner, not dismissible | n/a |
| Continue without GPS `_askContinueWithoutGps(status)` | Title row: location_off (orange) + `orgGpsSoftTitle`. Content `"$msg\n\n${orgGpsSoftBody}"`, where msg = `gpsDenied` (denied) / `gpsDisabled` (serviceDisabled) / `gpsError` (error). | If serviceDisabled: `gpsOpenLocationSettings` opens system location settings (dialog stays). If denied: `gpsOpenAppSettings` opens app details (dialog stays). `gpsRetry` (orange) pops false and then **restarts `_startNewSession()`**. `orgGpsSoftContinue` (orange bold) pops true. Barrier dismissal returns null (treated as abort). |
| Custom name needs GPS `_showCustomNeedsLocation()` | wrong_location icon + `apCustomNeedsGpsTitle`, body `apCustomNeedsGpsBody` | `gpsOpenLocationSettings` (dialog stays); `gpsRetry` pops and restarts `_startNewSession()` |
| No reference `_confirmNoReference(city)` | location_off + `apNoRefTitle`, body `apNoRefBody(city)` | `dialogCancel` → false; `apNoRefContinue` → true |
| Confirm picked meetup `_confirmPickedMeetup(m)` | `apConfirmPickTitle`. Content: an orange box (orange 12 %, border 40 %, radius 12) with `m.city.uppercase()` (18 sp w900 orange, centred), then `apConfirmPickBody` | `dialogCancel` → false; `apConfirmPickYes` → true |
| Portal meetup picker `_pickAnyPortalMeetup()` | `apPickPortalTitle`; a 380 dp tall content area with the hint `apPickPortalHint` (12 sp tertiary), an autofocus search field (hint `profileSearchCity`, orange search icon, filled `cDark`), and the list | Tap a row → confirm dialog → on true pop(meetup). Action `apEnterManually` → pop(null). **Barrier dismiss also returns null, which means "enter manually".** |
| Manual name `_askMeetupName()` | add_location_alt + `gpsNoMeetupTitle`; body `gpsNoMeetupBody`; autofocus TextField (label `gpsMeetupNameLabel`, hint `gpsMeetupNameHint`, error `gpsNameRequired` when trimmed empty) | Material cancel label → null. ElevatedButton `gpsStartAnyway` (orange): if the trimmed text is empty, show the error; else pop(trimmed). |
| Multiple nearby `_pickMeetup(candidates)` | Modal bottom sheet (`cDark`, top radius 20, padding 20): grabber, `gpsPickMeetup` (18 sp w800), `gpsPickMeetupSub`. Up to 5 candidate rows: location icon, `city` (15 sp bold), `groupName` if not null (11.5 sp tertiary, 1 line), `gpsDistanceKm(distance.toStringAsFixed(1))`, chevron. | Tap → pop(candidate); dismiss → null (abort) |
| Error | SnackBar `wotErrorShort(e.toString())`, background `Colors.red` | n/a |

Portal picker list details:
- Source: `MeetupService.fetchMeetups()`; on exception log and use `[]`. If empty, fall back to the bundled `allMeetups`. If that is still empty, or the screen is unmounted, return null.
- "Mine": `PortalApiService.getMyMeetups()` names, `trim().lowercase()`; errors are logged and treated as none. `isMine(m) = mine.contains(m.name.trim().lower) || mine.contains(m.city.trim().lower)`.
- Order: mine first, sorted by `city.lowercase()`, then all others sorted by `city.lowercase()`.
- Filter: `q = search.trim().lowercase()`; match on `city.lower.contains(q) || name.lower.contains(q)`.
- Row (dense ListTile):
  - leading: star (orange 18) if mine, else an 18 dp spacer.
  - title: `city` (14 sp, 1 line).
  - subtitle: `name` (11.5 sp tertiary), only if `name` is non-empty and `name.lower != city.lower`.
  - trailing: `place` icon (green) if `!(lat==0 && lng==0)`, else `location_off` (tertiary), size 16.

#### 2.2.3 Start-session flow (`_startNewSession`), exact decision tree

```
show blocking spinner
loc = MeetupLocationService.resolveLocation()     // GpsStatus ok|denied|serviceDisabled|error, lat,lng, candidates sorted by distance
pop spinner
if loc.status != ok:
    log warn "Standort fuer Meetup-Erstellung NICHT ermittelbar: <status>"
    weiter = _askContinueWithoutGps(status); if weiter != true: return
else log diag (#candidates, #withinCreationRadius)
nearby = loc.withinCreationRadius                  // candidates with distanceKm <= 10.0
orgLat, orgLng = loc.lat, loc.lng                  // 0,0 when no GPS
if nearby.isEmpty:
    hasMeasuredGps = !(orgLat == 0 && orgLng == 0)
    fromPortal = _pickAnyPortalMeetup()
    if fromPortal == null:                         // "enter manually" or dismissed
        if !hasMeasuredGps: log warn; _showCustomNeedsLocation(); return
        name = _askMeetupName(); if null/blank: return
        meetupId = name.trim(); meetupCountry = ''
    else:
        if !hasMeasuredGps && fromPortal.lat == 0 && fromPortal.lng == 0:
            ok = _confirmNoReference(fromPortal.city); if ok != true: return
        meetupId = fromPortal.city; meetupCountry = fromPortal.country
        // portal coordinates are deliberately NOT used as orgLat/orgLng
else:
    chosen = nearby.size == 1 ? nearby[0] : _pickMeetup(nearby) (null → return)
    meetupId = chosen.meetup.city; meetupCountry = chosen.meetup.country
compactId = MeetupKey.compose(MeetupKey.slug(meetupId), meetupCountry)   // "bad-neuenahr-de" or slug only
show blocking spinner
try:
    RollingQRService.endSession()                  // always end a stale session first
    session = RollingQRService.getOrCreateSession(meetupId: compactId, meetupName: meetupId,
                 meetupCountry, blockHeight: 0, lat: orgLat, lng: orgLng)   // may return null!
    CoAttendanceService.recordOrganizerAttendance(meetupName: meetupId, date: now(),
                 blockHeight: session?.blockHeight ?? 0, lat: orgLat, lng: orgLng)   // awaited, isEvent=false
    pop spinner; _checkSession()
    push(kNfcEnabled ? MeetupSessionWizard : RollingQRScreen)   // kNfcEnabled == false → RollingQRScreen
catch e: pop spinner; SnackBar(wotErrorShort(e.toString()), red)
```

`MeetupKey.slug(name) = name.trim().lowercase().replace(' ', '-')` (only the space character). `compose(slug, country)` returns `"$slug-${country.lower}"` when `country.trim().upper` is a valid ISO-3166 alpha-2 code, else just the slug. Both are owned by the badge/scan domain.

Parked (unused, `// ignore: unused_element`): `_openPortalForEvent()`. It opens the portal (`https://portal.einundzwanzig.space/login`, or the home meetup's `portalLink`) after a dialog (`apCreateEventTitle`, `apCreateEventBody`, `apPortalHint`, `apCancel`, `apOpenPortal`); on failure it shows the snackbar `errorOpenLink: url`. **Do not port** unless product asks; keep the strings available.

**String keys used on this screen:** `apOrganizer`, `apViaTrustScore`, `apMeetupSession`, `apSessionRunning`, `apOpenActiveMeetup`, `apEndMeetupEarly`, `apSessionEndQ`, `apSessionEndBody`, `apCancel`, `apEnd`, `apStartMeetup`, `apGeneratesProof`, `apNewMeetupQ`, `apNewMeetupBody`, `apStart`, `apHowItWorks`, `apHowStep1..4`, `apCrossConfirmTitle`, `apCrossConfirmBody`, `apCustomNeedsGpsTitle`, `apCustomNeedsGpsBody`, `apNoRefTitle`, `apNoRefBody(city)`, `apNoRefContinue`, `apConfirmPickTitle`, `apConfirmPickBody`, `apConfirmPickYes`, `apPickPortalTitle`, `apPickPortalHint`, `apEnterManually`, `gpsDenied`, `gpsDisabled`, `gpsError`, `orgGpsSoftTitle`, `orgGpsSoftBody`, `orgGpsSoftContinue`, `gpsOpenLocationSettings`, `gpsOpenAppSettings`, `gpsRetry`, `gpsNoMeetupTitle`, `gpsNoMeetupBody`, `gpsMeetupNameLabel`, `gpsMeetupNameHint`, `gpsNameRequired`, `gpsStartAnyway`, `gpsPickMeetup`, `gpsPickMeetupSub`, `gpsDistanceKm(km)`, `wotErrorShort(msg)`, `dialogCancel`, `profileSearchCity`. Parked: `apCreateEventTitle`, `apCreateEventBody`, `apPortalHint`, `apOpenPortal`, `errorOpenLink`. Home tiles: `tileActManage`, `tileOrganizer`, `tileOrganizerPanel`, `tileActEncounters`, `tileTrustNetwork`, `tileTrustNetworkSub`.

Note: the panel itself does **not** call `AdminStatusVerifier`. Gating happens only through tile visibility (`UserProfile.isAdmin`, which is re-verified on Home load). Keep that behavior unless the security spec says otherwise.

### 2.3 VerifyPersonScreen ("Person prüfen") — adjacent screen using this domain's service

Argument: optional `initialTargetNpub: String?`. If it is set, the field is pre-filled and `_check()` runs after the first frame. My npub comes from `UserProfile.nostrNpub`.

`_check()`:
1. `target = text.trim().lowercase().replaceFirst('nostr:', '')`.
2. If `!NostrService.isValidNpub(target)`, set the error `vpInvalidNpub` and stop.
3. Set `_checking = true`; `res = CoAttendanceService.verifyPerson(myNpub, target)`.
4. On exception, set the error `vpNoneTitle`.

Layout (ListView, padding 20):
1. `vpIntro`.
2. TextField (hint `vpEnterNpub`, monospace, @ prefix icon).
3. A row of OutlinedButton `vpScanQr` and ElevatedButton `vpCheck` (disabled while checking).
4. Error box (red), if any.
5. Spinner, while checking.
6. Result card:

   | condition | colour | icon | title | sub |
   |---|---|---|---|---|
   | self | gold | person | `vpSelfTitle` | none |
   | direct | green | verified | `vpDirectTitle` | `vpDirectSubOne` if shared==1, else `vpDirectSub(n)` |
   | found | orange | hub | `vpIndirectTitle(degree)` | `vpIndirectSub` |
   | none | tertiary | link_off | `vpNoneTitle` | `vpNoneSub` if `targetInNetwork`, else `vpNotInNetwork` |

7. Path list (only if found, not self, and `path.length >= 2`): header `vpPathTitle`. Node cards:
   - First node: gold, labelled `vpYou`, not tappable.
   - Last node: orange, labelled `vpTarget`.
   - Middle nodes: `NostrService.shortenNpub(npub)` = `npub.substring(0, 12) + '...' + last 8`, or the whole npub if its length < 21.
   - Tapping any node except the first opens `nostr:` with the njump fallback.
   - Connectors show a groups icon plus `vpMetAt` in italics.
8. Trust note `vpTrustNote` (only if found and not self).

QR scan sub-screen: CameraX full screen with an overlay hint `vpEnterNpub`. `_extractNpub(raw)`:
1. Lowercase and strip `nostr:`. If the result starts with `npub1` and its length is > 50, return it.
2. Otherwise, if the raw text is a JSON map with `id` as a map and `id.np` a string starting with `npub1` (case-insensitive), return `np.lowercase()`.
3. Otherwise null.

On the first match: medium haptic, then pop with the npub.

Keys: `vpTitle`, `vpIntro`, `vpEnterNpub`, `vpScanQr`, `vpCheck`, `vpInvalidNpub`, `vpSelfTitle`, `vpDirectTitle`, `vpDirectSub(count)`, `vpDirectSubOne`, `vpIndirectTitle(count)`, `vpIndirectSub`, `vpNoneTitle`, `vpNoneSub`, `vpNotInNetwork`, `vpPathTitle`, `vpYou`, `vpTarget`, `vpMetAt`, `vpTrustNote`. (`vpChecking` is unused.)

### 2.4 UX touchpoints owned by other screens that call this domain

- **Co-attendance opt-in dialog** (scanner `meetup_verification.dart`). It is shown after a successful scan iff `badge.isNostrSigned && badge.meetupEventId.isNotEmpty`. Title `caOptInTitle` with a hub icon (gold); body `caOptInBody`; warning box `caOptInPrivacy` (orange); actions `caOptInNo` → false, ElevatedButton.icon `caOptInYes` (gold) → true. Barrier dismissal is allowed and counts as "no". On yes: `count = publishAttendance(badge)`, then a floating SnackBar: `caPublished` (green, 3 s) if count>0, else `caPublishFailed` (red, 6 s).
- **Scanner/reputation social section** (`qr_scanner.dart`, `reputation_verify_screen.dart`, `reputation_layers_widget.dart`). These consume `SocialAnalysis` fields (`isMutual`, `iFollow`, `followsMe`, `commonContactCount`, `orgFollowerCount`, `hops`, and `socialScore`, weighted ×0.25 in the layered score). Owned by the reputation/scan spec. Note the hard-coded German strings there: `"${n} gemeinsame Kontakte"`, `"${n} Organisatoren folgen"`, `"${hops} Hop(s) entfernt"`.

---

## 3. Data models

All Kotlin models should be immutable `data class`es unless noted. JSON keys are given **exactly**.

### 3.1 Social graph

```kotlin
data class SocialAnalysis(
  val targetPubkey: String,      // hex; "" for empty()
  val myFollowCount: Int,
  val targetFollowCount: Int,
  val iFollow: Boolean,
  val followsMe: Boolean,
  val isMutual: Boolean,
  val commonContactCount: Int,
  val orgFollowerCount: Int,
  val hops: Int,                 // 1, 2, or -1 (no connection)
) {
  companion object { fun empty() = SocialAnalysis("",0,0,false,false,false,0,0,-1) }
  val socialScore: Double        // see §8.1
  val connectionLabel: String    // hard-coded German, see §8.1 (not localized in Flutter)
}

data class SocialStats(val followCount: Int, val estimatedMutualCount: Int, val orgFollowerCount: Int)
// JSON (embedded in reputation event content under key "social"):
//   {"follows": followCount, "mutuals": estimatedMutualCount, "org_followers": orgFollowerCount}
// fromJson: missing/non-int → 0. empty() = (0,0,0).
```

### 3.2 Co-attendance

```kotlin
data class CoAttendanceRecord(val npub: String /*author npub*/, val meetupEventId: String /*= content.event, i.e. the attendance KEY*/, val attendedAt: Int /*content.t or 0*/)

class CoAttNode(val npub: String) {             // mutable sets (graph building)
  val meetups: MutableSet<String>               // meetup attendance keys (no "ev|" prefix); insertion-ordered (LinkedHashSet)
  val events: MutableSet<String>                // event keys ("ev|…"), never used for edges
  val all get() = meetups + events
  fun copy(): CoAttNode                         // deep copy of both sets
}

data class CoAttNetwork(myNpub, targetNpub, sharedMeetups: Set<String>, mutualContacts: List<String>,
                        nodes: Map<String, CoAttNode>, targetTotalMeetups: Int, targetTotalContacts: Int) {
  val hasDirectOverlap = sharedMeetups.isNotEmpty()
  val hasAnyConnection = sharedMeetups.isNotEmpty() || mutualContacts.isNotEmpty()
}
// NOTE: CoAttendanceService.analyze() → CoAttNetwork has NO callers in the app. Port it as a library function only (optional).

data class NetworkContact(
  val npub: String,
  val degree: Int,                       // 1..3
  val sharedMeetupsWithMe: Set<String>,  // only degree 1, = sharedKeys(theirMeetups, myMeetups)
  val bridges: Set<String>,              // degree-1 npubs through which reachable; empty for degree 1
  val parent: String?,                   // predecessor on one shortest path (me for degree 1)
)

data class MyNetwork(
  val myNpub: String,
  val byDegree: Map<Int, List<NetworkContact>>,   // keys 1,2,3 always present (may be empty lists)
  val myMeetupCount: Int,                         // number of my meetup keys (not events)
  val updatedAt: Instant? = null,                 // start of the fetch the data comes from
  val stale: Boolean = false,                     // refresh failed; showing saved state
  val meetupsOf: Map<String, Set<String>> = emptyMap(), // meetup keys per person (me + contacts within maxDepth)
) {
  fun markStale(): MyNetwork                    // copy with stale = true
  val contactsByNpub: Map<String, NetworkContact>   // lazily built; iteration order degree 1,2,3
  fun pathTo(npub: String): List<String>        // §8.2.9
  fun sharedBetween(a: String, b: String): List<String> = AttendanceKeyLabel.newestFirst(sharedKeys(meetupsOf[a]?:∅, meetupsOf[b]?:∅))
  val degreeOf: Map<String, Int>
  val degree1Count/degree2Count/degree3Count: Int
  val totalReach = d1+d2+d3
  val isEmpty = totalReach == 0
}

data class PresenceCheck(
  val targetNpub: String,
  val degree: Int,              // 0 = me, 1 = direct, 2.. = via chain, -1 = none
  val path: List<String>,       // [myNpub, …, targetNpub], empty when not found
  val sharedMeetups: Set<String>,
  val targetInNetwork: Boolean, // target has any co-attendance node at all
  val targetTotalMeetups: Int,
) { val found = degree >= 0; val isDirect = degree == 1; val isSelf = degree == 0 }
```

Internal-only types: `_RelayAnswer(records, complete)`, `_BatchAnswer(records, anyComplete, allComplete)`, `_GraphRun(graph, knownAuthors, knownKeys, answered)`, `_NetworkJob(owner, listeners, future)`, `_GraphCache` (see §4).

Attendance publication event content, built with an ordered map in exactly this key order:
- Participant: `{"event": <key>, "meetup": <badge.meetupName>, "t": <badge.date epoch seconds>}`
- Organizer: `{"event": <key>, "meetup": <meetupName>, "t": <date epoch seconds>, "role": "organizer"}`

### 3.3 Admin registry

```kotlin
data class AdminEntry(val npub: String, val meetup: String, val name: String = "", val addedAt: Long = 0) {
  // JSON: {"npub": npub, "meetup": meetup, "name": name, "added_at": addedAt}  (this key order)
  // fromJson: npub/meetup/name default "", added_at default 0. Dart throws (→ whole event skipped) if a field has the wrong type.
  val isLegacyOrganicClaim: Boolean get() = Regex("^Organic \\(\\d+ Badges\\)$").matches(name)   // full-match
}
data class AdminCheckResult(val isAdmin: Boolean, val meetup: String? = null, val name: String? = null, val source: String)
// source ∈ "no_key" | "super_admin" | "local_cache" | "nostr_relay" | "unavailable" | "not_found" | "invalid_key"
// super_admin result: meetup = "Alle Meetups", name = "Super-Admin"
typealias AdminsBySigner = Map<String /*signer hex*/, List<AdminEntry>>
```

`added_at` units are inconsistent. `addVouch` and `addAdmin` set **unix seconds**; relay data contains whatever publishers wrote. Treat the value as opaque.

Admin list event content (kind 30078, `d=einundzwanzig-admins`):
`{"admins":[AdminEntry.toJson()...],"updated_at":"<DateTime.now().toIso8601String()>"}`. The timestamp is local time without a zone, in the form `2026-10-20T14:03:12.123456` (millis, plus micros if non-zero).

### 3.4 Admin status verification

```kotlin
data class AdminVerification(val isAdmin: Boolean, val source: String, val reason: String = "")
// source ∈ "no_key" | "trust_score" | "seed_admin" | "not_admin"
// NOT_ADMIN = AdminVerification(false, "not_admin", "Weder Trust Score noch Seed-Admin Bedingungen erfüllt.")
// no_key reason "Kein Nostr-Key vorhanden."
// trust_score reason "Trust Score ${totalScore.toStringAsFixed(1)} erfüllt Schwellenwert (${promotionScore})."   (promotionScore printed as Dart double e.g. "5.0")
// seed_admin reason "Super-Admin (super_admin)."
```

Consumer (`UserProfile.reVerifyAdmin`, user domain): if `source=='seed_admin'`, set `adminViaSeed = isAdmin`. Otherwise set `adminViaSeed = false` and `adminViaVouch = isAdmin`. In both cases set `isAdminVerified = isAdmin` and save. Home then publishes an admin claim if `isAdmin && source in {trust_score, vouch_consensus}`.

### 3.5 Promotion claims

```kotlin
data class RawClaim(val pubkey: String /*event author hex*/, val content: String, val createdAt: Long)
data class VerifiedClaim(val claimerPubkey: String, val meetup: String, val verifiedBadgeCount: Int, val uniqueSignerCount: Int, val claimedAt: Long /*= event created_at*/)
// toJson: {"claimer_pubkey","meetup","verified_badges","unique_signers","claimed_at","organic": true}
// fromJson: missing → ""/0 ("organic" ignored)
```

Admin claim content (kind 30021), ordered keys:
```json
{"type":"admin_claim","version":1,"claimer_npub":"npub1…","meetup":"<meetupName>",
 "verified_badges":<n>,"unique_signers":<n>,"proofs":[<proof>…],"claimed_at":<unix s>}
```
Proof object, ordered keys:
```json
{"meetup":b.meetupName,"date":b.date.toIso8601String(),"block":b.blockHeight,"sig":b.sig,"sig_id":b.sigId,
 "admin_pubkey":b.adminPubkey,"sig_version":b.sigVersion,"sig_content":b.sigContent,
 "claim_sig":b.claimSig,"claim_event_id":b.claimEventId,"claim_timestamp":b.claimTimestamp}
```
- `date` is Dart `toIso8601String()`: local time without `Z`, or with `Z` if the badge date is UTC.
- `sig_content` is the **original organizer payload JSON string**, for example compact `{"v":2,"t":"B","m":"…","b":…,"x":…,"c":…,"p":"…","s":"…"}`.

### 3.6 Badge fields this domain reads (model owned by badge domain)

`id, meetupName, date, blockHeight, signerNpub, meetupEventId, isEvent, isOrganizer, delivery, sig, sigId, adminPubkey, sigVersion, sigContent, claimSig, claimEventId, claimPubkey, claimTimestamp, lat, lng`. Derived fields:
- `isNostrSigned` = `sigVersion==2` && `sig` matches `^[0-9a-fA-F]{128}$` && `adminPubkey` matches `^[0-9a-fA-F]{64}$` && `BigInt(sig,16) != 0`.
- `hasCryptoProof` = `sig.isNotEmpty && sigVersion > 0`.
- `isClaimed` = `claimSig.isNotEmpty && claimPubkey.isNotEmpty`.
- `isFullyBound` = `hasCryptoProof && isClaimed`.

Organizer marker badge created by this domain:
`MeetupBadge(id="org-$meetupEventId", meetupName, date, iconPath="", blockHeight, meetupEventId, isEvent, delivery="organizer", isOrganizer=true, lat, lng)`. All other fields keep their defaults. It does not count toward the trust score.

---

## 4. Persistence

The Flutter app uses `SharedPreferences` (legacy API). On Android these live in `FlutterSharedPreferences.xml`, with every key prefixed `flutter.`:
- ints are stored as `Long`;
- `List<String>` is stored as a String `"VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu!" + jsonEncode(list)` (current), or the legacy prefix plus a base64 Java-serialized list.

If the native app ships under the same `applicationId` (`space.einundzwanzig.meetup`) as an in-place upgrade, it must migrate or read these. The global migration belongs to the persistence spec. The domain's keys (names **without** the `flutter.` prefix) are:

| Key | Type | Format | Writer | Reader | Notes |
|---|---|---|---|---|---|
| `social_my_follows` | String | JSON array of 64-char hex pubkeys | SocialGraphService._cacheFollows | getMyFollows | **not keyed by owner**: follows survive identity change for ≤2 h |
| `social_cache_ts` | int | ms epoch | _cacheFollows | _loadCachedFollows | TTL 2 h (`now - ts > 7_200_000` → miss) |
| `admin_registry_cache` | String | JSON array of AdminEntry | `_saveToCache`, `_loadFromCache` (rewrite after organic purge, timestamp untouched) | `_loadFromCache` (only reader) | Network cache of all known admins. Also exported to backup as `admin_registry`. |
| `admin_registry_timestamp` | int | ms epoch | `_saveToCache` | `cacheAge()` | background refresh skipped if age < 1 h |
| `bootstrap_permanently_sunset` | bool | | isSunsetActive / reset | isSunsetActive | consistency-checked against count |
| `admin_unique_authors_count` | int | | `_recordSunsetAuthors` | isSunsetActive | removed by one-time reset |
| `sunset_authors_set_v1` | StringList | hex signer pubkeys | `_recordSunsetAuthors` | same | accumulates across fetches |
| `sunset_count_reset_v1_done` | bool | | `_resetSunsetCountOnce` | same | one-time K1 migration flag |
| `my_personal_vouches` | String | JSON array of AdminEntry | addVouch/removeVouch/recover/revokeAll | getMyVouches, publish | legacy, no UI today (see §8.4.8) |
| `organic_admin_claims` | String | JSON array of VerifiedClaim JSON (with `"organic":true`) | `_saveOrganicClaims` | getOrganicAdmins | information only, no reader in UI |
| `coatt_publish_status` | String | JSON object `{ "<badge.sigId>": <relaysAccepted:int> }` | `_saveStatus` | publishStatus/failedBadges | 0 = consented but failed; absent = never consented |
| `coatt_graph_cache_v1` | String | JSON graph cache (below) | `_writeCache` | `_readCache` | owner-bound |

Read-only dependencies:
- `rqr_session_expires` (int, unix **seconds**; RollingQR domain).
- `custom_relays`, `disabled_default_relays` (StringList; RelayConfig).
- `nostr_display_names_v1` (profile names: `{hex: {"n": name, "t": msEpoch}}`, max age 1 day).
- `haptic_enabled` (bool, default true).
- `nostr`, `promotion_source`, `admin_via_portal`/`admin_via_vouch`/`admin_via_seed`, `review_demo` (UserProfile).

Badges are stored encrypted by the badge domain. This domain only calls `loadBadges()`, `saveBadges()`, and `keepAside()`.

**Graph cache JSON (`coatt_graph_cache_v1`)**, exact:
```json
{"v":1,"owner":"npub1…","full":<fullSyncAt ms>,"sync":<syncAt ms>,
 "ka":["npub1…",…],            // knownAuthors (npubs whose attendances were fetched completely at least once)
 "kk":["key",…],               // knownKeys (meetup keys whose participants were fetched completely)
 "n":{"npub1…":{"m":["key",…],"e":["ev|…",…]}}}   // "e" omitted when empty
```
Read rules:
- `v != 1` → null (rebuild).
- Unparsable → null, logged.
- `owner` is `toString()` with default `''`.
- `full` and `sync` must be ints, else 0.
- Lists keep only string elements (`toString()`); a non-list becomes empty.
- Each node value must be a map, else the node gets empty sets.
- A cache whose `owner != myNpub` is ignored (not deleted). It gets overwritten on the next successful build.

**Migrations performed by this domain:**
1. `_resetSunsetCountOnce()` runs once per install, on the first `isSunsetActive()`. If `sunset_count_reset_v1_done != true`:
   - set it to true;
   - log a security event if the old count was > 0;
   - remove `admin_unique_authors_count` and `sunset_authors_set_v1`;
   - set `bootstrap_permanently_sunset = false`.
   A full app reset clears prefs, so this runs again afterwards (harmless).
2. Organic purge on every cache read. Entries whose `name` matches `^Organic \(\d+ Badges\)$` are dropped. If any were dropped, the list is rewritten **without** touching the timestamp.
3. `getMyVouches`: if the key is missing, return `[]`. Old mixed caches are deliberately not migrated.

App reset (Home, other domain) calls `prefs.clear()`, which wipes all of the above.

---

## 5. Network

All network traffic in this domain is Nostr relay WebSocket traffic, except for:
- Portal (via other domains): `GET https://portal.einundzwanzig.space/api/my-meetups` with auth headers and a 15 s timeout, via `PortalApiService.getMyMeetups()`. Response `{"data":[{id:int,name,slug,…}]}`; only `name` is used here.
- `MeetupService.fetchMeetups()` (portal meetup list).
- `MempoolService.getBlockHeight()` (only when the organizer marker badge has blockHeight ≤ 0).
- External intents: `nostr:<npub>`, `https://njump.me/<npub>`, and Android settings screens.

### 5.1 Relay sets (two different sources — preserve!)

| Component | Relays |
|---|---|
| SocialGraphService, CoAttendanceService (fetch + publish) | `RelayConfig.getActiveRelays()`: the defaults below minus `disabled_default_relays`, plus `custom_relays`, de-duplicated with order kept (defaults first) |
| AdminRegistry, PromotionClaimService | **hard-coded**, ignoring user config: `wss://relay.damus.io`, `wss://nos.lol`, `wss://relay.nostr.band`, `wss://nostr.einundzwanzig.space` (in this order) |

The default relays are the same 4 URLs.

### 5.2 WebSocket semantics (RelaySocket, infra domain)

- `connect(url)` waits for the handshake. On failure it closes the channel without awaiting.
- Frames longer than 512×1024 characters (String) or bytes (binary) are dropped before parsing.
- Close the socket in `finally` in every flow.
- Subscription IDs are `<prefix>-<16 lowercase hex chars from SecureRandom, 8 bytes>`. Prefixes: `contacts-`, `admin-`, `organic-claims-`, `coatt-`.

### 5.3 Timeouts / concurrency / retries per operation

| Operation | Connect timeout | Wait timeout | Concurrency | On timeout |
|---|---|---|---|---|
| Contact list fetch (per relay) | 8 s (`RelayConfig.relayTimeout`) | 8 s until EOSE | relays **sequentially**, all relays queried | returns best-so-far |
| Admin registry fetch (per relay) | 8 s | 8 s until EOSE | relays sequentially; **stop at first relay that returns a usable result** | returns collected if non-empty, else null |
| Admin list publish | 5 s | 5 s inactivity on the stream waiting for `["OK",…]` | sequential over 4 relays | not confirmed |
| Promotion claim publish | 5 s | fixed 2 s delay after send (no OK check) | sequential | counted as success if connect+send didn't throw |
| Promotion claim fetch | 8 s | 8 s until EOSE | sequential; first relay with a **non-empty** list wins | returns `[]` (partial results discarded) |
| Co-attendance fetch (per relay) | 8 s | 8 s until EOSE | **all relays in parallel** per batch; ≤3 batches in parallel | partial records kept but `complete=false`; null if nothing |
| Co-attendance publish | 5 s (`RelayConfig.publishTimeout`) | 6 s for the `["OK", id, true…]` | **all relays in parallel** | not accepted |

No HTTP caching applies. There is no explicit rate limiting, beyond the batching caps in §8.2 and the 3-parallel-batches limit, which exists to avoid relay rate limits.

---

## 6. Nostr

### 6.1 Event kinds used

| Kind | NIP / semantics | d-tag | Who publishes | Purpose |
|---|---|---|---|---|
| 3 | NIP-02 contact list (replaceable) | none | everyone (third-party clients) | follows (read only) |
| 30078 | NIP-78 app data, parameterized replaceable (NIP-33/01) | `einundzwanzig-admins` | super admin / admins | organizer list ("vouches"). **The same kind also carries** reputation (`einundzwanzig-reputation`, other domain), so always filter by `#d`. |
| 30079 | parameterized replaceable (custom) | the attendance key | participants (opt-in) and organizers | co-attendance |
| 30021 | parameterized replaceable (custom; not a NIP-standard meaning) | `einundzwanzig-admin-claim` | organizers / promoted users | admin claim with badge proofs |
| 21000 | ephemeral-range kind used as a signed **badge payload** (never published) | none | organizer | verified inside claim proofs |
| 21002 | ephemeral-range kind used as a signed **claim binding** (never published) | none | participant | verified inside claim proofs |

### 6.2 Filters (REQ), exact

```jsonc
// contact list
["REQ","contacts-<hex16>",{"kinds":[3],"authors":["<hex>"],"limit":1}]        // no CLOSE is sent; socket closed

// admin registry
["REQ","admin-<hex16>",{"kinds":[30078],"authors":[<hex…≤50>],"#d":["einundzwanzig-admins"],"limit":50}]
// after result: ["CLOSE","admin-<hex16>"]

// promotion claims
["REQ","organic-claims-<hex16>",{"kinds":[30021],"#d":["einundzwanzig-admin-claim"],"limit":100}]
// after result: ["CLOSE",…]

// co-attendance, by authors (≤100 hex per filter)
["REQ","coatt-<hex16>",{"kinds":[30079],"authors":[…],"limit":5000[,"since":<s>]}]
// co-attendance, by keys (≤60 keys per batch, expanded with their "@"-less base → up to 120 d values)
["REQ","coatt-<hex16>",{"kinds":[30079],"#d":[…],"limit":1000[,"since":<s>]}]
// after EOSE/timeout: ["CLOSE",…]
```
- JSON key order in the filters is as written above: `kinds`, `authors`, `#d`, `limit`, `since`. Key order does not matter to relays.
- `since` is only added when `since != null && since > 0`.
- If a co-attendance call passes neither authors nor keys, the filter is just `{"kinds":[30079]}`. This is never used in practice.

### 6.3 Incoming event validation (per component)

Shared primitives (infra domain), reproduced here because the behavior is load-bearing:
- `verifiedEvent(raw)`. Rejects anything that is not a JSON object. Builds an event from `id`, `pubkey`, `created_at`, `kind`, `tags` (each tag element `toString()`), `content`, and `sig`; missing fields become `''`/`0`, and a wrong type rejects the event. Valid iff all of these hold:
  - `created_at.toString().length == 10`;
  - `id == sha256(NIP-01 serialization with pubkey lowercased)` as a lowercase hex string comparison;
  - BIP-340 `verify(pubkey, id, sig)`.

  Invalid events are counted and logged, never thrown.
- `answersFilter(event, filter)`. Checks:
  - `kinds` contains `event.kind`;
  - `authors` contains `event.pubkey`;
  - `since`/`until` (if either is present, `created_at` must be an int and lie within the range);
  - for each `#x` key: the value must be a non-empty list, and some tag `[x, v, …]` must have `v` in that list.
- `replaceableCreatedAt(event)`. Returns `created_at` if it is an int, ≥ 0, and ≤ now+600 s; otherwise null.

| Component | Checks applied to each EVENT |
|---|---|
| Contact list | `verifiedEvent` → `answersFilter(filter)` → `replaceableCreatedAt` not null. Keep the newest per relay (`at < best` → ignore, so equal timestamps replace). Across relays keep strictly newer (`hit.at > best.at`), so the first relay wins ties. **An empty list is a valid newest result.** |
| Admin registry | author ∈ queried authors (checked **before** the signature) → `isValid` (same rules as verifiedEvent) → `answersFilter(filter)` → content `{"admins":[…]}` parsed. Non-empty lists are appended under `event.pubkey`. **Multiple events from the same signer are merged (union)**, not newest-wins. |
| Promotion claims | `isValid` only; **no** `answersFilter`. Any valid event is a RawClaim (author, content, created_at). |
| Co-attendance fetch | `verifiedEvent` → `answersFilter(filter)` (so kind, authors / `#d`, and since are enforced) → `npub = bech32(pubkey)`, content JSON → `event = content.event.toString()` (`''` if missing; skip if empty), `t = content.t if int else 0`. |
| Co-attendance publish OK | count only `["OK", <event.id>, true, …]` with **matching id**; `false` is logged with `msg[3]` or "ohne Grund" |
| Admin publish OK | the first `["OK", _, <bool>]` with length ≥ 3, **id not checked**; confirmed iff `msg[2] == true` |

### 6.4 Outgoing events (tag layouts exact; signing via SigningService — local key, Amber NIP-55, NIP-07, or NIP-46)

```
Co-attendance (participant):  kind 30079
  tags: [["d", key], ["e_ref", badge.sigId], ["client", "einundzwanzig-meetup-app"]]
  content: {"event":key,"meetup":badge.meetupName,"t":<badge.date s>}
Co-attendance (organizer):    kind 30079
  tags: [["d", key], ["role", "organizer"], ["client", "einundzwanzig-meetup-app"]]
  content: {"event":key,"meetup":meetupName,"t":<date s>,"role":"organizer"}
Admin list:                   kind 30078
  tags: [["d", "einundzwanzig-admins"]]
  content: {"admins":[…my vouches…],"updated_at":"<local ISO>"}
Admin claim:                  kind 30021
  tags: [["d", "einundzwanzig-admin-claim"], ["meetup", meetupName]]
  content: see §3.5
```
`e_ref` is a custom tag; it is not an `e` tag. Wire format: `["EVENT", {"id","pubkey","created_at","kind","tags","content","sig"}]`.

Replaceable semantics this domain relies on:
- One kind-30079 per `(author, d=key)`. Re-publishing the same key replaces it.
- One kind-30078 `einundzwanzig-admins` per signer; publishing an empty list revokes all.
- One kind-30021 claim per author.
- Kind 3: the newest event wins.

### 6.5 NIP list

NIP-01 (events, REQ/EOSE/OK/CLOSE, serialization), NIP-02 (contact lists), NIP-19 (npub bech32), NIP-33 (parameterized replaceable, now part of NIP-01), NIP-78 (app data kind 30078). Signing transports (NIP-55/07/46) are owned by the signing domain.

---

## 7. Crypto & encoding

### 7.1 NIP-01 event id

`id = lowercase_hex(SHA-256(UTF-8(JSON([0, pubkey.lowercase(), created_at, kind, tags, content]))))`. The JSON must match Dart's `jsonEncode`:
- no whitespace;
- `"` is escaped as `\"` and `\` as `\\`;
- `\b \t \n \f \r` use their short escapes;
- other code units < 0x20 are escaped as `\u00XX` with **lowercase** hex;
- `/` is NOT escaped, and non-ASCII characters are emitted raw (UTF-8);
- lone surrogates are escaped as `\udXXX`.

Use a hand-written NIP-01 serializer in Kotlin, not a generic JSON library with different escaping (Gson escapes `=`, `<`, `>`, `&`, `'` by default!).

Validity additionally requires `created_at.toString().length == 10` (`nostr` 1.5.0 `Event.isValid`). Port this rule for **incoming** validation in this domain so that results stay identical.

Signature: BIP-340 Schnorr over the 32-byte id, with x-only 32-byte pubkey hex and a 64-byte signature hex. Recommended library: `fr.acinq.secp256k1:secp256k1-kmp` (`Secp256k1.verifySchnorr`).

### 7.2 NIP-19

`npub` = bech32 (not bech32m) with HRP `npub`, data = the 32-byte pubkey in 5-bit groups (padded). Dart `Nip19.decodePubkey(s)` returns `""` (it does **not** throw) when the HRP is not `npub`. It throws on bech32 errors (checksum, mixed case, length > 90).

`NostrService.isValidNpub(s)` = `s.startsWith("npub1")` && decode doesn't throw. **The data length is not checked.** Keep this lenient rule, or tighten it to 32 bytes (safe tightening).

### 7.3 Attendance key (byte-exact, wire-relevant)

Verbatim from `coattendance_service.dart`:
```dart
static const String eventPrefix = 'ev|';
static String attendanceKey(String meetupEventId, String signerNpub, {bool isEvent = false}) {
  if (isEvent) return '$eventPrefix$meetupEventId';
  final signer = signerNpub.trim();
  if (signer.isEmpty) return meetupEventId; // Altformat, besser als nichts
  final short = signer.length > 12 ? signer.substring(signer.length - 12) : signer;
  return '$meetupEventId@$short';
}
```
How `meetupEventId` is formed (other domains, for compatibility):
- Participant meetup: `"$keySlug-$dateStr"`.
  - `keySlug` is the signed slug from `BadgeSecurity.normalize(...)['meetup_slug']`, i.e. `MeetupKey.parse(m).slug` (the trailing ISO country is stripped only if it is a valid ISO code). If there is no signed slug, it is `MeetupKey.slug(meetupName)`.
  - `dateStr = DateTime.now().toIso8601String().substring(0,10)` (**local date of scan**).
  - If the name is unusable, the id is `''` and nothing is published.
- Participant event: `"evt|<eventAddress>"`, with `eventAddress = "<kind>:<creatorHex>:<dTag>"`.
- Organizer (this domain): `MeetupKey.eventId(MeetupKey.slug(meetupName), date)` = `"${slug}-${date.toIso8601String().substring(0,10)}"` (**local date of session start**).
- `signerNpub` is the organizer npub on both sides: the participant uses `normalized['admin_npub'] = bech32(p)`, and the organizer uses its own `SigningService.npub()`. Both therefore produce the same 12-char suffix.

Example: `aschaffenburg-2026-06-03@u8qf5q534jzc` (super-admin npub suffix).

### 7.4 Promotion-claim proof verification (byte-exact reconstructions)

Organizer signature (`BadgeSecurity.verify(data, checkExpiry:false)`, badge domain):
- **Compact** (data has `p` and `s` and no `sig`):
  - Require `p` = 64 hex, `s` = 128 hex, `c != 0`.
  - `content = canonicalJsonEncode({k: data[k] for k in [v,t,m,b,x] if present})`. Keys are sorted (top level only), with compact JSON and numbers re-encoded from the parsed values.
  - `tags = [["t","badge"],["m", m.toString()]]`, kind 21000, `created_at = c`.
  - `id = sha256(jsonEncode([0,p,c,21000,tags,content]))`. The event is valid iff `isValid` holds.
  - `adminPubkey = p`.
- **Legacy full** (`v >= 2`, no compact keys): `verifyNostr`.
  - Require `admin_pubkey`, `sig`, `sig_id`, and an int `created_at`.
  - `content = canonical(data − {sig, sig_id, admin_pubkey, _verified_by, created_at})`.
  - `tags = [["t", type.lowercase()],["meetup", meetup_id.toString()],["block", block_height.toString()]]`, kind 21000.
  - `id = sig_id` (must match the recomputed id), plus the BIP-340 check.

Participant binding (`BadgeClaimService.verifyClaim`), verbatim:
```dart
final Map<String, dynamic> claimContent = {
  'action': 'claim_badge',
  'org_sig': orgSig,
  'org_event_id': orgEventId,
  'org_pubkey': orgPubkey,
  'block_height': blockHeight,
  'claimed_at': claimTimestamp,
};
final contentJson = BadgeSecurity.canonicalJsonEncode(claimContent);   // sorted keys:
// {"action":"claim_badge","block_height":N,"claimed_at":T,"org_event_id":"…","org_pubkey":"…","org_sig":"…"}
final tags = [['t', 'badge_claim'], ['p', orgPubkey], ['block', blockHeight.toString()]];
final serialized = jsonEncode([0, claimPubkey, claimTimestamp, 21002, tags, contentJson]);
final expectedEventId = sha256.convert(utf8.encode(serialized)).toString();
if (expectedEventId != claimEventId) → invalid
Event(claimEventId, claimPubkey, claimTimestamp, 21002, tags, contentJson, claimSig).isValid()
```
Note: `claimPubkey` is **not** lower-cased in `expectedEventId`, but `isValid` lower-cases it. Hex pubkeys are lowercase in practice. `orgEventId` (`sig_id`) is an opaque value bound into the claim; it is **not** checked against the organizer event id.

Session key used for the "≥3 distinct sessions" rule, verbatim:
```dart
final normalized = BadgeSecurity.normalize(data);
final signedAt = (data['c'] ?? data['created_at'] ?? 0) as int;
final day = DateTime.fromMillisecondsSinceEpoch(signedAt * 1000, isUtc: true);
final dayKey = '${day.year}-${day.month}-${day.day}';      // UTC, NO zero padding, e.g. "2026-3-7"
sessions.add('$adminPubkey|${normalized['meetup_id']}|$dayKey');
```
`normalized['meetup_id']` depends on the format:
- compact `m` not starting with `evt:`: `MeetupKey.parse(m).slug`;
- compact `m` starting with `evt:` with ≥5 parts: `"evt:<dTag>"` (parts[3]);
- otherwise (incomplete evt): `m` raw;
- legacy (has `meetup_id`): the raw `meetup_id`.

### 7.5 Numbers in re-serialized JSON

When reconstructing signed content from parsed JSON (the compact badge `b`, `x`, `v`), the Dart behavior is `jsonDecode` → int/double → `jsonEncode`:
- an integer literal stays an integer (`875000`);
- `1.0` becomes `1.0`;
- `1e3` becomes `1000.0`.

The Kotlin port must reproduce this (simplest: use the literal text of `JsonPrimitive` numbers). Any mismatch makes valid proofs fail verification.

### 7.6 Other encodings

- Secure random subscription ids: 8 bytes, lowercase hex, zero-padded per byte.
- No KDFs and no symmetric crypto in this domain. Badge storage encryption belongs to the badge domain.

---

## 8. Business rules & algorithms

### 8.1 Social graph

**getMyFollows(forceRefresh=false):**
1. Unless forced, return the cached set if `now - social_cache_ts ≤ 2 h` and the JSON parses.
2. Otherwise compute `myHex = decode(NostrService.getNpub())`. If there is no npub or decoding fails, return `{}` (with a warn log), and do not cache.
3. `follows = fetchContactList(myHex)`; cache it with the current timestamp. **An empty result is also cached.**

**fetchContactList(hex):**
- For each active relay, sequentially: take the newest kind-3 event, as in §6.3.
- `follows = {t[1] for tag t if t[0]=="p" && t.size >= 2 && t[1].length == 64}`. Only the length is checked, not the hex; case is preserved.
- Return the follows of the newest event across relays, or `{}`.

**analyze(targetHex):**
- Return `empty()` if there is no own pubkey, or if `myHex == targetHex`.
- `myFollows = getMyFollows()` (cached); `targetFollows = fetchContactList(target)`.
- `iFollow = target ∈ myFollows`; `followsMe = myHex ∈ targetFollows`; `isMutual = iFollow && followsMe`.
- `common = myFollows ∩ targetFollows`.
- `orgFollowerCount = countOrgFollowers(target)`.
- `hops = 1` if mutual or one-way; else `2` if common is non-empty; else `-1`.

**countOrgFollowers(target):**
- `admins = AdminRegistry.fetchFromRelays()`. This is a fresh network fetch with side effects: sunset author recording, merge. The result is **not** written to the cache.
- Return 0 if null or empty.
- For the first 10 admins in list order: decode the npub, fetch their contact list, and count it if the list contains the target. Per-admin failures are logged at debug level and ignored.

**getMyStats():**
- Return `empty()` if there is no own pubkey.
- `myFollows = getMyFollows()`; `sample = myFollows.take(20)` (set iteration order).
- `mutualCount` = number of sampled follows whose contact list contains me. Fetches are sequential; failures are ignored.
- `estimatedMutuals = myFollows.isEmpty ? 0 : round(mutualCount / sample.size * myFollows.size)`. Dart `round()` is half-away-from-zero; for positive values this equals `Math.round`.
- `orgFollowerCount = countOrgFollowers(myHex)`.

**socialScore** (0..3), verbatim:
```dart
double score = 0;
if (isMutual) { score += 1.0; } else if (iFollow || followsMe) { score += 0.3; }
if (commonContactCount > 0) { score += (commonContactCount / (commonContactCount + 5)) * 1.0; }
score += (orgFollowerCount * 0.5).clamp(0.0, 1.0);
return score.clamp(0.0, 3.0);
```

**connectionLabel** (German, unlocalized): checks in order and returns the first match.
- `isMutual` → "Gegenseitig verbunden"
- `iFollow` → "Du folgst"
- `followsMe` → "Folgt dir"
- `common > 0` → "$n gemeinsame Kontakte"
- otherwise → "Keine Verbindung"

**Reputation embedding** (other domain): `content.social = stats.toJson()` only if `followCount > 0`; `getMyStats` is given a 10 s timeout, returning `empty()` on timeout.

### 8.2 Co-attendance

#### 8.2.1 Key matching (`sharedKeys`, `_keysMatch`), verbatim

```dart
static Set<String> sharedKeys(Set<String> a, Set<String> b) {
  if (a.isEmpty || b.isEmpty) return <String>{};
  String base(String k) { final i = k.indexOf('@'); return i < 0 ? k : k.substring(0, i); }
  final out = <String>{};
  for (final x in a) {
    final xb = base(x);
    final xHasSigner = x.length != xb.length;
    for (final y in b) {
      if (x == y) { out.add(x); continue; }
      final yb = base(y);
      if (xb != yb) continue;
      final yHasSigner = y.length != yb.length;
      if (!xHasSigner || !yHasSigner) out.add(xb);
    }
  }
  return out;
}
static bool _keysMatch(String a, String b) {
  if (a == b) return true;
  final ab = _base(a); final bb = _base(b);
  if (ab != bb) return false;
  return a.length == ab.length || b.length == bb.length;
}
```
In words: equal keys match. If exactly one side lacks the `@signer` suffix, the bases are compared (and the *base* is returned). If both have suffixes and they differ, there is no match. Results are insertion-ordered sets.

`_wantedKeys(keys)`: each key, plus its base if it had a non-empty `@` suffix and a non-empty base. This gives the d-tags to query.

#### 8.2.2 Degenerate ids (`_isDegenerateEventId`), verbatim

```dart
final v = id.trim().toLowerCase();
if (v.isEmpty) return true;
if (v.startsWith('-')) return true;
const placeholders = ['unbekanntes-meetup','unknown-meetup','meetup-desconocido'];
for (final p in placeholders) { if (v.startsWith(p)) return true; }
if (RegExp(r'^\d{4}-\d{2}-\d{2}$').hasMatch(v)) return true;
if (!v.contains('@')) {
  final namePart = v.replaceAll(RegExp(r'-\d{4}-\d{2}-\d{2}$'), '');
  const generic = {'test','test1','test2','test3','testing','demo','home','garten','ab-test','probe','temp','tmp','xxx'};
  if (generic.contains(namePart)) return true;
}
return false;
```
Where it is applied:
- In `publishAttendance`, to `badge.meetupEventId` (the raw id, before the suffix).
- In graph `add()`, to the record's key (with suffix or prefix). Suffixed keys are therefore never "generic".

#### 8.2.3 Publish status and retry

- `publishStatus()` reads the map `sigId → relays`. A non-int value becomes 0; on error it returns `{}`.
- `_saveStatus(sigId, n)` does nothing if `sigId` is empty. Otherwise it reads the map, sets the entry, and writes it back.
- `failedBadges(all) = all.filter { status[it.sigId] == 0 }`.
- `retryFailed(all)`: for each failed badge, call `publishAttendance` and count results > 0. Log "Erneut veroeffentlicht: x von y". Return the fixed count.
- `publishAttendance(badge)`:
  1. If `!badge.isNostrSigned || degenerate(badge.meetupEventId)`, log a warning and return 0 **without saving status**.
  2. Compute the key, sign, publish, `_saveStatus(sigId, n)`, and return n.
  3. On any exception: `_saveStatus(sigId, 0)` and return 0.

#### 8.2.4 Organizer attendance (`recordOrganizerAttendance`)

1. `meetupEventId = MeetupKey.eventId(MeetupKey.slug(meetupName), date)`.
2. `blockHeight = blockHeight > 0 ? blockHeight : MempoolService.getBlockHeight()`.
3. Build the marker badge (§3.6). If no existing badge has `isOrganizer && meetupEventId == id`, append it and call `saveBadges`. If that fails, call `keepAside(badge)`. If that also fails, log an error and **return null without publishing**.
4. `_publishOrganizerAttendance`:
   - `key = attendanceKey(id, ownNpub ?? '', isEvent)`;
   - sign and publish (§6.4);
   - return the accepted count. Exceptions return 0.
   - There is no `isNostrSigned` check here, and **no publish status is recorded**.
5. Return the badge. Any exception → null.

Callers:
- Admin panel: `isEvent=false`, awaited.
- Event-badge session service: `isEvent=true`, `meetupName = event.title`, `unawaited`.

#### 8.2.5 Constants

| Constant | Value |
|---|---|
| `kind` | 30079 |
| client tag | `einundzwanzig-meetup-app` |
| fetch timeout | 8 s |
| `_maxMeetupsPerStage` | 60 |
| `_maxPeoplePerStage` | 300 |
| `_authorBatch` | 100 |
| `_keyBatch` | 60 |
| `_parallelBatches` | 3 |
| `_fullSyncEvery` | 24 h |
| `_sinceOverlap` | 1 h |
| author-query limit | 5000 |
| key-query limit | 1000 |
| publish OK wait | 6 s |
| default `maxDepth` | 3 |
| `verifyPerson` BFS `maxDepth` | 6 (graph loaded to depth 3) |

#### 8.2.6 Per-relay fetch (`_fetchFromRelay`) result semantics

- `state`: `true` on EOSE; `false` if the socket closed first (onDone); `null` on error or 8 s timeout. A timeout logs a warning if records were received.
- Send `CLOSE` after the state is known, then close the socket.
- If `state == null && records.isEmpty`, return `null` (meaning "no answer").
- `hitLimit = records.size >= limit`; log a warning when it is hit.
- Otherwise return `RelayAnswer(records, complete = (state == true && !hitLimit))`. Any exception returns null.

**`_queryRelays`**: query all relays in parallel and de-duplicate records by `"${npub}|${meetupEventId}"` (first wins).
- `anyComplete` = some answer is complete.
- `allComplete` = relays is non-empty **and** every answer is non-null and complete.

#### 8.2.7 Staged graph crawl (`_loadGraph`) — exact algorithm

```
relays = RelayConfig.getActiveRelays()
start = (cache != null && !fullSync) ? cache : null; incremental = start != null
graph = {}; knownAuthors = {}; knownKeys = {}; since = null
if start: graph = deep copy of start.nodes; knownAuthors += start.ka; knownKeys += start.kk
          since = start.syncAt/1000 - 3600
batchesTotal = batchesAnswered = recordsFetched = 0

add(r): if degenerate(r.meetupEventId) return
        node = graph.getOrPut(r.npub); if r.key startsWith "ev|" node.events += key else node.meetups += key

keepCachedAuthors(npubs): if cache == null return       // NOTE: uses `cache` (also in full-sync mode)
        for n in npubs: c = cache.nodes[n] ?: continue; node = graph.getOrPut(n); node.meetups += c.meetups; node.events += c.events
keepCachedKeys(wanted): if cache == null return
        for (npub, c) in cache.nodes: hits = c.meetups ∩ wanted (exact); if hits nonEmpty: graph.getOrPut(npub).meetups += hits

fetchAuthors(npubs): partition into known (∈ knownAuthors) / fresh
   tasks: for each group (known with `since`, fresh with null), chunk 100:
      hexes = chunk.map(decode).filterNotNull(); if empty skip
      task: batchesTotal++; res = queryRelays(authors=hexes, since)
            res.records.forEach(add); recordsFetched += n
            if res.anyComplete: batchesAnswered++
            if !res.allComplete: keepCachedAuthors(chunk)
            if res.anyComplete: knownAuthors += chunk else knownAuthors -= chunk
   run tasks with ≤3 concurrently (worker pool, in order)

fetchKeys(keys): same pattern with knownKeys, chunk 60 (as Set), filter "#d" = wantedKeys(chunk)
      if !allComplete: keepCachedKeys(wantedKeys(chunk)); anyComplete → knownKeys += chunk else -= chunk

peopleAt(keys): byBase = group keys by base
   for each (npub,node) in graph: if any k in node.meetups with byBase[base(k)] containing q where keysMatch(k,q) → include npub
meetupKeysOf(npubs) = union of graph[n].meetups

seeds = [myNpub, *extraNpubs] distinct, filtered to decodable
if seeds empty or relays empty → return GraphRun(graph, knownAuthors, knownKeys, answered=false)

fetchAuthors(seeds)                                          // stage 0
seenPeople = seeds; seenKeys = {}; frontier = meetupKeysOf(seeds)
for depth in 1..maxDepth:
   frontier -= seenKeys; if empty break
   keys = frontier; if keys.size > 60: warn; keys = first 60 (insertion order)
   seenKeys += keys
   fetchKeys(keys)
   newPeople = peopleAt(keys) - seenPeople; if > 300: warn; first 300
   seenPeople += newPeople
   onStage(graph, depth)
   if depth == maxDepth || newPeople empty: break
   fetchAuthors(newPeople); frontier = meetupKeysOf(newPeople)
return GraphRun(graph, knownAuthors, knownKeys, answered = batchesAnswered > 0)
```
Notes:
- Event keys go into nodes but **never** into `frontier` or `peopleAt`; only meetups expand the graph.
- Dart `Nip19.decodePubkey` returns `""` for a non-npub HRP, and `""` counts as non-null. Kotlin: treat `""` as invalid (filter it out). This is safe.
- The worker pool preserves Dart's semantics: up to 3 tasks run at once, tasks are taken in order, and shared counters/sets are mutated. In Kotlin, use a `Mutex` or a single-threaded dispatcher for the graph mutations.

#### 8.2.8 Adjacency and degrees (`_adjacency`, `_networkFromGraph`) — exact

Adjacency:
- Group `(npub, key)` pairs by `base(key)`, using only meetups.
- Within each group, for every pair i < j with different npubs where `keysMatch(ki, kj)` holds, add an undirected edge.
- Neighbor sets are insertion-ordered.

BFS (verbatim core):
```dart
final degree = <String, int>{myNpub: 0};
final bridges = <String, Set<String>>{};
final parent = <String, String>{};
final queue = Queue<String>()..add(myNpub);
while (queue.isNotEmpty) {
  final current = queue.removeFirst();
  final curDeg = degree[current]!;
  if (curDeg >= maxDepth) continue;
  var neighbors = adj[current] ?? const <String>{};
  if (curDeg == 0) {
    final strength = { for (final n in neighbors) n: sharedKeys(nodes[n]?.meetups ?? {}, myMeetups).length };
    neighbors = (neighbors.toList()..sort((a, b) => strength[b]!.compareTo(strength[a]!))).toSet();
  }
  for (final neighbor in neighbors) {
    if (!degree.containsKey(neighbor)) { degree[neighbor] = curDeg + 1; parent[neighbor] = current; queue.add(neighbor); }
    if (curDeg == 0) {
    } else if (degree[neighbor] == curDeg + 1) {
      if (curDeg == 1) { bridges.putIfAbsent(neighbor, () => {}).add(current); }
      else { final inherited = bridges[current]; if (inherited != null) bridges.putIfAbsent(neighbor, () => {}).addAll(inherited); }
    }
  }
}
```
- Contacts: every `npub` with `1 ≤ degree ≤ maxDepth`.
  - Degree 1: `sharedMeetupsWithMe = sharedKeys(their, mine)` and `bridges = {}`.
  - Otherwise: `shared = {}` and `bridges = bridges[npub] ?: {}`.
  - `parent = parent[npub]`.
- Sorting:
  - Degree 1: by `shared.size` descending, then by `newest(shared)` descending (null → year 0).
  - Degrees 2 and 3: by `bridges.size` descending.
  - Dart's `List.sort` is not stable. Use a stable sort in Kotlin (`sortedWith`); this is acceptable.
- `myMeetupCount = myMeetups.size`.
- `meetupsOf = {n: copy(nodes[n].meetups) for n in degree.keys if degree[n] ≤ maxDepth}` (includes me).
- When `log=true`, write diagnostic logs: my keys, counts per degree, a sample of 15 foreign keys if degree 1 is empty, and each degree-1 contact's shared keys. Port these to the app logger.

#### 8.2.9 `MyNetwork.pathTo(npub)`, verbatim semantics

```
chain = [npub]; cur = npub
repeat 4 times: p = contactsByNpub[cur]?.parent ?: break; chain += p; if p == myNpub break; cur = p
return chain.last == myNpub ? chain.reversed() : []
```

#### 8.2.10 `buildMyNetwork(myNpub, maxDepth=3, forceFull=false, onStage)`

- **Job coalescing:** a static `_job` exists. If a job is running for the same owner, add `onStage` to its listeners and return the same future. Otherwise create a new job. A different owner's job keeps running but is no longer tracked.
- Listeners are called with a copy of the list, and their exceptions are swallowed. `_job` is cleared on completion if it is still the same job.
- **`_buildMyNetwork`:**
  1. `startedAt = nowMs`.
  2. `cache = readCache()`; keep it only if `owner == myNpub`.
  3. `previous = cache ? networkFromGraph(cache.nodes, updatedAt = cache.syncAt) : null`.
  4. `fullSync = forceFull || cache == null || startedAt - cache.fullSyncAt > 24h`; `incremental = cache != null && !fullSync`.
  5. `run = loadGraph(cache, fullSync, onStage = (g, d) -> emit(incremental ? fresh : mergeStage(fresh, previous, d), d))`, where `fresh = networkFromGraph(g)` with **no updatedAt**.
  6. If `!run.answered`, return `previous?.markStale() ?: MyNetwork(myNpub, {1:[],2:[],3:[]}, 0, stale=true)`. **Do not write the cache.**
  7. Otherwise `net = networkFromGraph(run.graph, log=true, updatedAt=startedAt)`. Log changes against `previous` (counts of new / closer / farther / gone).
  8. Write the cache `{owner, full: fullSync ? startedAt : cache.fullSyncAt, sync: startedAt, ka, kk, n: run.graph}`.
  9. Return `net`.
- **`_mergeStage(fresh, previous, doneDepth)`:**
  - If `previous == null`, return `fresh`.
  - Degrees `≤ doneDepth` come from `fresh`.
  - Degrees `> doneDepth` come from `previous`, minus anyone already placed (degrees are processed in ascending order).
  - `updatedAt = previous.updatedAt`; `meetupsOf = previous.meetupsOf + fresh.meetupsOf` (fresh overrides).
- **`cachedNetwork(myNpub)`:** `networkFromGraph(cache.nodes, maxDepth, updatedAt = cache.syncAt)` if the owner matches, else null.

#### 8.2.11 `verifyPerson(myNpub, targetNpub, maxDepth=6)`

1. `nodes = loadGraph(myNpub, extraNpubs=[target], maxDepth=3, no cache, full)`. This uses no persistent cache and does not write one.
2. `shared = sharedKeys(nodes[me].meetups, nodes[target].meetups)`.
3. If `me == target`, return `degree 0, path [me], targetInNetwork = nodes.contains(target)`.
4. `adj = adjacency(nodes)`. BFS over paths from me, but only if `adj` contains me:
   - `visited = {me}`; queue of paths;
   - pop the front path (`removeAt(0)`); skip it if `len-1 > maxDepth`;
   - stop when its last node is the target;
   - push unvisited neighbors (marking them visited when pushed).
5. Return `degree = path.size - 1` or -1; `path` (or empty); `targetInNetwork`; `targetTotalMeetups = target meetups count`.

#### 8.2.12 `analyze(myNpub, targetNpub)` (no app callers)

- `nodes` as in verifyPerson, with maxDepth 3; `shared = sharedKeys(mine, theirs)`.
- `mutual` = the other npubs n (not me, not the target) with `sharedKeys(n.meetups, mine)` non-empty **and** `sharedKeys(n.meetups, theirs)` non-empty.
- `targetContacts` = the npubs ≠ target that share with the target (**including me**).

#### 8.2.13 AttendanceKeyLabel (display)

- `_strip(key)`: remove a leading `ev|`, then cut at the first `@`.
- `date(key)`: regex `^(.*)-(\d{4})-(\d{2})-(\d{2})$` on the stripped key → **local** `LocalDate(y,m,d)` or null.
- `place(key)`: group(1), or the whole stripped key if there is no date; split on `-`; drop empty parts; uppercase the first UTF-16 code unit of each part; join with a space.
- `dateText(d, withYear=true)`: `dd.MM.yyyy`, or `dd.MM.` without the year.
- `label(key)`: `"$place, ${dateText(d)}"`, or `place` alone if there is no date.
- `newestFirst(keys)`: sort by date descending, with null treated as year 0.
- `newest(keys)`: the maximum date, or null.

### 8.3 Admin status verification (`AdminStatusVerifier.verifyAdminStatus(badges)`)

1. If `!SigningService.canSign()`, return `(false, "no_key", "Kein Nostr-Key vorhanden.")`.
2. If badges are non-empty:
   - `firstBadgeDate = minBy(date)` over **all** badges;
   - `score = TrustScoreService.calculateScore(badges, firstBadgeDate)` (the trust domain filters v1 badges and organizer markers);
   - if `score.meetsPromotionThreshold`, return `(true, "trust_score", reason)`.
3. `npub = NostrService.getNpub()`. If it is non-empty, run `res = AdminRegistry.checkAdmin(npub)`. If `res.isAdmin && res.source == "super_admin"`, return `(true, "seed_admin", "Super-Admin (super_admin).")`. Exceptions are swallowed (fail closed).
4. Return `NOT_ADMIN`.

Registry `local_cache` and `nostr_relay` hits are **deliberately ignored** for self-verification. They are self-published claims and would be circular. Portal status is handled separately (`adminViaPortal`, user domain). The former "vouch consensus" path was removed (Aug 2026).

`isVerifiedAdmin(badges) = verifyAdminStatus(badges).isAdmin`. It currently has no callers; keep it for guards.

### 8.4 Admin registry

#### 8.4.1 Constants

| Constant | Value |
|---|---|
| `superAdminNpub` | `npub1lf0rga7j66uj6enae2mxezamz5nsz3vechhvmh25tcarn4u8qf5q534jzc` |
| `sunsetThreshold` | 20 |
| relays | 4 hard-coded (see §5.1) |
| d-tag | `einundzwanzig-admins` |
| kind | 30078 |
| relay timeout | 8 s |
| query chunk | first 50 authors only |
| filter limit | 50 |
| background refresh min age | 1 h |

#### 8.4.2 `isSunsetActive()`

1. Run `_resetSunsetCountOnce()` (§4).
2. If `bootstrap_permanently_sunset == true`:
   - if `admin_unique_authors_count (default 0) < 20`, log a security event, set the flag to false, and return false (tamper check);
   - otherwise return true.
3. If `count >= 20`, set the flag to true and return true.
4. Otherwise return false.

#### 8.4.3 `_recordSunsetAuthors(signersHex)`

- If the set is empty, return.
- Union it into `sunset_authors_set_v1`. If the size changed, write the list and set `admin_unique_authors_count = size`.
- Only **signers** count: an event's `pubkey` after the signature check, never the npubs listed inside.

#### 8.4.4 `checkAdmin(userNpub)`

```
if userNpub empty → (false, "no_key")
sunset = isSunsetActive()
if !sunset && userNpub == superAdminNpub → (true, "Alle Meetups", "Super-Admin", "super_admin")
cached = loadFromCache()                               // organic-filtered
hit = cached?.first { it.npub == userNpub }
if hit → launch background refresh (fire-and-forget); return (true, hit.meetup, hit.name, "local_cache")
lookupFailed = false
try:
   list = fetchFromRelays()
   if list null/empty → lookupFailed = true
   else: saveToCache(list); isSunsetActive(); entry = list.first{npub==userNpub}; if entry → (true, entry.meetup, entry.name, "nostr_relay")
catch → warn; lookupFailed = true
if lookupFailed → warn "Registry nicht abrufbar — Signierer bleibt UNGEPRUEFT, nicht unbekannt."; return (false, "unavailable")
return (false, "not_found")
```
`checkAdminByPubkey(hex)`:
- empty → `no_key`;
- `npub = Nip19.encodePubkey(hex)`, and on exception → `invalid_key`;
- otherwise `checkAdmin(npub)`.

Background refresh:
1. If `cacheAge() < 1h` (`inHours < 1`), return.
2. `list = fetchFromRelays()`. If it is non-null, save it to the cache and call `isSunsetActive()`.
3. All errors are silent.

#### 8.4.5 `fetchFromRelays()` (testable via an injectable `relayFetchOverride(relayUrl, authorsHex) -> AdminsBySigner?`)

```
sunset = isSunsetActive()
authors = !sunset ? [hex(superAdminNpub)] (decode error → warn, return null)
                  : cached admins decoded to hex (skip failures); if empty → [hex(superAdmin)]
chunk = authors.take(50)
for relay in RELAYS (sequential):
  try:
    result = (override ?: fetchFromSingleRelay)(relay, chunk)
    if result == null → next relay
    legitSigners = {}; uniqueMap = LinkedHashMap<npub, AdminEntry>()
    sawQueried = false
    for (signer, entries) in result:
       if signer ∉ chunk → skip                        // un-asked signers can never fill list or counter
       sawQueried = true
       clean = entries.filterNot { isLegacyOrganicClaim }
       if clean empty → skip
       legitSigners += signer; for e in clean: uniqueMap[e.npub] = e   // later wins, first position kept
    if !sawQueried → next relay
    admins = uniqueMap.values
    recordSunsetAuthors(legitSigners)
    return sunset ? mergeWithCache(admins) : admins
  catch → warn; next relay
warn "Kein Relay erreichbar"; return null
```
`mergeWithCache(relayEntries)`: if `relayEntries` is empty, return the cached list (or `[]`). Otherwise return `relayEntries` de-duplicated by npub. Cache entries missing from the relay fetch are dropped; this is how revocation works (Audit M1).

`_fetchFromSingleRelay(relay, authorsHex)`:
- Collect per signer (§6.3).
- EOSE → the collected map, which may be empty `{}`.
- onError/onDone before EOSE → null.
- 8 s timeout → the collected map if it is non-empty, else null.
- Send CLOSE.
- De-duplicate each signer's entries by npub (later wins). Exceptions are re-thrown.

#### 8.4.6 Cache operations

- `getAdminList()` = `loadFromCache() ?: []`.
- `removeFromCache(npub)`: load, filter, and save if anything changed. The save also updates the timestamp. Errors are ignored. It is called by Home when portal organizer status is revoked.
- `addAdmin(entry)` (legacy, used by backup restore):
  - if it is an organic claim → **throw `IllegalStateException`** (Dart `StateError`);
  - if the npub is a duplicate → silently return;
  - if the npub is invalid → throw `"Ungültiger npub."`;
  - otherwise append it with `addedAt = entry.addedAt > 0 ? entry.addedAt : nowSeconds` and save.
- `removeAdmin(npub)`: remove it and save.
- `clearCache()` removes the cache and timestamp keys.
- `cacheAge()` returns `now - timestamp`, or null.
- `forceRefresh()`: `list = fetchFromRelays()`. If it is non-null, save it, call `isSunsetActive()`, and return `list.size`. Otherwise return -1.

#### 8.4.7 Backup interplay (backup domain)

- Backup v≥2 exports `admin_registry: getAdminList().map(toJson)`.
- Restore validates each npub (`isValidNpub`), calls `addAdmin` (counting rejections), and then `forceRefresh()`. A failed refresh while offline is only logged.

#### 8.4.8 Personal vouches (legacy, no UI; port the API only if the backup/WoT spec needs it)

- `getMyVouches()` reads `my_personal_vouches` (`[]` if missing or on error).
- `addVouch(e)` throws in these cases:
  - "Du bürgst bereits für diesen npub." (duplicate)
  - "Ungültiger npub." (invalid npub)
  - "Du kannst nicht für dich selbst bürgen." (own npub)

  Otherwise it adds the entry with `addedAt = nowSeconds`.
- `removeVouch(npub)`.
- `recoverMyVouchesFromRelays()`:
  - Return -1 if there is no npub or it cannot be decoded.
  - For each relay, call `_fetchFromSingleRelay(relay, [myHex])` (it does **not** use the override).
  - On the first non-null result: if `result[myHex]` is non-empty, save it and return its count; otherwise return 0 (an empty answer does **not** overwrite local data).
  - If no relay answers, return -1.
- `revokeAllVouches()`: require canSign, save `[]`, then call `createAndPublishAdminListEvent()`.
- `createAndPublishAdminListEvent()`:
  - require canSign;
  - sign kind 30078 with `[["d","einundzwanzig-admins"]]` and the content `{"admins": myVouches, "updated_at": localIso}`;
  - publish sequentially (§5.3), counting `OK` true;
  - if the count is 0, throw `"Event konnte an keinen Relay gesendet werden.\n" + errors.join("\n")`;
  - return `'{"sent_to": N, "event": {…}}'`.
- `createAdminListEvent()`: same signing, without publishing; returns the event JSON.

### 8.5 Promotion claims

**`publishAdminClaim(badges, meetupName)`:**
1. `npub = SigningService.npub()`. Return false if it is null or `!canSign()`, or if it cannot be decoded.
2. `verified = badges.filter { it.isFullyBound && it.claimPubkey == ownHex }`. Return false if `verified.size < 3`.
3. `proofs`: §3.5. `uniqueSigners = distinct non-empty adminPubkey`.
4. Sign kind 30021. Publish to the 4 relays sequentially: connect (5 s), send, wait 2 s, close, count. Return `count > 0`.

Callers (Home): on portal grant, with `meetupName = portal meetups.first.name` (or homeMeetupId, or `'Unbekannt'`); on re-verification with source `trust_score`/`vouch_consensus`, with `meetupName = homeMeetupId` (or `'Unbekannt'`). Errors are swallowed.

**`syncOrganicAdmins()`** (Home start, background):
1. `claims = fetchClaimsFromRelays()`. If it is null or empty, return `[]` **without overwriting the stored list**.
2. `known = {hex(superAdmin)} ∪ {hex(e.npub) for e in getAdminList()}`. Organic claims are NOT included, which prevents chained self-promotion (K1).
3. For each claim, `verifyClaim(claim, known)`. Collect the valid results and save them (newest `claimedAt` per claimer, strictly greater wins).
4. Any exception → log and return what was collected so far, without saving.

**`verifyClaim(claim, knownAdminPubkeys)`** — exact order:
1. Parse the content JSON as a map, else null. Require `type == "admin_claim"`. Read `proofs` (list, default empty) and `meetup` (string, default `''`). If either is empty, return null.
2. **Max age:** `if (now_s - claim.createdAt > 90*86400) return null`. This uses the **event** `created_at`.
3. For each proof (any exception → skip that proof). Read `sig`, `admin_pubkey`, `sig_version` (int), `sig_content`, `sig_id`, `block` (int), `claim_sig`, `claim_event_id`, `claim_timestamp` (int); defaults are `''`/0. A wrong type throws, which skips the proof.
   1. Skip if `sig` or `admin_pubkey` is empty.
   2. Skip if `sig` has already been seen. The seen-set is updated **before** the other checks.
   3. Skip if `admin_pubkey ∉ known`.
   4. Skip if `sig_version < 2` or `sig_content` is empty.
   5. `data = jsonDecode(sig_content)` (a map); `vr = BadgeSecurity.verify(data, checkExpiry=false)`. On exception, skip.
   6. Skip if `!vr.isValid` or `vr.adminPubkey != admin_pubkey`.
   7. Skip if `(data.s ?? data.sig ?? '').toString() != sig`.
   8. Skip if `claim_sig`, `claim_event_id` is empty or `claim_timestamp == 0`.
   9. Run `BadgeClaimService.verifyClaim(claimSig, claimEventId, claimPubkey = claim.pubkey (event author!), claimTimestamp, orgSig = sig, orgEventId = sig_id, orgPubkey = admin_pubkey, blockHeight = block)`. Skip if invalid.
   10. Add the session key (§7.4); `valid++`; `signers += admin_pubkey`.
4. Return null if `valid < 3`, `sessions.size < 3`, or `signers.size < 1`.
5. `active = signers ∩ known` (redundant double check); return null if `< 1`.
6. Return `VerifiedClaim(claim.pubkey, meetup, valid, active.size, claim.createdAt)`.

`minVerifiedBadges = 3` and `minUniqueSigners = 1` are constants. They are also used for publishing.

---

## 9. Security measures to preserve

Cross-referenced with code comments, `SecurityAudit.md`, and `SecurityChangelog.md`.

1. **C2 — admin status is not a stored boolean.**
   - `is_admin` and related prefs are only a UI cache.
   - Self-status is recomputed on every Home load from signed badges (trust score) or the hard-coded super admin.
   - Registry cache and relay hits are **not** accepted for self-verification (circularity).
   - The verifier fails closed when the registry throws.
2. **K1 — organic self-promotion.**
   - `Organic (N Badges)` names are filtered at every read/save path of the registry: `_loadFromCache` with a rewrite, `_saveToCache`, `fetchFromRelays`, and `addAdmin` (which throws).
   - Verified organic claims never enter the registry and never count as known admins.
   - A claim needs, all at once: unique sigs; a valid organizer signature by a known admin, where the **signing** key (not the unsigned label) must equal the claimed admin and the proof sig must be the signed one; a participant binding by the **claimer's own key**; and ≥3 distinct `(signer, meetup_id, UTC day)` sessions.
3. **Audit-2 Fund 5:** claims older than 90 days are rejected. Signer status is checked against the *current* registry.
4. **Audit-2 Fund 6 (sunset):**
   - Only distinct **signers** (event pubkeys) count, and only signers that were actually queried and that contributed ≥1 non-organic entry.
   - The count accumulates in a set.
   - A tampered `bootstrap_permanently_sunset=true` with count < 20 is reset.
   - A one-time reset (K1 follow-up) discards a possibly poisoned old count.
   - In the bootstrap phase only the super admin is queried, so the threshold is unreachable by design.
5. **M1 — revocation:** in the sunset phase, relay data replaces the cache; missing admins are dropped. Portal revocation explicitly removes the own npub from the cache (Home).
6. **H2/M1 — central signature verification** of every relay event (id recomputation plus BIP-340), plus `answersFilter`, so a validly signed but *different* event (another kind, author, or d) can't answer a query. Exception: the admin-claim fetch skips `answersFilter`; claim content is validated instead.
7. **M4:**
   - Subscription ids come from a CSPRNG.
   - Relay frames over 512 KiB are dropped.
   - Parse failures are tallied (`RelayParseTally`: one warn per subscription, with the first error at debug) instead of flooding the logs.
8. **M8:** the admin-list publish waits for `OK` instead of a blind delay. Co-attendance publish counts only `OK true` with a **matching event id**, in parallel, with a 6 s window; silence is not consent (Issue #57). The promotion claim publish still uses a blind 2 s delay; keep it, or improve it to OK-checking (wire-compatible).
9. **Replaceable timestamp guard:** contact lists with `created_at > now+600 s` are ignored, so a relay can't pin a future event forever.
10. **"Unavailable" ≠ "unknown":** an empty or failed registry lookup returns `source="unavailable"`, so the scanner can show "check failed" instead of accusing a signer.
11. **Co-attendance privacy and anti-collision:**
    - Opt-in only, with a strong privacy warning (`caOptInPrivacy`).
    - Only Nostr-signed badges qualify.
    - The signer suffix prevents strangers with the same session name and date from being linked.
    - Degenerate and generic legacy ids are ignored on read and refused on publish.
    - Events (large gatherings) never create edges.
    - Content is minimal: no location, no badge signature (only `e_ref` = the sigId tag).
12. **Social graph privacy:** only aggregated numbers (`follows`, `mutuals`, `org_followers`) are published. Who follows whom is never published.
13. **Organizer self-attendance:** the organizer gets only an unsigned marker badge (`isOrganizer=true`, excluded from the trust score). Nobody can confirm themselves (explained by `apCrossConfirm*`).
14. **Name/location integrity in the organizer flow:**
    - A free-text meetup is allowed only with a measured GPS position.
    - Portal coordinates are never used as the measured organizer location.
    - The picked meetup name is confirmed explicitly, because it is permanently signed into every badge.
15. **Network cache resilience:**
    - A run with no relay answer never overwrites the cache.
    - Partial answers keep cached data for the affected people and keys.
    - Truncation (stage caps and relay limits) is always logged, never silent.

---

## 10. Tests & vectors to port

### 10.1 Existing Flutter tests (port 1:1 to JUnit; use the injectable relay fetch and an in-memory prefs store)

**`test/admin_registry_organic_filter_test.dart`** (fresh random keys per test; `superHex = decode(superAdminNpub)`):
1. *Poisoned cache entry is not admin for checkAdmin.*
   - Cache = `[victim "Organic (3 Badges)", real "Ben"]` with timestamp = now; override returns null.
   - Expect `checkAdmin(victimNpub)` to be `isAdmin=false`, `source != "local_cache"` (it is `unavailable`). The same holds for `checkAdminByPubkey(victimHex)`.
   - Expect `checkAdmin(realNpub)` to be `isAdmin=true`, `source="local_cache"`.
   - The stored cache npubs are now exactly `[realNpub]`.
2. *Raw relay list with an organic entry makes nobody admin in step 4.*
   - Empty prefs; override returns `{superHex: [victim Organic(3 Badges), real Ben]}`.
   - Expect `checkAdmin(victim)` = `(false, "not_found")` and `checkAdmin(real).isAdmin == true`.
   - `getAdminList()` npubs are exactly `[real]`.
3. *A poisoned cache does not trigger sunset and is reset.*
   - Cache = 25 organic entries plus real "Ben"; `admin_unique_authors_count=25`; override returns null.
   - Expect `isSunsetActive() == false`, `count < 20`, no organic entries in `getAdminList()`, and the single entry is `real`.
4. *Bootstrap counts only the queried super admin.*
   - Override returns `{superHex: [real Ben, organic], secondSigner: [Eva], organicSigner: 25×organic}`.
   - After `checkAdmin(real)`: `admin_unique_authors_count == 1`, `isSunsetActive() == false`, and `getAdminList()` npubs = `{real}`.

**`test/admin_registry_sunset_signer_test.dart`:**
1. *One signer with 25 legitimate npubs counts as one author.*
   - Override returns `{superHex: 25×AdminEntry(name "Admin")}`.
   - `checkAdmin(listed[0])` gives `(true, "nostr_relay")`; `count == 1`; sunset is false; the flag is not true.
   - `checkAdmin(superAdminNpub).source == "super_admin"`.
   - `getAdminList()` = all 25.
2. *Bootstrap queries only the super admin.*
   - `sunsetThreshold == 20`. The override captures authors and returns 20 stranger signers plus the super admin.
   - After `fetchFromRelays()`, the asked authors are `[superHex]` and the count is 1. Sunset is false.

**`test/promotion_claim_k1_test.dart`.** The helpers construct a compact organizer badge:
- `content = {v:2, t:'B', m:'aschaffenburg-de', b:875000, x: createdAt+14400}`, canonical, with tags `[["t","badge"],["m",m]]`, kind 21000, signed at `createdAt`;
- the proof uses `sig_id = sha256(utf8(sig)).hex`, `sig_content = jsonEncode(badgeMap)`, `sig_version 2`;
- the binding is a kind-21002 event signed by the binder at `c+60` over the canonical claim content;
- the claim has `createdAt = now`, content `{type:'admin_claim', version:1, meetup:'Aschaffenburg', proofs}`;
- `day(n) = now - n*86400`.

Cases:
1. Three sessions (days 30/20/10) bound by the claimer → non-null, `verifiedBadgeCount == 3`, `claimerPubkey == claimer`.
2. The same proof copied 3× → null.
3. No binding → null.
4. Binding by a stranger key → null.
5. `admin_pubkey` label = a known admin but signed by a stranger → null.
6. Three badges in one session (same day, `t`, `t+600`, `t+1200`; blocks 1/2/3) → null.

**`test/security_followup_test.dart`** (relevant parts):
1. `AdminEntry(name "Organic (3 Badges)").isLegacyOrganicClaim == true`; `"Ben"` → false; `"Organic admin"` → false.
2. `getAdminList()` with a cache of `[Organic (4 Badges), Ben]` → `["npub1real"]`, and the result is stable on a second call (length 1). The npubs here are not even valid; no validation happens on read.
3. `addAdmin(Organic (3 Badges))` throws `StateError` (Kotlin: `IllegalStateException`).

**Related tests owned by other domains** (ensure they exist there):
- `test/meetup_key_test.dart`, group "Organisator-Kennung == Teilnehmer-Kennung". For each of Bad Neuenahr, Frankfurt am Main, Rothenburg ob der Tauber, Neu-Ulm, Köln-Deutz, Aschaffenburg, and Bad Neuenahr-Ahrweiler (with date 2026-10-05), the organizer key `eventId(slug(city))` equals the participant key built from `normalize({'v':2,'t':'B','m':m,'b':0})['meetup_slug']`. This holds both with `m = compose(slug, 'DE')` and with `m = slug`.
- `test/relay_event_signature_test.dart` (`verifiedEvent`, `answersFilter`, `replaceableCreatedAt` future guard).
- `test/relay_parse_tally_test.dart`.

### 10.2 New vectors to add (no Flutter tests exist for co-attendance / social graph; values computed by emulating the Dart code)

`attendanceKey`:

| meetupEventId | signerNpub | isEvent | → |
|---|---|---|---|
| `aschaffenburg-2026-06-03` | `npub1lf0rga7j66uj6enae2mxezamz5nsz3vechhvmh25tcarn4u8qf5q534jzc` | false | `aschaffenburg-2026-06-03@u8qf5q534jzc` |
| `evt\|31923:abc:xyz` | (any) | true | `ev\|evt\|31923:abc:xyz` |
| `x-2026-01-01` | `"  "` | false | `x-2026-01-01` |
| `x-2026-01-01` | `abc` | false | `x-2026-01-01@abc` |

`sharedKeys(a, b)`:

| a | b | → |
|---|---|---|
| {a-2026-06-03} | {a-2026-06-03@u8qf5q534jzc} | {a-2026-06-03} |
| {a-2026-06-03@AAAAAAAAAAAA} | {a-2026-06-03@BBBBBBBBBBBB} | {} |
| {a-2026-06-03@X} | {a-2026-06-03@X} | {a-2026-06-03@X} |
| {a-2026-06-03@X, b-2026-01-01} | {a-2026-06-03, b-2026-01-01@Y} | {a-2026-06-03, b-2026-01-01} |
| {} | {a} | {} |
| {ev\|e-2026-01-01} | {ev\|e-2026-01-01} | {ev\|e-2026-01-01} |

`_isDegenerateEventId`:
- **true**: `""`, `"  "`, `-2026-02-25`, `Unbekanntes-Meetup-2026-01-01`, `unknown-meetup`, `meetup-desconocido-2026-01-01`, `2026-02-25`, `test-2026-07-24`, `TEST-2026-07-24`, `ab-test-2026-01-01`, `testing`.
- **false**: `test-2026-07-24@u8qf5q534jzc`, `berlin-2026-01-01`, `home-2026`, `ev|test-2026-07-24`, `aschaffenburg-de-2026-06-03`.

`AttendanceKeyLabel.label`:

| key | → |
|---|---|
| `wuerzburg-2026-09-12@u8qf5q534jzc` | `Wuerzburg, 12.09.2026` |
| `bad-neuenahr-2026-10-05` | `Bad Neuenahr, 05.10.2026` |
| `ev\|blocktrainer-event-2026-10-05` | `Blocktrainer Event, 05.10.2026` |
| `ev\|evt\|31923:abc:dtag` | `Evt\|31923:abc:dtag` (no date) |
| `aschaffenburg-de-2026-06-03@x` | `Aschaffenburg De, 03.06.2026` |
| `nodate` | `Nodate` |
| `münchen-2026-01-02` | `München, 02.01.2026` |

`dateText(2026-09-12, withYear=false)` = `12.09.`

**BFS network vector** (`_networkFromGraph`, me = `ME`, maxDepth 3). Nodes (meetups only):
```
ME:    {a-2026-01-01@S1, b-2026-02-01@S1}
ANNA:  {a-2026-01-01@S1, b-2026-02-01, c-2026-03-01@S2}
BEN:   {a-2026-01-01@S2, d-2026-04-01@S3}        // different signer on same name/date → NOT linked to ME
CARLA: {c-2026-03-01@S2}
DORA:  {c-2026-03-01, e-2026-05-01@S4}           // legacy key links to ANNA and CARLA
EMIL:  {e-2026-05-01@S4}
FRED:  {e-2026-05-01@S4, f-2026-06-01@S5}
GUS:   {f-2026-06-01@S5}                          // degree 4 → excluded
```
Expected:
- **Degree 1:** `ANNA` (shared = {a-2026-01-01@S1, b-2026-02-01}, parent ME, bridges ∅).
- **Degree 2:** `CARLA`, `DORA` (bridges {ANNA}, parent ANNA).
- **Degree 3:** `EMIL`, `FRED` (bridges {ANNA}, parent DORA).
- `BEN` and `GUS` are absent. `myMeetupCount = 2`; `totalReach = 5`.
- `pathTo(EMIL) = [ME, ANNA, DORA, EMIL]`.
- `sharedBetween(ANNA, DORA) = [c-2026-03-01]`.
- `verifyPerson(ME→EMIL)`: degree 3, `path=[ME,ANNA,DORA,EMIL]`. `verifyPerson(ME→GUS)`: degree 4 (the BFS limit is 6), but only if GUS is loaded within the 3-stage crawl (GUS is an extra seed, so yes). `verifyPerson(ME→BEN)`: degree -1, `targetInNetwork = true`.

`socialScore` vectors:

| inputs | score |
|---|---|
| mutual, common=5, org=1 | 1.0 + 0.5 + 0.5 = **2.0** |
| one-way, common=0, org=3 | 0.3 + 0 + 1.0 = **1.3** |
| none, common=1, org=0 | 1/6 = **0.1666…** |
| mutual, common=1000, org=10 | 1 + 0.995 + 1 = **2.995** |

`getMyStats` estimate: 300 follows, sample 20, 7 mutual → `round(7/20*300) = 105`.

Graph cache round-trip: serialize a graph with events on one node and none on another, and assert that `"e"` is omitted for the latter, that `v != 1` → null, and that an owner mismatch → `cachedNetwork == null`.

`pathTo` cap: a chain longer than 4 parent hops (not possible with maxDepth 3) returns `[]`.

Other cases:
- `_mergeStage`: previous has X at degree 2 and fresh (doneDepth 1) has X at degree 1 → X appears only in degree 1.
- `buildMyNetwork` with no relay answers and a cache present: returns the cached network with `stale=true`, and the cache is not rewritten.
- Publish status: `publishAttendance` on a non-signed badge → 0, and status is **not** written; on a throwing signer → status `{sigId: 0}`.

---

## 11. Android capabilities & recommended native APIs

| Need | Permission / API | Library |
|---|---|---|
| Relay WebSockets | `INTERNET` | OkHttp `WebSocket` (one client; per-request sockets as in Dart, or a pooled relay manager that keeps the per-subscription semantics) |
| Schnorr verify/sign, SHA-256 | none | `fr.acinq.secp256k1:secp256k1-kmp-jni-android` (`verifySchnorr`), `java.security.MessageDigest` |
| Bech32 (npub) | none | small in-house bech32 codec (or `fr.acinq.bitcoin` Bech32) |
| JSON with exact numbers/escaping | none | kotlinx.serialization `JsonElement` for parsing + custom NIP-01 / canonical serializer |
| Persistence | none | DataStore Preferences (new) + one-time migration from `FlutterSharedPreferences.xml` (`flutter.` prefix, list prefix) if upgrading in place |
| Location (organizer panel) | `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Fused Location Provider **with fallback to `LocationManager`** (GrapheneOS without Play Services was an explicit field case) |
| Open location / app settings | intents `Settings.ACTION_LOCATION_SOURCE_SETTINGS`, `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` | n/a |
| Open Nostr profile | `Intent.ACTION_VIEW` `nostr:<npub>`, fallback browser `https://njump.me/<npub>`; declare `<queries>` for scheme `nostr` (package visibility) | n/a |
| Clipboard | `ClipboardManager` (Android 13+ shows its own toast; keep inline check icon) | n/a |
| Haptics | `View.performHapticFeedback` / `HapticFeedbackType` in Compose (no permission); use the Flutter engine mapping (PlatformPlugin.java, table in spec 12 §8.11): selectionClick→`CLOCK_TICK`, lightImpact→`VIRTUAL_KEY`, mediumImpact→`KEYBOARD_TAP`, heavyImpact→`CONTEXT_CLICK`, via the shared `Haptics` helper | n/a |
| QR scan (VerifyPerson) | `CAMERA` | CameraX + ML Kit Barcode Scanning (or ZXing for de-Googled devices) |
| Background (optional) | none | WorkManager for admin-registry refresh / organic-claim sync instead of fire-and-forget on Home; coroutines `Semaphore(3)` for batch parallelism |
| Graph drawing | none | Compose `Canvas` + `rememberInfiniteTransition` (3 s reverse pulse) |
| UI | n/a | Material 3 Expressive: `ModalBottomSheet`, `AlertDialog`, `PullToRefreshBox`, `SegmentedButton`/`FilterChip` row, `LoadingIndicator` |

---

## 12. Cross-domain dependencies

**Calls OUT of this domain:**

| Domain / component | What for |
|---|---|
| Signing (`SigningService`) | `canSign()`, `npub()`, `signEvent(kind, tags, content) → SignedEvent{id,pubkey,createdAt,kind,tags,content,sig}`; all four modes (local/Amber/NIP-07/NIP-46) |
| Identity (`NostrService`) | `getNpub()` (delegates to SigningService.npub), `isValidNpub`, `npubToHex`, `shortenNpub` |
| Relay infra | `RelayConfig.getActiveRelays()`, `relayTimeout` 8 s, `publishTimeout` 5 s; `RelaySocket.connect/listen/add/close/stream`, `verifiedEvent`, `answersFilter`, `replaceableCreatedAt`; `RelayParseTally`; `AppLogger` (debug/diag/info/warn/error/security) |
| Badges (`MeetupBadge`) | fields/derivations (§3.6), `loadBadges()`, `saveBadges()`, `keepAside()` |
| Badge crypto | `BadgeSecurity.verify(checkExpiry:false)`, `normalize`, `canonicalJsonEncode`; `BadgeClaimService.verifyClaim` |
| Meetup key | `MeetupKey.slug`, `compose`, `eventId` (parse via normalize) |
| Trust score | `TrustScoreService.calculateScore(badges, firstBadgeDate)` → `meetsPromotionThreshold`, `totalScore`, `activeThresholds.promotionScore` |
| Mempool | `MempoolService.getBlockHeight()` (organizer marker badge) |
| Rolling QR / sessions | `RollingQRService.endSession()`, `getOrCreateSession(meetupId, meetupName, meetupCountry, blockHeight, lat, lng)` → `MeetupSession?{blockHeight,…}`; pref `rqr_session_expires`; screens `RollingQRScreen`, `MeetupSessionWizard` (NFC disabled) |
| Location / meetups / portal | `MeetupLocationService.resolveLocation()` (`GpsStatus`, `withinCreationRadius` ≤10 km), `openLocationSettings`, `openAppSettings`; `MeetupService.fetchMeetups()`, `allMeetups`; `Meetup{city,name,country,lat,lng,groupName}`; `PortalApiService.getMyMeetups()` |
| Profiles | `NostrProfileService.cachedDisplayNames()`, `fetchDisplayNames(hexes)` |
| User | `UserProfile.load()` → `nostrNpub`, `promotionSource`, `isAdmin`, `isReviewDemo` |
| UI kit | `HapticService`, `NpubChip` (+`shorten`), theme colours |
| Navigation | `VerifyPersonScreen` |

**Calls INTO this domain:**

| Caller | Uses |
|---|---|
| Home | tiles → `MyNetworkScreen`, `AdminPanelScreen`; `PromotionClaimService.syncOrganicAdmins()` on load; `publishAdminClaim` on portal grant / trust-score verification; `AdminRegistry.removeFromCache(ownNpub)` on portal revocation; `UserProfile.reVerifyAdmin` → `AdminStatusVerifier.verifyAdminStatus` |
| Badge scanner (`meetup_verification`) | `AdminRegistry.checkAdminByPubkey` (known / unavailable / unknown); `CoAttendanceService.publishAttendance` after opt-in |
| Event badges | chain: `checkAdminByPubkey(event.pubkey)`; auth: `checkAdmin(npub)`; session: `recordOrganizerAttendance(isEvent:true)` |
| Backup | `getAdminList()` export; `addAdmin` + `forceRefresh()` on restore |
| Reputation publisher | `SocialGraphService.getMyStats()` → `content.social` |
| Reputation verify / QR scanner / layers widget | `SocialGraphService.analyze(hex)` → `SocialAnalysis` (`socialScore`, flags) |
| VerifyPersonScreen | `CoAttendanceService.verifyPerson` |

---

## 13. Proposed Kotlin package / module structure and public API

```
:core:nostr            (shared; owned by infra spec — listed for clarity)
  nostr/Event.kt               NostrEvent, Nip01Serializer (Dart-compatible escaping), EventVerifier.isValid (10-digit rule)
  nostr/Nip19.kt               encodeNpub(hex), decodeNpub(npub): String? (null for non-npub)
  nostr/Filter.kt              answersFilter(event, filter), replaceableCreatedAt(event, now)
  nostr/RelayClient.kt         suspend fun query(relay, filter, subPrefix, timeout): RelayQueryResult(events, state)
                               suspend fun publish(relay, event, connectTimeout, okTimeout, requireIdMatch): PublishOutcome
  nostr/SubscriptionIds.kt     newSubId(prefix)

:domain:socialgraph    package space.einundzwanzig.meetup.socialgraph
  SocialGraphRepository
    suspend fun getMyFollows(forceRefresh: Boolean = false): Set<String>
    suspend fun fetchContactList(pubkeyHex: String): Set<String>
    suspend fun analyze(targetPubkeyHex: String): SocialAnalysis
    suspend fun getMyStats(): SocialStats
  model/SocialAnalysis.kt, model/SocialStats.kt (+ JSON codec "follows","mutuals","org_followers")
  data/FollowCacheStore (keys social_my_follows, social_cache_ts)

:domain:coattendance   package space.einundzwanzig.meetup.coattendance
  AttendanceKeys (pure object)
    const val EVENT_PREFIX = "ev|"
    fun attendanceKey(meetupEventId: String, signerNpub: String, isEvent: Boolean = false): String
    fun sharedKeys(a: Set<String>, b: Set<String>): Set<String>
    fun keysMatch(a: String, b: String): Boolean
    fun wantedKeys(keys: Iterable<String>): Set<String>
    fun isDegenerateEventId(id: String): Boolean
    fun isEventKey(key: String): Boolean
  AttendanceKeyLabel (pure object): date, place, dateText, label, newestFirst, newest
  NetworkCalculator (pure): adjacency(nodes), networkFromGraph(myNpub, nodes, maxDepth, updatedAt), mergeStage(fresh, previous, doneDepth), shortestPath(...)
  GraphCrawler: suspend fun loadGraph(myNpub, extraNpubs, maxDepth, cache: GraphCache?, fullSync: Boolean, onStage: (Map<String,CoAttNode>, Int) -> Unit): GraphRun
  CoAttendanceRepository
    suspend fun publishAttendance(badge: MeetupBadge): Int
    suspend fun recordOrganizerAttendance(meetupName: String, date: ZonedDateTime/LocalDateTime, blockHeight: Int = 0, lat: Double = 0.0, lng: Double = 0.0, isEvent: Boolean = false): MeetupBadge?
    suspend fun publishStatus(): Map<String, Int>
    suspend fun failedBadges(all: List<MeetupBadge>): List<MeetupBadge>
    suspend fun retryFailed(all: List<MeetupBadge>): Int
    suspend fun cachedNetwork(myNpub: String, maxDepth: Int = 3): MyNetwork?
    fun buildMyNetwork(myNpub: String, maxDepth: Int = 3, forceFull: Boolean = false): Flow<NetworkBuildEvent>   // Stage(net, depth) … Done(net); shared per owner (job coalescing via a per-owner SharedFlow/Deferred)
    suspend fun verifyPerson(myNpub: String, targetNpub: String, maxDepth: Int = 6): PresenceCheck
    suspend fun analyze(myNpub: String, targetNpub: String): CoAttNetwork   // optional
  data/GraphCacheStore (coatt_graph_cache_v1, JSON v1), data/PublishStatusStore (coatt_publish_status)

:domain:admin          package space.einundzwanzig.meetup.admin
  AdminRegistry (singleton)
    const val SUPER_ADMIN_NPUB, SUNSET_THRESHOLD = 20
    suspend fun checkAdmin(userNpub: String): AdminCheckResult
    suspend fun checkAdminByPubkey(pubkeyHex: String): AdminCheckResult
    suspend fun fetchFromRelays(): List<AdminEntry>?
    suspend fun isSunsetActive(): Boolean
    suspend fun getAdminList(): List<AdminEntry>
    suspend fun removeFromCache(npub: String)
    suspend fun addAdmin(entry: AdminEntry)        // backup restore
    suspend fun removeAdmin(npub: String)
    suspend fun forceRefresh(): Int
    suspend fun cacheAge(): Duration?
    suspend fun clearCache()
    // legacy vouches (optional): getMyVouches/addVouch/removeVouch/recoverMyVouchesFromRelays/revokeAllVouches/createAndPublishAdminListEvent/createAdminListEvent
    @VisibleForTesting var relayFetchOverride: (suspend (relayUrl: String, authorsHex: List<String>) -> Map<String, List<AdminEntry>>?)?
  AdminRegistryStore (all registry/sunset keys)
  AdminStatusVerifier
    suspend fun verifyAdminStatus(badges: List<MeetupBadge>): AdminVerification
    suspend fun isVerifiedAdmin(badges: List<MeetupBadge>): Boolean
  PromotionClaimService
    suspend fun publishAdminClaim(badges: List<MeetupBadge>, meetupName: String): Boolean
    suspend fun syncOrganicAdmins(): List<VerifiedClaim>
    suspend fun getOrganicAdmins(): List<VerifiedClaim>
    fun verifyClaim(claim: RawClaim, knownAdminPubkeys: Set<String>, nowSeconds: Long = now()): VerifiedClaim?   // pure, injectable clock

:feature:network       package space.einundzwanzig.meetup.ui.network
  MyNetworkScreen(onCheckPerson: () -> Unit), MyNetworkViewModel (state = loading/updating/doneDepth/updateFailed/net/failedCount/retrying/names/filter/query/expanded/highlight)
  NetworkTreeCanvas (layout per §2.1, hit-test 24 dp→px), ContactDetailSheet, HowItWorksSheet
  VerifyPersonScreen(initialTargetNpub: String?) + QrNpubScanner

:feature:organizer     package space.einundzwanzig.meetup.ui.organizer
  AdminPanelScreen, AdminPanelViewModel (session countdown via ticker Flow), StartSessionFlow (state machine of §2.2.3), dialogs per §2.2.2
```
Design notes:
- Keep all pure algorithms (keys, labels, BFS, claim verification) free of Android dependencies so they can be unit-tested in JVM tests.
- Inject `Clock` (now) and `SecureRandom`.
- Use `LinkedHashSet` and `LinkedHashMap` everywhere Dart used `{}` literals, because order affects caps (first 60/300/10/20/50), tie-breaking, and display.

---

## 14. Risks / compatibility pitfalls

1. **Event-ID serialization.** Any deviation from Dart `jsonEncode` escaping (Gson HTML escaping, escaped `/`, uppercase `\u00XX`) produces different ids. Also keep the `created_at` 10-digit validity rule; dropping it changes which events are accepted.
2. **Number re-encoding in claim proofs.** Re-serialize parsed numbers exactly as Dart does (§7.5). Otherwise valid admin claims are rejected (or the reverse).
3. **Attendance key compatibility.**
   - Keep the last-12-chars suffix, the `ev|` prefix, `@` as the separator, and the **local** date in `YYYY-MM-DD`.
   - Keep the slug rule `trim().lowercase().replace(' ', '-')`. Only the space is replaced; umlauts and other punctuation are kept. Lowercasing should be locale-independent (`lowercase(Locale.ROOT)`); Dart `toLowerCase` is locale-independent.
   - Any change disconnects native users from Flutter users in the graph.
4. **Existing key quirks to preserve (do not "fix" silently):**
   - A free-text organizer name ending in an ISO-like token (`"Treff DE"` → slug `treff-de`) is split differently by participants (`treff`). Organizer and participant keys then differ.
   - Sessions crossing midnight produce different dates for organizer and participants.
   - The event-helper organizer key is `ev|<title-slug>-<date>`, while participant event keys are `ev|evt|<address>`. They never match, but events don't create edges, so the impact is cosmetic.
5. **Two relay sources.** The admin registry and promotion claims always use the 4 hard-coded relays. The social graph and co-attendance use the user-configured relays. Merging these would change the trust behavior.
6. **Admin registry merges multiple events per signer** instead of taking the newest. A relay that returns stale versions can resurrect revoked entries. A port that takes only the newest per signer is safer but changes results. Decide explicitly, document it, and keep the tests green either way.
7. **Sunset-phase query chunk of 50.** Admins beyond the first 50 cached are never queried, and in the sunset phase their entries are dropped by the merge. Preserve this, or paginate deliberately; pagination changes which admins remain.
8. **`fetchFromRelays` stops at the first relay with any queried-signer answer.** Results can differ by relay. An empty `{}` answer moves on to the next relay.
9. **Promotion-claim fetch.** The first relay with a non-empty list wins, there is no `answersFilter`, the limit is 100, and a timeout discards partial results. Claims on other relays are never seen. Keep this.
10. **Contact-list cache is not owner-keyed** (`social_my_follows`). After an identity switch, follows can be stale for up to 2 h. Keying the cache by pubkey is a safe improvement.
11. **`countOrgFollowers` calls `AdminRegistry.fetchFromRelays()`**, which records sunset authors as a side effect. Social analysis therefore influences sunset counting, which is correct but surprising. Do not replace it with `getAdminList()` without considering this.
12. **Self-verification side effects.** `AdminStatusVerifier` calls `checkAdmin`, which may hit the network and refresh the cache on every Home load. If you short-circuit it for performance, schedule the registry refresh elsewhere (WorkManager).
13. **Job coalescing.** Opening the network screen twice, or pull-to-refresh during a build, must attach to the running build, not start a second crawl. A `forceFull` request while a non-forced job is running is **ignored** (it attaches). Preserve this, or queue a forced run afterwards.
14. **Concurrency.** The Dart graph mutation is single-threaded, using async interleaving. In Kotlin, use a single-threaded dispatcher or a mutex for `graph`, `knownAuthors`, `knownKeys`, and the counters, while still allowing 3 batches × N relays of I/O in parallel.
15. **Unstable sorts.** Dart `List.sort` is unstable, so ties in degree-1 strength and bridge counts may order differently. Ties also determine BFS parents, so paths for ties may differ. This is acceptable, but tests must avoid tie-dependent assertions.
16. **Display npub source.** MyNetworkScreen and VerifyPersonScreen use `UserProfile.nostrNpub` (pref `nostr`), while publishing uses `SigningService.npub()`. If these diverge (signer switched), the network is built for the profile npub. Preserve this, or unify via the identity spec.
17. **`Nip19.decodePubkey` leniency.** Dart returns `""` for a wrong HRP, and `isValidNpub` doesn't check the length. Kotlin returning `null` or throwing is fine as long as the callers' fallbacks match: `""` → treated as invalid.
18. **Admin panel blocking.** `recordOrganizerAttendance` is awaited under a modal spinner. It can take up to about 5 s connect plus 6 s OK, plus a mempool fetch. If `getOrCreateSession` returns null, the flow still navigates to the QR screen (existing behavior). Consider surfacing an error, coordinating with the session spec.
19. **Barrier dismissal of the portal picker = "enter manually".** Tapping outside the list continues into the manual-name path (or the "Standort nötig" dialog). Preserve this, or make it an explicit abort. It is a UX decision; document it.
20. **"Retry" in the GPS dialogs re-enters `_startNewSession()` recursively** while the outer invocation returns. With Compose state machines, make sure only one flow instance is active.
21. **In-place upgrade.** If the native app reuses `applicationId space.einundzwanzig.meetup`, it must read `FlutterSharedPreferences.xml` (the `flutter.` prefix; the list prefix `VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu!` + JSON) for this domain's keys. Otherwise:
    - the K1 reset runs again (harmless);
    - the sunset set is lost (harmless, it rebuilds);
    - **publish status is lost**, so failed attendances can no longer be retried;
    - the network cache is lost, so the first load is slow.
22. **Time zones.** Attendance dates and labels use **local** dates. Claim session days use **UTC** without zero padding. Proof `date` and the admin list `updated_at` use local ISO without an offset. Mixing these up breaks either matching or the ≥3-sessions rule.
