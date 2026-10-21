#!/usr/bin/env python3
"""Syntax-only Swift 6 parsing for every Apple conditional branch; needs no SDK."""
import argparse
import pathlib
import shutil
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--swiftc", default=shutil.which("swiftc") or "swiftc")
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[1]
sources = sorted(str(p) for p in (root / "App").rglob("*.swift"))
for target in ("arm64-apple-ios26.0", "arm64-apple-ios26.0-simulator", "arm64-apple-macosx26.0"):
    subprocess.run([args.swiftc, "-frontend", "-parse", "-swift-version", "6", "-target", target, *sources], check=True)
    print(f"PASS Swift syntax: {target} · {len(sources)} files (no SDK typecheck)")
