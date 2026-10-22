# 14 — Android platform, release engineering and Flutter → native data migration

Porting spec for everything that is not a feature domain: the Android manifest and resources, the Flutter plugin
layer, the home-screen widget, the **in-place data migration** from the Flutter app (same `applicationId`
`space.einundzwanzig.meetup`), signing/versioning/distribution, and the platform-level security items.

Sources read completely: `android/` (all Gradle files, `gradle.properties`, `settings.gradle.kts`, `.gitignore`,
`app/src/{main,debug,profile}/AndroidManifest.xml`, `MainActivity.kt`, `MeetupWidgetProvider.kt`,
`WidgetRouterActivity.kt`, every file under `res/`), `pubspec.yaml`, `pubspec.lock`, `assets/`, `BUILD_APK.md`,
`SecurityAudit.md`, `SecurityChangelog.md`, `UEBERGABE.md`, `docs/ALTSTORE_PAL.md`, `.github/workflows/build_apk.yml`.
Followed into: every `SharedPreferences` / `FlutterSecureStorage` call site in `lib/` (371 prefs call sites, 67
secure-storage call sites, all constants resolved by script), `lib/main.dart`, `lib/services/widget_service.dart`,
`screen_secure.dart`, `device_integrity_service.dart`, `features.dart`, `passkey_prf_service.dart`, the git history
of `pubspec.lock` and of every storage key, and the plugin sources in `~/.pub-cache`:
`shared_preferences_android-2.4.20`, `flutter_secure_storage-10.3.4` (all 2 079 lines of `FlutterSecureStorage.java`,
`ciphers/*`, `crypto/*`, `MigrationBackup.java`), `flutter_secure_storage-9.2.4` (downloaded from pub.dev for
reading), `home_widget-0.6.0`, and the manifests of all Android plugins. The Flutter Gradle plugin
(`~/development/flutter/packages/flutter_tools/gradle`) for the split-ABI version codes.

Machine-readable companion: `spec/fixtures/14-platform-migration-vectors.json` (crypto vectors verified with Node
and JDK 17, XML samples, the full key inventories of §4, widget formatting vectors). See §8.

Other specs referenced as "spec NN": 01 identity/keys, 02 badges/claim, 03 badge wallet UI, 04 trust/reputation,
05 social graph/admin, 06 proofs, 07 Nostr infra/profile, 08 backup/sync, 09 meetups/portal, 10 calendar/events
(widget writers in §2.10), 11 chat/news/community, 12 shell/home/settings, 13 bitcoin tools.

---

## 0. Critical findings (read first)

| # | Finding | Consequence for the native app |
|---|---|---|
| **F1** | **EncryptedSharedPreferences data from app ≤ 1.6.9 is never migrated by flutter_secure_storage 10.3.4 with `migrateWithBackup: true`.** All releases from 2026-02-19 to 2026-10-10 used FSS **9.2.4 with `encryptedSharedPreferences: true`** (git history of `pubspec.lock` and of `AndroidOptions`). 10.3.4 skips its legacy ESP branch when `migrateWithBackup` is set (`continueInitialize`: `if (!isAlreadyMigrated && !config.shouldMigrateWithBackup())`), and its only other ESP path (step 7 of `migrateNonBiometricWithBackup`) is reached only through `handleKeyMismatch`. On an ESP device `canSkipMarkerlessMigration()` finds no entry whose *raw* name contains the prefix (ESP encrypts names with AES-SIV) and returns `true` ("fresh install"), so a new empty GCM store is used and the ESP entries stay **orphaned in the same file** `FlutterSecureStorage.xml`. Even without `migrateWithBackup`, ESP's `getAll()` throws on the first non-ESP entry of a mixed file. Commit `cfb43ff` ("Ein fehlgeschlagenes Lesen hat einen neuen Schlüssel über den alten geschrieben, die Sammlung wirkte leer", 2026-10-13) is consistent with this. Derived from source; **verify on a device** (install 1.6.9+28 → create key/badges → update to 1.7.2+31 → keys/badges appear lost). | The native reader **must** also read ESP entries (per known key, §4.6.4) and treat them as primary when the 10.x store has no value (identity secrets only when their npub matches the profile the Flutter prefs still reference), otherwise as recovery candidates (§4.8). This can give users back an identity or badge key that the Flutter 1.7.x app lost. |
| **F2** | Component names are part of the upgrade contract. Placed widgets are bound to `space.einundzwanzig.meetup.MeetupWidgetProvider`; launcher shortcuts to `.MainActivity`; stale widget `PendingIntent`s target `.WidgetRouterActivity`. | Keep these three FQCNs in `:app` (§1.5, §3.2). Renaming the receiver silently deletes every placed widget. |
| **F3** | Signing continuity. CI signs with the keystore from secrets `KEYSTORE_BASE64/…_STORE_PASSWORD/…_KEY_PASSWORD/…_KEY_ALIAS`; local builds without `android/key.properties` silently fall back to the **debug key**. | Native release must be signed with the **same** key or the update is rejected (and on a forced reinstall every Keystore-bound secret is gone). No debug fallback for release. Pin the certificate SHA-256 in CI (§5.2.1). |
| **F4** | `versionCode`: official APKs carry `31` (1.7.2+31), but `flutter build apk --split-per-abi` (documented in `BUILD_APK.md`) produces `1031 / 2031 / 4031` (`ABI_VERSION * 1000 + versionCode`). The native `app/build.gradle.kts` currently sets `versionCode = 200`. | Use `versionCode ≥ 10 000`; recommended scheme `major*10000 + minor*100 + patch` → 2.0.0 = **20000** (§5.2.2). |
| **F5** | Flutter `minSdk = 23`; native `minSdk = 26`. | Devices on Android 6.0–7.1 cannot install the update (`INSTALL_FAILED_OLDER_SDK`). They keep the Flutter app and its data untouched. Communicate in release notes (§5.3). |
| **F6** | An app reset (spec 12) clears `app_prefs`. If the migration marker lived there and the Flutter files still existed, the next start would **re-import the old identity**. | Migration state lives in `noBackupFilesDir`, never in `app_prefs`; reset also deletes the Flutter legacy files and aliases (§4.7). |
| **F7** | Flutter draws dialogs in the activity window, so `FLAG_SECURE` on the activity covered the nsec dialog. In Compose, `Dialog` / `ModalBottomSheet` / `AlertDialog` are **separate windows**; their default `SecureFlagPolicy.Inherit` copies the flag only if the activity window already had it when the dialog window was created. | Secret-showing dialogs/sheets must set `securePolicy = SecureFlagPolicy.SecureOn` explicitly in addition to the window flag (§6). |
| **F8** | `local_auth` needed `FlutterFragmentActivity` (`MainActivity.kt` comment). `androidx.biometric.BiometricPrompt` also needs a `FragmentActivity`. The native `MainActivity` currently extends `ComponentActivity`. | Native `MainActivity` must extend `androidx.fragment.app.FragmentActivity` (or `AppCompatActivity`). |
| **F9** | Passkeys use RP ID `einundzwanzig.space` (`passkey_prf_service.dart`). `https://einundzwanzig.space/.well-known/assetlinks.json` returns **404** (checked 2026-10-20). Credential Manager requires Digital Asset Links for the RP ID. | Passkey registration on Android fails today for Flutter and native alike. Publish assetlinks with the release certificate (§5.4). |
| **F10** | The native debug build uses `applicationIdSuffix ".native.debug"`, so it can never see the Flutter data. | Add a `migrationTest` build type without suffix (§5.2.5) to test the upgrade on devices. |
| **F11** | Widget/dashboard numbers come from Dart `toStringAsFixed`, which rounds the exact binary value: `1.45.toStringAsFixed(1) == "1.4"`, while Java `String.format("%.1f", 1.45) == "1.5"`. | Use the shared `Double.toStringAsFixed` (`:core:common`, `core.common.dart`; exact binary value, HALF_UP, sign kept incl. `-0.0`) (§3.1.4, fixture `widget_formatting`). |

---

## 1. Manifest inventory

### 1.1 Flutter app — `android/app/src/main/AndroidManifest.xml`

| Element | Attributes / content | Purpose (source comment) | Native |
|---|---|---|---|
| `uses-permission` | `android.permission.INTERNET` | Relays, HTTP | keep |
| `uses-permission` | `android.permission.NFC` | NFC badges | **drop** (NFC is compiled out: `lib/features.dart` `kNfcEnabled = false`, spec 02 keeps `BuildConfig.NFC_ENABLED = false`). Re-add together with the flag. |
| `uses-permission` | `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | nearby meetups, organizer session location (geolocator) | keep both (spec 09 §11: request both, coarse is sufficient) |
| `uses-feature` | `android.hardware.nfc required=false` | | drop with the permission |
| `queries/package` | `com.greenart7c3.nostrsigner` | Amber install check (NIP-55) | keep (spec 01 §6.6) |
| `queries/intent` | `VIEW` + `scheme=nostrsigner` | any NIP-55 signer | keep |
| `queries/intent` | `VIEW` + `scheme=https` | `canLaunchUrl` for portal/news links (Android 11 package visibility) | keep |
| `queries/intent` | `VIEW` + `scheme=lightning` | `payLightningInvoice`: `resolveActivity` before the chooser | keep |
| `application` | `label="21Meetup"`, `name="${applicationName}"` (FlutterApplication), `icon=@mipmap/ic_launcher`, `allowBackup="false"`, `fullBackupContent="false"`, `networkSecurityConfig=@xml/network_security_config` | | keep backup flags + NSC; add `dataExtractionRules`; label see §1.6 |
| `activity .MainActivity` | `exported=true`, `launchMode=singleTop`, `theme=@style/LaunchTheme`, `configChanges=orientation\|keyboardHidden\|keyboard\|screenSize\|smallestScreenSize\|locale\|layoutDirection\|fontScale\|screenLayout\|density\|uiMode`, `hardwareAccelerated=true`, `windowSoftInputMode=adjustResize`; meta-data `io.flutter.embedding.android.NormalTheme=@style/NormalTheme` | Flutter host. Extends `FlutterFragmentActivity`. Dynamic orientation: portrait when `smallestScreenWidthDp in 1..599`, else unspecified (`applyPhoneOrientation`, also in `onConfigurationChanged`). | keep FQCN, `singleTop`, `adjustResize`; drop `configChanges` (Compose recreates cleanly); port the orientation rule (§1.5) |
| `intent-filter` | `MAIN` / `LAUNCHER` | | keep |
| *(removed)* | NDEF intent filter | Security Audit N4: removed because NFC is off | do not add |
| `activity .WidgetRouterActivity` | `exported=false`, `excludeFromRecents=true`, `noHistory=true`, `taskAffinity=""`, `theme=@android:style/Theme.Translucent.NoTitleBar` | invisible widget click router (MIUI intent replay workaround) | keep FQCN and attributes (§3) |
| `meta-data` | `flutterEmbedding=2` | Flutter | drop |
| `receiver .MeetupWidgetProvider` | `exported=false`; filter `android.appwidget.action.APPWIDGET_UPDATE`; meta-data `android.appwidget.provider=@xml/meetup_widget_info` | home-screen widget | keep FQCN, now a `GlanceAppWidgetReceiver` (§3.2) |
| `receiver es.antonborri.home_widget.HomeWidgetBackgroundReceiver` | `exported=false` (Audit N4), filter `es.antonborri.home_widget.action.BACKGROUND` | refresh button → background Dart | replace by Glance `actionRunCallback` (its receiver is internal and not exported) |
| `service es.antonborri.home_widget.HomeWidgetBackgroundService` | `permission=BIND_JOB_SERVICE`, `exported=false` | JobIntentService running Dart | replace by WorkManager worker |

Not present (and not needed for parity): deep-link / App-Link intent filters, share targets (`ACTION_SEND` filters),
custom URL schemes, content providers of the app itself, NFC tech filters, `android:sharedUserId`, app shortcuts.

`android/app/src/debug/AndroidManifest.xml` and `.../profile/AndroidManifest.xml` only add `INTERNET` (Flutter tooling).

### 1.2 Entries merged in from Flutter plugins (effective manifest of the release APK)

| Plugin (locked version) | Merged entries | Native equivalent |
|---|---|---|
| `mobile_scanner 7.1.4` | `CAMERA` permission; `uses-feature android.hardware.camera required=false` | declare both yourself; also `android.hardware.camera.autofocus required=false` (implied by `CAMERA`) |
| `local_auth_android 1.0.56` | `USE_BIOMETRIC` | merged automatically by `androidx.biometric` |
| `passkeys_android 2.13.0` | `USE_BIOMETRIC`, `USE_CREDENTIALS` (obsolete, API ≤ 22), `CREDENTIAL_MANAGER_SET_ORIGIN` (only for browsers) | none needed; do not declare the last two |
| `image_picker_android 0.8.13+17` | `provider io.flutter.plugins.imagepicker.ImagePickerFileProvider` (`${applicationId}.flutter.image_provider`, not exported); `service com.google.android.gms.metadata.ModuleDependencies` with meta-data `photopicker_activity:0:required` (Photo Picker back-port on old devices via Play services) | `ActivityResultContracts.PickVisualMedia`; keep the `ModuleDependencies` service entry (copy) so API 26–29 devices with Play services get the back-ported picker |
| `share_plus 13.3.1` | `provider dev.fluttercommunity.plus.share.ShareFileProvider` (`${applicationId}.flutter.share_provider`); `receiver SharePlusPendingIntent` (not exported, action `EXTRA_CHOSEN_COMPONENT`) | own `androidx.core.content.FileProvider` (`${applicationId}.fileprovider`) + a non-exported chooser-callback receiver (§2) |
| `android_file_picker 2.0.1` (via `file_picker 13.1.0`) | `queries` `GET_CONTENT */*` | SAF `OpenDocument` / `CreateDocument` need no queries |
| `geolocator_android 4.6.2` | `service GeolocatorLocationService` (not exported) | none (one-shot location only) |
| `url_launcher_android 6.3.28` | `activity io.flutter.plugins.urllauncher.WebViewActivity` (not exported) | none (external browser / Custom Tabs) |
| `flutter_secure_storage 10.3.4`, `shared_preferences_android`, `home_widget`, `nfc_manager`, `path_provider_android`, `package_info_plus`, `just_audio`, `add_2_calendar` | nothing (home_widget's receiver/service are declared by the app) | — |

Libraries of the native app add their own entries (review the merged manifest of every release with
`./gradlew :app:processReleaseMainManifest` → `build/intermediates/merged_manifests/...`): WorkManager
(`ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `SystemJobService`,
`SystemForegroundService`, …), `androidx.startup` `InitializationProvider`, profileinstaller receiver, Glance
(`ActionCallbackBroadcastReceiver`, `ActionTrampolineActivity`, `InvisibleActionTrampolineActivity`,
`UnmanagedSessionReceiver`, `MyPackageReplacedReceiver`; all not exported — verify on the merged manifest),
CameraX / ML Kit (`MlKitComponentDiscoveryService`, `MlKitInitProvider`), Credential Manager / Play services auth.

### 1.3 Resources referenced by the manifest

| Resource | Content | Native |
|---|---|---|
| `xml/network_security_config.xml` | `base-config cleartextTrafficPermitted=false` with system trust anchors; `domain-config cleartext=true` for `onion` (includeSubdomains), `local` (includeSubdomains) and `127.0.0.1` + `localhost` (no subdomains; added for the just_audio loopback proxy) | copy verbatim (it is mirrored by `:core:network` `CleartextPolicy`; keep both in sync). Optional: `<debug-overrides><trust-anchors><certificates src="user"/></trust-anchors></debug-overrides>` for MITM debugging only |
| `xml/meetup_widget_info.xml` | `minWidth 250dp`, `minHeight 110dp`, `targetCellWidth 4`, `targetCellHeight 2`, `updatePeriodMillis 1800000`, `initialLayout @layout/meetup_widget`, `resizeMode horizontal\|vertical`, `widgetCategory home_screen`, `previewImage @mipmap/ic_launcher`, `description @string/widget_description` | keep file name and sizes; `initialLayout="@layout/glance_default_loading_layout"`; add `previewLayout` (API 31+) and localized `description` (§3.2) |
| `layout/meetup_widget.xml` | RemoteViews layout (§3.1.2) | replaced by Glance composition |
| `drawable/widget_bg.xml` | rect `#141416`, radius 20dp, stroke 1dp `#33F7931A` | Glance `background(ImageProvider(R.drawable.widget_bg))` (keep the drawable) |
| `drawable/widget_meetup_bg.xml` | rect `#1AF7931A`, radius 12dp | keep |
| `drawable/widget_new_badge.xml` | rect `#F7931A`, radius 4dp | keep |
| `drawable-*/widget_logo.png` | mdpi…xxhdpi Einundzwanzig wordmark | keep |
| `drawable/ic_widget_calendar.xml` | 24dp vector, `#F7931A` calendar | unused by the layout; drop or keep |
| `values/strings.xml` | `widget_description` = "Bitcoin-Netzwerk & nächstes Meetup" | `native_widget_description` in de/en/es |
| `values/styles.xml`, `values-night/styles.xml` | `LaunchTheme` / `NormalTheme` (parent `Theme.AppCompat[.Light].NoActionBar`, night: black background) | replaced by `Theme.Einundzwanzig.Splash` (core-splashscreen) |
| `drawable[-v21]/launch_background.xml` | white / `?android:colorBackground` | replaced by splash API |
| `mipmap-anydpi-v26/ic_launcher.xml` | adaptive icon: background `@color/ic_launcher_background` = `#1A1A1A`, foreground `@drawable/ic_launcher_foreground` inset 16 % | already ported in `android-native/app/src/main/res`; keep identical so the launcher icon does not change on update |

