#!/usr/bin/env python3
"""Waehlt das neueste installierte Xcode 26 fuer GitHub Actions aus.

Ohne GITHUB_ENV wird nur der DEVELOPER_DIR-Pfad ausgegeben. Lokal:
export DEVELOPER_DIR="$(python3 apple-native/Scripts/select_xcode.py)"
"""

import os
from pathlib import Path
import plistlib
import re
import subprocess
import sys


def xcode_version(developer_dir):
    info_path = developer_dir.parent / "Info.plist"
    try:
        with info_path.open("rb") as source:
            info = plistlib.load(source)
        version = info.get("CFBundleShortVersionString", "")
        # Preview/beta builds are intentionally not the default CI toolchain.
        if "beta" in developer_dir.as_posix().lower() or not re.fullmatch(r"26(?:\.\d+){0,2}", version):
            return None
        return tuple(int(part) for part in version.split(".")) + (0,) * (3 - len(version.split(".")))
    except (OSError, ValueError, plistlib.InvalidFileException):
        return None


def main():
    candidates = {path.resolve() / "Contents" / "Developer"
                  for path in Path("/Applications").glob("Xcode*.app")}
    if os.environ.get("DEVELOPER_DIR"):
        candidates.add(Path(os.environ["DEVELOPER_DIR"]).resolve())
    candidates = [(version, path) for path in candidates
                  if (version := xcode_version(path)) is not None]
    if not candidates:
        raise SystemExit("Kein installiertes Xcode 26 gefunden. Installiere Xcode 26 oder pruefe das macOS-Runner-Image.")
    _, developer_dir = max(candidates)
    environment = dict(os.environ, DEVELOPER_DIR=str(developer_dir))
    subprocess.run(["xcodebuild", "-version"], check=True, env=environment, stdout=sys.stderr)
    subprocess.run(["xcrun", "--sdk", "iphonesimulator", "--show-sdk-version"],
                   check=True, env=environment, stdout=sys.stderr)
    subprocess.run(["xcrun", "--sdk", "macosx", "--show-sdk-version"],
                   check=True, env=environment, stdout=sys.stderr)
    if os.environ.get("GITHUB_ENV"):
        with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as output:
            output.write(f"DEVELOPER_DIR={developer_dir}\n")
    print(developer_dir)


if __name__ == "__main__":
    main()
