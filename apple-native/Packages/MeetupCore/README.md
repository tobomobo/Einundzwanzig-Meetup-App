# MeetupCore

Dependency-free Swift 6 package for the native Apple prototype. It uses Foundation,
FoundationNetworking on Linux, and FoundationXML where available. Import `MeetupCore`.
The package builds on Linux and declares iOS 17 / macOS 14 minimum deployment targets.

Public API:

- `Meetup`, `MeetupEvent`, `NewsArticle`: `Codable`, `Hashable`, `Sendable`, `Identifiable`,
  with public properties and initializers. `BitcoinSnapshot` is also Codable/Hashable/Sendable.
- `PortalClient()` actor: `try await meetups()`, `try await events(now: Date())`.
- `NewsClient()` actor: `try await articles()`.
- `MempoolClient()` actor: `try await snapshot(baseURL: URL)`; default is `https://mempool.space`.
- `SafeURL.web(_:)`, `SafeURL.relay(_:)`.
- `BitcoinConverter.sats(fromBTC:)`, `.btc(fromSats:)`, `.fiat(sats:price:)`.
- `SecurityCapabilities.signingAvailable`, `.verificationAvailable`, `.reason`.

Directory requests use `/api/mobile/meetups`. Events request today plus the first day
of each of the next two months, using a Gregorian UTC calendar, and sort by start then ID.
Only equal IDs are deduplicated. The last fetched event with a given ID wins; the first
directory entry with a given ID wins. No time-based event filtering is applied; consumers
can choose their display window. ISO timestamps take precedence over database timestamps;
naive dates are UTC. Relative portal images and portal links resolve against the portal host.
Optional unknown values stay nil/empty rather than creating fake locations or dates.
Malformed JSON, missing IDs and missing/invalid event starts throw.

The RSS parser handles CDATA, creator/author fields, image enclosures and content images,
common HTML entities, and plain text summaries with bounded linear scans. Escaped literal
comparisons are preserved. It disables external entity resolution and rejects DTD/entity
declarations outside comments/CDATA, plus excessive nesting. Items lacking a safe web URL are excluded.
Publication dates support RFC 822 years and common named zones and preserve explicit offsets;
invalid dates stay nil rather than becoming
the current time. These are deliberate improvements over the Dart fallback behavior.

All network operations use GET, ephemeral URLSession instances, no cookie/credential
storage or cache, 15-second request/resource timeouts, and streamed body limits (4 MiB for
portal/RSS, 256 KiB per mempool response). Errors propagate; partial calendars and partial
Bitcoin snapshots are not returned as complete results. A snapshot requires a positive
height and USD/EUR prices plus nonnegative integer fee rates. Custom mempool base paths
are supported; a trailing `/api` is accepted. There is no hidden fallback host.

Web links require absolute HTTP(S) URLs with a host and no credentials. Relays require WSS
and reject credentials/fragments. BTC input is exact signed decimal text with a period and
at most eight fractional digits. No floating point rounding, exponent notation or locale
grouping is accepted; Int64 extrema round-trip. BTC output omits insignificant zeros.
`fiat` uses ordinary Double arithmetic for display and does not validate a supplied price.

There is no cryptographic implementation, signing, verification, badge trust, secret-key
handling, demo fallback, persistence or platform UI in this package. Consumers must never
promote imported metadata to a verified badge or proof of identity.

Run `swift test`. On the supplied Linux toolchain:

```sh
export PATH=/tmp/einundzwanzig-swift-toolchain/usr/bin:$PATH
export LD_LIBRARY_PATH=/tmp/einundzwanzig-swift-libs:/tmp/einundzwanzig-swift-libs/usr/lib/x86_64-linux-gnu
export CLANG_MODULE_CACHE_PATH=/tmp/einundzwanzig-clang-cache
export SWIFTPM_MODULECACHE_OVERRIDE=/tmp/einundzwanzig-module-cache
swift test --package-path apple-native/Packages/MeetupCore \
  --scratch-path /tmp/einundzwanzig-core-build \
  --cache-path /tmp/einundzwanzig-swiftpm-cache --disable-sandbox
```

Tests use small synthetic payloads and URLProtocol stubs; they do not contact live services
or ship full portal/news snapshots.
