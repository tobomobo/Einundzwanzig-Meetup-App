# :feature:network

"Meine Vernetzung", the co-attendance trust network screen (spec 05 §2.1).

## Screens

| NavKey | Route / screen | Spec |
|---|---|---|
| `MyTrustNetwork` | `MyNetworkRoute` / `MyNetworkScreen(state, onAction)` | 05 §2.1 |

Contents: first-load state with the staged build progress (`mnLoading` / `mnUpdating(n)`), empty
state (saved-state hint, failed banner, info box), reach card (total, distribution bar, split,
status chip: updating / saved state with `mnUpdateFailed` tooltip (`TooltipBox` + `PlainTooltip`) / `mnStandAt`), tree graph (Canvas, pulse, tap → detail with
path highlight, light haptic), degree filter (connected `ToggleButton` group, light haptic), search,
degree sections with "show all", "how it works" row, info sheet, person detail sheet (path,
shared meetups, also-via chips, copy npub with medium haptic and 2 s check, open in Nostr app with
njump.me fallback), failed-attendance banner with retry and result snackbar
(`mnRetryResult`, success/error tone), pull-to-refresh (forced full rebuild). App-bar actions:
info sheet, `VerifyPerson()` (owned by `:feature:reputation`).

## Entries

```kotlin
fun EntryProviderScope<NavKey>.networkEntries()
```

`:app` must add `implementation(project(":feature:network"))` and call `networkEntries()` in its
entry provider (owned by the app integrator).

## Structure

- `MyNetworkViewModel`: Dart `_load` / `_loadNames` / `_retry`; `StateFlow<MyNetworkUiState>` plus
  one-shot `MyNetworkEvent`s.
- `data/NetworkDataSource`: thin seam over the data layer (pure delegation) so the ViewModel can be
  tested with a fake; bound in `di/NetworkDataModule`.
- `NetworkPresentation`: pure display rules (sections, detail line, labels, stand time, tree
  layout, hit test, path target), JVM-tested.
- `ui/`: cards, graph, sheets.

## Dependencies

`:data:socialgraph` (`CoAttendanceRepository`, `MyNetwork`, `AttendanceKeyLabel`; brings
`:data:badges`), `:data:profile` (`NostrProfileService` display names), `:data:identity`
(`UserProfileRepository` for the profile npub). `NpubFormat`, haptics, `SecretClipboard`,
`SafeUrlLauncher` and snackbars come from `:core:ui`.

Native-only strings (`native_network_*`) are in `src/main/res/values{,-en,-es}`.
