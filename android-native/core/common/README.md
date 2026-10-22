# :core:common

Shared plumbing with no UI: coroutine dispatchers, time, the diagnostic log, and the Dart
compatibility layer that keeps hashes, signatures, JSON and dates byte-identical to the Flutter app.

```kotlin
implementation(project(":core:common"))   // feature modules get it from the convention plugin
```

Exposes `kotlinx-serialization-json` and `kotlinx-coroutines` as `api`.

Package root: `space.einundzwanzig.meetup.core.common`

## Infrastructure (root package)

| API | Purpose |
|---|---|
| `@IoDispatcher`, `@DefaultDispatcher` | Qualified `CoroutineDispatcher`s |
| `@ApplicationScope CoroutineScope` | Process-wide scope (`SupervisorJob` + `AppLogger.coroutineExceptionHandler`: an uncaught `launch` failure is logged as `ERROR [Async] Unbehandelter Fehler`, the process keeps running) for relays and sync |
| `TimeSource` / `SystemTimeSource` | Injectable wall clock (`nowMillis()`, `nowSeconds()`, `nowMicros()` — Dart `DateTime.now()` precision) |
| `AppStartupTask` | Hilt multibinding contract for work that runs after the first frame |

## Diagnostic log — `…core.common.log`

This is a port of `lib/services/app_logger.dart` and `diagnostics_service.dart`. Spec:
`spec/12-shell-home-settings.md` §3.1, §8.12, §8.13.

| API | Purpose |
|---|---|
| `interface DiagnosticLog` | Inject it. `debug/info/diag/warn/error/security/section`, `entries: StateFlow<List<LogEntry>>`, `verbose: StateFlow<Boolean>`, `isVerbose`, `setVerbose(on)`, `exportText(zone)`, `suspend clear()` |
| `object AppLogger : DiagnosticLog` | Static facade for code without DI (`AppLogger.warn("Tag", "msg", e)`). Also `attach(context)`, `suspend init(context)`, `installCrashHandler()`, `coroutineExceptionHandler`, `flushBlocking()`, `suspend importLegacy(rawDartJson, verbose)`, `registerSecret(s)`, `unregisterSecret(s)`, and `logger` (the single instance) |
| `class DiagnosticLogger` | The implementation: 800-entry ring buffer, a debounced write 2 s after the last entry (or at most 10 s after the first unsaved one), no write before `init`, a synchronous `flushBlocking()`, and a logcat `echo`. `AppLogger.logger` is the process instance, and Hilt provides the same one. Tests can build their own instance with a test scope and clock |
| `suspend fun <T> DiagnosticLog.measure(tag, label, rethrowError = false, action): T?` | Dart `AppLogger.measure`: logs `"<label> ok (N ms)"` on `debug`, or `"<label> FEHLGESCHLAGEN nach N ms"` with the cause on `error` |
| `data class LogEntry(timeMillis, level, tag, message)` | `format(zone)` gives `dd.MM HH:mm:ss  LEVEL [tag] msg` (export). `screenLine(zone)` gives `HH:mm:ss  LEVEL  tag: msg`. Also `timeOfDay`, `toJson()`/`toJsonLine()` (`{"t","l","g","m"}`), `fromJson`, `listFromDartJson` (Flutter prefs), `isProblem`, `logLevel` |
| `enum LogLevel` | `DEBUG, INFO, SEC, WARN, ERROR` (`code` = stored string) |
| `List<LogEntry>.problemsOnly()` | Log screen filter (ERROR and WARN; SEC is hidden) |
| `LogExport` | `SHARE_SUBJECT = "21Meetup Diagnose-Log"`, `shouldShareAsFile(text)` (over 100 KB), `writeToFile(text, dir)` |
| `interface LogStore`, `FileLogStore(dir)`, `FileLogStore.forContext(ctx)`, `InMemoryLogStore` | Persistence. The file store writes `filesDir/diagnostic_log.jsonl`: one Dart-JSON entry per line, written atomically. The verbose flag goes in `filesDir/diagnostic_verbose`. An unreadable log file is moved to `diagnostic_log.jsonl.unreadable` (`quarantine()`) before anything is written |
| `SecretRedactor` (`Default`, `REDACTED`) | Applied to every tag and message. It catches `nsec1…`/`ncryptsec1…`, `secret=`/`token=`/`key=`… URL params (bunker and nostrconnect), `Bearer`/`Basic`/NIP-98 `Nostr eyJ…`, and labelled values (`privkey=`, `"private_key":`, `secret:`, `token:`, `seed:` …). It also catches registered exact secrets, such as the active private key hex, which it stores only as a hash |
| `describeError(e)` | `Type: message (Ursache: Type: message)`, always on one line |
| `RelayParseTally(tag, label, log = AppLogger)` | `message()`, `failed(error?)`, `report()`, `seen`, `failures`. Reports once: `"<label>: N von M Relay-Nachrichten nicht auswertbar"`, plus a debug line with the first error |
| `DiagnosticsService(log, probe)` | `suspend logEnvironment(DiagnosticsSources(signingMode, inventory))` writes the start-up fingerprint in Dart order and wording |
| `EnvironmentProbe`, `AndroidEnvironmentProbe(ctx)` | Version, OS, locale and zone, location service, permission and last fix (no coordinates). Never prompts |
| `di.LoggingModule` | Provides `DiagnosticLogger`, `DiagnosticLog`, `SecretRedactor`, `EnvironmentProbe`, and `DiagnosticsService` |

