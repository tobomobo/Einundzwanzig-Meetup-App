# Actual Flutter ↔ Kotlin backup interoperability

These files contain **public synthetic test data**: private scalar `1`, the password in
`provenance.json`, an invented profile and two unsigned wallet entries. Never fund or reuse
this identity. These entries exercise data preservation; they confer no trusted attendance.

`flutter-v5.21bkp` was captured from the unchanged Flutter
`BackupService.createBackup` through its real password dialog and encryption/export flow.
Only preferences, secure storage and the operating-system file picker are replaced. The
source hash, file hash and Dart version are recorded in `provenance.json`.
`flutter-v5.json` is its decrypted document, including UTF-8/emoji data, a consistent local
key, favorites and the distinct event/presence flags of the two wallet entries.

`FlutterBackupInteropTest` decrypts and restores this file using the Kotlin production
codec, restore engine and badge repository. It checks the restored key and every exported
data section. The resulting `kotlin-v5.21bkp` was then passed to the real Flutter
`BackupService.restoreBackup`; its widget test verifies the restored key, profile and wallet.
No production Flutter source was modified. This verifies file and restore compatibility,
separately from Android upgrade/device tests and trusted badge-signature validation.

Run the checks from the repository root with Flutter dependencies installed and JDK 17:

```bash
flutter test android-native/tools/interop/flutter_backup_fixture_test.dart
cd android-native
tools/gradlew-locked :data:backup:testDebugUnitTest --tests '*FlutterBackupInteropTest'
```

To regenerate into a temporary directory, first run the Flutter test with
`E21_BACKUP_INTEROP_DIR=/tmp/e21-backup-interop` and select `--plain-name 'real Flutter exporter'`.
Copy its three output files into this fixture directory. Run the Kotlin test with the same
environment variable, copy its `kotlin-v5.21bkp` here, then run the Flutter importer test
against it. `E21_BACKUP_NATIVE_FILE` can select another native export, including a Swift
export, without replacing the checked-in Kotlin fixture. Update provenance hashes when
intentionally regenerating these randomized encrypted files.
