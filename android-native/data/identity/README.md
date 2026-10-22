# :data:identity

The user's Nostr identity and every signer path. Spec: `spec/01-identity-keys.md` (all data
sections), spec 14 §6 (device authentication, FLAG_SECURE notes), spec 00 items 8/9/11.
Package root: `space.einundzwanzig.meetup.data.identity`.

```kotlin
implementation(project(":data:identity"))   // exposes :core:common, :core:nostr (+ :core:crypto) and androidx.biometric as api
```

No UI lives here. Screens (Splash/Intro/IdentitySetup, BunkerConnectSheet, key management,
nsec/ncryptsec dialogs, the review-demo dialog) belong to `:feature:*`. Secret dialogs use
`:core:ui` `SecureDialog`/`SecureScreenEffect`/`SecretClipboard`.

## Public API (inject the interfaces; all `@Singleton`)

| API | Purpose (Dart origin) |
|---|---|
| `SigningService` | The only signing facade (`SigningService`): `signEvent(kind, tags, content): NostrEvent`, `pubkeyHex()`, `npub()`, `canSign()`, `getMode()`, `isExternalSigner()`, `nip44Encrypt/Decrypt(peerHex, …)`, `canNip44()`, `activeSigner()`, `recommendedExistingPath()`, `isAmberInstalled()`, `nip07Available()` (always `false`), Amber `connectAmber/restoreAmber/disconnectAmber`, NIP-07 `connectNip07` (always `Missing`) / `restoreNip07` / `disconnectNip07`, `useLocalMode()`, NIP-46 `connectNip46Bunker(uri)`, `startNip46Pairing()` → `completeNip46Pairing(p)`, `nip46SessionAlive()`, `nip46SessionForBackup()`, `restoreNip46(npub, bunkerUri, clientSk?)`, `disconnectNip46()`, `nip46PairingRelays()` |
| `SigningService.signerReady: SharedFlow<Unit>` | Replaces `onSignerReady`. Fires after connect/restore of any signer and `useLocalMode()`. `:data:badgesync` collects it in its `AppStartupTask` |
| `SigningService.authUrlEvents: SharedFlow<String>`, `acknowledgeAuthUrl(url)` | Replaces the global `onNip46AuthUrl`: the app root shows the orange 60 s snackbar (`bunkerAuthNeeded` / `bunkerAuthAction`) and opens the URL with `SafeUrlLauncher` (http/https only). The latest URL is replayed until acknowledged, so an approval that arrived while the UI was not collecting is shown when it comes back; call `acknowledgeAuthUrl(url)` once shown |
| `SigningService.interceptAuthUrls(handler): AutoCloseable` | The bunker sheet shows `auth_url` inline while open; `close()` restores the previous handler (nested handlers stack), finally the global flow. **The handler runs on the relay transport thread** (OkHttp reader): only update thread-safe state (`MutableStateFlow`) or post to Main |
| `SigningMode` (`LOCAL/AMBER/NIP07/NIP46`, `.stored`, `.isExternal`), `RecommendedExistingPath` | Stored as `signing_mode` (`local`/`amber`/`nip07`/`nip46`) |
| `AmberConnectResult`, `Nip07ConnectResult`, `Nip46ConnectResult` (`Success/Missing/Cancelled/Rejected/Timeout/Error`) | Sealed outcomes |
| `Nip46Pairing` (`uri`, `secret`, `relays`, `cancel()`; the client and its session key are internal) | Show `uri` as QR (`:core:ui` QR) + copy + `ACTION_VIEW`; call `cancel()` when the sheet closes (`onCleared`/dispose); after a successful `completeNip46Pairing` it is a no-op, the adopted session stays alive |
| `SigningException.error: SigningError?` | Machine-readable reason for localization (table below); `null` → show `message` |
| Exceptions | `SigningException` (+ `SigningCancelledException`, `SignerMissingException`, `WrongAccountException`, `KeyFormatException`, `SecureStorageUnreadableException`, `IdentityStorageException`), `LocalEasyAuthException`, `nip46.Nip46Exception` (+ `Nip46RemoteException`, `Nip46TimeoutException`, `Nip46RelayRejectedException`), `passkey.PasskeyPrfException` (`code`, `isUnavailable`, `isCancelled`), `core.crypto.Nip49Exception`. `message` = the Flutter German text |
| `signer.NostrSigner`, `signer.verifySignerResponse(...)`, `signer.SignerActor` | Signer seam + the shared N1 verification (account, sig, kind, content, real BIP-340, 10-digit `created_at`) |
| `nip46.BunkerPointer` (`parse`, `normalizePubkey`, `toBunkerUri`, `buildNostrConnectUri`), `nip46.Nip46Client`, `Nip46ClientMetadata`, `Nip46Timeouts`, `Nip46RelayLink`/`Nip46RelayOpener` | NIP-46 transport (kind 24133, NIP-44, multi-relay, OK/CLOSED handling, relay cooldown, pairing). Byte-identical URIs (fixture `nip46`) |
| `nip55.ExternalSignerBridge` (`pendingLaunch`, `attachHost(): AutoCloseable`, `onLaunched(id): Boolean`, `onResult(id, resultCode, data)`, `onLaunchFailed(id, e)`), `ExternalSignerLaunch` | Host side of Amber foreground intents (see wiring below). The data layer holds no Activity |
| `keys.SecureKeyStore` | `ensureMigrated`, `saveKeys`, `getNsec/getNpub/getPrivHex`, `loadKeys(): StoredKeys?`, `hasKey`, `deleteKeys`, `save/get/deleteNip46ClientKey` |
| `keys.LocalKeyVault` | `savePasswordWrap(ncryptsec, npub)`, `getPasswordWrap`, `getPasswordWrapNpub`, `passwordWrapMatchesNpub`, `savePasskeyWrap`, `getPasskeyWrap`, `getPasskeyCredentialId`, `hasPasskeyWrap`, `clearPasskeyWrap`, `clearAll` |
| `keys.NostrKeyService` | `generateKeyPair`, `importNsec`, `importPrivHex`, `importNcryptsec` → `GeneratedKeys(nsec, npub, pubHex)`. Stores keys only — callers then do `LocalKeyVault.clearAll()` + `useLocalMode()` (spec 01 §8.2) |
| `keys.IdentityKeys` | All pref / secure key names, `RECOVERY_PREFIXES`, `IDENTITY_SECRET_NAMES`, `recoveryName(prefix, name)` |
| `keys.IdentityRecovery` (`candidates()`, `adopt(candidate, password?)`, `exportNsec(candidate)`, `discard(candidate)`, `discardAll()`), `RecoveryCandidate` (`source`, `npub`, `hasPrivateKey`, `needsPassword`), `RecoverySource` (`ESP`/`BACKUP`/`RECOVERED`) | Identities `:data:migration` parked under `legacy_esp/`, `legacy_backup/`, `legacy_recovered/` (spec 14 §4.8). The identity area shows the one-time notice when `candidates()` is non-empty: restore (`adopt`, then `LocalKeyVault.clearAll()` + `useLocalMode()` as after every import), export (`exportNsec` behind `DeviceAuthenticator` + `SecureDialog`), or `discard`. Never adopted automatically (R5). A password-wrap candidate shows the wrap's bound npub; `adopt` decrypts first and refuses (`KeyFormatException`, active key untouched) if the result is not the shown identity |
| `easyauth.LocalEasyAuth`, `easyauth.PasskeyRegistration` | `register(nickname, password)`, `unlockWithPassword(pw)`, `LocalEasyAuth.MIN_PASSWORD_LENGTH` (= 8, `kMinPasswordLength`). Passkeys, ViewModel-safe split: add = `passkeyRegistration(name)` (VM) → `PasskeyPrfService.register(activity, req.userIdHex, req.userName)` (UI scope) → `savePasskeyWrap(req, result)` (VM, scrypt); unlock = `passkeyCredentialIds()` (VM) → `PasskeyPrfService.unlock(activity, ids)` (UI scope) → `unlockWithPrfSecret(result)` (VM). The combined `addPasskeyWrap(activity, name)` / `unlockWithPasskey(activity)` hold the Activity through scrypt: UI-scoped coroutines only |
| `passkey.PasskeyPrfService` (`isSupported`, `register(activity, …)`, `unlock(activity, …)`), `PasskeyPrfResult`, `passkey.PasskeyJson` | WebAuthn PRF via Credential Manager, RP `einundzwanzig.space`. `isSupported()` is `false` unless the build sets `-PpasskeyRpAssociated=true` (→ `BuildConfig.PASSKEY_RP_ASSOCIATED`): turn it on once `https://einundzwanzig.space/.well-known/assetlinks.json` lists the release certificate (spec 14 F9); without it every registration fails with `DomainNotAssociated`, so onboarding should not offer the step. Errors are mapped like the Flutter plugin + `tPasskeyError`: SecurityError / "RP ID cannot be validated" / missing provider / every `android-unhandled` error → `isUnavailable`; cancel → `isCancelled`; only `FAILED` (timeout, no Google account, sync account, excluded credential) carries a platform text to show |
| `profile.UserProfile`, `profile.UserProfileRepository` (`load`, `save`, `update { }`, `profile: Flow` — re-emits on its own prefs, `save`, and local key writes/deletes; an unreadable secure store is logged and skipped, the flow never fails) | Exact pref keys, npub precedence external > keystore > `nostr`, `isOnboarded`, `hasCustomNickname`, `isAdmin`, `promotionSource`, `withAdmin`, `withPromotionSource`, `withAdminVerification(isAdmin, source)` |
| `review.AppReviewDemo` (`PAYLOAD`, `matches`, `isDemoLogin`, `SUCCESS_TITLE/BODY`), `review.AppReviewSignIn` (`isOffered`, `signIn`), `review.ReviewDemoBadgeWriter`, `ReviewDemoBadge` | Review demo; gated by `BuildConfig.DEBUG \|\| APP_REVIEW_SIGNIN` (`-PappReviewSignin=true`) |
| `integrity.DeviceIntegrityService` (`check(forceRefresh)`, `warningMessage`), `IntegrityReport`, `DeviceIntegrityStatus` | Root/Magisk/BusyBox indicators, warn only |
| `auth.DeviceAuthenticator.authenticate(activity: FragmentActivity, title, subtitle?)` | BiometricPrompt `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`; no lock → `true` |
| `IdentityRepository.wipeAll()` | Identity part of the full reset: disconnect NIP-46, delete keys, session key, vault and every identity recovery copy (`legacy_*/<identity key>`, readable or not); forgets the in-memory admin crypto verification |

