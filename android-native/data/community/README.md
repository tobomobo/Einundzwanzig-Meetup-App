# :data:community

Data layer of spec 11 "community": V4V donations, PlebRap playback, glossary data and the
community-hub/portal helpers. It has no UI; the screens live in `:feature:community` (or wherever
the hub, V4V, PlebRap and glossary screens end up).

```kotlin
implementation(project(":data:community"))
```

Package root: `space.einundzwanzig.meetup.data.community`. Hilt binds every interface as a
`@Singleton` (`di.CommunityModule`).

Sources: `lib/services/v4v_service.dart`, `lib/services/plebrap_audio.dart`,
`lib/screens/glossary_screen.dart` and `lib/screens/community_hub_screen.dart` (the portal field helpers).

## V4V — `…community.v4v`

| API | Purpose |
|---|---|
| `interface V4vService` | `suspend createInvoice(amountSats: Long, address = V4V_LIGHTNING_ADDRESS, comment: String? = null): V4vResult`. Runs LNURL-pay (N4 metadata, then N5 callback) with a 15 s budget per request. Never throws, except for cancellation |
| `sealed V4vResult` | `Success(invoice)` / `Failure(code)`, plus `ok`. `Failure.kind: V4vErrorKind` maps the code to the screen message |
| `object V4vError` | Codes `invalid_amount`, `invalid_address`, `unreachable`, `not_lnurlp`, `below_min`, `above_max`, `invoice_failed`. Any other code is the server's `reason` text. `kindOf(code)` |
| `enum V4vErrorKind` | `InvalidAmount`→`v4vErrInvalidAmount`, `BelowMin`→`v4vErrBelowMin`, `AboveMax`→`v4vErrAboveMax`, `Unreachable`→`v4vErrUnreachable`, `Generic`→`v4vErrGeneric` |
| `buildLnurlCallbackUrl(callback, amountMsat, comment, commentAllowed, nostr: String? = null): String` | Byte-exact Dart LNURL-pay callback URL for V4V (N5) **and** the news zap (N3): separator `?`/`&`, `amount=<msat>`, then `&nostr=<encodeQueryComponent(zapRequestJson)>` when `nostr != null` (no comment then), else `&comment=` cut to `commentAllowed` UTF-16 units when allowed. **Canonical copy** of this logic: `:data:news` (and `:feature:news`) must call this function, adding a dependency on `:data:community`, and must not duplicate it, so the byte-exact URL cannot drift. Spec §13 places it in a shared `:core:network` `LnurlPayClient`; when that exists, the function and `LnurlCallbackUrlTest` move there |
| `V4V_LIGHTNING_ADDRESS`, `V4V_DEFAULT_COMMENT` | `wolpertinger1@timecatcher.lnbits.de`, `"V4V – 21Meetup"` (en dash). The screen passes the comment |
| `parseV4vAmount(text): Long` | `_donate()` parsing: trim, drop `.` and `,`, then `int.tryParse`. Returns `0` when parsing fails. A value ≤ 0 shows `v4vErrInvalidAmount` |
| `object LightningInvoiceLauncher` | `openWithChooser(context, bolt11, chooserTitle = "Lightning-Wallet wählen"): Boolean` is the V4V path: it checks `resolveActivity` and then opens a chooser. `open(context, bolt11)` is the news-zap path: a plain `startActivity` that returns false on `ActivityNotFoundException`. `invoiceIntent(bolt11)`. When either call returns `false`, show the copy-invoice sheet |

The manifest adds a `<queries>` entry for `VIEW lightning:` so the wallet check works on API 30+.

## PlebRap — `…community.plebrap`