Start-up wiring (app module):

```kotlin
override fun onCreate() {
    super.onCreate()
    AppLogger.attach(this)              // store attached, debug echo on; no I/O
    AppLogger.installCrashHandler()     // logs + flushes synchronously, then delegates
    appScope.launch { AppLogger.init(this@App) }   // loads the stored buffer on IO
    appScope.launch { diagnostics.logEnvironment(DiagnosticsSources(signingMode = { … }, inventory = { … })) }
}
```

The identity domain should call `AppLogger.registerSecret(privHex)` (and the bunker secret and
portal token) when it loads them, and `unregisterSecret` when it deletes them. The migration
calls `AppLogger.importLegacy(flutterPrefs["diagnostic_log"], flutterPrefs["diagnostic_verbose"])`.

## Dart compatibility — `…core.common.dart`

| API | Dart equivalent |
|---|---|
| `Double.toDartString()` | `double.toString()`: shortest round-trip digits, as in `1.0`, `0.000001`, `1e-7`, `1e+21`, `-0.0` |
| `Double/Long/Int.toStringAsFixed(n)` | `toStringAsFixed`: rounds the exact binary value half away from zero, keeps `-` on negative zero results, always uses `.` |
| `Double.dartRound(): Long` | `round()`: half away from zero, saturates, throws on NaN/Inf |
| `Double.dartToInt(): Long` | `toInt()` |
| `String.dartTrim()`, `isDartWhitespace(c)` | `String.trim()`, which uses a different whitespace set than Kotlin's `trim()` |
| `DartDateTime(microsecondsSinceEpoch, isUtc)` | `DateTime`. Provides `parse`/`tryParse`, `local(...)`/`utc(...)` with field overflow, `fromMilliseconds/MicrosecondsSinceEpoch`, `fromInstant`, `now(timeSource)`/`nowUtc`, `toIso8601String(zone)`, `toDartString(zone)` (`toString()`), `fields(zone)`, `year…microsecond`, `weekday`, `toUtc/toLocal`, `add/subtract/difference`, `isBefore/isAfter/isAtSameMomentAs`, `millisecondsSinceEpoch` (floor). Equality includes `isUtc`, while `compareTo` compares the instant only |
| `DartFormatException` | `FormatException` from `DateTime.parse` |
| `DartDuration(inMicroseconds)` | `Duration`: `inDays/inHours/inMinutes/inSeconds/inMilliseconds` (truncating), `toString()`, `of(days=, …)` |
| `DartUri.encodeQueryComponent(s)` / `encodeComponent(s)` | `Uri.encodeQueryComponent` / `encodeComponent` (`~` is kept and `*` is encoded, unlike `URLEncoder`) |

