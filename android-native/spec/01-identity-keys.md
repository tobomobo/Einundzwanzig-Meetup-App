# 01 — Identity & Keys (porting specification)

Scope: everything that creates, imports, unlocks, stores, exports, wipes or uses the user's Nostr identity, and every signer path (local key, Amber/NIP-55, remote signer/NIP-46, browser extension/NIP-07 as a stored-but-unusable mode on Android). Also: first-start routing (Splash → Intro → Identity setup), passkey/PRF second unlock, NIP-49 `ncryptsec`, PBKDF2 core used by the backup, screenshot protection, device authentication, root detection, and the Apple-review demo login.

Source of truth (all read completely): `lib/screens/identity_setup_screen.dart`, `lib/screens/intro.dart`, `lib/screens/bunker_connect_sheet.dart`, `lib/services/signing_service.dart`, `lib/services/secure_key_store.dart`, `lib/services/local_key_vault.dart`, `lib/services/local_easy_auth.dart`, `lib/services/passkey_prf_service.dart`, `lib/services/nip49.dart`, `lib/services/pbkdf2/*`, `lib/services/nip46/*`, `lib/services/nip07/*`, `lib/services/screen_secure.dart`, `lib/services/device_integrity_service.dart`, `lib/services/app_review_demo.dart`, `lib/widgets/app_review_sign_in.dart`, `lib/models/user.dart`, `docs/IDENTITY_ONBOARDING_PLAN.md`, `docs/SIGNER_APP_METADATA.md`. Followed into: `lib/services/nostr_service.dart`, `lib/services/nip44.dart`, `lib/services/relay_socket.dart`, `lib/services/relay_config.dart`, `lib/services/safe_url_launcher.dart`, `lib/main.dart`, the identity parts of `lib/screens/profile_edit.dart`, `lib/screens/home_screen.dart` (reset, integrity banner), `lib/services/backup_service.dart` (identity fields), `android/app/src/main/kotlin/.../MainActivity.kt` (the existing Kotlin NIP-55 bridge), `android/app/src/main/AndroidManifest.xml`, plus the pub-cache sources of `nostr 1.5.0`, `flutter_secure_storage 10.3.4`, `shared_preferences_android 2.4.20`, `passkeys_android 2.13.0` where byte formats matter.

Test vectors: `android-native/spec/fixtures/01-identity-keys-vectors.json` (this domain) and `<repo>/test/fixtures/nip44.vectors.json` (NIP-44, unchanged from paulmillr/nip44).

Conventions in this document:
- "Dart" = current Flutter app. "Native" = the Kotlin/Compose rewrite.
- String keys are ARB keys from `lib/l10n/app_de.arb`; `tools/arb2xml.py` keeps them verbatim as Android resource names (`R.string.idSetupTitle`). German value shown in quotes where useful.
- **MUST** = needed for wire/data compatibility or a security property. **SHOULD** = parity. **MAY** = optional improvement (call out explicitly when deviating).

---

## 1. Overview and user-visible features

The app identifies a user by a Nostr secp256k1 keypair (x-only pubkey, `npub`). Every signature in the whole app goes through one facade, `SigningService.signEvent(kind, tags, content)`, which dispatches to the active signer:

| Mode (stored value) | Who holds the private key | Available on Android native |
|---|---|---|
| `local` | App (Keystore-protected secure storage), created in-app or imported (`nsec`/`ncryptsec`) | yes |
| `amber` | Amber app (`com.greenart7c3.nostrsigner`) via NIP-55 intents/ContentResolver | yes |
| `nip46` | Remote signer ("bunker": nsec.app, Amber-as-bunker, Clave, Signet, Bunker46) via kind-24133 events over relays | yes |
| `nip07` | Browser extension (web only) | **no** — can only appear after restoring a web backup; identity is shown, `canSign()==false`, user is offered to reconnect another signer |

User-visible features (every one):

1. **Splash routing**: on cold start, run storage migration, load profile; if not onboarded → Intro, else → main shell (AppShell). 800 ms minimum splash, 500 ms fade.
2. **Intro screen**: animated logo/tagline/buttons, background image, language picker (System/Deutsch/English/Español with flag emoji), "COMMUNITY BETRETEN" (enter, routes to identity setup if needed, with an orange 3 s snackbar "Bitte lege zuerst deine Identität fest."), "BACKUP LADEN" (restore backup → main shell).
3. **Identity setup wizard** (8 steps): choose / new / resume / passkey / existing / existingMore / nameOnly / meetup.
   - "Schon auf diesem Gerät" (resume) card appears **first and primary** when a key or wrap survived on the device (e.g. reinstall with Keystore data still present on iOS; on Android only if data survived) — prevents overwriting an existing identity.
   - "Neu hier": name + password (+confirm) with live rule checklist (≥ 8 chars, match), coloured field borders, per-field show/hide toggle, warning that the password is the only way back; "Loslegen" button with in-button spinner; generates key + NIP-49 password wrap.
   - Optional "Mit Passkey sichern" (WebAuthn PRF second wrap) / "Später".
   - Two-step non-dismissible "secure your identity" sheet: (1/2) create backup file / skip, (2/2) save encrypted key (`ncryptsec`) as `.txt` file or copy to clipboard / later.
   - "Schon Nostr": one primary path per platform (Android: Amber if installed, else remote signer), "Anderer Weg" → import `nsec`/`ncryptsec` (+ password), alternative signer cards, "BACKUP LADEN".
   - Name step when the identity has no custom nickname; meetup picker step when no home/favorite meetup (with "Später").
   - Animated cross-faded background (sharp on button pages, blurred+brightened watermark on form pages).
   - App-review demo login box (debug builds / review builds only).
4. **Remote signer (NIP-46) sheet**: QR code + copy + "open signer app" for `nostrconnect://` pairing, or paste a `bunker://` URI; 2-minute waiting state with cancel; anti-double-tap; "Freigabe im Browser öffnen" button when the signer sends `auth_url`; platform-specific signer recommendation text.
5. **Global auth_url snackbar**: whenever a remote signer demands browser approval during any signature, an orange 60 s snackbar with "ÖFFNEN" action appears.
6. **Profile key management** (embedded in the profile screen, owned by this domain): show npub + copy; show nsec (confirm dialog + device authentication + FLAG_SECURE dialog + 45 s auto-clearing clipboard); export encrypted `ncryptsec` (device auth, reuses the setup password wrap if bound to this npub, else asks for password+confirm, scrypt wait state); create new key (education dialog, then nsec backup dialog); import `nsec`; connect Amber / remote signer; "Anderen Signer verbinden" with switch-confirmation dialog and identity-changed warning; "Verbindung prüfen" (bunker ping); "SIGNER TRENNEN" with confirmation.
7. **Diagnostics**: "NIP-44 probe" button in the diagnose log (encrypt+decrypt to self through the active signer) with snackbar result.
8. **Device integrity warning** on the home screen (root indicators), dismissible for the session.
9. **Full reset** (home settings) wipes keys, vault wraps, remote-signer session and all prefs.
10. **Review demo QR**: scanning `21review:einundzwanzig-meetup-demo` shows a fixed English "Demo scan OK" dialog (QR scanner domain calls into this domain).

No haptics are triggered anywhere in this domain's screens (verified: no `HapticService` calls). No guided-tour targets exist in the identity setup; the settings tour (`guide_set_profile`, settings domain) only points at the profile entry.

---

## 2. Screens & UX flows

Colours used (from `lib/theme.dart`, owned by the UI/theme domain): `cDark #0C0C0E`, `cCard #131315`, `cSurface #0F0F11`, `cOrange #F7931A`, `cCyan #D4A017` (warm gold, despite the name), `cGreen #22C55E`, `cRed #E53935`, `cPurple #9B45E8`, `cText #FFFFFF`, `cTextSecondary #9CA0A8`, `cTextTertiary #868A94`, `cBorder #1C1C22`, `cTileBorder #202028`, `gradientOrange` = linear `#F7931A → #E07810` top-left → bottom-right. Native maps these to the M3 Expressive theme in `:core:ui`; keep the semantic roles (orange = primary action, red = error, green = success, gold = signer/remote actions).

### 2.1 Startup routing (SplashScreen, `lib/main.dart`)

Order in `main()` that matters for this domain:
1. Logger init, locale load (`app_locale`), currency load.
2. `MeetupBadge.loadBadges()` **before anything else touches secure storage** (comment: the flutter_secure_storage v9→v10 re-encryption must not start from several objects in parallel). Native equivalent: run the single **legacy Flutter storage migration** (§4.4) to completion before any repository reads secrets.
3. `BadgeSyncService.init()` → registers `SigningService.onSignerReady = BadgeSyncService.trigger`.
4. `_installNip46AuthUrlHandler()` (§2.8).
5. Splash: `await SecureKeyStore.ensureMigrated()`; `user = UserProfile.load()`; `await delay(800 ms)`; `if (!user.isOnboarded) → IntroScreen else → AppShell` via `pushReplacement` with 500 ms fade.

Splash UI (native: use `androidx.core.splashscreen` + a Compose hold screen): dark background, 88 dp circle with orange glow (alpha 0.2, blur 40) containing a 56 dp orange bolt icon, then a 2 dp orange `CircularProgressIndicator`, whole thing fading in over 800 ms easeOut.

`isOnboarded` = `hasCustomNickname || isNostrVerified || isAdminVerified` (§3.2). A user with a key but nickname "Anon" goes to AppShell; the home screen then pushes IdentitySetupScreen once per load (`home_screen._loadAll`: `if (nickname == 'Anon' || nickname.isEmpty) push(IdentitySetupScreen)`, then reloads user).

### 2.2 IntroScreen (`lib/screens/intro.dart`)

Entry: Splash when not onboarded; Reset (home) via `pushAndRemoveUntil(IntroScreen)`. No arguments.

Layout (Stack, `resizeToAvoidBottomInset = false` — MUST keep: a dialog with keyboard (backup password) must not cause overflow; see test `intro_keyboard_test.dart`):
1. Background `assets/images/intro_background_87.jpg`, `BoxFit.cover`.
2. Black veil alpha 0.52.
3. Ambient glow: positioned top −80, full width, height 500, radial gradient centered top, radius 1.0, `cOrange@0.08 → transparent`.
4. Top-right (SafeArea, padding right 12 top 4): language chip — `cCard` pill radius 20, 0.5 px `cTileBorder` border, padding 12×8, shows flag emoji (de 🇩🇪, en 🇬🇧, es 🇪🇸, null/System 🌐) + `expand_more` icon 16 dp. Tap → language dialog.
5. Centered column:
   - Logo `assets/images/intro_logo_86.png`, width 390, translated x −16; appears at t=400 ms with opacity 0→1 and slide y +0.3→0, 800 ms `easeOutExpo`.
   - 28 dp gap; tagline `introTagline` ("DEINE BITCOIN COMMUNITY") 12 sp, letterSpacing 3.5, w700, `cTextSecondary`; 12 dp gap; 40×2 bar with `gradientOrange`, radius 1. Appears at t=1100 ms, fade 600 ms.
   - 56 dp gap; buttons block (horizontal padding 40) appears at t=1500 ms, fade+slide (y 0.2→0) 500 ms:
     - Primary: full width, height 56, radius 16, `gradientOrange`, shadow `cOrange@0.25` blur 20 offset (0,8). Label `introJoin` ("COMMUNITY BETRETEN") 15 sp w800 letterSpacing 1. While loading: gradient/shadow removed, black 20 dp spinner (stroke 2.5), button disabled.
     - 20 dp gap; TextButton.icon `restore_rounded` 18 dp + `introLoadBackup` ("BACKUP LADEN") 13 sp w700 letterSpacing 1.0, colour `cOrange@0.7`. Disabled while loading.

Actions:
- Language chip → Dialog (`cCard`, radius 16): header row (language icon orange 20 dp + `settingsLanguageChoose` "Sprache wählen" 16 sp w700), divider, options in order: 🌐 "System" (null), 🇩🇪 "Deutsch", 🇬🇧 "English", 🇪🇸 "Español". Selected row: background `cOrange@0.08`, label orange w700, trailing `check_circle_rounded` orange. Tap → `LocaleController.setLocale(value)` (persists `app_locale` = language code, or removes it for System) → close. (Native: `AppCompatDelegate.setApplicationLocales` / per-app language API; the stored pref is owned by settings domain.)
- Primary `_enterCommunity`: set loading; `user = load()`. If `!user.isOnboarded`: show snackbar `introSetIdentity` (background `cOrange`, 3 s), push IdentitySetupScreen, await return, reload user; if still not onboarded → loading false, stay. Otherwise `pushReplacement(AppShell)` with 500 ms fade. (Note: IdentitySetupScreen normally finishes with `pushAndRemoveUntil(AppShell)`, so the "after return" path is only reached when the user closes setup.)
- Secondary `_restoreAccount`: `BackupService.restoreBackup(context)` (backup domain, shows its own dialogs); on `true` → `pushReplacement(AppShell)` with default fade.

### 2.3 IdentitySetupScreen (`lib/screens/identity_setup_screen.dart`)

Entry points: IntroScreen (`_enterCommunity`), HomeScreen when nickname is "Anon"/empty. No arguments. Result: none (navigates itself to AppShell via `pushAndRemoveUntil` on completion; the close button pops).

Constant: `kMinPasswordLength = 8` (deliberately independent from `BackupService.minPasswordLength`, same number).

#### 2.3.1 State

```
step ∈ {choose, neu, resume, passkey, existing, existingMore, nameOnly, meetup}  (start: choose)
busy=false, bootstrapping=true, error: String?=null
passkeySupported=false, path: RecommendedExistingPath = bunker
hasLocalKey, hasPasswordWrap, hasPasskeyWrap, resumeUsePassword=false, offerReviewSignIn=false
meetups: List<Meetup>=[], selectedMeetupCities: List<String>=[], meetupsLoading=false
secureStep: Int = 1          // never reset
revealed: Map<field, Boolean> // per password field show/hide
canResume = hasLocalKey || hasPasswordWrap || hasPasskeyWrap
text fields: name, pass, pass2, import, importPass, resumePass
```

Bootstrap (on init, in this order; all awaited, then one state update):
`path = SigningService.recommendedExistingPath()`, `passkeySupported = PasskeyPrfService.isSupported()`, `hasLocalKey = SecureKeyStore.hasKey()`, `hasPasswordWrap = (LocalKeyVault.getPasswordWrap() ?: "").isNotEmpty()`, `hasPasskeyWrap = LocalKeyVault.hasPasskeyWrap()`, `offerReviewSignIn = AppReviewDemo.shouldOfferSignIn()`, `bootstrapping=false`.

While `busy || bootstrapping` the **whole list absorbs input** (AbsorbPointer). While bootstrapping only a `LinearProgressIndicator` (orange on `cBorder`) and the subtitle `idSetupSubtitle` (centered, 48 dp from top) are shown — MUST keep: prevents "Neu hier" being tapped before detection and overwriting a surviving identity.

#### 2.3.2 Scaffold & chrome

- `extendBodyBehindAppBar`, transparent AppBar, foreground `cText`, title (18 sp w700) per step:
  `choose → idSetupTitle` ("Identität"), `neu → idSetupNewTitle` ("Neu anlegen"), `resume → idSetupResumeTitle` ("Weitermachen"), `passkey → idSetupPasskeyTitle` ("Passkey"), `existing|existingMore → idSetupExistingTitle` ("Verbinden"), `nameOnly → idSetupNameTitle` ("Name wählen"), `meetup → idSetupMeetupTitle` ("Dein Meetup").
- Leading: on `choose` a close (X) button → pop. Otherwise back arrow (disabled while busy):
  - on `meetup` → go home (identity already exists);
  - else: clear error, `resumeUsePassword=false`, clear resume password, then `passkey → neu`, `existingMore|nameOnly → existing`, everything else `→ choose`.
  - Native: system back MUST behave the same (BackHandler).
- Body: ListView padding (24, 8+toolbarHeight, 24, 32). First item when `error != null`: box `cRed@0.12`, radius 10, padding 12, text `cRed` line-height 1.3, then 16 dp gap.

Background (`_buildBackground`, both layers permanently composed, cross-faded 450 ms easeInOut):
- "Bold" steps (no text inputs): `choose, passkey, existing, meetup`. Others are "subtle" (watermark).
- Layer 1: sharp `intro_background_87.jpg` cover.
- Layer 2: same image, colour matrix brightness ×2.2 on RGB (alpha unchanged) then Gaussian blur σ=4; opacity 0 on bold, 1 on subtle.
- Veil: black alpha 0.52 (bold) / 0.55 (subtle).
- Top glow as in Intro, opacity 1.0 bold / 0.7 subtle.
- Native: `Modifier.blur(4.dp)` (API 31+; on 26–30 pre-blur the asset or skip blur), `ColorFilter.colorMatrix` with `setToScale(2.2f,2.2f,2.2f,1f)`, `animateFloatAsState(tween(450, easing = FastOutSlowIn))`.

Shared widgets:
- **Card** (`_card(icon,title,subtitle,onTap,primary)`): Material radius 14; background `cOrange@0.12` if primary else `cCard`; 1 px border `cOrange@0.5` / `cTileBorder`; padding 16; leading icon 28 dp (orange if primary else gold `cCyan`); title 16 sp w700 `cText`; 4 dp; subtitle 13 sp `cTextSecondary` line-height 1.3; trailing chevron `cTextTertiary`.
- **Field** (`_field`): filled `cCard`, radius 12, prefix icon 20 dp `cTextSecondary`, label `cTextSecondary`. Password fields: obscured unless revealed; suffix eye icon toggles **per field**; always single line. Enabled border: `borderColor ?: cTileBorder`, width 1.3 if a borderColor is set else 1. Focused border: `borderColor ?: cOrange`, width 1.6. Every change triggers recomposition (rule list is live).
- **Password border rule** `_pwBorder(ok, touched)`: `touched ? (ok ? cGreen : cRed) : null` where touched = field non-empty.
- **Rule row** `_rule(ok, text)`: top padding 6; icon 15 dp `check_circle` green if ok else `circle_outlined` `cTextTertiary`; text 12 sp line-height 1.35, green if ok else `cTextSecondary`.
- **Primary button** `_primaryButton(label, onPressed?, busy)`: full width, height 52, radius 14; enabled ⇔ `onPressed != null && !busy`; enabled → `gradientOrange` background, black label w800 letterSpacing 0.5; disabled → `cSurface` background, label `cTextTertiary`; busy → black 22 dp spinner (stroke 2.4) instead of the label. (Note Dart quirk: when busy but onPressed non-null, the button is still tappable in theory; the AbsorbPointer prevents it. Native: disable while busy.)

