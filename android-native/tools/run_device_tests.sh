#!/usr/bin/env bash
# Build and execute the real Android runtime/legacy-storage checks on a dedicated test AVD.
# preview: non-debuggable, genuinely optimized/obfuscated app plus mapping-adapted test APK.
# migrationTest: unminified and debuggable for in-place Flutter upgrades and run-as fixtures.
# This suite excludes the upgrade-specific assertions; run those separately with expectedNpub.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
variant="${1:-debug}"
serial="${2:-emulator-5556}"
artifact_dir="${3:-/tmp/e21-device-tests}"
adb="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
case "$variant" in
  debug) task_variant=Debug; package=space.einundzwanzig.meetup.native.debug ;;
  preview) task_variant=Preview; package=space.einundzwanzig.meetup.preview ;;
  migrationTest) task_variant=MigrationTest; package=space.einundzwanzig.meetup ;;
  *) echo "Expected debug, preview or migrationTest" >&2; exit 2 ;;
esac
avd_name="$("$adb" -s "$serial" emu avd name | tr -d '\r' | head -1)"
[[ "$avd_name" == e21test* ]] || { echo "Refusing non-test AVD: $avd_name" >&2; exit 2; }
mkdir -p "$artifact_dir"
tools/gradlew-locked ":app:assemble${task_variant}" ":app:assemble${task_variant}AndroidTest" \
  :data:migration:assembleDebugAndroidTest "-PinstrumentationBuildType=$variant" --max-workers=3
"$adb" -s "$serial" install -r "app/build/outputs/apk/$variant/app-$variant.apk"
"$adb" -s "$serial" install -r "app/build/outputs/apk/androidTest/$variant/app-$variant-androidTest.apk"
# Exercise bundled/offline behavior and prevent startup services from contacting public relays.
# Restore only the connectivity that this dedicated AVD had before the test run.
wifi_before="$("$adb" -s "$serial" shell settings get global wifi_on | tr -d '\r')"
data_before="$("$adb" -s "$serial" shell settings get global mobile_data | tr -d '\r')"
restore_connectivity() {
  if [[ "$wifi_before" == 1 ]]; then "$adb" -s "$serial" shell svc wifi enable; fi
  if [[ "$data_before" == 1 ]]; then "$adb" -s "$serial" shell svc data enable; fi
}
trap restore_connectivity EXIT
"$adb" -s "$serial" shell svc wifi disable
"$adb" -s "$serial" shell svc data disable
"$adb" -s "$serial" shell am instrument -w \
  -e notClass space.einundzwanzig.meetup.testing.SeedFlutterUpgradeFixtureTest,space.einundzwanzig.meetup.testing.UpgradeDeviceTest \
  "$package.test/androidx.test.runner.AndroidJUnitRunner" | tee "$artifact_dir/app-$variant.txt"
grep -Eq '^OK \([1-9][0-9]* tests?\)' "$artifact_dir/app-$variant.txt"
"$adb" -s "$serial" install -r data/migration/build/outputs/apk/androidTest/debug/migration-debug-androidTest.apk
"$adb" -s "$serial" shell am instrument -w \
  space.einundzwanzig.meetup.data.migration.test/androidx.test.runner.AndroidJUnitRunner \
  | tee "$artifact_dir/legacy-storage.txt"
grep -Eq '^OK \([1-9][0-9]* tests?\)' "$artifact_dir/legacy-storage.txt"
