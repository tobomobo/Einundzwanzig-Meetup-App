# :data:admin

Organizer registry, the user's own admin-status check, and organic admin claims. There is no UI
here. Spec: `spec/05-social-graph-admin.md` §3.3–§3.5, §4, §6, §7.4, §8.3–§8.5, §9.
Dart sources: `lib/services/admin_registry.dart`, `lib/services/admin_status_verifier.dart`,
`lib/services/promotion_claim_service.dart`, and `UserProfile.reVerifyAdmin` in `lib/models/user.dart`.

```kotlin
implementation(project(":data:admin"))
```

`api` dependencies: `:core:common`, `:core:data`, `:core:nostr`, `:data:identity`, `:data:badges`.
`implementation`: `:data:trust` (score and `ZoneIdProvider`).

Package root: `space.einundzwanzig.meetup.data.admin`. Hilt binds every service as a `@Singleton`
(`di/AdminModule`). Inject the interfaces.

## Constants: `AdminConstants`

| | |
|---|---|
| `SUPER_ADMIN_NPUB` | `npub1lf0rga7j66uj6enae2mxezamz5nsz3vechhvmh25tcarn4u8qf5q534jzc` |
| `SUNSET_THRESHOLD` | 20 distinct admin-list **signers** |
| `RELAYS` | the 4 hard-coded relays (damus, nos.lol, nostr.band, einundzwanzig). The user's relay config does not apply to these |
| `ADMIN_LIST_KIND`/`ADMIN_LIST_D_TAG` | 30078 / `einundzwanzig-admins` |
| `CLAIM_KIND`/`CLAIM_D_TAG` | 30021 / `einundzwanzig-admin-claim` |
| `MIN_VERIFIED_BADGES` (3), `MIN_UNIQUE_SIGNERS` (1), `MAX_CLAIM_AGE_DAYS` (90) | claim rules |
| `KEY_*`, `ALL_KEYS` | Flutter pref names in the shared `KeyValueStore`: `admin_registry_cache`, `admin_registry_timestamp` (ms), `bootstrap_permanently_sunset`, `admin_unique_authors_count`, `sunset_authors_set_v1`, `sunset_count_reset_v1_done`, `my_personal_vouches`, `organic_admin_claims` |

## Models: `…model`

| Type | Notes |
|---|---|
| `AdminEntry(npub, meetup, name = "", addedAt = 0)` | `isLegacyOrganicClaim` (`^Organic \(\d+ Badges\)$`), `toJsonMap()`/`toJson()` (key order `npub, meetup, name, added_at`), `fromJson`, `listFromJson`, `listToJson`. `addedAt` is opaque |
| `AdminCheckResult(isAdmin, meetup?, name?, source)` | `SOURCE_NO_KEY`, `SOURCE_SUPER_ADMIN`, `SOURCE_LOCAL_CACHE`, `SOURCE_NOSTR_RELAY`, `SOURCE_UNAVAILABLE`, `SOURCE_NOT_FOUND`, `SOURCE_INVALID_KEY` |
| `AdminsBySigner` | `Map<signerHex, List<AdminEntry>>` |
| `AdminVerification(isAdmin, source, reason)` | sources `no_key`, `trust_score`, `seed_admin`, `not_admin` (+ legacy `vouch_consensus`). `NOT_ADMIN`, `NO_KEY`. `shouldPublishClaim` = `isAdmin && source ∈ {trust_score, vouch_consensus}` (Home trigger) |
| `RawClaim(pubkey, content, createdAt)` | a fetched kind-30021 event |
| `VerifiedClaim(claimerPubkey, meetup, verifiedBadgeCount, uniqueSignerCount, claimedAt)` | `toJsonMap()` adds `"organic": true`; `fromJson` |

## `registry.AdminRegistry`

| Method | Behavior |
|---|---|
| `checkAdmin(npub)` | `no_key` → `super_admin` (only before the sunset; meetup `"Alle Meetups"`, name `"Super-Admin"`) → `local_cache` (also starts a background refresh if the cache is ≥ 1 h old; single-flight, at most one runs at a time) → blocking relay fetch: `nostr_relay`; `unavailable` if no relay gave a non-empty list, else `not_found` |
| `checkAdminByPubkey(hex)` | `no_key` for `""`, `invalid_key` if the hex can't be encoded, otherwise `checkAdmin` |
| `fetchFromRelays()` | bootstrap: asks only for the super admin; after the sunset: up to the first 50 cached admins. Relays are queried one after another. Organic entries and signers that weren't asked for are dropped. Records the signers for the sunset count. Does not write the cache. `null` = no relay answered |
| `isSunsetActive()` | includes the one-time K1 reset and the check for a tampered flag |
| `getAdminList()` | the cache without organic entries (rewritten if any were dropped), `[]` if missing |
| `removeFromCache(npub)` | Home calls this on portal revocation. Never throws |
| `addAdmin(entry)` | backup restore. Organic name → `IllegalStateException`; duplicate → ignored; invalid npub → `AdminRegistryException("Ungültiger npub.")`; `addedAt <= 0` → now (seconds) |
| `removeAdmin(npub)`, `clearCache()`, `cacheAge(): Duration?` | |
| `forceRefresh(): Int` | fetches, saves, re-checks the sunset. Returns the list size, or `-1` |
| legacy vouches | `getMyVouches`, `addVouch`, `removeVouch`, `recoverMyVouchesFromRelays`, `revokeAllVouches`, `createAndPublishAdminListEvent` (waits for `OK`, returns `{"sent_to": N, "event": {…}}`), `createAdminListEvent`. No UI in Flutter |
| `AdminRegistry.create(store, relayClient, signer, timeSource, backgroundScope, relayFetch = null, zone, log)` | factory for tests and non-Hilt code. `AdminRelayFetch` replaces the per-relay query (Dart `relayFetchOverride`); the filtering after it still runs |

