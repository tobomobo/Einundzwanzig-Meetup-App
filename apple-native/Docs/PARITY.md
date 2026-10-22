# Apple native implementation coverage

Updated 2026-10-22 against the Flutter `lib/` behavior and the independent native-port findings. The exact 57-key source contract remains in [SOURCE_ROUTES.md](SOURCE_ROUTES.md). “Implemented” below describes connected source workflows, not Apple SDK or physical-device validation. Explanatory screens and crypto primitives alone do not count as a feature.

The app now connects signing, badge/claim/authority verification, encrypted recovery, portal/calendar/chat operations and public media/data flows. [VALIDATION.md](VALIDATION.md) separates portable checks, executed Mac package/store/Network tests and five successful unsigned Apple SDK builds from pending simulator UI and signed-device acceptance.

| Route | Status | Apple behavior / remaining work |
| --- | --- | --- |
| `Splash` | Implemented | Loads native state and detects existing Flutter secrets/preferences/wallet before permitting identity creation. |
| `Intro` | Implemented | Nickname/language onboarding, isolated preview mode and signing/recovery navigation. |
| `IdentitySetup` | Partial | Local random/imported keys, locked NIP-49 vault, NIP-46 bunker pairing/authentication, exports, password changes, encrypted backup and reviewed migration/recovery. Passkeys remain gated. |
| `HomeTab` | Implemented | Native dashboard, favorites, local plans, configurable tiles and navigation; public background refresh and Bitcoin widget are separate targets. |
| `WalletTab` | Implemented | Actual encrypted full-proof wallet, grouping/search, source/collector verification, synchronization and trust navigation. |
| `EventsTab` | Implemented | Aggregates portal/ICS meetup dates, signed NIP-52 entries and course appointments with filters and local plans. |
| `BadgeScanner` | Implemented | Bounded manual/file/photo/camera import validates source, freshness and expiry, resolves issuer authority and signs the collector claim. Unknown issuers stay outside trusted reputation. |
| `BadgeQrCamera` | Implemented | VisionKit device camera and Vision selected-photo QR decoding; manual/file alternatives on simulator/Mac. Device permission/runtime acceptance pending. |
| `RollingQr` | Implemented | Organizer session seed and changing QR payloads, expiry, static payload sharing and explicit session end. |
| `NfcTagWriter` | Partial | Disabled to match the current Flutter baseline; static payload export is available. |
| `MeetupSessionWizard` | Implemented | Native meetup/event picker, portal/registry/progression authority, current-location check, Bitcoin height and actual signed session issuance. |
| `BadgeWallet` | Implemented | NIP-44 encrypted full source/claim wallet, lossless backup, identity-scoped merge, unreadable-data protection and complete-generation gzip relay sync. |
| `BadgeCollection` | Implemented | Named collection, search, date/meetup grouping and actual verified details. |
| `BadgeDetails` | Implemented | Source/collector/authority status, original full proof, share, explicit public attendance publication and local deletion. |
| `BadgeWorldMap` | Implemented | Maps collected badge coordinates; unsigned rolling QR coordinates are excluded from presence evidence. |
| `ReputationQr` | Implemented | Signed reputation QR and relay publication from deduplicated cryptographically verified badge evidence. |
| `ReputationCard` | Implemented | Source/claim/authority-derived trust score, independent issuers/meetups/account age and organizer thresholds. |
| `ReputationScanner` | Implemented | Camera or text input for signed reputation records and optional underlying full badge evidence. |
| `ReputationScanResult` | Implemented | Distinguishes authenticated author statements from independently verified badge evidence. |
| `ReputationVerify` | Implemented | Checks signature, versioned proof hash, source/collector signatures and issuer authority when evidence is supplied. |
| `VerifyPerson` | Implemented | Public-key lookup, signed observed coattendance, bounded three-degree paths and separate badge ownership evidence. |
| `VerifyPersonQrCamera` | Implemented | Shared native QR scanner supplies signed reputation/person verification input. |
| `TrustNetworkHub` | Implemented | Verified registry, vouches, reputation/proofs and observed social-network navigation. |
| `MyTrustNetwork` | Implemented | Incremental signed graph discovery up to three degrees, bridge paths, encrypted reverified cache and explicit stale/bounded status. Large-event edges remain separate. |
| `AdminPanel` | Implemented | Signed registry, bootstrap sunset, organic promotion, personal vouches/recovery/revocation and scoped portal leader operations. |
| `PlatformProofs` | Implemented | NIP-05 mapping plus signed nested platform proof verification and publication. |
| `HumanityProof` | Implemented | Signed zap/request/invoice/receipt/provider-chain verification and proof publication; wallet authorizes any payment externally. |
| `ProfileEdit` | Implemented | Nickname, canonical public reference, full name, home meetup, favorites and social handles persist; sanitized unknown public backup fields survive export. |
| `RelaySettings` | Implemented | Configured WSS endpoints drive the real relay client, with bounded subscriptions, Schnorr/filter checks and NIP-42 authentication. |
| `NearbyMeetups` | Implemented | Explicit foreground location, MapKit place search, group radius/date/favorite filters and native map/list selection. |
| `MeetupDetails` | Implemented | Directory details, exact-ID favorites, dates, native maps/share, portal and meetup-chat navigation. |
| `MeetupSelection` | Implemented | Search and multiple favorites, retaining distinct same-city portal groups and imported legacy favorite identifiers. |
| `LocationPicker` | Implemented | Native venue map tap, place search and explicit current location return coordinates to the event editor. |
| `PortalMeetups` | Implemented | Actual challenge login or reviewed manual token, identity-bound persisted session and managed meetup list. |
| `PortalMeetupEvents` | Implemented | Managed event list plus per-meetup leader listing/add/remove using Flutter endpoints. |
| `PortalEventEditor` | Implemented | Create/update portal dates and recurrence using the actual meetup-events contract; authenticated failures surface. |
| `CommunityPortal` | Implemented | Native community resources and official service destinations. |
| `MeetupCalendar` | Implemented | Complete portal aggregate first, bounded ICS fallback with recurrence/time-zone/DST/EXDATE, provenance and portal RSVP. |
| `EventCalendar` | Implemented | Unified public meetup/course/signed Nostr calendar and event details. |
| `EventEditor` | Implemented | Signed NIP-52 create/edit/delete, civil all-day/timed dates, mapped venue, badge-enabled event and issuer cutoff chain. |
| `MyEvents` | Implemented | Persisted local going/maybe notes plus portal/Nostr RSVP and event-chat entry points; local plans remain distinct from network acknowledgments. |
| `MeetupChat` | Implemented | NIP-29 room discovery, NIP-42 relay auth, membership, signed messages and visible-screen polling/read marks. |
| `EventChat` | Implemented | NIP-22 signed calendar comments with exact address/root/parent tags and current identity fencing. |
| `News` | Implemented | RSS feed/search/cache/read marks plus verified Nostr full articles, deduplicated reactions and explicit author zaps. |
| `NewsArticleDetails` | Implemented | Native full article when a verified Nostr address is supplied, excerpt fallback, original link/share, reactions and invoice flow. |
| `CommunityHub` | Implemented | News/resources/learning/calendar/trust/portal and native PlebRap/value-for-value navigation. |
| `PortalArea` | Implemented | Authenticated meetup/event/leader workflows alongside public course and lecturer navigation. |
| `Courses` | Implemented | Native course/lecturer tabs, searches, persisted list/detail cache and truthful refresh failures. |
| `CourseDetails` | Implemented | Actual detail/related lecturer/date/join links, cached fallback and native calendar review/export. |
| `LecturerDetails` | Implemented | Biography, source/cache status, related courses and validated official/social/Nostr links. |
| `ValueForValue` | Implemented | LNURL-pay amount/comment, optional signed zap request, BOLT-11 checksum/amount match, explicit external wallet review/open/share. Returning never claims payment success. |
| `PlebrapLibrary` | Implemented | All 41 source tracks, album/search, native AVPlayer queue/play/pause/seek, offline download/removal and system Now Playing controls. |
| `Glossary` | Implemented | All 55 source entries in German/English/Spanish plus a native seven-step feature tour. |
| `BitcoinDashboard` | Implemented | Height/fees, seven fiat currencies, partial prices/mempool/mining/difficulty metrics, source status, configurable HTTPS host, widget and public background refresh. |
| `Converter` | Implemented | Exact sats/BTC/mBTC/µBTC and reverse fiat conversion with excessive precision rejection. |
| `MempoolSettings` | Implemented | Explicit default HTTPS, source onion and normalized custom HTTP/HTTPS host selection; bounded public-only transport, no redirects or silent clearnet fallback, 45-second onion budget. System VPN supplies Tor routing. |
| `DiagnosticLog` | Implemented | Sanitized endpoint outcomes/counts and shareable diagnostics, settings/help and public background refresh status; no keys/tokens/raw badge log. |

