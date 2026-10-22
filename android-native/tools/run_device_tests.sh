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
wifi_before=""
data_before=""
finish() {
  local status=$?
  if (( status != 0 )); then
    echo "Device runner failed with exit $status; collecting emulator diagnostics" >&2
    "$adb" -s "$serial" logcat -d -t 500 > "$artifact_dir/logcat.txt" 2>&1 || true
    "$adb" -s "$serial" shell cat /proc/meminfo > "$artifact_dir/guest-memory.txt" 2>&1 || true
    free -m > "$artifact_dir/host-memory.txt" 2>&1 || true
  fi
  "$adb" -s "$serial" pull "/sdcard/Android/data/$package/files/device-failures" "$artifact_dir/failures" 2>/dev/null || true
  if [[ "$wifi_before" == 1 ]]; then "$adb" -s "$serial" shell svc wifi enable || true; fi
  if [[ "$data_before" == 1 ]]; then "$adb" -s "$serial" shell svc data enable || true; fi
  return "$status"
}
trap finish EXIT
tools/gradlew-locked ":app:assemble${task_variant}" ":app:assemble${task_variant}AndroidTest" \
  :data:migration:assembleDebugAndroidTest "-PinstrumentationBuildType=$variant" --max-workers=3
"$adb" -s "$serial" install -r "app/build/outputs/apk/$variant/app-$variant.apk"
"$adb" -s "$serial" install -r "app/build/outputs/apk/androidTest/$variant/app-$variant-androidTest.apk"
# Exercise bundled/offline behavior and prevent startup services from contacting public relays.
# Restore only the connectivity that this dedicated AVD had before the test run.
echo "Reading emulator Wi-Fi state"
wifi_before="$("$adb" -s "$serial" shell settings get global wifi_on | tr -d '\r')"
echo "Reading emulator mobile-data state"
data_before="$("$adb" -s "$serial" shell settings get global mobile_data | tr -d '\r')"
echo "Disabling emulator Wi-Fi"
"$adb" -s "$serial" shell svc wifi disable
echo "Disabling emulator mobile data"
"$adb" -s "$serial" shell svc data disable
echo "Running app runtime tests"
"$adb" -s "$serial" shell am instrument -w \
  -e notClass space.einundzwanzig.meetup.testing.SeedFlutterUpgradeFixtureTest,space.einundzwanzig.meetup.testing.UpgradeDeviceTest \
  "$package.test/androidx.test.runner.AndroidJUnitRunner" | tee "$artifact_dir/app-$variant.txt"
grep -Eq '^OK \([1-9][0-9]* tests?\)' "$artifact_dir/app-$variant.txt"
echo "Running actual Flutter Keystore format tests"
"$adb" -s "$serial" install -r data/migration/build/outputs/apk/androidTest/debug/migration-debug-androidTest.apk
"$adb" -s "$serial" shell am instrument -w \
  space.einundzwanzig.meetup.data.migration.test/androidx.test.runner.AndroidJUnitRunner \
  | tee "$artifact_dir/legacy-storage.txt"
grep -Eq '^OK \([1-9][0-9]* tests?\)' "$artifact_dir/legacy-storage.txt"
