#!/usr/bin/env python3
"""Compile actual app stores and callbacks; fixture platform/storage only."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import sys

fixture = Path(__file__).resolve()
package = fixture.parents[3]
apple = package.parents[1]
harness = Path(tempfile.mkdtemp(prefix="swift-app-store-continuity-"))
sources = harness / "Tests" / "AppStoreContinuityTests"
sources.mkdir(parents=True)
for name in ["AppStore.swift", "UserContinuity.swift", "IdentityStore.swift", "BadgeStore.swift", "WorkflowStore.swift", "LearningStore.swift",
             "Platform/IdentityVault.swift", "Platform/IdentityFlutterUpgrade.swift", "Models/LocalModels.swift", "Models/PreviewFixtures.swift", "Models/AppLocalization.swift"]:
    source = apple / "App" / name
    shutil.copy2(source, sources / source.name)
shutil.copy2(fixture.parent / "IdentityAppStoreFixture.swift", sources / "IdentityAppStoreFixture.swift")
for source in fixture.parent.glob("WorkflowAppStore*.swift"):
    shutil.copy2(source, sources / source.name)
shutil.copytree(fixture.parent / "BackupInterop", sources / "Fixtures/BackupInterop")
(harness / "Package.swift").write_text('''// swift-tools-version: 6.1
import PackageDescription
let package = Package(name: "AppStoreContinuityHarness", platforms: [.macOS(.v14)],
    dependencies: [.package(path: %s)], targets: [.testTarget(name: "AppStoreContinuityTests",
        dependencies: [.product(name: "MeetupNostr", package: "MeetupCore"), .product(name: "MeetupCore", package: "MeetupCore")],
        resources: [.copy("Fixtures")], swiftSettings: [.define("APP_STORE_LIFECYCLE_TESTS")])], swiftLanguageModes: [.v6])
''' % json.dumps(str(package)))
print("Actual AppStore continuity harness:", harness, flush=True)
raise SystemExit(subprocess.call(["swift", "test", "--package-path", str(harness), "--scratch-path", "/tmp/swift-app-store-continuity-build",
    "--cache-path", "/tmp/einundzwanzig-swiftpm-cache", "-c", "release", *sys.argv[1:]]))