## Identity, authority and upgrade boundaries

Local and remote signing require an unlocked vault. Backgrounding revokes retained signer handles and fences pending work; NIP-46 web authorization uses an in-app authentication view. Relay AUTH signing has a separate two-attempt budget and remote RPC reads authenticate with the session client key. Source and collector signatures, scoped portal leadership, signed registry/promotion evidence and revocations determine authority. Imported admin booleans and cached derived graph fields never grant it.

Encrypted `.21bkp` backup uses the existing Flutter `enc_v2` format. Actual unchanged Flutter exporter/importer fixtures have been checked in both directions. AppStore tests additionally prove full profile/favorites/wallet continuity across relaunch, additive versus replacement semantics, unknown public nested fields and large integer preservation. Proof sections are staged for revalidation and nested secrets are removed from public staging.

Startup discovers accessible Flutter Keychain records, `flutter.*` preferences and legacy cleartext/AES-GCM wallets. Unreadable originals block unwarned key creation. Reviewed migration creates a protected source archive and commits native identity/profile/wallet before optional original deletion; interrupted deletion or storage failure remains resumable. Binary wrapped-key records are preserved without inventing a platform unwrap format. When the original Flutter platform can read a key that this app cannot, recovery requires its complete backup.

Debug/Release use a separate preview app ID. The explicit **Upgrade** configuration uses Flutter's `dev.einundzwanzig.einundzwanzigMeetupApp` ID and a matching widget prefix. Access to installed Flutter data still requires the original team, signing/access groups and platform permissions. Synthetic source tests do not prove a signed device upgrade.

