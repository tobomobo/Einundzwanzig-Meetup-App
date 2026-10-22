# :feature:backup

Component feature for the `.21bkp` backup export and import UI (spec 08 §2). It has no NavKeys
and no screens of its own (ROUTES.md, "Modules without destinations"). `:feature:home`,
`:feature:settings` and `:feature:onboarding` may depend on it; ARCHITECTURE.md allows these
feature → feature dependencies.

## What it renders (spec 08)

| Piece | Spec | Code |
|---|---|---|
| Password dialog, export (password + confirmation, eye toggles, live length/match rules, coloured borders, button disabled until both rules pass, not dismissible by tapping outside, `FLAG_SECURE`) | §2.2 | `BackupPasswordDialog` |
| Password dialog, import (one field, inline `backupPasswordEmpty`) | §2.2 | `BackupPasswordDialog` |
| Blocking loading overlay during key derivation | §2.3 | `BackupLoadingOverlay` |
| Replace-identity dialog (destructive; back cancels, tap outside does not) | §2.5.1 | `BackupDialogs` → `IdentityConfirmDialog` |
| Add-to-identity dialog (back cancels, tap outside does not) | §2.5.2 | `BackupDialogs` → `IdentityConfirmDialog` |
| Export orchestration: password → overlay → SAF `CreateDocument` → share-sheet fallback | §2.4 | `BackupCoordinator.export()` |
| Import orchestration: SAF `OpenDocument` → password → decrypt → identity dialogs → restore | §2.5 | `BackupCoordinator.restore()` |
| Snackbars (`badgesUnreadableBody` 8 s, `backupError`, `backupWrongPassword`, `backupImportFailed`, `backupMergeAdded`/`backupMergeSame`, `backupLoaded`) | §1 #12–#21 | `BackupMessages` |

The data logic comes from `:data:backup`: `BackupExporter`, `BackupCrypto`, `BackupDocument`,
`RestoreEngine`, `BackupSession` (shared busy guard), `BackupPasswordRules` and `BackupFiles`.
Badge sync has no UI (spec 08 §1 #25). `:data:badgesync` is used only through `RestoreEngine`'s
restore hold, so this module does not depend on it directly.

## API for callers

```kotlin
// App root, once, inside the theme next to the app's SnackbarHost:
BackupHost(snackbarHostState)

// ViewModel (home reset flow, settings sheet, intro, identity setup): inject BackupLauncher
// and call it from viewModelScope.
val ok: Boolean = backupLauncher.export()   // reset flow: false ⇒ abort the reset (§2.6)
backupLauncher.restore()                    // always reload all data afterwards (§2.1)

// Fire and forget, e.g. from a sheet that is closed before the flow starts:
backupLauncher.launchExport { ok -> }
backupLauncher.launchRestore { ok -> }

// Composable: only for `busy` and for launchX calls that need no result.
val launcher = rememberBackupLauncher()
```

- **Callers that need the result must use a ViewModel.** The SAF pickers and the share sheet are
  separate activities; a rotation while they are open recreates the host activity. A call to
  `export()`/`restore()` from `rememberCoroutineScope` is then cancelled and the picked file is
  dropped, and a `launchX` callback would touch a disposed composition. Intro and identity setup
  (navigate to the app shell on `true`, §2.1) inject `BackupLauncher` into their `@HiltViewModel`
  and expose the navigation as UI state or an event.
- The flows may run on the application scope (`Dispatchers.Default`); the coordinator starts the
  SAF pickers on the main thread (`Dispatchers.Main.immediate`; core:common has no main qualifier,
  tests use `Dispatchers.setMain`), and `ShareHelper` switches to main itself. File I/O uses the
  injected `@IoDispatcher`.
- If the host activity is destroyed while the share sheet is open, the share counts as dismissed:
  `export()` returns `false` and the busy guard is released.

- `BackupLauncher.busy` tells callers when to disable their backup buttons (§1 #23).
- In previews and UI tests of a calling screen, provide `LocalBackupLauncher provides NoOpBackupLauncher`
  or your own fake.
- The reset-flow dialogs (§2.6) belong to home/settings. They only call `export()`.

## Deviations from Flutter

- The restored-item list uses plurals: "1 Badge", "1 Plattform-Proof" (Flutter always wrote
  "N Badges" / "N Plattform-Proofs", spec 08 §1 #20). The count-based restore errors
  (`native_backupErrStageFailed`, `native_backupErrBadgesNotSaved`) are plurals as well.
- `RestoreError.InvalidContent` shows the localized `backupNotValid` text; the technical detail
  (German, from `:data:backup`) goes to the log only. Flutter showed the detail.
- `ImportFailure.Unexpected` and `RestoreError.Other` still show the raw exception text (storage,
  signer, JVM messages), untranslated in en/es, as Flutter did. If that text is blank, the
  localized `native_backupErrUnreadable` is shown instead.
- Typed passwords are not restored after a configuration change (not written to the saved-state
  bundle on purpose); the dialog itself stays open.
- If writing the chosen SAF document fails, the empty/partial document is deleted (best effort)
  before the share-sheet fallback, so no 0-byte `.21bkp` is left behind.
- `export()` without a mounted `BackupHost` returns `false` at once (no password dialog that nobody
  renders), like `restore()`. The app shell must mount `BackupHost(snackbarHostState)` once at the root.
- The password rule rows expose "Erfüllt"/"Nicht erfüllt" (`native_backupRuleMet`/`NotMet`) as
  TalkBack state description (polite live region).
- Snackbars are not re-shown after a configuration change: a message is consumed once, and a
  snackbar that is visible during a rotation disappears with the recreated `SnackbarHostState`.

## Entries function

There is none, because the module has no NavKeys.

## Dependencies

`:data:backup`, `androidx.activity:activity-compose` (SAF launchers), plus the feature convention
(`:core:common`, `:core:ui` for `ShareHelper`, `ConfirmDialog`, snackbars and secure windows,
`:core:l10n`, `:core:navigation`).

## Strings

ARB keys from spec 08 §2.7. Native-only strings use the `native_backup*` prefix in
`res/values{,-en,-es}/strings.xml`: the restored-item words and the restore error texts that
Flutter hard-coded in German, plus the overlay content description and the rule-row state
descriptions.

## Tests

`BackupCoordinatorTest` covers the flows with fakes, `BackupCoordinatorThreadTest` checks that the
pickers start on the main thread when the flow runs on a background scope. The Robolectric tests are
`BackupDialogsTest` (incl. autofocus and back on the identity dialogs), `BackupFlowUiTest` (the
coordinator driving the dialogs end to end), `BackupHostTest` (attach/detach, SAF result routing,
snackbars), `ShareBackupTest` (share-file handling) and `BackupMessagesTest` (German texts). Run them with:

```
tools/gradlew-locked :feature:backup:testDebugUnitTest
```
