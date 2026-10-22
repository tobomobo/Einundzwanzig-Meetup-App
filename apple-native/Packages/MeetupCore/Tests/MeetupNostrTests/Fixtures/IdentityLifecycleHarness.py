#!/usr/bin/env python3
"""Run real IdentityStore state transitions with fixture Keychain, never live keys.

Uses the caller's Swift toolchain/environment. Apple SDK/Keychain validation is a
separate Apple-host gate; this compiles Observation and actual async store logic.
"""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

fixture = Path(__file__).resolve()
package = fixture.parents[3]
apple = package.parents[1]
harness = Path(tempfile.mkdtemp(prefix="swift-identity-lifecycle-"))
sources = harness / "Tests" / "IdentityWorkflowTests"
sources.mkdir(parents=True)
for source in [apple / "App/IdentityStore.swift", apple / "App/Platform/IdentityVault.swift", apple / "App/Platform/IdentityFlutterUpgrade.swift", apple / "App/BadgeStore.swift",
               fixture.parents[1] / "IdentityAppLifecycleTests.swift"]:
    shutil.copy2(source, sources / source.name)
shutil.copytree(fixture.parent / "BackupInterop", sources / "Fixtures/BackupInterop")
(harness / "Package.swift").write_text('''// swift-tools-version: 6.1
import PackageDescription
let package = Package(name: "IdentityWorkflowHarness", platforms: [.macOS(.v14)],
    dependencies: [.package(path: %s)],
    targets: [.testTarget(name: "IdentityWorkflowTests", dependencies: [
        .product(name: "MeetupNostr", package: "MeetupCore")], resources: [.copy("Fixtures")],
        swiftSettings: [.define("IDENTITY_APP_LIFECYCLE_TESTS")])],
    swiftLanguageModes: [.v6])
''' % json.dumps(str(package)))
print("Identity lifecycle fixture harness:", harness, flush=True)
raise SystemExit(subprocess.call(["swift", "test", "--package-path", str(harness),
    "--scratch-path", "/tmp/swift-identity-lifecycle-build", "--cache-path", "/tmp/einundzwanzig-swiftpm-cache",
    "-c", "release", "--filter", "IdentityAppLifecycleTests"]))