#### 2.3.3 Step `choose`

1. Text `idSetupSubtitle` ("Wie möchtest du starten?") `cTextSecondary` lh 1.4; 24 dp.
2. If `canResume`: Card `restore_rounded`, `idSetupResumeCard` ("Schon auf diesem Gerät") / `idSetupResumeCardSub` ("Vorhandene Identität weiter nutzen"), **primary** → clear error, step=resume; 12 dp.
3. Card `person_add_alt_1_rounded`, `idSetupNewCard` ("Neu hier") / `idSetupNewCardSub`, primary only if `!canResume` → step=neu.
4. 12 dp; Card `link_rounded`, `idSetupExistingCard` ("Schon Nostr") / `idSetupExistingCardSub` → step=existing.
5. If `offerReviewSignIn`: 28 dp + AppReviewSignIn widget (§2.6) with `onSuccess = _finishIfOnboarded`.

Note: "Neu hier" stays available even when `canResume`; registering then **overwrites** the surviving local key (Dart behaviour; see Risks). Native MAY add a confirmation dialog in that case — this is a UX-only deviation.

#### 2.3.4 Step `neu`

1. `idSetupNewHint` text; 20 dp.
2. Field name (`badge_outlined`, label `idSetupNameLabel` "Name"); 12 dp.
3. Field password (`lock_outline`, `idSetupPasswordLabel` "Passwort für deinen Schlüssel", obscure, border = `_pwBorder(len ≥ 8, pass non-empty)`); 12 dp.
4. Field confirm (`lock_outline`, `idSetupPasswordConfirmLabel`, obscure, border = `_pwBorder(pass non-empty && pass == pass2, pass2 non-empty)`); 4 dp.
5. Rules: `backupPwRuleLength(8)` ("Mindestens 8 Zeichen — …"), `backupPwRuleMatch`.
6. 10 dp; row: `key_outlined` orange 16 dp + `idSetupPasswordWarn` (12 sp lh 1.4).
7. 24 dp; primary `idSetupCreate` ("Loslegen"), enabled only when `pwOk = len ≥ 8 && pass non-empty && pass == pass2`; busy spinner.

`_register()`:
- name = trimmed; if empty or exactly `"Anon"` (case-sensitive) → error `idSetupNameRequired`. If `pass.length < 8` → `idSetupPasswordShort`. If mismatch → `keyExportMismatch`. (Length = UTF-16 code units in Dart; native `String.length` is identical.)
- busy=true, error=null → `LocalEasyAuth.register(nickname, password)` (§8.3).
- Success: if `passkeySupported` → busy=false, step=passkey; else `_offerBackupThenHome()`.
- Error: busy=false, error = exception message (`LocalEasyAuthException.message` or raw).

#### 2.3.5 Step `resume`

Text (lh 1.4): `hasLocalKey → idSetupResumeHasKey`; else if `hasPasskeyWrap && !resumeUsePassword → idSetupResumePasskeyHint`; else `idSetupResumePasswordHint`. 24 dp, then:
- `hasLocalKey`: primary `idSetupResumeContinue` ("Weitermachen") → `_resume()`.
- else if `hasPasskeyWrap && !resumeUsePassword`: primary `idSetupResumePasskey` ("Mit Passkey entsperren") → `_resume(usePasskey=true)`; if also `hasPasswordWrap`: 8 dp + TextButton `idSetupResumePassword` ("Stattdessen Passwort benutzen") → error=null, resumeUsePassword=true.
- else: password field (`idSetupPasswordLabel`, obscure, **no rule checks** — an existing password may be shorter), 16 dp, primary `idSetupResumeContinue`.

`_resume(usePasskey)` (guard: ignore if busy):
1. busy=true, error=null.
2. If `hasLocalKey` → `SigningService.useLocalMode()` (no password needed — key is already in the store).
3. else if usePasskey → `LocalEasyAuth.unlockWithPasskey()`.
4. else: if resume password empty → busy=false, error `idSetupResumeNeedPassword`, return; else `LocalEasyAuth.unlockWithPassword(pass)`.
5. Clear resume password; `_finishIfOnboarded()`; busy=false.
6. On any error: busy=false; error = `usePasskey ? passkeyErrorText(e) : idSetupResumeWrongPassword` (every password-path failure, including "no wrap", shows "Das Passwort passt nicht zu diesem Schlüssel.").

`passkeyErrorText(e)` (`tPasskeyError`): if `e.toString()` contains any of `PRF_UNSUPPORTED`, `DomainNotAssociated`, `RP_ID_NOT_ALLOWED`, `TYPE_NOT_SUPPORTED_ERROR`, `algorithms are supported`, `NoCreateOption`, `android-no-create-option`, `android-unhandled` → `idSetupPasskeyUnavailable`; else the raw string. Native: map typed Credential Manager exceptions instead (§8.4) — `CreateCredentialNoCreateOptionException`, `CreateCredentialUnsupportedException`, `GetCredentialUnsupportedException`, `NoCredentialException`, DOM errors `NotSupportedError`/`SecurityError` (RP ID/asset-links), PRF missing → `idSetupPasskeyUnavailable`; user cancellation (`*CancellationException`) → MAY show nothing (Dart showed the raw platform string).

#### 2.3.6 Step `passkey`

`idSetupPasskeyBody`; 24 dp; primary `idSetupPasskeyAction` ("Mit Passkey sichern", busy spinner) → `_addPasskey()`; 8 dp; TextButton `idSetupPasskeyLater` ("Später") → `_offerBackupThenHome()`.

`_addPasskey()`: busy=true, error=null → `LocalEasyAuth.addPasskeyWrap(displayName = name.trim())` → on success `_offerBackupThenHome()`; on error busy=false, error=`passkeyErrorText(e)` (user stays and can tap "Später").

Back from `passkey` returns to `neu` — tapping "Loslegen" again would generate a **second, different** key and overwrite the first (Dart quirk, see Risks). Native SHOULD instead treat back on `passkey` as "Später" or disable re-registration; document whichever is chosen.

#### 2.3.7 "Secure your identity" sheet (`_offerBackupThenHome`)

Shown only if a password wrap exists (always true right after `register`). Then always continues with `_continueAfterIdentity()`.

Modal bottom sheet: background `cCard`, top radius 16, `isDismissible=false`, `enableDrag=false` (only the buttons close it), SafeArea + scroll, padding (20,18,20,24), content stretched. Internal state `secureStep` (1 or 2):

Header row: icon (step1 `save_alt_rounded`, step2 `key_rounded`) orange 22 dp; title 18 sp w700 (`idSetupSecureBackupTitle` "Backup-Datei" / `idSetupSecureKeyTitle` "Verschlüsselter Schlüssel"); trailing progress text "1/2"/"2/2" 12 sp w700 `cTextTertiary`.
Body 13.5 sp lh 1.45 `cTextSecondary`: `idSetupSecureBackupBody` / `idSetupSecureKeyBody`. 16 dp.
- Step 1 only: info box `cOrange@0.08` radius 10 padding 12: `update_rounded` orange 16 dp + `idSetupSecureRepeat` (12 sp lh 1.45).
- Step 2 only: row `info_outline_rounded` 15 dp `cTextTertiary` + `idSetupSecureWhere` (11.5 sp lh 1.4 tertiary).
18 dp; primary ElevatedButton.icon (orange background, black, vertical padding 14, icon `save_alt_rounded` 18 dp, label w800):
- Step 1: `idSetupSecureBackup` ("Backup erstellen") → `BackupService.createBackup(context)` (backup domain; result ignored) → secureStep=2.
- Step 2: `idSetupSecureKeySave` ("Als Datei speichern") → `_saveKeyFile(wrap)` → close sheet.
Step 2 only: 8 dp + OutlinedButton.icon (`copy_rounded`, border `cTileBorder`, vertical padding 13) `idSetupSecureCopy` ("Schlüssel in die Zwischenablage") → plain clipboard copy of the wrap (**no** 45 s auto-clear here, unlike profile export), close sheet, snackbar `keyExportCopied`.
4 dp; TextButton: step 1 `idSetupSecureSkip` ("Überspringen") → secureStep=2 (skipping step 1 leads to step 2, not out); step 2 `idSetupBackupLater` ("Später") → close.

`_saveKeyFile(wrap)`:
- fileName = `Nostr-Schluessel_${yyyy}-${MM}-${dd}_21MeetupApp.txt` (local date, zero-padded).
- content = `"${idSetupSecureFileHeader}\n\n${wrap}\n"` UTF-8 (header string is localized, so the file language follows the UI).
- `FilePicker.saveFile(dialogTitle: idSetupSecureKeySave, fileName, bytes)`. Native: `ActivityResultContracts.CreateDocument("text/plain")` with the suggested name, write bytes via `ContentResolver.openOutputStream`.
- Cancelled → nothing. Success → green snackbar `idSetupSecureKeySaved`. Exception → copy wrap to clipboard + snackbar `keyExportCopied`.

#### 2.3.8 Steps `existing` / `existingMore`

`existing`: primary card depends on `path`:
- `nip07` → (`extension_rounded`, `idSetupPrimaryNip07`, `idSetupPrimaryNip07Sub`) — never on Android.
- `amber` → (`phone_android_rounded`, `idSetupPrimaryAmber` "Amber", `idSetupPrimaryAmberSub` "In Amber bestätigen").
- `bunker` → (`vpn_key_rounded`, `idSetupPrimaryBunker` "Signer verbinden", `idSetupPrimaryBunkerSub` "Bunker / Clave / Amber").
Tap → `_connectPrimary()`. 12 dp. Card `alt_route_rounded` `idSetupOtherWay` / `idSetupOtherWaySub` → step=existingMore.

`recommendedExistingPath()` on Android: `amber` if the Amber package is installed, else `bunker`.

`_connectPrimary()`: busy=true, error=null, then by path:
- `amber`: `SigningService.connectAmber()`; anything but success → error `idSetupConnectFailed` (Dart displays `"Exception: Verbindung fehlgeschlagen."` because it throws `Exception(msg)` and shows `toString()`; native SHOULD show just the localized text).
- `bunker`: open BunkerConnectSheet (§2.5); `null` → busy=false, return silently.
- `nip07`: (web only).
On success → busy=false, `_finishIfOnboarded()`. On exception → busy=false, error = message.

`existingMore` (form page):
1. `idSetupImportHint`; 12 dp; field (`key_outlined`, `idSetupImportLabel` "Schlüssel", maxLines 3, not obscured); 12 dp; field (`lock_outline`, `idSetupImportPasswordLabel` "Passwort (nur bei ncryptsec)", obscure); 16 dp; primary `idSetupImportAction` ("Importieren", busy) → `_importKey()`; 20 dp.
2. If `path != bunker`: card (`vpn_key_rounded`, `idSetupPrimaryBunker`/`Sub`) → BunkerConnectSheet; non-null → `_finishIfOnboarded()`. 12 dp.
3. (Web only: NIP-07 card.)
4. If Android and `path != amber`: card (`phone_android_rounded`, `idSetupPrimaryAmber`/`Sub`) → set `path = amber` (persisting for this screen instance — the `existing` step then shows Amber as primary) → `_connectPrimary()`. Shown even if Amber is not installed (connect then fails with `idSetupConnectFailed`).
5. TextButton.icon `restore_rounded` orange 18 dp + `introLoadBackup` (orange w700) → `BackupService.restoreBackup(context)`; true → go home.

`_importKey()`:
- (Web-only guard irrelevant on Android.)
- raw = trimmed input; empty → error `idSetupImportEmpty`.
- busy=true, error=null.
- If `raw.startsWith("ncryptsec1")` (case-sensitive): password empty → error `idSetupImportNeedPassword`; else `NostrService.importNcryptsec(raw, pass)` (§8.2).
- Else `NostrService.importNsec(raw)` (requires `nsec1` prefix).
- Then `LocalKeyVault.clearAll()` (old EasyAuth wraps belong to the previous identity), `SigningService.useLocalMode()`, busy=false, `_finishIfOnboarded()`.
- Error: busy=false, error = exception text (e.g. Nip49Exception "Falsches Passwort — oder der Schlüssel ist beschädigt.", FormatException `Ungültiges Format. Der Key muss mit "nsec1" beginnen.` — Dart shows `"FormatException: …"`; native SHOULD drop the type prefix).
- Note: import does **not** create a password wrap.

#### 2.3.9 Step `nameOnly`

`idSetupNameOnlyHint`; 20 dp; name field (shared controller with `neu`); 24 dp; primary `idSetupContinue` ("Weiter", busy) → `_saveNameOnly()`: name trimmed, empty or `"Anon"` → `idSetupNameRequired`; busy=true; load profile; set nickname; save; `_continueAfterIdentity(user)`.

#### 2.3.10 Step `meetup`

`_finishIfOnboarded()`: load profile; `!hasCustomNickname` → step=nameOnly; else `_continueAfterIdentity`.
`_continueAfterIdentity(user)`: `hasMeetup = homeMeetupId.trim().isNotEmpty || favoriteMeetupIds.isNotEmpty` → go home; else `_enterMeetupStep()`.
`_goHome()`: `pushAndRemoveUntil(AppShell)` (clears Intro and setup from the back stack).

`_enterMeetupStep()`: busy=false, error=null, step=meetup, loading=true, selected=[] → `MeetupService.fetchMeetups()` (meetups domain; 15 s timeout). Empty list or exception → error `idSetupMeetupLoadError`, loading=false.

UI: `idSetupMeetupHint`; 20 dp; then
- loading → text `idSetupMeetupLoading`;
- meetups empty → TextButton `idSetupMeetupLater` → go home;
- else: primary card (`place_outlined`, `idSetupMeetupPick`, subtitle = `selected.join(", ")` or `homeMeetupChooseSub` if none) → MeetupSearchSheet (meetups domain widget; modal sheet `cCard`, top radius 20, scroll-controlled; `initialSelected = selected`; `onDone(favs: List<String>)` = selected **city names**); 24 dp; if any selected → primary `idSetupMeetupContinue` → `_saveMeetupAndHome()`; TextButton `idSetupMeetupLater` → go home.

`_saveMeetupAndHome()`: requires non-empty selection; busy=true; `favoriteMeetupIds = selected`; `homeMeetupId = selected.first`; save; go home.

#### 2.3.11 Unused ARB keys (exist, not referenced by current UI)

`idSetupBackupTitle`, `idSetupBackupBody`, `idSetupBackupCopy`, `idSetupSecureTitle`, `idSetupSecureBody` — keep in resources (arb2xml generates them) but no UI needed.

### 2.4 Navigation summary

```
Splash ──!onboarded──▶ Intro ──join──▶ IdentitySetup ──done──▶ AppShell (clear stack)
   │                     └──restore ok──▶ AppShell
   └──onboarded──▶ AppShell ──nickname "Anon"──▶ IdentitySetup (push)
Home/Radar ──▶ ProfileEditScreen (key management section, §2.7)
Home reset ──▶ wipe (§8.9) ──▶ Intro (clear stack)
```

### 2.5 BunkerConnectSheet (`lib/screens/bunker_connect_sheet.dart`)

Entry: IdentitySetup (`existing`/`existingMore`), Profile (`_connectBunker`). API: `show(context): Nip46ConnectSuccess?` (modal bottom sheet, scroll-controlled, background `#1A1A1A`, top radius 16). Returns the success on pairing, `null` otherwise.

State: `step ∈ {choose, signerApp, paste}` (start choose), `busy`, `error`, `pairing: Nip46Pairing?`, `authUrl: String?`, `previousAuthUrlHandler`.

Lifecycle:
- On open: remember `SigningService.onNip46AuthUrl`, replace it with `{ url -> authUrl = url }` (shown inline as a button).
- On close/dispose: restore the previous handler (MUST NOT set null — the global handler from §2.8 would be lost); dispose controller; `pairing?.cancel()` (closes relay sockets of an unfinished pairing).
- Back/swipe blocked while `busy` (`PopScope(canPop: !busy)`; native: `BackHandler(enabled = busy) {}` + `sheetState` confirmValueChange returns `!busy`).

Layout (padding 20, bottom += IME inset, scrollable):
1. Row: `lock_outline` gold 20 dp + `bunkerTitle` ("Mit Remote-Signer verbinden") white 16 sp bold.
2. 8 dp; `bunkerIntro` grey 12 sp; 16 dp.
3. Step content:
   - **choose**: option tile (`qr_code_2`, gold, `bunkerModeSigner` / `bunkerModeSignerDesc`) → `_startSignerApp()`; 10 dp; option tile (`content_paste`, green, `bunkerModePaste` / `bunkerModePasteDesc`) → step=paste, error=null; 14 dp; recommendation box (`cCyan@0.08`, radius 8, padding 10, `lightbulb_outline` gold 16 dp + text grey 11 sp: Android → `bunkerRecommendAndroid`); 12 dp; right-aligned TextButton `dialogCancel` → close.
     Option tile: InkWell radius 8 (disabled while busy), padding 12, border `color@0.5`, icon 22 dp, title colour 13 sp bold, subtitle grey 11 sp.
   - **signerApp**: if `pairing == null` → centered gold spinner (padding 24). Else: white rounded box (radius 8, padding 10) with QR code of `pairing.uri`, size 200, white background; 12 dp; `bunkerScanHint` grey 12 sp; 10 dp; row of two outlined buttons: (`copy`, grey, `bunkerCopy`) → clipboard (plain) + snackbar `bunkerCopied`; (`open_in_new`, gold, `bunkerOpenSigner`) → `_openSignerApp()`. If busy: 16 dp + waiting row. 8 dp; right-aligned TextButton `dialogCancel` → `_cancel()`.
   - **paste**: TextField (enabled unless busy, 1–3 lines, white 12 sp, label `bunkerPasteLabel`, hint `bunkerPasteHint` "bunker://…?relay=wss://…" white24 11 sp, outline white24 / focused green). If busy: waiting row. 12 dp; row: TextButton `bunkerBack` (disabled while busy) → step=choose, error=null; spacer; ElevatedButton green `bunkerConnect` (black bold; **disabled while busy** — double tap would start a second pairing) → `_connectPasted()`.
4. If error: 12 dp + red box (`red@0.12`, radius 8, padding 10, text redAccent 12 sp).
5. If authUrl: 12 dp + full-width outlined orange button (`open_in_new`, `bunkerAuthOpen`) → `launchHttpUrl(authUrl)`; failure → warn log only (no UI).

Waiting row: 16 dp gold spinner (stroke 2) + column (`bunkerWaiting` gold 12 sp, `bunkerWaitingHint` grey 11 sp).

