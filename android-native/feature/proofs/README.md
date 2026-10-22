# :feature:proofs

Screens for platform proofs and Proof of Humanity (spec 06 §2.1, §2.2).

## Entries

```kotlin
fun EntryProviderScope<NavKey>.proofsEntries()
```

| NavKey | Route | Spec |
|---|---|---|
| `PlatformProofs` | `PlatformProofsRoute` / `PlatformProofsScreen` + `PlatformProofsViewModel` | 06 §2.1 |
| `HumanityProof` | `HumanityProofRoute` / `HumanityProofScreen` + `HumanityProofViewModel` | 06 §2.2 |

## Screens

- **PlatformProofs**: info card, active link cards (platform icon, display name, mono
  username, `ppCreated`, QR-embedded marker, revoke button with tooltip), primary button
  (`ppLinkPlatform`/`ppAnotherPlatform`), create bottom sheet (FilterChips with an "already
  linked" check, custom name field for `other`, username field with a per-platform hint),
  silent validation, wavy progress while the signer signs (native addition), revoke
  confirmation dialog. On success it shows `ppLinkSaved` (success, 3 s) on the app snackbar
  host and closes the screen (spec 06 §14 R3). On failure it shows a dismissable error
  snackbar with the service message.
- **HumanityProof**: status card (verified or not verified), "how it works" card with four
  steps and a hint box, check button (check now, check again, or searching), the last-check
  line and the result box.

Create (sign, store, republish) and revoke (write, republish) run in the `@ApplicationScope`, so
leaving the screen while Amber or a NIP-46 bunker is signing never loses an approved proof; only
the UI events are tied to the ViewModel. The sheet's text fields are `TextFieldState`s held in
`CreateSheetState` (synchronous, IME-safe). The humanity "last check" label is recomputed on
resume (`refreshClock()`).

Both screens use a forced, fire-and-forget reputation republish (`ReputationRepublisher`,
application scope) after a proof is created, revoked or found.

Tours: none (spec 06 §2.7: no active tour steps on these screens).

## Dependencies

`:data:proofs` (PlatformProofRepository, PlatformCatalog, HumanityProofRepository),
`:data:reputation` (ReputationPublisher), `:data:badges` (BadgeRepository for the publish).

Native-only strings (`native_*`, de/en/es) cover the platform hints, service messages
(spec 06 §2.6) and a few accessibility labels.

## Tests

41 tests in 5 classes: PlatformProofsScreenTest (12), PlatformProofsViewModelTest (11),
HumanityProofScreenTest (6), HumanityProofViewModelTest (8), DefaultReputationRepublisherTest (4).
The Robolectric UI tests run with `qualifiers=de-w411dp-h891dp` and scroll the lazy list where needed.
