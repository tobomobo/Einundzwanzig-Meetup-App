# :data:meetups

Meetup directory, location and Einundzwanzig portal client. Port of `lib/models/meetup.dart`,
`meetup_service.dart`, `meetup_location_service.dart`, `nearby_meetup_service.dart`,
`geocoding_service.dart`, `portal_api_service.dart`, `portal_leader_service.dart` and the crest part
of `meetup_calendar_service.dart` (`portalLogos`, `logoFor`, `absoluteImageUrl`, `portalStartUtc`).
Spec: `spec/09-meetups-portal.md` (plus 03 §12 / 10 §8.5 for the crest registry, 00 items 6 and 16).
No UI. Screens (nearby map, favorites picker, "Meine Meetups", event editor, details, location
picker) belong to `:feature:meetups`.

```kotlin
implementation(project(":data:meetups"))
```

Package root: `space.einundzwanzig.meetup.data.meetups`. Every service is a Hilt `@Singleton`
interface. Implementations are `internal`. Network calls run on `@IoDispatcher` and never throw.
The module declares `INTERNET`, `ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION`.

## Directory (root package, `…model`)

| API | Purpose |
|---|---|
| `@Serializable data class Meetup` | `id` (portal ID as string, e.g. `"107"`, or fallback `m_muc`/`m_hh`/`m_b`), `name` (group), `city`, `country`, `telegramLink` (portal `url`, may lack a scheme), `lat`, `lng`, `logoUrl` (may be relative), `description` (`intro`), `website`, `portalLink`, `twitterUsername`, `nostrNpub`, `coverImagePath`; derived `groupName` (name if it differs from the city, case-insensitive), `hasCoords`. Serializable so features can put it into NavKeys |
| `interface MeetupService` | `cached`, `cachedFlow: StateFlow<List<Meetup>>`, `fallbackMeetups` (Kotlin constant, safe on the main thread), `suspend fetchMeetups()` (`GET /api/mobile/meetups`, 15 s, `[]` on failure without touching the cache, non-object entries skipped and logged, deduplicated by exact ID), `resolveFavorite(stored)` (ID first, then legacy city name, case-insensitive), `labelFor(stored)`, `cityFor(stored)`, `bySlug(slug, country)` (display only, `MeetupKey.slug(city)` rule), `byName(name)`, `suspend organizerMeetupFor(hexOrNpub)`, `migrateFavorites(stored, meetups = cached)` |
| `object FavoriteMigration` | `migrate(stored, meetups): Result(ids, ambiguous, dropped)`. Maps legacy city-name favorites to portal IDs (spec 09 §8.4, spec 00 item 6). Persist only when the user saves, and never while the directory is empty (§14 item 11) |

Favorites (`favorite_meetups`, `home_meetup`) are owned by the user profile (`:data:identity`).
This module only resolves them. Always use `resolveFavorite`. Never compare `city == homeMeetupId`.

## Location — `…location`