### 1.4 Behaviour hidden in `MainActivity.kt` (platform channels)

| Channel / method | Behaviour | Native owner |
|---|---|---|
| `einundzwanzig/amber_signer` `isAppInstalled`, `getPublicKey`, `signEvent`, `nip44Encrypt`, `nip44Decrypt` | NIP-55: ContentResolver first (`content://com.greenart7c3.nostrsigner.SIGN_EVENT` / `.NIP44_ENCRYPT` / `.NIP44_DECRYPT`, projection `[value, pubkeyOrEmpty, npub]`, column `rejected` → error), then foreground intent `nostrsigner:<payload>` with `package=com.greenart7c3.nostrsigner`, extras `type`, `current_user`, `pubkey`, `permissions` (get_public_key: `[{"type":"sign_event"},{"type":"nip44_encrypt"},{"type":"nip44_decrypt"}]`). Request codes 9551/9552/9553; one pending request at a time (`busy`). | spec 01 §6.6 (vectors in fixture `nip55`) |
| `einundzwanzig/amber_signer` `payLightningInvoice` | `ACTION_VIEW lightning:<invoice>`; `resolveActivity == null` → `false`; else `Intent.createChooser(intent, "Lightning-Wallet wählen")` | spec 11 (V4V/zaps); localize chooser title |
| `einundzwanzig/widget` `getLaunchTarget` | read-and-remove `pending_target` from prefs file `e21_widget` (`commit()`) | §3 |
| `einundzwanzig/screen` `setSecure(on)` | `FLAG_SECURE` add/clear on the activity window | §6 |
| `applyPhoneOrientation` | see §1.1 | §1.5 |

### 1.5 Native manifest (targetSdk 36, minSdk 26)

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <!-- Only if the PlebRap player gets a media notification (spec 11): -->
    <!-- <uses-permission android:name="android.permission.POST_NOTIFICATIONS" /> -->
    <!-- <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" /> -->

    <uses-feature android:name="android.hardware.camera" android:required="false" />
    <uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />
    <uses-feature android:name="android.hardware.location" android:required="false" />
    <uses-feature android:name="android.hardware.location.gps" android:required="false" />

    <queries>
        <package android:name="com.greenart7c3.nostrsigner" />
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:scheme="nostrsigner" />
        </intent>
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:scheme="https" />
        </intent>
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:scheme="lightning" />
        </intent>
        <!-- only if androidx.browser Custom Tabs are used -->
        <intent>
            <action android:name="android.support.customtabs.action.CustomTabsService" />
        </intent>
    </queries>

    <application
        android:name=".EinundzwanzigApp"
        android:allowBackup="false"
        android:fullBackupContent="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:networkSecurityConfig="@xml/network_security_config"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.Einundzwanzig.Splash"
        tools:targetApi="36">

        <!-- FQCN must stay space.einundzwanzig.meetup.MainActivity (launcher shortcuts survive). -->
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- FQCN must stay: stale widget PendingIntents created by the Flutter build point here. -->
        <activity
            android:name=".WidgetRouterActivity"
            android:exported="false"
            android:excludeFromRecents="true"
            android:noHistory="true"
            android:taskAffinity=""
            android:theme="@android:style/Theme.Translucent.NoTitleBar" />

        <!-- FQCN must stay: placed widgets are bound to this component. -->
        <receiver
            android:name=".MeetupWidgetProvider"
            android:exported="false"
            android:label="@string/native_widget_label">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/meetup_widget_info" />
        </receiver>

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>

        <!-- Photo Picker back-port for API 26-29 via Google Play services (same as image_picker). -->
        <service
            android:name="com.google.android.gms.metadata.ModuleDependencies"
            android:enabled="false"
            android:exported="false"
            tools:ignore="MissingClass">
            <intent-filter>
                <action android:name="com.google.android.gms.metadata.MODULE_DEPENDENCIES" />
            </intent-filter>
            <meta-data android:name="photopicker_activity:0:required" android:value="" />
        </service>

        <!-- Hilt workers: replace the default WorkManager initializer (Application implements Configuration.Provider). -->
        <provider
            android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup"
            android:exported="false"
            tools:node="merge">
            <meta-data
                android:name="androidx.work.WorkManagerInitializer"
                android:value="androidx.startup"
                tools:node="remove" />
        </provider>
    </application>
</manifest>
```

`res/xml/data_extraction_rules.xml` (API 31+; on target ≥ 31 `allowBackup="false"` no longer stops device-to-device
transfer, and a transferred Keystore-encrypted store without its key is a broken install):

```xml
<data-extraction-rules>
    <cloud-backup disableIfNoEncryptionCapabilities="true">
        <exclude domain="root" path="." />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
        <exclude domain="external" path="." />
    </cloud-backup>
    <device-transfer>
        <exclude domain="root" path="." />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
        <exclude domain="external" path="." />
    </device-transfer>
</data-extraction-rules>
```

`res/xml/file_paths.xml`: `<paths><cache-path name="share" path="share/" /></paths>` (badge/reputation PNGs and
backup files are written to `cacheDir/share/` and shared via `content://${applicationId}.fileprovider/share/…`).

`MainActivity` (code, not manifest): extend `FragmentActivity` (F8); `installSplashScreen()` → `enableEdgeToEdge()`;
keep the splash on screen until the migration gate (§4.7) completes; port the orientation rule:

```kotlin
private fun applyPhoneOrientation(c: Configuration) {
    val o = if (c.smallestScreenWidthDp in 1..599) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    if (requestedOrientation != o) requestedOrientation = o
}
// call before super.onCreate() and in onConfigurationChanged()
```

On Android 16 (API 36) with targetSdk 36 the system ignores orientation requests on displays ≥ 600 dp anyway; the
rule still matters for API 26–35 tablets and foldables.

### 1.6 Differences to the Flutter manifest (recommended for target 36)

| Topic | Flutter | Native | Why |
|---|---|---|---|
| Label | `21Meetup` | `@string/app_name` (currently "Einundzwanzig") | **Product decision.** The launcher label changes on update otherwise; recommend keeping "21Meetup" for continuity. |
| NFC | permission + feature | none | feature flag off (Audit N4 spirit: no unused capability) |
| Backup | `allowBackup=false`, `fullBackupContent=false` | + `dataExtractionRules` excluding everything | D2D transfer on target ≥ 31 (§6) |
| `configChanges` | 11 flags | none | Compose + ViewModel survive recreation; per-app locale change needs recreation |
| Theme | AppCompat Launch/Normal | SplashScreen API | |
| Widget refresh | home_widget receiver + JobIntentService | Glance action callback + WorkManager | no exported components, no background Dart |
| Share | share_plus provider/receiver | own FileProvider + chooser callback | |
| Passkey permissions | `USE_CREDENTIALS`, `CREDENTIAL_MANAGER_SET_ORIGIN` | none | not needed by a relying-party app |
| Predictive back | n/a | default on for target 36 | Navigation 3 handles it |
| Edge-to-edge | forced immersive sticky (`SystemChrome.setEnabledSystemUIMode`) | `enableEdgeToEdge()`, insets per screen | enforced on API 35+ |

---

## 2. Plugin inventory (pubspec → native)

Versions are the locked versions from `pubspec.lock`; native versions from `android-native/gradle/libs.versions.toml`.

