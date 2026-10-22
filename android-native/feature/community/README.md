# :feature:community

Screens of spec 11 §2.5–§2.12: community hub, portal area, courses and lecturers, V4V donation,
PlebRap player and glossary.

```kotlin
implementation(project(":feature:community"))
// in the app's entryProvider:
communityEntries()
```

`fun EntryProviderScope<NavKey>.communityEntries()` (`CommunityEntries.kt`) registers:

| NavKey | Route / screen | Spec |
|---|---|---|
| `CommunityHub` | `CommunityHubRoute` / `CommunityHubScreen` (+ `CommunityHubViewModel`) | §2.5 |
| `PortalArea` | `PortalAreaRoute` / `PortalAreaScreen` (stateless, no ViewModel) | §2.6 |
| `Courses` | `CoursesRoute` / `CoursesScreen` (+ `CoursesViewModel`), info sheet for items without int id | §2.7 |
| `CourseDetails(courseId)` | `CourseDetailsRoute` / `CourseDetailsScreen` (+ assisted `CourseDetailsViewModel`) | §2.8 |
| `LecturerDetails(lecturerId)` | `LecturerDetailsRoute` / `LecturerDetailsScreen` (+ assisted `LecturerDetailsViewModel`) | §2.8 |
| `ValueForValue` | `ValueForValueRoute` / `ValueForValueScreen` (+ `ValueForValueViewModel`), no-wallet copy sheet | §2.10 |
| `PlebrapLibrary` | `PlebrapRoute` / `PlebrapScreen` (+ `PlebrapViewModel`) | §2.11 |
| `Glossary` | `GlossaryRoute` / `GlossaryScreen` (+ `GlossaryViewModel`) | §2.12 |

Tours: the hub runs `GuideTour.Portal` (4 steps), the portal area `GuideTour.PortalArea` (4 steps),
both 500 ms after start, finished on dispose (`StartTourEffect` / `FinishTourOnDispose` from `:core:ui`).

## Dependencies

- `:data:community`: `V4vService`, `LightningInvoiceLauncher`, `PlebrapPlayer`/`PlebSongs`, `Glossary`,
  `PortalFields`, `PortalEntityCache` (detail fallback, replaces Flutter's `fallback` argument), `CommunityLinks`.
- `:data:proofs`: `SatoshiDuellClient` (hub card status).
- `:data:meetups`: `PortalApiService` (courses / lecturers), wrapped in the narrow `PortalCoursesSource`.
- `:data:identity`: `SigningService.npub()` for the SatoshiDuell auto-login link (`NpubSource`).

## Notes

- Course "add to calendar" uses the system `ACTION_INSERT` calendar screen (title / location / desc,
  start, start + 2 h), the native replacement of `Add2Calendar`.
- The V4V and PlebRap snackbars use Flutter's default 4 s display time; `prLoadError` is shown for every
  `loadErrors` change while the screen is at least STARTED. Errors from before the screen opened or from
  while it was in the background are skipped (each collection drops the replayed current value).
- Hub colours: the Podcast card uses `extendedColors.purple` (`cPurple`), the SatoshiDuell card
  `extendedColors.duellGold` (`#FFC93C`), both added to `:core:ui` `ExtendedColors`.
- Courses: the Kurse/Dozenten lists sit in a `HorizontalPager` (swipeable like Flutter `TabBarView`), synced
  with `state.tab` both ways; the connected toggle row exposes `Role.Tab` + `selected` semantics. Each tab keeps
  its own scroll position. List rows show the 42×42 cNostr icon box like Flutter (no portal images); the
  lecturer detail course rows use the typed `PortalCourseRef` from `:data:community`.
- Insets: every `Scaffold` body calls `consumeWindowInsets(padding)` after `padding(padding)`, so the nav-bar/IME
  padding inside is not added twice. Hub cards use `heightIn(min = …)` (text scales; the two-card rows share
  their height via `IntrinsicSize.Min`).
- The no-wallet copy sheet animates out (`sheetState.hide()`) before the copy + `v4vCopied` snackbar.
- Glossary expansion: an entry follows "expanded while searching" until the user toggles it; the toggle
  is kept per title (Flutter `PageStorageKey(title)`).
- The dashboard PlebRap mini player (§2.11) and the home tiles live in `:feature:home`.

Native-only strings (`res/values{,-en,-es}/strings.xml`, prefix `native_community_`): content
descriptions for player controls, clear-search, add-to-calendar, link, seek, and the not-found text.

## Cross-module edits

- `:core:ui` `ExtendedColors`: `purple` (`cPurple`) and `duellGold` (`#FFC93C`).
- `:data:community`: `PortalCourseRef(id, name, image)`; `PortalLecturer.courses` is `List<PortalCourseRef>`.

## Tests

```
tools/gradlew-locked :feature:community:testDebugUnitTest
```

73 tests: ViewModel tests with fakes (`CommunityHubViewModelTest`, `CoursesViewModelTest` incl. course
and lecturer details, `ValueForValueViewModelTest`, `PlebrapViewModelTest`, `GlossaryViewModelTest`),
Robolectric Compose UI tests (`HubAndPortalScreenTest`, `CoursesScreenTest`, `PlayerV4vGlossaryScreenTest`),
route wiring (`RouteNavigationTest`: NavKeys and URLs of hub, portal area, courses and detail routes, V4V
no-wallet sheet + clipboard + `v4vCopied`, PlebRap `prLoadError` snackbar, community tour start after 500 ms
and finish on dispose), the calendar insert intent (`CalendarInsertTest`) and the
tour step lists (`TourStepsTest`).
