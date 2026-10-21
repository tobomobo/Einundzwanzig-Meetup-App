# 15 — Miscellaneous files not covered by domain specs 01–14

Porting spec for the native Android rewrite (Kotlin + Jetpack Compose + Material 3 Expressive).
Source of truth: Flutter app at commit `623bdc9` (app version `1.7.2+31`).

Purpose: this spec lists every repository file that no domain spec (01–14) covers, and records for each one whether it is ported, replaced, or intentionally dropped. A file listed here as "dropped" is a deliberate decision, not an omission.

| File (read completely) | Lines | Role | Port decision |
|---|---|---|---|
| `test/passkey_rp_id_web_test.dart` | 30 | Browser-only test (`@TestOn('browser')`) of the `kIsWeb` branch of `PasskeyPrfService` (`relyingPartyId()`, `isEnvironmentAllowed`, `allowedWebHosts`) | **Intentionally dropped** (web-only; see §10.1). Native replacement tests in §10.2. |

Read to understand behavior: `lib/services/passkey_prf_service.dart` (233 lines; the code under test), `test/local_key_vault_test.dart` (the VM-side shared-domain host-list test, already covered in `01-identity-keys.md` §10 item 12), `docs/IDENTITY_ONBOARDING_PLAN.md` (lists the file as "Web-Test (Chrome) — `flutter test --platform chrome`"), `.github/workflows/pr_checks.yml`, `android-native/ARCHITECTURE.md` ("Web-only code (`nip07`, `*_web.dart`) is out of scope").

---

## 1. Overview and user-visible features

None. `test/passkey_rp_id_web_test.dart` is a test file. It has no UI, no runtime code path, and nothing user-visible.

The code it exercises is the web-only host gate of the passkey/PRF second unlock (the feature itself is specified in `01-identity-keys.md` §2.3.5, §2.3.6, §7.5). On a non-allowed web host the gate hides the "Mit Passkey sichern" option (`isSupported()` returns `false`) and makes `register()` throw `PasskeyPrfException('RP_ID_NOT_ALLOWED')`. On Android the gate is always open (§8), so there is nothing user-visible to port.

## 2. Screens & UX flows

None.

## 3. Data models

None new. The constants and getters exercised by the test (verbatim from `lib/services/passkey_prf_service.dart`):

```dart
static const _rpName = '21meetup';
static const _nativeRpId = 'einundzwanzig.space';

static const Set<String> allowedWebHosts = {
  'einundzwanzig.space',
  'localhost',
  '127.0.0.1',
};

static bool get isEnvironmentAllowed {
  if (!kIsWeb) return true; // Native: es entscheidet die Domain-Verknüpfung
  final host = Uri.base.host;
  if (host.isEmpty) return true; // Testumgebung ohne Host
  return allowedWebHosts.contains(host);
}

static String relyingPartyId() {
  if (kIsWeb) {
    final host = Uri.base.host;
    if (host.isEmpty) return 'localhost';
    return host;
  }
  return _nativeRpId;
}
```

`isSupported()` returns `false` immediately when `!isEnvironmentAllowed`. `register()` throws `PasskeyPrfException('RP_ID_NOT_ALLOWED')` when `!isEnvironmentAllowed`.

## 4. Persistence

None.

## 5. Network

None.

## 6. Nostr

None.

## 7. Crypto & encoding

None. The passkey RP id, PRF salt and WebAuthn JSON are specified in `01-identity-keys.md` §7.5. The only crypto-relevant fact here: on Android the RP id is always the constant `einundzwanzig.space`, never derived from a host (§8).

## 8. Business rules & algorithms

Truth table of the Dart code under test, with the Android outcome:

| Platform | `Uri.base.host` | `isEnvironmentAllowed` | `relyingPartyId()` | Android port |
|---|---|---|---|---|
| native (`!kIsWeb`) | (ignored) | `true` | `einundzwanzig.space` | **This row is the only one that applies.** |
| web | `''` (empty) | `true` | `localhost` | n/a |
| web | `einundzwanzig.space` | `true` | `einundzwanzig.space` | n/a |
| web | `localhost` / `127.0.0.1` | `true` | that host | n/a |
| web | any other host, e.g. `razue.github.io` (the GitHub Pages PWA) | `false` → no passkey offered, `register()` throws `RP_ID_NOT_ALLOWED` | that host (unused) | n/a |

Native rule (MUST): the Kotlin passkey module uses the fixed RP id `einundzwanzig.space` and has **no** host allowlist and no environment gate. Whether passkeys work is decided only by Credential Manager and the Digital Asset Links file (`https://einundzwanzig.space/.well-known/assetlinks.json`, see `01-identity-keys.md` §7.5). `allowedWebHosts`, `isEnvironmentAllowed` and the host-derived RP id are **not** ported. The `RP_ID_NOT_ALLOWED` code can still be kept in the error mapping (`01-identity-keys.md` §2.3.5) for parity, but the native code never throws it.

## 9. Security & privacy measures to preserve

The security reason behind the web gate (from the Dart comments): WebAuthn binds a passkey to the RP id, which in the browser is the host. On a shared domain such as `github.io`, that namespace belongs to every project under it, so a passkey for `razue.github.io` does not belong to this app. The allowlist keeps passkeys off the GitHub Pages PWA.

