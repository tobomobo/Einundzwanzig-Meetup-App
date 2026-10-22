# Domain 11 — Chat, News & Community (native Android porting spec)

Source of truth (Flutter, read completely):

| File | Role |
|---|---|
| `lib/services/chat_service.dart` | NIP-29 group chat on `wss://group.einundzwanzig.space` incl. NIP-42 AUTH, room lookup, membership, read marks, unread counts |
| `lib/screens/chat_screen.dart` | One chat UI for two sources: NIP-29 meetup room and NIP-22 event comment thread |
| `lib/services/event_chat_service.dart` | NIP-22 (kind 1111) comment threads hanging on NIP-52 calendar events |
| `lib/services/news_service.dart` | RSS feed of media.einundzwanzig.space, unread counter, full-text fetch of NIP-23 (kind 30023) |
| `lib/screens/news_screen.dart` | News list + article detail (Markdown, like, zap, share) |
| `lib/services/news_reactions_service.dart` | NIP-25 likes on articles; also the shared "read targets" relay list used by other domains |
| `lib/services/news_zap_service.dart` | NIP-57 zap request + LNURL-pay (LUD-16) for article authors; lightning address lookup |
| `lib/screens/community_hub_screen.dart` | Community hub, Portal area, Courses/Lecturers (+details), unreachable PortalEventsScreen |
| `lib/services/v4v_service.dart`, `lib/screens/v4v_screen.dart` | Value-for-Value donation via LNURL-pay |
| `lib/services/plebrap_audio.dart`, `lib/screens/plebrap_player_screen.dart` | App-wide PlebRap music player (41 hard-coded songs) |
| `lib/screens/glossary_screen.dart` | Searchable in-app glossary (55 entries, 8 categories) |
| `lib/widgets/markdown_view.dart` | Dependency-free safe Markdown renderer (used for article bodies) |

Helpers followed: `relay_socket.dart` (verification/filter binding), `signing_service.dart` (SignedEvent/sign/pubkey/mode), `nip46_client.dart` (`signTimeout` = 60 s), `relay_config.dart`, `safe_url_launcher.dart`, `share_origin.dart`, `portal_api_service.dart` (subset), `meetup_calendar_service.dart` (`portalStart`), `satoshiduell_service.dart`, `guide_service.dart`, `tours/more_tours.dart`, `mixins/guide_service_host.dart`, `widget_service.dart` (news part), callers in `home_screen.dart`, `my_events_screen.dart`, `event_calendar_screen.dart`, `settings_screen.dart`, Android `MainActivity.kt` (`payLightningInvoice`), `AndroidManifest.xml`, `network_security_config.xml`, nostr-dart 1.5.0 `Event.isValid()`.

---

## 1. Overview and user-visible features

The domain bundles everything "social / content" that is not badges or reputation:

* **Meetup group chat (NIP-29)** on Einundzwanzig's dedicated group relay. Rooms are relay-managed objects (kind 39000 metadata, kind 39002 members, relay-signed). The relay requires NIP-42 authentication even to READ. Membership is relay-authoritative; writing requires association ("Verein") membership which the relay enforces.
* **Event chat (NIP-22)**: comment thread (kind 1111) attached to a NIP-52 calendar event address `<kind>:<pubkey>:<d>`, on the app's normal relays, no membership.
* **News**: RSS list from media.einundzwanzig.space; full article text fetched from Nostr (kind 30023) and rendered with an in-house Markdown renderer; NIP-25 hearts; NIP-57 zaps to author; share.
* **Community hub**: tiles to Portal area, News, Nostr (njump), Shoutout, Podcast, SatoshiDuell (with live duel status), PlebRap.
* **Portal area**: rows to Meetups (calendar), Events (calendar), Courses & Lecturers, Map, My Meetups, Portal website.
* **Courses & lecturers** (portal API, read-only) with detail screens and add-to-calendar.
* **V4V donation**: free amount → LNURL-pay invoice → Android wallet chooser; copy fallback.
* **PlebRap player**: 41 songs, playlist, seek after full caching, global mini-player on dashboard.
* **Glossary**: searchable help.

Full feature checklist (each line is user-visible):

