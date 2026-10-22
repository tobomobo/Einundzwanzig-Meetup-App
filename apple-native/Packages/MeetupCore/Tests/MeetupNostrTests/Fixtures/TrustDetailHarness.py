#!/usr/bin/env python3
"""Focused offline trust parity checks against the actual App/BadgeStore.swift.

Uses synthetic signer/relay/HTTP fixtures. No Apple SDK, UI rendering, emulator,
real network relay, payment, or production key is exercised. Dependencies must
already be available locally; resolution and updates are disabled.
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
harness = Path(tempfile.mkdtemp(prefix="swift-trust-detail-fixture-"))
sources = harness / "Tests" / "FocusedTrustFixtures"
sources.mkdir(parents=True)
for source in [apple / "App/BadgeStore.swift", fixture.parent / "TrustDetailStoreFixture.swift",
               fixture.parent.parent / "TrustDetailParityTests.swift",
               fixture.parent.parent / "BadgeProtocolTests.swift",
               fixture.parent.parent / "BadgeJourneyParityTests.swift",
               fixture.parent.parent / "TestFixtures.swift"]:
    shutil.copy2(source, sources / source.name)
# Keep the exact fixture resource directory expected by fixtureData.
shutil.copytree(fixture.parent, sources / "Fixtures")
shutil.copy2(package / "Package.resolved", harness / "Package.resolved")
(harness / "Package.swift").write_text('''// swift-tools-version: 6.1
import PackageDescription
let package = Package(name: "TrustDetailHarness", platforms: [.macOS(.v14)],
    dependencies: [.package(path: %s)],
    targets: [.testTarget(name: "FocusedTrustFixtures", dependencies: [
        .product(name: "MeetupNostr", package: "MeetupCore")], resources: [.copy("Fixtures")])],
    swiftLanguageModes: [.v6])
''' % json.dumps(str(package)))
print("Actual BadgeStore trust detail fixture harness:", harness, flush=True)
# The module name deliberately does not match the suite selector.
selector = "TrustDetail|PersonIdentityMappingParityTests|ReputationEvidenceRegressionTests|humanityNeedsSignedRequest"
raise SystemExit(subprocess.call(["swift", "test", "--package-path", str(harness),
    "--scratch-path", os.environ.get("TRUST_DETAIL_SCRATCH_PATH", "/tmp/swift-trust-parity-detail-build"),
    "--cache-path", os.environ.get("TRUST_DETAIL_CACHE_PATH", "/tmp/swift-trust-parity-detail-build/cache"),
    "--disable-automatic-resolution", "--skip-update", "--disable-sandbox",
    "-Xswiftc", "-gnone", "-j", "4", "--filter", selector]))
