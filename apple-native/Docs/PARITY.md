# Apple prototype: route and protocol coverage

Audit basis: current local commit `0d00c83`, Flutter `lib/`, `android-native/spec/00` through `15`, and the **57 destination keys** in `android-native/core/navigation/ROUTES.md`. Source behavior wins over stale spec comments. This is a substantial initial prototype, not feature parity or a security implementation.

**Implemented** means the named ordinary user flow exists in this prototype. **Partial** means only the stated subset exists. **Missing** means no working native equivalent; an explanation or external website link does not count as implementation. Native routes use identifiers and show a not-found view when data disappears. The table maps product behaviors rather than claiming every Kotlin navigation argument is reproduced.

| Route | Status | Apple behavior / remaining work |
| --- | --- | --- |
| `Splash` | Partial | Synchronous local state/profile loading and native system launch screen; no Flutter migration or artificial timed splash. |
| `Intro` | Partial | Native welcome, language choice and explicit preview mode; encrypted backup restore missing. |
| `IdentitySetup` | Partial | Local nickname and optional user-entered public npub reference. No key creation, ownership proof, Bech32 checksum verification, external signer, passkey or encrypted key export. |
| `HomeTab` | Partial | Native dashboard, chronological favorite cards, edit favorites, configurable tile order/visibility and navigation. No widgets, tours, chat unread counts, Nostr sessions, live duel status or audio mini-player. |
| `WalletTab` | Partial | Searchable unverified preview collection, grouping, metadata details and deletion. No trusted credentials or production wallet import. |
| `EventsTab` | Partial | Portal meetup dates, text/day/favorite filters and local plans. No NIP-52 event or course aggregation. |
| `BadgeScanner` | Partial | Manual/file/camera input extracts bounded allowlisted display metadata only. No acceptance, freshness check, Schnorr verification, registry lookup or collector claim. |
| `BadgeQrCamera` | Partial | Native VisionKit QR scanning on supported iOS devices with availability checks and manual fallback. Simulator/Mac camera unavailable; gallery-image QR decoding missing. |
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
| `ProfileEdit` | Partial | Saves nickname/public reference; iOS Keychain and public-only Mac file. No avatars, key management or signer switching. |
| `RelaySettings` | Partial | Persist/add/remove/reset validated WSS relay URLs. Saved configuration only; no relay connection, ping, NIP-65 discovery or subscription. |
| `NearbyMeetups` | Partial | Real portal directory, city/group search, favorite filter, MapKit markers and selection. GPS/radius/geocoding/date-radius search missing. |
| `MeetupDetails` | Partial | Native detail, favorite toggle, matching portal dates, MapKit location, Apple Maps, safe portal/Telegram links and share. Public slim endpoint may omit description/social fields. No chat/leader management. |
| `MeetupSelection` | Implemented | Search and toggle multiple favorites by exact portal ID; persists across launches and retains distinct groups in the same city. |
| `LocationPicker` | Missing | No editor or location selection result flow. |
| `PortalMeetups` | Missing | No portal token/login, managed meetup list or leader permissions. Public directory is separate. |
| `PortalMeetupEvents` | Missing | No organizer event management. Public event browsing does not imply portal login. |
| `PortalEventEditor` | Missing | No portal create/edit requests. |
| `CommunityPortal` | Partial | Curated verified source URLs open in the default browser; not the full in-app portal catalog. |
| `MeetupCalendar` | Partial | Public API today + first of next two months, UTC timestamp parsing, chronological list, search, exact meetup/favorite/day filters, detail, sharing and calendar export. No iCal/RRULE fallback, portal RSVP or widget integration. |
| `EventCalendar` | Partial | Native date selection filters public portal meetup dates only. No relay NIP-52 events, event badges or courses. |
| `EventEditor` | Missing | No signed NIP-52 event publishing or organizer helpers. |
| `MyEvents` | Partial | Persisted private local Going/Maybe plans, notes, detail and removal. Demo plans are separate. Does not publish portal or Nostr RSVP; no event chats. |
| `MeetupChat` | Missing | Clear unavailable state. NIP-29 relay authentication/membership and messaging absent. |
| `EventChat` | Missing | Clear unavailable state. NIP-22 comments absent. |
| `News` | Partial | Real RSS, dates/images/sanitized summary, search, local read marks, refresh and cached content. No Nostr article fetch, reactions, zaps or push. |
| `NewsArticleDetails` | Partial | Native RSS excerpt reader, original article link and native share. Full article content remains on the publisher website. |
| `CommunityHub` | Partial | Native hub to news, directory, calendar, resources, reputation explanation and source community websites. No live duel status, authenticated auto-login or audio player. |
| `PortalArea` | Partial | Portal/community navigation and external official website links. Courses and organizer data unavailable. |
| `Courses` | Missing | Official portal link only, no native course/lecturer API adapter. |
| `CourseDetails` | Missing | No native course details or course dates. |
| `LecturerDetails` | Missing | No native lecturer details. |
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
| Public Nostr identity | Partial | Input format/length check only; no Bech32 checksum, relay profile or key ownership proof. No generated npub or fictitious keypair. |
| Private keys and local signing | Missing | App never accepts, generates, imports, exports or stores nsec/private keys. Needs audited secp256k1/BIP-340 implementation and official vectors. Apple's CryptoKit signing curves do not substitute for secp256k1. |
| NIP-07 / NIP-55 / NIP-46 | Missing | Browser/Android signer paths are not emulated. A reviewed Apple-compatible remote signer requires encryption, pairing, request authentication, cancellation and response verification. |
| NIP-44 / NIP-49 / encrypted backups | Missing | No replacement encryption or made-up backup format. No Flutter credential migration, restoration or secret export. |
| Passkey PRF / local authentication | Missing | No credential wrapping, private-key reveal or associated-domain entitlement. |
| Rolling QR issue / scan | Partial | Camera obtains raw text; metadata preview is not a protocol validation. No session/freshness/signature/expiry/authority/presence acceptance. |
| Organizer signature / collector claim | Missing | No kind-21000 verification or kind-21002 signing. Boolean fields and signature-shaped text never confer trust. |
| Organizer registry / vouch / distrust | Missing | No seed admin, bootstrap promotion, authority lookup or Nostr publish. |
| Trust score / proofs / signed reputation | Missing | Unverified and demo previews contribute zero trusted badges. Score is unavailable, not a fabricated zero or production trust calculation. No signed QR, kind-30078 publish or proof-hash verification. |
| Portal authentication / RSVP | Missing | No challenge signing/token or mutations. |
| Nostr calendar RSVP | Missing | Going/Maybe are local plans only; no kind-31925 event is created. |
| Payments / zaps / humanity / platform proofs | Missing | No payment or verification success is simulated. |
| Transport and remote URLs | Implemented | Read-only ephemeral URLSession, 15-second resource/request deadlines, bounded bodies, cancellation, HTTP failures, safe web URL scheme+host checks and no HTTPS→HTTP redirects. ATS remains enabled. |
| Accessibility | Partial | System text styles, VoiceOver labels, semantic native controls, adaptable control rows, glass transparency fallback, no custom motion. Device Dynamic Type/VoiceOver/Reduce Transparency testing outstanding. |

## Next parity milestones

1. Run the checked-in three Apple SDK builds and the manual acceptance checklist on actual iPhone/Mac; fix platform/compiler issues before any release claim.
2. Add iCal recurrence fallback and course adapters, then NIP-52 read-only event discovery with verified event parsing.
3. Select and audit a secp256k1/BIP-340 provider; port the source protocol/security vectors and defensive invariants before exposing signer setup or trusted badge import.
4. Implement Apple remote signer integration and portal authentication; only then enable claims, real RSVP, organizer tools, chat, encrypted backup and proof flows.
5. Recreate trust algorithms, registry, QR and proof-chain verification with the source golden fixtures. Expand native media, community, widgets and remaining resource screens afterward.