Dart-compatible UTF-8 (`DartUtf8`) and base64 (`DartBase64`) live in `:core:crypto`
(`space.einundzwanzig.meetup.core.crypto`), not here; depend on that module instead of copying
them. `DartUri` only uses a private UTF-8 percent-encoder that follows the same rules.

## JSON — `…core.common.json`

The **only** Dart-compatible JSON codec of the app (the former copy in `:core:crypto` was merged
into it; `:core:crypto` uses this one for event ids). Use it for every string that is hashed,
signed, put into a QR code or read by the Flutter app — kotlinx.serialization, org.json, Gson &
co. differ in escaping and number formatting. Doubles are printed with `Double.toDartString()`.

| API | Purpose |
|---|---|
| `DartJson.encode(value)` | Byte-exact Dart `jsonEncode` for `JsonElement`s and plain Kotlin values (Map, List, String, Int/Long, Double, Boolean, null) |
| `DartJson.encodePretty(value, indent = "  ")` | `JsonEncoder.withIndent('  ')` |
| `DartJson.canonicalJsonEncode(map)` | `BadgeSecurity.canonicalJsonEncode`: sorts top-level keys only |
| `DartJson.quote(s)`, `DartJson.writeString(sb, s)` | Dart string literal escaping |
| `DartJson.decode(text)` | Parses JSON as strictly as Dart's `jsonDecode` (throws `DartJsonFormatException`, a `SerializationException`) and keeps number literals verbatim, so int and double stay distinct |
| `DartJson.numberOf(primitive): DartNumber?` | `DartNumber.Int(Long)` / `DartNumber.Double(Double)`, typed the way `jsonDecode` types them |
| `jsonObjectOf("k" to v, …)`, `jsonArrayOf(…)`, `Any?.toJsonElement()` | Ordered builders: Int/Long become int, Double/Float become double |
| `JsonElement?.dartIntOrNull()`, `.dartNumOrNull()`, `.dartStringOrNull()`, `JsonElement.isDartInt` | Dart cast semantics (`x as int` fails for `1.0`) |

## URLs and results

| API | Purpose |
|---|---|
| `SafeUrl.httpUrlOrNull(url): SafeHttpUrl?`, `SafeUrl.isAllowedHttpUrl(url)` | Pure part of `launchHttpUrl` (Audit M2). Only `http`/`https` with a host get through. Open `SafeHttpUrl.url` with `ACTION_VIEW` |
| `SafeUrl.hostForLog(url)` | Host or `"unbekannt"`. Use it for NIP-46 `auth_url` logging (never log the full URL) |
| `runSuspendCatching { }` | `runCatching` that rethrows `CancellationException` |
| `Result<T>.logFailure(tag, msg)` / `logFailureDebug` | Logs a failure as `WARN` (or `debug`) and passes the result through |

## Tests

```
tools/gradlew-locked :core:common:testDebugUnitTest
```

The tests are ports of `app_logger_test`, `app_logger_init_race_test`, `app_logger_volume_test`
and `relay_parse_tally_test`. They also check the spec fixtures (`canonicalJson`, `dartIso8601`,
`event_id_escaping`, `toStringAsFixed1`, `compactBadge`). `DartJsonTest` also carries the
`jsonEncode` vectors of the former `:core:crypto` copy (doubles, escaping, kotlinx number
re-reading). `src/test/resources/dart-vectors` holds 6,400 Dart
reference values for `double.toString`, `toStringAsFixed` and `round`; the generator is next to
them.
