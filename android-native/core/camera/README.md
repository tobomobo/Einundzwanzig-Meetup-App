# :core:camera

Shared QR scanner, the replacement for `mobile_scanner` (spec 14 §2). It uses CameraX 1.6
(`camera-compose` `CameraXViewfinder`) and the **bundled** ML Kit barcode model, limited to
`FORMAT_QR_CODE`. The bundled model works without Play services. The module has no screens and
no NavKeys. The badge, reputation and verify-person features build their scanner screens on it
(spec 02 §2.2/§2.6, spec 04 §2.3/§2.7.1).

```kotlin
implementation(project(":core:camera"))
```

The manifest declares `CAMERA` and `uses-feature camera` / `camera.autofocus` with `required=false`.
Both merge into the app.

## API (`space.einundzwanzig.meetup.core.camera`)

| API | Notes |
|---|---|
| `QrScanner(onResult: (String) -> Boolean, modifier, state = rememberQrScannerState(), permissionState = rememberCameraPermissionState(), enabled = true, showTorchButton = true, overlay = { ScannerOverlay() })` | Full-bleed camera with the overlay slot (default `:core:ui` `ScannerOverlay`, spec 02 §2.7). It shows the permission panel or the unavailable panel when needed, an expressive loading indicator while starting, and a torch toggle above the scan window. `onResult` runs on the main thread. Return `true` to stop ("ignore after first hit"). |
| `rememberQrScannerState()` → `QrScannerState` | `cameraStatus` (`Starting/Running/Unavailable`), `hasTorch`, `torchOn`, `toggleTorch()`, `setTorch(on)`, `isStopped`, `stop()` (stop delivering, for example after a gallery hit, spec 04 §2.3), `resume()` (scan again, for example after the demo dialog), `retry()`. `isStopped` survives recreation. Stopping never touches the torch. `Running` is set only once CameraX reports the camera `OPEN`. Errors after binding (camera in use, too many cameras, disabled by policy, fatal) switch to `Unavailable`, and CameraX reopening the camera switches back. |
| `ScanDebouncer(sameValueWindowMillis = 1500, minIntervalMillis = 250)` | Delivers the same value at most once per window and at most one value per interval. After a stop it delivers nothing until reset. |
| `enabled = false` | Pauses frame analysis and delivery. The preview keeps running. Use it while a dialog is open. |
| `rememberCameraPermissionState(autoRequest = true)` → `CameraPermissionState` | `status` (`Granted/NotRequested/Denied/PermanentlyDenied`), `launchPermissionRequest()`, `openAppSettings()`. The status is saved across recreation (a "denied forever" stays), then reconciled with the real permission. On first open it asks once by itself and re-checks on every `ON_RESUME`, so returning from Settings works. `CameraPermissionLogic` holds the pure transition rules. |
| `CameraPermissionContent(status, onRequestPermission, onOpenSettings)`, `CameraUnavailableContent(onRetry)` | Brand panels: `MaterialShapes` icon container with a spring entrance and 56 dp actions. When the permission is denied forever, the panel shows "Einstellungen öffnen" plus "Erneut fragen". Android 11+ also reports a dismissed dialog as denied forever. |
| `TorchButton(on, onToggle)` | Shape-morphing `FilledIconToggleButton` with one stable label ("Taschenlampe") and an on/off state description. Use it in a top bar together with `showTorchButton = false`. |
| `rememberQrImagePicker(onResult: (QrImageResult) -> Unit): QrImagePicker` | `launch()` opens the Photo Picker (`PickVisualMedia(ImageOnly)`, no permission needed) and decodes the image. `isDecoding` is true meanwhile, and `launch()` is ignored then. A cancelled pick gives no callback. The picked URI is saved, so decoding restarts after recreation. |
| `QrImageDecoder.decode(context, uri)` / `decode(InputImage)` → `QrImageResult` | `Codes(values)` / `NoCode` / `Failed(error)`. Downsamples to 2048 px on the long edge (`ImageDecoder` with EXIF rotation on API 28+, `BitmapFactory` `inSampleSize` below). Replaces `analyzeImage` for the gallery flows (spec 02 §2.2: silent on no code; spec 04 §2.3: snackbars `qrNoCodeInImage` / `qrNotEinundzwanzig` / `qrProcessError`). The caller maps the result. |
| `QrScannerTestTags` | `Preview`, `Loading`, `Torch`, `Permission`, `Unavailable` |

Payload parsing (badge JSON, `21v3:` prefixes, `extractNpub`, the demo payload) belongs to the
features and data modules. This module only delivers raw strings.

Feature screens: draw the spec's top bar and the bottom hint boxes over `QrScanner`. The torch
sits about 182 dp above the centre, so it stays clear of the bottom boxes at 60 dp. In short
windows it moves down so it stays below the status bar plus a 64 dp top bar.

## Lifecycle

The camera binds to `LocalLifecycleOwner`. CameraX stops it in the background, which also turns
off the torch. It unbinds and closes the ML Kit client and the analyzer thread when the
composable leaves the composition. Frame analysis uses `STRATEGY_KEEP_ONLY_LATEST` at about
1280×720. If there is no back camera it falls back to the front camera, and with no camera at
all it shows `Unavailable`.

## Native-only strings

`res/values{,-en,-es}/strings.xml`: `native_camera_permission_title/_body/_rationale/_denied_forever/_grant/_ask_again`,
`native_camera_open_settings`, `native_camera_unavailable_title/_body`, `native_camera_retry`,
`native_camera_preview_description`, `native_torch`, `native_torch_state_on/_off`. The Flutter app had
no texts for these because mobile_scanner showed none.

## Tests

`tools/gradlew-locked :core:camera:testDebugUnitTest`: 47 tests.
- Permission transition logic and the state holder, including restoration of "denied forever"
- Debouncer and stop/resume behaviour, including restoration of a stopped scanner
- CameraX `CameraState` mapping and the torch placement in short windows
- `QrImageDecoder`: unreadable URIs give `Failed`, sample size
- Robolectric Compose tests against a fake camera backend: delivery and stop, `stop()` from the gallery path, `enabled`, torch (kept on across a hit), loading until the camera opens, the unavailable state (bind failure and errors after binding) and retry, the permission panel, starting the camera after a grant, release on dispose, and permission state from the shadow permissions
- The permission and unavailable panels: texts, actions and 48 dp targets