The manifest of this module merges the NIP-55 `<queries>` (Amber package, `nostrsigner:`) and
`nostrconnect:` into the app.

### `SigningError` → string resources (spec 01 §8.10)

| `SigningError` | Resource (args) |
|---|---|
| `SignCancelled` / `AmberMissing` / `WrongAccount` / `NoPrivateKey` | `errSignCancelledAmber` / `errAmberMissing` / `errWrongAccount` / `errNoPrivateKey` |
| `SignerNoEvent(actor)` / `SignerContentChanged(actor)` / `SignerBadSig(actor)` | `errSignerNoEvent` / `errSignerContentChanged` / `errSignerBadSig` (actor label) |
| `SignerKindChanged(actor, got, want)` | `errSignerKindChanged(actor, got, want)` |
| `SignerUnreadable(actor, detail)` / `AmberError(detail)` | `errAmber(detail)` (ARB `errorAmber`) |
| `BunkerNoAnswer` / `BunkerRejected(reason)` / `BunkerFailure(detail)` | `errBunkerNoAnswer` / `errBunkerRejected(reason)` / show `message` |
| `ModeNoKey(AMBER/NIP07/NIP46)` | `errModeAmberNoKey` / `errModeNip07NoKey` / `errModeBunkerNoKey` |
| `NoBunkerSession` / `Nip07WebOnly` | `errNoBunkerSession` / `errNip07WebOnly` |

