#!/usr/bin/env bash
set -euo pipefail

# A release-mode APK with its own package, app data and development certificate.
# Usage: FLUTTER_BIN=/path/to/flutter bash tool/build_android_preview.sh 1.7.3-perf.1 32
preview_version=${1:?Usage: build_android_preview.sh VERSION BUILD_NUMBER}
preview_build_number=${2:?Usage: build_android_preview.sh VERSION BUILD_NUMBER}

export ORG_GRADLE_PROJECT_performancePreview=true
"${FLUTTER_BIN:-flutter}" build apk --release \
  --build-name="$preview_version" \
  --build-number="$preview_build_number" \
  --obfuscate \
  --split-debug-info=build/debug-info/performance-preview