| API | Purpose |
|---|---|
| `interface MeetupLocationService` | `isLocationServiceEnabled()`, `permissionState(): LocationPermission` (never prompts; `DENIED_FOREVER` is remembered from the last prompt), `suspend getCurrentLocation(): LocationResult` (one permission request whenever not granted, also after "denied forever" so a settings reset is noticed; then 3-stage: balanced fix 25 s → LocationManager high-accuracy fix 20 s → last known ≤ 10 min, works without Play services), `suspend resolveLocation(): LocationMeetupResult` (no fallback to the bundled list), `distanceKm(...)`, `isWithinParticipantRadius(lat1, lng1, lat2, lng2)` ((0,0) reference → `true`), `openLocationSettings()`, `openAppSettings()` |
| `MeetupLocationService.PARTICIPANT_RADIUS_KM` / `CREATION_RADIUS_KM` / `DERIVED_RADIUS_KM` / `AMBIGUITY_THRESHOLD_KM` | 5.0 / 10.0 / 50.0 / 2.0. Also `FIRST_FIX_TIMEOUT_S`, `SECOND_FIX_TIMEOUT_S`, `MAX_LAST_KNOWN_AGE_MIN` |
| `object Geo` | `distanceMeters`, `distanceKm`: geolocator haversine with R = 6378137 m. Never use `Location.distanceBetween` |
| `enum LocationStatus` | `OK, DENIED, DENIED_FOREVER, SERVICE_DISABLED, NO_FIX, ERROR`; `toGpsStatus()` |
| `enum GpsStatus` | `OK, DENIED, SERVICE_DISABLED, ERROR` (the coarse status used by the badge flows) |
| `enum LocationPermission` | `GRANTED` (fine or coarse), `DENIED`, `DENIED_FOREVER` |
| `data class Position(latitude, longitude, accuracyMeters, timeMillis)`, `LocationResult(status, position?)` | Fix and result |
| `data class MeetupCandidate(meetup, distanceKm)`, `LocationMeetupResult(status, lat, lng, candidates)` | `nearest`, `withinCreationRadius` (≤ 10 km), `isAmbiguous` (two nearest < 2 km apart) |
| `interface LocationPermissionBridge` | Host side of the permission prompt. `MainActivity` collects `pendingRequest`, calls `onLaunched(id)`, launches `RequestMultiplePermissions(FINE, COARSE)` and reports `onResult(granted, permanentlyDenied)` (id-less, survives Activity recreation; `onResult(id, …)` also exists) / `onLaunchFailed(id)`. See the KDoc for the wiring snippet. Without a collector a request resolves to `DENIED` immediately; an unanswered request resolves to `DENIED` after 120 s |

## Nearby — `…nearby`

| API | Purpose |
|---|---|
| `interface NearbyMeetupService` | `suspend preload(forceReload = false)` (process cache, empty directory → bundled list, dedupe by city for Dart parity), `suspend search(centerLat, centerLng, radiusKm, dateMode, dayFrom?, dayTo?, zone)` (sorted by distance). Constants `DEFAULT_RADIUS_KM` 25, `MIN_RADIUS_KM` 5, `MAX_RADIUS_KM` 100, `RADIUS_STEP_KM` 5 (`Slider(steps = 18)`), `ANY_WINDOW_PAST_HOURS` 12 |
| `enum DateMode` / `enum SearchMode` | `ANY, SINGLE_DAY, RANGE` / `HERE, PLANNED` |
| `data class NearbyMeetup(meetup, nextEvent?, allEvents, distanceKm?)` | `hasCoords` |
| `data class MeetupEventInfo` | `title, description, location, start: Instant, url, meetupId, meetupPortalLink, portalEventId?`: the calendar fields the search needs |
| `fun interface MeetupEventsSource` | **Bound by `:data:calendar`** (`@Binds`, optional here) and wraps `fetchMeetupsPortalFirst()`. Without a binding, nearby runs without dates |

Distance label in the UI: `distanceKm.toStringAsFixed(if (distanceKm < 10) 1 else 0)` from
`:core:common` (spec 09 §8.13). Never use `String.format`.

## Geocoding — `…geocoding`

| API | Purpose |
|---|---|
| `interface GeocodingService` | `suspend search(query, limit = 5): List<GeoPlace>` (Nominatim `jsonv2`, < 2 chars → no request, 10 s, ≥ 1 s between requests, (0,0) dropped). Constants `URL`, `USER_AGENT` (`EinundzwanzigMeetupApp/1.0 (nostr meetup app)`), `DEFAULT_LIMIT`, `MIN_QUERY_LENGTH`, `DEBOUNCE_MS` (600, UI side), `MIN_INTERVAL_MS` |
| `data class GeoPlace(displayName, shortName, lat, lng)` | One result |

## Portal — `…portal`