Flows:
- `_startSignerApp()` (ignored if busy): busy=true, error=null, authUrl=null, step=signerApp → `pairing = SigningService.startNip46Pairing()` (if the sheet is gone meanwhile → `pairing.cancel()`) → `result = SigningService.completeNip46Pairing(pairing)` (waits ≤ 120 s + 75 s) → `_handle(result)`. Exception → busy=false, error = toString.
- `_connectPasted()` (ignored if busy or input empty): busy=true, error=null, authUrl=null → `SigningService.connectNip46Bunker(input)` → `_handle`.
- `_handle(result)`: Success → **set `pairing = null` first** (the service adopted that client as the live session; cancelling it on dispose would kill the session just created) → close sheet returning the result. Timeout → busy=false, error `bunkerTimeout`. Error(message) → busy=false, error=message (raw service text, see §8.10).
- `_cancel()`: take pairing, set null, `cancel()` it, close sheet (returns null).
- `_openSignerApp()`: `startActivity(ACTION_VIEW, Uri.parse(pairing.uri))` (external app); if it fails (no handler / exception) → snackbar `bunkerNoSignerApp`.

### 2.6 AppReviewSignIn widget (`lib/widgets/app_review_sign_in.dart`)

Only rendered when `AppReviewDemo.shouldOfferSignIn()` (Android native: `BuildConfig.DEBUG || BuildConfig.APP_REVIEW_SIGNIN`; Dart: `kDebugMode`, `--dart-define=APP_REVIEW_SIGNIN=true`, or iOS TestFlight — always false on Android release). Hard-coded English (no ARB keys):
- "App Review" (11 sp w700 tertiary, letterSpacing 1.2, centered); 6 dp; "Sign in" (14 sp w800, ls 0.5); 10 dp.
- Field "User Name" (not obscured), 8 dp, field "Password" (obscured); both: no autocorrect, no suggestions, filled `cCard`, radius 12, padding 14×12, focused border orange.
- Error text (red 12 sp centered) if any; 12 dp; button height 44 (`cCard` background, orange 1 px border, label "Sign in" w800 or 18 dp orange spinner while busy).
- Submit: `AppReviewDemo.signIn(user, pass)`; success → `onSuccess()` (= `_finishIfOnboarded`); error → message.

### 2.7 Profile key management (embedded in ProfileEditScreen)

ProfileEditScreen itself (nickname, meetups, proofs, read-only/verified mode) belongs to the profile domain; the **identity section and all its dialogs are specified here** and must be reused there. Entry: Home settings menu → Profile, Radar → Profile.

Loaded state (`_loadData`): `hasKey = SecureKeyStore.hasKey()`, `npub = SigningService.npub()`, `isAmber = mode==amber` (Android always supported), `isNip07`, `nip07Available` (Android: always false), `isNip46`, `canSign = SigningService.canSign()`. Derived: `hasNostrKey = hasKey || isAmber || isNip07 || isNip46`; `nostrNpub = npub ?: user.nostrNpub`; `showAmberOption = true on Android`.

Error snackbar rule (`_showError`): red, **10 s**, close icon (messages carry long signer reasons).

#### 2.7.1 Edit form — "NOSTR SCHLÜSSEL" section

Header `profileNostrKey` (purple bold), `profileKeyDesc` (grey 12 sp), 12 dp.

**If `hasNostrKey`** — card (`cCard`, radius 12, padding 16, border `green@0.5`):
1. Row `check_circle` green 18 dp + `profileKeyActiveCaps` ("SCHLÜSSEL AKTIV") green bold 12 sp.
2. 12 dp; npub box (`cDark`, radius 8, padding 10, monospace 11 sp, max 2 lines, ellipsis).
3. 12 dp; row: OutlinedButton.icon (`copy` 16 dp, "NPUB", gold) → clipboard (plain) + snackbar `profileNpubCopied` (orange, 1 s). If **local** mode (`!isAmber && !isNip07 && !isNip46`): 8 dp + OutlinedButton.icon (`key`, "NSEC", orange) → `_showExistingNsec()`.
4. If local mode: 8 dp + full-width OutlinedButton.icon green `keyExportEncrypted` ("VERSCHLÜSSELT EXPORTIEREN (ncryptsec)"), icon = 14 dp green spinner while exporting else `lock_outline`; disabled while exporting → `_exportNcryptsec()`.
5. Signer management section (§2.7.3).

Note "local mode" is derived from the **mode**, not from key existence: in the mixed state (local key present + external signer active) NSEC/export buttons are hidden.

**Else** (no identity) — card (`cCard`, border `cBorder`): `key_off` grey 32 dp; `profileNoKey`; 16 dp;
- ElevatedButton.icon purple (vertical padding 14): icon `add_circle_outline` or 16 dp white spinner; label `profileCreateNewKey` / `profileCreating` while generating → `_generateNostrKey()`.
- Amber (Android): OutlinedButton.icon gold `shield_outlined` `profileConnectAmber` → `_connectAmber()`.
- OutlinedButton.icon gold `lock_outline` `profileConnectBunker` → `_connectBunker()` (unconditional, every platform).
- (NIP-07 button only if extension available — never on Android.)
- OutlinedButton.icon orange `download` `profileImportNsec` → `_importNsec()`.
- 12 dp; `profileNoNostrNeeded` grey 11 sp centered.
All except import are disabled while `isGeneratingKey`.

#### 2.7.2 Read-only (verified) view

If `hasNostrKey`: info tile ("Nostr", `NostrService.shortenNpub(npub)` = first 12 chars + "..." + last 8 chars when length ≥ 21) and the signer management section (it changes no profile data, so it must not hide behind "BEARBEITEN (Status verlieren)").

#### 2.7.3 Signer management section

1. If `isNip46`: 8 dp + full-width outlined gold button (`wifi_tethering` or 14 dp spinner while checking) `bunkerCheck` ("VERBINDUNG PRÜFEN") → `_checkBunkerSession()`. Never automatic (costs a relay round trip).
2. `offerAgain = !canSign`. Options:
   - Bunker if `!isNip46 || offerAgain` (`lock_outline`, gold, `profileConnectBunker`) → `_connectBunker`
   - Amber if Android && `(!isAmber || offerAgain)` (`shield_outlined`, gold, `profileConnectAmber`) → `_connectAmber`
   - NIP-07 if extension available (never on Android).
   - `canDisconnect = isAmber || isNip07 || isNip46`.
   - If no options and cannot disconnect → render nothing.
3. Else: 16 dp, divider `cBorder`, 12 dp; if options: heading `profileSwitchSignerHeading` ("Anderen Signer verbinden", white bold 12 sp), 4 dp, hint = `offerAgain ? profileSignerUnusable` (orange) `: profileSwitchSignerHint` (grey) 11 sp, 10 dp; each option button + 8 dp; if canDisconnect: button (`link_off`, orange, `profileDisconnectSigner`) → `_disconnectSigner()`.
   Switch buttons: full width outlined, icon 16 dp, label 11 sp, vertical padding 10, disabled while `isGeneratingKey`.

Why `offerAgain`: after restoring a bunker backup the mode is `nip46` but no session key exists; without re-offering the active type the user would be stuck.

#### 2.7.4 Actions & dialogs

- **`_confirmSignerSwitch()`** (before any connect when `hasNostrKey`): AlertDialog `cCard`, title `profileSwitchSignerTitle`, body `profileSwitchSignerBody`, actions `dialogCancel` (grey) / gold ElevatedButton `profileSwitchSignerContinue` (black bold). Dismiss = cancel. The previous key is **never deleted** by connecting a signer.
- **`_warnIfIdentityChanged(prev, new)`**: if prev non-empty and differs → orange snackbar `profileIdentityChanged`, 6 s.
- **`_connectAmber()`**: confirm switch → generating=true → `SigningService.connectAmber()`:
  - Success → state (hasKey, isAmber, others false, canSign=true, npub), green snackbar `profileAmberConnected`, identity-changed warning.
  - Missing → AlertDialog title `profileAmberNotFound`, content `profileAmberDesc + profileAmberInstall + profileAmberRetry` (concatenated sentence fragments), action "OK" (orange).
  - Cancelled → snackbar `profileAmberAborted`.
  - Error(msg) → `_showError(errorAmber(msg))`.
  - Exception → `_showError(errorGeneric(e))`.
- **`_connectBunker()`**: confirm switch → BunkerConnectSheet → success: state (isNip46, canSign, npub), green snackbar `bunkerConnected`, identity-changed warning.
- **`_disconnectSigner()`**: AlertDialog title `profileDisconnectTitle`, body `profileDisconnectBody`, actions `dialogCancel` / orange ElevatedButton `profileDisconnectSigner`. Confirm → `disconnectNip46()` / `disconnectNip07()` / `disconnectAmber()` by current mode; error → `_showError(errorGeneric)`; success → reload all, green snackbar `profileDisconnectDone`.
- **`_checkBunkerSession()`**: checking=true → `SigningService.nip46SessionAlive()` → snackbar `bunkerAlive` (green, 3 s) or `bunkerDead` (red, 10 s, close icon). Texts deliberately say "ping works" ≠ "signing rights exist".
- **`_generateNostrKey()`**: show key-education dialog (below); if confirmed → generating=true → `NostrService.generateKeyPair()` (§8.1) → `useLocalMode()` → `LocalKeyVault.clearAll()` → state local → device authentication with reason "Bitte Identität bestätigen, um den Schlüssel anzuzeigen" → if ok: `_showNsecBackupDialog(nsec)`. Errors → `_showError(errorGeneric)`. (No password wrap is created on this path.)
- **Key education dialog**: Dialog `cCard` radius 18, inset (20,40). Header: 42×42 box `cOrange@0.15` radius 12 with `key_rounded` orange 22 dp + `keyEduTitle` 18 sp w800. Scrollable body: `keyEduIntro` (13.5 sp lh 1.55), sections (icon 18 dp + title 15 sp w700 + body 13 sp lh 1.55, 16 dp bottom): (`public_rounded`, gold, `keyEduWhatNostrH/B`), (`vpn_key_rounded`, orange, `keyEduPairH/B`), (`badge_outlined`, green, `keyEduNpubH/B`), highlighted red (`warning_amber_rounded`, `keyEduNsecH/B`; highlight = container `red@0.08`, radius 12, 0.5 px border `red@0.35`, title in red), (`hub_rounded`, gold, `keyEduIdentityH/B`); then row `shield_rounded` orange + `keyEduProtectH` (15 sp w700), 4 checklist rows (`check_circle_rounded` green 16 dp + text 13 sp lh 1.45): `keyEduProtect1..4`. Buttons: full-width orange ElevatedButton.icon (`check_rounded` white) `keyEduUnderstood` → true; TextButton `keyEduCancel` → false. (Web-only `webKeyWarn*` section omitted on Android.)
- **`_showNsecBackupDialog(nsec)`**: `ScreenSecure.set(true)` (FLAG_SECURE) → non-dismissible AlertDialog `cCard`: title row `warning_amber` orange 24 dp + `profileSecureKey` orange bold 16 sp; content `profileSaveKeyDesc + profileWhoHasKey` (white70 13 sp), 16 dp, selectable monospace 11 sp orange nsec in a `cDark` box with `orange@0.5` border, 16 dp, `profileKeyNotShownAgain` red bold 12 sp. Actions: orange ElevatedButton.icon (`copy`) `profileCopy` → `ScreenSecure.copySecret(nsec)` (45 s conditional clear) + orange snackbar `profileNsecCopied`; TextButton green bold `profileKeySecured` → close. On close (any way) → `ScreenSecure.set(false)`.
- **`_showExistingNsec()`**: `keys = SecureKeyStore.loadKeys()` (null unless both nsec and npub exist) → confirm AlertDialog (title `profileShowNsecQ` red bold, body `profileShowNsecWarn`, actions `dialogCancel` / red `profileShow`) → device authentication (same reason) → nsec dialog.
- **`_importNsec()`**: AlertDialog title `profileImportNsecShort`; content: `profileEnterNsec` (white70 13 sp), 16 dp, obscured single-line TextField monospace 13 sp hint "nsec1..." (filled `cDark`, orange border), 8 dp, `profileNsecNeverLeaves` orange 11 sp. Actions: `dialogCancel`; orange `profileImport` → trimmed empty → ignore; `NostrService.importNsec` → `useLocalMode()` → `LocalKeyVault.clearAll()` → close dialog → state local + npub → green snackbar `profileKeyImported`; error → `_showError("$e")` (dialog stays open).
- **`_exportNcryptsec(forceNewPassword=false)`**: guard `exporting` (set **immediately**, before device auth, to block parallel scrypt runs of 64 MiB each) → device authentication with reason "Bitte Identität bestätigen, um den Schlüssel zu exportieren" (false → abort) → `privHex = SecureKeyStore.getPrivHex()`; empty → `_showError(keyExportNoKey)`. Fast path: unless forced, if `LocalKeyVault.getPasswordWrap()` non-empty AND `npub` non-null AND `passwordWrapMatchesNpub(npub)` → show export sheet with `fromVault=true` (no scrypt). Else prompt password dialog → `delay(50 ms)` (let spinner paint) → `Nip49.encrypt(privHex, password, keySecurity = INSECURE (0x00), logN = 16)` → export sheet. Nip49Exception → `_showError(errorGeneric(message))`. Always reset `exporting`.
- **Export password dialog** (`_promptExportPassword`): non-dismissible; title `keyExportTitle`; `keyExportDesc` (grey 12 sp); password field (obscure, autofocus, label `backupPassword`, errorText); confirm field (`backupPasswordConfirm`); `keyExportDuration` (orange 11 sp). Actions: `dialogCancel` → null; green `keyExportAction`: empty → errorText `backupPasswordEmpty`; mismatch → `keyExportMismatch`; else return password. No minimum length here.
- **Export sheet** (`_showNcryptsecSheet`): FLAG_SECURE on while open; bottom sheet `cCard` top radius 16, padding 20: row `lock_outline` green + `keyExportReadyTitle` (white 15 sp bold); `keyExportReadyBody` grey 12 sp; if fromVault: `keyExportFromVault` green 12 sp; selectable monospace 11 sp ncryptsec in `cDark` box; green full-width ElevatedButton.icon (`copy`, black) `keyExportCopy` → `copySecret` (45 s clear) → close → green snackbar `keyExportCopied`; if fromVault: TextButton grey 12 sp `keyExportOtherPassword` → close → `_exportNcryptsec(forceNewPassword=true)`.

### 2.8 Global NIP-46 auth_url handler (`lib/main.dart`)

`SigningService.onNip46AuthUrl = { url -> … }` installed at startup. If no UI is attached → warn-log **host only** ("Freigabe-Aufforderung … (Host: <host>)"). Else snackbar: text `bunkerAuthNeeded`, background orange, duration 60 s, close icon, action `bunkerAuthAction` ("ÖFFNEN", black) → `launchHttpUrl(url)`; on false → warn log with host only. Native: an app-scoped `SnackbarHostState`/event bus collected in the root scaffold; the BunkerConnectSheet temporarily overrides it (§2.5).

### 2.9 Diagnose log NIP-44 probe (log screen, diagnostics domain)

Key icon button (tooltip "NIP-44-Verbindung prüfen (Freigabe im Signer eventuell nötig)") → `SigningService.canNip44()` → snackbar "NIP-44-Probe erfolgreich." / "NIP-44-Probe fehlgeschlagen — Ursache im Diagnose-Log." (hardcoded German). Disabled while probing.

### 2.10 Device integrity banner (home domain consumer)

`DeviceIntegrityService.check()` once per home load; if `isCompromised` → card (`cCard`, radius 10, 0.5 px `cOrange@0.3` border, padding 12): `warning_amber_rounded` orange 18 dp + `DeviceIntegrityService.warningMessage` (orange.shade200, 11 sp) + close icon (16 dp) → hide for this screen instance (not persisted).

### 2.11 Review demo success dialog

`AppReviewDemo.showSuccess()`: AlertDialog `cCard`, title "Demo scan OK" (w700), body "This is how participants collect a meetup badge: they scan the organizer QR on site. Live codes are cryptographically signed and expire within seconds so a photo cannot be reused. This demo code does not issue a real badge." (`cTextSecondary`, lh 1.4), action "OK" (orange). English only by design.

---

## 3. Data models

### 3.1 SignedEvent (Dart `SignedEvent`)

| Field | Type | JSON key |
|---|---|---|
| id | String (64 lowercase hex) | `id` |
| pubkey | String (64 hex) | `pubkey` |
| createdAt | Int (unix seconds) | `created_at` |
| kind | Int | `kind` |
| tags | List<List<String>> | `tags` |
| content | String | `content` |
| sig | String (128 hex) | `sig` |

`toJson()` key order: `id, pubkey, created_at, kind, tags, content, sig`. `toEventMessage()` = `["EVENT", <toJson>]` (no subscription id). For signer results: `kind` and `content` are the caller's values (verified equal), `tags`/`created_at`/`id`/`sig` come from the signer.

### 3.2 UserProfile (`lib/models/user.dart`)

| Field | Type | Default | Prefs key (save) | Notes |
|---|---|---|---|---|
| nickname | String | `"Anon"` | `nickname` | |
| fullName | String | `""` | `full_name` | |
| telegramHandle | String | `""` | `telegram` | |
| nostrNpub | String | `""` | `nostr` | load precedence below |
| twitterHandle | String | `""` | `twitter` | |
| isNostrVerified | Boolean | false | `nostr_verified` | load: `hasKey || externalSigner || pref` |
| isAdminVerified | Boolean | false | `admin_verified` | |
| isAdmin (derived) | Boolean | — | `is_admin` | `adminViaPortal || adminViaVouch || adminViaSeed`; setter sets/clears `adminViaVouch` |
| homeMeetupId | String | `""` | `home_meetup` | portal meetup ID of the first favorite (widget routing); older installs may still hold a city name (legacy form, mapped to an ID by the picker migration, spec 09 §8.4; the App Review demo writes `'Berlin'`) |
| favoriteMeetupIds | List<String> | `[]` | `favorite_meetups` (string list) | portal meetup IDs (legacy installs: city names, migrated by spec 09 §8.4 when the picker is opened and saved; the Dart comment "Stadtnamen" in models/user.dart:16 is stale). Load fallback: `[home_meetup]` if list missing and home non-empty |
| hasNostrKey | Boolean | false | **not persisted** | load: `SecureKeyStore.hasKey()` (local nsec present; false in pure external modes) |
| promotionSource (derived) | String | `""` | `promotion_source` | getter priority: `seed_admin` > `portal_organizer` > `trust_score` > `""`; setter maps one value to one flag |
| adminViaPortal | Boolean | false | `admin_via_portal` | |
| adminViaVouch | Boolean | false | `admin_via_vouch` | |
| adminViaSeed | Boolean | false | `admin_via_seed` | |
| isReviewDemo | Boolean | false | `review_demo` | cosmetic; never an admin source |
| adminCryptoVerified | Boolean | false | in-memory only | set true by `reVerifyAdmin` (admin domain) |

