#!/usr/bin/env python3
"""Run focused product journeys against the actual App/BadgeStore.swift.

Only local signer, relay, HTTP and ciphertext fixtures are used. This does not
validate SwiftUI, Apple SDK, Keychain, camera, location or real relay runtime.
Set BADGE_JOURNEY_FILTER to include selected existing lifecycle guard tests.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

fixture = Path(__file__).resolve()
package = fixture.parents[3]
apple = package.parents[1]
harness = Path(tempfile.mkdtemp(prefix="swift-badge-journey-"))
sources = harness / "Tests" / "BadgeJourneyTests"
sources.mkdir(parents=True)
shutil.copy2(apple / "App/BadgeStore.swift", sources / "BadgeStore.swift")
shutil.copy2(fixture.parent / "BadgeLifecycleFixture.swift", sources / "BadgeLifecycleFixture.swift")
(harness / "Package.swift").write_text('''// swift-tools-version: 6.1
import PackageDescription
let package = Package(name: "BadgeJourneyHarness", platforms: [.macOS(.v14)],
    dependencies: [.package(path: %s)],
    targets: [.testTarget(name: "BadgeJourneyTests", dependencies: [
        .product(name: "MeetupNostr", package: "MeetupCore")])],
    swiftLanguageModes: [.v6])
''' % json.dumps(str(package)))
print("Actual BadgeStore product journey fixture harness:", harness, flush=True)
raise SystemExit(subprocess.call(["swift", "test", "--package-path", str(harness),
    "--scratch-path", os.environ.get("BADGE_JOURNEY_SCRATCH_PATH", "/tmp/swift-badge-parity-forward-store-build"),
    "--cache-path", "/tmp/einundzwanzig-swiftpm-cache", "--disable-sandbox", "-j", "4",
    "--filter", os.environ.get("BADGE_JOURNEY_FILTER", "BadgeJourneyParityTests")]))
