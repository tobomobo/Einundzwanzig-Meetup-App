#!/usr/bin/env python3
"""Run the real WorkflowStore offline, substituting only transport and Keychain."""
from pathlib import Path
import json
import shutil
import subprocess
import sys
import tempfile
fixture = Path(__file__).resolve()
package = fixture.parents[3]
apple = package.parents[1]
harness = Path(tempfile.mkdtemp(prefix="swift-portal-workflow-store-"))
sources = harness / 'Tests' / 'PortalWorkflowStoreTests'
sources.mkdir(parents=True)
shutil.copy2(apple / 'App' / 'WorkflowStore.swift', sources / 'WorkflowStore.swift')
shutil.copy2(fixture.parent / 'PortalWorkflowStoreFixture.swift', sources / 'PortalWorkflowStoreFixture.swift')
(harness / 'Package.swift').write_text('''// swift-tools-version: 6.1
import PackageDescription
let package = Package(name: "PortalWorkflowStoreHarness", platforms: [.macOS(.v14)],
 dependencies: [.package(path: %s)], targets: [.testTarget(name: "PortalWorkflowStoreTests",
 dependencies: [.product(name: "MeetupNostr", package: "MeetupCore"), .product(name: "MeetupCore", package: "MeetupCore")])], swiftLanguageModes: [.v6])
''' % json.dumps(str(package)))
# A source snapshot prevents other authorized workers' edits from changing
# compiler inputs mid-build. No implementation is rewritten in this copy.
local_package = harness / 'MeetupCore'
shutil.copytree(package, local_package, ignore=shutil.ignore_patterns('.build', '.swiftpm'))
manifest = harness / 'Package.swift'
manifest.write_text(manifest.read_text().replace(str(package), str(local_package)))
print('Actual WorkflowStore fixture harness:', harness, flush=True)
raise SystemExit(subprocess.call(['swift', 'test', '--package-path', str(harness), '--scratch-path', '/tmp/swift-portal-workflow-store-build',
 '--cache-path', '/tmp/einundzwanzig-swiftpm-cache', '--skip-update', '-c', 'release', '--filter', 'PortalWorkflowStoreParityTests', *sys.argv[1:]]))
