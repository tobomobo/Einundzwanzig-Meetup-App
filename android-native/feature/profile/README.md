# :feature:profile

The user's own profile and the Nostr relay settings. Spec: `spec/07-nostr-infra-profile.md` §2.1–§2.2,
`spec/01-identity-keys.md` §2.5 and §2.7 (key management and BunkerConnectSheet embedded in the
profile), `spec/09-meetups-portal.md` §2.5 (favourites picker).

```kotlin
implementation(project(":feature:profile"))   // :app only
// in the NavDisplay entryProvider:
profileEntries()
```

## Screens

| NavKey | Route / screen | Spec |
|---|---|---|
| `ProfileEdit` | `ProfileEditRoute` → `ProfileEditScreen(state, onAction, bunkerState, snackbarHostState)` | 07 §2.2, 01 §2.7 |
| `RelaySettings` | `RelaySettingsRoute` → `RelaySettingsScreen(state, onAction, snackbarHostState)` | 07 §2.1 |

`ProfileEditScreen` covers: loading / load-error (retry) states; read-only view for verified
organizers (avatar, "VERIFIZIERT", info tiles, signer management, identity tiles, "BEARBEITEN
(Status verlieren)" + warning dialog); edit form (nickname validator, home-meetup field with
favourites sheet, Nostr key section, identity tiles, save with `profileFillIn` validation snackbar,
`resetTo(HomeTab)` after saving). Dialogs/sheets: key education, nsec backup (FLAG_SECURE,
45 s clipboard clear), show-nsec confirmation, nsec import, ncryptsec password dialog and result
sheet (vault fast path, "anderes Passwort"), signer switch confirmation, Amber missing,
disconnect confirmation, BunkerConnectSheet (choose / QR pairing / paste, inline `auth_url`
button, back blocked while busy), MeetupSearchSheet. Device authentication runs in the route
(`DeviceAuthenticator`). **`:app`'s `MainActivity` must extend `FragmentActivity`** (spec 14 F8):
with a plain `ComponentActivity` host the route logs `DeviceAuth needs FragmentActivity` and shows
the red `errorGeneric` snackbar, so NSEC display, the nsec backup after key creation and the
ncryptsec export are denied. A configuration change cancels a running prompt and the new
composition shows it again (the request stays pending in the ViewModel).

Avatar: the header shows the locally picked picture (pref `local_profile_picture`, same as the
Home header, spec 07 §8.8) or the kind-0 picture / letter. In edit mode a tap opens the system
photo picker; the URI goes to `:data:profile` `LocalPictureImporter.import(uri)` (≤ 400 px, JPEG
q80, `filesDir/profile_picture/`, `setLocalPicture`, then older files are deleted). The feature only
owns the `PickVisualMedia` launcher; `:feature:home` uses the same importer (spec 12 F14).

## Dependencies

`:data:identity` (SigningService, SecureKeyStore, LocalKeyVault, NostrKeyService,
UserProfileRepository, DeviceAuthenticator; `RelayConfig` and `Nip49` via its api), `:data:profile`
(`NostrProfileService`, `LocalPictureImporter`), `:data:meetups` (directory, `FavoriteMigration`,
`MeetupSearchRows` for the sheet's filter/title/subtitle with Dart case mapping), `:data:proofs` (platform proof
count, humanity status), `androidx.activity:activity-compose` (photo picker, BackHandler).

## Native-only strings

`res/values{,-en,-es}/strings.xml`: `native_relays_active` (plural, Flutter hard-coded
"N aktive Relays"), `native_platforms_active` (plural), `native_relay_remove` ("Entfernen"),
`native_nostr_label`, `native_npub_button`, `native_nsec_button`, `native_nsec_hint`,
`native_list_joiner` (" und "), `native_auth_show_key` / `native_auth_export_key` (biometric
prompt titles), `native_more_favorites` ("+N ★") with the plural a11y label `native_more_favorites_a11y`,
accessibility labels (`native_relay_enabled/disabled`, `native_meetup_favorite`,
`native_meetup_not_favorite`, `native_bunker_qr`, `native_profile_avatar`).

## Deviations

- Disconnecting a signer re-reads only the identity part instead of the full `loadData()`, so
  unsaved form input survives (identical result in read-only mode).
- The home-meetup field shows the raw ID (Flutter) plus the resolved name as a second line, and
  "+N ★" when more favourites are selected.
- The nsec import dialog is also screenshot-protected (Flutter was not).
- No Blossom upload / kind-0 publish of the avatar: `lib/screens/profile_edit.dart` has none (Blossom
  is only used in `event_calendar_screen.dart`) and spec 07 §2.2 names none; a kind-0 publish would
  also overwrite metadata the user maintains in other Nostr clients. Do not re-request it here.
- `:data:settings` is not a dependency: the profile screen reads/writes nothing of it.
- The nsec import is not blocked while a key generation / Amber connect runs (Flutter and spec 01
  §2.7: "all except import are disabled"); a second tap while an import runs is ignored and the
  "Importieren" button shows a LoadingIndicator.

## Tests

`tools/gradlew-locked :feature:profile:testDebugUnitTest` — 73 tests:
`ProfileEditViewModelTest` (31, fakes in `testing/Fakes.kt`; incl. save/import double-tap guards and
the missing-FragmentActivity path), `ProfileEditScreenTest` (26, Robolectric Compose incl. dialogs,
ncryptsec sheet, picker and bunker sheet), `RelaySettingsViewModelTest` (6, incl. a stale relay test
after reopening the dialog), `RelaySettingsScreenTest` (7), `SnackbarEffectTest` (3, replace drops
a queued message). The importer itself is tested in `:data:profile` (`LocalPictureImporterTest`).

## Migration recovery and identity safety

The profile offers parked Flutter identities (including overflow slots) for authenticated restore,
export or discard. Confirmation precedes device authentication; passwords live only in ordinary
memory, and exported keys use secure windows and SecretClipboard. Recovery remains accessible
when the active identity cannot be loaded. Restoring clears stale vault wraps before enabling
local signing. The data-layer IdentityChangeHook runs synchronously before identity persistence;
the app binds proof cleanup and must throw if that cleanup fails. Key errors use the shared typed
identity message resources in German, English and Spanish, including scrypt memory failures.
The profile avatar uses the Nostr picture; local gallery selection belongs to Home.