Derived:
- `hasCustomNickname = nickname.trim().isNotEmpty() && nickname.trim() != "Anon"` (case-sensitive).
- `isVerified = isNostrVerified || isAdminVerified`.
- `isOnboarded = hasCustomNickname || isVerified`.

Constructor compatibility: `UserProfile(isAdmin, promotionSource, …)`: if `promotionSource` non-empty → `this.promotionSource = it` (sets the one flag); else if `isAdmin` → `adminViaVouch = true`.

`load()` exact order:
1. `hasKey = SecureKeyStore.hasKey()`.
2. `npub = prefs["nostr"] ?: ""`; if hasKey and keystore npub non-empty → keystore npub.
3. `externalSigner = SigningService.isExternalSigner`; if true and `SigningService.npub()` non-empty → that npub.
4. Construct with prefs values (`is_admin`, `promotion_source` through the constructor logic).
5. If `prefs.containsKey("admin_via_portal")` → read all three `admin_via_*` (missing → false), overriding.
6. `isReviewDemo = prefs["review_demo"] ?: false`.

`save()` writes **all** keys above (including derived `is_admin`, `promotion_source`, and all three flags, `review_demo`). `favorite_meetups` is written as a string list.

`reVerifyAdmin(badges)` (admin domain logic, kept here because it mutates this model): runs `AdminStatusVerifier.verifyAdminStatus`; if `source == "seed_admin"` → `adminViaSeed = isAdmin`; else `adminViaSeed = false; adminViaVouch = isAdmin`; `isAdminVerified = isAdmin`; `adminCryptoVerified = true`; save.

### 3.3 Signer result types (sealed)

```
AmberConnectResult  = Success(pubkeyHex, npub) | Missing | Cancelled | Error(message)
Nip07ConnectResult  = Success(pubkeyHex, npub) | Missing | Rejected | Error(message)   (web only)
Nip46ConnectResult  = Success(pubkeyHex, npub) | Timeout | Error(message)
Nip46Pairing(uri, secret, clientSecretKeyHex, relays, client) ; cancel() = client.close()
enum SigningMode { local, amber, nip07, nip46 }          // stored as the lowercase name
enum RecommendedExistingPath { nip07, amber, bunker }
```

Exceptions (all user-facing via `message`):
- `SigningException(message)`; subclasses `SigningCancelledException` ("Signatur in Amber abgebrochen."), `SignerMissingException` ("Amber (Nostr Signer) ist nicht installiert."), `WrongAccountException` ("Amber hat mit einem anderen Konto signiert als verbunden." — used for **all** signer types).
- `Nip46Exception(message)`; `Nip46RemoteException(message)` (signer returned `error`); `Nip46TimeoutException(method, limit: Duration, undecryptable: Int, foreignSender: Int = 0, waitingForApproval: Boolean = false)` message "Der Signer hat nicht geantwortet."; `Nip46RelayRejectedException(reason)` message "Das Relay hat die Anfrage abgewiesen: <reason>".
- `Nip07Exception`, `Nip07RejectedException` (web only).
- `Nip49Exception(message)`, `Nip44Exception(message)`, `LocalEasyAuthException(message)`, `PasskeyPrfException(message)` with codes `RP_ID_NOT_ALLOWED`, `PRF_UNSUPPORTED`.

### 3.4 BunkerPointer

| Field | Type | Notes |
|---|---|---|
| remoteSignerPubkey | String | 64 lowercase hex — the **signer's** key (≠ user key for nsec.app) |
| relays | List<String> (unmodifiable, ≥1) | only `wss://…` |
| secret | String? | one-time; sent only in the first `connect`; never persisted |

Parse / serialize rules in §6.3.

### 3.5 Nip46ClientMetadata (constants)

```
name  = "Einundzwanzig Meetup"
url   = "https://github.com/louisthecat86/Einundzwanzig-Meetup-App"
image = "https://raw.githubusercontent.com/louisthecat86/Einundzwanzig-Meetup-App/main/ios/Runner/Assets.xcassets/AppIcon.appiconset/Icon-App-60x60%403x.png"
values = {"name": name, "url": url, "image": image}   // insertion order name,url,image
```
The image URL intentionally contains a literal `%40` (= `@` in the GitHub path). Keep it byte-identical.

### 3.6 PasskeyPrfResult

`credentialId: String` (base64url as returned in the WebAuthn JSON `id`), `prfSecretHex: String` (lowercase hex of the PRF `first` output).

### 3.7 IntegrityReport

`status ∈ {secure, warning, unknown}`, `findings: List<String>`, `checkedAt`; `isCompromised = status == warning`.

### 3.8 KeySecurity (NIP-49)

`insecure = 0x00`, `secure = 0x01`, `unknown = 0x02`; `fromByte`: 0→insecure, 1→secure, anything else→unknown.

---

## 4. Persistence

### 4.1 Plain preferences (Flutter `SharedPreferences`)

On disk (Android, Flutter legacy API used everywhere): file `shared_prefs/FlutterSharedPreferences.xml`, every key prefixed with `flutter.` (e.g. `flutter.signing_mode`). Types: String → string; bool → boolean; int → long; double → string `"VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu" + Double.toString()`; string list → string `"VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu!" + JSON array` (current writer, shared_preferences_android 2.4.x) or legacy `"VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu" + Base64(Java-serialized ArrayList<String>)` (older writes; must also be decoded). `prefs.clear()` in Dart removes only `flutter.`-prefixed keys.

Keys owned/read by this domain (logical names; native stores them in its own DataStore after migration — keep the same logical names to simplify cross-domain backup code):

| Key | Type / values | Writer | Reader |
|---|---|---|---|
| `signing_mode` | `"local"` / `"amber"` / `"nip07"` / `"nip46"`; absent or unknown → local | SigningService `_setMode` | `getMode` |
| `amber_pubkey_hex` | 64 lowercase hex | connectAmber, restoreAmber; removed by disconnectAmber | resolveSigner, canSign |
| `amber_npub` | npub | same | `npub()` |
| `nip07_pubkey_hex`, `nip07_npub` | hex / npub | restoreNip07 (from web backups) | getMode/npub/canSign |
| `nip46_pubkey_hex` | user pubkey hex | `_persistNip46Session`, restoreNip46 | resolveSigner, canSign |
| `nip46_npub` | npub derived from hex | same | `npub()` |
| `nip46_bunker_uri` | `bunker://<signer-hex>?relay=<enc>&relay=…` (no secret) | same | session rehydrate, canSign, backup |
| `secure_migration_done` | bool | SecureKeyStore.ensureMigrated | same |
| `nostr_nsec_key`, `nostr_npub_key`, `nostr_priv_hex` | **legacy plaintext** (pre-secure-storage versions) | — (removed after migration) | ensureMigrated |
| `app_review_demo` | bool | AppReviewDemo.signIn | AppReviewDemo.signIn |
| UserProfile keys | see §3.2 | UserProfile.save | UserProfile.load |
| `custom_relays` (list), `disabled_default_relays` (list) | relay domain | RelayConfig | `nip46PairingRelays` |
| `app_locale` | `"de"`/`"en"`/`"es"`, absent = system | LocaleController (intro picker) | settings domain |

Invariant: leaving `nip46` mode (any `_setMode(m)` with `m != nip46`) clears `nip46_pubkey_hex`, `nip46_npub`, `nip46_bunker_uri` **and** the secure `nip46_client_sk`, and closes the live client. Switching modes never deletes `amber_*`/`nip07_*` except via the respective disconnect, and **never** deletes the local key.

### 4.2 Secure storage (Flutter `flutter_secure_storage` 10.3.4)

All identity secrets share one store (same options in SecureKeyStore, LocalKeyVault, badge, portal and rolling-QR code): `AndroidOptions(resetOnError=false, migrateOnAlgorithmChange=true, migrateWithBackup=true)`.

| Key | Value | Writer | Reader |
|---|---|---|---|
| `nostr_nsec_key` | `nsec1…` | SecureKeyStore.saveKeys | getNsec, loadKeys, hasKey, backup |
| `nostr_npub_key` | `npub1…` | saveKeys | getNpub, loadKeys |
| `nostr_priv_hex` | 64 lowercase hex | saveKeys | getPrivHex (LocalSigner, NIP-44, export) |
| `nip46_client_sk` | 64 hex, app-side NIP-46 session key (not the user key) | `_persistNip46Session`, restoreNip46 | session, canSign; deleted by `_clearNip46Artifacts`, restoreNip46 without key, reset |
| `easyauth_password_ncryptsec` | `ncryptsec1…` (logN 16, keySecurity 0x02) | LocalKeyVault.savePasswordWrap | resume, export fast path, setup sheet |
| `easyauth_password_wrap_npub` | npub the wrap belongs to | savePasswordWrap | passwordWrapMatchesNpub |
| `easyauth_passkey_ncryptsec` | `ncryptsec1…` (logN 8, keySecurity 0x02) | savePasskeyWrap | unlockWithPasskey |
| `easyauth_passkey_credential_id` | WebAuthn credential id (base64url) | savePasskeyWrap | unlockWithPasskey |