| API | Purpose |
|---|---|
| `interface PortalApiService` | **Token:** `getToken()`, `hasToken()`, `storeToken(t): Boolean` (binds to the mode-aware `SigningService.npub()`; `false` = nothing stored, show an error), `setManualToken(t): Boolean`, `deleteToken(): Boolean`, `loginWithNostr(deviceName)` (kind 22242 `[["challenge", k1]]` → `POST /api/mobile/token`, not NIP-98, never published), `logout()` (best-effort revoke + local delete, non-cancellable), `tokenMatchesCurrentKey()` (decision table §8.11), `getProfile()`. **Generic:** `rawGet(path)`, `rawGetStatus(path): StatusBody` (0 = offline, distinguishes 401/403). **Organizer:** `getMyMeetups()`, `getMyMeetupEvents()`, `createMeetupEvent(...)`, `updateMeetupEvent(...)` (nulls/empties omitted), `getMeetupLeaders(id)`, `addMeetupLeader(id, npub)`, `removeMeetupLeader(id, userId)`. **Public/RSVP:** `getAllMeetupEvents(today)` / `getAllMeetupEvents()` (today from the injected `TimeSource`, device zone; raw maps, 3 dates, merged by id), `getRsvp(id)`, `getRsvpCached(id, forceRefresh)` (1 h), `clearRsvpCache(id)`, `rsvp(id, status)`, `getRsvpAttendees(id)`, `isGoing(r)`. **Courses:** `getCourses()`, `getCourse(id)`, `getLecturers()`, `getLecturer(id)` |
| `PortalApiService.TOKEN_KEY` / `TOKEN_NPUB_KEY` | `portal_api_token` / `portal_api_token_npub` (SecureStore) |
| `PortalApiService.RSVP_CACHE_PREFIX` / `RSVP_CACHE_TS_PREFIX` / `RSVP_CACHE_TTL_MS` | `rsvp_cache_<id>` / `rsvp_cache_ts_<id>` (KeyValueStore) / 1 h |
| `PortalApiService.LOGIN_EVENT_KIND`, `RSVP_ATTENDING/MAYBE/NONE`, `looksLikeNpub(input)` | 22242; RSVP status values; leader dialog check (`npub1` + ≥ 60 chars; also check `Nip19`) |
| `data class PortalResult(ok, statusCode, error?, errorKind?, data?)` | `error` = the exact German Flutter text. Use `errorKind: PortalErrorKind` to show localized text |
| `enum PortalErrorKind` | `NOT_LOGGED_IN, NETWORK, SERVER_MESSAGE, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, VALIDATION, HTTP, NO_TOKEN, LOGIN_FAILED` |
| `data class PortalMeetup(id, name, slug, isLeader)`, `PortalMeetupEvent(id?, meetupId, start /* raw */, location?, description?, link?)` | `fromJson` with the tolerant Dart rules |
| `data class StatusBody(status, body)`, `Attendee(npub, name)` | |
| `object PortalTime` | `portalStartUtc(raw, zone)` (zone-less = UTC, Dart `DateTime.parse` grammar), `toPortalIso(instant / zoned)` (`YYYY-MM-DDTHH:mm:ss.SSSZ`). The editor prefills with `portalStartUtc` and sends `toPortalIso`, so a round trip keeps the time (fix for the Flutter bug) |
| `object PortalUrls` | `BASE_URL`, `API_BASE`, `MEETUPS_URL`, `LEADERS_URL`, `MEETUP_PAGE_PREFIX`, `DEVICE_NAME`, `TIMEOUT`, `LEADERS_TIMEOUT`, `meetupPageFor(slug)` (Dart `Uri.encodeComponent`), `absoluteImageUrl(url)` |
| `interface PortalLeaderService` | `ensureLoaded(force)`, `isLeaderOf(meetupId: Int, npub): Boolean?` (**`null` = unknown, `false` = not a leader; never collapse**), `meetupCountFor(npub)` (display only). Keys `portal_leaders_cache`, `portal_leaders_cache_ts`, TTL 24 h. There is deliberately no "all leaders" API |
| `interface PortalLogoRegistry` | Crests: `logos: StateFlow<Map<String,String>>`, `put(name, logo)`, `seedFrom(meetups)` (kept apart from calendar entries; consulted by `logoFor` only when no calendar entry matches; does not suppress `ensureLoaded`), `suspend ensureLoaded()`, `logoFor(titleOrCity)` (returns an absolute URL or `""`), `absoluteImageUrl(url)` |
| `fun interface PortalLogoLoader` | **Bound by `:data:calendar`** (optional here). Loads portal dates and calls `put` for every `meetup.logo` |

Fallback meetups (no numeric ID) have no leader check: `isLeaderOf` needs `meetup.id.toIntOrNull()`.

## Hooks for `:data:calendar`