| API | Purpose |
|---|---|
| `data class PlebSong(album, title, artist, url)` + `cover` | One track. The URL is pre-encoded; never re-encode it |
| `object PlebSongs` | `all` (the 41 songs; list order = playlist index), `albums` (in order of first appearance), `byAlbum: Map<String, List<IndexedValue<PlebSong>>>`, `lastIndex`, `FALLBACK_ARTIST = "Plebs together strong"` |
| `interface PlebrapPlayer` | The app-wide singleton behind the player screen and the dashboard mini player. State: `index: StateFlow<Int?>`, `loading`, `loadErrors` (monotonic: show `prLoadError` on every change while visible), `cacheProgress: StateFlow<Float>` (0..1, where 0 means unknown and the bar is indeterminate), `isPlaying` (play intent; `false` once the playlist has ended, so `toggle()` then restarts the last track like the notification's play button), `durationMs: StateFlow<Long?>` (null while unknown), `seekable: StateFlow<Boolean>` (duration known **and** the source can seek), `positionMs: Flow<Long>` (sampled every 200 ms while collected), `current`. Commands (main-safe suspend functions): `playIndex(i)`, `toggle()`, `next()`, `prev()`, `seekTo(ms)` |
| `class PlebrapPlaybackService` | Media3 `MediaSessionService` (foreground type `mediaPlayback`), declared in this module's manifest. It is bound automatically by any player command (and re-bound after the session was released). Only this app, the media notification and trusted system controllers (System UI, Bluetooth, Auto/Wear) may connect. When the app task is removed while nothing plays, the service releases its session and stops (the notification goes away); the next player command binds it again. Notification and system-UI artwork loads through the shared `OkHttpClient`. Features never touch it |

UI rules from spec 11 §2.11:
- `prev` is enabled when `index != null && index > 0`; `next` when `index != null && index < 40`.
- The slider is enabled only when `seekable` is `true`. Seek only in `onChangeEnd` (`seekTo`).
  This is stricter than Flutter's `durationMs != null`: a streamed MP3 can report a duration from its
  header while the source still cannot seek until it is cached.
- The caching hint `prCaching((p*100).round())` shows while `!seekable && current != null`.

Behaviour:
- **Playlist:** all 41 songs, `REPEAT_MODE_OFF`. `next` and `prev` move to the neighbouring item and do not wrap around.
- **Errors:** a track that fails to load increments `loadErrors` and skips to the next track.
- **Caching:** every track is downloaded completely to `cacheDir/plebrap_audio`, a Media3 `SimpleCache` with a 400 MB LRU limit. When the download finishes and the player still cannot seek, the track is reloaded from the cache at the same position. A track that was cached before plays from disk and can seek right away. "Cached" is checked against the span files on disk, not only SimpleCache's index: if Android trimmed `cacheDir`, the entry is dropped and downloaded again. A download is only reported complete when the track really is fully cached; a failed download is retried by the next `playIndex` of the same track or by resuming with `toggle`.
- **Data use:** on first play each track is fetched twice, because the player streams it while the cache download runs in parallel (Flutter's `LockCachingAudioSource` fed the player from its single download). That is about 2× Flutter's mobile data for a first listen (tracks are 6–8 MB); replays come from the cache and use no data.
- **Notification and controls:** the media notification has prev/play/next and the PlebRap metadata and cover. It works with the lock screen and Bluetooth, and tapping it opens the launcher activity. If you want it to open the PlebRap screen instead, route the launcher intent in `:app`.
- **Permissions** (merged from this manifest): `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `INTERNET`. Media-session notifications are exempt from the runtime notification permission, so playback does not need a prompt.

## Glossary — `…community.glossary`

| API | Purpose |
|---|---|
| `enum GlossaryCategory(labelRes)` | `Start, Badges, Reputation, Network, Identity, Events, Nostr, App`, in display order. Each has a `glCat*` label. Icons (Material rounded): flag, military_tech, workspace_premium, hub, key, celebration, bolt, tune |
| `data class GlossaryEntry(category, titleRes, bodyRes)` | Refers to the `gl<Name>Title`/`gl<Name>Body` strings in `:core:l10n` |
| `object Glossary` | `entries` (55, in Flutter order). `resolve(getString)` returns `List<ResolvedGlossaryEntry>`. `search(resolved, query)` uses Dart trim + Dart `toLowerCase` (per-code-point, resolution #12) on query and entry text and matches title or body; a blank query returns all entries. `groupByCategory(hits)` follows the enum order and leaves out empty categories. `normalizeQuery(q)`. Expand all entries while `normalizeQuery(q)` is non-empty |

## Community hub / portal — root package and `…community.portal`

| API | Purpose |
|---|---|
| `object CommunityLinks` | `NOSTR_PROFILE` (njump), `SHOUTOUT`, `PODCAST`, `SATOSHI_DUELL`, `PLEBRAP_V4V`, `satoshiDuell(npub)` (adds `?npub=` when the npub is non-empty). Open these externally through `SafeUrl` |
| `object PortalFields` | Dart portal helpers. `pv(e, key)`, `pImg(e)`, `extractLinks(text)` (the Dart `\s` regex, deduplicated, keeps order), `intId(e): Int?` (`id is int`; typed `Int` to match `PortalApiService.getCourse/getLecturer(id: Int)` and the `CourseDetails`/`LecturerDetails` routes, so a JSON integer outside 32 bits gives `null` and the list falls back to the bottom sheet rather than wrapping to a wrong id; `PortalCourse.lecturerId: Int?` follows the same rule), `listName(e)`, `listDescription(e)`. `course(e, zone)` returns a `PortalCourse`, `courseEvent(ev, zone)` a `PortalCourseEvent` (`whenLabel` = `dd.MM.yyyy · HH:mm` from Dart `DateTime.tryParse` fields), `lecturer(e)` a `PortalLecturer` (with `links: List<PortalLink>`). Also `lecturerLinkUrl(key, value)` and `calendarTitle(ev)` (`title`, then `loc`, then `"Termin"`). Calendar end = start + 2 h |

`PortalEntityCache` (`@Singleton`, injectable): process-lifetime map id → last seen course / lecturer
JSON, the replacement of Flutter's `fallback` argument (ROUTES.md). `rememberCourses/rememberLecturers`
(lists), `rememberCourse/rememberLecturer` (detail responses; nested `lecturer` / `courses` fill the other
map without overwriting), `course(id)`, `lecturer(id)`. Added for `:feature:community`.

Not in this module: the portal courses/lecturers API (`:data:meetups`, portal API), SatoshiDuell status
(`:data:proofs`) and tour progress (`:data:settings`).

## Tests

```
tools/gradlew-locked :data:community:testDebugUnitTest
```

68 tests:
- `V4vServiceTest` (MockWebServer): the full LNURL sequence and every error code, plus the comment vector of §10.4.
- `LnurlCallbackUrlTest`: the §10.4 URL builder vectors and amount parsing.
- `LightningInvoiceLauncherTest` (Robolectric).
- `GlossaryTest`: 55 entries, category sizes, strings resolve, search (Dart `toLowerCase` semantics, resolution #12: no final sigma, `İ`→`i`) and grouping.
- `PortalFieldsTest`, `PortalEntityCacheTest`.
- `PlebSongsTest`: the catalog copied verbatim, the album order and the covers.
- `PlebrapControllerTest`: the player logic against a fake player and cache. Covers toggle, playIndex, next/prev without wrap-around, skipping broken tracks, the reload after download, cancelling a download on track change, already-cached tracks, retrying a failed download, and `seekable`.
- `Media3AudioCacheTest` (Robolectric + MockWebServer, a chunked server that ignores Range): the download ends fully cached with its length stored, and the player source reports that length and seeks without network. Also covers resuming a cancelled download (skipping the `200` prefix) the writer waiting for a locked span, and span files deleted behind the index (re-downloaded).

Flutter has no Dart unit tests for this domain.