`SignerActor` (`AMBER`, `REMOTE_SIGNER`, `EXTENSION`) needs a localized label in the feature.

## Wiring for other modules

**`:app` / `MainActivity`** (must extend `FragmentActivity`, spec 14 F8):

```kotlin
@Inject lateinit var externalSignerBridge: ExternalSignerBridge
private var launchedSignerId = 0L   // keep in onSaveInstanceState / restore in onCreate
private val signerLauncher = registerForActivityResult(StartActivityForResult()) { r ->
    externalSignerBridge.onResult(launchedSignerId, r.resultCode, r.data)
}
// in onCreate:
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        val host = externalSignerBridge.attachHost()   // "a UI can launch Amber now"
        try {
            externalSignerBridge.pendingLaunch.collect { launch ->
                if (launch != null && !launch.launched && externalSignerBridge.onLaunched(launch.id)) {
                    launchedSignerId = launch.id
                    try { signerLauncher.launch(launch.intent) }
                    catch (e: ActivityNotFoundException) { externalSignerBridge.onLaunchFailed(launch.id, e) }
                }
            }
        } finally {
            host.close()
        }
    }
}
```

A launched Amber request stays reserved (`busy`) until its result arrives, even if the caller
gave up; results for another id or for a request that was never launched are dropped, and ids
start at a random value per process, so a late answer (also one delivered to a recreated Activity
after process death) never completes a newer request. Without an attached host a foreground
request waits at most 5 s (configuration change) and then fails with `cancelled`
(`SigningCancelledException`). The same 5 s grace applies to a published request whose host
detaches before launching it, so background callers such as badge sync never block the slot or
pop Amber up out of context later.

Root scaffold: collect `signingService.authUrlEvents`, show the 60 s snackbar, then call
`signingService.acknowledgeAuthUrl(url)` (otherwise every re-subscription shows it again).
Splash: `secureKeyStore.ensureMigrated()` (after the Flutter migration), then
`userProfileRepository.load().isOnboarded`.

