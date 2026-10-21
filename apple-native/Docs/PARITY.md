# Apple prototype: route and protocol coverage

Audit basis: Flutter `lib/` and the Android specifications/navigation contract inspected at source commit `0d00c83`. The **57 destination keys** are preserved in [SOURCE_ROUTES.md](SOURCE_ROUTES.md), so Apple validation does not require the separate Kotlin branch. Source behavior wins over stale spec comments. This is a substantial initial prototype, not feature parity or a security implementation.

**Implemented** means the named ordinary user flow exists in this prototype. **Partial** means only the stated subset exists. **Missing** means no working native equivalent; an explanation or external website link does not count as implementation. Native routes use identifiers and show a not-found view when data disappears. The table maps product behaviors rather than claiming every Kotlin navigation argument is reproduced.

| Route | Status | Apple behavior / remaining work |
| --- | --- | --- |
| `Splash` | Partial | Synchronous local state/profile loading and native system launch screen; no Flutter migration or artificial timed splash. |
| `Intro` | Partial | Native welcome, language choice and explicit preview mode; encrypted backup restore missing. |
| `IdentitySetup` | Partial | Local nickname plus checksum-validated NIP-19 npub, nostr: URI or 64-hex public reference, saved as canonical npub. No curve membership/ownership claim, keys, external signer or passkey. |
| `HomeTab` | Partial | Native dashboard, chronological favorite cards, edit favorites, configurable tile order/visibility and navigation. No widgets, tours, chat unread counts, Nostr sessions, live duel status or audio mini-player. |
| `WalletTab` | Partial | Searchable unverified preview collection, grouping, metadata details and deletion. No trusted credentials or production wallet import. |
| `EventsTab` | Partial | Portal meetup dates, text/day/favorite filters and local plans. No NIP-52 event or course aggregation. |
| `BadgeScanner` | Partial | Manual/file/camera input extracts bounded allowlisted display metadata only. No acceptance, freshness check, Schnorr verification, registry lookup or collector claim. |
| `BadgeQrCamera` | Partial | VisionKit camera on supported iOS devices plus selected-photo QR decoding via Vision on iOS/Mac. Camera unavailable on Mac/simulator; native SDK/device behavior remains untested. |
| `RollingQr` | Missing | No organizer sessions, session seed, rotating signed payload or QR issuance. |
| `NfcTagWriter` | Missing | Not implemented; NFC is also disabled in current Flutter source. |
| `MeetupSessionWizard` | Missing | No NFC/session wizard. |
| `BadgeWallet` | Partial | Same preview-only wallet as Badges tab. No encrypted production badge store, archive merge, credential sharing or deduplication parity. |
| `BadgeCollection` | Partial | Opens a named collection, filters/searches its unverified previews and drills into metadata. No trust statistics/artwork parity. |
| `BadgeDetails` | Partial | Name, import time, optional claimed block height, explicit unverified/demo status and local deletion. No verification, claim or shareable signed card. |
| `BadgeWorldMap` | Missing | Explicit empty/unavailable state. Unverified coordinates are never displayed as visited places. |
| `ReputationQr` | Missing | Explains unavailable signing/verification; never emits an unsigned QR pretending to be reputation. |
| `ReputationCard` | Partial | Honest unavailable trust display with zero trusted badges and no computed score. No score/card export or production algorithm. |
| `ReputationScanner` | Missing | No reputation protocol parsing/verification. Badge scanner cannot certify reputation. |
| `ReputationScanResult` | Missing | No verified reputation result. |
| `ReputationVerify` | Missing | No verification string or proof-chain verification. |
| `VerifyPerson` | Missing | No identity/presence verification; public npub text is not an ownership claim. |
| `VerifyPersonQrCamera` | Missing | QR camera exists only for raw badge preview input, not verified person lookup. |
| `TrustNetworkHub` | Missing | Explanation of missing social graph; no data or trust paths. |
| `MyTrustNetwork` | Missing | Co-attendance publishing/query, encounter graph and trust paths absent. |
| `AdminPanel` | Missing | Organizer explanation and official portal link only; no authority asserted. |
| `PlatformProofs` | Missing | No NIP-05, platform proof creation or verification. |
| `HumanityProof` | Missing | No Lightning challenge, payments, zap receipt verification or humanity proof. |
| `ProfileEdit` | Partial | Persisted public nickname/reference with NIP-19 checksum/hex validation on save. No ownership proof, avatars, key management or signer switching. |
| `RelaySettings` | Partial | Persist/add/remove/reset validated WSS relay URLs. Saved configuration only; no relay connection, ping, NIP-65 discovery or subscription. |
| `NearbyMeetups` | Partial | Native foreground one-shot location with denial/no-fix states; MapKit planned-place search; radius/day/range/favorite filters, straight-line group distances and map selection. No background tracking or presence verification. Native runtime acceptance outstanding. |
| `MeetupDetails` | Partial | Native detail, favorite toggle, matching portal dates, MapKit location, Apple Maps, safe portal/Telegram links and share. Public slim endpoint may omit description/social fields. No chat/leader management. |
| `MeetupSelection` | Implemented | Search and toggle multiple favorites by exact portal ID; persists across launches and retains distinct groups in the same city. |
| `LocationPicker` | Missing | No editor or location selection result flow. |
| `PortalMeetups` | Missing | No portal token/login, managed meetup list or leader permissions. Public directory is separate. |
| `PortalMeetupEvents` | Missing | No organizer event management. Public event browsing does not imply portal login. |
| `PortalEventEditor` | Missing | No portal create/edit requests. |
| `CommunityPortal` | Partial | Curated verified source URLs open in the default browser; not the full in-app portal catalog. |
| `MeetupCalendar` | Partial | Complete portal aggregate first, ICS fallback with bounded WEEKLY/MONTHLY recurrence, time zones/DST/EXDATE and explicit source/fetch/error/limitation provenance. Conservative directory ownership associates fallback dates; all-day dates preserved. No portal RSVP/widget. |
| `EventCalendar` | Partial | Native day filters public portal/ICS meetup dates. Courses are available through their own native course screens; NIP-52 discovery/event badges still absent. |
| `EventEditor` | Missing | No signed NIP-52 event publishing or organizer helpers. |
| `MyEvents` | Partial | Persisted private Going/Maybe plans and notes; demo isolated. Exact canonical nonrecurring portal ICS UIDs retain portal event IDs; other occurrences use deterministic source IDs. No portal/Nostr RSVP or chats. |
| `MeetupChat` | Missing | Clear unavailable state. NIP-29 relay authentication/membership and messaging absent. |
| `EventChat` | Missing | Clear unavailable state. NIP-22 comments absent. |
| `News` | Partial | Real RSS, dates/images/sanitized summary, search, local read marks, refresh and cached content. No Nostr article fetch, reactions, zaps or push. |
| `NewsArticleDetails` | Partial | Native RSS excerpt reader, original article link and native share. Full article content remains on the publisher website. |
| `CommunityHub` | Partial | Native hub to news, directory, calendar, resources, reputation explanation and source community websites. No live duel status, authenticated auto-login or audio player. |
| `PortalArea` | Partial | Portal/community navigation, public native course/lecturer screens and external official website links. Authenticated organizer data unavailable. |
| `Courses` | Implemented | Public course/lecturer tabs, search, refresh, persisted summaries and ID navigation. Exact withDetails list contracts; failed requests never appear as fresh success. |
| `CourseDetails` | Implemented | Public detail fetch, immediate cached summary/full-detail fallback by ID, failure/source/fetch labels, course dates, native calendar review/export, join/portal links and related lecturer navigation. |
| `LecturerDetails` | Implemented | Public biography, source/cache/failure labels, related course navigation, safe website/social links and validated public Nostr reference links. Persisted list relationships also resolve offline. |
| `ValueForValue` | Missing | External V4V information link only. No LNURL-pay invoice, amount picker, wallet payment or zap verification. |
| `PlebrapLibrary` | Missing | External PlebRap link only. No native playlist, audio cache, playback or mini-player. |
| `Glossary` | Partial | Searchable native disclosures use selected source DE/EN/ES glossary entries, with prototype limitation notice. Not the entire source glossary or guided tour. |
| `BitcoinDashboard` | Partial | Live height, EUR/USD prices, recommended fees, source/timestamp, retry, visible-only 60-second refresh and configurable HTTPS instance. No hashrate/difficulty/mempool stats, Tor, partial six-source status or widget. |
| `Converter` | Partial | Exact Int64 sats↔BTC conversion, validation and optional current EUR/USD estimates with timestamp. No mBTC/μBTC/other fiat or reverse-fiat input. |
| `MempoolSettings` | Partial | Persisted validated HTTPS base URL, default reset and stale-request invalidation. No Tor/proxy/onion transport or cleartext instance support. |
| `DiagnosticLog` | Partial | In-memory endpoint outcome/counts only; no tokens, profiles or QR payload logging. No file logs, export or NIP-44 probe. |

