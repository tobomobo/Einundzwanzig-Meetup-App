#!/usr/bin/env python3
"""Compile and test the real portable PlebRap controller/cache without an Apple SDK."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--scratch-path', help='Independent parent directory for temporary build artifacts')
args = parser.parse_args()
apple = Path(__file__).resolve().parents[3]
swiftc = shutil.which('swiftc') or 'swiftc'
if args.scratch_path:
    Path(args.scratch_path).mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='plebrap-parity-', dir=args.scratch_path) as temporary:
    build = Path(temporary)
    source = build / 'source'
    source.mkdir()
    # Snapshot only the needed sources so unrelated workers cannot invalidate a build.
    core = source / 'MeetupCore'
    shutil.copytree(apple / 'Packages/MeetupCore/Sources/MeetupCore', core)
    inputs = [apple / 'App/Models/PlebRapCatalog.swift', apple / 'App/Platform/NativeMediaPlayer.swift',
              apple / 'App/Platform/Tests/PlebRapJourneyParityTests.swift']
    for path in inputs:
        shutil.copy2(path, source / path.name)
    suffix = 'dylib' if sys.platform == 'darwin' else 'so'
    library = build / ('libMeetupCore.' + suffix)
    common = [swiftc, '-swift-version', '6', '-warnings-as-errors']
    subprocess.run(common + ['-emit-module', '-emit-library', '-module-name', 'MeetupCore',
                   *map(str, sorted(core.glob('*.swift'))), '-o', str(library)], check=True)
    binary = build / 'plebrap-parity-tests'
    subprocess.run(common + ['-D', 'PLEBRAP_JOURNEY_TESTS', '-I', str(build), '-L', str(build), '-lMeetupCore',
                   *[str(source / path.name) for path in inputs], '-o', str(binary)], check=True)
    environment = os.environ.copy()
    key = 'DYLD_LIBRARY_PATH' if sys.platform == 'darwin' else 'LD_LIBRARY_PATH'
    environment[key] = str(build) + ':' + environment.get(key, '')
    subprocess.run([str(binary)], check=True, env=environment)