**`SecureStorageUnreadableException`** (corrupt/unavailable Keystore) escapes `UserProfileRepository.load()`,
`SigningService.canSign()`, `npub()`, `pubkeyHex()` and the `SecureKeyStore` getters (Dart parity:
an unreadable key never reads as "no key"). Splash and every `canSign()`/`load()` caller must catch
it and show a "secure storage unavailable, retry" state; **never** route to onboarding (that would
create a new identity over the unreadable one). The `profile` flow already logs and skips it.

**`:data:badgesync`**: collect `signingService.signerReady` in its startup task.

**`:data:migration`**: park identity secrets that must not become primary under
`IdentityKeys.recoveryName(prefix, name)` with a prefix from `IdentityKeys.RECOVERY_PREFIXES`
(spec 14 §4.8). `IdentityRecovery` lists/adopts/discards them; `wipeAll()` deletes every
`legacy_*/<IDENTITY_SECRET_NAMES>` entry. Recovery copies of other domains (badge keys,
`portal_api_token*`) are not touched here: their owners must delete them on reset.

**`:data:settings` / reset use case**: `IdentityRepository.wipeAll()` already covers the
identity recovery copies; no separate secure-store deletion is needed for them.

**`:data:admin`** (`reVerifyAdmin` moved there):
`profiles.update { it.withAdminVerification(v.isAdmin, v.source) }`. `adminCryptoVerified` is in
memory only (never persisted) but held by the repository for the verified npub: every later
`load()` and the `profile` flow carry it until the identity changes or `IdentityRepository.wipeAll()`.
Saving a stale profile with `false` does not revoke it.

**`:data:badges`**: bind `ReviewDemoBadgeWriter` (`@Binds`) to save the demo badge
(`MeetupBadge(id, meetupName, date = DartDateTime(dateMicros, isUtc = false), iconPath, delivery, isRetroactive)`).

**`:data:backup`**: `SigningService.getMode/npub/isExternalSigner/nip46SessionForBackup/restoreAmber/restoreNip07/restoreNip46/useLocalMode`,
`SecureKeyStore.getNsec/getNpub/getPrivHex/saveKeys`, `core.crypto.Keys.backupKeyConsistent`.

**Features**: map `PasskeyPrfException.isUnavailable` → `idSetupPasskeyUnavailable`,
`isCancelled` → nothing; `Nip46ConnectResult.Timeout` → `bunkerTimeout`; Amber results → the
`profileAmber*` strings. Run `LocalEasyAuth`/`NostrKeyService.importNcryptsec` from a ViewModel
(scrypt takes seconds; it already runs on the default dispatcher).

## Storage

Prefs (shared `KeyValueStore`): `signing_mode`, `amber_pubkey_hex`, `amber_npub`, `nip07_pubkey_hex`,
`nip07_npub`, `nip46_pubkey_hex`, `nip46_npub`, `nip46_bunker_uri`, `secure_migration_done`,
`app_review_demo`, the `UserProfile` keys. Secure store: `nostr_nsec_key`, `nostr_npub_key`,
`nostr_priv_hex`, `nip46_client_sk`, `easyauth_password_ncryptsec`, `easyauth_password_wrap_npub`,
`easyauth_passkey_ncryptsec`, `easyauth_passkey_credential_id`. Unreadable secrets throw
`SecureStorageUnreadableException` instead of reading as "no key".

## Tests

`tools/gradlew-locked :data:identity:testDebugUnitTest` — 195 tests (JVM; one Robolectric class).

Ported: `nip46_client_test` (all cases, real NIP-44 fake signer), `nip46_signing_service_test`,
`signing_mode_test`, `recommended_existing_path_test`, `local_easy_auth_test`, `local_key_vault_test`,
`user_profile_test`, `app_review_demo_test`, `signer_nip44_test` (service level), `nip07_signer_test`
and `stage_a_security_test` (N1 via `verifySignerResponse`, N3 via `Keys.backupKeyConsistent`).
Plus fixture `01-identity-keys-vectors.json` (`nip46`, `passkeyPrf`, `appReviewDemo`), Amber
parsing, passkey JSON builders, legacy plaintext migration, device integrity, the signer bridge
(request/result matching, abandoned requests), the reset incl. recovery copies, `IdentityRecovery`,
service-level NIP-46/Amber wiring (`Nip46ServiceFlowTest`: persistence without secret, client
reuse, timeout/error/cancel cleanup, `auth_url` routing), the profile flow, passkey error
mapping, Dart trim semantics and typed `SigningError`s. `security_followup_test` has no identity cases (admin registry, badge coordinates,
zap receipts belong to other modules).