## Signing and security workflows

| Workflow | Status | Boundary / next implementation |
| --- | --- | --- |
| Local profile and preferences | Implemented | Public-only nickname/npub reference; iOS device-only Keychain for profile, Mac public file with mode 0600, atomic nonsecret files. Errors are surfaced, corrupt storage is not silently overwritten. |
| Public Nostr identity | Partial | Classic Bech32 checksum, HRP, mixed case, exact 32-byte length/padding and 64-hex normalization are implemented and tested. This does not establish curve membership, ownership or a relay profile. Legacy saved references are not automatically rewritten. |
| Private keys and local signing | Missing | App UI still never accepts/generates/imports/exports/stores private keys. Separate MeetupNostr actor now implements length-checked secp256k1/BIP-340 primitives and canonical NIP-01 IDs/signatures with official vectors. Product signing remains disabled until key lifecycle and authorization/claim binding exist; no wrapper audit is claimed. |
| NIP-07 / NIP-55 / NIP-46 | Missing | Browser/Android signer paths are not emulated. A reviewed Apple-compatible remote signer requires encryption, pairing, request authentication, cancellation and response verification. |
| NIP-44 / NIP-49 / encrypted backups | Missing | No replacement encryption or made-up backup format. No Flutter credential migration, restoration or secret export. |
| Passkey PRF / local authentication | Missing | No credential wrapping, private-key reveal or associated-domain entitlement. |
| Rolling QR issue / scan | Partial | Camera obtains raw text; metadata preview is not a protocol validation. No session/freshness/signature/expiry/authority/presence acceptance. |
| Organizer signature / collector claim | Missing | General NIP-01/BIP-340 verification exists in the isolated foundation module, but no badge kind-21000 validation or kind-21002 claim workflow is enabled. Boolean fields/signature-shaped text never confer trust. |
| Organizer registry / vouch / distrust | Missing | No seed admin, bootstrap promotion, authority lookup or Nostr publish. |
| Trust score / proofs / signed reputation | Missing | Unverified and demo previews contribute zero trusted badges. Score is unavailable, not a fabricated zero or production trust calculation. No signed QR, kind-30078 publish or proof-hash verification. |
| Portal authentication / RSVP | Missing | No challenge signing/token or mutations. |
| Nostr calendar RSVP | Missing | Going/Maybe are local plans only; no kind-31925 event is created. |
| Payments / zaps / humanity / platform proofs | Missing | No payment or verification success is simulated. |
| Transport and remote URLs | Implemented | Read-only ephemeral URLSession, 15-second resource/request deadlines, bounded bodies, cancellation, HTTP failures, safe web URL scheme+host checks and no HTTPS→HTTP redirects. ATS remains enabled. |
| Accessibility | Partial | System text styles, VoiceOver labels, semantic native controls, adaptable control rows, glass transparency fallback, no custom motion. Device Dynamic Type/VoiceOver/Reduce Transparency testing outstanding. |

