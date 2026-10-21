# :data:calendar

Meetup dates (portal first, iCal fallback), NIP-52 calendar events, Nostr and portal RSVPs, the
event-calendar merge, "Meine Termine", Home favorite cards and the home-screen widget data.
Port of `lib/models/calendar_event.dart`, `calendar_event_service.dart`,
`meetup_calendar_service.dart`, `recurrence_expander.dart`, `meetup_event_matcher.dart`,
`event_rsvp_service.dart`, `widget_service.dart` (meetup/news/store part) and the non-UI logic of
`calendar_screen.dart`, `event_calendar_screen.dart`, `my_events_screen.dart`, `home_screen.dart`
(favorites, my events). Spec: `spec/10-calendar-events.md`, spec 14 §3 (widget), spec 12 §8.3–§8.5.
No UI.

```kotlin
implementation(project(":data:calendar"))   // exposes :core:common, :data:identity, :data:meetups as api
```

Package root: `space.einundzwanzig.meetup.data.calendar`. Services are Hilt `@Singleton`
interfaces; implementations are `internal`. Nothing here throws for network/relay/signing
problems (only cancellation).

## Services (inject)

| API | Purpose |
|---|---|
| `MeetupCalendarService` | `fetchPortalFirst()` (portal `/meetup-events`, now − 6 h, sorted, logos → `PortalLogoRegistry.put`; empty/error → iCal), `fetchIcs()` (`stream-calendar`, parsed + RRULE-expanded, no past filter). Companion: `CALENDAR_URL`, `PAST_HOURS`, pure `mapPortalEvents(raw, now, zone, onLogo)` |
| `CalendarEventService` | NIP-52: `fetchEvents(limit = 200, worldwide = false)` (community relay + app `client` tag, or all active relays), `fetchByAddress(address)` (newest `created_at` ≤ now + 600 s over `{community} ∪ active`, cancelled → `null`), `publishEvent(draft, existingDTag?)` → relay count, `updateIssuers(event, hexes)`, `cancelEvent(event)` (creator only, checked in the service) |
| `EventRsvpService` | kind 31925: `dTagFor(address)`, `setStatus(address, authorPubkey, status): RsvpError?` (`null` = ≥ 1 `OK true`), `loadMine(): Map<address, RsvpStatus>` |
| `RsvpError` | `SigningFailed(message, cause)` / `NotAccepted` → show `rsvpFailed(msg)` |
| `EventCalendarRepository` | `load(worldwide)`: Nostr + meetup dates + course dates (details in batches of 8), merged/deduped/sorted `List<CalendarItem>` |
| `MyEventsService` | `loadAcceptedEvents()` (accepted RSVPs → events from now − 1 day), `loadMeetupRsvps(dates?)` (favorites' portal dates ≤ 90 days that the user RSVP'd), `withdraw(event)` (`declined`), `loadFavoriteCards(dates?, updateWidget = true)` (Home carousel; writes the widget's next meetup, empty city without favorites) |
| `HomeWidgetStore` | `HomeWidgetPreferences` file (exact `home_widget` keys): `snapshot()`, `writeBitcoin(map)`, `updateMeetup(city, start?)`, `publishLatestNews(id, title)`, `markNewsSeen()`; key constants incl. native `meetupStartEpochMs`, app-pref keys `widget_*_news_*` |
| `WidgetLaunchTargetStore` | `e21_widget/pending_target`: `consume(): WidgetTarget?` (read + remove, closed set), `store(target)` (router side; ignores `home`/empty) |
| `HomeWidgetRenderer` (fun interface, **bind in the app**, optional) | `requestUpdate()`, called after every widget write → `MeetupWidget().updateAll(context)` |

Bound hooks of `:data:meetups`: `MeetupEventsSource` (nearby dates) and `PortalLogoLoader`
(crest loading), both via `fetchPortalFirst()` (`CalendarHooks`, `dagger.Lazy` to break the
registry ↔ loader cycle).

## Models — `…model`

| API | Purpose |
|---|---|
| `MeetupDate` | Portal/ICS date (`title, description, location, start: Instant, url, meetupId, meetupPortalLink, portalEventId?, logo, attendees?, mightAttendees?`), `toEventInfo()`, `at(newStart)`. Key per-date UI state by `portalEventId`, not equality |
| `NostrCalendarEvent` | NIP-52 event incl. badge fields (`badgeEnabled, badgeImageUrl, lat, lng, issuers, formerIssuers`), `startDate/endDate` for all-day events, `createdAt`; `isCancelled`, `address`, `hasNoLocation`, `day(zone)`, `lastDay(zone)`, `isIssuer(hex, atEpoch?)`, `isBadgeWindowOpen(now, zone)`; constants `KIND_TIME`, `KIND_DATE`, `CLIENT`, `STATUS_CANCELLED`, `DELETION_CONTENT` |
| `RsvpStatus` | `ACCEPTED/DECLINED/TENTATIVE` (`wire`, `fromWire`) |
| `CalendarItem`, `CalendarItemType`, `CalendarTypeFilter` | Unified calendar row (`fromNostr`, `fromMeetup`, `type`, `hasBadge`, `badgeImage`, `day(zone)`) |
| `CalendarEventDraft` | Publish input (`start/end: ZonedDateTime`, badge fields); `of(event, issuers, formerIssuers, zone)` for republishing |
| `MyMeetupDate`, `FavoriteMeetupCard` | My events / Home favorites rows |

## Pure helpers

| API | Purpose |
|---|---|
| `nostr.NostrCalendarCodec` | `parse(rawVerifiedEvent, zone)`, `buildTags(draft, dTag)` → `(kind, tags)` (exact Dart order, `g` via `toStringAsFixed(6)`), `cancelTags`, `deletionTags`, `parseAddress`, `newDTag()`, `unixSeconds`, `ymdPadded/ymdUnpadded`, `ROLE_ISSUER`, `ROLE_FORMER_ISSUER` |
| `nostr.RsvpCodec` | `KIND_RSVP`, `dTagFor`, `tags`, `parse`, `newestPerAddress` |
| `match.MeetupEventMatcher` | `portalIdentity(url)`, `resolve(date, meetups)`, `matchesCity(date, city)`, `matchesFavorite(date, key, meetupService)` |
| `time.PortalStart` | `portalStart(raw, zone)`: zone-less = UTC (organizer `portalStartUtc` lives in `:data:meetups` `PortalTime`) |
| `ics.IcsParser`, `IcsComponent`, `IcsDateTime`, `IcsStructured`, `IcsFormatException` | `icalendar_parser` 2.1.0 subset incl. quirks |
| `ics.IcsMeetupDates` | `fromComponent(c, now, zone)` (Dart `CalendarEvent.fromMap`), `fromIcs(text, now, zone)` |
| `recurrence.RecurrenceExpander` | `expand(base, rrule, now, zone)`, `HORIZON_DAYS = 366`, `MAX_OCCURRENCES = 200` |
| `merge.CalendarMerger` | `courseItems(details, now, zone)`, `dedupKey`, `merge`, `filter(items, type, query)`, `isFilterActive`, `byDay`, `upcoming`, `countsPerMonth`, `leadingBlanks` |
| `portal.PortalRsvp` | Tolerant RSVP map readers: `count`, `might`, `attendees`, `status`, `isGoing`, `initial(date)`, `merge`, `withStatus`, `toggleTarget`; `ATTENDING/MAYBE/NONE` |
| `format.CalendarFormat` | `hhmm`, `meetupWhen` (`DD.MM.YYYY · HH:MM`), `shareText`, `links` (details sheet), `myMeetupWhen`, `shortDate`, `editorDate`, `coordinates` (5 decimals), `daysUntil`, `proximity`, `unreadBadge` (`99+`), `shortNpub`, `initial` |
| `format.IssuerInput` | Editor rows `toHex`, `isRowInvalid`, `issuersOf`; edit sheet `addHelper(raw, creator, current): AddResult` (`Added/Invalid/Duplicate`), `initialHelpers` |
| `widget.HomeWidgetSnapshot` | Widget values with provider defaults; `priceText`, `updatedText`, `hasMeetup`, `meetupDaysFrom(today, zone)` |
| `widget.WidgetFormat` | `DASH`, `groupInt`, `daysUntil`, `countdown(days)` (`Today/Tomorrow/InDays`), `germanCountdown` |
| `widget.WidgetTarget` | `BITCOIN/MEETUP/NEWS` (`fromWire`) |
| `CalendarRelays` | `COMMUNITY`, `RSVP_BASE`, `readByAddress`, `publish`, `rsvp` relay sets |

## Wiring for other modules

- **App**: bind `HomeWidgetRenderer` (Glance `updateAll`); contribute a `BitcoinWidgetSink`
  (`:data:mempool`) that calls `homeWidgetStore.writeBitcoin(values.toMap())`; the
  `WidgetRouterActivity` calls `WidgetLaunchTargetStore.store(target)`, `MainActivity` calls
  `consume()` on resume.
- **News / Home**: after `fetchArticles(limit = 1)` call `publishLatestNews(id, title)`; opening
  News calls `markNewsSeen()`.
- **Home**: `MyEventsService.loadFavoriteCards()` (also updates the widget), `loadAcceptedEvents()`,
  `loadMeetupRsvps(dates)`; today's tile count from `fetchPortalFirst()` + `fetchEvents()`.
- **Badges / issuance**: `CalendarEventService.fetchByAddress`, `NostrCalendarEvent.isIssuer(signer, atEpoch)`,
  `isBadgeWindowOpen(now, zone)`, `badgeEnabled`, `lat/lng`, `address`.
- **Feature events**: screens use the services above plus `CalendarMerger`, `CalendarFormat`,
  `IssuerInput`, `PortalRsvp`, and `:data:meetups` `PortalApiService` for portal RSVP calls
  (`getRsvpCached`, `rsvp`, `getRsvpAttendees`, `tokenMatchesCurrentKey`, `loginWithNostr`).

## Storage

No calendar data is cached. Widget: `SharedPreferences("HomeWidgetPreferences")` (Flutter keys +
`meetupStartEpochMs`), `SharedPreferences("e21_widget")/pending_target`; app prefs
`widget_last_seen_news_id`, `widget_latest_news_id`, `widget_latest_news_title`. Portal RSVP cache
and token belong to `:data:meetups`.

## Tests

`tools/gradlew-locked :data:calendar:testDebugUnitTest` — 75 tests (JVM; one Robolectric class).
Ported: `meetup_event_matcher_test` (all cases), the calendar half of
`meetup_portal_identity_test`, the calendar part of `relay_event_signature_test`; the
`event_details_sheet_test` content logic (when line, share text, links). Spec 10 §10 vectors:
publish (time/all-day/badge off), `g` `toStringAsFixed(6)` vectors, cancel + deletion, issuers,
`isIssuer`, `fromEvent`, address parse, RSVP d-tag/tags, `portalStart`, ICS DTSTART, recurrence,
dedup key, widget `groupInt`/countdown; fixture `home_widget_prefs` (rendered + empty). Service
tests with `FakeRelayConnector` + a signing proxy (publish relays, `updateIssuers`, creator check,
cancel, newest-wins/cancel/future guard, community filter, RSVP OK handling, `loadMine`).

## Deviations from the Dart code

- `fetchEvents` keeps the newest `created_at` per address including cancelled versions, then
  drops cancelled ones (Dart: last seen wins, cancelled dropped first — could resurrect a
  cancelled event; spec 10 §14 item 6). Worldwide relays are queried in parallel (§14 item 23).
- `loadMine` ignores RSVPs with `created_at` > now + 600 s (§14 item 10).
- Recurrence and the badge window use local dates/wall-clock times, so they do not drift an hour
  across DST (§8.3, §8.8).
- Course `to` is parsed with `portalStart` (zone-less = UTC) instead of local (§8.6).
- The helper edit sheet requires a 64-char input to be hex (§2.3 "SHOULD").
- `MyEventsService.loadFavoriteCards` writes an empty widget city when there are no favorites
  (§14 item 18). The widget gets `meetupStartEpochMs` so the countdown can be computed at render
  time (spec 14 §3.2); the legacy `meetupCountdown` German string is still written.
- Calendar publish keeps the Flutter semantics (connect + send + 2 s linger counts as accepted,
  no `OK` wait), as listed in `:core:nostr`'s mapping table.
- The widget store lives here instead of a separate `:data:widget` (spec 14 §3.2); the Bitcoin
  sink is a thin app-side adapter so this module does not depend on `:data:mempool`.