`:data:calendar` depends on this module, so it binds the two optional hooks:

```kotlin
@Binds abstract fun meetupEvents(impl: CalendarMeetupEventsSource): MeetupEventsSource
@Binds abstract fun portalLogoLoader(impl: CalendarPortalLogoLoader): PortalLogoLoader
```

The calendar module calls `PortalLogoRegistry.put(name, logo)` while it parses portal dates. It
owns `portalStart` (public endpoint), `MeetupEventMatcher` and its tests
(`meetup_event_matcher_test`, the `MeetupDate`/matcher half of `meetup_portal_identity_test`, and
the matcher half of the `LIVE_PORTAL_TEST` variant, still to be ported there). The data-layer half
of the live test is `LivePortalTest` in this module (real portal: group 107 and every
"Einundzwanzig Südniedersachsen" event carry id 107 and their own portal link).

`PortalLogoRegistry` injects `Optional<Provider<PortalLogoLoader>>` and resolves the loader only
inside `ensureLoaded()`, so a loader that depends on `MeetupCalendarService` (which depends on the
registry) does not form a Hilt cycle, with or without its own `Lazy`.

## Deviations from Flutter

- **Fallback Hamburg spelling.** The bundled `m_hh` city is `"Hamburg"` (Flutter: `"Hambmurg"`,
  spec 09 §14 item 17). Offline-issued native badges get the slug `hamburg` and group with the
  online badges; old Flutter offline badges with slug `hambmurg` keep verifying but group
  separately. Wire-visible; to be recorded in `spec/00-contradictions.md` by the orchestrator.
- **Non-object directory entries** are skipped (Flutter lost the whole list and showed `[]`).
- **`tokenMatchesCurrentKey` with a portal `nostr` shorter than 12 chars** that differs from the
  current npub returns `false` (foreign account, token deleted by the caller). Flutter's
  `substring(0, 12)` threw a `RangeError` and landed in the offline branch (`stored == null`).
  Deliberate: safer against inherited logins. Pinned by a test; do not "fix" back.
- **"Denied forever" location permission** is re-validated with one request (Flutter returned
  `deniedForever` without asking, spec 09 §8.5/§10), so a reset in the system settings is noticed.
  If the permission really is permanently denied, Android answers instantly without a dialog, so
  the user sees no prompt, as in Flutter. Pinned by `LocationTest`; to be recorded in
  `spec/00-contradictions.md` by the orchestrator.
- **Leader check answers from cached data immediately** (spec 09 §14 item 24). The due network
  refresh (first call of the process, or data older than 24 h) runs in the application scope,
  one at a time. `isLeaderOf` waits for the network only when no data is in memory or in prefs.
  Results, the never-adopt-empty rule and `null` vs `false` are unchanged.
- **Nearby caches only real answers.** The bundled fallback list (directory empty or offline) and
  an empty date list are retried by the next `preload()`. Flutter kept them until the app restarted.
- **`logout()` runs non-cancellable**, so the local delete happens even if the caller is cancelled
  during the revoke.
- **`tokenMatchesCurrentKey` when `SigningService.npub()` throws** returns `true` (keep the token,
  no request). Flutter propagated the exception and the caller kept the token. Cancellation is
  rethrown.
- **Token setters** report a failed secure-store write as `false` (Flutter threw).
- **Case mapping** uses `DartString` from `:data:badges` (spec 00 item 12; dependency until it
  moves to `:core:common`), so `bySlug` equals `MeetupKey.slug` byte for byte.

## Tests

```
tools/gradlew-locked :data:meetups:testDebugUnitTest
```

110 JVM tests. They port `meetup_group_name_test` and the data-layer half of
`meetup_portal_identity_test`, and cover every new vector in spec 09 §10: haversine, firstNum,
image priority, portalLink/encodeComponent, dedupe, fallbacks, favorites resolution and migration,
the location state machine, nearby search, event dates, `errorFrom`, payload omission, `_write`,
login, every `tokenMatchesCurrentKey` row, the leader service, RSVP TTL, attendees,
`portalStartUtc` and the editor round trip, and Nominatim. HTTP goes through a MockWebServer that
receives every host, so the production URL constants are tested unchanged.
