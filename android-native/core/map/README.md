# :core:map

OpenStreetMap map for Compose on osmdroid 6.1.20. It replaces `flutter_map` + `latlong2`
(spec 14 §2). The world map (spec 03 §2.4), the nearby map (spec 09 §2.2) and the location
picker (spec 09 §2.6) use it. The module has no screens, no NavKeys and no entries function:
`:feature:wallet` and `:feature:meetups` build their screens on it.

```kotlin
implementation(project(":core:map"))
```

Dependencies: `:core:common` (Dart number formatting, `AppLogger`), `:core:ui` (theme, springs,
`SafeUrlLauncher`), osmdroid, lifecycle-runtime-compose. The manifest adds `INTERNET` and
`ACCESS_NETWORK_STATE`.

## API (`space.einundzwanzig.meetup.core.map`)

| API | Notes |
|---|---|
| `OsmMap(cameraState, modifier, onMapClick, onMapLongClick, tileStyle, showAttribution = true, attributionAlignment, contentDescription, centerClickLabel) { content }` | OSM raster tiles at 256 dp per tile, so zoom levels match Flutter. Supports drag, fling, pinch and double-tap zoom. Rotation is always off. The world does not wrap, and the centre stays within ±180° and the Mercator latitude limit. Accessibility: the map surface has custom actions to pan north, south, east or west by a third of the viewport and to zoom in or out. With `centerClickLabel`, its click action calls `onMapClick` with the camera centre. Follows `onResume`/`onPause` and detaches on dispose. Shows the OSM attribution chip, which opens the OSM copyright page. |
| `OsmMapScope.Marker(position, modifier, anchor = Center, contentDescription, onClick) { }` | Any composable as a marker (flutter_map `Marker`). It pops in with an expressive spring, or appears without animation when reduced motion is on. It gets button semantics and an unbounded ripple. Compose enlarges touch targets to 48 dp. A marker tap does not reach `onMapClick`. Markers far outside the viewport are not placed. There is no clustering, because none of the Flutter maps cluster. |
| `OsmMapScope.Circle(center, radiusMeters, fillColor, strokeColor, strokeWidth = 1.5.dp)` | A circle with a radius in metres on the ground (flutter_map `CircleMarker(useRadiusInMeter: true)`), for example the nearby search radius |
| `rememberOsmCameraState(initialTarget, initialZoom, minZoom = 1, maxZoom = 19)` → `OsmCameraState` | `position` (updates on every gesture), `move`, `animateTo`, `zoomBy` (quick taps add up across a running animation), `canZoomIn/Out`, `suspend fitPoints(points, padding, maxZoom, animate)` (port of flutter_map `CameraFit.coordinates`; it waits for layout), `suspend awaitTilesLoaded(timeout)` (all visible tiles loaded, none a scaled placeholder), `toScreen`/`fromScreen`. Saveable, including the limits. The view applies the limits of the attached state. The default minimum is 1, because flutter_map had none and a fit across continents needs a zoom below 2 on phones |
| `MapTileStyle.Standard` / `Dark` | Standard keeps the Flutter look. Dark is a colour-filter night look (lightness inverted, hues kept) |
| `BitcoinBadgeMarker()`, `MeetupPinMarker(active)`, `LocationDot(pulse)`, `DropPinMarker()`, `MapMarkerDefaults` | The marker visuals of spec 03 §2.4 and spec 09 §2.2/§2.6. `LocationDot` doubles as the user-location dot (breathing halo, off with reduced motion) |
| `MapZoomControls(cameraState)` | Expressive `VerticalFloatingToolbar` with zoom in and zoom out (optional; Flutter had gestures only) |
| `MapAttribution()` | The attribution chip on its own, for screens that hide the built-in one |
| `LocationPickerMap(cameraState, picked, onPick)`, `rememberLocationPickerCameraState(initialPick)`, `LocationPicker.initialPick/initialCamera`, `LocationPicker.PICKED_ZOOM` (15), `DEFAULT_ZOOM` (5), `JUMP_TO_ME_ZOOM` (13), `COORDINATE_DIGITS` (5) | The map part of spec 09 §2.6. A tap sets the pick, and the pin tip sits on the point (`MarkerAnchor.PinTip`). TalkBack or switch-access users pan with the map actions and then use the click action "Kartenmitte als Ort wählen". The app bar, hint bar, "jump to me" and confirm button belong to `:feature:meetups` |
| `rememberMapSnapshotState()`, `Modifier.mapSnapshotArea(state)`, `suspend MapSnapshotState.capture(pixelRatio = 2.5f, settleMillis)` | World map share image (spec 03 §2.4). It captures everything drawn in the marked area (tiles, markers, stats bar) at `dp × pixelRatio` px. Suggested order: `awaitTilesLoaded()`, then `capture(settleMillis = WORLD_MAP_SETTLE_MS)` |
| `LatLng` (`isZero`, `GermanyCenter`, `DachCenter`), `LatLng.format(digits)`, `CameraPosition`, `LatLngBounds`, `MarkerAnchor` (`Center`, `BottomCenter`, `PinTip`), `PixelPoint` | Value types. `format` uses Dart `toStringAsFixed` |
| `WebMercator` | Projection, `fitCamera`, `metersToPixels`, `tileSizePx(density)` (pure functions, unit-tested) |
| `OsmUrls.marker(lat, lng)` | Directions link of spec 09 §2.2 with Dart `double.toString()` |
| `OsmConfiguration` | Process-wide osmdroid setup. Identifying UA `<applicationId>/<version> (Android)` (spec/00-contradictions.md item 17), tile cache in `cacheDir/osmdroid` (100 MB, trimmed to 80 MB), and a tile source for `https://tile.openstreetmap.org/` with at most 2 connections and no bulk or preventive downloads (OSM tile policy) |
| `LocalOsmTileRendering` | Provide `false` in tests and screenshots to draw a tile-grid placeholder instead of osmdroid. Previews do this automatically. Markers, taps and the camera work the same way |

