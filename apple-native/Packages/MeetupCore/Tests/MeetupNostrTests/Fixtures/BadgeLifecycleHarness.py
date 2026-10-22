#!/usr/bin/env python3
"""Compile and exercise actual App/BadgeStore.swift against fixture-only adapters.

The package code is linked normally; no copies of store logic or syntax-only
checks are used. This is not Apple SDK or Keychain-device validation.
"""
import json
import gzip
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

fixture = Path(__file__).resolve()
package = fixture.parents[3]
apple = package.parents[1]
harness = Path(tempfile.mkdtemp(prefix="swift-badge-lifecycle-"))
sources = harness / "Tests" / "BadgeLifecycleTests"
sources.mkdir(parents=True)
shutil.copy2(apple / "App/BadgeStore.swift", sources / "BadgeStore.swift")
shutil.copy2(fixture.parent / "BadgeLifecycleFixture.swift", sources / "BadgeLifecycleFixture.swift")
(harness / "Package.swift").write_text('''// swift-tools-version: 6.1
import PackageDescription
let package = Package(name: "BadgeLifecycleHarness", platforms: [.macOS(.v14)],
    dependencies: [.package(path: %s)],
    targets: [.testTarget(name: "BadgeLifecycleTests", dependencies: [
        .product(name: "MeetupNostr", package: "MeetupCore")])],
    swiftLanguageModes: [.v6])
''' % json.dumps(str(package)))
print("Actual BadgeStore lifecycle fixture harness:", harness, flush=True)
environment = os.environ.copy()
compressed = harness / "native-gzip.bin"
environment["BADGE_GZIP_FIXTURE_OUTPUT"] = str(compressed)
result = subprocess.call(["swift", "test", "--package-path", str(harness),
    "--scratch-path", "/tmp/swift-badge-lifecycle-build", "--cache-path", "/tmp/einundzwanzig-swiftpm-cache",
    "--disable-sandbox", "-j", "4", "--filter", "BadgeLifecycleTests"], env=environment)
if result == 0:
    expected = ("Swift gzip interop fixture äöü. " * 500).encode()
    assert gzip.decompress(compressed.read_bytes()) == expected
    print("Independent Python gzip decoder accepted native compressed stream.", flush=True)
raise SystemExit(result)