Semantics to keep:
- `hasKey()` ⇔ `nostr_nsec_key` non-null and non-empty.
- `loadKeys()` returns `{nsec, npub}` only if both are non-null.
- `deleteKeys()` deletes the three key entries but **not** `nip46_client_sk` (session and identity are separate).
- `passwordWrapMatchesNpub(npub)` ⇔ wrap non-empty AND bound npub non-empty AND equal (exact string). Missing binding = no match (legacy safety).
- `hasPasskeyWrap()` ⇔ wrap non-empty AND credential id non-empty.
- `clearPasskeyWrap()` keeps the password wrap. `clearAll()` deletes all four vault entries.
- Every access first awaits `ensureMigrated()` (mutex/completer — Security Audit 2, finding #8: concurrent callers must wait for the first migration instead of re-reading half-deleted prefs).

`ensureMigrated()` (legacy plaintext → secure storage, runs once): if `secure_migration_done` != true: copy non-empty `nostr_nsec_key`, `nostr_npub_key`, `nostr_priv_hex` from prefs into secure storage, remove the three prefs keys, set `secure_migration_done = true`. Native migration (§4.4) MUST still honour this: if the Flutter prefs still contain those plaintext keys and `secure_migration_done` is not true, import them as well, then delete them.

### 4.3 Native storage design (recommended)

- Non-secret prefs: the shared `:core:data` `KeyValueStore` (Preferences DataStore file `app_prefs`, one file for all domains) with the Flutter key names without the `flutter.` prefix (spec 14 §4.1). `:data:identity` wraps it in a typed `IdentityPrefs` class but does **not** open its own DataStore file — the migration (spec 14 §4.7) writes only to `app_prefs`.
- Secrets: the shared `:core:data` `SecureStore` (Preferences DataStore file `secure_prefs`, values AES-256-GCM with the non-exportable Android Keystore key `einundzwanzig_secure_store_v1`, no user-auth requirement so background signing still works; `readChecked` distinguishes `Absent` from `Unreadable`). Key names = flutter_secure_storage key names. `EncryptedSharedPreferences` is deprecated — do not use.
- Never log values; never include secrets in crash reports.
- `android:allowBackup="false"` (already set in the native manifest) — MUST stay, plus `dataExtractionRules` excluding everything for API 31+, so Keystore-bound data is never restored onto a device without the key.

### 4.4 One-time migration from the Flutter app (`:data:migration`, required — same applicationId `space.einundzwanzig.meetup`, upgrade in place)

The native app ships as an update of the Flutter app (same package, same signing key — mandatory, otherwise all on-device keys are lost). On first start, before any repository reads:

The authoritative procedure (order, state file, retry, cleanup, conflict rules) is spec 14 §4.6–§4.8; this section only lists what it means for identity.

1. Read `FlutterSharedPreferences.xml` (all `flutter.*` keys), decode types as in §4.1, and copy them into the single `KeyValueStore` (`app_prefs`) under the same names without `flutter.` (one generic copy for all domains, spec 14 §4.3; this domain's keys are listed in §4.1).
2. Read every flutter_secure_storage entry and re-encrypt into the native `SecureStore`:
   - Data file `shared_prefs/FlutterSecureStorage.xml`, entry name = `"VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIHNlY3VyZSBzdG9yYWdlCg" + "_" + key`, value = `Base64.DEFAULT( IV ‖ ciphertext ‖ tag )`.
   - Current format (v10): AES-128-GCM (`AES/GCM/NoPadding`, 12-byte IV, 128-bit tag). The AES key is stored in `shared_prefs/FlutterSecureKeyStorage.xml` under `AESVGhpcyBpcyB0aGUga2V5IGZvciBhIHNlY3VyZSBzdG9yYWdlIEFFUyBLZXkK` (fallback typo name `VGhpcyBpcyB0aGUga2V5IGZvcihBIHNlY3XyZZBzdG9yYWdlIEFFUyBLZXkK`), Base64, wrapped with RSA-OAEP (`RSA/ECB/OAEPPadding`, OAEP SHA-256, MGF1-SHA1) by Keystore alias `space.einundzwanzig.meetup.FlutterSecureStoragePluginKeyOAEP`.
   - Legacy format (v9.2.4, if no algorithm markers were ever written): AES-128-CBC/PKCS7, 16-byte IV, AES key under `VGhpcyBpcyB0aGUga2V5IGZvciBhIHNlY3VyZSBzdG9yYWdlIEFFUyBLZXkK`, wrapped RSA/ECB/PKCS1 with alias `space.einundzwanzig.meetup.FlutterSecureStoragePluginKey`.
   - Algorithm markers: `FlutterSecureSAlgorithmKey` / `FlutterSecureSAlgorithmStorage` in `FlutterSecureStorageConfiguration:FlutterSecureStorage` (fallback `FlutterSecureStorageConfiguration`, or the data file itself for v9).
   - Because `migrateWithBackup=true` the v10 plugin may have left a backup copy; and because these code paths are intricate (2 000+ lines), the **recommended implementation is to vendor the read path of `flutter_secure_storage` 10.3.4's Android Java sources (BSD-3-Clause) into `:data:migration`** and call it with exactly these options, instead of re-deriving the format.
3. Only after **every** value was read, re-stored and read back successfully: record state `IMPORTED` in `noBackupFilesDir/flutter_migration/state.json` (spec 14 §4.7; not a pref). The Flutter files and Keystore aliases are deleted only by the later cleanup step of spec 14 §4.7 (`CLEANED`). On any failure: do **not** delete or overwrite anything (Dart `resetOnError=false` semantics), surface a non-destructive error, retry next start. Never create a new key while migration is pending (otherwise an unread old key would be shadowed — the same reason Dart reads badges first).
4. Legacy plaintext keys in prefs (§4.2 `ensureMigrated`) are imported in the same step.

---

## 5. Network

This domain talks only WebSockets to Nostr relays (NIP-46) and launches external URLs; no HTTP endpoints of its own.

| What | Where | Details |
|---|---|---|
| NIP-46 relays | relays from the bunker URI (`relay=` params), or pairing relays (§6.4) | `wss://` only (bunker parse rejects others). Open all in parallel; per-relay connect timeout **10 s**; a dead relay never fails the others; one REQ per relay; publish every request to all open relays; first valid answer wins. |
| `auth_url` | URL from the signer | opened externally only if scheme is `http`/`https` and host non-empty (`launchHttpUrl`); never logged except host |
| `nostrconnect://…` | pairing URI | `ACTION_VIEW` to whatever app handles it; failure → `bunkerNoSignerApp` |
| Meetup list (setup step) | meetups domain `MeetupService.fetchMeetups()` | 15 s timeout; failure → `idSetupMeetupLoadError` |

WebSocket requirements (shared `RelaySocket` — relay domain; OkHttp `WebSocket` natively): connect = wait for handshake (fail fast); drop frames > **512 × 1024 characters** before parsing (Security Audit M4); `close()` must not block after failed handshake.

Timeouts / retries / rate limits for NIP-46 are in §6.2. No HTTP caching. No automatic retries of RPCs (tests assert a silent signer gets exactly one request).

---

## 6. Nostr

### 6.1 Event model and validation (NIP-01)

- Event id = `lowercase_hex(SHA-256(UTF-8(JSON([0, lowercase(pubkey), created_at, kind, tags, content]))))`. JSON must be byte-identical to Dart `json.encode` / JS `JSON.stringify`: no whitespace; strings escape `"` → `\"`, `\` → `\\`, `\b \t \n \f \r` short forms, other U+0000–U+001F as `\u00xx` (lowercase hex), everything else **verbatim** (no `\/`, no `\uXXXX` for non-ASCII, U+2028/2029 verbatim, U+007F verbatim). Integers without decimal point. **Do not use `org.json`** (escapes `/` as `\/`) or a serializer that escapes non-ASCII.
- Signature: BIP-340 Schnorr over the 32-byte id with 32 random aux bytes (Dart: `generate64RandomHexChars()`); pubkey = x-only lowercase hex.
- Validity check used for every **external signer response** (Dart `Event(...).isValid()` from `nostr 1.5.0`), must be replicated exactly:
  ```dart
  bool isValid() {
    String verifyId = getEventId();
    if (createdAt.toString().length == 10 &&
        id == verifyId &&
        bip340.verify(pubkey, id, sig)) { return true; } else { return false; }
  }
  ```
  i.e. `created_at` must have exactly 10 decimal digits, `id` must equal the recomputed **lowercase** id (string equality), and the Schnorr signature must verify.

### 6.2 NIP-46 (remote signer) — transport

Constants: event kind **24133**; timeouts `connect` 75 s (also used for `get_public_key`), pairing 120 s, `sign_event`/`nip44_encrypt`/`nip44_decrypt` 60 s, `ping` 15 s, relay open 10 s; relay cooldown 2 min.

**Client identity**: a random app session key `client_sk` (32 bytes from `Keychain.generate()` = 64 random hex chars) → `client_pubkey = BIP-340 x-only pubkey`. Never the user's key. Persisted only in secure storage `nip46_client_sk`.

**Subscription** (one per relay, sent right after the socket opens):
```
["REQ", "nip46-<16 random hex>", {"kinds":[24133],"#p":["<client_pubkey>"],"since":<now_seconds - 60>}]
```
Subscription id is fixed per client instance.

**Request** (`_rpc(method, params)`):
- `id` = 16 random lowercase hex chars (8 bytes).
- payload JSON = `{"id":"<id>","method":"<method>","params":[<strings>]}` (all params are strings).
- conversation key = NIP-44 `conversationKey(client_sk, remote_signer_pubkey)` (cached only for the paired signer's pubkey).
- event = kind 24133, `tags=[["p","<remote_signer_pubkey>"]]`, `content = NIP-44 encrypt(payload)`, `created_at = now`, signed with `client_sk`.
- frame = `["EVENT", {id,pubkey,created_at,kind,tags,content,sig}]`, sent to **every** open link. If sending fails on all links → immediately `Nip46Exception("Kein Relay des Signers ist erreichbar.")`. Remember `{eventId → requestId, destinations}` to map relay `OK` messages.
- Wait for the response up to the method's timeout. Timeout → `Nip46TimeoutException(method, elapsed, undecryptable, foreignSender, waitingForApproval)`.
- Logging: for `nip44_*` log only frame byte length, number of relay connections and elapsed ms — never payloads, plaintexts, keys (test-enforced).

Methods and params:

| Method | params | Result handling |
|---|---|---|
| `connect` | `[remote_signer_pubkey, secret ?: "", requestedPerms, json(metadata)]` — always 4 params | result ignored (any non-error) |
| `get_public_key` | `[]` | `normalizePubkey(result.toString())` (npub or hex accepted) → user pubkey hex |
| `sign_event` | `[json(unsigned)]` with unsigned keys in order `pubkey, created_at, kind, tags, content` | result is a JSON string or object → map; else `Nip46Exception("Der Signer hat kein lesbares Event zurückgegeben.")` |
| `nip44_encrypt` | `[peer_pubkey_hex, plaintext]` | `result.toString()` |
| `nip44_decrypt` | `[peer_pubkey_hex, payload]` | `result.toString()` |
| `ping` | `[]` | `result == "pong"`; any Nip46Exception → false |

`requestedPerms` (exact): `get_public_key,nip44_encrypt,nip44_decrypt,sign_event:21000,sign_event:21001,sign_event:21002,sign_event:21003,sign_event:22242,sign_event:30021,sign_event:30078,sign_event:30079,sign_event:31922,sign_event:31923`
(21000 badge sig, 21001 reputation-QR (21v3) signature envelope — tags `[]`, content = QR JSON, never published, see spec 02 §3.7 / spec 04 (the Dart comment `21001 Badge-Widerruf` in nip46_client.dart:102 is stale; no revocation event exists), 21002 badge claim, 21003 platform proof/distrust, 22242 portal login & NIP-42 auth, 30021 promotion claim, 30078 reputation/admin registry/badge sync, 30079 co-attendance, 31922/31923 calendar.) Kinds signed elsewhere but **not** requested: 5, 7, 9, 1111, 9021, 9022, 9734, 24242, 31925 — the signer will prompt for those (see Risks).

**Incoming message handling** (`_onMessage(url, data)`), only text frames:
1. Parse JSON array; empty → ignore.
2. `["OK", eventId, accepted, reason?]` → relay ack:
   - unknown eventId or relay not a destination → ignore.
   - `accepted == true` → mark relay accepted.
   - else (unless already accepted by that relay): record rejection reason; if reason starts with `blocked:`, `banned:` or `rate-limited:` → cooldown that relay for 2 minutes (skipped by future opens) and drop its link.
   - If no relay accepted and rejected count ≥ sent count → fail the pending request with `Nip46RelayRejectedException(lastReason or "ohne Angabe")` immediately (no waiting for the timeout). One relay rejecting while another accepts must **not** fail the request. Duplicate rejections from the same relay count once.
3. `["CLOSED", subId, reason?]` → warn, drop the link (close socket asynchronously). The next RPC reconnects (only when **all** links are gone).
4. `["EVENT", subId, event]` with `event.kind == 24133`, `event.pubkey` of length 64:
   - If paired and `pubkey != remote_signer_pubkey` → `foreignSender++`, ignore.
   - Decrypt `content` with `conversationKey(client_sk, sender)`; JSON object required. Any failure (decrypt, JSON) → `undecryptable++` silently.
   - Event signatures and `p` tags are intentionally **not** checked (authenticity follows from the ECDH conversation key; replays carry stale ids).
   - Dispatch `{id, result, error}`:
     - **Pairing**: if a pairing is pending and `result.toString() == pairing_secret` → `remote_signer_pubkey = sender`, complete pairing.
     - Unknown id → ignore.
     - `result == "auth_url"` → mark request as waiting for approval; if `error` non-empty → `onAuthUrl(error)`; keep request pending (real answer comes later with the same id).
     - else remove pending; non-empty `error` → `Nip46RemoteException(error)`; else complete with `result`. Second deliveries of the same id are ignored.
5. Socket error → drop link (and close); socket done → drop link (no close). A late `onDone` of an old socket must not remove a newer link for the same URL (identity check).

`close()`: mark closed, cancel subscriptions, close all links, fail all pending requests with `Nip46Exception("Verbindung zum Signer wurde geschlossen.")`, fail a pending pairing with `Nip46Exception("Kopplung wurde abgebrochen.")`. After close every RPC throws `Nip46Exception("Die Signer-Sitzung ist geschlossen.")`.

`_ensureConnected()`: no-op if links exist; concurrent callers share one connection attempt (no duplicate sockets/subscriptions); open all relays in parallel; none opened → `Nip46Exception("Kein Relay des Signers ist erreichbar. Ist das Gerät online?")`. RPC without a paired signer → `Nip46Exception("Noch kein Signer gekoppelt.")`. Empty relay list at construction → `Nip46Exception("Für den Remote-Signer ist kein Relay angegeben.")`. Invalid `client_sk` (length ≠ 64 or invalid) → `Nip46Exception("Der Sitzungsschlüssel der App ist ungültig.")`.

`awaitPairing(secret, timeout=120 s)`: returns immediately if already paired; else connect links, wait for the secret echo; timeout → `Nip46TimeoutException("pairing", limit, undecryptable)`.

### 6.3 NIP-46 URIs

`bunker://` parse (`BunkerPointer.parse(input)`):
1. `trimmed = input.trim()`; must start with `bunker://` (case-insensitive) else `Nip46Exception('Das ist keine Bunker-Adresse — sie muss mit "bunker://" beginnen.')`.
2. Parse as URI (failure → "Die Bunker-Adresse ist unlesbar."). Key = host; if host empty and path segments exist → first path segment.
3. Relays = all `relay` query values (form-decoded), each trimmed, kept only if it starts with `wss://` (case-sensitive). None → `Nip46Exception("In der Bunker-Adresse fehlt ein Relay (relay=wss://…). Ohne Relay ist der Signer nicht erreichbar.")`.
4. `secret` = first `secret` value trimmed; empty → null.
5. `remoteSignerPubkey = normalizePubkey(key)`: trim; if it starts with `npub1` (case-insensitive) → NIP-19 decode (failure → "Der npub des Signers ist ungültig."); lowercase; must be 64 chars of `[0-9a-f]` else "Der Schlüssel des Signers ist kein 64-stelliger Hex-Wert.".

Persisted form (`toBunkerUri()`, secret dropped):
```dart
'bunker://$remoteSignerPubkey?${relays.map((r) => 'relay=${Uri.encodeQueryComponent(r)}').join('&')}'
```
`nostrconnect://` (`buildNostrConnectUri`), exact parameter order:
```dart
final params = <String>[
  for (final r in relays) 'relay=${Uri.encodeQueryComponent(r)}',
  'secret=${Uri.encodeQueryComponent(secret)}',
  'perms=${Uri.encodeQueryComponent(perms)}',
  'name=${Uri.encodeComponent(appName)}',          // %20 for space, never '+'
  if (appUrl != null) 'url=${Uri.encodeQueryComponent(appUrl)}',
  if (appImage != null) 'image=${Uri.encodeQueryComponent(appImage)}',
];
return 'nostrconnect://$clientPubkeyHex?${params.join('&')}';
```
Encoding rules (byte-exact):
- `Uri.encodeQueryComponent`: keep `A–Z a–z 0–9 - . _ ~`; space → `+`; every other UTF-8 byte → `%XX` (uppercase hex). Kotlin: `URLEncoder.encode(s, UTF_8)` is **not** identical (it keeps `*` and encodes `~`); implement explicitly.
- `Uri.encodeComponent`: keep `A–Z a–z 0–9 - _ . ! ~ * ' ( )`; everything else `%XX` (space → `%20`, `+` → `%2B`).
- The image constant contains `%40`, which becomes `%2540` (decodes back to the literal `%40`).
- Full expected example in the fixture (`nip46.nostrConnectUri.expected`).

### 6.4 Pairing relays

`nip46PairingRelays()`: `active = RelayConfig.getActiveRelays()` (defaults `wss://relay.damus.io`, `wss://nos.lol`, `wss://relay.nostr.band`, `wss://nostr.einundzwanzig.space` minus `disabled_default_relays`, plus `custom_relays`, de-duplicated, insertion order defaults-then-custom); take the first 3; if empty → first 2 defaults (`wss://relay.damus.io`, `wss://nos.lol`). Never hard-code a relay ahead of the user's choice (the signer stores this list for the session).

### 6.5 SigningService session logic (NIP-46)

- One held client (`_nip46`) for the process lifetime; concurrent first uses after restart share one rehydration (`Completer` → native `Mutex`/`Deferred`).
- Rehydrate: needs `nip46_client_sk` (secure) and `nip46_bunker_uri`; missing → `SigningException("Es ist keine Signer-Sitzung gespeichert. Bitte den Signer erneut verbinden.")`; else `Nip46Client(client_sk, BunkerPointer.parse(uri))` with `onAuthUrl` forwarding to the current global handler.
- Swapping sessions closes the old client (no zombie subscriptions).
- `connectNip46Bunker(uri)`: parse → new `client_sk` → client → `connect(secret)` → `getPublicKey()` → persist (`nip46_client_sk`, `nip46_pubkey_hex`, `nip46_npub`, `nip46_bunker_uri = pointer.toBunkerUri()`, mode `nip46`) → swap in → security log (npub shortened `first12…last4`) → `onSignerReady()` → Success. Errors: close client; timeout → Timeout; Nip46Exception → Error(message); other → Error(toString).
- `startNip46Pairing()`: relays, new `client_sk`, 16-hex secret, unpaired client, URI with perms + metadata. No network until awaited.
- `completeNip46Pairing(p)`: `awaitPairing(secret)` → `getPublicKey()` → persist with `BunkerPointer(signerPubkey, p.relays).toBunkerUri()` (always stored as `bunker://`, single restore path) → swap → log → signerReady → Success. No `connect` RPC on this path. Errors → `p.cancel()` + Timeout/Error.
- `nip46SessionAlive()`: false unless mode nip46; `ping()` on the session; exceptions → false. Proves the connection, **not** signing rights.
- `nip46SessionForBackup()`: `{"bunker_uri": uri}` or null — **never** the session key.
- `restoreNip46(npub, bunkerUri, clientSecretKeyHex?)`: decode npub (empty result for wrong hrp — see Risks), `BunkerPointer.parse(bunkerUri)` first (throws before anything is stored; mode must stay unchanged on failure), swap session to null, if key empty → delete `nip46_client_sk` else save it, write the three prefs (`nip46_bunker_uri = bunkerUri.trim()`), mode nip46, signerReady. Backups always call it without a key (an old `nip46_client_sk` field in a backup is ignored on purpose).
- `disconnectNip46()`: clear artifacts, then mode local.

### 6.6 NIP-55 (Amber, Android external signer)

Package `com.greenart7c3.nostrsigner`. Manifest `<queries>`: `<package android:name="com.greenart7c3.nostrsigner"/>` and `<intent><action VIEW/><data android:scheme="nostrsigner"/></intent>` (MUST — Android 11+ package visibility; otherwise install check and intents fail).

Native implements the bridge directly (no MethodChannel). Semantics of the existing Kotlin bridge (`MainActivity.kt`) to reproduce:

`isInstalled()`: `packageManager.getPackageInfo(pkg, 0)` succeeds.

**get_public_key** (foreground, only on connect):
```kotlin
Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:")).apply {
  `package` = "com.greenart7c3.nostrsigner"
  putExtra("type", "get_public_key")
  putExtra("permissions", """[{"type":"sign_event"},{"type":"nip44_encrypt"},{"type":"nip44_decrypt"}]""")
}
```
Result: `RESULT_OK` and data non-null, value = `data.getStringExtra("result") ?: data.getStringExtra("signature")`; blank → cancelled. Not OK → cancelled.
Value parsing (`AmberNostrSigner.connect`): trim; if it starts with `{` → JSON, take `result` ?: `npub` ?: `pubkey`; then `npub1…` → decode (hex) + keep npub; `^[0-9a-fA-F]{64}$` → lowercase hex + encode npub; else `Error("Unerwartetes Schlüsselformat von Amber.")`; decode exception → `Error("Ungültiger pubkey von Amber: <e>")`. Empty result → Cancelled. Not installed → Missing.

**sign_event**:
1. Background first: `contentResolver.query(Uri.parse("content://com.greenart7c3.nostrsigner.SIGN_EVENT"), arrayOf(eventJson, "", npub), null, null, null)` (run on IO). If cursor has a first row: column `rejected` present and non-null → error `rejected`; else column `event` (signed event JSON) non-null → success `{event, result}`; null cursor / exception → fall through.
2. Foreground: if another foreground request is pending → error `busy`. `Intent(ACTION_VIEW, Uri.parse("nostrsigner:$eventJson"))` (raw JSON appended, not URL-encoded), package Amber, extras `type="sign_event"`, `current_user=<npub>`. Result: OK → `event` extra (blank → cancelled), `result`/`signature` extra; not OK → cancelled.
- `eventJson` (unsigned) = `{"pubkey":"<expected hex>","created_at":<now>,"kind":k,"tags":[…],"content":"…"}` in that key order; `npub` = `Nip19.encodePubkey(expectedHex)` (fallback: the hex).
3. Parse `event` JSON (`SigningException("Amber hat kein signiertes Event geliefert.")` if missing/empty; `"Amber-Antwort nicht lesbar: <e>"` if not JSON) → `verifySignerResponse(actor = "Amber")` (§7.6, Security Audit N1).

**nip44_encrypt / nip44_decrypt**:
1. ContentResolver `content://com.greenart7c3.nostrsigner.NIP44_ENCRYPT` (or `NIP44_DECRYPT`) with projection `arrayOf(value, peerPubkeyHex, npub)`; `rejected` → error; column `result` non-null → success.
2. Foreground `Intent(ACTION_VIEW, Uri.parse("nostrsigner:$value"))`, package, extras `type` = `nip44_encrypt`/`nip44_decrypt`, `pubkey` = peer hex, `current_user` = npub. Result `result` ?: `signature`; blank → cancelled.

Error mapping to exceptions: `cancelled`/`rejected` → `SigningCancelledException`; `signer_missing` → `SignerMissingException`; anything else (`busy`, `launch_failed`, `bad_args`, `unknown`) → `SigningException("Amber-Fehler: <message or code>")`; null result → `SigningCancelledException`.

Native implementation notes: use `ActivityResultContracts.StartActivityForResult` registered in the single Activity, expose `suspend fun request(intent): ActivityResult` via a `CompletableDeferred` held by an app-scoped `Nip55Bridge`; serialize foreground requests (one at a time, others get `busy`); NIP-55 recommends `FLAG_ACTIVITY_SINGLE_TOP | FLAG_ACTIVITY_CLEAR_TOP` for batched requests (MAY add). Handle Activity recreation while Amber is in front (process death → the pending call fails as cancelled).

### 6.7 NIP-07

Browser-only. On Android: `nip07Available()` = false; `connect()` = Missing; sign/NIP-44 throw `SigningException("NIP-07 gibt es nur im Browser.")` (stub message). Mode `nip07` must still be readable, displayable (npub) and disconnectable (`disconnectNip07` removes `nip07_pubkey_hex`, `nip07_npub`, sets local), and restorable from backups.

### 6.8 Other identity-related Nostr facts

- No kind-0 is published by the identity setup (Name ≠ NIP-05; the nickname is local; the profile domain handles kind 0 if at all).
- `NostrService.sign(data)` (legacy helper): requires `canSign()`, signs kind **21000**, `tags=[]`, `content=data`, returns only `sig`.
- `NostrService.signBadge(meetupId, meetupName, date, blockHeight)` (badge domain caller): content = `jsonEncode({"meetup_id","meetup_name","date","block_height","npub","timestamp":<ms>})` in that order, kind 21000, tags `[["meetup", id], ["block", "<height>"]]`; returns `{event_id, pubkey, npub, signature, content, created_at}`.
- `NostrService.verify(data, signature, pubkeyHex, eventId, createdAt, tags=[], kind=21000)`: reconstruct event and `isValid()`.

---

## 7. Crypto & encoding

### 7.1 Keys and NIP-19

- Key generation: 32 random bytes from `SecureRandom` as 64 lowercase hex (Dart `generate64RandomHexChars`; no explicit range check — native SHOULD reject 0 and ≥ n, which only changes behaviour with probability ~2⁻¹²⁸).
- Pubkey: BIP-340 x-only, 64 lowercase hex.
- `npub` / `nsec` = bech32 (BIP-173, **not** bech32m) with HRP `npub`/`nsec` over the 32 raw bytes (8→5 bit with padding).
- Decoding quirk (MUST replicate where used): `Nip19.decodePubkey(x)` returns `""` (empty string, no exception) when the HRP is not `npub`; same for `decodePrivkey` with `nsec`. Invalid bech32 checksum throws. Default bech32 length limit 90 applies to npub/nsec (63 chars) — fine.
- `importPrivHex(hex)`: `trim().lowercase()` must match `^[0-9a-f]{64}$` else `FormatException("Ungültiger privater Schlüssel (hex).")`; derive pubkey; store `nsec`, `npub`, `privHex` (normalized).
- `importNsec(nsec)`: trim; must start with `nsec1` else `FormatException('Ungültiges Format. Der Key muss mit "nsec1" beginnen.')`; decode → importPrivHex; any error → `FormatException("Ungültiger nsec Key: <e>")`.
- `importNcryptsec(s, pw)`: `Nip49.decrypt(s.trim(), pw)` → importPrivHex.
- `shortenNpub(npub, chars=8)`: if `length < chars*2+5` return as is; else `npub.substring(0, 4+chars) + "..." + npub.substring(len-chars)`.

### 7.2 NIP-49 (`ncryptsec`) — byte-exact

Payload 91 bytes, then bech32 (classic) with HRP `ncryptsec`, **max length 5000** (result is 162 chars; standard 90-char limit must be lifted):

| Offset | Len | Field |
|---|---|---|
| 0 | 1 | version `0x02` |
| 1 | 1 | log_n |
| 2 | 16 | salt (random) |
| 18 | 24 | nonce (random) |
| 42 | 1 | key-security byte (also the AAD) |
| 43 | 48 | ciphertext (32) ‖ Poly1305 tag (16) |

```dart
// key derivation
final normalized = unorm.nfkc(password);
final derivator = Scrypt()..init(ScryptParameters(1 << logN, 8, 1, 32, salt));
return derivator.process(Uint8List.fromList(utf8.encode(normalized)));
// sealing: XChaCha20-Poly1305 (IETF), key = scrypt output, nonce 24 bytes, aad = [keySecurityByte]
return Uint8List.fromList([...box.cipherText, ...box.mac.bytes]);
```
Rules:
- Encrypt: privkey hex must decode to exactly 32 bytes ("Der private Schlüssel ist kein gültiges Hex." / "… muss 32 Bytes haben, hat N."); password non-empty; `1 ≤ logN ≤ 22`.
- Decrypt: input trimmed; must start with `ncryptsec1` (case-insensitive check) else `Nip49Exception('Das ist kein ncryptsec — es muss mit "ncryptsec1" beginnen.')`; HRP must be exactly `ncryptsec`; length 91; version 2; **`1 ≤ log_n ≤ 22` checked before scrypt** (DoS guard); any AEAD failure → `Nip49Exception("Falsches Passwort — oder der Schlüssel ist beschädigt.")`; plaintext must be 32 bytes; result lowercase hex.
- 5→8-bit conversion without padding rejects leftover bits ≥ 5 or non-zero padding ("Überzählige Bits in der Kodierung.").
- `keySecurityOf(s)` reads byte 42 without a password.
- Defaults & uses: `defaultLogN = 16` (64 MiB scrypt memory); password wrap at registration `logN 16, keySecurity unknown (0x02)`; passkey wrap `logN 8, unknown` (never exported); profile export `logN 16, insecure (0x00)`.
- NFKC: `java.text.Normalizer.normalize(pw, Normalizer.Form.NFKC)`; UTF-8 bytes.
- Native libs: scrypt — BouncyCastle `SCrypt.generate(P, S, N=1 shl logN, r=8, p=1, dkLen=32)` (allocates 64 MiB on the Java heap for logN 16: run on `Dispatchers.Default`, catch `OutOfMemoryError` → user-visible error; or use a native scrypt such as libsodium `crypto_pwhash_scryptsalsa208sha256_ll`). XChaCha20-Poly1305 — libsodium (`crypto_aead_xchacha20poly1305_ietf_*`) or Tink's `InsecureNonceXChaCha20Poly1305` (explicit nonce) or HChaCha20 + BouncyCastle `ChaCha20Poly1305`.

### 7.3 NIP-44 v2 (used by NIP-46 transport and `LocalNostrSigner.nip44*`)

```
conversation_key = HMAC-SHA256(key = "nip44-v2" (UTF-8), msg = x-coordinate(ECDH(priv, lift_x(pub))) as 32 BE bytes)
keys(76) = HKDF-Expand-SHA256(PRK = conversation_key, info = nonce(32), L = 76)
  chacha_key = [0..32), chacha_nonce = [32..44), hmac_key = [44..76)
padded = u16_be(len) ‖ utf8(plaintext) ‖ zeros up to 2 + calc_padded_len(len)
ciphertext = ChaCha20 (RFC 7539, 12-byte nonce, counter 0)(padded)
mac = HMAC-SHA256(hmac_key, nonce ‖ ciphertext)
payload = base64( 0x02 ‖ nonce(32) ‖ ciphertext ‖ mac(32) )   // standard base64 with padding
```
`calc_padded_len(n)`: `n ≤ 32 → 32`; else `next_power = 1 << bitLength(n-1)`; `chunk = next_power ≤ 256 ? 32 : next_power/8`; `chunk * ((n-1)/chunk + 1)`.
Checks: plaintext 1..65535 bytes; payload starting with `#` → unknown version; base64 string length 132..87472 **before** decoding; decoded 99..65603 bytes; version byte 2; MAC compared in constant time; unpadded length 1..65535 and buffer length must equal `2 + calc_padded_len(len)`. Private key must be 1..n−1; public key x must lift to a curve point (even y) — reject twist points. `lift_x` + ECDH: secp256k1-kmp has no raw-x ECDH (`ecdh()` hashes) — compute `pubKeyTweakMul(0x02‖x, priv)` and take bytes 1..33, or use BouncyCastle EC math.
All official vectors: `test/fixtures/nip44.vectors.json` (35 conversation keys, 32 message-key sets, 24 padded lengths, 10 encrypt/decrypt incl. reverse direction, 3 long messages; invalid: 8 conversation keys, 4 message lengths, 12 payloads).

### 7.4 PBKDF2-HMAC-SHA256 (backup key derivation core, `lib/services/pbkdf2/`)

Used by the backup domain with **600 000 iterations, 32-byte key, 32-byte random salt** (`enc_v2:` format). Byte-exact core:
```dart
final hmac = Hmac(sha256, password);          // password = UTF-8 bytes
const hashLength = 32;
final blocks = (keyBytes + hashLength - 1) ~/ hashLength;
// T_i = U1 ^ U2 ^ ... ^ Uc ; U1 = HMAC(P, S || INT_32_BE(i)) ; U_{j+1} = HMAC(P, U_j)
return Uint8List.sublistView(out, 0, keyBytes);
```
`iterations < 1` or `keyBytes < 1` → error. Native: BouncyCastle `PKCS5S2ParametersGenerator(SHA256Digest())` with `password.toByteArray(UTF_8)` — do **not** rely on `SecretKeyFactory("PBKDF2WithHmacSHA256")` with a `char[]` (provider-specific char→byte conversion breaks non-ASCII/emoji passwords). Run off the main thread (several seconds on low-end devices). The web WebCrypto shortcut (`pbkdf2_fast_web.dart`) is irrelevant on Android (stub returns null).

### 7.5 Passkey PRF

- RP id (native) `einundzwanzig.space`; RP name `21meetup`. Requires `https://einundzwanzig.space/.well-known/assetlinks.json` listing package `space.einundzwanzig.meetup` with the **release signing certificate SHA-256** (and `delegate_permission/common.get_login_creds`). Without it Credential Manager fails → "Passkey unavailable" path (documented as still open in `docs/IDENTITY_ONBOARDING_PLAN.md`).
- PRF salt: UTF-8 `21meetup-easyauth-prf-v1` → base64url without padding `MjFtZWV0dXAtZWFzeWF1dGgtcHJmLXYx`, sent as `extensions.prf.eval.first`.
- Challenge: 32 random bytes, base64url no padding (no server verification exists).
- Registration JSON (as built by `passkeys_android`): `{"rp":{"id":"einundzwanzig.space","name":"21meetup"},"user":{"name":N,"displayName":N,"id":"<base64url WITH '=' padding of the 32 pubkey bytes>"},"challenge":"…","attestation":"none","timeout":60000,"pubKeyCredParams":[{"type":"public-key","alg":-7},{"type":"public-key","alg":-257}],"authenticatorSelection":{"authenticatorAttachment":"platform","requireResidentKey":false,"residentKey":"preferred","userVerification":"preferred"},"excludeCredentials":[],"extensions":{"prf":{"eval":{"first":"<salt b64url>"}}}}` → `CreatePublicKeyCredentialRequest(json)`. N = display name (trimmed nickname, or `21meetup` if empty). Without the ES256+RS256 list Android returns `TYPE_NOT_SUPPORTED_ERROR`.
- Authentication JSON: `{"challenge":"…","timeout":60000,"rpId":"einundzwanzig.space","allowCredentials":[{"type":"public-key","id":"<credentialId>","transports":[]}],"userVerification":"preferred","extensions":{"prf":{"eval":{"first":"<salt>"}}}}` → `GetPublicKeyCredentialOption(json)`, `preferImmediatelyAvailableCredentials = false`.
- Result: `clientExtensionResults.prf.results.first` (base64url, padding optional) → bytes → **lowercase hex string** = the NIP-49 "password" (64 chars). Missing/empty → `PasskeyPrfException("PRF_UNSUPPORTED")` (on unlock).
- Registration without PRF output: immediately run `unlock(allow=[response.id])` to obtain it (second prompt), credentialId from the create response.
- Compatibility: a passkey wrap created by the Flutter app can be opened natively only with the same RP id, same salt, same credential id → same PRF output.
- Browser-only members of `PasskeyPrfService` — **not applicable on Android** (all are `kIsWeb` branches that never run on the Dart VM / Android):
  - `relyingPartyId()`: web → `Uri.base.host` (`'localhost'` if the host is empty); native → constant `einundzwanzig.space`. Native port: a constant `RP_ID = "einundzwanzig.space"`, no host lookup.
  - `allowedWebHosts = {'einundzwanzig.space', 'localhost', '127.0.0.1'}` (a passkey's rpId in the browser is the host; shared domains such as `*.github.io` must never be listed, because every project on the domain would share the passkey namespace).
  - `isEnvironmentAllowed`: `!kIsWeb` → `true` (native: the asset-links domain association decides); web → host empty → `true`, else `allowedWebHosts.contains(host)`. It gates `isSupported()` (→ `false`) and `register()` (→ `PasskeyPrfException('RP_ID_NOT_ALLOWED')`); `unlock()` is not gated. On Android it is always `true`, so the gate and the `RP_ID_NOT_ALLOWED` code are unreachable; native may omit both (keep `RP_ID_NOT_ALLOWED` in the error→`idSetupPasskeyUnavailable` mapping only if a shared code path ever needs it).

### 7.6 External signer response verification (`verifySignerResponse`) — shared by Amber, NIP-46 (and NIP-07)

Input: `signed` map, `expectedPubkeyHex`, requested `kind`, `tags`, `content`, `fallbackCreatedAt` (the local `now` used in the unsigned event), `actor` ∈ {`"Amber"`, `"Der Signer"`, `"Die Erweiterung"`}.
1. `signed.pubkey` (string) lowercase ≠ expected lowercase → `WrongAccountException`.
2. `sig` empty → `SigningException("<actor> hat kein signiertes Event geliefert.")`.
3. If `signed.kind` is an int and ≠ kind → `SigningException("<actor> hat einen anderen Event-Typ signiert (<got> statt <kind>).")`.
4. If `content` key present and its string value ≠ content → `SigningException("<actor> hat den Inhalt des Events verändert.")`.
5. `id = signed.id` (string), `createdAt = signed.created_at` if int else fallback, `tags = parse(signed.tags)`: if not a list of lists → **caller tags**; elements stringified.
6. Build event `(id, signedPubkey, createdAt, kind, tags, content, sig)` and require `isValid()` (§6.1, 10-digit created_at!) else `SigningException("<actor> hat keine gültige Signatur geliefert.")`.
7. Return SignedEvent with signer's id/pubkey/createdAt/tags/sig and caller's kind/content.
Signer-normalized tags (re-ordered, extra `client` tag) are accepted because id/sig cover them.

### 7.7 Random identifiers

- NIP-46 request id: 8 random bytes → 16 lowercase hex. Subscription id `nip46-` + 16 hex. Pairing secret 16 hex. All from `SecureRandom`.

---

## 8. Business rules & algorithms

### 8.1 Mode resolution (`SigningService`)

- `getMode()`: stored value → mode; `amber` on Android always allowed; unknown/absent → local.
- `isExternalSigner` ⇔ mode ∈ {amber, nip07, nip46}. Used to: exclude nsec from "local" UI, decide npub source, **avoid silent signing at app start** (home auto portal login only in local mode — every external signature prompts).
- `resolveSigner()`:
  - amber: needs `amber_pubkey_hex` else `SigningException("Amber-Modus aktiv, aber kein verbundener Schlüssel. Bitte Amber erneut verbinden.")`.
  - nip07: needs `nip07_pubkey_hex` else "Erweiterungs-Modus aktiv, aber kein verbundener Schlüssel. Bitte die Browsererweiterung erneut verbinden."
  - nip46: needs `nip46_pubkey_hex` else "Bunker-Modus aktiv, aber kein verbundener Schlüssel. Bitte den Signer erneut verbinden."
  - local: LocalNostrSigner (missing privHex → `SigningException("Kein privater Schlüssel vorhanden.")` at sign time).
- `pubkeyHex()`: local → decode keystore npub (null if missing, `""` if wrong HRP, null on exception); external → stored expected hex.
- `npub()`: amber → `amber_npub`; nip07 → `nip07_npub`; nip46 → `nip46_npub`; local → keystore npub.
- `canSign()` (hot path, no network): amber → hex present; nip07 → hex present AND extension available (Android: false); nip46 → hex AND bunker uri AND `nip46_client_sk` non-empty (no ping); local → privHex non-empty.
- `canNip44()`: probe with plaintext `e21-nip44-probe`: own pubkey (missing → false + log), encrypt to self, decrypt, compare; any exception → false. Logs step (`Signer laden`, `Identität lesen`, `nip44_encrypt`, `nip44_decrypt`), elapsed ms and `SigningException.message` or exception type — **never** payloads/plaintext/keys (test-enforced). Mismatch logs "Entschlüsselte Probe stimmt nicht überein".
- `onSignerReady` is invoked after: connectAmber success, useLocalMode, restoreAmber, connectNip07, restoreNip07, Nip46 connect/pair success, restoreNip46 (→ Badge sync trigger).
- Connecting any external signer **never deletes** an existing local key (deliberate; data-loss path removed). Backup saves keys by existence, not mode (mixed state).
- `disconnectAmber()`: remove `amber_pubkey_hex`, `amber_npub`; mode local.

### 8.2 Import

See §2.3.8 and §7.1. Always followed by `LocalKeyVault.clearAll()` + `useLocalMode()`.

### 8.3 `LocalEasyAuth.register(nickname, password)` (exact order matters)

1. `name = nickname.trim()`; empty or `"Anon"` → `LocalEasyAuthException("Bitte einen Namen wählen.")`. `password.length < 8` → "Passwort muss mindestens 8 Zeichen haben.".
2. Generate keypair **in memory**.
3. `wrap = Nip49.encrypt(priv, password, keySecurity = unknown, logN = 16)` — compute **before** persisting anything (if it fails/aborts, no key without wrap is left behind).
4. `LocalKeyVault.clearPasskeyWrap()` (old passkey wraps belong to the previous identity).
5. `NostrService.importPrivHex(priv)` (stores nsec/npub/privHex).
6. `SigningService.useLocalMode()`.
7. `npub = SecureKeyStore.getNpub()`; `LocalKeyVault.savePasswordWrap(wrap, npub)`.
8. Profile: `nickname = name`, `nostrNpub = npub`, `hasNostrKey = true`, `isNostrVerified = true`, `isReviewDemo = false`; save.
Does **not** check for an existing key (overwrites).

`addPasskeyWrap(displayName)`: requires local priv and npub (else "Kein lokaler Schlüssel vorhanden."); `pubHex = decode(npub)`; `PasskeyPrfService.register(userIdHex = pubHex, userName = displayName.trim() or "21meetup")`; `wrap = Nip49.encrypt(priv, prfSecretHex, unknown, logN = 8)`; `savePasskeyWrap(wrap, credentialId)`.

`unlockWithPassword(pw)`: wrap missing → "Kein gespeicherter Schlüssel."; `priv = Nip49.decrypt(wrap, pw)` → `importPrivHex` → `useLocalMode()`.

`unlockWithPasskey()`: wrap or credential id missing → "Kein Passkey hinterlegt."; `secret = PasskeyPrfService.unlock([credId])`; decrypt with `secret.prfSecretHex` → importPrivHex → useLocalMode.

### 8.4 Passkey availability

`isSupported()`: first `if (!isEnvironmentAllowed) return false` (always passes on Android, see §7.5); then Android → `isUserVerifyingPlatformAuthenticatorAvailable` (Play Services FIDO2) and API ≥ 28; any exception → false. "Supported" says nothing about PRF; PRF absence is detected only at use.

### 8.5 Profile export fast path

Reuse the vault password wrap only if it is bound to the **current keystore npub** (`passwordWrapMatchesNpub`). After an nsec import the vault is cleared anyway; the binding protects against legacy wraps of a different key.

### 8.6 Backup interplay (backup domain owns the file format)

Identity fields written into the backup JSON (`version: 5`):
- `user`: `nickname`, `fullName`, `homeMeetupId`, `favoriteMeetupIds`, `isAdmin`, `isAdminVerified`, `isNostrVerified`, `nostrNpub`, `telegramHandle`, `twitterHandle`.
- `nostr`: `nsec` (keystore nsec or ""), `npub` (`hasLocalKey ? keystoreNpub : activeNpub ?: keystoreNpub ?: ""`), `priv_hex` (only if local key), `has_key` (local nsec exists), `signing_mode` (`mode.name`: local/amber/nip07/nip46), `active_npub` (only in mixed state: external mode AND local key → `SigningService.npub()`; else ""), `nip46_bunker_uri` (`nip46SessionForBackup()["bunker_uri"]` or "") — never `nip46_client_sk`.
Restore (identity part, non-additive decisions only): if `has_key` and nsec/npub/priv_hex non-empty and **consistent** (Security Audit N3: `decodePrivkey(nsec).lower == priv_hex.lower`, priv_hex matches `^[0-9a-f]{64}$`, `encodePubkey(pub(priv)) == npub.trim()`) → `saveKeys`; inconsistent → don't store, realign profile npub with keystore. Then if `signing_mode ∈ {amber, nip07, nip46}`: `externalNpub = active_npub non-empty ? active_npub : npub`; restore via `restoreNip07`/`restoreNip46(npub, nip46_bunker_uri)`/`restoreAmber`; profile `nostrNpub = externalNpub`, `isNostrVerified = true`, `hasNostrKey = restoredLocalKey`. If no external restore but a local key was restored → `useLocalMode()`, profile npub = backup npub. Exported API required from this domain: `SigningService.getMode/npub/isExternalSigner/nip46SessionForBackup/restoreAmber/restoreNip07/restoreNip46/useLocalMode`, `SecureKeyStore.getNsec/getNpub/getPrivHex/saveKeys`, `backupKeyConsistent`.

### 8.7 App-review demo (`AppReviewDemo`)

- `payload = "21review:einundzwanzig-meetup-demo"`; `matches(raw) = raw != null && raw.trim() == payload`.
- Credentials `AppReview` / `ReviewDemo1`; `isDemoLogin(u, p) = u.trim() == "AppReview" && p == "ReviewDemo1"` (password not trimmed).
- `signIn(u, p)`: not offered in this build → `LocalEasyAuthException("Demo sign-in is not available in this build.")`; bad credentials → "Unknown user name or password." (no hints). If profile nickname == "AppReview" AND pref `app_review_demo` true AND local key exists → return (re-entry). If not a demo profile and a local key exists → "Demo sign-in is only available on a fresh install." Else `LocalEasyAuth.register("AppReview", "ReviewDemo1")`; profile `isReviewDemo = true`, `homeMeetupId = "Berlin"`, `favoriteMeetupIds = ["Berlin"]`; save; `MeetupBadge.saveBadges([MeetupBadge(id="review-demo-badge-1", meetupName="App Review Demo Meetup", date=now−7 days, iconPath="", delivery="rolling_qr", isRetroactive=true)])` (badge domain model); pref `app_review_demo = true`. No admin rights.

### 8.8 Device integrity (Android checks, cached per process)

Findings (each path checked as file OR directory exists):
- Root binaries: `/system/bin/su`, `/system/xbin/su`, `/sbin/su`, `/system/app/Superuser.apk`, `/system/app/SuperSU.apk`, `/data/local/bin/su`, `/data/local/xbin/su` → "Root-Binary gefunden: <path>".
- Magisk: `/sbin/.magisk`, `/data/adb/magisk`, `/data/adb/modules` → "Magisk-Indikator: <path>".
- `getprop ro.build.tags` containing `test-keys` → debug log only, **not** a finding (OEM ROMs).
- BusyBox: `/system/xbin/busybox`, `/system/bin/busybox` → "BusyBox installiert: <path>".
Status: no findings → secure; any → warning (security log "<n> Auffälligkeit(en) gefunden"); exception → unknown ("Prüfung fehlgeschlagen"). Warn only, never block. Message: "Dieses Gerät zeigt Anzeichen von Root/Jailbreak. Kryptographische Schlüssel könnten weniger geschützt sein. Für maximale Sicherheit ein nicht modifiziertes Gerät verwenden."

### 8.9 Full reset (home domain triggers, identity wipe order)

`disconnectNip46()` (closes live client + session key) → badges reset (badge domain) → `prefs.clear()` → `SecureKeyStore.deleteKeys()` → `deleteNip46ClientKey()` (idempotent) → `LocalKeyVault.clearAll()` (the password wrap is a full copy of the key) → portal logout → profile cache clear → `pushAndRemoveUntil(Intro)`. Each step `try/catch` (continue on error). Native exposes `IdentityRepository.wipeAll()` doing the identity part.

### 8.10 User-facing service messages (hard-coded German in Dart; add as string resources in native)

Proposed resource names (new, not in ARB): `errSignCancelledAmber`, `errAmberMissing`, `errWrongAccount`, `errNoPrivateKey`, `errAmberKeyFormat`, `errAmberInvalidPubkey(%s)`, `errAmber(%s)` (= ARB `errorAmber`), `errSignerNoEvent(%1$s actor)`, `errSignerKindChanged(%1$s actor, %2$d got, %3$d want)`, `errSignerContentChanged(%s)`, `errSignerBadSig(%s)`, `errBunkerNoAnswer` ("Der Signer hat nicht geantwortet. Ist die Signer-App geöffnet und die Freigabe bestätigt?"), `errBunkerRejected(%s)` ("Der Signer hat abgelehnt: %s"), `errModeAmberNoKey`, `errModeNip07NoKey`, `errModeBunkerNoKey`, `errNoBunkerSession`, `errNip07WebOnly`, all `Nip46Exception`/`Nip49Exception`/`LocalEasyAuthException` texts quoted in §§3.3, 6.2, 6.3, 7.1, 7.2, 8.3. Keep German as default, add en/es.

---

## 9. Security measures that must be preserved

| ID / source | Measure | Native implementation |
|---|---|---|
| Audit Fix 1 | Private keys never in plain prefs; Keystore-backed secure storage; one-time migration from plaintext prefs | SecureStore (§4.3), migration (§4.2/§4.4) |
| Audit 2 #8 | Migration mutex — concurrent first access waits | single `Mutex`-guarded migration before DI graph exposes repositories |
| `resetOnError=false` | Never wipe secrets on a decrypt error | migration/SecureStore must surface errors, never auto-reset |
| Manifest | `allowBackup=false`, no full-backup content | keep + `dataExtractionRules` |
| N1 | Amber responses verified like NIP-07/NIP-46 (account, kind, content, real signature) | `verifySignerResponse` for all external signers |
| — | Real Schnorr verification of signer output (placeholder sig rejected; 10-digit created_at) | §6.1, §7.6 |
| N2 | Bunker relays `wss://` only | BunkerPointer.parse |
| M2 | `auth_url` opened only for http(s) with host; no raw intents from network data | `launchHttpUrl` equivalent (`Intent.ACTION_VIEW` only after scheme/host check) |
| N5 | `auth_url` never logged in full (contains an approval token); log host only | logger discipline |
| M3 | Device authentication (PIN/pattern/biometric) before showing nsec or exporting ncryptsec; devices without any lock pass | `BiometricPrompt` with `BIOMETRIC_WEAK or DEVICE_CREDENTIAL` (API 30+); API 26–29: `BIOMETRIC_WEAK` + `setDeviceCredentialAllowed(true)` / `KeyguardManager.createConfirmDeviceCredentialIntent`; `KeyguardManager.isDeviceSecure == false` and no biometrics → allow; cancel/error → deny. MainActivity must be a `FragmentActivity` (androidx.biometric requirement; the Flutter app hit `no_fragment_activity`). |
| M3 | `FLAG_SECURE` while an nsec/ncryptsec is on screen (nsec dialog, export sheet) | `DisposableEffect { window.addFlags(FLAG_SECURE); onDispose { clearFlags } }` |
| M3 | Secrets copied to clipboard are cleared after 45 s if unchanged | `ClipboardManager`; on API 33+ set `ClipDescription.EXTRA_IS_SENSITIVE`; schedule clear after 45 s: if app is foreground and clip text still equals the secret → `clearPrimaryClip()` (API 28+) / set empty; background reads return null on API 29+ (same limitation as Flutter). The setup sheet's "copy ncryptsec" and npub copy are plain copies (parity). |
| M4 | Relay frames > 512 Ki chars dropped before parsing | RelaySocket |
| M5 | Root detection warns, never blocks | §8.8 |
| H4 | (web) no local nsec in browser | n/a on Android (keep flag semantics if a web build ever shares code) |
| N3 | Backup key consistency check before writing keys | `backupKeyConsistent` |
| NIP-46 | Session key not in backups; restored bunker mode has `canSign=false` until re-paired; old session key deleted on restore | §6.5 |
| NIP-46 | Only the paired signer's answers accepted; foreign senders counted; conversation-key cache only for the paired signer (no unbounded cache from relay spam) | §6.2 |
| NIP-46 | Pairing QR must not contain the client secret key (test-enforced) | §6.3 |
| NIP-46 | `close()` fails pending requests (no hanging UI); mode switch away from nip46 always clears session artifacts | §6.2, §4.1 |
| UX-security | Anti-double-trigger locks: setup busy overlay, bunker sheet busy, export lock set before device auth (parallel scrypt = 64 MiB each) | ViewModel state |
| UX-security | Resume card primary & bootstrapping lock prevent overwriting a surviving identity | §2.3 |
| UX-security | Signer switch confirmation; never delete old key on signer connect | §2.7.4 |
| Logging | Never log keys, payloads, plaintexts, full npubs in security logs (`npub` shortened `first12…last4`) | logger discipline |
| Passkey | Passkey wrap uses logN 8 and is **never exported** | §7.2 |
| Vault | `clearAll()` on reset/import/new key (password wrap = full key copy) | §4.2 |

Audit items referenced but owned elsewhere: C1/C2/C3 (admin crypto verification), H1–H3, K1, M1/H2 (central event signature verification in RelaySocket), M7/M8, N4 (manifest exports).

---

## 10. Tests & vectors to port

Fixture: `android-native/spec/fixtures/01-identity-keys-vectors.json`.

1. **`nip49_test.dart`** — official vector decrypts (pw `nostr`, logN 16 → `3501…8683`); wrong password → Nip49Exception; NFKC vector with composed and normalized password both → `11b2…8944`; round trip (logN 8); two exports differ but decrypt equal; key-security byte readable without password (insecure/secure); flipping byte 42 → MAC failure; Unicode/emoji password round trip; empty password, wrong-length key, non-hex key, `nsec1…` prefix, truncated input, log_n=255 → all Nip49Exception (log_n rejected before scrypt).
2. **`pbkdf2_equality_test.dart` / `pbkdf2_web_equality_test.dart`** — 8 vectors (fixture), 0 iterations / 0 keyBytes → error. (WebCrypto parts: n/a.)
3. **`nip44_vectors_test.dart`** — all official vectors + extra: random nonce → different payloads; wrong conversation key fails; private key 0 / n / 0xff…ff rejected with a valid pubkey (`Keychain('01'*32).public`); short hex rejected.
4. **`nip07_signer_test.dart`, `stage_a_security_test.dart` (N1), `nip46_signing_service_test.dart` (Signer groups)** → port as `SignerResponseVerifierTest`: good case takes signer id/sig/created_at (1786000000) and normalized tags; malformed tags → falls back to caller tags and placeholder sig is rejected; placeholder sig rejected; other pubkey → WrongAccount; changed kind/content → SigningException; empty sig → SigningException; NIP-46 actor text contains "Der Signer" and not "Erweiterung"; Nip46TimeoutException → message contains "Signer-App"; Nip46RemoteException("user rejected") → message contains it.
5. **`stage_a_security_test.dart` (N3)** → `backupKeyConsistent`: consistent true; foreign npub false; nsec/priv mismatch false; `zz-not-hex` false.
6. **`nip46_client_test.dart`** (fake relay + real NIP-44 signer; port with a fake `RelayLink` and short timeouts):
   - sign_event request decrypted correctly by the signer (kind 21000, content "Badge-Signatur"), response mapped; get_public_key returns user key; connect sends 4 params `[signerPk, secret, perms, metadataJson]` with every signed kind, and `""` when no secret; ping true on `pong`, false on silence; CLOSED drops link and closes socket, next call reconnects.
   - Remote error propagated as Nip46RemoteException; delayed answer on same socket; silent nip44/sign with or without relay ACK → exactly 1 request then timeout; duplicate rejection from one relay counted once while another relay answers; late socket close doesn't remove replacement link; remote "denied" not retried; `blocked:`/`rate-limited:`/`banned:` → relay closed and skipped during cooldown, even across reconnects; nip44 logs contain "Rahmen-Bytes", "Relay-Verbindungen", "Antwort nach" but never the plaintext/"Nutzlast"; `auth_url` interim answer → `waitingForApproval=true` on timeout, request sent once; silence → timeout with method name; foreign sender → `foreignSender>0`, `undecryptable==0`; all relays reject → Nip46RelayRejectedException with reason in message, faster than timeout, signer never sees it; one relay rejecting while another works → success; unpaired client sends nothing; unreachable relay / socket dying after subscribe → Nip46Exception containing "erreichbar"; close() fails pending.
   - Multiple relays: dead relay doesn't block; duplicate answers from two relays → single result; parallel calls open each relay once; request goes to all relays.
   - auth_url: callback receives URL from `error` field, request still completes later.
   - nostrconnect pairing: secret echo pairs and sets remote key; wrong secret → timeout, unpaired.
   - BunkerPointer: parse full (whitespace, two relays, secret), npub key accepted, persist form drops secret and encodes relay, invalid inputs rejected; nostrconnect URI contains secret/perms/`sign_event%3A21000`/`name=Einundzwanzig%20Meetup`, url/image round-trip, relays list; app name with `+ Café &` encodes without `+`.
7. **`nip46_signing_service_test.dart` (Modus und Sitzung)**: restoreNip46 → mode nip46, isExternal, npub, stored `"nip46"`; canSign needs key+uri+hex; broken bunker uri → throws and mode stays local; disconnect clears 3 prefs + session key; useLocalMode clears bunker session; pairing URI has scheme/host=client pubkey/secret/perms/relays/name/url/https image and no client secret; two pairings → different secret & client key, same metadata, cancel keeps mode local; pairing relays respect disabled defaults, use custom relays, ≤ 3, fallback non-empty; backup session has `bunker_uri` and no key; restore without key → identity yes, canSign false; restore without key deletes old key.
8. **`signing_mode_test.dart`**: `amber` stays amber (Android); `local`; absent → local; unknown → local; restoreNip07 → `"nip07"`; canSign false without extension; disconnectNip07 clears; Nip07 connect → Missing; isExternalSigner for amber/nip07/nip46 true, local false.
9. **`recommended_existing_path_test.dart`**: Android without Amber → bunker; with Amber → amber.
10. **`signer_nip44_test.dart`**: Local signer NIP-44 round trip; matches `Nip44.conversationKey(priv, peer)`; NIP-46 signer calls `nip44_encrypt`/`nip44_decrypt` with `(peer, value)`; probe failure logs step and reason without payload; mismatch logs "stimmt nicht überein"; canNip44 true in local mode with key.
11. **`local_easy_auth_test.dart`**: register creates key, `ncryptsec1` wrap, nickname, onboarded; delete key then unlockWithPassword restores same priv; rejects "Anon" and short password; importNcryptsec of own export gives same npub; register clears old passkey wrap and binds npub.
12. **`local_key_vault_test.dart`**: empty vault; separate wraps; clearPasskeyWrap keeps password wrap; clearAll removes all incl. binding; match requires binding; orphan wrap without binding → false; passkey wrap without credential id → hasPasskeyWrap false; allowed hosts (`PasskeyPrfService.allowedWebHosts`) contain no host ending in `.github.io`, `.pages.dev`, `.netlify.app`, `.vercel.app` (web-only, §7.5; drop natively).
13. **`user_profile_test.dart`**: isOnboarded truth table (`""`, "Anon" false; "Satoshi", isNostrVerified, isAdminVerified true); prefs `nostr` npub is loaded without a private key (`hasNostrKey=false`); restored remote signer keeps npub, `canSign=false`.
14. **`app_review_demo_test.dart`**: matches/isDemoLogin tables (fixture).
15. **`intro_keyboard_test.dart`**: Intro at 402×874 dp with a dialog + 346 dp IME inset must not overflow; TextField bottom ≤ 528 → port as Compose UI test (no exceptions, dialog visible above IME).
16. **`passkey_rp_id_web_test.dart`** (`@TestOn('browser')`, run with `flutter test --platform chrome`) — **n/a on Android, do not port**: it asserts `relyingPartyId() == Uri.base.host` in the browser, that the test host (`localhost`/`127.0.0.1`) gives `isEnvironmentAllowed == true`, and that `allowedWebHosts` does not contain `razue.github.io`. All three exercise browser-only `kIsWeb` branches (§7.5). Native replacement: a plain unit test asserting the passkey JSON builders use the constant rpId `einundzwanzig.space` (registration `rp.id` and authentication `rpId`).
17. New native tests to add: Flutter-storage migration (fixture XML files for prefs incl. both list encodings; secure-storage blobs generated on a device with the Flutter build), NIP-01 serializer escaping, Amber result parsing (npub / hex / JSON `{"result":…}`), passkey JSON builders.

---

## 11. Android capabilities & recommended APIs

| Capability | Need | Recommended |
|---|---|---|
| Internet | relays (NIP-46) | `INTERNET`; OkHttp WebSocket (shared RelaySocket) |
| Package visibility | Amber detection and intents | manifest `<queries>` (§6.6) |
| External signer | NIP-55 | `ActivityResultContracts.StartActivityForResult`, `ContentResolver.query` on `Dispatchers.IO` |
| Secure key storage | nsec/privHex/session key/wraps | Android Keystore AES-GCM (StrongBox if available) or Tink; DataStore for non-secrets |
| Legacy data migration | read Flutter prefs + flutter_secure_storage | vendored flutter_secure_storage 10.3.4 read path (BSD-3) in `:data:migration` |
| secp256k1 / BIP-340 | keys, sign, verify, ECDH x | `fr.acinq.secp256k1:secp256k1-kmp-jni-android` (in catalog) |
| Hashes, HKDF, HMAC, ChaCha20, scrypt, PBKDF2, EC | NIP-44/49, backup | BouncyCastle `bcprov-jdk18on` (in catalog) + `javax.crypto.Mac`; XChaCha20-Poly1305 via libsodium/Tink or HChaCha20+BC |
| Unicode NFKC | NIP-49 | `java.text.Normalizer` |
| Bech32 | npub/nsec/ncryptsec | small own implementation (no 90-char limit for ncryptsec) |
| Passkeys + PRF | second unlock | `androidx.credentials:credentials` (+ `credentials-play-services-auth`), `com.google.android.gms:play-services-fido` for availability; assetlinks.json on `einundzwanzig.space` |
| Device auth | M3 | `androidx.biometric:biometric` (Activity must extend `FragmentActivity`) |
| Screenshot block | M3 | `WindowManager.LayoutParams.FLAG_SECURE` |
| Clipboard | copy secrets | `ClipboardManager` + `EXTRA_IS_SENSITIVE` (API 33+) |
| File save | ncryptsec `.txt` | SAF `ActivityResultContracts.CreateDocument("text/plain")` |
| QR rendering | nostrconnect pairing | ZXing core `QRCodeWriter` (in catalog), 200 dp, white quiet zone |
| Locale | intro picker | `AppCompatDelegate.setApplicationLocales` / `LocaleManager` (API 33+) |
| Coroutines | all I/O, crypto off main thread | `Dispatchers.Default` for scrypt/PBKDF2/Schnorr batches |
| Root detection | M5 | `java.io.File.exists()`, `Runtime.exec("getprop ro.build.tags")` (optional) |
| Orientation | phones portrait (<600 dp smallest width), tablets free | carried over from Flutter MainActivity (shell domain) |

No camera, NFC, location, or notification permissions are needed by this domain.

---

## 12. Cross-domain dependencies

Calls **from** this domain:
- Backup (`BackupService.createBackup(context)`, `restoreBackup(context)`) — setup sheet step 1, existingMore, Intro.
- Meetups (`MeetupService.fetchMeetups()`, `MeetupSearchSheet` returning city names) — setup meetup step; profile meetup picker.
- Badge model (`MeetupBadge.saveBadges`) — review demo; Badge sync (`BadgeSyncService.trigger` via `onSignerReady`).
- Relay config (`RelayConfig.getActiveRelays`, `defaultRelays`) and RelaySocket (WebSocket transport, frame limit) — NIP-46.
- Logger (`AppLogger.info/warn/security/debug`, entries/export for tests).
- App shell/navigation (AppShell route), theme/UI kit, l10n, LocaleController (`app_locale`).
- Admin verifier (`AdminStatusVerifier`, via `UserProfile.reVerifyAdmin`).

Called **by** other domains (public API this domain must provide):
- `SigningService.signEvent` — badge security (21000/21001), badge claim (21002), platform proof & distrust (21003), portal login (22242), chat NIP-42 auth (22242; timeout depends on mode: local 8 s, nip46 = 60 s + 20 s, others 25 s), chat messages (9, 9021, 9022), event chat (1111), RSVP (31925), calendar (31922/31923, delete kind 5), promotion claim (30021), reputation/admin registry/badge sync (30078), co-attendance (30079), news reactions (7), zaps (9734), Blossom upload auth (24242).
- `SigningService.pubkeyHex()` — chat, event calendar, RSVP, calendar, badge session, news reactions, badge sync.
- `SigningService.npub()`, `canSign()`, `getMode()`, `isExternalSigner` — home (portal auto-login only for local), portal API, platform proof, promotion claim, admin registry, rolling QR, coattendance, satoshiduell, community hub, event badge auth, admin status verifier, diagnostics (logs mode name only), backup, badge sync.
- `SigningService.nip44Encrypt/nip44Decrypt/canNip44` — badge sync, log screen.
- `SigningService.onSignerReady` (badge sync), `onNip46AuthUrl` (app root).
- `SecureKeyStore.getNsec/getNpub/getPrivHex/saveKeys/deleteKeys/deleteNip46ClientKey/hasKey/ensureMigrated` — backup, badge sync (`_localKeyAgrees`), home reset, splash.
- `LocalKeyVault.clearAll` — home reset.
- `UserProfile.load/save/reVerifyAdmin`, `isOnboarded`, `hasCustomNickname` — everywhere.
- `AppReviewDemo.matches/showSuccess` — QR scanner, meetup verification.
- `DeviceIntegrityService.check/warningMessage` — home.
- `ScreenSecure` — any screen showing secrets.
- `NostrService.verify/signBadge/shortenNpub/isValidNpub/isValidNsec/npubToHex/hexToNpub` — badge & verification domains.
- `Nip44`, `Nip49`, `Pbkdf2`, bech32/NIP-19, NIP-01 serializer, Schnorr — shared crypto used by badge sync (08), backup, chat.

---

## 13. Proposed Kotlin modules & public API

Aligned with the existing `android-native` layout (`:core:*`, `:data:*`, `:feature:*`, namespace root `space.einundzwanzig.meetup`, minSdk 26, Hilt).

```
:core:crypto      space.einundzwanzig.meetup.core.crypto     (pure JVM-testable)
  Hex, Bech32, Nip19, Schnorr(secp256k1-kmp), NostrJson(NIP-01 serializer), Nip01,
  Nip44, Nip49, Pbkdf2, SecureRandomHex, UriComponentEncoder(dart-compatible)
:core:nostr       models shared with other domains: SignedEvent, UnsignedEvent, Tag helpers
:core:ui          ScreenSecure (FLAG_SECURE), SecretClipboard   (no :core:security module)
                  DeviceAuthenticator, DeviceIntegrityService → :data:identity (ARCHITECTURE.md module map)
:data:migration   LegacyFlutterPrefsReader, LegacyFlutterSecureStorageReader (vendored), MigrationRunner
:data:identity    IdentityPrefs (typed view on :core:data KeyValueStore), SecureKeyStore (on :core:data SecureStore), LocalKeyVault, SigningService,
                  signer/{NostrSigner, LocalNostrSigner, AmberNostrSigner, Nip46NostrSigner, Nip07Stub, SignerResponseVerifier},
                  nip55/Nip55Bridge, nip46/{BunkerPointer, Nip46Client, Nip46RelayLink, Nip46ClientMetadata, exceptions},
                  passkey/PasskeyPrfService, easyauth/LocalEasyAuth, profile/UserProfileRepository, review/AppReviewDemo
:feature:identity Splash/Intro/IdentitySetup screens + ViewModels, BunkerConnectSheet, KeyManagementSection
                  (used by :feature:profile), NsecDialog, NcryptsecExportSheet, KeyEducationDialog, AuthUrlSnackbarHost
```

Key signatures (suspend = main-safe):

```kotlin
// :core:crypto
object Nip19 {
  fun encodePubkey(hex: String): String
  fun encodePrivkey(hex: String): String
  /** Returns "" if HRP != npub (Dart parity); throws on bad checksum. */
  fun decodePubkey(bech: String): String
  fun decodePrivkey(bech: String): String
}
object Schnorr {
  fun publicKey(privHex: String): String                       // x-only lowercase
  fun sign(idHex: String, privHex: String, aux: ByteArray = SecureRandomHex.bytes(32)): String
  fun verify(pubHex: String, idHex: String, sigHex: String): Boolean
}
object Nip01 {
  fun serialize(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String
  fun eventId(...): String
  fun sign(kind: Int, tags: List<List<String>>, content: String, privHex: String, createdAt: Long = nowSeconds()): SignedEvent
  /** Exact port of nostr 1.5.0 isValid(): 10-digit created_at, id equality, Schnorr verify. */
  fun isValid(e: SignedEvent): Boolean
}
object Nip44 {
  fun conversationKey(privHex: String, pubHex: String): ByteArray
  fun encrypt(plaintext: String, conversationKey: ByteArray, nonce: ByteArray? = null): String
  fun decrypt(payload: String, conversationKey: ByteArray): String
  fun calcPaddedLen(n: Int): Int
  internal fun messageKeys(ck: ByteArray, nonce: ByteArray): MessageKeys
}
enum class KeySecurity(val byte: Int) { INSECURE(0), SECURE(1), UNKNOWN(2) }
object Nip49 {
  const val DEFAULT_LOG_N = 16
  suspend fun encrypt(privHex: String, password: String, logN: Int = DEFAULT_LOG_N, keySecurity: KeySecurity = KeySecurity.UNKNOWN): String
  suspend fun decrypt(ncryptsec: String, password: String): String
  fun keySecurityOf(ncryptsec: String): KeySecurity
}
object Pbkdf2 { fun hmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray }
object DartUri { fun encodeQueryComponent(s: String): String; fun encodeComponent(s: String): String }

// :data:identity
enum class SigningMode(val stored: String) { LOCAL("local"), AMBER("amber"), NIP07("nip07"), NIP46("nip46") }
enum class RecommendedExistingPath { NIP07, AMBER, BUNKER }

interface NostrSigner {
  suspend fun pubkeyHex(): String?
  suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String): SignedEvent
  suspend fun nip44Encrypt(peerHex: String, plaintext: String): String = throw UnsupportedOperationException()
  suspend fun nip44Decrypt(peerHex: String, payload: String): String = throw UnsupportedOperationException()
}
fun verifySignerResponse(signed: JsonObject, expectedPubkeyHex: String, kind: Int,
                         tags: List<List<String>>, content: String, fallbackCreatedAt: Long, actor: SignerActor): SignedEvent

@Singleton class SigningService {
  val authUrlEvents: SharedFlow<String>           // replaces onNip46AuthUrl (sheet may override/intercept)
  val signerReady: SharedFlow<Unit>               // replaces onSignerReady
  suspend fun getMode(): SigningMode
  suspend fun isExternalSigner(): Boolean
  suspend fun recommendedExistingPath(): RecommendedExistingPath
  suspend fun signEvent(kind: Int, tags: List<List<String>>, content: String): SignedEvent
  suspend fun pubkeyHex(): String?
  suspend fun npub(): String?
  suspend fun canSign(): Boolean
  suspend fun nip44Encrypt(peerHex: String, plaintext: String): String
  suspend fun nip44Decrypt(peerHex: String, payload: String): String
  suspend fun canNip44(): Boolean
  suspend fun connectAmber(): AmberConnectResult
  suspend fun restoreAmber(npub: String)
  suspend fun disconnectAmber()
  suspend fun restoreNip07(npub: String)
  suspend fun disconnectNip07()
  suspend fun useLocalMode()
  suspend fun connectNip46Bunker(uri: String): Nip46ConnectResult
  suspend fun startNip46Pairing(): Nip46Pairing
  suspend fun completeNip46Pairing(p: Nip46Pairing): Nip46ConnectResult
  suspend fun nip46SessionAlive(): Boolean
  suspend fun nip46SessionForBackup(): Map<String, String>?
  suspend fun restoreNip46(npub: String, bunkerUri: String, clientSecretKeyHex: String? = null)
  suspend fun disconnectNip46()
  suspend fun nip46PairingRelays(): List<String>
}

class Nip46Client(clientSecretKeyHex: String, relays: List<String>, remoteSignerPubkey: String? = null,
                  onAuthUrl: (String) -> Unit = {}, timeouts: Nip46Timeouts = Nip46Timeouts(),
                  openRelay: suspend (String) -> Nip46RelayLink = ::okHttpLink) {
  val clientPubkeyHex: String; val remoteSignerPubkey: String?; val isPaired: Boolean
  suspend fun connect(secret: String? = null)
  suspend fun getPublicKey(): String
  suspend fun signEvent(unsigned: JsonObject): JsonObject
  suspend fun nip44Encrypt(peerHex: String, plaintext: String): String
  suspend fun nip44Decrypt(peerHex: String, payload: String): String
  suspend fun ping(): Boolean
  suspend fun awaitPairing(secret: String): String
  suspend fun close()
  companion object { const val EVENT_KIND = 24133; val SIGNED_KINDS: List<Int>; val REQUESTED_PERMS: String
                     fun generateClientKey(): String; fun generateSecret(): String }
}
interface Nip46RelayLink { val incoming: Flow<String>; fun send(frame: String): Boolean; suspend fun close() }

data class BunkerPointer(val remoteSignerPubkey: String, val relays: List<String>, val secret: String? = null) {
  fun toBunkerUri(): String
  companion object { fun parse(input: String): BunkerPointer; fun normalizePubkey(v: String): String
    fun buildNostrConnectUri(clientPubkeyHex: String, relays: List<String>, secret: String, perms: String,
                             appName: String, appUrl: String? = null, appImage: String? = null): String }
}

@Singleton class Nip55Bridge {   // Amber
  fun isInstalled(): Boolean
  suspend fun getPublicKey(): String                       // raw value (npub / hex / JSON)
  suspend fun signEvent(unsignedJson: String, npub: String): Pair<String /*event*/, String? /*sig*/>
  suspend fun nip44(type: String, peerHex: String, value: String, npub: String): String
}

@Singleton class SecureKeyStore {
  suspend fun saveKeys(nsec: String, npub: String, privHex: String)
  suspend fun getNsec(): String?; suspend fun getNpub(): String?; suspend fun getPrivHex(): String?
  suspend fun loadKeys(): Pair<String, String>?; suspend fun hasKey(): Boolean; suspend fun deleteKeys()
  suspend fun saveNip46ClientKey(hex: String); suspend fun getNip46ClientKey(): String?; suspend fun deleteNip46ClientKey()
}
@Singleton class LocalKeyVault { savePasswordWrap(ncryptsec, npub); getPasswordWrap(); getPasswordWrapNpub();
  passwordWrapMatchesNpub(npub); savePasskeyWrap(ncryptsec, credentialId); getPasskeyWrap(); getPasskeyCredentialId();
  hasPasskeyWrap(); clearPasskeyWrap(); clearAll() }          // all suspend
@Singleton class LocalEasyAuth { suspend fun register(nickname: String, password: String)
  suspend fun addPasskeyWrap(activity: Activity, displayName: String)
  suspend fun unlockWithPassword(password: String); suspend fun unlockWithPasskey(activity: Activity) }
@Singleton class PasskeyPrfService { suspend fun isSupported(): Boolean
  suspend fun register(activity: Activity, userIdHex: String, userName: String): PasskeyPrfResult
  suspend fun unlock(activity: Activity, credentialIds: List<String>): PasskeyPrfResult }
@Singleton class NostrKeyService { suspend fun generateKeyPair(): GeneratedKeys; suspend fun importNsec(s: String): GeneratedKeys
  suspend fun importPrivHex(h: String): GeneratedKeys; suspend fun importNcryptsec(s: String, pw: String): GeneratedKeys }
@Singleton class UserProfileRepository { suspend fun load(): UserProfile; suspend fun save(p: UserProfile); val profile: Flow<UserProfile> }
@Singleton class IdentityRepository { suspend fun wipeAll() }   // reset helper (§8.9)
object AppReviewDemo { const val PAYLOAD = "21review:einundzwanzig-meetup-demo"; fun matches(raw: String?): Boolean
  fun isDemoLogin(u: String, p: String): Boolean; fun shouldOfferSignIn(): Boolean }   // + suspend signIn via injected deps
fun backupKeyConsistent(nsec: String, npub: String, privHex: String): Boolean
```

ViewModels: `IdentitySetupViewModel` (state machine §2.3.1; one-shot effects: NavigateHome, OpenBunkerSheet, OpenMeetupPicker, ShowSecureSheet, SaveKeyFile(name, bytes), Snackbar), `BunkerConnectViewModel` (§2.5; survives configuration change, cancels pairing in `onCleared` unless adopted), `KeyManagementViewModel` (§2.7).

---

## 14. Risks / compatibility pitfalls

1. **Upgrade-in-place data migration** is the single biggest risk: same `applicationId` + same release signing key are mandatory; Flutter prefs live under `flutter.` keys with special list/double encodings (two list formats); secrets are AES-GCM/RSA-OAEP (or legacy AES-CBC/RSA-PKCS1) blobs keyed under a Base64-looking prefix. A failed or partial migration must never delete or overwrite (no new key may be generated while old data is unread). Test on real devices upgraded from the Flutter 1.7.2 build.
2. **NIP-01 serialization**: any JSON library that escapes `/`, non-ASCII or uses uppercase `\u` escapes yields different event ids → remote clients (Dart, JS) reject events. `org.json` is unsafe for this.
3. **Signer-response validity is stricter than plain BIP-340**: `created_at` must have exactly 10 digits and the id string must equal the recomputed lowercase id. Relaxing it changes behaviour; tightening it breaks parity.
4. **`Nip19.decodePubkey` returns `""` for wrong HRP** (no exception). `restoreAmber/restoreNip07/restoreNip46` with an `nsec` string would store an empty hex; `LocalNostrSigner.pubkeyHex` may return `""`. Native should keep the observable behaviour where tests rely on it but MAY add validation in restore paths (log + reject) — document the deviation.
5. **URL encoding**: Dart `encodeQueryComponent` vs Kotlin `URLEncoder`/`Uri.encode` differ (`*`, `~`, space). The app name must use `%20` (Clave shows `+` literally). The image URL's `%40` must become `%2540`. Bunker URIs are persisted encoded and re-parsed on every start.
6. **NIP-46 perms list is incomplete** (kinds 1, 5, 7, 9, 1111, 9021, 9022, 9734, 24242, 31925 not requested) → signer prompts for chat/RSVP/news/zaps/uploads/deletes. Keeping the exact list preserves current behaviour; extending it only affects **new** pairings and is wire-safe — decide explicitly. The metadata/perms are not re-sent for restored sessions.
7. **NIP-46 response events are not signature-checked** (by design); porting the fake-signer tests requires keeping that (they use dummy `sig`). Adding verification would break those tests and possibly some signers.
8. **Timeout semantics**: `get_public_key` uses the 75 s connect timeout; chat NIP-42 auth uses `signTimeout + 20 s` for bunker mode. No RPC is retried; a relay `OK false` from all relays fails fast; cooldown only for `blocked:`/`banned:`/`rate-limited:`.
9. **Pairing completion** matches the echoed secret in `result` only (older signers that answer `"ack"` to nostrconnect won't pair) — parity.
10. **Amber specifics**: `get_public_key` may return npub, hex, or JSON; ContentResolver projection order differs between SIGN_EVENT (`[event, "", npub]`) and NIP44 (`[value, peer, npub]`); `current_user` is the **npub**; only one foreground request at a time; `<queries>` required; process death while Amber is in front loses the pending result.
11. **scrypt memory** (64 MiB for logN 16) can OOM on low-RAM devices if done on the Java heap; the profile export must keep the "lock before device-auth" rule to avoid parallel runs.
12. **Bech32 length**: `ncryptsec` is 162 chars; a bech32 library with the BIP-173 90-char limit will reject it.
13. **PBKDF2 password bytes**: must be UTF-8 bytes; JCA `PBEKeySpec(char[])` conversions differ by provider for non-ASCII (emoji = surrogate pairs) → existing backups unreadable.
14. **Passkeys**: RP id `einundzwanzig.space` requires `assetlinks.json` with the release cert (currently listed as missing → passkey creation likely fails today → "unavailable" path). PRF output hex must be lowercase. `user.id` is padded base64url (works with Credential Manager today; changing it only affects new credentials). Passkey wraps from the Flutter app open natively only if RP id, salt and credential id are unchanged.
15. **Overwrite hazards inherited from Dart**: "Neu hier" while a resumable identity exists, and back-from-passkey → "Loslegen" again, both generate a new key and overwrite the stored one (the password wrap is replaced too). Keep or guard, but decide consciously.
16. **Mixed state** (local key + external signer active) is legitimate: never delete the local key on signer connect; backups store it; the profile hides nsec/export in external mode.
17. **User-visible German strings hard-coded in services** (and `WrongAccountException` always naming Amber) — localize natively without changing semantics; Dart prefixes like `"Exception: "`/`"FormatException: "` should not be reproduced.
18. **Clipboard auto-clear** cannot read the clipboard in background on API 29+; parity with Flutter (no clear) unless the app is foreground.
19. **BiometricPrompt** requires a `FragmentActivity`; the current native `MainActivity` is a `ComponentActivity` → must change before M3 works.
20. **Mode `nip07` on Android** (from web backups) must not crash: identity shown, `canSign=false`, signer re-connect offered.
21. **Badge-sync coupling**: `onSignerReady` must fire on every path listed in §8.1, otherwise sync never starts after connecting/restoring a signer.
22. **`hasNostrKey` is not persisted** and means "local nsec present", not "has identity"; `isNostrVerified` is the identity flag. Using the wrong one breaks onboarding routing for Amber/bunker users.
23. **Review demo** credentials ship in the binary; gate `signIn` itself (not only the UI) by build flag, as Dart does.
