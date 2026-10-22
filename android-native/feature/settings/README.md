# :feature:settings

Settings bottom sheet and diagnostic log (spec 12 §2.6, §2.8, §2.9.1; NIP-44 probe spec 01 §2.9).

## Public API

- `fun EntryProviderScope<NavKey>.settingsEntries()` registers `DiagnosticLog` → `DiagnosticLogRoute()`.
- `SettingsSheet(visible, onDismissRequest, onResetApp, onRestoreFinished, …)` (package `sheet`):
  the gear-icon sheet, hosted by `:feature:home`. Keep it in the composition and toggle `visible`;
  its `SettingsSheetViewModel` is scoped to the host entry, so the "Touren zurückgesetzt" snackbar
  appears after the sheet closed. `onResetApp` must run the reset flow of 12 §2.5.12 (owned by home);
  `onRestoreFinished` is called after every restore attempt (Flutter `_loadAll()`).
- `SettingsSheetContent(state, onAction, backupBusy, enabled)`: stateless body (previews/tests);
  `enabled = false` while the sheet hides, so a double tap runs an action only once.
- `SettingsTour`: the 7-step settings tour; started 650 ms after opening, finished (marked done) on close.

## Host requirements (`:feature:home`, `:app`)

- Keep `visible` in `rememberSaveable { mutableStateOf(false) }` (or ViewModel state). A language
  change recreates the activity (per-app locale), as does rotation; with plain `remember` the sheet
  would vanish, whereas Flutter keeps it open. The settings tour survives the recreation (it is
  neither finished nor restarted from step 0 on a configuration change).
- `:app` must mount `BackupHost(snackbarHostState)` once at the root (see `feature/backup/README.md`).
  Without it `launchExport`/`launchRestore` return `false` immediately and the backup and restore
  rows do nothing.

## Deviations

- Sheet height: a fixed 92 % `ModalBottomSheet` without a partially expanded stop. Flutter uses a
  `DraggableScrollableSheet` (opens at 85 %, draggable 50–95 %, closes below 50 %). M3's partial
  stop is always half the screen and the sheet would open there instead of near-full height, so
  the native sheet opens almost full and closes when dragged down.

- The log copy is clipped to the newest ~500 KB when the buffer is larger (Flutter copies all of
  it); a larger clip would crash `setPrimaryClip`. Sharing still sends the full log as a file.

## Screens

| Screen | Spec | Notes |
|---|---|---|
| Settings sheet (+ language dialog, currency dialog) | 12 §2.6 | Profile, backup/restore (via `:feature:backup` `rememberBackupLauncher`), relays, mempool, language (per-app locale, `LocaleController`), currency (`CurrencyRepository`), haptics switch, native theme mode (Dark/Light/System connected `ToggleButton` group), Diagnose-Log, restart tours, V4V, reset, version footer |
| `DiagnosticLog` | 12 §2.8 | Terminal-style log as one selectable text (selection spans all lines, like Flutter's `SelectableText.rich`), level colours, NIP-44 probe, WARN/ERROR filter, verbose toggle, copy, share (text or `.txt` via FileProvider above the size threshold, clipboard fallback), count + "Log leeren" footer, empty state |

Native-only strings are in `res/values{,-en,-es}/strings.xml` (`native_` prefix). The log screen
texts (`native_log_*`) and the version footer (`native_settings_version`) are hard-coded German in
Flutter (spec 12 §2.8 F44, §2.6); for parity all three files hold the byte-identical German text
(so `native_log_count` stays the Flutter form `N Einträge`, no plural). Theme-mode labels and the
drag-handle description are native-only and translated.

Clipboard copy of the log (Copy and the share fallback) goes through `LogClipboard`: the text is
cut to its newest ~500 KB (at a line start) to stay below the Binder limit, and a failing
`setPrimaryClip` shows `native_log_copy_failed` (German, like the screen) instead of crashing.

## Dependencies

`:data:settings` (haptics, theme mode, `LocaleController`/`AppLocales`), `:data:identity`
(`SigningService.canNip44()` behind the `Nip44Probe` seam), `:data:mempool` (currency),
`:feature:backup` (backup/restore launcher), `androidx.activity:activity-compose` (`LocalActivity`, to tell a configuration change from a real close), plus core common/ui/l10n/navigation. The `GuideController`
and the `DiagnosticLog` logger come from `:core:ui` / `:core:common`.