Passkeys remain fail-closed pending verified relying-party/domain/provisioning. Browser NIP-07 and Android NIP-55 are not Apple obligations. NFC remains disabled in the source baseline. No perfect secret erasure, independent crypto audit, production credentials or live payment/relay acceptance is claimed.

## Native implementation limits

Social edges are signed self-attestations and are labelled as observations, independent of badge ownership. Discovery is bounded to three degrees, 2 MiB/2,500 cached events and per-stage limits. Failed queries retain same-owner reverified cache with stale status; absent records cannot be treated as deletion without relay completeness metadata.

ICS retains bounded WEEKLY/MONTHLY, EXDATE, civil-date and DST handling. Unsupported RDATE/EXRULE/RECURRENCE-ID/custom zones and expansion limits remain visible. Camera, location, keychain access, in-app signer authorization, media/background policy, widgets and accessibility require Apple runtime acceptance. Bitcoin defaults to HTTPS and supports explicit custom HTTP/onion endpoints through a scoped public-data Network adapter. Onion requires the user’s system VPN; no built-in Tor proxy or silent clearnet fallback is supplied. Payments are reviewed and authorized in the user's external wallet, and app return is never payment proof.

The independent review follow-up additionally prevents locked or unloaded-wallet complete exports, binds mixed Flutter backups to their active remote signer while protecting a retained local key, preserves historical v1 HMAC badge rows outside v2 authority, exports current proofs, and resolves unambiguous legacy city favorites without merging same-city groups. Modal routes, editor draft lifetime, all-day civil dates, naive UTC portal edit dates, selected currency rates and asynchronous chat-draft acknowledgments have dedicated corrections/regressions.

Same-identity recovery authenticates the current vault password before adding alternate key material. Pending humanity receipt evidence survives normalization and remains unverified. Public wallet/account staging shares recursive secret-field redaction, including actual Flutter aliases; encrypted full recovery preserves legitimate identity keys. Independent final security and platform source reviews report no supported P1/P2 in their reviewed scope. All five unsigned Apple SDK builds passed at `bf7246b`; simulator UI acceptance is blocked by widget installation metadata, and signed-device acceptance remains open.