| pubspec dependency (locked) | Used for (lib/) | Native capability | Native replacement (catalog) | Notes |
|---|---|---|---|---|
| `flutter`, `flutter_localizations` (SDK), gen-l10n | UI, `app_*.arb` (de/en/es) | resources | `:core:l10n` generated by `tools/arb2xml.py`; `generateLocaleConfig = true`; per-app language | `app_locale` pref → `AppCompatDelegate.setApplicationLocales` (needs appcompat) or `LocaleManager` (API 33+) + own `Context` wrapper below 33 |
| `cupertino_icons 1.0.8` | none on Android | — | — | |
| `qr_flutter 4.1.0` (+ `qr 3.0.2`) | QR rendering; ECL **L** (reputation QR), **M** (rolling QR) | QR encode | `zxing-core 3.5.4` `Encoder.encode(text, ErrorCorrectionLevel.L/M)` → Compose `Canvas` | match version/ECL/quiet zone per specs 02/04 |
| `mobile_scanner 7.1.4` | camera QR scan, scan from gallery image | camera + barcode | CameraX `1.6.2` (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-compose`) + ML Kit `barcode-scanning 17.3.0` (bundled model); gallery: `InputImage.fromFilePath` | bundled ML Kit is not FOSS (F-Droid); `zxing-core` is the fallback decoder (§5.3) |
| `url_launcher 6.3.2` | external links, `nostr:` with njump fallback, `nostrconnect://`, `lightning:` | intents | `Intent.ACTION_VIEW` (`startActivity` in `try/catch ActivityNotFoundException` = Flutter's `false`); optional `androidx.browser 1.10.0` Custom Tabs | keep the http(s) whitelist of `safe_url_launcher.dart` for data-derived URLs (Audit M2) |
| `home_widget 0.6.0` | widget data + refresh callback | app widget | `androidx.glance:glance-appwidget 1.2.0` (+ `glance-material3 1.2.0`), `work-runtime-ktx 2.12.0`, `hilt-work 1.4.0` | §3 |
| `package_info_plus 10.2.2` | version in settings/diagnostics/backups | build info | `BuildConfig.VERSION_NAME` / `VERSION_CODE` (`buildConfig = true` already set) | backup files embed app version strings — check spec 08 |
| `google_fonts 6.3.3` | Rajdhani, Inconsolata (`lib/theme.dart`) — **downloaded at runtime** from fonts.gstatic.com and cached in `filesDir` | fonts | bundled `res/font/*.ttf` in `:core:ui` (OFL) | no runtime download (privacy, offline first start) |
| `image_picker 1.2.2` (android `0.8.13+17`) | profile picture (`maxWidth/maxHeight 400`, `imageQuality 80`), event logo | media pick | `ActivityResultContracts.PickVisualMedia` (activity-compose `1.13.0`) + decode/scale (`ImageDecoder` API 28+, `BitmapFactory` below) + JPEG q80 into `filesDir/profile/` | Flutter stored a **cache path** (§4.9) |
| `just_audio 0.9.46` (+ `audio_session`) | PlebRap player, `LockCachingAudioSource` (loopback proxy) | audio | `media3-exoplayer 1.11.1` (+ `media3-session 1.11.1` if a notification is added) with `CacheDataSource` + `SimpleCache` in `cacheDir` | spec 11 |
| `web_socket_channel 3.0.3` | Nostr relays | WebSocket | `okhttp 5.5.0` WebSocket | frame limit 512 KiB (Audit M4, spec 07) |
| `http 1.6.0` | REST (portal, mempool, news, nip05, Supabase, Blossom) | HTTP | `okhttp 5.5.0` | `:core:network` |
| `shared_preferences 2.5.4` (android `2.4.20`) | all non-secret state (§4.3) | key-value | `datastore-preferences 1.2.1` → `:core:data` `KeyValueStore` (file `app_prefs`) | only the **legacy** `SharedPreferences` API is used (no `SharedPreferencesAsync` / `WithCache`) |
| `icalendar_parser 2.1.0` | ICS feed | parse | own Kotlin parser (spec 10) | |
| `nfc_manager 4.1.1`, `nfc_manager_ndef 1.1.0` | NFC read/write (flag off) | NFC | `android.nfc` (`NfcAdapter.enableReaderMode`, `Ndef`, `NdefFormatable`) behind `BuildConfig.NFC_ENABLED` | compiled out |
| `crypto 3.0.7` | SHA-256, HMAC | hashing | `java.security.MessageDigest`, `javax.crypto.Mac` (`:core:crypto`) | |
| `archive 4.0.7` | gzip in the **web** codec only | gzip | `java.util.zip.GZIP*Stream` | bytes need not match (spec 08 §7.3) |
| `dbcrypt 2.0.0` | **not imported anywhere in lib/** | — | none required (`:core:crypto` has `Bcrypt.kt` anyway) | dead dependency |
| `nostr 1.5.0` (+ `bip340 0.2.0`) | events, BIP-340, NIP-19 | secp256k1 | `secp256k1-kmp-jni-android 0.24.0` + `:core:nostr` | JNI → R8 rules (§5.2.4) |
| `pointycastle 3.9.1` | scrypt (NIP-49), NIP-44 primitives | crypto | `bcprov-jdk18on 1.86` lightweight API | do not register BC as a JCA provider |
| `cryptography 2.9.0` | XChaCha20-Poly1305 (NIP-49) | AEAD | `:core:crypto` `XChaCha20Poly1305.kt` (HChaCha20 + BC ChaCha20-Poly1305) | |
| `convert 3.1.2`, `bech32 0.2.2` | hex, bech32 | encoding | `:core:crypto` `Hex.kt`, `Bech32.kt` | |
| `unorm_dart 0.3.2` | NFKC of NIP-49 passwords | Unicode | `java.text.Normalizer.normalize(s, NFKC)` | |
| `encrypt 5.0.3` | AES-GCM badge blobs, backup AES | AES | `javax.crypto` `AES/GCM/NoPadding`, `AES/CBC/PKCS5Padding` | badge blob format in fixture `badge_blob` |
| `flutter_secure_storage 10.3.4` | 19 secret keys (§4.4) | Keystore-backed secrets | `:core:data` `SecureStore` (DataStore `secure_prefs`, Keystore alias `einundzwanzig_secure_store_v1`, AES-256-GCM) + `:data:migration` legacy reader; ESP recovery needs `com.google.crypto.tink:tink-android:1.21.0` (**add to catalog**; the version FSS 10.3.4 builds against) | §4 |
| `local_auth 2.3.0` (android `1.0.56`) | device auth before showing nsec/ncryptsec (Audit M3) | biometrics/credential | `androidx.biometric 1.1.0` `BiometricPrompt` with `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`; `KeyguardManager.isDeviceSecure == false` → allow (Flutter: unsupported → allow) | needs `FragmentActivity` (F8) |
| `add_2_calendar 3.1.1` | "add to calendar" | calendar intent | `Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)` + `EXTRA_EVENT_BEGIN_TIME/END_TIME`, `Events.TITLE/DESCRIPTION/EVENT_LOCATION/ALL_DAY/EVENT_TIMEZONE` | no permission |
| `share_plus 13.3.1` | share text, PNGs, backup file; result `success`/`dismissed` | share sheet | `ACTION_SEND` + FileProvider + `Intent.createChooser(intent, title, pendingIntent.intentSender)`; the non-exported receiver records the chosen component | "chosen" ≠ "sent"; backup flow must keep "dismissed is not success" (commit 212e77b) |
| `file_picker 13.1.0` (+ `android_file_picker 2.0.1`) | save backup / key file, open backup, pick image | SAF | `ActivityResultContracts.CreateDocument("application/octet-stream")`, `OpenDocument(arrayOf("*/*"))`, image via PickVisualMedia | |
| `path_provider 2.1.5` | no direct call in lib/; used by just_audio (temp dir) and google_fonts (support dir) | dirs | `Context.filesDir`, `cacheDir`, `noBackupFilesDir` | §4.9 |
| `intl 0.20.2` | dates, numbers, plurals | formatting | `java.time` + `DateTimeFormatter.ofPattern(…, locale)`; plural resources | formatting vectors live in the domain specs |
| `geolocator 13.0.4` (android `4.6.2`, play-services-location `21.2.0`) | current position, permission, settings intents | location | `LocationManagerCompat.getCurrentLocation` (core-ktx `1.19.1`); optional Fused provider needs `com.google.android.gms:play-services-location` (**not in catalog**) | must work without GMS (spec 09/05) |
| `flutter_map 7.0.2`, `latlong2 0.9.1` | OSM maps (`tile.openstreetmap.org`, UA `space.einundzwanzig.meetup`) | map | `osmdroid 6.1.20` (`Configuration.getInstance().userAgentValue = "space.einundzwanzig.meetup"`) | haversine R = 6378137 m (spec 09) |
| `passkeys 2.17.4` (android `2.13.0`: credentials 1.6.0, play-services-auth 20.5.0, fido 20.0.1) | EasyAuth passkey + PRF | WebAuthn | `androidx.credentials 1.6.0` + `credentials-play-services-auth 1.6.0`; JSON request with `extensions.prf` | needs assetlinks (F9) |
| `flutter_svg 2.3.0` | `SvgPicture.asset` only: `einundzwanzig_logo.svg`, `bitcoin.svg` | SVG | VectorDrawable converted at build time (`coil-svg 3.6.3` only if remote SVGs appear, e.g. portal crests — spec 09) | |
| `provider 6.1.5+1` | GuideService | DI/state | Hilt `2.60.1` + ViewModel/StateFlow | |
| dev: `flutter_launcher_icons 0.14.4`, `flutter_lints 6.0.0` | icon generation, lints | — | icons already in `android-native/app/src/main/res` | |

Transitive packages with platform impact: `device_info_plus 13.3.0` (imported by `diagnostics_service.dart` →
`Build.MANUFACTURER/MODEL/VERSION.SDK_INT/RELEASE`), `uuid` → `java.util.UUID`, `rxdart` → Flow, `image` →
`android.graphics`, `xml` → XmlPullParser.

**Assets** (`pubspec.yaml` `flutter.assets`): `images/logo.png` (4022 × 4022 px, 237 KB — downscale to ≤ 1024 px
WebP), `images/intro_logo_86.png`, `images/intro_background_87.jpg`, `images/app_icon_83.jpg`,
`images/einundzwanzig_logo.svg`, `images/nostr_icon.png`, `icons/bitcoin.svg`, `images/satoshiduell.png` →
`res/drawable-nodpi/` (raster) and `res/drawable/` (vectors). Files in `assets/` that are **not** listed in pubspec
(`assets/app_icon.png`, `icons/app_icon.png`, `images/app_icon_83.png`, `images/intro_logo_86.jpg`) are not in the
APK and need no port.

---

## 3. Home-screen widget

### 3.1 Current behaviour (Flutter build)

#### 3.1.1 Components

- `MeetupWidgetProvider : AppWidgetProvider` (not exported) renders `R.layout.meetup_widget` with `RemoteViews` for
  every widget id in `onUpdate`; every step is wrapped so the widget never fails (`Log.e("MeetupWidget", …)`).
- Data source: `HomeWidgetPlugin.getData(context)` = `context.getSharedPreferences("HomeWidgetPreferences",
  MODE_PRIVATE)` — **no `flutter.` prefix**. home_widget 0.6.0 writes `String` with `putString`, `bool` with
  `putBoolean`, `int` with `putInt`, `double` with `putLong(doubleToRawLongBits)`; this app writes only Strings and
  one Boolean.
- `WidgetRouterActivity` (§3.1.5), `HomeWidgetBackgroundReceiver` + `HomeWidgetBackgroundService` (§3.1.6).
- `InternalHomeWidgetPreferences` (`callbackDispatcherHandle`, `callbackHandle` as `Long`) holds the Dart callback
  handles — meaningless for native.

#### 3.1.2 Layout (`meetup_widget.xml`, root padding 16dp, background `widget_bg`)

1. Header row (margin-bottom 20dp): `widget_logo` (ImageView, height 18dp, weight 1, `fitStart`, contentDescription
   "Einundzwanzig") · `widget_updated` (10sp `#8A8F98`, margin-end 6dp) · `widget_refresh` "⟳" (22sp bold
   `#F7931A`, padding 14/4/6/4).
2. `widget_btc_area`: row "BITCOIN" (13sp bold orange, weight 1) · "BLOCK" (9sp grey, margin-end 8) ·
   `widget_block` (30sp bold orange); divider 1dp `#22F7931A` (margins 10/12); row 1 (margin-bottom 14) three equal
   columns label (9sp grey, margin-bottom 3) over value (15sp bold white): "PREIS"/`widget_price`,
   "MOSCOW"/`widget_moscow`, "FEES L·M·H"/`widget_fees`; row 2 same with 13sp values: "HASHRATE"/`widget_hashrate`,
   "SUPPLY"/`widget_supply`, "DIFFICULTY"/`widget_difficulty`.
3. `widget_meetup_area` (background `widget_meetup_bg`, padding 12): column "NÄCHSTES MEETUP" (9sp bold orange,
   margin-bottom 2) over `widget_meetup_city` (16sp bold white, 1 line, ellipsize end) · `widget_meetup_countdown`
   (15sp bold orange, gravity end).
4. `widget_news_row` (margin-top 10, padding-top 10, padding-bottom 4): "NEWS" (10sp bold orange, margin-end 8) ·
   `widget_news_new` "NEU" (9sp bold `#141416` on `widget_new_badge`, padding 6/1, margin-end 8, `gone` by default) ·
   `widget_news_title` (12sp `#CCCCCC`, weight 1, 1 line, ellipsize end) · "›" (16sp bold grey).

All label strings are hard-coded German. Fonts are the system font.

#### 3.1.3 Data keys (`HomeWidgetPreferences`) and rendering

| Key | Type | Writer (`lib/services/widget_service.dart`) | Rendered as | Default when absent |
|---|---|---|---|---|
| `block` | String | `updateBitcoin`: `fmtInt(blockHeight)` or `"––"` | `widget_block` | `"––"` |
| `priceFiat` | String | `fmtInt(round(price in selected currency))` or `"––"` | `"$price $priceSymbol"` | falls back to `priceEur` |
| `priceSymbol` | String | `CurrencyService.symbol(cur)` (EUR `€`, USD `$`, GBP `£`, JPY `¥`, CAD `CA$`, AUD `A$`, else code) | | `"€"` |
| `priceEur` | String | legacy, still written | fallback for `priceFiat` | `"––"` |
| `moscow` | String | `d.moscowTime` = `(100000000 / priceUsd).round()` padLeft(4,'0'), `head:tail` (last two digits after `:`), `"--:--"` if no price | `widget_moscow` | `"––:––"` |
| `fees` | String | `"$low·$medium·$high"` (U+00B7) | `widget_fees` | `"–·–·–"` |
| `hashrate` | String | `"${ehs.toStringAsFixed(0)} EH/s"` or `"––"` | `widget_hashrate` | `"––"` |
| `supply` | String | `fmtInt(supply)` or `"––"` | `widget_supply` | `"––"` |
| `difficulty` | String | `remaining > 0 ? "${pct >= 0 ? '+' : ''}${pct.toStringAsFixed(1)}% / $remaining" : "––"` | `widget_difficulty` | `"––"` |
| `updated` | String | local `"HH:mm"` at write time | `"Stand $updated"`, empty → `""` | `""` |
| `meetupCity` | String | `updateMeetup(city, countdown)` from Home (front favorite card) | city; **empty → "Kein Home-Meetup" + countdown "In der App wählen"** | `""` |
| `meetupCountdown` | String | `days <= 0 → "Heute"`, `1 → "Morgen"`, else `"in $days Tagen"` (calendar-day difference); `""` if the card has no date | countdown | `""` |
| `newsTitle` | String | `refreshNews`: latest title or `"News"` | `widget_news_title` | `"Aktuelle Artikel"` |
| `newsIsNew` | Boolean | `refreshNews`: `id.isNotEmpty && id != widget_last_seen_news_id`; `markNewsSeen` → `false` | `widget_news_new` visible/gone | `false` |

`fmtInt` inserts `.` every three digits from the right (`95000 → "95.000"`); `"––"` is two U+2013. Vectors:
fixture `home_widget_prefs` (XML + rendered result + empty-state result) and `widget_formatting`.

#### 3.1.4 Update triggers

- `updatePeriodMillis = 1800000` (30 min, Android minimum) → `onUpdate` **re-renders stored values only**; no fetch.
- `HomeWidget.updateWidget(name: 'MeetupWidgetProvider', androidName: 'MeetupWidgetProvider')` after every write
  (sends `APPWIDGET_UPDATE` to all ids).
- Writers: Home dashboard data load (`updateBitcoin`), currency change (`CurrencyService.set` → `updateBitcoin`),
  Home favorites computation on load/resume/midnight timer/favorite change (`updateMeetup`), Home init
  (`refreshNews`), opening News (`markNewsSeen`), refresh button (`refreshBitcoin` + `refreshNews`).
- `refreshBitcoin` does **not** overwrite when no source answered (`d.isDead`) — last good values stay.
- Quirk: with zero favorites Home returns before `updateMeetup`, so the widget keeps the old city (spec 10 §2.10:
  native writes an empty city).
- Formatting traps (F11): `toStringAsFixed` uses the exact binary value and rounds an exact tie away from zero; a
  negative value keeps its `-` even when it rounds to zero (`-0.04 → "-0.0"`, `-0.0 → "-0.0"`). Kotlin: the shared
  `Double.toStringAsFixed(fractionDigits)` from `:core:common` (`space.einundzwanzig.meetup.core.common.dart`, DartCompat.kt) — never `String.format`, never a local copy. (A local `if (x < 0) "-$s" else s` is wrong: it prints `"0.0"` for `-0.0`.)

#### 3.1.5 Click routing

| View | Target | Request code | Intent |
|---|---|---|---|
| `widget_logo` | `home` | 0 | `Intent(context, WidgetRouterActivity)`, action `space.einundzwanzig.meetup.WIDGET_HOME`, data `homewidget://home`, extra `open=home`, `FLAG_ACTIVITY_NEW_TASK` |
| `widget_news_row` | `news` | 1 | action `…WIDGET_NEWS`, data `homewidget://news` |
| `widget_btc_area` | `bitcoin` | 2 | action `…WIDGET_BITCOIN`, data `homewidget://bitcoin` |
| `widget_meetup_area` | `meetup` | 3 | action `…WIDGET_MEETUP`, data `homewidget://meetup` |

`PendingIntent.getActivity(context, requestCode, intent, FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE)` — action + data +
request code make every PendingIntent distinct.

`WidgetRouterActivity.onCreate`: `target = intent.data?.host ?: intent.getStringExtra("open")`; if non-empty and
`!= "home"` → `getSharedPreferences("e21_widget").edit().putString("pending_target", target).commit()`; then
`startActivity(Intent(this, MainActivity::class.java).addFlags(FLAG_ACTIVITY_NEW_TASK))`; `finish()`. Any exception
→ just open the app. Reason (source comment): MIUI replays the **old** launch intent when MainActivity is restored
from recents, so MainActivity never reads the target from its own intent.

App side (`home_screen.dart` `_pollWidgetTarget`): on Home init and on every `AppLifecycleState.resumed`, call
`getLaunchTarget` (read **and remove** `pending_target`), guard against double routing, wait 250 ms, then route:
`news` → `_openNews()` (marks news seen), `bitcoin` → `BitcoinDashboardScreen`, `meetup` → `CalendarScreen(initialSearch
= front favorite city, initialMeetupId = MeetupService.resolveFavorite(key)?.id)` or plain `CalendarScreen` without
favorites. Unknown targets are ignored.

#### 3.1.6 Refresh button

`HomeWidgetBackgroundIntent.getBroadcast(context, Uri.parse("homewidget://refresh"))` → `HomeWidgetBackgroundReceiver`
→ `HomeWidgetBackgroundService` starts a headless Flutter engine → `widgetBackgroundCallback(uri)` (`main.dart`): if
`uri.host == "refresh"` → `WidgetService.refreshBitcoin()` then `WidgetService.refreshNews()`.

### 3.2 Native replacement (Glance)

**Modules and classes**

| Class | Module / package | Notes |
|---|---|---|
| `MeetupWidgetProvider : GlanceAppWidgetReceiver` | `:app`, package `space.einundzwanzig.meetup` | **exact FQCN** (F2). `override val glanceAppWidget = MeetupWidget()` |
| `WidgetRouterActivity : Activity` | `:app`, same package | exact FQCN; same logic as Flutter (writes `e21_widget/pending_target` with `commit()`) |
| `MeetupWidget : GlanceAppWidget` | `:feature:widget` | composition, actions |
| `RefreshWidgetAction : ActionCallback` | `:feature:widget` | enqueues the worker |
| `WidgetRefreshWorker : CoroutineWorker` (`@HiltWorker`) | `:feature:widget` | fetch + write + `updateAll` |
| `WidgetStateStore` | `:data:widget` | reads/writes the **same** `HomeWidgetPreferences` file and keys |
| `WidgetLaunchTargetStore` | `:data:widget` | `consume(): String?` = read + remove `e21_widget/pending_target` (commit) |
| `BitcoinWidgetSink`, `MeetupWidgetSink`, `NewsWidgetSink` | implemented in `:data:widget`, called by the bitcoin (spec 13), home (spec 12/10) and news (spec 11) domains | after every write: `MeetupWidget().updateAll(context)` |

**State.** Keep `SharedPreferences("HomeWidgetPreferences")` with the existing keys as the widget state store. It is
tiny, non-secret, readable synchronously from the receiver, shared across all widget instances, and — most
importantly — already populated on the device, so the widget shows the last values right after the update, before
the app was ever opened (no migration step). Add one key: `meetupStartEpochMs` (`Long`) so the countdown is computed
at render time (`Heute`/`Morgen`/`in N Tagen`, localized) instead of going stale after midnight; fall back to the
stored `meetupCountdown` string while `meetupStartEpochMs` is absent (first render after the upgrade). Delete
`InternalHomeWidgetPreferences.xml` during the migration.

**Composition.** `provideGlance` reads a snapshot from `WidgetStateStore` and calls `provideContent { … }`.
`sizeMode = SizeMode.Responsive(setOf(DpSize(250.dp, 110.dp), DpSize(250.dp, 220.dp), DpSize(300.dp, 300.dp)))`:
the smallest size shows header + block/price row + meetup; the middle adds the second metrics row; the largest is
the full Flutter layout. Colors as in §3.1.2 via a fixed dark `ColorProviders` defined in `:core:ui`
(`WidgetColors`; the widget is always dark like the Flutter one). Text sizes and margins as in §3.1.2. System font
(Glance cannot use custom fonts). Strings: `native_widget_*` resources in de/en/es (German default identical to the
Flutter labels). `contentDescription` for the logo and the refresh button.

**Actions**

```kotlin
private fun open(context: Context, target: String) = actionStartActivity(
    Intent(context, WidgetRouterActivity::class.java)
        .setAction("space.einundzwanzig.meetup.WIDGET_${target.uppercase(Locale.ROOT)}")
        .setData(Uri.parse("homewidget://$target"))
        .putExtra("open", target)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
)
// logo → open("home"), bitcoin area → open("bitcoin"), meetup area → open("meetup"), news row → open("news")
// refresh "⟳" → actionRunCallback<RefreshWidgetAction>()
```

`RefreshWidgetAction.onAction` enqueues `OneTimeWorkRequest<WidgetRefreshWorker>` as unique work `"widget_refresh"`
(`ExistingWorkPolicy.KEEP`, constraint `NetworkType.CONNECTED`; not expedited — expedited work below API 31 needs a
foreground notification). The worker awaits the migration gate (§4.7), fetches the dashboard (spec 13) and the latest
article (`limit = 1`, spec 11), writes like `updateBitcoin` (skipped when `isDead`) and `refreshNews`, then
`MeetupWidget().updateAll(applicationContext)`. Optional (product decision, not parity): a periodic worker every
60 min with the same body.

**App side routing.** `MainActivity` (`launchMode singleTop`) observes `Lifecycle.Event.ON_RESUME` (and first
composition); `WidgetLaunchTargetStore.consume()` → navigate: `news` → mark news seen + `News`, `bitcoin` →
`BitcoinDashboard`, `meetup` → `Calendar(initialSearch = frontFavoriteCity, initialMeetupId = …)`. Never read the
target from `getIntent()` (MIUI replay). No 250 ms delay is needed once the NavDisplay is composed; navigate after
the start destination is on the back stack.

**Upgrade path.** The system keeps the Flutter-era `RemoteViews` (layout ids of the old APK) until the provider
updates. Glance's merged `MyPackageReplacedReceiver` re-renders Glance widgets after the update (verify in the merged
manifest); in addition the migration calls `MeetupWidget().updateAll(context)` when it finishes. Until then, taps on
the stale views still resolve because `WidgetRouterActivity` keeps its FQCN; the stale refresh button targets the
removed `es.antonborri.home_widget.HomeWidgetBackgroundReceiver` and does nothing (acceptable, gone after the first
render).

**Tests.** Robolectric: `WidgetStateStore` reads the fixture XML `home_widget_prefs.xml` → `rendered` /
`renderedWhenEmpty`; formatter vectors `widget_formatting`; `WidgetRouterActivity` writes `pending_target` and
ignores `home`; `consume()` returns once. Glance: `androidx.glance:glance-appwidget-testing` (`runGlanceAppWidgetUnitTest`)
for the three sizes.

---

## 4. Data migration (Flutter → native, same applicationId)

### 4.1 What can be on a device

The native APK installs as an update over **any** Flutter build ever shipped (GitHub tag releases, the rolling
`latest` pre-release rebuilt on every push to `main`, Zapstore, self-built APKs). `versionCode` does not identify the
storage format (the rolling builds of 2026-10-11 still carried 1.6.9+28), so the reader detects the format from the
files.

| Era | App builds | Secrets | Badges | Detect by |
|---|---|---|---|---|
| **E0** plaintext | before 2026-02-19 (`09a3d10`) | `FlutterSharedPreferences.xml`: `flutter.nostr_nsec_key`, `flutter.nostr_npub_key`, `flutter.nostr_priv_hex` | `flutter.badges` plaintext JSON list | those prefs keys present and `flutter.secure_migration_done` ≠ true |
| **E1** FSS 9.2.4 + ESP | 1.0.0+1 … 1.6.9+28 (2026-02-19 … 2026-10-10) | `FlutterSecureStorage.xml` as Jetpack EncryptedSharedPreferences: entry names AES-256-SIV, values AES-256-GCM, Tink keysets stored as entries `__androidx_security_crypto_encrypted_prefs_{key,value}_keyset__` in the same file, master key alias `_androidx_security_master_key_`. If ESP init failed on the device: custom AES-CBC/RSA-PKCS1 entries instead (same file) | `flutter.badges_encrypted` (+ E0 list until first save) | keyset entries present |
| **E2** FSS 10.3.4 | rolling builds after `212e77b` (2026-10-11), 1.7.0+29 … 1.7.2+31 | `FlutterSecureStorage.xml` entries `<prefix>_<key>` = Base64(iv12‖ct‖tag), AES-128-GCM key wrapped RSA-OAEP. **E1 ESP entries may still be in the same file, orphaned (F1).** Interrupted 10.x migration leaves `<prefix>_<key>_BACKUP` entries (old cipher) | plus secure second copies `badges_encrypted`, `badges_former` | entries with `<prefix>_` names |

Every file lives in `/data/data/space.einundzwanzig.meetup/` (app-private, credential-encrypted storage):