Chat
1. Open meetup chat from a favorite meetup card on the dashboard ("Chat" action) — shows "Chatraum wird gesucht …" snackbar while resolving.
2. Room lookup by portal meetup id (`["i","meetup:<id>"]`), fallback by city slug for legacy favorites.
3. Distinct error snackbars: offline (`chatOffline`), relay/auth failure (`chatRelayUnavailable`), no room (`chatNoRoom(label)`).
4. Unread badge per favorite on the dashboard "Chat" action (count of others' messages since local read mark; 7-day window if never opened).
5. Chat screen header: room name (or `h`), subtitle "Gruppen-Relay von Einundzwanzig".
6. Message list oldest→newest, auto-scroll to bottom on load and on every new message.
7. Message bubble: own right/orange tint, others left with avatar (kind-0 picture or initial), header line "name · time" above the bubble; own name shown as "Du".
8. Display names load lazily per sender (once per pubkey); fallback short hex key `abcdef…1234`.
9. Time label: `HH:mm` today, otherwise `d.M. HH:mm`.
10. Tap a sender header → bottom sheet: avatar, name, short npub chip; tap chip → copy full npub, close sheet, snackbar "npub kopiert.".
11. Live updates via open subscription (closed when leaving the screen).
12. Optimistic display of own sent message (temporary id `lokal-<micros>`), with duplicate suppression when the relay echoes it.
13. Non-member footer: explanation, association hint box (tap → opens `https://verein.einundzwanzig.space/association/profile`), "Dem Raum beitreten" button with spinner.
14. Join: publish kind 9021, then re-query membership from relay; rejection snackbar (red, 6 s) with action "Zum Verein".
15. Member footer: rounded input (1–4 lines, IME action send), circular orange send button with spinner; red snackbar on failure "Nachricht nicht angekommen: <reason>".
16. Empty state texts differ for room vs. event thread.
17. Read mark persisted locally when messages are loaded/received (never published).
18. Event chat: opened from calendar event sheet ("Chat zum Termin" button, Nostr events only) and from "Meine Termine" list; header = event title, subtitle "Beiträge zum Termin · öffentlich auf Nostr"; always writable.
19. Dashboard tile "Meine Termine" shows aggregated unread count of event chats (orange number + "{count} neue Nachrichten").

News
20. Dashboard News tile: newest headline as value, red unread badge (articles newer than last visit; 0 on very first run).
21. Opening News from the dashboard tile clears the unread counter and the home-screen-widget "NEU" marker.
22. News screen: two equal action tiles "Artikel schreiben" (→ https://media.einundzwanzig.space) and "Auf der Webseite öffnen" (→ https://media.einundzwanzig.space/s/einundzwanzig-news), each with subtitle "media.einundzwanzig.space".
23. Pull-to-refresh; loading state "Lade Artikel …"; empty state icon + "Keine Artikel gefunden."; footer "Artikel via Nostr (NIP-23)".
24. Article cards: optional 150-dp cover image, title, 3-line summary, author (or "Einundzwanzig"), date `d.M.yyyy`.
25. Article detail: cover, title, author/date, full Markdown body loaded from Nostr; while loading: summary + spinner; if no full text: summary + orange button "Auf der Webseite lesen" (hard-coded German).
26. Heart button (NIP-25): shows count when loaded and >0, else "Gefällt mir"; once liked it stays filled (no un-like); spinner while sending; red snackbar on failure.
27. Zap button: amount sheet (21 / 210 / 2100 / 21000 sat) → zap invoice → `lightning:` intent; if no wallet: sheet with invoice + "Rechnung kopieren"; specific error snackbars.
28. Share button: system share "title\n\nurl"; on failure copy to clipboard + snackbar "Link in die Zwischenablage kopiert" (orange).
29. Article body render failure never crashes: falls back to plain selectable text.

Community / Portal / Courses
30. Community hub with first-run spotlight tour (4 steps).
31. Portal big card (→ Portal area), News, Nostr (→ njump profile), Shoutout, Podcast tiles (external browser).
32. SatoshiDuell card: opens `https://satoshiduell.de/?npub=<npub>` (auto-login) or plain URL; subtitle shows colored live status "⚡ n dran · n in der Lobby · n warten auf Gegner"; gold count badge = myTurn + lobby.
33. PlebRap wide card (→ player).
34. Portal area screen with 6 rows and first-run tour (4 steps).
35. Courses screen with tabs Kurse/Dozenten, pull-to-refresh, empty states, detail navigation.
36. Course detail: header, "Im Portal öffnen", upcoming dates with add-to-device-calendar and link icons, about text, extracted links, lecturer card.
37. Lecturer detail: header, about, course list, links (Website / X / Nostr / Portal).

V4V
38. V4V screen (from Settings and the dashboard settings sheet): explanation, big numeric amount field, donate button, recipient line; inline red error text; wallet chooser; copy fallback sheet with snackbar "Rechnung kopiert".

PlebRap
39. Player screen: now-playing card (cover watermark, cover thumb, title/artist), seek slider (enabled only when duration known), position/duration labels, caching progress hint, prev/play-pause/next, album-grouped track list with active highlight; AppBar action "Sats an die Künstler" (→ https://plebrap.de/V4V/).
40. Playback continues after leaving the screen; dashboard PlebRap tile is a mini player (play/pause, next, open library).
41. Broken tracks are skipped automatically; snackbar "Song konnte nicht geladen werden" on the player screen.

Glossary
42. Glossary ("Nachschlagen", opened via "?" icon in dashboard header): search field with clear button, category headers, collapsible entries, all entries auto-expanded while searching, empty-search-result text.

No haptics, no confirmation dialogs exist in this domain.

---

## 2. Screens & UX flows

Colors referenced (from `lib/theme.dart`): `cDark #0C0C0E`, `cCard #131315`, `cSurface #0F0F11`, `cOrange #F7931A`, `cNostr #8B5CF6`, `cPurple #9B45E8`, `cCyan #D4A017` (warm gold), `cGreen #22C55E`, `cRed #E53935`, `cText #FFFFFF`, `cTextSecondary #9CA0A8`, `cTextTertiary #868A94`, `cBorder #1C1C22`, `cTileBorder #202028`, `kTileRadius = 10`. SatoshiDuell gold `#FFC93C`. The native app may restyle for Material 3 Expressive, but must keep content, order, states and behaviour.

### 2.1 ChatScreen (`ChatScreen.room(room)` / `ChatScreen.event(eventAddress, eventAuthor, title)`)

**Purpose**: one UI for two backends. `isEvent = eventAddress != null`.

**Entry points**
* Dashboard favorite card → "Chat" action → `_openMeetupChat(favKey, label)` (home_screen):
  1. Show snackbar `chatSearching` (4 s, card bg).
  2. `room = _findRoomFor(favKey)`: `meetup = MeetupService.resolveFavorite(favKey)`; if meetup ≠ null → `ChatService.findRoomForMeetupId(meetup.id)`; if that returned non-null use it; otherwise `ChatService.findRoomForCity(MeetupService.cityFor(favKey))`. A thrown `ChatRelayUnavailable` propagates (NO city fallback).
  3. On `ChatRelayUnavailable e`: hide current snackbar; show `e.offline ? chatOffline : chatRelayUnavailable` (5 s).
  4. room == null → hide snackbar; show `chatNoRoom(label)` (4 s) where label = `MeetupService.labelFor(favKey)`.
  5. Else push `ChatScreen.room(room)`; after returning, recompute dashboard unread (`_loadChatUnread`).
  The same callback is passed to `MyEventsScreen` (`onOpenMeetupChat`).
* Calendar event details sheet (event_calendar_screen) — only for Nostr calendar events: full-width outlined button, icon `forum`, label `chatEventOpen`, purple; pops the sheet then pushes `ChatScreen.event(eventAddress: event.address, eventAuthor: event.pubkey, title: event.title)`. `event.address = "$kind:$pubkey:$dTag"`.
* MyEventsScreen list item tap → `ChatScreen.event(...)`; after return recompute unread (`EventChatService.unreadCounts`).

**Layout**
* AppBar (bg cDark, no elevation, no actions): Column(title 15sp w700 1 line ellipsis = event ? `title` : (room.name non-empty ? room.name : room.h); subtitle 10.5sp tertiary = event ? `chatEventHint` : `chatRelayHint`).
* Body column:
  * Expanded area: `_loading` → centered orange spinner. Else if no messages → centered text (32 padding, 14sp, tertiary, line-height 1.5): event ? `chatEventEmpty` : `chatEmpty`. Else list (padding 12,12,12,8) of bubbles.
  * Footer (see below).

**Bubble** (`_bubble(m)`): `mine = m.pubkey == myPubkey`. Row aligned end (mine) / start. If not mine: avatar (radius 14, `pubkeyHex = m.pubkey`, fallback letter = first char of (name ?? pubkey) uppercased) + 8 dp gap. Column:
* Header (tappable → author sheet): text = mine ? `chatYou` : (name non-empty ? name : shortKey(pubkey)), color mine ? orange : cNostr, 11.5sp w700; 7 dp; time label 10.5sp tertiary.
* Bubble: padding 12h/9v, radius 12, bg mine ? orange@0.16 : cCard, border 0.5 mine ? orange@0.35 : tileBorder; text 14sp, line-height 1.4. Content rendered as plain text (no Markdown, no link detection).
* Bottom spacing 12 between messages.

`shortKey(p) = p.length > 12 ? p[0..6] + "…" + p[last 4] : p` (U+2026).
`timeLabel(d)`: local time; same calendar day as now → `HH:mm` (zero-padded); else `"${d.day}.${d.month}. HH:mm"` (day/month NOT padded, e.g. `5.3. 09:07`).

**Author sheet** (`_showAuthor(pubkey, name)`): npub = NIP-19 encode(pubkey) (fallback: raw pubkey if encoding throws); display = name non-empty ? name : shortKey. Modal bottom sheet (cCard, top radius 16, SafeArea, padding 20,18,20,24): avatar radius 26 (fallback = first letter of display), 12 dp, display 18sp w700 centered, 10 dp, chip (surface bg, radius 9, border tileBorder 0.5, padding 12h/8v) with `shortNpub(npub)` monospace 12sp secondary + copy icon orange 15. Tap chip → clipboard = full npub, close sheet, snackbar `chatNpubCopied`.
`shortNpub(n) = n.length > 20 ? n[0..12] + "…" + n[last 6] : n`.

**Footer**
* `_loading` → empty 24 dp box (deliberately no join button while membership unknown).
* Not member (room only): SafeArea, padding 16: `chatJoinHint` (12.5sp secondary, centered); 10 dp; tappable box (orange@0.08 bg, radius 10, orange@0.3 border 0.5, padding 12) containing `chatMemberHint` (12sp) + row(open_in_new icon 14 + `chatMemberLink` 12sp w700 orange) → `launchHttpUrl(kVereinUrl)`; 12 dp; full-width 48 dp orange button (black fg, radius kTileRadius) label `chatJoin` (w800) or black spinner (20 dp, stroke 2.4) while joining; disabled while joining.
* Member (or event thread): SafeArea, padding 12,4,12,12: TextField (15sp, minLines 1, maxLines 4, imeAction send → `_send`, hint `chatPlaceholder`, filled cCard, radius 24, border tileBorder 0.5, focused orange 1.4) + 8 dp + 48×48 circular orange button (send icon black 20 / black spinner 18 dp stroke 2 while sending, disabled while sending).

**Load sequence (`_load`)**
1. `myPubkey = SigningService.pubkeyHex()`.
2. `msgs = isEvent ? EventChatService.loadMessages(address) : ChatService.loadMessages(room.h)` (sorted ascending).
3. `member = isEvent ? true : ChatService.isMember(room.h)`.
4. setState: messages = msgs, seenIds = their ids, member, loading=false; scroll to end (post-frame `jumpTo(maxScrollExtent)`); start `_loadNames()` (not awaited).
5. If msgs non-empty: `ChatService.markRead(readKey, msgs.last.createdAt)` where `readKey = isEvent ? "evt|$eventAddress" : room.h`.
6. `since = msgs non-empty ? msgs.last.createdAt : now`. Subscribe (`EventChatService.subscribe` or `ChatService.subscribe`) with `onNew(m)`:
   * ignore if screen gone, `seenIds` contains `m.id`, or `_isDuplicate(m)`;
   * add, sort ascending by createdAt, scroll to end, `_loadNames()`, `markRead(readKey, m.createdAt)` (fire-and-forget).
   Exceptions from subscribe are swallowed (chat stays readable without live updates).
7. On dispose: call unsubscribe (closes socket).

**`_loadNames()`**: unknown = distinct message pubkeys not yet in `names`; sequentially `NostrProfileService.fetchDisplayName(pubkey)`; set when non-empty. (Each pubkey fetched at most once per screen instance; the profile service has its own RAM cache.)

**`_isDuplicate(m)`**: only for own messages: return false if `m.pubkey` empty or ≠ myPubkey; else true if any existing message has same pubkey, same content and `|x.createdAt − m.createdAt| < 2 minutes`.

**`_send()`**: text = input.trim(); ignore if empty or already sending. sending=true. err = isEvent ? `EventChatService.send(eventAddress, eventAuthorPubkey = eventAuthor, text)` : `ChatService.send(room.h, text)`. sending=false. If err == null: clear input; build local message `ChatMessage(id: "lokal-${nowMicros}", pubkey: myPubkey ?? "", content: text, createdAt: now)`; add it only if `!_isDuplicate(local)` (relay may already have echoed it via the live subscription); scroll to end. Else red snackbar `chatSendFailed(err)`.

**`_join()`**: joining=true; `err = ChatService.join(h)`; `member = err == null ? ChatService.isMember(h) : false`; joining=false; set member. If err != null: snackbar `chatJoinFailed(err)`, bg red, 6 s, action `chatMemberLink` (white) → `launchHttpUrl(kVereinUrl)`. Note: if the join is accepted but the member list is not updated yet, the footer silently stays in "not member" state (no message).

### 2.2 Dashboard hooks (implemented by the dashboard domain, data from this domain)

* `_loadChatUnread()`: favKeys = ordered set {homeMeetupId (if non-empty), ...favoriteMeetupIds}. For each favKey: `room = _findRoomFor(favKey)`; `byRoom[room.h] = favKey` (later favKey wins if two map to same room). If any: `counts = ChatService.unreadCounts(byRoom.keys)`; `_chatUnread[favKey] = count`. Any exception → silent (debug log). Triggered at dashboard init/refresh/resume and after returning from a room.
* Favorite card action bar: three equal fields "Termine" | "Chat" (icon forum, cNostr, badge = `_chatUnread[card.key] ?? 0`) | "Info".
* "Meine Termine" tile: accepted RSVP events (start not older than 1 day) → `EventChatService.unreadCounts(addresses)` → sum → value colored orange and sub `tileEventChatsUnread(sum)` if > 0, else `tileEventChatsSub`; `tileEventChatsNone` if nothing planned.
* News tile: `NewsService.unreadCount()` + `NewsService.cachedArticles()`; value = title of newest article by `publishedAt` (`reduce(a.publishedAt >= b.publishedAt ? a : b)`), else `tileNewsSub`; trailing red pill with count if > 0. Tap → `WidgetService.markNewsSeen()`, `NewsService.markRead()` (not awaited), set unread 0, push NewsScreen.
* PlebRap tile = mini player (see 2.11). Community tile → CommunityHubScreen. Portal-area tile → PortalAreaScreen. Header "?" icon → GlossaryScreen. Settings sheet group `v4vSectionTitle` row "V4V"/`v4vSectionSubtitle` → V4VScreen.

### 2.3 NewsScreen

Entry: dashboard News tile (`_openNews`), Community hub News card (this path does NOT call markRead/markNewsSeen).

Layout (Scaffold cDark; AppBar title `newsTitle`; body pull-to-refresh (orange on cCard) ListView padding 16):
1. Action tiles row, equal width, equal height (IntrinsicHeight), gap 12: tile = gradient (#2A1E0A → cCard, TL→BR), radius 10, orange@0.4 border 0.5, padding 16v/12h; 44×44 icon box (orange@0.15, radius 12, icon orange 22); 10 dp; label 14sp w700 max 2 lines centered; 2 dp; literal "media.einundzwanzig.space" 11sp tertiary 1 line.
   * Left: icon `edit_note`, `newsWriteArticle` → open `https://media.einundzwanzig.space`.
   * Right: icon `public`, `newsOpenWebsite` → open `https://media.einundzwanzig.space/s/einundzwanzig-news`.
   Opening: `canLaunchUrl` then `launchUrl(external)`; silently nothing if not launchable.
2. 16 dp.
3. Loading: top 60 padding, orange spinner, 14 dp, `newsLoading` 13sp secondary. Empty: top 60, icon `article_outlined` 44 tertiary, 12 dp, `newsEmpty` 14sp secondary. Else article cards.
4. 8 dp; centered `newsSource` 11sp tertiary; 20 dp.

Data: `_load()` → `loading=true`; `NewsService.fetchArticles()` (limit 50, NOT the cached variant); set list.

Article card (tap → ArticleDetail): cCard, radius 10, border tileBorder 0.5, clip; if image non-empty: network image height 150 full width cover (error → nothing, loading placeholder 150 surface); padding 14: title 16sp w800 h1.3; if summary non-empty: 6 dp + summary 13sp secondary max 3 lines ellipsis; 10 dp; row: person icon 13 + author label 11sp tertiary, spacer, date `"${d.day}.${d.month}.${d.year}"` 11sp tertiary (local time of `publishedAt`). Bottom margin 14.

`authorLabel(a) = a.pubkey (author NAME from RSS) or "Einundzwanzig" if empty`.

### 2.4 ArticleDetail (private route pushed from NewsScreen with the `NewsArticle`)

State: fullContent (String?), loading (true), likes (ArticleLikes.empty), likesLoaded (false), liking, zapping.

`authorPubkey = article.id.split(':')` → parts.length ≥ 2 ? parts[1] : "" (guid is `30023:<pubkey>:<d>`).

init (parallel): `_loadFullArticle()` (= `NewsService.fetchArticleContent(article.id)`), `_loadLocalLike()` (if `NewsReactionsService.hasLikedLocally(article.id)` → likes = ArticleLikes(count: likes.count, mine: true)), `_loadLikes()` (→ `fetchLikes(article.id)`; likesLoaded = true).

Layout (AppBar title `newsTitle` 16sp; ListView padding 20):
1. If image: rounded (10) network image, cover, natural height (error → nothing); 18 dp.
2. Title 24sp w800 h1.25; 10 dp; row: person icon 14 + author label 12sp + 12 dp + date `d.M.yyyy` 12sp (tertiary).
3. 20 dp; content:
   * loading: summary (15sp secondary h1.5, if non-empty) + 20 dp + centered orange spinner;
   * fullContent non-null and non-blank: `SafeArticleBody(fullContent)` (MarkdownView with render-error fallback to `SelectableText(content)` 15sp h1.6 vertical padding 12);
   * else: summary (if any) + 20 dp + full-width orange button (radius 10, padding 14v) icon `open_in_new` black 18 + "Auf der Webseite lesen" (hard-coded, black 15sp w700) → open `articleUrl(article)`.
4. 24 dp; action bar; 20 dp; centered `newsSource`.

`articleUrl(a)`: `link = a.link.trim()`; if starts with `http://` or `https://` → link, else `kNewsWebsiteUrl` (link may be the non-URL guid).

**Action bar**: three equal-width outlined buttons (padding 12v), gaps 10:
* Like: icon spinner (16 dp, orange) while liking, else `favorite` (liked, orange) / `favorite_border` (secondary) 18; label = likesLoaded && count > 0 ? "$count" : `newsLike`; label/border orange if liked else secondary/cBorder; disabled while liking. Tap → `_toggleLike()`:
  * return if `likes.mine` or liking (no un-like: NIP-25 has no retraction except deletion);
  * liking=true; `ok = NewsReactionsService.like(articleAddress: article.id, authorPubkey: authorPubkey)`; liking=false; if ok `likes = likes.withMine()`; else red snackbar `newsLikeFailed`.
* Zap: icon bolt orange (spinner while zapping), label `newsZap` orange, border orange@0.5; disabled while zapping. Flow `_zap()`:
  1. Modal sheet (cCard, top radius 20, padding 20): `newsZapTitle` 17sp w700; 6 dp; `newsZapBody` 13sp; 18 dp; Wrap(spacing 10) of outlined orange buttons "`$sats sat`" for `[21, 210, 2100, 21000]`; tap returns amount.
  2. Dismissed → nothing. Else zapping=true; `result = NewsZapService.createZapInvoice(authorPubkey, articleAddress: article.id, amountSats, comment: article.title)`; zapping=false.
  3. Failure → red snackbar: noLightningAddress → `newsZapNoAddress`; unsupportedAddress → `newsZapUnsupportedAddress`; amountOutOfRange → `newsZapAmountRange`; anything else → `newsZapFailed`.
  4. Success → `launchUrl("lightning:<invoice>", external)` (exceptions → false). If not opened → fallback sheet: `newsZapNoWallet` 17sp w700; 12 dp; invoice box (cDark, radius 10, padding 12, monospace 11sp, max 4 lines ellipsis); 14 dp; full-width orange button copy icon + `newsZapCopyInvoice` → clipboard = invoice, close sheet (no snackbar here).
  (Unlike V4V, News uses a plain ACTION_VIEW, not a forced chooser.)
* Share: icon `ios_share` secondary, label `newsShare`. `text = "${title}\n\n${articleUrl}"`; system share sheet; on exception: clipboard = text + snackbar `newsShareCopied` (bg orange).

### 2.5 CommunityHubScreen

Entry: dashboard Community tile. Title `chTitle`. SafeArea ListView padding 16:
1. Big card (tour key `portalKey`): icon `public`, orange, title `chPortal`, subtitle `chPortalSub`, chevron; watermark icon 96 @0.10 bottom-right offset −12. → PortalAreaScreen. (A `chips` list `['Meetups','Events','Kurse','Karte']` is passed but NOT rendered.)
2. 14 dp; Row of two small cards (height 130, gap 14):
   * News (tour key `newsKey`): icon `article`, orange, `chNews`/`chNewsSub` → NewsScreen.
   * Nostr: icon `flutter_dash` (use a Nostr-ish icon natively), cNostr, `chNostr`/`chNostrSub` → `https://njump.me/npub1qv02xpsc3lhxxx5x7xswf88w3u7kykft9ea7t78tz7ywxf7mxs9qrxujnc`.
3. 14 dp; Row:
   * Shoutout (tour key `shoutoutKey`): icon `campaign`, orange, `chShoutout`/`chShoutoutSub` → `https://shoutout.einundzwanzig.space`.
   * Podcast: icon `podcasts`, cPurple, `chPodcast`/`chPodcastSub` → `https://einundzwanzig.space/podcast/`.
4. 14 dp; SatoshiDuell card (tour key `duellKey`, height 120, gold gradient @0.16, gold border @0.28, watermark image `assets/images/satoshiduell.png` 100×100 @0.13 opacity (fallback bolt icon)). Content from `SatoshiDuellService.fetchStatus()` (empty while loading): row(bolt gold 22, spacer, if n = myTurn+lobby > 0: gold pill with `n` (11.5sp w800), open_in_new tertiary 17); 10 dp; "SatoshiDuell" 15sp w700; 3 dp; subtitle: if no non-zero parts → `chDuellSub`; else one line rich text joining with `"  ·  "` (tertiary): `"⚡ ${myTurn} ${sdShortTurn}"` (gold w700, if >0), `"${lobby} ${sdShortLobby}"` (orange w600, if >0), `"${waiting} ${sdShortWait}"` (green, if >0). Tap → `npub = SigningService.npub()`; open `https://satoshiduell.de/?npub=$npub` if non-empty else `https://satoshiduell.de/`.
5. 14 dp; wide card (height 112) icon `graphic_eq`, orange, title literal "PlebRap", subtitle `chPlebrapSub` (1 line) → PlebrapPlayerScreen.

All external opens: `launchUrl(external)`, errors swallowed silently.

Tour: 500 ms after init, if `GuideService.wasTourCompleted(GuideTour.portal)` is false → `startTour(GuideTour.portal, CommunityTour.steps())`. Steps (title/body ARB keys): portalKey (`guideCommunityPortalTitle`/`Body`), newsKey (`guideCommunityNewsTitle`/`Body`), shoutoutKey (`guidePortalShoutoutTitle`/`Body`), duellKey (`guideCommunityFunTitle`/`Body`). All default GuideStep options (blockOutside, scrollIntoView, waitTimeout 6 s). On dispose: if this tour is active → `finishTourNow()` (marks completed, pref `guide_completed_portal = true`).

### 2.6 PortalAreaScreen

Entry: hub Portal card; dashboard Portal-area tile. Title `paTitle`. Rows (gradient color@0.08→card, radius 10, border color@0.22, 44×44 icon box color@0.12 radius 11, title 15sp w700, sub 11.5sp tertiary, chevron), bottom margin 12:

| # | Icon | Color | Title / Sub | Target | Tour key |
|---|---|---|---|---|---|
| 1 | groups | orange | `paMeetups` / `paMeetupsSub` | CalendarScreen() | meetupsKey |
| 2 | event_available | green | `paEvents` / `paEventsSub` | CalendarScreen() (same target!) | – |
| 3 | school | cNostr | `paCourses` / `paCoursesSub` | CoursesScreen | coursesKey |
| 4 | map | cCyan(gold) | `paMap` / `paMapSub` | NearbyMeetupsScreen | mapKey |
| 5 | edit_calendar | orange | `paMine` / `paMineSub` | PortalMeetupsScreen | mineKey |
| 6 | language | secondary | `paWeb` / `paWebSub` | CommunityPortalScreen (legacy portal link screen, other domain) | – |

Tour `GuideTour.portalArea` with `PortalAreaTour.steps()`: meetupsKey (`guidePaMeetupsTitle/Body`), coursesKey (`guidePaCoursesTitle/Body`), mapKey (`guidePaMapTitle/Body`), mineKey (`guidePaMineTitle/Body`); same start/finish pattern (500 ms delay; finish on dispose; pref `guide_completed_portalArea`).

### 2.7 CoursesScreen

Two tabs (`crsCourses`, `crsLecturers`), title `paCourses`. `_load()`: loading=true; `courses = PortalApiService.getCourses()` THEN `lecturers = getLecturers()` (sequential); loading=false. Loading view: spinner + `crsLoading`. Each tab: empty → card with school icon + `crsNone` (used for BOTH tabs). Else pull-to-refresh list (reloads both):
* item: `name = e.name ?? e.title ?? ""`, `desc = e.description ?? e.intro ?? e.bio ?? ""` (first non-null, toString); card (cNostr gradient), 42×42 icon (school / person), name 15sp w700, desc 12.5sp max 3 lines, chevron.
* tap: if `e.id is int` → CourseDetailScreen(id, fallback: e) / LecturerDetailScreen(id, fallback: e); else bottom sheet (draggable 0.5/0.3/0.9): icon + name 18sp w800, desc (or "—") 14sp, then link rows for each URL extracted from desc with regex `https?://[^\s\)\]>,]+` (deduplicated preserving first-seen order) → open URL.

### 2.8 CourseDetailScreen(id, fallback) / LecturerDetailScreen(id, fallback)

Both: on init fetch detail (`getCourse(id)` / `getLecturer(id)`); `_d = result ?? fallback`; render from `_d ?? fallback` (so fallback shows immediately).

Portal field helpers (exact semantics):
```dart
String pv(Map e, String key) {
  final v = e[key];
  if (v != null && v is! Map && v is! List) return v.toString();
  if (key.contains('.')) {
    final parts = key.split('.');
    final n = e[parts[0]];
    if (n is Map && n[parts[1]] != null) return n[parts[1]].toString();
  }
  return '';
}
String pImg(Map e) { for (k in ['image','logo','avatar','picture','cover']) { v = pv(e,k); if (v.startsWith('http')) return v; } return ''; }
```

Course detail (AppBar `crsCourses`): name = pv(name) or pv(title); desc = pv(description); img = pImg; lecName = pv('lecturer.name'); lecId = lecturer.id if int; events = list under `events` else `course_events` (maps only); portal = pv(portalLink); links = regex URLs from desc.
1. Header card (cCard, radius 12): 64×64 image (or fallback icon box) + title 17sp w800 + sub (lecName, 2 lines).
2. If portal starts with http → link row `crsOpenPortal` (open_in_new) → open.
3. If events: section `crsUpcoming`; event rows: `from = pv(from) || pv(start)`, `loc = pv(location) || pv('venue.name')`, `link`, `title = pv(name) || pv(title)`, `desc`. `d = DateTime.tryParse(from)` (plain ISO parse, NO UTC re-interpretation unlike portalStart); `when = d == null ? from : "dd.MM.yyyy · HH:mm"` (zero-padded). Row: event icon orange, when 13.5sp w700, loc 12sp 1 line; if d != null: calendar icon → insert device calendar event (title = title || loc || "Termin", description = desc, location = loc, start d, end d + 2 h); if link starts with http: link icon → open.
4. If desc: section `crsAbout` + text 14sp h1.55; then link rows for each extracted URL.
5. If lecName: section `crsLecturer` + row card (pImg(lecturer), lecName, pv('lecturer.intro'), chevron), tap → LecturerDetailScreen(lecId, fallback: lecturer map) if lecId != null.

Lecturer detail (AppBar `crsLecturer`): name, intro, bio = pv(description) || pv(bio), courses list (`courses`), linkPairs (ordered): `Website` = pv(website); `X (Twitter)` = pv(twitter) || pv(twitter_username); `Nostr` = pv(nostr); `Portal` = pv(portalLink); empties removed.
1. Header (pImg, name, intro). 2. If bio: section `lecAbout` + text. 3. If courses: section `crsCourses` + row cards (pImg(c), pv(c,name)), tap → CourseDetailScreen(c.id, fallback c) if id int. 4. If links: section `lecLinks` + link rows labelled `"${key}: ${value}"`; tap → if value starts with http → value; else if key == 'X (Twitter)' → `https://x.com/$value`; else `https://$value`.

Link row: orange@0.08 bg, radius 10, orange@0.3 border, padding 12/11, icon 15 + label 13sp w600 1 line. Section header: 15sp w800, padding top 18 bottom 10.

### 2.9 PortalEventsScreen (unreachable — no navigation references it)

Exists in the Dart file but no route pushes it (PortalArea "Events" row opens CalendarScreen). Behaviour if ported: loads `getAllMeetupEvents()`, keeps events with `portalStart(start)` after now−6 h, sorts by raw `start` string, then sequentially fetches `getRsvp(id)` for every event; card shows meetup name (`meetup.name` | `meetup.name` flat | `meetup_name` | "Meetup #<meetup_id>"), date `dd.MM.yyyy · HH:mm`, location, `"$count ${rsvpCount}"` (count from `attendees|count|total|rsvps` if int), going state (`status` attending/maybe or `going|is_going|rsvped == true`) with "Du hast zugesagt ✓" + red "Absagen" (POST status `none`) or green "Zusagen" (POST `attending`); requires portal token (`rsvpNeedLogin`); failure snackbar uses `'${t.rsvpFailed}: …'` which in Dart interpolates a closure (bug). **Recommendation: do not port** (not user-reachable); RSVP UX lives in the calendar domain.

### 2.10 V4VScreen

Entry: Settings screen row "V4V" (`v4vSectionSubtitle`), dashboard settings sheet group `v4vSectionTitle`.

Layout (AppBar `v4vTitle`; ListView padding 24,12,24,40):
1. 84 dp circle, radial gradient orange@0.25→@0.04, orange@0.35 border, bolt icon 42.
2. 22 dp; `v4vHeadline` 24sp w800 centered; 14 dp.
3. Card (cCard, radius 10, padding 16): `v4vExplain1`, 10 dp, `v4vExplain2` (14.5sp secondary h1.6).
4. 28 dp; centered `v4vAmountLabel.toUpperCase()` (orange bold 12sp, letterSpacing 1.5); 12 dp.
5. Amount box (cCard, radius 18, padding 20h/6v): TextField number keyboard, digits-only filter, centered 36sp w800, hint "0"; suffix literal "Sats" orange 16sp w700. Typing clears error.
6. If error: 12 dp + row(error icon red 16, text red 13sp).
7. 26 dp; full-width 56 dp orange button (radius 16, black fg): bolt icon 22 + `v4vDonateButton` (16sp w800); while loading: black spinner 20 dp and EMPTY label; disabled while loading.
8. 18 dp; centered row: heart icon orange 13 + `"${v4vRecipient}: wolpertinger1@timecatcher.lnbits.de"` 12sp tertiary.

`_donate()`: `raw = text.trim().replaceAll('.', '').replaceAll(',', '')`; `amount = int.tryParse(raw) ?? 0`; if ≤ 0 → error `v4vErrInvalidAmount`. loading=true, error=null. `V4VService.createInvoice(amountSats: amount, comment: 'V4V – 21Meetup')` (en dash U+2013). loading=false. Failure → error text by code: `invalid_amount`→`v4vErrInvalidAmount`, `below_min`→`v4vErrBelowMin`, `above_max`→`v4vErrAboveMax`, `unreachable`→`v4vErrUnreachable`, anything else (`invalid_address`, `not_lnurlp`, `invoice_failed`, server `reason` string) → `v4vErrGeneric`.
Success → `_openInvoice(invoice)`: on Android call native chooser: `Intent(ACTION_VIEW, Uri.parse("lightning:$invoice"))`; if `resolveActivity == null` → false; else `startActivity(Intent.createChooser(intent, "Lightning-Wallet wählen"))` → true; exception → false. If channel throws → fallback `launchUrl("lightning:…", external)`. If not opened → fallback sheet: `v4vNoWalletTitle` 17sp w700; 8 dp; `v4vNoWalletBody` 14sp; 16 dp; invoice box (surface, radius 10, monospace 12sp, 4 lines); 12 dp; full-width orange button copy icon + `v4vCopyInvoice` → clipboard, close sheet, floating snackbar `v4vCopied`.

### 2.11 PlebrapPlayerScreen and mini player

State lives in an app-wide singleton (`PlebrapAudio`): one player instance never disposed; `index: Int?` (null = nothing chosen), `loading: Boolean`, `loadErrors: Int` (monotonic counter), `cacheProgress: Double 0..1`.

Screen layout (AppBar title literal "PlebRap" 16sp; action text button bolt + `prV4V` → open `https://plebrap.de/V4V/` externally, errors swallowed):
1. Now-playing card (cCard, radius 12, margin 16,8,16,4): if song: cover image 180×180 @0.10 opacity at right −20/bottom −30 (error → nothing). Padding 16:
   * Row: cover box 52 (radius 10; null song or image error → orange@0.12 box with `graphic_eq` icon 24); 12 dp; title (song.title or `prPickSong`) 15sp w700 1 line; 2 dp; artist (or literal "Plebs together strong") 12sp secondary.
   * 10 dp; slider (track 3 dp, thumb r6, active orange, inactive surface) driven by duration stream (outer) and position stream (inner): `max = duration ms`; if max > 0: value = (dragMs ?? positionMs).clamp(0, max), `onChanged` sets dragMs only (no seek while dragging), `onChangeEnd` → `seek(v ms)` then dragMs = null; if max ≤ 0 slider disabled (value 0, max 1).
   * Labels row (padding 8h): left = fmt(dragMs ?? position), right = fmt(duration) — `fmt(d) = "${d.inMinutes}:${(d.inSeconds % 60).pad2}"`, monospace 11sp tertiary.
   * If max ≤ 0 and song != null: linear progress (value = p > 0 ? p : indeterminate, 2 dp, orange on surface) + 5 dp + `prCaching((p*100).round())` 11sp centered.
   * Controls row centered: prev (skip_previous 30, enabled iff index != null && index > 0) → `prev()`; 8 dp; 54 dp orange circle → `toggle()`, shows black spinner (stroke 2.5) while loading else play/pause icon 32 from `playerState.playing`; 8 dp; next (enabled iff index != null && index < 40) → `next()`.
2. Expanded track list (padding 16,8,16,24): for each album in first-appearance order (Money Fest, Proof of Word EP, Pleb2Pleb EP, Singles): header row (album icon orange 15, album UPPERCASE 11.5sp letterSpacing 1.5 w700 orange, hairline divider); song rows: bg active ? orange@0.10 : cCard, border active ? orange@0.5 width 1 : tileBorder 0.5, radius 10; icon `equalizer` (active, orange) / `play_arrow` (tertiary) 18; title 13.5sp w600 (orange if active), artist 11sp tertiary. Tap → `playIndex(i)`.

Screen listens to index/loading/loadErrors; whenever `loadErrors` differs from the last seen value, shows red floating snackbar `prLoadError` (only while the screen is visible; initial value captured at open).

Dashboard mini player (PlebRap tile): value = song.title (17sp) or `chPlebrapSub` (14sp secondary), sub = artist; 40 dp orange play/pause circle (spinner while loading) → `toggle()`; next icon → `next()`; `queue_music` icon → player screen.

Player logic (must be reproduced):
* `ensureWired()` (idempotent): listen to current-index changes → `index = i` and, unless reloading, `watchCache(i)`; log duration changes; on playback errors: `loadErrors++`, and if hasNext: seekToNext + play (skip broken track).
* `playIndex(i)`: `index = i`, loading = true; if playlist not yet set → set a concatenated playlist of all 41 songs (each a caching source) with initialIndex i; else seek(0, index i). loading=false; play (not awaited). On exception: loading=false, loadErrors++.
* `toggle()`: if index == null → playIndex(0); else pause if playing, else play.
* `next()`/`prev()`: if hasNext/hasPrevious → seekToNext/seekToPrevious + play. (No wrap-around; playlist not looping.)
* Caching: the plebrap.de server delivers files without HTTP range support and without Content-Length, so a player cannot seek or know duration while streaming. Each song is fully downloaded to a local cache on first play; `watchCache(i)` observes download progress of the current track (→ `cacheProgress`); at 100 %, if the player still reports duration == null, reload the whole playlist at the same index and position (`_reloadAt`) and resume if it was playing — now from the local file, seekable. Second play of a cached song needs no network.
* Background: playback continues while the app process lives (no notification/MediaSession in Flutter; "Stage 2" TODO).

### 2.12 GlossaryScreen

Entry: dashboard header "?" icon (tour key `HomeTour.glossaryKey`, home domain).
AppBar title `glTitle` 16sp. Body: search field (padding 16,4,16,12; filled cCard; prefix search icon; suffix clear icon only when query non-empty → clears; hint `glSearchHint`). Filter: `q = query.trim().lowercase()`; hits = all entries where `title.lower.contains(q) || body.lower.contains(q)` (empty q → all). No hits → centered `glNoResults` (padding 32). Else list (padding 16,0,16,32): for each category in enum order that has hits: header row (category icon orange 15 + label UPPERCASE 11sp w800 letterSpacing 1.1 orange, padding 4,14,4,8) followed by its entries as cards (cCard, radius 10, border tileBorder 0.5, margin bottom 8) containing an expandable tile (title 14.5sp w700; body 13.5sp secondary h1.55; initially expanded iff query non-blank; expansion state keyed by title; no divider lines; icon orange expanded / tertiary collapsed).

Categories (order, label key, icon): start `glCatStart` flag; badges `glCatBadges` military_tech; reputation `glCatReputation` workspace_premium; network `glCatNetwork` hub; identity `glCatIdentity` key; events `glCatEvents` celebration; nostr `glCatNostr` bolt; app `glCatApp` tune.

Entries (category: titleKey/bodyKey prefix `gl<Name>Title` / `gl<Name>Body`), in order:
* start: WhatIsApp, Collect, HomeMeetup, Nickname, FindMeetup, Offline
* badges: BadgeProof, RollingQr, OnSite, BlockHeight, Checksum, Duplicate, BadgeShare, WorldMap
* reputation: TrustScore, Levels, Humanity, Platforms, Publish, VerifyPerson, RepCard
* network: Encounter, Degrees, Vouch, TrustPath, EventNet, Distrust, Organizer
* identity: Keys, FindKeys, Password, Signer, Ncryptsec, Passkey, Nip05, Import, Backup, Restore
* events: CalendarSources, CreateEvent, Portal, SpecialEvent, EventHelper, EventWindow
* nostr: NostrBasics, Relays, Public, Zap, News, Community, Converter
* app: Tiles, Language, Log, Reset
(55 entries; e.g. `glWhatIsAppTitle`/`glWhatIsAppBody`, `glNip05Title`/`glNip05Body`.) Implement as a data-driven list; search runs on localized strings.

### 2.13 MarkdownView (widget) — see algorithm in §8.6.

### 2.14 Localized string keys used (DE values from `lib/l10n/app_de.arb`; EN/ES exist for all)

Chat: `chatRelayHint` "Gruppen-Relay von Einundzwanzig"; `chatEmpty`; `chatPlaceholder` "Nachricht schreiben …"; `chatJoin` "Dem Raum beitreten"; `chatJoinHint`; `chatJoinFailed(msg)` "Beitritt abgelehnt: {msg}"; `chatSendFailed(msg)` "Nachricht nicht angekommen: {msg}"; `chatSearching` "Chatraum wird gesucht …"; `chatNoRoom(city)` "Für {city} gibt es auf dem Gruppen-Relay noch keinen Chatraum. Einzelheiten stehen im Diagnose-Log."; `chatEventOpen` "Chat zum Termin"; `chatEventHint` "Beiträge zum Termin · öffentlich auf Nostr"; `chatEventEmpty`; `chatMemberHint`; `chatMemberLink` "Zum Verein"; `chatYou` "Du"; `chatNpubCopied` "npub kopiert."; `chatRelayUnavailable`; `chatOffline`. Unused but present: `chatCopyNpub`, `chatEventFailed`. Dashboard: `btnChat`, `tileEventChats`, `tileEventChatsSub`, `tileEventChatsUnread(count)`, `tileEventChatsNone`.

News: `newsTitle`, `newsEmpty`, `newsLoading`, `newsSource` "Artikel via Nostr (NIP-23)", `newsOpenWebsite`, `newsWriteArticle`, `newsLike` "Gefällt mir", `newsShare`, `newsShareCopied`, `newsLikeFailed`, `newsZap`, `newsZapTitle` "Sats an den Autor", `newsZapBody`, `newsZapNoAddress`, `newsZapUnsupportedAddress`, `newsZapAmountRange`, `newsZapFailed`, `newsZapNoWallet`, `newsZapCopyInvoice`; dashboard `tileNews`, `tileNewsSub`. Unused: `newsRefresh`, `tileNewsUnread`. Hard-coded: "Auf der Webseite lesen", "(ohne Titel)", "Einundzwanzig", "media.einundzwanzig.space", "`$sats sat`".

Community/Portal/Courses: `chTitle`, `chPortal`, `chPortalSub`, `chNews`, `chNewsSub`, `chNostr`, `chNostrSub`, `chShoutout`, `chShoutoutSub`, `chPodcast`, `chPodcastSub`, `chDuellSub`, `chPlebrapSub`, `sdShortTurn` "dran", `sdShortLobby` "in der Lobby", `sdShortWait` "warten auf Gegner"; `paTitle`, `paMeetups`, `paMeetupsSub`, `paEvents`, `paEventsSub`, `paCourses`, `paCoursesSub`, `paMap`, `paMapSub`, `paMine`, `paMineSub`, `paWeb`, `paWebSub`; `crsLoading`, `crsNone`, `crsCourses`, `crsLecturers`, `crsAbout`, `crsUpcoming`, `crsLecturer`, `crsOpenPortal`, `lecAbout`, `lecLinks`; (PortalEventsScreen only: `rsvpLoading`, `rsvpNone`, `rsvpGoing`, `rsvpYouGo`, `rsvpCount`, `rsvpNeedLogin`, `rsvpFailed(msg)`, `rsvpCancel`). Tours: `guideCommunityPortalTitle/Body`, `guideCommunityNewsTitle/Body`, `guidePortalShoutoutTitle/Body`, `guideCommunityFunTitle/Body`, `guidePaMeetupsTitle/Body`, `guidePaCoursesTitle/Body`, `guidePaMapTitle/Body`, `guidePaMineTitle/Body`. Hard-coded: "SatoshiDuell", "PlebRap", "Meetup #", "Termin", "Website", "X (Twitter)", "Nostr", "Portal".

V4V: `v4vSectionTitle`, `v4vSectionSubtitle`, `v4vTitle`, `v4vHeadline`, `v4vExplain1`, `v4vExplain2`, `v4vAmountLabel`, `v4vDonateButton`, `v4vRecipient`, `v4vErrInvalidAmount`, `v4vErrBelowMin`, `v4vErrAboveMax`, `v4vErrUnreachable`, `v4vErrGeneric`, `v4vNoWalletTitle`, `v4vNoWalletBody`, `v4vCopyInvoice`, `v4vCopied`. Hard-coded: "Sats", "V4V", "Lightning-Wallet wählen" (native chooser title).

PlebRap: `prV4V` "Sats an die Künstler", `prPickSong` "Song auswählen", `prLoadError` "Song konnte nicht geladen werden", `prCaching(percent)` "Lied wird geladen ({percent} %) — Springen ist möglich, sobald es vollständig da ist. Danach bleibt es gespeichert.", `chPlebrapSub`. Hard-coded "PlebRap", "Plebs together strong".

Glossary: `glTitle` "Nachschlagen", `glSearchHint`, `glNoResults`, `glCat*` (8), all 55 `gl*Title`/`gl*Body` pairs.

---

## 3. Data models

### 3.1 `ChatRoom` (from a kind-39000 event)
| Field | Type | Default | Source |
|---|---|---|---|
| `h` | String | required | first tag `["d", v]` (value `t[1].toString()`); event rejected (null) if empty |
| `name` | String | "" | first `["name", v]` |
| `about` | String | "" | first `["about", v]` |
| `picture` | String | "" | first `["picture", v]` |
| `meetupSlug` | String | "" | first `["meetup_slug", v]` |
| `eventAddress` | String | "" | LAST `["i", v]` whose v starts with `event:` → `v.substring(6)` |

Derived: `isMeetup = meetupSlug.isNotEmpty`, `isEvent = eventAddress.isNotEmpty` (read but unused by UI). "first tag" = first tag with `t.length > 1 && t[0] == key`. No equality override (identity); dedup by `h`.

### 3.2 `ChatMessage` (from kind 9 or kind 1111 event)
| Field | Type | Rule |
|---|---|---|
| `id` | String | `event.id` (toString); null-reject if empty |
| `pubkey` | String | `event.pubkey`; null-reject if empty |
| `content` | String | `event.content` or "" |
| `createdAt` | DateTime (local) | `created_at` must be int, else null-reject; `ms = ts * 1000` |
Local optimistic message: `id = "lokal-<epochMicros>"`, `pubkey = myPubkey ?? ""`, `createdAt = now`.

### 3.3 `ChatRelayUnavailable` (exception)
`offline: Boolean = false`; `toString()` → "Keine Internetverbindung" if offline else "Gruppen-Relay ohne Auskunft". Thrown by `loadMeetupRooms` (no stale cache), `findRoomForMeetupId`, `findRoomForCity` when the relay gave NO answer (≠ empty answer).

### 3.4 `NewsArticle`
| Field | Type | Source (RSS item) |
|---|---|---|
| `id` | String | `guid` if non-empty else `link` (expected `30023:<hexpubkey>:<d>`) |
| `pubkey` | String | AUTHOR NAME: `dc:creator` if non-empty else `author` (cleaned) — NOT a key |
| `dTag` | String | always "" (compat) |
| `title` | String | cleaned `title`; "(ohne Titel)" if empty |
| `summary` | String | `stripHtml(clean(description)).trim()` |
| `image` | String | first `<enclosure … url="…">` value (raw, not entity-decoded), else first `<img … src="…">` in raw content |
| `content` | String | `clean(content:encoded)` or `clean(description)` (parsed but NOT displayed anywhere) |
| `publishedAt` | int (unix s) | `parseDate(pubDate)` (§8.4) |
| `topics` | List<String> | always empty |
| `link` | String | cleaned `link` if non-empty else `guid` |
Derived: `date = fromMillis(publishedAt*1000)` local.

### 3.5 `ArticleLikes`
`count: Int` (distinct positive reactor pubkeys), `mine: Boolean`. `empty = (0,false)`. `withMine() = mine ? this : (count+1, true)`.

### 3.6 `ZapError` enum / `ZapResult`
`noLightningAddress, unsupportedAddress, lnurlUnreachable, amountOutOfRange, signingFailed, invoiceFailed`. `ZapResult.success(invoice)` / `.failure(error)`; `ok = invoice != null`.

### 3.7 `V4VResult`
`ok`, `invoice?` (bolt11), `error?` code string: `invalid_amount`, `invalid_address`, `unreachable`, `not_lnurlp`, `below_min`, `above_max`, `invoice_failed`, or the server-provided `reason` text.

### 3.8 `PlebSong`
`album, title, artist, url` (all String). Derived `cover` by album:
* `Money Fest` → `https://plebrap.de/.cm4all/uproc.php/0/.video_2024-11-01_13-33-06.mp4/poster?_=192f3022949`
* `Pleb2Pleb EP` → `https://plebrap.de/.cm4all/uproc.php/0/.Pleb2Pleb_1.jpg/picture-200?_=18df683959a`
* otherwise (Proof of Word EP, Singles) → `https://plebrap.de/.cm4all/uproc.php/0/photo_2022-08-08_13-08-28.jpg`

Catalog (index: album | title | artist | url) — must be copied exactly (order defines playlist indices):
```
0  Money Fest | Money Fest | TooBitToFail | https://plebrap.de/.cm4all/uproc.php/0/01-Money%20Fest-Too%20Bit%20To%20Fail.mp3?cdp=a&_=192e4484b03
1  Money Fest | Mouse Click Money | TooBitToFail | https://plebrap.de/.cm4all/uproc.php/0/02-Mouse%20Click%20Money-Too%20Bit%20To%20Fail.mp3?cdp=a&_=192e44943e3
2  Money Fest | Modern Märchen Token | TooBitToFail | https://plebrap.de/.cm4all/uproc.php/0/03-Modern%20Ma%CC%88rchen%20Token-Too%20Bit%20To%20Fail.mp3?cdp=a&_=192e44a4c54
3  Money Fest | SHA 'o' lin | TooBitToFail | https://plebrap.de/.cm4all/uproc.php/0/04-SHA%20%27o%27%20lin-Too%20Bit%20To%20Fail.mp3?cdp=a&_=192e456e3fe
4  Money Fest | Collective Behavior Data Coin | TooBitToFail feat. JustAnotherNode | https://plebrap.de/.cm4all/uproc.php/0/05-Collective%20Behavior%20Data%20Coin-Too%20Bit%20To%20Fail.mp3?cdp=a&_=192e44b21da
5  Money Fest | Was Bitcoin nicht bringt | TooBitToFail | https://plebrap.de/.cm4all/uproc.php/0/06-Was%20Bitcoin%20nicht%20bringt-Too%20Bit%20To%20Fail.mp3?cdp=a&_=192e44d40ad
6  Money Fest | B-Ware | TooBitToFail | https://plebrap.de/.cm4all/uproc.php/0/07-B-Ware-Too%20Bit%20To%20Fail.mp3?cdp=a&_=192e44f74c7
7  Money Fest | Bar-rierefrei | TooBitToFail | https://plebrap.de/.cm4all/uproc.php/0/08-Bar-rierefrei-Too%20Bit%20To%20Fail.mp3?cdp=a&_=192e4504186
8  Money Fest | Don't trust, verify | TooBitToFail | https://plebrap.de/.cm4all/uproc.php/0/09-Don%60t%20trust%2C%20verify-Too%20Bit%20To%20Fail.mp3?cdp=a&_=192e45110b1
9  Money Fest | Miss Stackchelorette | TooBitToFail | https://plebrap.de/.cm4all/uproc.php/0/10-Miss%20Stackchelorette-Too%20Bit%20To%20Fail.mp3?cdp=a&_=192e451de99
10 Money Fest | Moon Ätherisierung | TooBitToFail | https://plebrap.de/.cm4all/uproc.php/0/11-Moon%20A%CC%88therisierung-Too%20Bit%20To%20Fail.mp3?cdp=a&_=192e454c72e
11 Money Fest | Signale | TooBitToFail | https://plebrap.de/.cm4all/uproc.php/0/12-Signale-Too%20Bit%20To%20Fail.mp3?cdp=a&_=192e4561d25
12 Proof of Word EP | Proof of Word | TooBitToFail | https://plebrap.de/.cm4all/uproc.php/0/Proof-of-Word-Too-Bit-To-Fail.mp3?cdp=a&_=18e04c99f1d
13 Proof of Word EP | Cancel Bank Direct Control | TooBitToFail feat. JustAnotherNode | https://plebrap.de/.cm4all/uproc.php/0/Cancel-Bank-Direct-Control-Too-Bit-To-Fail-feat.-JustAnotherNode.mp3?cdp=a&_=18e0e85aa60
14 Proof of Word EP | Orange Pill 'o' Sophie | TooBitToFail | https://plebrap.de/.cm4all/uproc.php/0/Orange-Pill-o-Sophie-Too-Bit-To-Fail.mp3?cdp=a&_=18e0e873495
15 Proof of Word EP | BitSkit (I don't give a FUD) | TooBitToFail | https://plebrap.de/.cm4all/uproc.php/0/BitSkit-I-dontt-give-a-FUD-Too-Bit-To-Fail.mp3?cdp=a&_=18e0e8879c6
16 Proof of Word EP | Bubble Trouble | TooBitToFail | https://plebrap.de/.cm4all/uproc.php/0/Bubble-Trouble-Too-Bit-To-Fail.mp3?cdp=a&_=18e0e89afdb
17 Proof of Word EP | Das Geldsystem ist krank | TooBitToFail, Beat: Meidi | https://plebrap.de/.cm4all/uproc.php/0/Das-Geldsystem-ist-krank-Too-Bit-To-Fail-Beat-by-Meidi_BTC-Beatz.mp3?cdp=a&_=18e0e8a8e11
18 Proof of Word EP | Diamond Hands | TooBitToFail | https://plebrap.de/.cm4all/uproc.php/0/Diamond-Hands-Too-Bit-To-Fail.mp3?cdp=a&_=18e0e8b7c7b
19 Proof of Word EP | Hit the Road FED | TooBitToFail feat. Fr. LeBlanc | https://plebrap.de/.cm4all/uproc.php/0/Hit-the-Road-FED-Too-Bit-To-Fail-feat.-Fr.-Le-Blanc.mp3?cdp=a&_=18e0e8ccafd
20 Pleb2Pleb EP | Was ist Bitcoin eigentlich | JustAnotherNode | https://plebrap.de/.cm4all/uproc.php/0/Was-ist-Bitcoin-eigentlich-JustAnotherNode.mp3?cdp=a&_=18e04ba6ec1
21 Pleb2Pleb EP | Was ist Geld für dich | JustAnotherNode | https://plebrap.de/.cm4all/uproc.php/0/Was%20ist%20Geld%20fu%CC%88r%20dich%20-%20JustAnotherNode.mp3?cdp=a&_=191148ce0f4
22 Pleb2Pleb EP | Stack Sats | JustAnotherNode | https://plebrap.de/.cm4all/uproc.php/0/StackSats-JustAnotherNode.mp3?cdp=a&_=18e04c86c51
23 Pleb2Pleb EP | Is Ok | JustAnotherNode feat. KidfromtheBlock | https://plebrap.de/.cm4all/uproc.php/0/Is-Ok-JustAnotherNode-feat.-Kid.mp3?cdp=a&_=18e04dc1c95
24 Pleb2Pleb EP | Dezentral | JustAnotherNode | https://plebrap.de/.cm4all/uproc.php/0/Dezentral-JustAnotherNode.mp3?cdp=a&_=18e04dd36a3
25 Pleb2Pleb EP | Inflation | JustAnotherNode | https://plebrap.de/.cm4all/uproc.php/0/Inflation-JustAnotherNode_1.mp3?cdp=a&_=18e0e8e87dc
26 Pleb2Pleb EP | Sound Money | JustAnotherNode feat. KidfromtheBlock | https://plebrap.de/.cm4all/uproc.php/0/Sound-Money-JustAnotherNode-Lyrics-by-Kid-from-the-Block.mp3?cdp=a&_=18e0e903402
27 Singles | Plebs together strong | TooBitToFail, JustAnotherNode, MoGries | https://plebrap.de/.cm4all/uproc.php/0/Plebs-together-strong-TooBitToFail-feat.-JustAnotherNode-mixed-by-MoGries.mp3?cdp=a&_=18e0e9c5178
28 Singles | FOMO | TooBitToFail, Hanspanzer | https://plebrap.de/.cm4all/uproc.php/0/FOMO-TooBitToFail-Hanspanzer.mp3?cdp=a&_=18e0ea15b2b
29 Singles | Nutzen Sats | JustAnotherNode | https://plebrap.de/.cm4all/uproc.php/0/Nutzen%20Sats.mp3?cdp=a&_=18e0eaf1e63
30 Singles | Fiat unter Feuer | TooBitToFail, Hanspanzer | https://plebrap.de/.cm4all/uproc.php/0/Fiat%20unter%20%20Feuer%20-%20TooBitToFail%20-%20Hanspanzer.mp3?cdp=a&_=18f97d95bb6
31 Singles | Bank gegen Node | TooBitToFail, Hanspanzer feat. JustAnotherNode | https://plebrap.de/.cm4all/uproc.php/0/Bank-gegen-Node-TooBitToFail-Hanspanzer-feat.-JustAnotherNode.mp3?cdp=a&_=18e0e996966
32 Singles | Feuer über Fiat | TooBitToFail | https://plebrap.de/.cm4all/uproc.php/0/Feuer-ueber-Fiat-TooBitToFail.mp3?cdp=a&_=18e0e9e4736
33 Singles | One Bit Wonder | TooBitToFail, Hanspanzer | https://plebrap.de/.cm4all/uproc.php/0/One-Bit-Wonder-TooBitToFail-Hanspanzer.mp3?cdp=a&_=18e0ea0437b
34 Singles | El Presidente | TooBitToFail | https://plebrap.de/.cm4all/uproc.php/0/El-Presidente-TooBitToFail.mp3?cdp=a&_=18e0eab9876
35 Singles | Moinsen | TooBitToFail, JustAnotherNode, Hanspanzer | https://plebrap.de/.cm4all/uproc.php/0/Moinsen-TooBitToFail-JustAnotherNode-Hanspanzer.mp3?cdp=a&_=18e0ea284b5
36 Singles | Money Printer Go | TooBitToFail | https://plebrap.de/.cm4all/uproc.php/0/Money-Printer-Go-TooBitToFail.mp3?cdp=a&_=18e0eaad93c
37 Singles | Shitstorm | TooBitToFail, Hanspanzer feat. Dr.Block | https://plebrap.de/.cm4all/uproc.php/0/Shitstorm-TooBitToFail-Hanspanzer-feat.-Dr.-Block.mp3?cdp=a&_=18e0e9f42f3
38 Singles | Bitcoin braucht dich nicht | SatStacker, MoGries | https://plebrap.de/.cm4all/uproc.php/0/Bitcoin-braucht-dich-nicht-SatStacker-Mo.mp3?cdp=a&_=18e0eb03831
39 Singles | Hier im Münzweg | TooBitToFail, JustAnotherNode | https://plebrap.de/.cm4all/uproc.php/0/Hier-im-Muenzweg-TooBitToFail-JustAnotherNode.mp3?cdp=a&_=18e0e7651b8
40 Singles | Papa hodlt | TooBitToFail feat. JustAnotherNode | https://plebrap.de/.cm4all/uproc.php/0/Papa-Hodlt-TooBitToFail-JustAnotherNode.mp3?cdp=a&_=18e0e9b0261
```
Note the URLs contain pre-encoded sequences (`%CC%88` = combining diaeresis, `%60`, `%2C`, `%27`, double space `%20%20`): pass them to the player as-is; do NOT re-encode.

### 3.9 `GlossaryEntry` / `GlossaryCategory` — see §2.12.

### 3.10 `DuellStatus` (SatoshiDuell, used by hub)
`myTurn, waiting, lobby: Int`; `empty = (0,0,0)`; badge number = `myTurn + lobby`.

---

## 4. Persistence

All Flutter `SharedPreferences` values live on Android in `/data/data/space.einundzwanzig.meetup/shared_prefs/FlutterSharedPreferences.xml` with key prefix **`flutter.`** (e.g. `flutter.chat_last_read`). ints are stored as `long`; `List<String>` (shared_preferences_android 2.4.x legacy API) as a string `"VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu!" + <JSON array>`; values written by older plugin versions may still be `"VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu" + <base64 Java-serialized ArrayList>` (no `!`). A native in-place upgrade (same applicationId `space.einundzwanzig.meetup`, same signing key) must migrate these keys (central persistence domain should provide the reader; listed here for this domain).

| Key (Dart name, without `flutter.` prefix) | Type / format | Writer | Reader | Notes |
|---|---|---|---|---|
| `chat_last_read` | StringList; each entry `"<key>|<unixSeconds>"`; key = room `h` OR `"evt|<kind>:<pubkey>:<d>"` | `ChatService.markRead` (from ChatScreen) | `ChatService._readMarks`, `lastReadFor`, `unreadCounts`, `EventChatService.unreadCounts` | Parse with `lastIndexOf('|')`; skip entries with index ≤ 0 or non-int suffix. Value never decreases. Whole list rewritten on each change (order = previous order, new keys appended). Unbounded growth. Local only — never published. |
| `news_last_read_ts` | int (unix seconds of NEWEST article `publishedAt` seen) | `NewsService._storeNewest` (via `unreadCount` first call, `markRead`) | `NewsService.unreadCount` | Missing ⇒ first run: store newest, report 0. |
| `news_liked_reactions` | StringList; entries `"<hexPubkey>|<articleAddress>"` | `NewsReactionsService._rememberLike` after ≥1 relay accepted | `hasLikedLocally`, `fetchLikes`, `like` | Capped at 500 entries: append, then drop oldest (`removeRange(0, len-500)`). Duplicate entries not added. Pubkey = signer pubkey of the published reaction. |
| `guide_completed_portal` | bool | GuideService (hub tour) | GuideService | Tour enum name `portal`. |
| `guide_completed_portalArea` | bool | GuideService (portal-area tour) | GuideService | Tour enum name `portalArea`. |
| `widget_latest_news_id`, `widget_latest_news_title`, `widget_last_seen_news_id` | String | WidgetService (`refreshNews` uses `NewsService.fetchArticles(limit: 1)`, `markNewsSeen`) | WidgetService | Owned by widget domain; HomeWidget keys `newsTitle`, `newsIsNew`. |
| `custom_relays`, `disabled_default_relays` | StringList | relay settings domain | `RelayConfig.getActiveRelays` (→ read targets) | Read-only here. |

Secure storage: none in this domain (portal token `portal_api_token` only used by the unreachable PortalEventsScreen via `hasToken`).

In-memory caches (process lifetime, not persisted):
* Chat room list: `_roomCache` + timestamp, TTL 5 min; empty results NOT cached; stale cache returned when relay unavailable.
* News articles: `_cache` TTL 10 min (only non-empty results cached; `limit` ignored on hit).
* SatoshiDuell status: TTL 2 min; on error returns last cached or empty.
* Display names: NostrProfileService RAM cache (+ its own persisted cache, profile domain).
* PlebRap: no persisted state (index resets on app restart). Audio files cached by just_audio `LockCachingAudioSource` in the app temp/cache dir (`just_audio_cache/…`); not migrated (re-download is fine).

App reset (`_performReset`, dashboard) calls `SharedPreferences.clear()` → all of the above are wiped.

Migrations: none in-domain. Note the event chat read key prefix `evt|` lives in the SAME list as room keys.

---

## 5. Network (HTTP)

All HTTP via Dart `http` (default UA `Dart/<ver> (dart:io)` unless stated). No retries unless stated.

| # | Purpose | Method / URL | Headers | Timeout | Response handling |
|---|---|---|---|---|---|
| N1 | News feed | `GET https://media.einundzwanzig.space/s/einundzwanzig-news/feed.xml` | `Accept: application/rss+xml, application/xml, text/xml`, `User-Agent: Einundzwanzig-Meetup-App/1.0 (Android)` (same string on all platforms) | 12 s | non-200 → `[]`; body decoded as UTF-8 with malformed bytes allowed (replacement); RSS parse §8.3; truncate to `limit` (default 50); any error → `[]` |
| N2 | LNURL-pay metadata (zap) | `GET https://<domain>/.well-known/lnurlp/<user>` | default | 12 s | non-200 / bad JSON / `tag != "payRequest"` / no `callback` → `lnurlUnreachable` |
| N3 | Zap invoice | `GET <callback>{?|&}amount=<msat>[&nostr=<enc(zapRequestJson)>][&comment=<enc(c)>]` | default | 12 s | non-200 → `invoiceFailed`; JSON `pr` non-empty → success; else `invoiceFailed` |
| N4 | V4V metadata | `GET https://timecatcher.lnbits.de/.well-known/lnurlp/wolpertinger1` | default | 15 s | non-200 → `unreachable`; `tag != payRequest` or no callback → `not_lnurlp` |
| N5 | V4V invoice | `GET <callback>{?|&}amount=<msat>[&comment=<enc(c)>]` | default | 15 s | non-200 → `invoice_failed`; `status.uppercase()=="ERROR"` → error = `reason` or `invoice_failed`; `pr` empty → `invoice_failed`; any exception in N4/N5 → `unreachable` |
| N6 | Portal courses | `GET https://portal.einundzwanzig.space/api/courses?withDetails=1` | `Accept: application/json` + `Authorization: Bearer <token>` if token | 15 s | non-200/err → null → `[]`; list = direct array OR `data` OR `items` |
| N7 | Portal lecturers | `GET …/api/lecturers?withDetails=1` | same | 15 s | same |
| N8 | Course detail | `GET …/api/courses/{id}` | same | 15 s | `data` or body if Map, else null |
| N9 | Lecturer detail | `GET …/api/lecturers/{id}` | same | 15 s | same |
| N10 | SatoshiDuell profile | `GET https://uydjemquyogdemjtxyyv.supabase.co/rest/v1/profiles?npub=eq.<enc(npub.lower)>&select=username&limit=1` | `apikey: sb_publishable_RESZrjWqb-l_AIiqZcE7-g_JenFWO_d`, `Authorization: Bearer <same>`, `Accept: application/json` | 8 s | first `username` or none |
| N11 | SatoshiDuell my duels | `GET …/duels?select=status,creator,challenger,target_player,creator_score,challenger_score&or=(creator.eq.<q>,challenger.eq.<q>,target_player.eq.<q>)&order=created_at.desc&limit=50` where `q = encodeQueryComponent('"' + username.lower.replace('"','') + '"')` | same | 8 s | see §8.9 |
| N12 | SatoshiDuell lobby | `GET …/duels?select=creator,target_player&status=eq.open&limit=100` | same | 8 s | see §8.9 |
| N13 | Images | article cover / enclosure, Markdown images (http/https only), PlebRap covers, portal images | – | default | errors → hidden/fallback |
| N14 | PlebRap audio | the 41 URLs above | – | – | full download to cache, see §2.11 |
| N15 | (unreachable screen) portal events / RSVP | `GET /api/meetup-events/{yyyy-MM-dd}` ×3, `GET /api/meetup-events/{id}/rsvp`, `POST /api/meetup-events/{id}/rsvp {"status": "attending"|"none"}` | portal domain | 15 s | portal domain |

URL building (byte-relevant for servers that are strict):
```dart
final sep = callback.contains('?') ? '&' : '?';
var url = '$callback${sep}amount=$amountMsat';
url += '&nostr=${Uri.encodeQueryComponent(zapRequestJson)}';      // zap, only if allowsNostr
url += '&comment=${Uri.encodeQueryComponent(c)}';                  // only if no zap request (zap) / always if allowed (V4V)
```
`Uri.encodeQueryComponent` = form encoding: space → `+`, unreserved per RFC 2396 (`A-Za-z0-9-_.!~*'()`) kept. Kotlin `URLEncoder.encode(s, UTF_8)` differs only for `~ ! ' ( )` (percent-encoded) — acceptable, servers decode both. Do NOT use `%20` for spaces only if you want byte parity; both are valid.

External links opened (browser/apps): see §2 (media site, njump, shoutout, podcast, satoshiduell, plebrap V4V, verein, portal links, course/lecturer links, Markdown links).

---

## 6. Nostr

### 6.1 Relays
* **Group relay** `kGroupRelay = "wss://group.einundzwanzig.space"` — the only relay for NIP-29. Room ids `h` are only meaningful on this relay.
* **News relays** (`NewsReactionsService.newsRelays`, write + read): `wss://nostr.einundzwanzig.space`, `wss://relay.damus.io`, `wss://nos.lol`.
* **Read-only** (`readOnlyRelays`): `wss://relay.nostr.band` (aggregator; never used for writing reactions).
* **readTargets()** = ordered set `newsRelays ∪ readOnlyRelays ∪ RelayConfig.getActiveRelays()` → effectively `[nostr.einundzwanzig.space, relay.damus.io, nos.lol, relay.nostr.band, <custom relays…>]` (default relays are always included even if the user disabled them in settings, because they are hard-coded here). Used by: event chat (read AND write — writes include relay.nostr.band), likes (read), lud16 lookup, and by other domains (EventRsvpService read/write, ZapReceiptValidator).
* **writeTargets()** = readTargets minus readOnlyRelays (likes only).
* **Article full-text relays** (`NewsService._relays`): `wss://nostr.einundzwanzig.space`, `wss://relay.damus.io`, `wss://nos.lol`, `wss://relay.nostr.band` (fixed, user relays not added).
* Display-name lookup uses `RelayConfig.getActiveRelays()` (profile domain).

### 6.2 Incoming event handling (ALL subscriptions in this domain)
Every `["EVENT", sub, ev]` is accepted only if:
1. frame ≤ 512 × 1024 chars (`RelaySocket.maxMessageChars`; larger frames dropped before JSON parse),
2. `RelaySocket.verifiedEvent(ev)` — NIP-01 id recomputation + BIP-340 signature valid (see §7.1), and
3. `RelaySocket.answersFilter(ev, filter)` with the exact filter that was sent (see §8.1).
For replaceable/addressable "newest wins" decisions `RelaySocket.replaceableCreatedAt(ev)` is used (created_at int ≥ 0 and ≤ now + 600 s, else ignored).

### 6.3 NIP-42 authentication (group relay)
Relay sends `["AUTH", "<challenge>"]`. Client replies `["AUTH", <signedEventJson>]` where the event is:
```dart
SigningService.signEvent(kind: 22242, content: '', tags: [
  ['relay', 'wss://group.einundzwanzig.space'],
  ['challenge', challenge],
]);
```
The connection is usable ONLY after `["OK", <authEventId>, true, …]`. Detailed state machine in §8.2. AUTH required for reads (without it the relay silently returns nothing) and for writes.

### 6.4 NIP-29 events (group relay)
| Purpose | Kind | Tags | Content | Signed by |
|---|---|---|---|---|
| Room metadata | 39000 (addressable, relay-managed) | `["d", h]`, `["name", …]`, `["about", …]`, `["picture", …]`, meetup rooms also `["t","meetup"]`, `["meetup_slug","<portal slug>"]`, binding `["i","meetup:<portalId>"]` or `["i","event:<kind:pubkey:d>"]` | – | relay |
| Room members | 39002 | `["d", h]`, `["p", <memberHex>]…` | – | relay |
| Chat message | 9 | `[["h", h]]` exactly | trimmed text | user |
| Join request | 9021 | `[["h", h]]` | `""` | user |
| Leave request | 9022 | `[["h", h]]` | `""` | user (implemented in service, NOT exposed in UI) |
| Room creation | 9007 (+9002 edit) | – | – | NOT done by this app any more (documented only) |

The app does NOT verify that 39000/39002 are signed by the relay's own pubkey (any validly signed event matching the filter is accepted).

REQ filters (sub id in parentheses), JSON key order as in Dart map literals:
* Meetup rooms (`q`): `{"kinds":[39000],"#t":["meetup"],"limit":500}`
* Room by portal id (`q`): `{"kinds":[39000],"#i":["meetup:<portalId>"],"limit":5}`
* Membership (`q`): `{"kinds":[39002],"#d":[h],"limit":5}`
* Messages (`q`): `{"kinds":[9],"#h":[h],"limit":100}`
* Unread (`q`): `{"kinds":[9],"#h":[h1,h2,…],"since":<s>,"limit":500}` (timeout 6 s)
* Live (`live`): `{"kinds":[9],"#h":[h]}` + `"since":<unixSecondsOfLastLoadedMessageOrNow>` (inclusive; duplicates removed by id)

Each query/publish opens a NEW authenticated connection and closes it afterwards (opening a room = 3 AUTH handshakes: messages, membership, live).

Publish: send `["EVENT", ev]`, wait for `["OK", ev.id, bool, reason]` up to 8 s. Returns null on success, else reason string, `"Zeitüberschreitung"` on timeout, `"Anmeldung am Relay fehlgeschlagen"` if no authed connection, or exception text. These strings are shown raw inside `chatJoinFailed`/`chatSendFailed`.

### 6.5 NIP-22 event comments (normal relays)
Write (to ALL readTargets in parallel; success if ≥ 1 OK true; else `"Kein Relay hat den Beitrag angenommen"`):
```dart
final parts = eventAddress.split(':');
final rootKind = parts.isNotEmpty ? parts[0] : '31923';   // split never returns empty → always parts[0]
SigningService.signEvent(kind: 1111, content: body /* trimmed, non-empty */, tags: [
  ['A', eventAddress],
  ['K', rootKind],
  if (eventAuthorPubkey.isNotEmpty) ['P', eventAuthorPubkey],
  ['a', eventAddress],
  ['k', rootKind],
  if (eventAuthorPubkey.isNotEmpty) ['p', eventAuthorPubkey],
]);
```
Exact tag order matters for id/signature determinism only within one event, but keep it for parity. Uppercase = root, lowercase = direct parent (flat thread → identical).
Read filters:
* Load (`q`, every readTarget in parallel, each: connect 4 s, wait EOSE/CLOSED 7 s): `{"kinds":[1111],"#A":[address],"limit":200}`; merge by event id; sort ascending.
* Live (`evtchat`, FIRST reachable readTarget only, connect 4 s each): `{"kinds":[1111],"#A":[address]}` + optional `"since"`.
* Unread (`q`, all readTargets): `{"kinds":[1111],"#A":[addr1,…],"since":<s>,"limit":300}`.
All 1111 events with root `A` = address are shown flat (replies from other clients included).

### 6.6 NIP-23 long-form article (read only)
Article address/guid `30023:<pubkey>:<d>`. Parse: `parts = guid.split(':')`; need ≥ 3 parts; `pubkey = parts[1]`; `d = parts.sublist(2).join(':')` (d may contain `:`); both non-empty. Filter (sub `article`, to the 4 article relays in parallel): `{"kinds":[30023],"authors":[pubkey],"#d":[d],"limit":1}`. Newest `replaceableCreatedAt` wins (ties: later arrival replaces). Done when every relay sent EOSE / closed / errored / failed to connect (connect timeout 6 s) or overall 12 s timer. Result: content if non-empty else null.

### 6.7 NIP-25 reaction (like)
```dart
SigningService.signEvent(kind: 7, content: '+', tags: [
  ['a', articleAddress],
  if (authorPubkey.isNotEmpty) ['p', authorPubkey],
  ['k', '30023'],
]);
```
Sent to writeTargets (parallel; connect 5 s; overall 8 s timer; finishes early when every target answered OK/failed-to-connect; NOTICE frames only logged). Success iff ≥ 1 relay `OK true`. Not re-sent if local list already has `"<myPubkey>|<address>"` (returns true without network).
Fetch (sub `likes`, all readTargets, connect 4 s, overall 8 s, early finish when all EOSE/closed): `{"kinds":[7],"#a":[articleAddress],"limit":500}`. Count DISTINCT pubkeys with positive content (§8.7).

### 6.8 NIP-57 zap request (kind 9734) — only if LNURL metadata `allowsNostr == true`
```dart
SigningService.signEvent(kind: 9734, content: comment ?? '' /* = article title */, tags: [
  ['relays', 'wss://nostr.einundzwanzig.space', 'wss://relay.damus.io', 'wss://nos.lol'],
  ['amount', '$amountMsat'],
  ['p', authorPubkey],
  ['a', articleAddress],
]);
zapRequestJson = jsonEncode(signed.toJson());   // key order: id, pubkey, created_at, kind, tags, content, sig
```
Not published to relays by the app; passed to the LNURL callback as `nostr=` param. No `e` or `lnurl` tag.

### 6.9 Kind 0 lightning address lookup (`fetchLightningAddress(pubkeyHex)`)
Sub `zapmeta`, all readTargets in parallel (connect 4 s), overall 6 s, early finish when all done. Filter `{"kinds":[0],"authors":[pubkey],"limit":1}`. For each verified/matching event with `at = replaceableCreatedAt` and `at >= bestAt`: parse content JSON (parse failure → event ignored, bestAt unchanged); then `bestAt = at`; `lud16 = profile.lud16.toString().trim()`; if contains `@` → found = lud16; else if `lud06` non-empty → found = `"lud06:"` (marker → unsupported); else found = null (newer profile without address clears an older one). Returns found. Used also by ZapReceiptValidator (other domain).

### 6.10 Signing
All signing goes through `SigningService.signEvent(kind, tags, content)` (local nsec / Amber NIP-55 / NIP-46 bunker / NIP-07). `created_at` = signer's current time. Own pubkey via `SigningService.pubkeyHex()`; npub via `SigningService.npub()`.

### 6.11 NIP list
NIP-01 (events, REQ/EOSE/CLOSED/OK/NOTICE), NIP-19 (npub display/copy), NIP-22 (comments), NIP-23 (long-form 30023), NIP-25 (reactions), NIP-29 (relay-based groups), NIP-42 (AUTH), NIP-52 (calendar addresses 31922/31923 as comment roots), NIP-57 (zap request), LUD-06/LUD-16 (LNURL-pay, lightning address), NIP-55/NIP-46/NIP-07 indirectly through signing.

---

## 7. Crypto & encoding

### 7.1 Event verification (must match nostr-dart 1.5.0 `Event.isValid()` exactly)
```dart
// RelaySocket.verifiedEvent: raw must be a Map; fields with defaults:
Event(raw['id'] ?? '', raw['pubkey'] ?? '', raw['created_at'] ?? 0, raw['kind'] ?? 0,
      tags: (raw['tags'] as List?)?.map((t) => (t as List).map((e) => e.toString()).toList()).toList() ?? [],
      raw['content'] ?? '', raw['sig'] ?? '', verify: false);
// isValid():
String verifyId = sha256hex(utf8(json.encode([0, pubkey.toLowerCase(), createdAt, kind, tags, content])));
return createdAt.toString().length == 10 && id == verifyId && bip340.verify(pubkey, id, sig);
```
Rules to replicate: any type error (e.g. created_at not int, tags not list-of-lists) → reject; tag elements are stringified before hashing (a numeric tag element changes the serialization → id mismatch → reject); `created_at` must have exactly 10 decimal digits; id comparison is case-sensitive against lowercase hex; pubkey lowercased in the serialization; signature BIP-340 Schnorr over the 32-byte id. JSON serialization = standard NIP-01 (Dart `json.encode`: no whitespace, non-ASCII emitted raw as UTF-8, escapes `"` `\\` and control chars `\b \f \n \r \t`, other < 0x20 as `\u00XX` lowercase hex; `/` NOT escaped). Rejected events are counted (`rejectedEventCount`) and logged sparsely (1st and every 10th).

### 7.2 Encodings
* Pubkeys/ids/sigs: lowercase hex (64/64/128 chars).
* npub: NIP-19 bech32 (`hexToNpub`) for display/copy only.
* Read marks: `"$key|$unixSeconds"` UTF-8 strings, seconds = `millis ~/ 1000` (floor).
* Liked marks: `"$pubkeyHex|$articleAddress"`.
* Event address: `"$kind:$pubkeyHex:$d"`; event chat read key `"evt|" + address`.
* Data-URI images in Markdown: `data:image/<type>;base64,<b64>` — decode text after first `,` as base64 (Dart decoder accepts standard and URL-safe alphabets).
* msat = sats × 1000 (Int; use Long natively).

No KDFs, hashes or ciphers beyond §7.1 in this domain.

---

## 8. Business rules & algorithms

### 8.1 `answersFilter(event, filter)` (verbatim semantics)
```dart
final kinds = filter['kinds'];   if (kinds is List && !kinds.contains(event['kind'])) return false;
final authors = filter['authors']; if (authors is List && !authors.contains(event['pubkey'])) return false;
final created = event['created_at']; final since = filter['since']; final until = filter['until'];
if (since != null || until != null) {
  if (created is! int) return false;
  if (since is int && created < since) return false;
  if (until is int && created > until) return false;
}
final tags = event['tags'];
for (entry in filter.entries) {
  if (!key.startsWith('#') || key.length < 2) continue;
  if (wanted is! List || wanted.isEmpty) return false;
  if (tags is! List) return false;
  name = key.substring(1);
  hit = any tag (tag is List && tag.length >= 2 && tag[0] == name && wanted.contains(tag[1]));
  if (!hit) return false;
}
return true;   // `limit` ignored
```

### 8.2 NIP-42 handshake state machine (`authenticateSocket(ws, onEvent, timeout, sign?)`) — tested
State: `ready` (completer<bool>), `authEventId`, `activeChallenge`, `authenticated=false`, `renewalChallenge`, `renewalEventId`, `renewalEpoch=0`.
For each incoming text frame (non-JSON or empty array ignored):
1. `["AUTH", <String challenge>, …]`:
   * **Before ready completes**:
     * same as `activeChallenge` → ignore (no second signer prompt);
     * `activeChallenge != null` and different → `ready.complete(false)` (abort; do NOT start a parallel signature);
     * else `activeChallenge = challenge`; asynchronously sign kind 22242; when done: if ready already completed → drop silently (no send, no log); else `authEventId = signed.id`, send `["AUTH", signed.toJson()]`. On sign exception: if ready completed → drop silently (no WARN log); else WARN log + `ready.complete(false)`.
   * **After ready completed**: if `!authenticated` → ignore. If `challenge == renewalChallenge` → ignore. Else `renewalChallenge = challenge`, `generation = ++renewalEpoch`; sign async; when done: if `generation != renewalEpoch` → drop; else `renewalEventId = signed.id`, send AUTH. On error: if generation stale → drop; else WARN and if `renewalChallenge == challenge` reset it to null.
2. `["OK", renewalEventId, …]` (len ≥ 3) → consumed, ignored (never changes authenticated state).
3. `["OK", authEventId, ok, reason?]` (len ≥ 3) → if ready not completed: `authenticated = (msg[2] == true)`, `ready.complete(authenticated)`. Consumed.
4. Anything else → forwarded to `onEvent(msg)`.
Stream error or close before completion → `ready.complete(false)`.
Await `ready` with `timeout`; on timeout → WARN, complete(false). If false → close socket, return null; else return socket (subscription stays open; later AUTH challenges are answered on the same socket).

Effective timeout by signing mode: `local` → 8 s; `nip46` → `Nip46Client.signTimeout (60 s) + 20 s = 80 s`; `amber`/`nip07` → 25 s. TCP/WebSocket connect timeout 5 s. If connect fails: `lastOffline = looksOffline(e)` where looksOffline = `e.toString()` contains any of `"Failed host lookup"`, `"Network is unreachable"`, `"No address associated with hostname"`, `"Connection failed"` (native: map `UnknownHostException`, `ConnectException` with ENETUNREACH, "No address associated…", network-unavailable from ConnectivityManager).

### 8.3 RSS parsing (tolerant regex, not an XML parser)
```dart
itemRe   = RegExp(r'<item\b[^>]*>(.*?)</item>', dotAll: true, caseSensitive: false)
extract(block, tag) = first match of '<' + escape(tag) + '\b[^>]*>(.*?)</' + escape(tag) + '>' (dotAll, case-insensitive), group 1 or ''
clean(s): trim; remove '<![CDATA[' (case-insens.); remove ']]>'; replace in this order:
          '&amp;'→'&', '&lt;'→'<', '&gt;'→'>', '&quot;'→'"', '&#39;'→"'", '&apos;'→"'", '&nbsp;'→' '; trim
stripHtml(s) = s.replaceAll(RegExp(r'<[^>]+>'), ' ').replaceAll(RegExp(r'\s+'), ' ')
```
Per item: `title = clean(extract('title'))`, `link = clean(extract('link'))`, `guid = clean(extract('guid'))`, `author = clean(extract('dc:creator') non-empty ? that : extract('author'))`, `descRaw = extract('description')`, `contentRaw = extract('content:encoded') non-empty ? that : descRaw`, `pubDate = extract('pubDate')`. Image: `RegExp(r'<enclosure[^>]*url="([^"]+)"', ci)` on the raw block; else `RegExp(r'<img[^>]*src="([^"]+)"', ci)` on contentRaw. Skip item if `title.isEmpty && descRaw.isEmpty`. Item order = feed order (feed is newest first). `&amp;` is decoded BEFORE `&lt;` (so `&amp;lt;` → `<`) — keep order.

`htmlToMarkdown` exists in NewsService but is unused — do not port (or port as dead utility).

### 8.4 RSS date parsing (`_parseDate`)
* empty → now (unix s).
* `m = RegExp(r'(\d{1,2})\s+(\w{3})\w*\s+(\d{4})\s+(\d{2}):(\d{2})(?::(\d{2}))?').firstMatch(s.toLowerCase())`; month map `jan..dec` → 1..12 (unknown → 1); seconds default 0; result = `DateTime.utc(y, mon, d, h, mi, sec)` → unix seconds. **The timezone suffix is IGNORED (treated as UTC)** — the feed uses `GMT`, so this is correct today; keep identical to avoid shifting unread counts.
* no match / exception → now.

### 8.5 News unread counter
```text
unreadCount(): lastRead = prefs.getInt('news_last_read_ts'); articles = cachedArticles();
  if articles empty → 0
  if lastRead == null → store max(publishedAt) ; return 0
  return count(a.publishedAt > lastRead)
markRead(): articles = cachedArticles(); if non-empty → store max(publishedAt)
```
Rationale: stores the article timestamp, not "now" (late RSS items with older pubDate stay visible). Errors → 0 / no-op.

### 8.6 Markdown renderer (`MarkdownView`) — exact block algorithm
Input `data`; if `data.trim().isEmpty` → render nothing. Parse; on parser exception → plain fallback text (selectable, 15sp h1.6). Inline rendering failures → plain text of that block.
```
lines = data.replace("\r\n", "\n").split("\n"); i = 0
while i < lines.size:
  t = lines[i].trim()
  1  t empty → i++
  2  t startsWith "```" → i++; collect RAW lines until a line whose trim startsWith "```"; skip that closing line if present → CodeBlock(join "\n")   (fence language ignored)
  3  t in {"---","***","___"} → Divider; i++
  4  t startsWith "### " → H3(t[4:]); "## " → H2(t[3:]); "# " → H1(t[2:]); i++
  5  imgs = all matches of !\[[^\]]*\]\(([^)]+)\) in t; if any and t without all those matches (trimmed) is empty → Image(g1) for each; i++
  6  ^!\[[^\]]*\]\((.+)\)$ (dotAll) matches t → Image(g1); i++          (greedy → URLs containing ')')
  7  t.length > 200 && ' ' not in t → skip line (raw base64 garbage); i++
  8  t startsWith "> " → collect consecutive lines whose trim startsWith "> ", each trim()[2:] → Quote(join " ")
  9  ^[-*]  → collect consecutive matching lines (trim()[2:]) → BulletList
  10 ^\d+\.  → collect consecutive matching lines (prefix removed) → OrderedList (numbered 1..n by position, original numbers ignored)
  11 paragraph: buf = []
     while i < size and lines[i].trim() non-empty and not startsWith('#') and not startsWith('> ')
           and not startsWith('```') and not ^[-*]  and not ^\d+\. :
        lt = lines[i].trim()
        if lt contains !\[[^\]]*\]\((data:image/[^)]+)\) → flush buf as Paragraph; Image(g1); i++; continue
        if lt.length > 200 && ' ' not in lt → i++; continue
        buf += lt; i++
     if buf non-empty → Paragraph(buf.join(" ")) else i++   // progress guarantee
```
Consequences to keep: lines starting with `#` that are not `# `/`## `/`### ` (e.g. `#bitcoin`, `#### H4`) are DROPPED; `---` directly after a text line is appended to the paragraph; `>` without space doesn't start a quote; only 3 heading levels.

Block styles: Paragraph 15sp h1.6 bottom 12; H1 24sp / H2 20 / H3 17, w800 h1.3, top padding 8 (H1) or 16, bottom 8; Quote: left orange bar 3 dp, padding 14/10, italic secondary 15sp h1.55, bottom 12; lists: marker "•" or "n." orange bold 15sp, item text 15sp h1.5, item spacing 6, list bottom 12; CodeBlock: full width, surface bg, radius 10, border tileBorder 0.5, padding 12, monospace 13sp secondary h1.5; Divider: tileBorder, vertical padding 12.

Image rule (`_image(url)`):
* `u = url.trim()`; if starts `data:image/`: comma = first `,` (none → nothing); b64 = after comma; if `b64.length > 3*1024*1024` → placeholder (height 120, cCard, rounded, border, text "🖼  Bild" (two spaces) 13sp tertiary); else decode → in-memory image, cover, rounded; decode failure → nothing.
* else parse URI; empty / no scheme / scheme not http(s) → nothing; if lowercase contains `.heic` or `.heif` → same placeholder; else network image cover, rounded 10, decode width capped at 1080 px, loading placeholder (height 160, card, small orange spinner), error → nothing. Bottom spacing 12.

Inline (`_richText`): scan with
```
(\*\*([^*]+)\*\*)|(\*([^*]+)\*)|(`([^`]+)`)|(\[([^\]]+)\]\(([^)]+)\))
```
left to right; gaps → plain; g2 → bold (w800); g4 → italic; g6 → monospace orange, size −1; g8/g9 → link text orange underlined, tap → `launchHttpUrl(g9)` (only http/https with host; anything else silently ignored). No nesting; `_` emphasis unsupported; inline non-data images render as `!` + link text (tap opens the image URL) or raw text if alt empty.

Plain fallback: remove `!\[[^\]]*\]\([^)]*\)`; remove `data:image/[A-Za-z0-9;,+/=._-]+`; remove chars `[#*`>]`; drop lines whose trim is > 200 chars without space; collapse `\n{3,}` → `\n\n`; trim.

### 8.7 Likes counting
`isPositive(content) = content.trim() != "-"` (empty and any emoji count as positive). `count = |distinct positive reactor pubkeys| + (localOnly ? 1 : 0)` where `localOnly` = own pubkey not among relay reactors but present in local list (then `mine = true`). `mine` also true if own pubkey among positive reactors. Local list is the truth for the own action ("relay didn't echo it back" happened in field tests).

### 8.8 Chat rules
* **Room list merge**: for 39000 events with valid `replaceableCreatedAt` and parseable room: keep per `h` the one with highest created_at; ties → later processed wins (`if prev != null && at < prev continue`).
* **findRoomForMeetupId**: newest among results (ties → later wins); null result from strict query → throw `ChatRelayUnavailable(offline: lastOffline)`; empty → null.
* **findMeetupRoom(slug)** (unused by UI): case-insensitive equality on `meetupSlug`.
* **findRoomForCity(city)**: `needle = slugify(city)`; empty → null; rooms = `loadMeetupRooms()` (may throw); first room with `slug == needle || slug.endsWith('-' + needle)` (slug lowercased); else first room with `slugify(name) == needle`; else null.
  ```dart
  String _slugify(String input) => input.toLowerCase()
      .replaceAll('ä','ae').replaceAll('ö','oe').replaceAll('ü','ue').replaceAll('ß','ss')
      .replaceAll(RegExp(r'[^a-z0-9]+'), '-')
      .replaceAll(RegExp(r'^-+|-+$'), '');
  ```
  (no Unicode normalization; decomposed umlauts become `-`).
* **Strict query** (`_queryStrict`): returns null ("no information") when: no authed connection; exception while sending/waiting; CLOSED received (retry exactly once if reason starts with `auth-required`); or timeout with neither EOSE nor any event. Returns events (possibly partial) otherwise. Non-strict `_query` maps null → `[]` (used by loadMessages, isMember, unreadCounts — so relay failures show as "no messages"/"not member"/0 unread).
* **isMember(h)**: me = pubkeyHex (null → false); newest 39002 (replaceableCreatedAt, ties later wins); true iff it has a `["p", me]` tag.
* **unreadCounts(hs)** (rooms): fallback = now − 7 days (unix s); `since = min(fallback, min over hs of (marks[h] ?? fallback))`; query (6 s); counts init 0 for every h; for each event: skip if created_at not int or (me != null && pubkey == me); take FIRST `h` tag; if that h not in counts → skip event; if `ts > (marks[h] ?? fallback)` → count++. (Not deduplicated by id — single relay.)
* **EventChat unreadCounts(addresses)**: fallback = now − 14 days; marks[a] = lastReadFor("evt|"+a) or fallback if 0; since = min; query all readTargets in parallel; dedupe by event id (first-seen); skip own; first tag with `tag.length >= 2 && tag[0] == 'A'`; same counting rule.
* **markRead(key, until)**: ts = floor(until ms / 1000); no-op if existing ≥ ts.
* **Message order**: ascending createdAt; ties keep insertion order (stable sort).
* **Optimistic duplicate window**: 2 minutes, own messages only, same content.

### 8.9 SatoshiDuell status (hub card)
`npub = SigningService.npub()` (none → empty). `username = profiles lookup (lowercased npub)`; none → empty. `me = username.lower`. My duels: for each with status `open`/`active`: `isCreator = creator.lower == me`; `myScore = isCreator ? creator_score : challenger_score`; if myScore == null → (status == open && isCreator ? waiting++ : myTurn++) else waiting++. Lobby: for status=open duels: skip if creator.lower == me; count if target_player null/empty or == me. Non-200 responses → that part counts 0. Cache 2 min; errors → cached or empty. (Owned jointly with dashboard domain.)

### 8.10 Zap / V4V amount rules
* Zap amounts: 21, 210, 2100, 21000 sats. `amountMsat = sats*1000`; `min = minSendable ?? 1000`, `max = maxSendable ?? 100_000_000_000` (num → int); out of range → amountOutOfRange (zap) / below_min, above_max (V4V).
* Lightning address must split on `@` into exactly 2 non-empty parts.
* Comment (no zap request, or V4V): only if `commentAllowed > 0`; truncate to `commentAllowed` UTF-16 code units (`substring(0, n)`).
* Zap: if `allowsNostr != true` the payment is a plain LNURL payment (no receipt) and the article title goes as `comment` if allowed.
* Invoice amount / description hash are NOT verified against the request (parity; see risks).

### 8.11 Timeouts summary
| Operation | Connect | Wait |
|---|---|---|
| Group relay AUTH | 5 s | 8 s / 80 s / 25 s by signer |
| Group relay query | – | 8 s (unread 6 s) |
| Group relay publish OK | – | 8 s |
| Event chat query | 4 s per relay | 7 s per relay |
| Event chat publish | 4 s | 7 s per relay |
| Event chat live | 4 s per relay (sequential until one connects) | – |
| Article full text | 6 s per relay | 12 s overall |
| Likes fetch | 4 s | 8 s overall |
| Like publish | 5 s | 8 s overall |
| lud16 lookup | 4 s | 6 s overall |
| RSS | – | 12 s |
| LNURL (zap) | – | 12 s each |
| LNURL (V4V) | – | 15 s each |
| Portal | – | 15 s |
| SatoshiDuell | – | 8 s |

---

## 9. Security measures to preserve

1. **Signature verification of every incoming relay event** (Security Audit H2/M1) — §7.1; never trust relay-provided ids.
2. **Response-to-request binding** (`answersFilter`, commit "Relay-Antwort an die gestellte Anfrage binden") — drop validly signed events that do not match the sent filter (wrong kind/author/tag/since).
3. **Future-timestamp clamp** for "newest wins" (`replaceableCreatedAt`: > now + 600 s ignored) — prevents a relay from pinning a fake newest room/profile/article.
4. **Frame size cap** 512 × 1024 chars (Audit M4) before JSON parsing.
5. **Safe URL opening** (Audit M2): links from foreign content (Markdown links, chat association link) only via `launchHttpUrl` → scheme must be http/https AND host non-empty; no `intent:`, `javascript:`, `file:` etc. Markdown images: only http(s) or `data:image/`.
6. **NIP-42 hardening** (recent fixes, tested): never release an unauthenticated socket after a grace period; only the OK for the AUTH event id authenticates; a changed challenge during the initial handshake aborts (no parallel signer prompt); duplicate challenge does not re-prompt; late signer results/errors after abort are discarded silently; renewal challenges are answered on the open socket, superseded renewals dropped; renewal OK never changes auth state.
7. **Read marks are local only** — never publish read state (privacy).
8. **Like de-duplication via local list keyed by pubkey** (prevents identity switch inheriting likes and double-counting on the website).
9. **Offline vs. relay error distinction** — UI must not claim "no room" when the relay gave no answer.
10. **Markdown DoS guards**: skip > 200-char spaceless lines, data-URI > 3 MiB base64 not decoded, HEIC/HEIF placeholder, image decode width cap 1080, progress guarantee in parser loop, render errors fall back to plain text.
11. **Cleartext**: base config forbids cleartext; Flutter allowed `127.0.0.1`/`localhost` only for the just_audio loopback cache proxy. A native Media3 implementation should NOT need any cleartext exception — drop it if unused.
12. **No secrets**: SatoshiDuell key is a public Supabase publishable key (by design). Portal bearer token only sent to portal.einundzwanzig.space.
13. Zap request is signed by the user's identity — only built when the LNURL server declares `allowsNostr`.

---

## 10. Tests & vectors to port

### 10.1 `test/chat_auth_handshake_test.dart` (port 1:1 against the Kotlin `Nip42Authenticator`, using a fake socket that records sent frames and an injectable `sign` function; deadline = 10 s, virtual time)
Fake signed event: `id='auth-id'` (default), `pubkey='test-key'`, `created_at=1792144000`, `kind=22242`, `tags=[['relay', kGroupRelay], ['challenge', <challenge>]]`, `content=''`, `sig='test-sig'`.
1. *Slow signer releases connection only after matching AUTH-OK*: push AUTH "challenge"; after 4 s → not ready, nothing sent; push same AUTH again → sign called exactly once with kind 22242 and tags `[['relay','wss://group.einundzwanzig.space'],['challenge','challenge']]`; complete signature → exactly one frame sent, first element "AUTH", still not ready; push `OK other-id true` → still not ready; push `OK auth-id true` → returns the same socket.
2. *No AUTH → never released early*: after 4 s not ready; after total 10 s → null and socket closed.
3. *Expired deadline discards a late signature*: AUTH, wait 10 s → null; completing the signature afterwards sends nothing; socket closed.
4. *Late signer rejection after abort produces no additional WARN log* — for each abort cause: deadline, changed challenge (`AUTH "changed"`), connection closed. Result null, socket closed, then signer fails with `SigningException('user rejected')` → WARN count for tag `Chat` unchanged; sign called once; nothing sent.
5. *Missing or rejected AUTH-OK stays failed*: (a) `OK auth-id false denied` → null, closed; (b) no OK until deadline → null, closed.
6. *Explicit signer rejection ends auth*: signer throws immediately → null, nothing sent, closed.
7. *Changed challenge starts no parallel approval*: AUTH "challenge", AUTH "changed" → null; completing first signature afterwards → calls == 1, nothing sent.
8. *Renewal challenge after auth on the open socket*: sign returns id `auth-<n>`; AUTH "challenge", OK auth-1 true → socket returned, open, 1 frame sent; AUTH "next" → calls 2, 2 frames, last is AUTH with id `auth-2`, still open; AUTH "next" again → no change; `OK auth-2 false denied` → socket stays open.
9. *Switch during renewal signs only the new challenge*: initial auth OK (calls 1); AUTH "renew-1" (pending) → calls 2, frames 1; AUTH "renew-2" → calls 3, frames 2, last id `renew-2`; completing renew-1 afterwards → frames still 2, open.
10. *Connection drop during approval discards late result*: AUTH, close socket → null; completing signature afterwards sends nothing.

### 10.2 `test/relay_event_signature_test.dart` (relay/core domain, but this domain depends on it) — port against `verifiedEvent`/`answersFilter`/`replaceableCreatedAt`:
valid signed kind-31923 event passes; empty sig rejected; tampered tag rejected; foreign pubkey rejected; replaced id (`'ab'*32`) rejected; non-hex sig (`'zz'*64`) / pubkey `'xyz'` rejected without throwing; `answersFilter` true for matching `{kinds:[31923], authors:[pk], '#d':['test-meetup']}`, false for kind 0, other author, other `#d`; `replaceableCreatedAt` returns created_at for now-ish, null for now+3600; non-map input and `{'kind':'x','tags':42}` return null and increment `rejectedEventCount`.

### 10.3 Related: `test/zap_sent_filter_test.dart` (answersFilter with `#p`/`#P` filters — zap domain), `test/relay_parse_tally_test.dart` (logger), `test/share_origin_test.dart` (iOS share anchor — not applicable to Android).

### 10.4 New unit tests to add natively (vectors derived from the Dart code)
* **slugify**: `"Saarbrücken"→"saarbruecken"`, `"Würzburg"→"wuerzburg"`, `"St. Gallen"→"st-gallen"`, `"  Köln  "→"koeln"`, `"Gießen"→"giessen"`, `"Zürich"→"zuerich"`, `"München (Ost)"→"muenchen-ost"`, `"Bad Kissingen"→"bad-kissingen"`. City match: slug `einundzwanzig-saarbruecken` matches city "Saarbrücken" (endsWith `-saarbruecken`).
* **RSS date**: `"Sat, 17 Oct 2026 08:39:33 GMT"` → `1792226373`; `"Fri, 16 Oct 2026 12:16:17 GMT"` → `1792152977`; `"Mon, 01 Jan 2024 00:00 +0200"` → `1704067200` (offset ignored); `""`/garbage → now.
* **RSS item** (live sample, 2026-10-20):
  ```xml
  <item><title>Die Ausnahme, das Fenster und die Uhr</title>
  <link>https://media.einundzwanzig.space/s/einundzwanzig-news/die-ausnahme-das-fenster-und-die-uhr-71bdfd</link>
  <guid isPermaLink="false">30023:68240d4932950242924434c5b118035a92448a9570caf1fde7aeca77afbc80e4:draft-1c569370-55a9-4ff8-b3c6-2020f697e0f9</guid>
  <pubDate>Sat, 17 Oct 2026 08:39:33 GMT</pubDate><author>Roger</author>
  <description>Ein Zoll, der sich selbst ausnimmt, …</description>
  <enclosure url="https://image.nostr.build/a181242b58e9a95dd7474a7d7fbe7a2c77a42a8ff26a841924ec4c8293c7bd54.jpg" type="image/jpeg" />
  <category>bitcoin</category></item>
  ```
  → id = guid, pubkey(author name) = "Roger", image = enclosure URL, publishedAt = 1792226373, link = the https link, topics = []. `fetchArticleContent` parses pubkey `68240d49…80e4`, d = `draft-1c569370-55a9-4ff8-b3c6-2020f697e0f9`. A guid `30023:pk:a:b` → d = `a:b`. CDATA title `<title><![CDATA[A &amp; B]]></title>` → `A & B`.
* **Read marks**: entries `["abc|100", "evt|31923:pk:d|200", "bad", "|5", "x|notint"]` → `{abc:100, "evt|31923:pk:d":200}`; markRead older value is a no-op.
* **Room unread**: marks {h1: 1000}, me = "me"; events (h1, 1001, other) count, (h1, 1000, other) not, (h1, 1500, me) not, (h2-not-requested) skipped.
* **ChatRoom.fromEvent**: tags `[["d","r1"],["name","Saarbrücken"],["meetup_slug","einundzwanzig-saarbruecken"],["i","meetup:42"],["i","event:31923:pk:d"]]` → h r1, eventAddress `31923:pk:d`; missing d → null.
* **Tag layouts**: event comment tags for address `31923:<pk>:abc` and author `<pk>` → `[[A,addr],[K,31923],[P,pk],[a,addr],[k,31923],[p,pk]]`; empty author → `[[A,addr],[K,31923],[a,addr],[k,31923]]`. Like tags → `[[a,addr],[p,pk],[k,30023]]`. Zap request tags for 2100 sat → `[["relays","wss://nostr.einundzwanzig.space","wss://relay.damus.io","wss://nos.lol"],["amount","2100000"],["p",pk],["a",addr]]`. AUTH tags as in §6.3.
* **LNURL URL builder**: callback `https://x/cb`, 21 sat → `https://x/cb?amount=21000`; callback `https://x/cb?id=1` → `https://x/cb?id=1&amount=21000`; V4V comment `"V4V – 21Meetup"` with `commentAllowed = 5` is truncated on UTF-16 units to `"V4V –"` → `&comment=V4V+%E2%80%93`; with `commentAllowed = 0` no comment param.
* **likes**: contents `["+", "", "🤙", "-", " - "]` from distinct pubkeys → count 3 (`"-"` and `" - "` negative); same pubkey twice → 1.
* **Markdown parser** golden tests: headings 1–3; `#tag` dropped; `#### x` dropped; fenced code with language; quote merge; bullets `-`/`*`; ordered renumbering (`5. a\n7. b` → 1., 2.); image-only line with two images; data-URI inside paragraph splits paragraph; 250-char spaceless line skipped; inline `**b** *i* `c` [l](https://x)`; link with `javascript:` not launched; HEIC placeholder.
* **_isDuplicate** 2-minute window, own only.

---

## 11. Android capabilities, permissions and recommended libraries

* `INTERNET` (already). No location/camera/NFC needed in this domain.
* Package visibility `<queries>`: `VIEW` + scheme `lightning` (wallets), `VIEW` + scheme `https` (browser) — keep.
* Lightning payment: `Intent(ACTION_VIEW, Uri.parse("lightning:$bolt11"))`; V4V uses `Intent.createChooser(intent, "Lightning-Wallet wählen")` after `resolveActivity` check; News zap uses plain `startActivity` (catch `ActivityNotFoundException` → fallback sheet). Use `ClipboardManager` for copy.
* Share: `Intent.ACTION_SEND` text/plain via `Intent.createChooser`.
* External links: `Intent.ACTION_VIEW` (or Custom Tabs) — always through a `SafeUrlLauncher` enforcing http/https + host for foreign content.
* Device calendar insert (course dates): `Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)` with `TITLE`, `DESCRIPTION`, `EVENT_LOCATION`, `EXTRA_EVENT_BEGIN_TIME`, `EXTRA_EVENT_END_TIME` (+2 h) — no calendar permission needed.
* WebSockets: OkHttp `WebSocket` (one client, pingInterval ~30 s); JSON via kotlinx.serialization `JsonElement` (preserve insertion order for filters).
* Schnorr/BIP-340 + SHA-256: `fr.acinq.secp256k1:secp256k1-kmp-jni-android` (shared Nostr core).
* HTTP: OkHttp (+ per-call timeouts as in §8.11); RSS parsing: port the regex parser verbatim (do NOT switch to XmlPullParser — behaviour on malformed feeds must match).
* Images: Coil 3 (`AsyncImage`, size cap 1080 for Markdown, error → hide). Data-URI via `android.util.Base64` (accept URL-safe too).
* Audio: AndroidX Media3 ExoPlayer + `MediaSessionService` (adds proper background playback/notification; requires `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` and `POST_NOTIFICATIONS` (API 33+) if a notification is shown). For seekability without range support: download each track fully into a `SimpleCache` (`CacheWriter`) or a plain file in `cacheDir`, expose progress, and switch the item to the local file when complete (same UX as Flutter). Playlist = `setMediaItems(41 items)`, `repeatMode OFF`, error listener skips to next.
* Persistence: `SharedPreferences`/DataStore + one-time migration from `FlutterSharedPreferences.xml` (§4).
* UI: Jetpack Compose Material 3 (Expressive): `PullToRefreshBox`, `ModalBottomSheet`, `SnackbarHost`, `TabRow`, `Slider`, `ExposedDropdown` not needed; Markdown via custom AnnotatedString renderer with `LinkAnnotation.Clickable` routed through SafeUrlLauncher.
* Connectivity: `ConnectivityManager` to classify offline vs relay failure (complementing exception-text heuristics).

---

## 12. Cross-domain dependencies

Calls OUT of this domain:
* **Signing/identity**: `SigningService.signEvent`, `pubkeyHex`, `npub`, `getMode` (signer-dependent AUTH timeout), `Nip46Client.signTimeout` (60 s).
* **Nostr core / relay**: `RelaySocket.connect`, `verifiedEvent`, `answersFilter`, `replaceableCreatedAt`, frame cap; NIP-19 `hexToNpub`.
* **Relay settings**: `RelayConfig.getActiveRelays` (prefs `custom_relays`, `disabled_default_relays`).
* **Profiles**: `NostrProfileService.fetchDisplayName`; `NostrAvatar` composable (kind-0 picture + letter fallback).
* **Safe URL**: `launchHttpUrl`.
* **Logging**: `AppLogger` (tags `Chat`, `EventChat`, `NewsService`, `NewsReactions`, `NewsZap`, `V4VService`, `PlebRap`, `SatoshiDuell`), `RelayParseTally` (article fetch, label "Artikel-Volltext").
* **Portal**: `PortalApiService.getCourses/getLecturers/getCourse/getLecturer` (+ unreachable `getAllMeetupEvents/getRsvp/rsvp/hasToken`), `MeetupCalendarService.portalStart`.
* **Meetups (via dashboard)**: `MeetupService.resolveFavorite/cityFor/labelFor` for chat room lookup.
* **Guide/tours**: `GuideService` (`wasTourCompleted`, `startTour`, `finishTourNow`, `activeTour`), `GuideTour.portal/portalArea`, target-key registry.
* **Navigation targets**: CalendarScreen, NearbyMeetupsScreen, PortalMeetupsScreen, CommunityPortalScreen (portal/calendar domains).
* **SatoshiDuellService** (shared with dashboard tile).

Calls INTO this domain:
* Dashboard/home: chat room lookup + unread (`findRoomForMeetupId`, `findRoomForCity`, `unreadCounts`, `ChatRelayUnavailable`), `ChatScreen.room`, `EventChatService.unreadCounts`, `NewsService.unreadCount/cachedArticles/markRead`, `NewsScreen`, `CommunityHubScreen`, `PortalAreaScreen`, `GlossaryScreen`, `V4VScreen`, `PlebrapAudio` (mini player) & `PlebrapPlayerScreen`, `kPlebSongs`.
* Calendar (event_calendar_screen) & MyEventsScreen: `ChatScreen.event`, `EventChatService.unreadCounts`.
* Settings: `V4VScreen`.
* WidgetService (home-screen widget): `NewsService.fetchArticles(limit: 1)`.
* EventRsvpService (calendar RSVP): `NewsReactionsService.readTargets()` for both read and write.
* ZapReceiptValidator (humanity/zap): `NewsZapService.fetchLightningAddress`.

---

## 13. Proposed Kotlin package / module structure and public API

```
:core:nostr          (shared)  NostrEvent, EventVerifier, FilterMatcher, RelayClient(OkHttp), Nip19
:data:identity       (shared)  SigningService/Signer { suspend fun sign(kind, tags, content): SignedEvent; suspend fun pubkeyHex(): String?; suspend fun npub(): String?; suspend fun mode(): SigningMode }
:core:network        (shared)  HttpClient, LnurlPayClient   (SafeUrl helpers: :core:common `url`)
:feature:chat        space.einundzwanzig.meetup.chat
:feature:news        space.einundzwanzig.meetup.news
:feature:community   space.einundzwanzig.meetup.community   (hub, portal area, courses, lecturers)
:feature:v4v         space.einundzwanzig.meetup.v4v
:feature:plebrap     space.einundzwanzig.meetup.plebrap
:feature:glossary    space.einundzwanzig.meetup.glossary
:ui:markdown         space.einundzwanzig.meetup.ui.markdown
```

```kotlin
// ---------- chat ----------
const val GROUP_RELAY = "wss://group.einundzwanzig.space"
const val VEREIN_URL = "https://verein.einundzwanzig.space/association/profile"

data class ChatRoom(val h: String, val name: String = "", val about: String = "", val picture: String = "",
                    val meetupSlug: String = "", val eventAddress: String = "") {
  val isMeetup get() = meetupSlug.isNotEmpty(); val isEvent get() = eventAddress.isNotEmpty()
  companion object { fun fromEvent(ev: JsonObject): ChatRoom? }
}
data class ChatMessage(val id: String, val pubkey: String, val content: String, val createdAt: Instant) {
  companion object { fun fromEvent(ev: JsonObject): ChatMessage? }
}
class ChatRelayUnavailable(val offline: Boolean = false) : Exception()

/** NIP-42 state machine of §8.2; pure, testable with a fake socket and virtual time. */
class Nip42Authenticator(private val relayUrl: String, private val sign: suspend (Int, List<List<String>>, String) -> SignedEvent) {
  suspend fun authenticate(socket: RelaySocket, onMessage: (JsonArray) -> Unit, timeout: Duration): RelaySocket?
}

interface GroupChatRepository {
  suspend fun loadMeetupRooms(force: Boolean = false): List<ChatRoom>          // throws ChatRelayUnavailable
  suspend fun findMeetupRoom(slug: String): ChatRoom?
  suspend fun findRoomForMeetupId(portalId: String): ChatRoom?                // throws ChatRelayUnavailable
  suspend fun findRoomForCity(city: String): ChatRoom?                        // throws ChatRelayUnavailable
  suspend fun isMember(h: String): Boolean
  suspend fun join(h: String): String?            // null = ok, else reason
  suspend fun leave(h: String): String?
  suspend fun loadMessages(h: String, limit: Int = 100): List<ChatMessage>
  fun subscribe(h: String, since: Instant?): Flow<ChatMessage>              // cancellation closes socket
  suspend fun send(h: String, text: String): String?
  suspend fun unreadCounts(hs: List<String>): Map<String, Int>
}
interface EventChatRepository {
  suspend fun loadMessages(eventAddress: String): List<ChatMessage>
  suspend fun send(eventAddress: String, eventAuthorPubkey: String, text: String): String?
  fun subscribe(eventAddress: String, since: Instant?): Flow<ChatMessage>
  suspend fun unreadCounts(addresses: List<String>): Map<String, Int>
  companion object { fun readKey(address: String) = "evt|$address" }
}
interface ChatReadMarkStore {             // prefs key "chat_last_read", "<key>|<sec>"
  suspend fun lastReadFor(key: String): Long        // 0 if none
  suspend fun markRead(key: String, until: Instant)
  suspend fun all(): Map<String, Long>
}
sealed interface ChatTarget { data class Room(val room: ChatRoom) : ChatTarget
                              data class Event(val address: String, val author: String, val title: String) : ChatTarget }
@Composable fun ChatScreen(target: ChatTarget, onBack: () -> Unit)
class ChatViewModel(target: ChatTarget, ...) : ViewModel()   // StateFlow<ChatUiState(loading, member, joining, sending, messages, names)>

// ---------- news ----------
const val NEWS_FEED_URL = "https://media.einundzwanzig.space/s/einundzwanzig-news/feed.xml"
const val NEWS_WEBSITE_URL = "https://media.einundzwanzig.space/s/einundzwanzig-news"
const val NEWS_WRITE_URL = "https://media.einundzwanzig.space"
data class NewsArticle(val id: String, val author: String, val title: String, val summary: String, val image: String,
                       val content: String, val publishedAt: Long, val link: String)
object RssParser { fun parse(xml: String): List<NewsArticle>; fun parseDate(s: String, now: Long): Long }
interface NewsRepository {
  suspend fun fetchArticles(limit: Int = 50): List<NewsArticle>
  suspend fun cachedArticles(limit: Int = 50): List<NewsArticle>   // 10-min RAM cache
  suspend fun unreadCount(): Int
  suspend fun markRead()
  suspend fun fetchArticleContent(guid: String): String?          // NIP-23
}
data class ArticleLikes(val count: Int, val mine: Boolean) { fun withMine(): ArticleLikes }
interface NewsReactionsRepository {
  suspend fun readTargets(): List<String>        // shared with RSVP + zap validator
  suspend fun hasLikedLocally(address: String): Boolean
  suspend fun fetchLikes(address: String): ArticleLikes
  suspend fun like(address: String, authorPubkey: String): Boolean
}
enum class ZapError { NoLightningAddress, UnsupportedAddress, LnurlUnreachable, AmountOutOfRange, SigningFailed, InvoiceFailed }
sealed interface ZapResult { data class Ok(val bolt11: String) : ZapResult; data class Err(val error: ZapError) : ZapResult }
interface NewsZapService {
  suspend fun fetchLightningAddress(pubkeyHex: String): String?   // "lud06:" marker
  suspend fun createZapInvoice(authorPubkey: String, articleAddress: String, amountSats: Long, comment: String?): ZapResult
}
@Composable fun NewsScreen(...); @Composable fun ArticleDetailScreen(article: NewsArticle, ...)

// ---------- lnurl / v4v ----------
const val V4V_ADDRESS = "wolpertinger1@timecatcher.lnbits.de"
class LnurlPayClient(http: OkHttpClient) {
  suspend fun fetchMeta(address: String, timeout: Duration): LnurlMeta   // tag/callback/min/max/commentAllowed/allowsNostr
  fun buildCallbackUrl(callback: String, amountMsat: Long, nostr: String? = null, comment: String? = null): String
  suspend fun requestInvoice(url: String, timeout: Duration): JsonObject
}
sealed interface V4VResult { data class Ok(val invoice: String) : V4VResult; data class Err(val code: String) : V4VResult }
interface V4VService { suspend fun createInvoice(amountSats: Long, address: String = V4V_ADDRESS, comment: String? = null): V4VResult }
object LightningLauncher { fun openWithChooser(ctx: Context, bolt11: String): Boolean; fun open(ctx: Context, bolt11: String): Boolean }

// ---------- community ----------
@Composable fun CommunityHubScreen(nav: CommunityNav); @Composable fun PortalAreaScreen(nav: CommunityNav)
@Composable fun CoursesScreen(...); @Composable fun CourseDetailScreen(id: Int, fallback: JsonObject)
@Composable fun LecturerDetailScreen(id: Int, fallback: JsonObject)
object PortalFields { fun pv(e: JsonObject, key: String): String; fun pImg(e: JsonObject): String; fun extractLinks(text: String): List<String> }

// ---------- plebrap ----------
data class PlebSong(val album: String, val title: String, val artist: String, val url: String) { val cover: String }
val PLEB_SONGS: List<PlebSong>   // 41 entries, §3.8
interface PlebrapPlayer {         // app-wide singleton (Hilt @Singleton) backed by MediaController
  val index: StateFlow<Int?>; val loading: StateFlow<Boolean>; val loadErrors: StateFlow<Int>
  val cacheProgress: StateFlow<Float>; val isPlaying: StateFlow<Boolean>
  val position: Flow<Long>; val duration: StateFlow<Long?>
  suspend fun playIndex(i: Int); suspend fun toggle(); suspend fun next(); suspend fun prev(); suspend fun seekTo(ms: Long)
}
@Composable fun PlebrapPlayerScreen(...); @Composable fun PlebrapMiniPlayer(onOpenLibrary: () -> Unit)

// ---------- glossary ----------
enum class GlossaryCategory { Start, Badges, Reputation, Network, Identity, Events, Nostr, App }
data class GlossaryEntry(val category: GlossaryCategory, @StringRes val title: Int, @StringRes val body: Int)
val GLOSSARY: List<GlossaryEntry>   // 55, §2.12
@Composable fun GlossaryScreen(...)

// ---------- markdown ----------
sealed interface MdBlock { data class Heading(val level: Int, val text: String); data class Paragraph(val text: String)
  data class Quote(val text: String); data class ListBlock(val ordered: Boolean, val items: List<String>)
  data class Code(val code: String); data class Image(val url: String); data object Rule }
object MarkdownParser { fun parse(src: String): List<MdBlock>; fun inline(text: String): List<MdSpan>; fun plainFallback(src: String): String }
@Composable fun MarkdownView(data: String, onLink: (String) -> Unit = SafeUrlLauncher::open)
```

---

## 14. Risks / compatibility pitfalls

1. **NIP-42 regressions**: the handshake is the #1 source of field bugs (empty room list, "no room" while offline, double Amber prompts). Port §8.2 exactly and port all 10 handshake tests. Do NOT release a socket on a grace timer; only the matching OK authenticates.
2. **Signer-dependent timeouts**: Amber/bunker need 25 s / 80 s. A fixed short timeout reproduces the old "Kein Chatraum" bug.
3. **Null vs. empty**: room lookups must distinguish "relay gave no answer" (throw `ChatRelayUnavailable`) from "no room" (null); city fallback must not run after a relay failure; do not cache empty room lists.
4. **Event verification parity**: the 10-digit `created_at` rule and stringified tag elements are nostr-dart quirks; a stricter/looser verifier changes which events are visible (e.g. events with created_at < 1e9 or ≥ 1e10 are rejected).
5. **Tag order and exact values** of kinds 9, 9021, 1111, 7, 9734, 22242 (§6) — other clients (Ben's web UI, media site counting likes as events) depend on them. `["k","30023"]` is a string. `relays` tag lists exactly the 3 news relays.
6. **Like counting semantics**: website counts events, app counts distinct pubkeys + local mark; never send a second reaction for the same `<pubkey>|<address>`; key the local list by the SIGNER pubkey.
7. **Read-mark storage format** (`chat_last_read` with `evt|` keys, parse via LAST `|`) and `news_last_read_ts` semantics must survive migration from Flutter prefs (`flutter.` prefix, list encoding with `VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu!` + JSON or legacy base64).
8. **RSS timezone ignored** (treated as UTC). Switching to a "correct" RFC-822 parser would shift `publishedAt` if the feed ever uses non-GMT offsets and change unread counts relative to the stored mark — keep parity or migrate deliberately.
9. **readTargets includes relay.nostr.band and ignores user-disabled defaults**; event-chat writes go to ALL read targets (including the read-only aggregator) — keep for parity (harmless timeouts) or document a deliberate change across domains (RSVP shares the list).
10. **Group metadata trust**: 39000/39002 are not checked against the relay's pubkey; adding such a check (NIP-11 `pubkey`) is a hardening option but could hide rooms if the relay key changes — coordinate before changing.
11. **Edge crashes in Flutter not to replicate**: `fetchLightningAddress` logs `pubkeyHex.substring(0, 8)` → throws for pubkeys shorter than 8 chars, leaving the zap spinner stuck; chat bubble fallback letter `substring(0,1)` on an empty string when no identity exists; Courses lecturer "Nostr" link builds `https://npub…`. Guard these natively.
12. **Article id without guid** (link used as id): `authorPubkey` becomes `//host/...`; full text fetch returns null; like would be published with a bogus `p` tag. Natively, skip the `p` tag / disable zap when `authorPubkey` is not 64-hex (behavioural improvement that does not affect wire format for valid articles).
13. **Zap invoice not validated** (amount/description hash vs. zap request) — parity keeps it unvalidated; if adding validation, only warn, never block silently.
14. **Query-component encoding**: Dart encodes space as `+` and leaves `!~*'()` unescaped; OkHttp `HttpUrl.Builder.addQueryParameter` uses `%20`. Prefer building the query string manually with form encoding for parity; both decode identically on compliant servers.
15. **PlebRap source**: server lacks range requests and Content-Length; ExoPlayer streaming will be unseekable — implement the full-download cache. URLs contain pre-encoded characters; never re-encode. Without a MediaSessionService, playback may stop when the process is killed (Flutter has the same limitation).
16. **Markdown behaviour**: lines starting with `#` (non-heading) vanish; only http(s)/data images; this is relied upon to hide raw base64 and hashtags. Using an off-the-shelf CommonMark renderer would change output and could open non-http links — port the parser.
17. **Multiple connections per chat open** (3 AUTH handshakes): with Amber each may prompt; a single shared authenticated connection is an acceptable optimisation as long as semantics (null vs empty, retry on `auth-required`) are kept.
18. **Hard-coded strings** ("Auf der Webseite lesen", "(ohne Titel)", "Lightning-Wallet wählen", "Meetup #", "Termin") are German-only in Flutter; native may localize them (no compatibility impact).
19. **PortalEventsScreen** is dead code (and has a string-interpolation bug); PortalArea "Events" opens the calendar. Don't silently "fix" by routing to a new screen without product decision.
20. **Hub News card vs. dashboard News tile**: only the dashboard tile clears the unread counter / widget NEW flag — keep or unify consciously.