### Feature recipes

- **World map (03 §2.4):** start at the first located badge with zoom 11 (one badge) or 4. Fit once and never again
  (the camera survives rotation and process death, so a refit would reset the user's zoom):
  `var didFit by rememberSaveable { mutableStateOf(false) }` and
  `LaunchedEffect(Unit) { if (!didFit) { if (located.size == 1) camera.move(p, 11.0) else camera.fitPoints(located, 60.dp, maxZoom = 12.0); didFit = true } }`.
  For each badge add `Marker(p, onClick = { selected = b }) { BitcoinBadgeMarker() }`.
  Put the map and stats bar in a `Box(Modifier.mapSnapshotArea(snapshot))`.
- **Nearby (09 §2.2):** add `Circle(center, radiusKm * 1000, primary.copy(alpha = 0.08f), primary.copy(alpha = 0.4f))`,
  `Marker(center) { LocationDot() }` and `Marker(p, onClick = …) { MeetupPinMarker(n.nextEvent != null) }`.
  For one point use `move(point, 10.0)`, otherwise `fitPoints(points, 50.dp, maxZoom = 12.0)`.

## Notes

- Markers are Compose nodes placed from `OsmCameraState.position`. During a fling, osmdroid
  scrolls inside its draw pass, so markers can trail the tiles by one frame.
- Horizontal world wrap is off, so markers never need a second copy of the world.

## Tests

`tools/gradlew-locked :core:map:testDebugUnitTest` runs 74 tests:
- Mercator projection and its round trip, the `CameraFit.coordinates` zoom and centre, clamping, metres to pixels.
- Camera state: commands, the driver, `fitPoints` waiting for layout, the tile wait, the saver.
- Formatting and URLs, user agent, tile policy.
- Robolectric Compose: marker placement and anchors, camera follow, marker vs map taps, long press,
  zoom controls, location picker (pin tip, accessibility pick of the centre), pan and zoom accessibility
  actions, camera restoration (`StateRestorationTester`), zoom limits, snapshot size.
- Real osmdroid view (`OsmdroidMapTest`): osmdroid's `Projection.toPixels` matches `OsmCameraState.toScreen`
  within 1.5 px at integer, fractional and low zoom and at a fractional density (tile 672 px); `move` and
  `animateTo` reach osmdroid; a view scroll updates the state; the map still works after its view is
  detached and reattached (`setDestroyMode(false)`).
- Native graphics (`OsmMapRenderTest`): the capture contains a marker's pixels at its scaled position
  the badge marker glow (drawn, not an elevation shadow, so the software capture keeps it) and the pixels
  of the osmdroid view.