`AdminRegistryException(message)` carries Flutter's German message.

Scanner and event-badge callers: show `unavailable` as "check failed", never as "unknown
signer" (spec 05 §9 item 10).

## `verify.AdminStatusVerifier`

| Method | Behavior |
|---|---|
| `verifyAdminStatus(badges)` | `no_key` → trust score of the badges meets the promotion threshold (`trust_score`, reason `"Trust Score 5.3 erfüllt Schwellenwert (5.0)."`) → own npub is the bootstrap super admin (`seed_admin`) → `NOT_ADMIN`. Registry cache and relay hits are ignored (circular). Fails closed |
| `isVerifiedAdmin(badges)` | `.isAdmin` |
| `reVerifyAdmin(badges): AdminReVerification(verification, profile)` | Dart `UserProfile.reVerifyAdmin`: verifies, then saves the profile through `UserProfileRepository.update { withAdminVerification(...) }`. The portal flag is not touched. Afterwards Home calls `publishAdminClaim` if `verification.shouldPublishClaim` |

## `claims.PromotionClaimService` / `claims.PromotionClaims`

| API | Behavior |
|---|---|
| `publishAdminClaim(badges, meetupName): Boolean` | needs ≥ 3 badges with `isFullyBound && claimPubkey == own hex`. Signs kind 30021 (`[["d","einundzwanzig-admin-claim"],["meetup",name]]`) and publishes to the 4 relays one at a time (5 s connect, no OK check, as in Flutter). `true` if any relay took it. Signing errors propagate |
| `syncOrganicAdmins(): List<VerifiedClaim>` | Home start. Fetches claims (the first relay with a non-empty list wins) and verifies them against super admin + registry. Organic claims never count as known. Stores the newest claim per claimer. Fetching nothing or an error leaves the stored list unchanged. Verification runs on `@DefaultDispatcher`, so calling from Main is safe |
| `getOrganicAdmins()` | the stored list, for information only (grants no rights) |
| `suspend verifyClaim(claim, knownHex)` | `PromotionClaims.verifyClaim(claim, knownHex, nowSeconds)` with the injected clock, on `@DefaultDispatcher` |
| `PromotionClaims` (pure object) | `verifyClaim`, `claimContent(npub, meetup, badges, claimedAt, zone)` (exact Flutter key order), `proofOf(badge, zone)`, `claimableBadges(badges, ownHex)`, `claimTags(meetup)` |

## `signing.AdminSigner`

The part of `SigningService` this module uses (`canSign`, `npub`, `signEvent`). Hilt binds it to
`SigningService`. Use `AdminSigner.of(signingService)` outside Hilt.

## Tests

`AdminRegistryOrganicFilterTest`, `AdminRegistrySunsetSignerTest`, `PromotionClaimK1Test` (1:1
ports with extra cases), `AdminRegistryCacheTest` (admin parts of `security_followup_test.dart`,
cache ops, sunset revocation/reset/tamper), `AdminStatusVerifierTest` (incl. the `trust_score`
branch with a fake calculator), `PromotionClaimWireTest`, and the real relay paths on
`FakeRelayConnector`/`FakeRelaySocket` with virtual time: `AdminRegistryRelayTest` (EOSE/timeout/
disconnect/connect-failure mapping, `unavailable` vs `nostr_relay`, admin-list OK handling,
single-flight refresh, shared lookup, cache-write serialization, admin-list wire format, legacy
vouch API) and `PromotionClaimRelayTest` (partial results on timeout/close, storage
rules, sequential publish). `ClaimFixtures` builds the K1 badge bindings by hand, as the Dart test.

## Concurrency

Every admin-cache write goes through one mutex: the local edits (`removeFromCache`, `addAdmin`,
`removeAdmin`) and the relay saves of `checkAdmin`, `forceRefresh` and the background refresh (the
lock is taken after the network I/O, never around a fetch). A local edit therefore cannot overwrite
a newer relay list with a stale one. Vouch-list writes use the same mutex. The organic purge on
read is a compare-and-set, so it never overwrites a newer relay save. Concurrent blocking lookups
in `checkAdmin` (cache miss) share one relay crawl; `fetchFromRelays()` itself is not coalesced.
`PromotionClaimService.syncOrganicAdmins` checks for cancellation between claims.