## Milestone two: foundations and remaining work

The app links the pinned **MeetupNostr** package product so all Apple app builds also compile the provider path. The module provides actual canonical NIP-01 hashing, Schnorr sign/verify primitives and byte-exact local read policies. It does not provide a relay subscription, app key lifecycle, trusted badge verification or organization authority. `SecurityCapabilities` intentionally remains false. [Provider/plugin review](../Packages/MeetupCore/Docs/CRYPTO_PROVIDER.md) records the exact release/commit and limits; it is not an audit assurance.

Recurrence deliberately corrects the source's fixed-duration DST drift, COUNT=1, truncated UNTIL and invalid DATE handling. DATE uses each civil day's actual start (including Santiago's 01:00 start), rejects entirely skipped civil dates (Apia 2011-12-30), retains source time zone and exports VALUE=DATE. Timed events use a six-hour lookback; all-day dates remain visible through their exclusive civil end and date filters compare their source civil date. Unknown/malformed recurrence keeps only the base plus an explicit limitation; unsupported RDATE/EXRULE/RECURRENCE-ID/custom zones and expansion caps never imply a complete calendar. Expansion retains the source's oldest-first 200-occurrence cap and 4,096-period budget, so very old series can have no future results with a limitation warning. The live feed currently contains no RRULEs; synthetic fixtures test recurrence.

Course floating dates retain Flutter's local-date policy, distinct from naive UTC meetup API timestamps. Calendar/learning caches retain original source timestamps and failed refresh states. Reset replaces the course client actor so old in-flight requests cannot hydrate the new cache. Coordinates for nearby search describe groups; only actual event/venue GEO fields appear on event maps.

Next: run Apple builds and device acceptance; then wire verified NIP-52 relay discovery with exact filter/time/signature checks. App signing needs reviewed key lifecycle, remote signer integration, portal auth and claim/registry binding before real RSVP, badges or organizer tools. Full trust algorithms, chats, encrypted backup, platform/payment proofs, playback and widgets remain ahead.
