# MeetupCore and MeetupNostr

Swift tools **6.1+**, Swift language mode 6, iOS 17 / macOS 14 package minimums.
The app itself requires iOS/macOS 26. Both products compile on Linux with Swift 6.2.

`MeetupCore` is dependency-free Foundation logic: public models, bounded read-only
portal/RSS/mempool clients, calendar fallback/recurrence, courses/lecturers,
conservative meetup ownership matching, nearby filters, NIP-19 public identity
validation and exact sats/BTC conversion. Linux uses FoundationNetworking and
FoundationXML. No platform UI, private keys or trusted badge state are stored here.

`MeetupNostr` is a separate product using source dependency
`21-DOT-DEV/swift-secp256k1` **0.23.2**, commit
`e70a10e036a55fffea31568f0af92d69b6d449cd`. It supplies an actor adapter for BIP-340
public-key derivation/signing/verification, canonical NIP-01 event IDs and immutable
event models, and separate exact-UTF-8 filter/time policies. Use
`NostrEvent.decodeJSON(_:)` / `UnsignedNostrEvent.decodeJSON(_:)` for untrusted wire
bytes. Generic Codable cannot inspect original numeric tokens. This module does
not enable app signing, establish key ownership, or confer registry/badge authority.
`SecurityCapabilities` remains false. See [provider and plugin trust](Docs/CRYPTO_PROVIDER.md)
before building; the pinned dependency executes a source-preparation build plugin.
No wrapper audit or perfect memory erasure is claimed.

## Public data APIs

- `PortalClient().meetups()` and `.events(now:)` use `/api/mobile/meetups` and today
  plus the first of the next two months. ISO fields take precedence; naive meetup
  dates are UTC. Directory IDs alone deduplicate groups. `events` returns the full
  aggregate or throws, without display filtering. Group coordinates never substitute
  for venue coordinates.
- `CalendarClient().snapshot(now:timeZone:)` uses a complete portal aggregate first,
  then `/stream-calendar` on any failed month or no usable portal dates. Snapshots
  include source, actual successful fetch time and structured warnings. Timed events
  have a six-hour lookback; DATE values remain through their exclusive civil end.
  `CalendarDisplayWindow` also preserves source civil dates for date filters.
- ICS supports bounded WEEKLY/MONTHLY recurrence, interval/BYDAY ordinals/COUNT/UNTIL,
  EXDATE, known TZIDs, DATE/all-day and explicit ends. Civil Calendar arithmetic
  preserves wall time through DST and handles days beginning at 01:00. Unknown rules
  retain the base plus warnings; RDATE/EXRULE/RECURRENCE-ID/custom zones are unsupported.
  The oldest-first 200-occurrence cap, 366-day horizon and 4,096-period budget are
  explicit limits. Very old series may yield no future occurrences with a warning.
- `CoursesClient().courses()/lecturers()` fetch the exact `?withDetails=1` list
  endpoints; summary `next_event` is not a full appointment. `courseDetail(id:)` and
  `lecturerDetail(id:)` return a value, `.fresh/.cache/.unavailable` source and failure.
  ID-bound list/nested/detail fallback never looks fresh after a failed detail fetch.
  Explicit course date zones are preserved; floating course dates use the client's
  captured local zone, matching Flutter's separate course policy.
- `MeetupEventMatcher`, `NearbySearch`, `GeoPoint` keep explicit IDs authoritative
  and never arbitrarily choose between distinct same-city groups. Radius filtering
  uses group positions and excludes groups without a usable position.
- `PublicIdentity` validates classic Bech32 npub checksum/case/HRP/padding and exact
  32-byte public references; canonical `.npub/.hex` are format validation, not proof
  of curve membership or ownership.
- `NewsClient().articles()` parses bounded RSS, disables external entities, rejects
  DTDs/excessive nesting, sanitizes text, and leaves invalid publication dates nil.
- `MempoolClient().snapshot(baseURL:)` requires all three height/price/fee responses
  to succeed. `BitcoinConverter` uses exact signed decimal text for sats/BTC.
- `SafeURL.web/relay` reject unsafe schemes, credentials and missing hosts.

HTTP uses GET with ephemeral URLSession, no cookie/credential/cache storage,
15-second deadlines, bounded streaming responses and no HTTPS-to-HTTP redirects.
Portal/RSS/calendar bodies are limited to 4 MiB; each mempool response to 256 KiB.
Errors and cancellation propagate; partial aggregates do not become complete success.
The app owns protected public cache persistence and labels offline preview fixtures.

## Tests

From the repository root, with Swift 6.1+ installed:

```sh
swift test --package-path apple-native/Packages/MeetupCore \
  --force-resolved-versions -Xswiftc -warnings-as-errors
swift test -c release --package-path apple-native/Packages/MeetupCore \
  --force-resolved-versions --filter MeetupNostrTests
```

The package lock is checked in; the app has its own workspace lock. Keep both pins
and use resolved-only mode. The project validator checks the expected URL/version/
revision. See the app [build commands](../../README.md) and
[executed checks / Apple acceptance limits](../../Docs/VALIDATION.md).
Tests use synthetic payloads, transport stubs and published crypto fixtures without
network access. An optional `MEETUP_LIVE_ICS_FIXTURE` path enables a captured-response
smoke test; no full live feed is checked in. Linux checks do not establish Apple SDK
compilation or runtime behavior.