| Path | Written by | Content | Migration |
|---|---|---|---|
| `shared_prefs/FlutterSharedPreferences.xml` | shared_preferences_android | all `flutter.*` keys (§4.3) | read, copy, keep until cleanup |
| `shared_prefs/FlutterSecureStorage.xml` | flutter_secure_storage | secret entries (E1 ESP and/or E2 GCM, `_BACKUP`, ESP keysets; in v9 also the algorithm markers) | read-only, snapshot, keep until cleanup |
| `shared_prefs/FlutterSecureKeyStorage.xml` | flutter_secure_storage | RSA-wrapped AES keys (§4.5) | read-only, snapshot |
| `shared_prefs/FlutterSecureStorageConfiguration:FlutterSecureStorage.xml` | FSS 10.x | markers `FlutterSecureSAlgorithmKey`/`…Storage`, `ENCRYPTED_PREFERENCES_MIGRATED`, `FlutterSecureStorageBackupStatus`, `<name>_MIGRATED` | read-only (hints) |
| `shared_prefs/FlutterSecureStorageConfiguration.xml` | FSS 10.x legacy fallback reads | same keys (rare) | read-only (hints) |
| `shared_prefs/HomeWidgetPreferences.xml` | home_widget | widget values (§3.1.3) | **kept as the native widget store** |
| `shared_prefs/InternalHomeWidgetPreferences.xml` | home_widget | Dart callback handles | delete after import |
| `shared_prefs/e21_widget.xml` | `WidgetRouterActivity` | `pending_target` | kept (same mechanism) |
| Android Keystore `space.einundzwanzig.meetup.FlutterSecureStoragePluginKey` | FSS 9.x/10.x | RSA key pair, PKCS#1 padding (E1; also created in ESP mode) | used read-only; delete at cleanup |
| Android Keystore `space.einundzwanzig.meetup.FlutterSecureStoragePluginKeyOAEP` | FSS 10.x | RSA key pair, OAEP | used read-only; delete at cleanup |
| Android Keystore `_androidx_security_master_key_` | ESP (E1) | AES-256-GCM master key wrapping the Tink keysets | used read-only; delete at cleanup |
| `files/` (`getApplicationSupportDirectory`) | google_fonts | downloaded font files | delete at cleanup |
| `app_flutter/` (`getApplicationDocumentsDirectory`) | Flutter / path_provider | not used by this app | delete at cleanup if present |
| `cache/` | image_picker (picked/scaled images, incl. the **profile picture path**), share_plus (`share_plus/`), file_picker (`file_picker/`), just_audio (`just_audio_cache/`) | temporary | profile picture: §4.9; rest: OS-managed, delete at cleanup |

No SQLite databases, no `SharedPreferencesAsync`/DataStore file (`files/datastore/FlutterSharedPreferences.preferences_pb`
would only exist if the async API had been used — it was not), no external storage.

Target stores in the native app (already implemented in `:core:data`):

- `KeyValueStore` — Preferences DataStore file `app_prefs`; **key names = Flutter key names without `flutter.`**;
  types `PrefValue.{StringValue, BoolValue, IntValue(Long), DoubleValue, StringListValue}`; atomic `edit {}`.
- `SecureStore` — DataStore file `secure_prefs`, values AES-256-GCM with the non-exportable Keystore key
  `einundzwanzig_secure_store_v1`; **key names = flutter_secure_storage key names**; `readChecked` distinguishes
  `Absent` from `Unreadable`.

So the migration is a generic copy plus the routing exceptions of §4.3.4 and §4.8.

### 4.2 shared_preferences_android 2.4.20 on-disk format (byte-exact)

Source: `~/.pub-cache/hosted/pub.dev/shared_preferences_android-2.4.20/android/src/main/java/.../LegacySharedPreferencesPlugin.java`
and `lib/src/shared_preferences_android.dart` (`SharedPreferencesAndroid`), `lib/src/strings.dart`.

- File: `shared_prefs/FlutterSharedPreferences.xml` (`context.getSharedPreferences("FlutterSharedPreferences",
  MODE_PRIVATE)`), standard Android XML (`<map>`, UTF-8, `&quot;`/`&amp;`/`&lt;`/`&gt;` escaping, non-ASCII raw).
- Every key written through the Dart legacy API is `"flutter." + key` (`SharedPreferences._prefix`, `setPrefix` is
  never called). `getAll`/`clear` filter by that prefix; entries without it are invisible to Dart and must be ignored.
- Encodings (all writes use `commit()`):

| Dart call | XML element | Value |
|---|---|---|
| `setString(k, s)` | `<string name="flutter.k">s</string>` | verbatim; the plugin refuses strings that start with one of the prefixes below |
| `setBool` | `<boolean name="flutter.k" value="true\|false" />` | |
| `setInt` | `<long name="flutter.k" value="…" />` | **always `putLong`** (Dart int is 64-bit); read with `getLong` |
| `setDouble(d)` | `<string>` | `"VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu" + java.lang.Double.toString(d)` (e.g. `…Lu48.137154`, `…Lu1.0E-5`, `…Lu1.0E7`) |
| `setStringList(l)` | `<string>` | `"VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu!" + jsonEncode(l)` — Dart `jsonEncode` (no whitespace, non-ASCII **not** escaped) |
| *(old plugin < 2.3, still decoded)* | `<string>` | `"VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu" + android.util.Base64.encodeToString(ObjectOutputStream(ArrayList<String>), DEFAULT)` (no `!`; 76-char lines + trailing `\n`) |
| *(BigInteger, removed)* | `<string>` | `"VGhpcyBpcyB0aGUgcHJlZml4IGZvciBCaWdJbnRlZ2Vy" + BigInteger.toString(36)` |
| *(old `setStringSet`)* | `<set>` | converted to the Base64 list format on first read |

The prefixes are Base64 of "This is the prefix for a list.", "…for Double.", "…for BigInteger"; `!` cannot occur in
Base64, so the JSON list prefix is unambiguous. Both the legacy and the JSON list format must be decoded (the JSON form
is what 2.4.20 writes; every pubspec.lock in the repository history pins 2.4.20, but older plugin writes cannot be ruled
out for keys never rewritten). Vectors: fixture `flutter_shared_prefs` (XML → expected typed map, Java-serialized list,
double encodings).

### 4.3 SharedPreferences key inventory (complete)

Produced by a script over every `prefs.get*/set*/remove/containsKey/get` call in `lib/` (receivers `prefs`, `p`,
`humanityPrefs`, null-aware `prefs?.`), with all `static const` key names resolved, cross-checked against
`prefs.getKeys()` usage and the git history. **112 keys/patterns.** Sensitivity: `SECRET` (key material — never in
`KeyValueStore`, never logged), `PRIVATE` (personal/identity/location data — plaintext at rest is acceptable, never log
values), `INTEGRITY` (security-relevant caches the app re-verifies), `SETTING`, `STATE`, `CACHE` (re-fetchable).
"copy" = write to `KeyValueStore` under the same name with the same type.