On Android the equivalent protection is the Digital Asset Links binding of the fixed RP id `einundzwanzig.space` to package `space.einundzwanzig.meetup` and its release signing certificate. Preserve this:
- Never derive the RP id at runtime (from a URL, build flavor or setting). It must be the compile-time constant `einundzwanzig.space`, or existing Flutter-created passkey wraps cannot be unlocked (same RP id + salt + credential id are required, `01-identity-keys.md` §7.5).
- Never set the RP id to a shared-hosting domain (`*.github.io`, `*.pages.dev`, `*.netlify.app`, `*.vercel.app`).

## 10. Tests & vectors to port

### 10.1 `test/passkey_rp_id_web_test.dart` — intentionally dropped (not ported)

Annotated `@TestOn('browser')`. It only runs with `flutter test --platform chrome test/passkey_rp_id_web_test.dart`. Plain `flutter test` (the VM run in CI) skips it, and `.github/workflows/pr_checks.yml` does **not** run it in its Chrome steps (those run only `pbkdf2_web_equality_test.dart`, `nip44_web_smoke_test.dart`, `nip46_client_test.dart`, `nip49_test.dart`). It is a manual developer test. Its header comment says why it exists: the `kIsWeb` branch never runs on the Dart VM, so without it the host allowlist is untested.

Its three tests, verbatim intent:

| # | Name (German, verbatim) | Assertion | Why it does not apply on Android |
|---|---|---|---|
| 1 | `rpId ist im Browser der Host` | `PasskeyPrfService.relyingPartyId() == Uri.base.host` | No browser and no `Uri.base`. Native RP id is the constant `einundzwanzig.space`. |
| 2 | `Testlauf auf localhost ist erlaubt` | `Uri.base.host` is `'localhost'` or `'127.0.0.1'`, and `PasskeyPrfService.isEnvironmentAllowed == true` | `isEnvironmentAllowed` is not ported (always `true` on native in Dart). The test also depends on the browser test runner's origin (it would fail if served from another host). |
| 3 | `ein github.io-Host wuerde abgelehnt` | `PasskeyPrfService.allowedWebHosts.contains('razue.github.io') == false` | `allowedWebHosts` is not ported (§8). |

Decision: drop all three. This matches the treatment of the other web-only tests: `pbkdf2_web_equality_test.dart` (`01-identity-keys.md` §10: "WebCrypto parts: n/a") and the "allowed hosts contain no shared-domain suffix" case of `local_key_vault_test.dart` (`01-identity-keys.md` §10 item 12: "web-only; can be dropped natively").

### 10.2 Native replacement tests (recommended, JVM unit tests)

They protect the same invariant (the passkey is bound to the right, non-shared RP id):
1. `PasskeyPrf.RP_ID == "einundzwanzig.space"` and `PasskeyPrf.RP_NAME == "21meetup"`.
2. The registration JSON built by the native module contains `"rp":{"id":"einundzwanzig.space","name":"21meetup"}`, and the authentication JSON contains `"rpId":"einundzwanzig.space"` (full JSON shapes: `01-identity-keys.md` §7.5).
3. `RP_ID` does not end with `.github.io`, `.pages.dev`, `.netlify.app` or `.vercel.app` (native counterpart of test 3 and of the `local_key_vault_test.dart` case).

## 11. Android capabilities, permissions, libraries

None beyond what `01-identity-keys.md` §11 already requires for passkeys (`androidx.credentials`, Digital Asset Links on `einundzwanzig.space`).

## 12. Cross-domain dependencies

- `01-identity-keys.md`: owns `PasskeyPrfService`, the passkey UI and the native RP id. §8 and §10.2 here only add the explicit "no web host gate" rule and the replacement tests.
- `android-native/ARCHITECTURE.md`: states that web-only code is out of scope. This spec is the per-file record of that decision for `test/passkey_rp_id_web_test.dart`.

## 13. Proposed Kotlin package/module structure and public API

No new module. Put the §10.2 tests next to the passkey implementation from `01-identity-keys.md` §13, e.g. `app/src/test/.../identity/passkey/PasskeyPrfRpIdTest.kt`. The passkey module MUST NOT expose `allowedWebHosts`, `isEnvironmentAllowed` or a host-dependent `relyingPartyId()`. A constant (`const val RP_ID = "einundzwanzig.space"`) is enough.

## 14. Risks / compatibility pitfalls

- **Copying the web gate into Kotlin.** A literal port of `isEnvironmentAllowed`/`allowedWebHosts` has no host to check on Android. Any "host" substitute (for example a configured server URL) could wrongly hide the passkey option or change the RP id. Do not port it.
- **RP id drift.** Changing the RP id (for example to a debug/staging domain) silently makes every passkey wrap created by the Flutter app unusable after the in-place upgrade. The user then falls back to the password wrap or loses the passkey path.
- **Test-coverage audit.** Tools that check "every Dart test has a Kotlin counterpart" must treat `test/passkey_rp_id_web_test.dart` as intentionally dropped (this spec), not as missing.