| # | Dart key (XML name = `flutter.` + key) | Dart type → XML | Owner (lib/…) | Meaning | Sensitivity | Native action | Spec |
|---|---|---|---|---|---|---|---|
| 1 | `signing_mode` | String → `<string>` | services/signing_service.dart | active signer: local\|amber\|nip07\|nip46 (absent/unknown = local) | STATE | copy | 01 |
| 2 | `amber_pubkey_hex` | String → `<string>` | services/signing_service.dart | Amber (NIP-55) user pubkey, 64 lowercase hex | PRIVATE | copy | 01 |
| 3 | `amber_npub` | String → `<string>` | services/signing_service.dart | Amber user npub | PRIVATE | copy | 01 |
| 4 | `nip07_pubkey_hex` | String → `<string>` | services/signing_service.dart | NIP-07 pubkey (only from web backups) | PRIVATE | copy | 01 |
| 5 | `nip07_npub` | String → `<string>` | services/signing_service.dart | NIP-07 npub (only from web backups) | PRIVATE | copy | 01 |
| 6 | `nip46_pubkey_hex` | String → `<string>` | services/signing_service.dart | NIP-46 bunker user pubkey hex | PRIVATE | copy | 01 |
| 7 | `nip46_npub` | String → `<string>` | services/signing_service.dart | NIP-46 user npub | PRIVATE | copy | 01 |
| 8 | `nip46_bunker_uri` | String → `<string>` | services/signing_service.dart | bunker://<signer-hex>?relay=... (secret stripped) | PRIVATE | copy | 01 |
| 9 | `secure_migration_done` | bool → `<boolean value>` | services/secure_key_store.dart | plaintext->secure key migration ran (Dart ensureMigrated) | STATE | copy (set true after routing legacy plaintext keys) | 01 |
| 10 | `nostr_nsec_key` | String → `<string>` | services/secure_key_store.dart (legacy read+remove) | LEGACY plaintext nsec from builds before 2026-02-19 | SECRET | route -> SecureStore nostr_nsec_key if secure_migration_done != true; then remove from Flutter prefs; never copy to KeyValueStore | 01 |
| 11 | `nostr_npub_key` | String → `<string>` | services/secure_key_store.dart (legacy read+remove) | LEGACY plaintext npub (pre-secure-storage) | PRIVATE | route -> SecureStore nostr_npub_key (same rule as nsec) | 01 |
| 12 | `nostr_priv_hex` | String → `<string>` | services/secure_key_store.dart (legacy read+remove) | LEGACY plaintext private key hex | SECRET | route -> SecureStore nostr_priv_hex (same rule as nsec) | 01 |
| 13 | `app_review_demo` | bool → `<boolean value>` | services/app_review_demo.dart | store-review demo identity was signed in | STATE | copy | 01 |
| 14 | `nickname` | String → `<string>` | models/user.dart | display nickname (default "Anon") | PRIVATE | copy | 01/07 |
| 15 | `full_name` | String → `<string>` | models/user.dart | optional real name | PRIVATE | copy | 01/07 |
| 16 | `telegram` | String → `<string>` | models/user.dart | Telegram handle | PRIVATE | copy | 01/07 |
| 17 | `twitter` | String → `<string>` | models/user.dart | X/Twitter handle | PRIVATE | copy | 01/07 |
| 18 | `nostr` | String → `<string>` | models/user.dart | cached own npub (overridden by key store / external signer) | PRIVATE | copy | 01/07 |
| 19 | `nostr_verified` | bool → `<boolean value>` | models/user.dart | npub considered verified | STATE | copy | 01/07 |
| 20 | `admin_verified` | bool → `<boolean value>` | models/user.dart | admin status crypto-verified cache | INTEGRITY | copy (re-verified at runtime) | 05 |
| 21 | `is_admin` | bool → `<boolean value>` | models/user.dart | organizer/admin cache (re-verified) | INTEGRITY | copy (re-verified at runtime) | 05 |
| 22 | `admin_via_portal` | bool → `<boolean value>` | models/user.dart | admin source flag: portal organizer | INTEGRITY | copy | 05 |
| 23 | `admin_via_vouch` | bool → `<boolean value>` | models/user.dart | admin source flag: trust score/vouch | INTEGRITY | copy | 05 |
| 24 | `admin_via_seed` | bool → `<boolean value>` | models/user.dart | admin source flag: seed admin | INTEGRITY | copy | 05 |
| 25 | `promotion_source` | String → `<string>` | models/user.dart | legacy single admin source (seed_admin\|portal_organizer\|trust_score\|"") | INTEGRITY | copy | 05 |
| 26 | `review_demo` | bool → `<boolean value>` | models/user.dart | profile is the review demo profile | STATE | copy | 01 |
| 27 | `home_meetup` | String → `<string>` | models/user.dart | first favorite's portal meetup ID (widget routing anchor); legacy installs: city name | SETTING | copy | 09/10 |
| 28 | `favorite_meetups` | List<String> → `<string>` JSON_LIST_PREFIX + JSON | models/user.dart | favorite portal meetup IDs (legacy installs: city names, mapped by spec 09 §8.4; Dart comment "Stadtnamen" is stale); absent -> [home_meetup]. Copied verbatim, no conversion during migration | SETTING | copy | 09/10 |
| 29 | `badges_encrypted` | String → `<string>` | models/badge.dart | PRIMARY badge collection: base64(iv12):base64(ct\|\|tag) AES-256-GCM with secure badge_encryption_key | PRIVATE | copy verbatim (never re-encrypt during migration) | 02/03 |
| 30 | `badges_encrypted_preserved` | String → `<string>` | models/badge.dart | first backup generation of the encrypted collection | PRIVATE | copy verbatim | 02/03 |
| 31 | `badges_encrypted_preserved_older` | String → `<string>` | models/badge.dart | second (older) backup generation | PRIVATE | copy verbatim | 02/03 |
| 32 | `badges_encrypted_incoming` | String → `<string>` | models/badge.dart; services/badge_sync_service.dart | staged collection for an identity switch (restore) | PRIVATE | copy verbatim | 08 |
| 33 | `badges_pending_encrypted` | String → `<string>` | models/badge.dart | scans received while the main file was unreadable (own key badge_pending_key) | PRIVATE | copy verbatim | 02 |
| 34 | `badges_former` | String → `<string>` | models/badge.dart | archive: JSON list [{at,reason,npub,blob}] (max 10 / 500 KiB) | PRIVATE | copy verbatim | 03/08 |
| 35 | `badges` | List<String> → `<string>` JSON_LIST_PREFIX + JSON | models/badge.dart | LEGACY plaintext badge JSON list (pre-encryption builds) | PRIVATE | copy (badge domain encrypts on next save, as Dart) | 02 |
| 36 | `badges_pending` | List<String> → `<string>` JSON_LIST_PREFIX + JSON | models/badge.dart | LEGACY plaintext pending scans (PR 84 test builds) | PRIVATE | copy (badge domain migrates) | 02 |
| 37 | `rqr_session_start` | int → `<long value>` | services/rolling_qr_service.dart | organizer rolling-QR session start (unix s) | STATE | copy | 02 |
| 38 | `rqr_session_expires` | int → `<long value>` | services/rolling_qr_service.dart; screens/admin_panel.dart | session expiry (unix s) | STATE | copy | 02 |
| 39 | `rqr_session_meetup_id` | String → `<string>` | services/rolling_qr_service.dart | session meetup id | STATE | copy | 02 |
| 40 | `rqr_session_meetup_name` | String → `<string>` | services/rolling_qr_service.dart | session meetup name | STATE | copy | 02 |
| 41 | `rqr_session_meetup_country` | String → `<string>` | services/rolling_qr_service.dart | session meetup country | STATE | copy | 02 |
| 42 | `rqr_session_block_height` | int → `<long value>` | services/rolling_qr_service.dart | block height at session start | STATE | copy | 02 |
| 43 | `rqr_session_pubkey` | String → `<string>` | services/rolling_qr_service.dart | organizer pubkey hex of the session | PRIVATE | copy | 02 |
| 44 | `rqr_session_lat` | double → `<string>` DOUBLE_PREFIX + `Double.toString` | services/rolling_qr_service.dart | session latitude (DOUBLE_PREFIX string) | PRIVATE | copy | 02 |
| 45 | `rqr_session_lng` | double → `<string>` DOUBLE_PREFIX + `Double.toString` | services/rolling_qr_service.dart | session longitude (DOUBLE_PREFIX string) | PRIVATE | copy | 02 |
| 46 | `rqr_session_seed` | String → `<string>` | services/rolling_qr_service.dart (legacy read+remove) | LEGACY plaintext HMAC session seed | SECRET | route -> SecureStore rqr_session_seed_secure (overwrites, Dart _migrateSeedIfNeeded); remove; never copy | 02 |
| 47 | `rqr_session_base_payload` | String → `<string>` | services/rolling_qr_service.dart (legacy read+remove) | LEGACY signed session base payload | SECRET | route -> SecureStore rqr_session_base_payload_secure (overwrites); remove; never copy | 02 |
| 48 | `badge_sync_dirty` | bool → `<boolean value>` | services/badge_sync_service.dart | a sync push is pending/retry | STATE | copy | 08 |
| 49 | `badge_sync_chunk_hashes` | List<String> → `<string>` JSON_LIST_PREFIX + JSON | services/badge_sync_service.dart | content hash per pushed chunk (64 hex) | STATE | copy | 08 |
| 50 | `badge_sync_last_gen` | int → `<long value>` | services/badge_sync_service.dart | last pushed/seen sync generation | STATE | copy | 08 |
| 51 | `badge_restore_hold` | bool → `<boolean value>` | services/badge_sync_service.dart | sync suspended during a backup restore | STATE | copy | 08 |
| 52 | `badge_restore_from` | String → `<string>` | services/badge_sync_service.dart | npub before the restore | PRIVATE | copy | 08 |
| 53 | `badge_restore_expect` | List<String> (legacy: String) → `<string>` JSON_LIST_PREFIX + JSON (old builds: plain `<string>`) | services/badge_sync_service.dart | npubs the restore may switch to (read with prefs.get: list or string) | PRIVATE | copy preserving the stored type | 08 |
| 54 | `badge_restore_proofs` | bool → `<boolean value>` | services/badge_sync_service.dart | restore carried proofs | STATE | copy | 08 |
| 55 | `badge_restore_clear` | bool → `<boolean value>` | services/badge_sync_service.dart | empty-replace pending | STATE | copy | 08 |
| 56 | `platform_proofs` | String → `<string>` | services/platform_proof_service.dart; backup_service.dart; badge_sync_service.dart | JSON list of platform proofs {platform,username,proof_sig,created_at} | PRIVATE | copy | 06 |
| 57 | `humanity_verified` | bool → `<boolean value>` | services/humanity_proof_service.dart; backup_service.dart; badge_sync_service.dart | humanity (zap) proof valid | STATE | copy | 06 |
| 58 | `humanity_needs_reverification` | bool → `<boolean value>` | services/humanity_proof_service.dart; backup_service.dart; badge_sync_service.dart | proof must be re-checked | STATE | copy | 06 |
| 59 | `humanity_receipt_id` | String → `<string>` | services/humanity_proof_service.dart; backup_service.dart; badge_sync_service.dart | zap receipt event id | PRIVATE | copy | 06 |
| 60 | `humanity_first_zap_at` | int → `<long value>` | services/humanity_proof_service.dart; backup_service.dart; badge_sync_service.dart | first zap timestamp (unix s) | STATE | copy | 06 |
| 61 | `humanity_checked_at` | int → `<long value>` | services/humanity_proof_service.dart; backup_service.dart; badge_sync_service.dart | last check (unix s) | STATE | copy | 06 |
| 62 | `reputation_last_publish` | int → `<long value>` | services/reputation_publisher.dart | last reputation publish (epoch ms) | STATE | copy | 04 |
| 63 | `reputation_last_hash` | String → `<string>` | services/reputation_publisher.dart | 16-hex hash of last published reputation | STATE | copy | 04 |
| 64 | `social_my_follows` | String → `<string>` | services/social_graph_service.dart | JSON array of followed pubkeys (cache, not owner-keyed) | CACHE | copy | 04/05 |
| 65 | `social_cache_ts` | int → `<long value>` | services/social_graph_service.dart | follows cache time (epoch ms) | CACHE | copy | 04/05 |
| 66 | `coatt_publish_status` | String → `<string>` | services/coattendance_service.dart | JSON {badgeSigId: relaysAccepted} co-attendance consent/publish status | PRIVATE | copy | 05 |
| 67 | `coatt_graph_cache_v1` | String → `<string>` | services/coattendance_service.dart | co-attendance graph cache (owner-bound JSON) | CACHE | copy | 05 |
| 68 | `admin_registry_cache` | String → `<string>` | services/admin_registry.dart | JSON array of AdminEntry (network cache) | INTEGRITY | copy | 05 |
| 69 | `admin_registry_timestamp` | int → `<long value>` | services/admin_registry.dart | admin cache time (epoch ms) | CACHE | copy | 05 |
| 70 | `bootstrap_permanently_sunset` | bool → `<boolean value>` | services/admin_registry.dart | bootstrap phase permanently ended | INTEGRITY | copy | 05 |
| 71 | `admin_unique_authors_count` | int → `<long value>` | services/admin_registry.dart | unique admin-list authors seen | INTEGRITY | copy | 05 |
| 72 | `sunset_count_reset_v1_done` | bool → `<boolean value>` | services/admin_registry.dart | one-time K1 reset done | STATE | copy | 05 |
| 73 | `sunset_authors_set_v1` | List<String> → `<string>` JSON_LIST_PREFIX + JSON | services/admin_registry.dart | hex pubkeys of admin-list authors | INTEGRITY | copy | 05 |
| 74 | `my_personal_vouches` | String → `<string>` | services/admin_registry.dart | JSON array of own vouches (legacy, no UI) | PRIVATE | copy | 05 |
| 75 | `organic_admin_claims` | String → `<string>` | services/promotion_claim_service.dart | JSON array of organic admin claims | CACHE | copy | 05 |
| 76 | `nostr_display_names_v1` | String → `<string>` | services/nostr_profile_service.dart | JSON {hex:{n,t}} display-name cache | CACHE | copy | 07 |
| 77 | `local_profile_picture` | String → `<string>` | services/nostr_profile_service.dart | ABSOLUTE PATH of a picked image (image_picker output in cacheDir) | PRIVATE | rewrite: copy the file into filesDir/profile/ if it exists, store the new path; else drop | 07/12 |
| 78 | `nostr_profile_metadata_v2_<hex64>` | String → `<string>` | services/nostr_profile_lookup.dart | JSON {event: kind-0, fetchedAt: ms} per pubkey | CACHE | copy | 07 |
| 79 | `nostr_profile_relays_v2_<hex64>` | String → `<string>` | services/nostr_profile_lookup.dart | JSON kind-10002 event per pubkey | CACHE | copy | 07 |
| 80 | `nostr_profile_picture_<hex64>` | String → `<string>` | services/nostr_profile_lookup.dart (legacy read+remove) | LEGACY picture URL per pubkey | CACHE | copy (lookup still reads it as fallback) | 07 |
| 81 | `nostr_profile_picture_time_<hex64>` | int → `<long value>` | services/nostr_profile_lookup.dart (legacy read+remove) | LEGACY picture cache time (epoch ms) | CACHE | copy | 07 |
| 82 | `zap_stats_cache_v2_<hex64>` | String → `<string>` | services/zap_verification_service.dart | ZapStats JSON per pubkey | CACHE | copy | 04/06 |
| 83 | `zap_cache_ts_v2_<hex64>` | int → `<long value>` | services/zap_verification_service.dart | zap stats cache time (epoch ms) | CACHE | copy | 04/06 |
| 84 | `zap_provider_pubkey_<hex64>` | String → `<string>` | services/zap_receipt_validator.dart | JSON {pk, ts} LNURL provider pubkey cache (7 d) | CACHE | copy | 06 |
| 85 | `portal_leaders_cache` | String → `<string>` | services/portal_leader_service.dart | raw JSON of /api/meetup-leaders | CACHE | copy | 09 |
| 86 | `portal_leaders_cache_ts` | int → `<long value>` | services/portal_leader_service.dart | leaders cache time (epoch ms) | CACHE | copy | 09 |
| 87 | `rsvp_cache_<portalEventId>` | String → `<string>` | services/portal_api_service.dart | RSVP payload cache per portal event | CACHE | copy | 09/10 |
| 88 | `rsvp_cache_ts_<portalEventId>` | int → `<long value>` | services/portal_api_service.dart | RSVP cache time (epoch ms) | CACHE | copy | 09/10 |
| 89 | `chat_last_read` | List<String> → `<string>` JSON_LIST_PREFIX + JSON | services/chat_service.dart | read marks "<roomKey>\|<unix s>" (parse at LAST '\|') | STATE | copy | 11 |
| 90 | `news_last_read_ts` | int → `<long value>` | services/news_service.dart | newest seen article publishedAt (unix s) | STATE | copy | 11 |
| 91 | `news_liked_reactions` | List<String> → `<string>` JSON_LIST_PREFIX + JSON | services/news_reactions_service.dart | "<hexPubkey>\|<articleAddress>" (cap 500) | PRIVATE | copy | 11 |
| 92 | `widget_last_seen_news_id` | String → `<string>` | services/widget_service.dart | newest article id the user has seen (widget NEU badge) | STATE | copy | 10/11 |
| 93 | `widget_latest_news_id` | String → `<string>` | services/widget_service.dart | newest article id fetched for the widget | STATE | copy | 10/11 |
| 94 | `widget_latest_news_title` | String → `<string>` | services/widget_service.dart | its title | CACHE | copy | 10/11 |
| 95 | `app_locale` | String → `<string>` | services/locale_controller.dart | de\|en\|es; absent = system | SETTING | copy + apply as per-app locale | 12 |
| 96 | `haptic_enabled` | bool → `<boolean value>` | services/haptic_service.dart; screens/app_shell.dart; screens/home_screen.dart | haptics on/off (default true) | SETTING | copy | 12 |
| 97 | `fiat_currency` | String → `<string>` | services/currency_service.dart | display currency code (default EUR) | SETTING | copy | 12/13 |
| 98 | `diagnostic_log` | String → `<string>` | services/app_logger.dart | JSON array of up to 800 LogEntry | PRIVATE | not copied to `KeyValueStore`: passed to `AppLogger.importLegacy(raw, verbose)` → `FileLogStore` `filesDir/diagnostic_log.jsonl` (redacted, merged in front of the native entries, one INFO entry with the count; corrupt buffer → WARN, not imported) (§4.3.4) | 12 |
| 99 | `diagnostic_verbose` | bool → `<boolean value>` | services/app_logger.dart | verbose logging | SETTING | not copied to `KeyValueStore`: passed as `verbose` to `AppLogger.importLegacy` → `filesDir/diagnostic_verbose` (§4.3.4) | 12 |
| 100 | `guide_completed_<tour>` | bool → `<boolean value>` | services/guide_service.dart | tour finished; <tour> in onboarding\|home\|settings\|events\|portal\|wallet\|reputation\|scan\|myMeetups\|portalArea | SETTING | copy | 12 |
| 101 | `guide_onboarding_asked` | bool → `<boolean value>` | services/guide_service.dart | onboarding tour offer shown | SETTING | copy | 12 |
| 102 | `tile_order` | List<String> → `<string>` JSON_LIST_PREFIX + JSON | screens/home_screen.dart | dashboard tile order | SETTING | copy | 12 |
| 103 | `tile_hidden` | List<String> → `<string>` JSON_LIST_PREFIX + JSON | screens/home_screen.dart | dashboard hidden tiles | SETTING | copy | 12 |
| 104 | `mig_hide_events_v1` | bool → `<boolean value>` | screens/home_screen.dart | one-time tile migration flag | STATE | copy | 12 |
| 105 | `mig_trust_header_v1` | bool → `<boolean value>` | screens/home_screen.dart | one-time tile migration flag | STATE | copy | 12 |
| 106 | `mig_hide_new_tiles_v131` | bool → `<boolean value>` | screens/home_screen.dart | one-time tile migration flag | STATE | copy | 12 |
| 107 | `mig_hide_plebrap_v1` | bool → `<boolean value>` | screens/home_screen.dart | one-time tile migration flag | STATE | copy | 12 |
| 108 | `nostr_last_seen` | int → `<long value>` | screens/home_screen.dart | last tap on the Nostr tile (unix s) | STATE | copy | 12 |
| 109 | `mempool_host` | String → `<string>` | services/mempool_config.dart | normalized mempool base URL (default https://mempool.space) | SETTING | copy | 13 |
| 110 | `mempool_last_height` | int → `<long value>` | services/mempool.dart | highest block height seen (never decreases) | CACHE | copy | 13 |
| 111 | `custom_relays` | List<String> → `<string>` JSON_LIST_PREFIX + JSON | services/relay_config.dart | user-added relay URLs (wss://) | SETTING | copy | 07 |
| 112 | `disabled_default_relays` | List<String> → `<string>` JSON_LIST_PREFIX + JSON | services/relay_config.dart | default relays the user switched off | SETTING | copy | 07 |

#### 4.3.1 Dynamic key patterns

| Pattern | Variable part | Writer / reader |
|---|---|---|
| `guide_completed_<tour>` | Dart enum name: `onboarding, home, settings, events, portal, wallet, reputation, scan, myMeetups, portalArea` (camelCase) | `GuideService` |
| `nostr_profile_metadata_v2_<pubkey>` / `nostr_profile_relays_v2_<pubkey>` | 64-hex pubkey | `NostrProfileLookup` (removed by `NostrProfileService.clearCache` via `getKeys()` prefix scan) |
| `nostr_profile_picture_<pubkey>` / `nostr_profile_picture_time_<pubkey>` | 64-hex | legacy, read as fallback and removed |
| `rsvp_cache_<id>` / `rsvp_cache_ts_<id>` | portal event id (int as string) | `PortalApiService` |
| `zap_stats_cache_v2_<pubkey>` / `zap_cache_ts_v2_<pubkey>` | 64-hex (own and others) | `ZapVerificationService` |
| `zap_provider_pubkey_<recipientPubkey>` | 64-hex | `ZapReceiptValidator` |

`NostrProfileService.clearCache` removes every key starting with `nostr_profile_picture`, `nostr_profile_metadata_v2_`,
`nostr_profile_relays_v2_` → native `KeyValueStore.removeByPrefix`.

#### 4.3.2 Obsolete keys (no reader in the current code, may exist on devices)

| Key | Type | Origin | Action |
|---|---|---|---|
| `vouching_consensus_cache` | String | services/vouching_service.dart (removed 2026-08-21, WoT removal d410e8d) | ignore (not copied); deleted with the Flutter file at cleanup |
| `vouching_consensus_ts` | int | same | ignore (not copied); deleted with the Flutter file at cleanup |
| `distrust_reports_cache` | String | same | ignore (not copied); deleted with the Flutter file at cleanup |
| `zap_stats_cache_<hex64>` | String | zap_verification_service.dart v1 (replaced by _v2 in cd15c5c, 2026-10-11) | ignore (not copied); deleted with the Flutter file at cleanup |
| `zap_cache_ts_<hex64>` | int | same | ignore (not copied); deleted with the Flutter file at cleanup |
| `nostr_profile_picture` | String | nostr_profile_service.dart global picture cache (before 2026-03-11) | ignore (not copied); deleted with the Flutter file at cleanup |
| `nostr_profile_picture_time` | int | same | ignore (not copied); deleted with the Flutter file at cleanup |
| `badge_sync_outbox_v1` | String | badge_sync_service.dart, only in main builds 42d7ccb..6a1738a (2026-10-16) | ignore (not copied); deleted with the Flutter file at cleanup |
| `badge_sync_hash_owner` | String | same | ignore (not copied); deleted with the Flutter file at cleanup |
| `badge_sync_collection_hash` | String | same | ignore (not copied); deleted with the Flutter file at cleanup |

#### 4.3.3 Other SharedPreferences files (not `flutter.`-prefixed)

| File | Keys | Native |
|---|---|---|
| `HomeWidgetPreferences` | §3.1.3 (13 Strings + `newsIsNew` Boolean) | stays the widget state store (§3.2) |
| `InternalHomeWidgetPreferences` | `callbackDispatcherHandle`, `callbackHandle` (Long) | delete |
| `e21_widget` | `pending_target` (String `news`/`bitcoin`/`meetup`) | stays (router) |

Dart `prefs.clear()` (app reset) only removes `flutter.*` keys; it never touched these files.

#### 4.3.4 Routing exceptions (everything else is copied verbatim)

| Flutter prefs key | Rule |
|---|---|
| `nostr_nsec_key`, `nostr_npub_key`, `nostr_priv_hex` | Dart `SecureKeyStore.ensureMigrated` semantics: if `secure_migration_done != true`, every non-empty value is written to `SecureStore` under the same name (**overwrites**), then `secure_migration_done = true` is copied/set. Never written to `KeyValueStore`. Removed from the Flutter file only after the `SecureStore` read-back matched (§4.7 step 9). If `secure_migration_done == true` and the keys still exist (should not happen) → ignore them, keep them in the file, record a warning (names only). |
| `rqr_session_seed` | non-empty → `SecureStore` `rqr_session_seed_secure` (overwrites, Dart `_migrateSeedIfNeeded`), then remove after read-back |
| `rqr_session_base_payload` | non-empty → `SecureStore` `rqr_session_base_payload_secure` (overwrites, Dart `_loadBasePayloadSecure`), then remove after read-back |
| `local_profile_picture` | absolute path into `cacheDir` (image_picker). If the file exists: copy to `filesDir/profile/local_picture.<ext>` and store the new absolute path; otherwise omit the key (Home then shows the Nostr picture or the placeholder, like Flutter after a cache wipe) |
| `diagnostic_log`, `diagnostic_verbose` | not written to `KeyValueStore`; handed to `AppLogger.importLegacy(rawDartJson, verbose)` (`:core:common` `DiagnosticLogger`), which stores them in `filesDir/diagnostic_log.jsonl` / `filesDir/diagnostic_verbose` (spec 12 §4) |
| obsolete keys (§4.3.2) | not copied |
| `badge_restore_expect` | copy with the type found (`StringListValue` or `StringValue`) — the reader accepts both (spec 08) |
| keys whose value fails to decode (corrupt list JSON, unknown class in a serialized list) | not copied; record the key name; never abort the migration for one key |
| `BigInteger` values | never written by this app; skip and record |

### 4.4 flutter_secure_storage key inventory (complete)

All five Dart instances (`SecureKeyStore`, `LocalKeyVault`, `MeetupBadge`, `PortalApiService`, `RollingQRService`)
use identical `AndroidOptions(resetOnError: false, migrateOnAlgorithmChange: true, migrateWithBackup: true)` (no
`sharedPreferencesName`, `preferencesKeyPrefix` or `storageNamespace`) and therefore share **one** store. Before
2026-10-11 the options were `AndroidOptions(encryptedSharedPreferences: true)`. Git history shows no other key name
was ever written.

| # | Logical key (XML name = `VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIHNlY3VyZSBzdG9yYWdlCg_` + key) | Owner (lib/…) | Meaning | Sensitivity | Plausibility check (CBC trial decrypt only) | Spec |
|---|---|---|---|---|---|---|
| 1 | `nostr_nsec_key` | services/secure_key_store.dart | local Nostr private key, nsec1… (bech32, 63 chars) | SECRET | `^nsec1[02-9ac-hj-np-z]{58}$` | 01 |
| 2 | `nostr_npub_key` | services/secure_key_store.dart | matching npub1… | PRIVATE | `^npub1[02-9ac-hj-np-z]{58}$` | 01 |
| 3 | `nostr_priv_hex` | services/secure_key_store.dart | private key, 64 lowercase hex | SECRET | `^[0-9a-f]{64}$` | 01 |
| 4 | `nip46_client_sk` | services/secure_key_store.dart | NIP-46 app session key (not the user key), 64 hex | SECRET | `^[0-9a-f]{64}$` | 01 |
| 5 | `easyauth_password_ncryptsec` | services/local_key_vault.dart | NIP-49 password wrap of the nsec (logN 16) | SECRET | `^ncryptsec1[02-9ac-hj-np-z]+$` | 01 |
| 6 | `easyauth_password_wrap_npub` | services/local_key_vault.dart | npub the password wrap belongs to | PRIVATE | `^npub1[02-9ac-hj-np-z]{58}$` | 01 |
| 7 | `easyauth_passkey_ncryptsec` | services/local_key_vault.dart | NIP-49 passkey-PRF wrap (logN 8) | SECRET | `^ncryptsec1[02-9ac-hj-np-z]+$` | 01 |
| 8 | `easyauth_passkey_credential_id` | services/local_key_vault.dart | WebAuthn credential id (base64url) | PRIVATE | `^[A-Za-z0-9_-]+={0,2}$` | 01 |
| 9 | `portal_api_token` | services/portal_api_service.dart | portal bearer token (Sanctum style "<id>\|<token>") | SECRET | `non-empty, no control chars` | 09 |
| 10 | `portal_api_token_npub` | services/portal_api_service.dart | npub the token is bound to | PRIVATE | `^npub1[02-9ac-hj-np-z]{58}$` | 09 |
| 11 | `rqr_session_seed_secure` | services/rolling_qr_service.dart | rolling-QR HMAC seed (64 hex) | SECRET | `^[0-9a-f]{64}$` | 02 |
| 12 | `rqr_session_base_payload_secure` | services/rolling_qr_service.dart | signed session base payload | SECRET | `non-empty` | 02 |
| 13 | `badge_encryption_key` | models/badge.dart | AES-256 badge key, 64 hex (case-insensitive, trimmed) | SECRET | `^\s*[0-9a-fA-F]{64}\s*$ (anything else: keep, flag suspect)` | 02 |
| 14 | `badge_encryption_key_suspect` | models/badge.dart | non-hex value moved aside from badge_encryption_key | SECRET | `any` | 02 |
| 15 | `badge_pending_key` | models/badge.dart | AES-256 key for badges_pending_encrypted, 64 hex | SECRET | `^[0-9a-fA-F]{64}$` | 02 |
| 16 | `badge_pending_key_suspect` | models/badge.dart | non-hex value moved aside from badge_pending_key | SECRET | `any` | 02 |
| 17 | `badges_encrypted` | models/badge.dart | second copy of the primary badge blob (iv:ct) | PRIVATE | `^[A-Za-z0-9+/=]+:[A-Za-z0-9+/=]+$` | 02/03 |
| 18 | `badges_former` | models/badge.dart | second copy of the archive JSON | PRIVATE | `JSON array` | 03/08 |
| 19 | `badge_reset_pending` | models/badge.dart | reset marker '1' (secure copies must not come back) | STATE | `^1$` | 03 |

Not a stored key but read by Dart: `badge_encryption_key_BACKUP` (`MeetupBadge._candidateKeys` reads it and every
`readAll()` value as candidate badge keys). In the file this name is exactly the FSS migration backup of
`badge_encryption_key` (old cipher) — the native reader decrypts `_BACKUP` entries with the old cipher (§4.6.3) and
hands every 64-hex secret to the badge domain as a candidate key (§4.8).

### 4.5 flutter_secure_storage on-disk formats

Constants (fixture `fss_constants`), `<pkg>` = `space.einundzwanzig.meetup`:

| Item | Value |
|---|---|
| Data file | `FlutterSecureStorage` |
| Entry name | `VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIHNlY3VyZSBzdG9yYWdlCg` + `_` + logical key ("This is the prefix for a secure storage\n" Base64 without padding) |
| Entry value (custom ciphers) | `android.util.Base64.encodeToString(cipherBytes, DEFAULT)` → 76-char lines joined by `\n`, trailing `\n`; decode with `Base64.DEFAULT` |
| Wrapped-key file | `FlutterSecureKeyStorage` |
| Wrapped AES key, v10 GCM | `AESVGhpcyBpcyB0aGUga2V5IGZvciBhIHNlY3VyZSBzdG9yYWdlIEFFUyBLZXkK` |
| Wrapped AES key, v9 GCM (typo name, read as fallback by 10.x) | `VGhpcyBpcyB0aGUga2V5IGZvcihBIHNlY3XyZZBzdG9yYWdlIEFFUyBLZXkK` |
| Wrapped AES key, v9 CBC | `VGhpcyBpcyB0aGUga2V5IGZvciBhIHNlY3VyZSBzdG9yYWdlIEFFUyBLZXkK` |
| Wrapped key value | `Base64.DEFAULT(rsaCipher.wrap(aesKey))`, AES key = **16 bytes** (AES-128) |
| RSA PKCS#1 alias / cipher | `<pkg>.FlutterSecureStoragePluginKey`; `Cipher.getInstance("RSA/ECB/PKCS1Padding", "AndroidKeyStoreBCWorkaround")`, no params; key generated with `KeyGenParameterSpec` (PURPOSE_ENCRYPT\|DECRYPT, DIGEST_SHA256, ECB, ENCRYPTION_PADDING_RSA_PKCS1, subject `CN=<alias>`, serial 1, validity 25 years) |
| RSA OAEP alias / cipher | `<pkg>.FlutterSecureStoragePluginKeyOAEP`; `Cipher.getInstance("RSA/ECB/OAEPPadding", "AndroidKeyStoreBCWorkaround")` with `OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)` |
| Storage cipher GCM | `AES/GCM/NoPadding`, IV 12 random bytes, tag 128 bit; stored bytes = `iv ‖ ciphertext ‖ tag` |
| Storage cipher CBC | `AES/CBC/PKCS7Padding` (= JCA `AES/CBC/PKCS5Padding`), IV 16; stored bytes = `iv ‖ ciphertext` |
| Plaintext | `value.getBytes(UTF_8)` |
| Algorithm markers | `FlutterSecureSAlgorithmKey` ∈ {`RSA_ECB_PKCS1Padding`, `RSA_ECB_OAEPwithSHA_256andMGF1Padding`, `AES_GCM_NoPadding`(+legacy `…_BIOMETRIC`)}, `FlutterSecureSAlgorithmStorage` ∈ {`AES_CBC_PKCS7Padding`, `AES_GCM_NoPadding`}. 10.x reads them from `FlutterSecureStorageConfiguration:FlutterSecureStorage`, falling back to `FlutterSecureStorageConfiguration`; v9 wrote them into the data file (10.3.2+ adopts them). **No markers ⇒ 10.x assumes the v9 defaults RSA_PKCS1 + AES_CBC.** |
| Migration backup (10.x, `migrateWithBackup`) | data `<entry>_BACKUP` (old ciphertext), key storage `<wrappedName>_BACKUP`, config `FlutterSecureStorageBackupStatus` (`started`/`complete`/`deleted`), `<entry>_MIGRATED` (bool) per re-encrypted entry; all removed at step 8 |
| ESP (E1) | `EncryptedSharedPreferences.create(ctx, "FlutterSecureStorage", MasterKey(alias "_androidx_security_master_key_", AES-256 GCM, padding NONE), AES256_SIV, AES256_GCM)`; entry name = Tink-Base64(SIV(`<prefix>_<key>`, AD = file name bytes)); value = Base64(AEAD(`[int32 typeId=0][int32 len][UTF-8 bytes]`, AD = encrypted name bytes)); keysets hex-encoded in the reserved entries |

What each Flutter version did on first access:

1. **9.2.4, `encryptedSharedPreferences: true`** (E1): built the custom cipher anyway (`initStorageCipher` →
   `getSavedStorageCipher` → creates RSA-PKCS1 alias and a CBC key under the v9 CBC name), then opened ESP; moved any
   custom-cipher entries into ESP; if ESP failed it set `failedToUseEncryptedSharedPreferences` and used the custom CBC
   cipher in the same file.
2. **10.3.4** with this app's options (E2): adopts v9 markers if present; no markers → saved = PKCS1/CBC, current =
   OAEP/GCM → `requiresReEncryption`; `canSkipMarkerlessMigration()` samples the first data entry whose raw name
   contains the prefix: none → "fresh install" → use GCM (new key) without migrating; decrypts with GCM → use GCM;
   otherwise `handleKeyMismatch` → `migrateNonBiometricWithBackup` (8 steps: backup → decrypt `_BACKUP` with the old
   cipher → delete originals → new GCM key → re-encrypt with `_MIGRATED` tracking → ESP step (only reached here) →
   delete `_BACKUP`/markers, write current markers; old RSA alias is **not** deleted because the store has no
   namespace). Failure with `resetOnError: false` → every read/write throws `PlatformException` → Dart logs and treats
   values as unreadable (e.g. badge key "nicht lesbar").
3. Consequence F1: ESP data is only ever migrated when a CBC→GCM migration runs in the same start; a pure ESP device
   never runs one. The ESP entries remain readable with the master key.

### 4.6 Native legacy readers (Kotlin pseudo-code, `:data:migration`, package `…data.migration.legacy`)

Rules for all readers: **read-only** on every Flutter file and Keystore alias (never call
`getSharedPreferences(…).edit()` on them before the cleanup phase, never let a library create keys/keysets), values
held only in memory, never logged, exceptions logged by class name only.

#### 4.6.1 Flutter prefs

```kotlin
internal object FlutterPrefs {
    const val FILE = "FlutterSharedPreferences"
    const val KEY_PREFIX = "flutter."
    const val LIST = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu"
    const val JSON_LIST = "$LIST!"
    const val DOUBLE = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu"
    const val BIG_INTEGER = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBCaWdJbnRlZ2Vy"
}

/** Decoded Flutter entry; same five types as :core:data PrefValue. */
internal data class DecodedPrefs(val values: Map<String, PrefValue>, val undecodable: List<String>)

internal fun readFlutterPrefs(context: Context): DecodedPrefs {
    val raw: Map<String, *> = context.getSharedPreferences(FlutterPrefs.FILE, Context.MODE_PRIVATE).all
    val out = LinkedHashMap<String, PrefValue>()
    val bad = mutableListOf<String>()
    for ((name, value) in raw) {
        if (!name.startsWith(FlutterPrefs.KEY_PREFIX) || value == null) continue
        val key = name.removePrefix(FlutterPrefs.KEY_PREFIX)
        val decoded: PrefValue? = runCatching {
            when (value) {
                is String -> decodeString(value)
                is Boolean -> PrefValue.BoolValue(value)
                is Long -> PrefValue.IntValue(value)
                is Int -> PrefValue.IntValue(value.toLong())
                is Float -> PrefValue.DoubleValue(value.toDouble())
                is Set<*> -> PrefValue.StringListValue(value.filterIsInstance<String>())
                else -> null
            }
        }.getOrNull()
        if (decoded == null) bad += key else out[key] = decoded
    }
    return DecodedPrefs(out, bad)
}

private fun decodeString(s: String): PrefValue? = when {
    s.startsWith(FlutterPrefs.JSON_LIST) -> PrefValue.StringListValue(
        AppJson.parseToJsonElement(s.substring(FlutterPrefs.JSON_LIST.length))
            .jsonArray.map { it.jsonPrimitive.content },          // List<String> only
    )
    s.startsWith(FlutterPrefs.LIST) -> PrefValue.StringListValue(
        decodeJavaSerializedList(s.substring(FlutterPrefs.LIST.length)),
    )
    s.startsWith(FlutterPrefs.DOUBLE) -> PrefValue.DoubleValue(s.substring(FlutterPrefs.DOUBLE.length).toDouble())
    s.startsWith(FlutterPrefs.BIG_INTEGER) -> null                 // never used by this app; report as undecodable
    else -> PrefValue.StringValue(s)
}

/** Same allow-list as the plugin's StringListObjectInputStream (no gadget classes). */
private fun decodeJavaSerializedList(b64: String): List<String> {
    val allowed = setOf("java.util.ArrayList", "java.util.Arrays\$ArrayList", "java.lang.String", "[Ljava.lang.String;")
    val input = object : ObjectInputStream(ByteArrayInputStream(Base64.decode(b64, Base64.DEFAULT))) {
        override fun resolveClass(desc: ObjectStreamClass): Class<*> {
            if (desc.name !in allowed) throw InvalidClassException(desc.name)
            return super.resolveClass(desc)
        }
    }
    return input.use { (it.readObject() as List<*>).map { e -> e as String } }
}
```

#### 4.6.2 Wrapped AES keys

```kotlin
internal object Fss {
    const val DATA = "FlutterSecureStorage"
    const val KEYS = "FlutterSecureKeyStorage"
    const val CONFIG = "FlutterSecureStorageConfiguration:FlutterSecureStorage"
    const val LEGACY_CONFIG = "FlutterSecureStorageConfiguration"
    const val PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIHNlY3VyZSBzdG9yYWdlCg"
    const val ENTRY = "${PREFIX}_"
    const val BACKUP = "_BACKUP"
    val WRAPPED_KEY_NAMES = listOf(
        "AESVGhpcyBpcyB0aGUga2V5IGZvciBhIHNlY3VyZSBzdG9yYWdlIEFFUyBLZXkK",   // v10 GCM
        "VGhpcyBpcyB0aGUga2V5IGZvcihBIHNlY3XyZZBzdG9yYWdlIEFFUyBLZXkK",      // v9 GCM (typo)
        "VGhpcyBpcyB0aGUga2V5IGZvciBhIHNlY3VyZSBzdG9yYWdlIEFFUyBLZXkK",      // v9 CBC
    )
    const val ESP_KEY_KEYSET = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
    const val ESP_VALUE_KEYSET = "__androidx_security_crypto_encrypted_prefs_value_keyset__"
    const val ESP_MASTER_ALIAS = "_androidx_security_master_key_"
    fun pkcs1Alias(pkg: String) = "$pkg.FlutterSecureStoragePluginKey"
    fun oaepAlias(pkg: String) = "$pkg.FlutterSecureStoragePluginKeyOAEP"
}

enum class RsaMode { OAEP, PKCS1 }
data class LegacyAesKey(val key: SecretKey, val wrappedName: String, val rsa: RsaMode)

/** Seam for Robolectric tests (no AndroidKeyStore there): tests return the fixture AES key. */
interface LegacyKeyUnwrapper { fun unwrap(wrapped: ByteArray, mode: RsaMode): SecretKey? }

internal class AndroidKeystoreUnwrapper(private val pkg: String) : LegacyKeyUnwrapper {
    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    override fun unwrap(wrapped: ByteArray, mode: RsaMode): SecretKey? {
        val alias = if (mode == RsaMode.OAEP) Fss.oaepAlias(pkg) else Fss.pkcs1Alias(pkg)
        if (!ks.containsAlias(alias)) return null                       // NEVER generate
        val priv = ks.getKey(alias, null) as? PrivateKey ?: return null
        return runCatching {
            val c = when (mode) {
                RsaMode.OAEP -> Cipher.getInstance("RSA/ECB/OAEPPadding", "AndroidKeyStoreBCWorkaround").apply {
                    init(Cipher.UNWRAP_MODE, priv,
                        OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
                }
                RsaMode.PKCS1 -> Cipher.getInstance("RSA/ECB/PKCS1Padding", "AndroidKeyStoreBCWorkaround").apply {
                    init(Cipher.UNWRAP_MODE, priv)
                }
            }
            c.unwrap(wrapped, "AES", Cipher.SECRET_KEY) as SecretKey
        }.getOrNull()?.takeIf { it.encoded?.size == 16 }               // a wrong-mode unwrap may "succeed" with garbage
    }
}

internal fun candidateKeys(context: Context, unwrapper: LegacyKeyUnwrapper): List<LegacyAesKey> {
    val prefs = context.getSharedPreferences(Fss.KEYS, Context.MODE_PRIVATE).all
    val out = mutableListOf<LegacyAesKey>()
    for (base in Fss.WRAPPED_KEY_NAMES) for (name in listOf(base, base + Fss.BACKUP)) {
        val b64 = prefs[name] as? String ?: continue
        val wrapped = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: continue
        for (mode in RsaMode.entries) unwrapper.unwrap(wrapped, mode)?.let { out += LegacyAesKey(it, name, mode) }
    }
    return out.distinctBy { it.key.encoded.toList() }
}
```

Order the candidates by the markers when present (GCM-name keys first if `FlutterSecureSAlgorithmStorage ==
AES_GCM_NoPadding`), otherwise v10 GCM name → v9 typo name → v9 CBC name.

#### 4.6.3 Custom-cipher entries (E1 fallback, E2, `_BACKUP`)

```kotlin
data class LegacySecret(val value: String, val source: Source) {
    enum class Source { GCM, CBC, GCM_BACKUP, CBC_BACKUP, ESP, PLAINTEXT_PREFS }
    override fun toString() = "LegacySecret(<redacted>, $source)"   // never leak values into logs/crash reports
}

internal data class CustomEntries(
    val main: Map<String, LegacySecret>,     // <prefix>_<key>
    val backup: Map<String, LegacySecret>,   // <prefix>_<key>_BACKUP (old cipher of an interrupted 10.x migration)
    val failed: List<String>,                // entry names (without prefix) that no candidate key opened
)

internal fun readCustomCipherEntries(
    context: Context, keys: List<LegacyAesKey>, validator: (String, String) -> Boolean,
): CustomEntries {
    val data = context.getSharedPreferences(Fss.DATA, Context.MODE_PRIVATE).all
    val main = HashMap<String, LegacySecret>(); val backup = HashMap<String, LegacySecret>(); val failed = mutableListOf<String>()
    for ((name, v) in data) {
        if (v !is String || !name.startsWith(Fss.ENTRY)) continue        // skips ESP names, keysets, v9 markers
        val isBackup = name.endsWith(Fss.BACKUP)
        val logical = name.removePrefix(Fss.ENTRY).let { if (isBackup) it.removeSuffix(Fss.BACKUP) else it }
        val bytes = runCatching { Base64.decode(v, Base64.DEFAULT) }.getOrNull()
        val plain = bytes?.let { decryptWithAny(it, keys, logical, validator) }
        when {
            plain == null -> failed += name.removePrefix(Fss.ENTRY)
            isBackup -> backup[logical] = plain.copy(source = if (plain.source == LegacySecret.Source.GCM) LegacySecret.Source.GCM_BACKUP else LegacySecret.Source.CBC_BACKUP)
            else -> main[logical] = plain
        }
    }
    return CustomEntries(main, backup, failed)   // precedence and recovery naming: §4.8
}

private fun decryptWithAny(data: ByteArray, keys: List<LegacyAesKey>, logical: String, ok: (String, String) -> Boolean): LegacySecret? {
    // 1) GCM is authenticated: the first key that opens it is the right one.
    if (data.size >= 12 + 16) for (k in keys) runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, k.key, GCMParameterSpec(128, data, 0, 12))
        return LegacySecret(strictUtf8(c.doFinal(data, 12, data.size - 12)), LegacySecret.Source.GCM)
    }
    // 2) CBC is not authenticated: accept only strict UTF-8 that passes the per-key plausibility check (§4.4 table).
    if (data.size >= 32 && (data.size - 16) % 16 == 0) for (k in keys) runCatching {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, k.key, IvParameterSpec(data, 0, 16))
        val s = strictUtf8(c.doFinal(data, 16, data.size - 16))
        if (ok(logical, s)) return LegacySecret(s, LegacySecret.Source.CBC)
    }
    return null
}

private fun strictUtf8(b: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(b)).toString()
```

#### 4.6.4 ESP entries (E1, including F1 orphans)

Vendor `crypto/EncryptedSharedPreferences.java`, `crypto/MasterKey.java`, `crypto/MasterKeys.java` from
`flutter_secure_storage-10.3.4/android/src/main/java/com/it_nomads/fluttersecurestorage/crypto/` (Apache-2.0 AndroidX
code as vendored by FSS; keep headers) into `:data:migration` and depend on `com.google.crypto.tink:tink-android:1.21.0`
— the same pair that FSS 10.3.4 uses to read 9.2.4 data. Do **not** call `getAll()` (it throws
`SecurityException("Could not decrypt key")` on the first non-ESP entry of a mixed E1/E2 file); look up the known keys
one by one:

```kotlin
internal fun readEspEntries(context: Context, knownKeys: Collection<String>): Map<String, String>? {
    val raw = context.getSharedPreferences(Fss.DATA, Context.MODE_PRIVATE)
    if (!raw.contains(Fss.ESP_KEY_KEYSET) || !raw.contains(Fss.ESP_VALUE_KEYSET)) return null   // else Tink would CREATE keysets
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    if (!ks.containsAlias(Fss.ESP_MASTER_ALIAS)) return null                                   // else MasterKey would CREATE a key
    val esp = runCatching {
        val master = MasterKey.Builder(context).setKeyGenParameterSpec(
            KeyGenParameterSpec.Builder(Fss.ESP_MASTER_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setKeySize(256).build(),
        ).build()
        EncryptedSharedPreferences.create(context, Fss.DATA, master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }.getOrNull() ?: return null
    return buildMap {
        for (key in knownKeys) runCatching { esp.getString(Fss.ENTRY + key, null) }.getOrNull()?.let { put(key, it) }
    }
}
// knownKeys = the 19 keys of §4.4 (every key ever written; see git history)
```

Because Tink/AndroidKeysetManager behaviour on a broken master key is version-dependent, the byte snapshot of §4.7
step 3 is taken **before** this call.

#### 4.6.5 Per-key plausibility checks

Used to accept a CBC trial decryption and to flag (not reject) authenticated values: the regex column of §4.4. Identity
consistency (spec 01 Audit N3 rule, reused): if `nostr_nsec_key`, `nostr_priv_hex` and `nostr_npub_key` are all present,
`bech32decode(nsec) == priv_hex` and `npub(xonly(priv)) == npub`; a mismatch is recorded and surfaced to the identity
domain but values are still imported verbatim.

### 4.7 Order of operations

`:data:migration` exposes `FlutterMigration.ensureMigrated(): MigrationReport` (idempotent, process-wide `Mutex`) and
completes a `MigrationGate` (interface in `:core:data`) that **`KeyValueStore` and `SecureStore` await before their
first read or write** (the migration itself writes through an internal ungated handle). `Application.onCreate`
launches `ensureMigrated()` in `@ApplicationScope` on `@IoDispatcher`; `MainActivity` keeps the splash screen until the
gate completes; Glance `provideGlance` and every `Worker` await it too (a widget update or WorkManager job can start
the process before any activity).

State file (not in `app_prefs`, survives app reset — F6): `noBackupFilesDir/flutter_migration/state.json` written
with `AtomicFile`:
`{ "version": 1, "state": "NOT_STARTED|NOT_NEEDED|IMPORTED|IMPORTED_WITH_ERRORS|CLEANED", "attempts": n,
"startedAt": ms, "finishedAt": ms, "counts": {…}, "failedKeys": [names], "recovered": [names], "warnings": [codes] }`.

1. **Lock + state.** `NOT_NEEDED`, `IMPORTED`, `CLEANED` → complete the gate, return.
2. **Detect.** If none of `FlutterSharedPreferences.xml`, `FlutterSecureStorage.xml`, `FlutterSecureKeyStorage.xml`
   exists in `dataDir/shared_prefs/` (also check the `*.xml.bak` files Android leaves after an interrupted write)
   → `NOT_NEEDED` (fresh install), done. (Check with `File.exists`, not `getSharedPreferences`, which would create an
   in-memory instance.)
3. **Snapshot** (first attempt only): byte-copy `FlutterSecureStorage*.xml` and `FlutterSecureKeyStorage.xml` (and their `.bak`) to
   `noBackupFilesDir/flutter_migration/snapshot/` (ciphertext only; never snapshot `FlutterSharedPreferences.xml`, which
   may hold E0 plaintext keys).
4. **Read prefs** (§4.6.1) → `DecodedPrefs`.
5. **Read secrets**: `candidateKeys` (§4.6.2) → custom-cipher entries incl. `_BACKUP` (§4.6.3) → ESP entries for the
   19 known keys (§4.6.4) → E0 plaintext keys from prefs (§4.3.4).
6. **Resolve** per key with the rules of §4.8 → `primarySecrets: Map<String,String>`, `recovery: Map<String,String>`
   (namespaced names), `prefsOut: Map<String, PrefValue>` (copy + routing of §4.3.4).
7. **Validate** (§4.6.5) and **test-open badges**: try every 64-hex secret (primary + recovery) on
   `badges_encrypted`, `badges_encrypted_preserved(_older)`, `badges_pending_encrypted`, the secure copy `badges_encrypted`
   and each `badges_former[].blob` (format fixture `badge_blob`). Record which opened; this is diagnostic only — the
   badge domain repeats it at runtime (spec 02/03).
8. **Write**, secrets first: for each `(key, value)` in `primarySecrets`: if `SecureStore.readChecked(key)` is
   `Present` with a different value (only possible on a retry after the user already created data) → write to the
   recovery name instead; else `write` and verify `readChecked == Present(value)`. Then `recovery` entries. Then all
   `prefsOut` in **one** `KeyValueStore.edit {}` transaction (keys already present natively on a retry are kept), then
   the profile-picture copy.
9. **Commit state**: every secret that existed in Flutter was imported (or deliberately routed to recovery) →
   `IMPORTED`; otherwise `IMPORTED_WITH_ERRORS` with `failedKeys`. Then, only for verified imports: remove the E0
   plaintext keys and `rqr_session_seed`/`rqr_session_base_payload` from `FlutterSharedPreferences` (`edit().remove().commit()`);
   delete `InternalHomeWidgetPreferences.xml`; `MeetupWidget().updateAll(context)`.
10. **Release** the gate (also on failure — the app must start). Log one line with counts and key **names** of
    failures (never values, never ciphertext).

`IMPORTED_WITH_ERRORS`: on every following start, retry only the failed secret keys (steps 3–9 for those keys), at
most 10 automatic attempts, then only from Settings → Diagnose ("Alte Daten erneut übernehmen", `native_` string).
While a Flutter secret is still unreadable, `SecureStore.readChecked(key)` for that key must report `Unreadable`
(not `Absent`) so no domain generates a replacement key (Dart `resetOnError: false` semantics; spec 01/02).

Cleanup (`CLEANED`), not before the release after the native one **and** only after: state `IMPORTED`, ≥ 3 app
starts on the native app, every imported secret still `Present`, the local identity (if any) signs and its npub matches,
badges opened (or there were none). Then delete `FlutterSharedPreferences.xml`, `FlutterSecureStorage*.xml`,
`FlutterSecureKeyStorage.xml`, the three Keystore aliases, `files/` font files, `app_flutter/`, `cache/{share_plus,
file_picker,just_audio_cache}`, the snapshot, and set `CLEANED`. Recovery entries stay in `SecureStore` until the user
dismisses them.

App reset (spec 12 §2.5.12) in the native app additionally: if state ≠ `CLEANED`, delete all Flutter legacy files,
aliases and the snapshot, and set `CLEANED` — a reset must never be undone by a later import (F6). The state file
itself is never deleted by a reset.

### 4.8 Conflict and recovery rules

| Situation | Rule |
|---|---|
| Key present as a main custom-cipher entry (E2 GCM, or the E1 fallback CBC) | primary |
| main entry missing/undecryptable, `_BACKUP` decrypts | `_BACKUP` value is primary |
| main entry and `_BACKUP` both decrypt and differ | main primary; `_BACKUP` → `legacy_backup/<key>` |
| E0 plaintext and `secure_migration_done != true` | plaintext wins (Dart overwrote secure storage with it) |
| ESP value, no E2/backup value for the key | **identity keys** (`nostr_*`, `easyauth_*`, `nip46_client_sk`, `portal_api_token*`): primary only if the npub this ESP group belongs to (ESP `nostr_npub_key`, else derived from the ESP `nostr_nsec_key`, else ESP `easyauth_password_wrap_npub` / `portal_api_token_npub`) equals an npub the Flutter prefs still reference (`nostr` = cached own npub, `amber_npub`, `nip46_npub`) — the F1 loss case. Otherwise (e.g. the user reset the Flutter app or switched identity after the loss) → `legacy_esp/<key>` and a one-time notice in the identity area offering restore/export (spec 01 decides the UI). **Badge keys** (`badge_encryption_key`, `badge_pending_key`, `*_suspect`) and other keys: primary if absent, else recovery |
| ESP value and a different custom-cipher value | custom-cipher value primary; ESP → `legacy_esp/<key>` |
| Badge keys | every 64-hex value from any source (primary, `legacy_*`, `*_suspect`) is a candidate for opening blobs (Dart `_candidateKeys`); when a blob opens only with a recovery key, the badge domain's `_rememberKey` logic writes that key to `badge_encryption_key` (Dart behaviour) |
| Native value already present (retry case) | never overwritten; Flutter value → `legacy_recovered/<key>` |

Recovery names (`legacy_esp/…`, `legacy_backup/…`, `legacy_recovered/…`) are `SecureStore` keys; they never collide
with Flutter names (which contain no `/`).

### 4.9 Files outside shared_prefs

- **Profile picture**: `local_profile_picture` stores the absolute path returned by image_picker (scaled copy in
  `cacheDir`, e.g. `…/cache/scaled_<name>.jpg` or `…/cache/<uuid>/<name>`). The OS may have deleted it. Copy if present
  (§4.3.4); the native picker writes to `filesDir/profile/` from the start.
- **google_fonts**: font files in `filesDir` (support directory) — unused natively (bundled fonts); delete at cleanup.
- **just_audio** `cacheDir/just_audio_cache/` (LockCachingAudioSource, files named by SHA-256 of the URL + `.mime`/`.part`)
  — do not reuse (Media3 has its own `SimpleCache` layout); OS-managed, delete at cleanup.
- **share_plus** `cacheDir/share_plus/`, **file_picker** `cacheDir/file_picker/` — temporary copies; delete at cleanup.
- **home_widget**: §4.3.3. **Router**: `e21_widget`.
- No files written via `getApplicationDocumentsDirectory`; backups are saved through SAF to user-chosen locations
  (outside the app; untouched).

### 4.10 Logging rules for the migration

- Allowed: counts per category, key **names**, era detected, `Source` enums, exception class names, durations.
- Forbidden: values, ciphertext, wrapped keys, file contents, exception messages from crypto (`getMessage()` can embed
  data), npubs (use "npub match yes/no").
- `diagnostic_log` is not copied into `KeyValueStore`: `AppLogger.importLegacy` redacts the Flutter entries and merges
  them in front of the native buffer in `filesDir/diagnostic_log.jsonl` (§4.3.4). Then append one entry
  `Migration: Flutter-Daten übernommen (Ära E2, 14 Einträge, 0 Fehler)` style.

### 4.11 Tests

- **JVM/Robolectric** (`:data:migration`): write the fixture XML (`flutter_shared_prefs.xml`) into Robolectric's
  `shared_prefs/` before calling `readFlutterPrefs` → `expectedDecoded`; Java-serialized list vector; double encodings;
  `prefix-less` keys ignored; corrupt list JSON → `undecodable`. FSS: build `FlutterSecureStorage.xml` from fixture
  `fss_v10_gcm` / `fss_v9_cbc` with a fake `LegacyKeyUnwrapper` returning the fixture AES key → plaintexts; `_BACKUP`
  precedence; CBC plausibility rejection (wrong key must not pass for `nostr_nsec_key`). Orchestrator: state machine,
  idempotent rerun, gate completes on exceptions, reset → `CLEANED`, routing table of §4.3.4 (`nostr_nsec_key` never in
  `KeyValueStore`).
- **Instrumented** (`androidTest`, emulator API 26/29/34/36): reproduce FSS writes natively (generate the RSA aliases with
  the exact `KeyGenParameterSpec` of §4.5, wrap a random AES-128 key, write entries) and an ESP file through the vendored
  ESP class → run the reader.
- **Manual upgrade matrix** with the `migrationTest` build type (§5.2.5), signed with the same key as the installed
  Flutter APK: install Flutter 1.6.9+28 (E1) / 1.7.2+31 (E2) / an E1→E2 updated device (F1 case) / a 1.7.2 device after
  app reset; create identity (local, Amber, NIP-46), badges, running rolling-QR session, favorites, widget placed;
  `adb install -r` native → verify identity npub, signing, badges count, session, widget content before first app start,
  widget taps.

---

## 5. Release engineering

### 5.1 Current Flutter setup

| Item | Value (source) |
|---|---|
| applicationId / namespace | `space.einundzwanzig.meetup` (`android/app/build.gradle.kts`) |
| SDK | `compileSdk 36`, `minSdk 23`, `targetSdk 36`, `ndkVersion 27.0.12077973`, Java/Kotlin target 1.8; AGP 8.12.1, Kotlin 2.2.20 (`settings.gradle.kts`) |
| Version | `pubspec.yaml` `version: 1.7.2+31` → `versionName 1.7.2`, `versionCode 31` (`flutter.versionCode`). History: 1.0.0+1 (Feb–Jul 2026), 1.4.3+12, 1.6.9+28, 1.7.0+29, 1.7.1+30, 1.7.2+31. UEBERGABE: "N (versionCode) muss streng steigen". |
| Signing | `android/key.properties` (gitignored together with `*.jks`, `*.keystore`): `storePassword`, `keyPassword`, `keyAlias`, `storeFile` (resolved by `file()` in the **app** module, i.e. relative to `android/app/`). Missing file ⇒ release is signed with the **debug** config. `BUILD_APK.md` example: `keytool -genkey -v -keystore ~/einundzwanzig-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias einundzwanzig`. The real alias is the CI secret `KEYSTORE_KEY_ALIAS` (value not in the repo). |
| CI | `.github/workflows/build_apk.yml`: on push to `main`, tags `v*`, manual. Zulu JDK 17, `platforms;android-36`, `build-tools;35.0.0`, NDK 27.0.12077973. Writes `KEYSTORE_BASE64` → `android/app/release.jks` and `android/key.properties` (`storeFile=release.jks`). `flutter build apk --release --obfuscate --split-debug-info=build/debug-info` (one universal APK, **no** ABI split). Renamed to `einundzwanzig-meetup-v${VERSION}-${DATE}.apk`. Artifacts: APK, Dart debug symbols. Push to `main` → GitHub pre-release `latest` ("Neueste Version (automatisch)", `make_latest: true`); tag → release "Release vX.Y.Z". |
| Shrinking | `isMinifyEnabled = false`, `isShrinkResources = false` (no R8; only Dart obfuscation) |
| ABIs | Flutter release engine: `armeabi-v7a`, `arm64-v8a`, `x86_64` (x86 removed upstream) |
| Distribution | GitHub Releases (tag + rolling), Zapstore (UEBERGABE: `zsp publish zapstore.yaml` signed via bunker; the yaml is not in this repo), sideload instructions in `BUILD_APK.md`/`QUICKSTART_APK.md`. No Google Play listing in the repository. iOS separately (App Store + AltStore PAL, `docs/ALTSTORE_PAL.md`; bundle id `dev.einundzwanzig.einundzwanzigMeetupApp`) — not affected by the Android rewrite except that both apps must stay wire compatible. |
| App links | none (no `autoVerify` intent filters, no `assetlinks.json`) |

### 5.2 Native

#### 5.2.1 Signing

`android-native/app/build.gradle.kts`:

```kotlin
val signingProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun secret(name: String, env: String): String? = signingProps.getProperty(name) ?: System.getenv(env)

android {
    signingConfigs {
        create("release") {
            val store = secret("storeFile", "E21_KEYSTORE_FILE")
            if (store != null) {
                storeFile = file(store)
                storePassword = secret("storePassword", "E21_KEYSTORE_PASSWORD")
                keyAlias = secret("keyAlias", "E21_KEY_ALIAS")
                keyPassword = secret("keyPassword", "E21_KEY_PASSWORD")
                enableV1Signing = false        // minSdk 26: v2 + v3 are sufficient
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes.getByName("release") {
        // No silent debug fallback (the Flutter build had one): without a keystore AGP produces
        // app-release-unsigned.apk, which cannot be installed over the Flutter app by accident.
        signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
    }
}
```

`keystore.properties` and `*.jks` go into `android-native/.gitignore`. CI reuses the **same** secrets
(`KEYSTORE_BASE64`, `KEYSTORE_STORE_PASSWORD`, `KEYSTORE_KEY_PASSWORD`, `KEYSTORE_KEY_ALIAS`). Pin the certificate:
take the SHA-256 once from an official Flutter APK (`apksigner verify --print-certs einundzwanzig-meetup-v1.7.2-*.apk`),
store it as repository variable `RELEASE_CERT_SHA256`, and fail the release job if
`apksigner verify --print-certs app-release.apk` reports a different digest. Never rotate the key without an APK
Signature Scheme v3.1 lineage (`apksigner rotate`).

#### 5.2.2 Versioning

- `versionCode = major * 10_000 + minor * 100 + patch` → 2.0.0 = **20000**, 2.0.1 = 20001, 2.1.0 = 20100. Must exceed
  every Flutter code including split-ABI builds (`ABI_VERSION * 1000 + 31` = 1031/2031/4031; Flutter Gradle plugin
  `FlutterPlugin.kt`) — F4. Replace the current `versionCode = 200`.
- `versionName` semver `2.0.0`; debug suffix `-debug` as now.
- Rolling builds from `main` must not be published as the GitHub "latest" release while the migration is unverified
  (Flutter users tracking "latest" via Obtainium would get it). Use a separate pre-release tag (e.g. `native-latest`,
  `make_latest: false`).

#### 5.2.3 SDK levels

`compileSdk 37`, `targetSdk 36`, `minSdk 26` (ARCHITECTURE.md). targetSdk 36 implications handled elsewhere:
edge-to-edge enforced (API 35+), predictive back on by default, orientation/aspect-ratio requests ignored on large
screens (API 36), stricter background activity launches (router: §3.2), `JobScheduler`/WorkManager quotas. Watch item
for a later targetSdk bump: Android's local-network protection (access to `*.local` mempool instances) may require a new
runtime permission — re-check the release notes of the next target API.

#### 5.2.4 R8 (`app/proguard-rules.pro`, currently empty)

Release builds already use `isMinifyEnabled = true`, `isShrinkResources = true` with
`proguard-android-optimize.txt`. Most libraries ship consumer rules; add only:

```proguard
# Readable stack traces in the diagnostic log (deobfuscate with mapping.txt from the release artifacts)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# secp256k1-kmp-jni-android 0.24.0 ships "-keep class fr.acinq.secp256k1.jni.** { *; }" (loader found by reflection).
# Nothing else is needed for it; keep JNI method names in our own code (none today) generically:
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# BouncyCastle 1.86: lightweight API only (no JCA provider registration) -> no keep rules.
# bcprov references optional JNDI/LDAP classes:
-dontwarn javax.naming.**

# Tink (ESP recovery in :data:migration; tink-android ships its own rules). Annotations only:
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# kotlinx.serialization, Hilt, OkHttp, Glance, WorkManager, Media3, CameraX, ML Kit, Credential Manager,
# Navigation 3: consumer rules are bundled. Do not add blanket -keep rules.
```

Release-build checks (instrumented test on the minified `migrationTest` build): Schnorr sign/verify, NIP-44
round trip, NIP-49 decrypt, every `@Serializable` `NavKey` restored after process death (reflection-based serializer
lookup), osmdroid map renders, ML Kit scan, ESP read path. Upload `mapping.txt` with every release (CI artifact).

#### 5.2.5 Build types

| Type | applicationId | Signing | Use |
|---|---|---|---|
| `debug` | `space.einundzwanzig.meetup.native.debug` | debug | development side by side with the Flutter app |
| `release` | `space.einundzwanzig.meetup` | release key (§5.2.1) | distribution |
| `migrationTest` (`initWith(release)`, `isDebuggable = true`, `matchingFallbacks += "release"`) | `space.einundzwanzig.meetup` | **the key of the Flutter APK installed on the test device** (debug keystore for locally built Flutter APKs, which fall back to the debug key; never the release key on developer machines) | upgrade testing (§4.11); `run-as` allows inspecting `shared_prefs/` |

#### 5.2.6 ABIs and packaging

`defaultConfig { ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64") } }` — same ABIs as the Flutter APK;
drops the `x86` copies of `libsecp256k1-jni.so` and `libbarhopper_v3.so`. Keep `jniLibs.useLegacyPackaging = false`
(AGP default: uncompressed, page-aligned). Checked: the 64-bit `.so` files of `secp256k1-kmp-jni-android 0.24.0` and
`barcode-scanning 17.3.0` have 16 KB (`0x4000`) LOAD alignment; verify every release with
`zipalign -c -P 16 -v 4 app-release.apk`. Ship one universal APK on GitHub (Obtainium/Zapstore pick a single asset);
build an AAB only for a Play listing.

#### 5.2.7 CI

Same triggers as today. Steps: JDK 17 → `tools/gradlew-locked :app:assembleRelease` (or plain `./gradlew` in CI) →
unit tests of touched modules → signing-certificate pin check → 16 KB alignment check → rename to
`einundzwanzig-meetup-v${versionName}-${date}.apk` (same pattern as before, so Obtainium filters keep matching) →
upload APK + `mapping.txt` → release (tag) / `native-latest` pre-release (main).

### 5.3 Distribution notes

- **GitHub Releases**: users install the APK over the Flutter app. Release notes must state: update in place, data is
  taken over, Android 8.0+ required (F5), do not uninstall first (uninstalling deletes the Keystore keys and every
  local secret; only a backup file restores them).
- **Obtainium** users tracking the repository: asset naming stays; first native release must be a normal (non-pre)
  release.
- **Zapstore**: publish the same signed APK; the Zapstore event lists the certificate hash — it must not change.
- **Google Play** (not used today): if added later, use **Play App Signing with the existing key** (upload the current
  release key), otherwise Play-delivered APKs carry a different certificate and can never update sideloaded installs
  (or vice versa). Same for the assetlinks fingerprint (§5.4).
- **F-Droid**: not possible with the bundled ML Kit model or Play-services location; a `foss` flavor would need ZXing
  scanning (`zxing-core 3.5.4`) and `LocationManager` only.
- **iOS / AltStore PAL**: unaffected; keep wire compatibility with the iOS Flutter app (ARCHITECTURE hard requirement).

### 5.4 App links / Digital Asset Links

No App Links exist or are needed for parity. Passkeys (EasyAuth, spec 01) use RP ID `einundzwanzig.space`; Android
Credential Manager validates the app against `https://einundzwanzig.space/.well-known/assetlinks.json`, which does not
exist (HTTP 404, 2026-10-20 — F9). Required content (served as `application/json`, no redirects):

```json
[{
  "relation": ["delegate_permission/common.handle_all_urls", "delegate_permission/common.get_login_creds"],
  "target": {
    "namespace": "android_app",
    "package_name": "space.einundzwanzig.meetup",
    "sha256_cert_fingerprints": ["<RELEASE_CERT_SHA256 as AA:BB:…>"]
  }
}]
```

Add the Play App Signing fingerprint as a second entry if Play is ever used. Debug/`migrationTest` builds signed with
other keys cannot create passkeys for this RP ID (expected).

---

## 6. Platform-level security items

| Item | Source | Flutter implementation | Native requirement |
|---|---|---|---|
| Secrets out of plain prefs | SecurityAudit Fix 1; Audit 2 #3 (base payload), C3 (session seed) | flutter_secure_storage (ESP → custom GCM), one-time migration from prefs | `SecureStore` (Keystore AES-256-GCM, non-exportable, no user-auth requirement so background signing/sync work); E0 plaintext routing (§4.3.4); `readChecked` `Unreadable` never triggers key regeneration |
| Screenshot / recents protection while nsec or ncryptsec is visible | M3 (`ScreenSecure.set`) | `FLAG_SECURE` on the activity window via `einundzwanzig/screen` around the nsec dialog and the ncryptsec export (`profile_edit.dart`) | reference-counted `SecureWindow` effect: `window.addFlags(FLAG_SECURE)` while any secret is composed, `clearFlags` when the last one leaves; **and** `DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)` / `ModalBottomSheetProperties(securePolicy = …)` for every dialog/sheet that shows a secret (separate windows, F7). The iOS overlay is not needed. |
| Device authentication before revealing secrets | M3 (`ScreenSecure.authenticate`) | `local_auth` PIN/pattern/biometric, `biometricOnly=false`, `stickyAuth`; device without lock → allow; cancel/error → don't show | `BiometricPrompt` (`BIOMETRIC_WEAK or DEVICE_CREDENTIAL`), `KeyguardManager.isDeviceSecure == false` → allow; host must be a `FragmentActivity` (F8) |
| Clipboard | M3 (`ScreenSecure.copySecret`) | copy, after 45 s clear the clipboard if it still holds exactly the secret | `ClipData` with `ClipDescription.EXTRA_IS_SENSITIVE = true` (API 33+; key string `"android.content.extra.IS_SENSITIVE"` below); a coroutine in `@ApplicationScope` clears after 45 s if the app is in the foreground and the primary clip still equals the secret; Android 10+ blocks clipboard reads in the background, so if the timer fires in the background, re-check on the next `ON_RESUME` within 10 min (Flutter could not clear in the background either) |
| Cleartext traffic | network_security_config comments | base cleartext off; allowed for `*.onion`, `*.local`, `127.0.0.1`, `localhost` | copy the XML (§1.3) and keep `:core:network` `CleartextPolicy`/`CleartextGuard` identical; no raw LAN IPs; no user CAs in release |
| Backup / device transfer | manifest | `allowBackup=false`, `fullBackupContent=false` | same + `dataExtractionRules` excluding all domains for cloud backup **and** device transfer (§1.5); migration snapshot and state in `noBackupFilesDir` |
| Exported components | N4 | only `MainActivity` exported; widget receiver, router, home_widget receiver/service not exported; NDEF filter removed | only `MainActivity` exported; verify the merged manifest in CI (`exported="true"` allow-list = `.MainActivity` + library components that require it, e.g. profileinstaller's receiver which is permission-guarded) |
| PendingIntents | widget | `FLAG_IMMUTABLE`, explicit component, distinct action/data/request code | Glance-created PendingIntents are immutable; router intent explicit |
| Root / integrity warning | M5 (`DeviceIntegrityService`) | warn, never block: files `/system/bin/su`, `/system/xbin/su`, `/sbin/su`, `/system/app/Superuser.apk`, `/system/app/SuperSU.apk`, `/data/local/bin/su`, `/data/local/xbin/su`, Magisk `/sbin/.magisk`, `/data/adb/magisk`, `/data/adb/modules`, BusyBox `/system/xbin/busybox`, `/system/bin/busybox` (file **or** directory exists); `ro.build.tags` test-keys only logged; banner text `DeviceIntegrityService.warningMessage` (German, hard-coded) | same checks with `File(path).exists()`, cached per process, banner on Home (spec 12) with localized text; no Play Integrity / SafetyNet (no Google dependency, no server) |
| Intents to other apps | N1, M2, NIP-55 | Amber intents with explicit `package`; `auth_url` only http(s); lightning via chooser | same (spec 01, 11); `safe_url_launcher` whitelist |
| Log hygiene | N5 | `auth_url` host only; diagnostic log is plaintext and shared by users | `AppLogger` never logs secrets, tokens, full URLs with queries; migration logging rules §4.10 |
| WebSocket frame size | M4 | drop frames > 512 KiB before parsing | spec 07 (OkHttp listener) |
| minSdk | SecurityAudit "offen" item 4 (minSdk 21/23 vs ESP) | minSdk 23 | minSdk 26 closes it |
| Web-only nsec | H4 | disabled on web | out of scope (no web build) |

---

## 7. Risks

| # | Risk | Impact | Mitigation |
|---|---|---|---|
| R1 | Different signing key for the native release | update refused; a user who uninstalls loses all Keystore-bound secrets | same CI secrets, certificate pin check, no debug fallback (§5.2.1) |
| R2 | Widget receiver renamed / moved | every placed widget silently removed | FQCN `space.einundzwanzig.meetup.MeetupWidgetProvider` in `:app` (F2); manual upgrade test with a placed widget |
| R3 | Migration reads before writes are blocked | a domain generates a new identity/badge key over an unread Flutter one | `MigrationGate` awaited inside `KeyValueStore`/`SecureStore`; `Unreadable` for pending legacy secrets (§4.7) |
| R4 | F1 ESP orphans not recovered | users who updated 1.6.x → 1.7.x keep their "lost" key/badges lost | ESP reader per known key (§4.6.4); device verification |
| R5 | ESP recovery resurrects a deliberately reset identity | privacy/UX confusion | npub-match rule; otherwise recovery-only with explicit user choice (§4.8) |
| R6 | Tink/ESP or Keystore misbehaves on some OEMs (e.g. keyset rewrite, `KeyStoreException` after OS upgrade) | secrets unreadable | byte snapshot before any ESP call; read-only access; never create aliases/keysets; retry loop; Flutter files kept until cleanup |
| R7 | CBC trial decryption yields garbage that looks valid | wrong secret imported | GCM first (authenticated); CBC only with strict UTF-8 + per-key validator (§4.6.3/4.6.5) |
| R8 | App reset before cleanup followed by a re-import | deleted data returns | state outside `app_prefs`; reset deletes Flutter files and sets `CLEANED` (F6) |
| R9 | versionCode below a split-ABI Flutter build | update refused for those users | versionCode 20000+ (F4) |
| R10 | Android 6–7.1 users (minSdk 26) | cannot update | release notes; their Flutter app keeps working |
| R11 | Device-to-device transfer copies Keystore-encrypted stores without keys | broken install on the new phone | `dataExtractionRules` exclude everything; on detection of `Unreadable` secrets with no Flutter files, guide the user to restore a backup file (spec 08) |
| R12 | Compose dialogs not covered by `FLAG_SECURE` | nsec visible in screenshots/recents | `securePolicy` on dialogs/sheets (F7); screenshot test on a device |
| R13 | `BiometricPrompt` without `FragmentActivity` | secrets can never be revealed | `MainActivity : FragmentActivity` (F8) |
| R14 | Passkeys without assetlinks | EasyAuth passkey path fails | publish assetlinks (§5.4) before shipping passkeys |
| R15 | Number formatting via `String.format`/`NumberFormat` | widget/dashboard strings differ from Flutter ("1.5%" vs "1.4%", "," decimal in de) | shared `Double.toStringAsFixed` in `:core:common` `core.common.dart` (§3.1.4) + fixture vectors |
| R16 | R8 strips reflection targets (secp256k1 loader, NavKey serializers) | crash only in release | release-variant instrumented tests (§5.2.4) |
| R17 | Rolling native builds reach Flutter users via "latest" | half-tested migration on real devices | `native-latest` pre-release tag (§5.2.2) |
| R18 | Profile picture path into `cacheDir` vanishes | picture lost | copy on migration if present; native picker writes to `filesDir` |
| R19 | Launcher label changes ("21Meetup" → "Einundzwanzig") | users don't find the app | product decision (§1.6) |
| R20 | Stale Flutter `RemoteViews` after the update ("Problem loading widget" / dead refresh button) | cosmetic until the first render | Glance package-replaced receiver + `updateAll` after migration (§3.2) |

---

## 8. Fixture `spec/fixtures/14-platform-migration-vectors.json`

All crypto vectors were re-verified with Node `crypto` (2026-10-20); the Java-serialized list and the double
encodings were produced with JDK 17.

| Key | Content | Used by |
|---|---|---|
| `fss_v10_gcm` | AES-128 key, prefix, three entries (`nostr_nsec_key`, `badge_encryption_key`, `portal_api_token`) with XML name, IV, plaintext, raw bytes and the exact `Base64.DEFAULT` stored value (line break after 76 chars, trailing `\n`) | §4.6.3 GCM path |
| `fss_v9_cbc` | AES-128-CBC entry (`nostr_npub_key`) | §4.6.3 CBC path |
| `badge_blob` | `badges_encrypted` format `base64(iv12):base64(ct‖tag)` with 64-hex key | §4.7 step 7, spec 02/03 |
| `flutter_shared_prefs` | full `FlutterSharedPreferences.xml` sample + expected typed map; `legacyJavaSerializedList`; `doubleEncoding`; `prefixes` | §4.6.1 |
| `home_widget_prefs` | `HomeWidgetPreferences.xml` sample, rendered values, empty-state values | §3 |
| `widget_formatting` | `fmtInt`, Moscow time, countdown, difficulty (incl. 1.45 and −0.04 rounding traps), hashrate | §3.1.4 |
| `nip55` | Amber permission extra, unsigned-event key order, intent URI, ContentResolver projections | spec 01 |
| `fss_constants` | every file name, entry/wrapped-key name, marker, Keystore alias and cipher parameter | §4.5/§4.6 |
| `storage_eras` | E0/E1/E2 with app versions | §4.1 |
| `prefs_key_inventory` | the 112 rows of §4.3 (key, type, files, meaning, sensitivity, action, spec) | tests: assert every Dart key has a native owner |
| `obsolete_prefs_keys` | §4.3.2 | migration skip list |
| `secure_key_inventory` | the 19 rows of §4.4 incl. validators | §4.6.4 known-key list, §4.6.5 |
| `migration_routing` | §4.3.4 / §4.8 rules in data form | orchestrator tests |
