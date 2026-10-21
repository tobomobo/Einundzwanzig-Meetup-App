#!/usr/bin/env python3
"""Build portable core, then test the actual app's non-UI import/local-state models."""
import argparse
import pathlib
import shutil
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--scratch-path", help="SwiftPM build cache directory")
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[1]
swift = shutil.which("swift") or "swift"
swiftc = shutil.which("swiftc") or "swiftc"
build = [swift, "build", "--package-path", str(root / "Packages/MeetupCore")]
if args.scratch_path:
    build += ["--scratch-path", args.scratch_path]
subprocess.run(build + ["-Xswiftc", "-warnings-as-errors"], check=True)
binary_dir = pathlib.Path(subprocess.check_output(build + ["--show-bin-path"], text=True).strip())
objects = sorted(str(p) for p in (binary_dir / "MeetupCore.build").glob("*.swift.o"))
if not objects:
    raise SystemExit(f"No MeetupCore objects in {binary_dir}; cannot link real app model tests")
with tempfile.TemporaryDirectory(prefix="einundzwanzig-model-tests-") as temporary:
    binary = str(pathlib.Path(temporary) / "model-tests")
    subprocess.run([swiftc, "-swift-version", "6", "-warnings-as-errors", "-D", "LOCAL_MODEL_TESTS",
                    "-I", str(binary_dir / "Modules"), str(root / "App/Models/LocalModels.swift"),
                    str(root / "App/Models/AppLocalization.swift"), str(root / "Tests/LocalModelTests.swift"), *objects, "-o", binary], check=True)
    subprocess.run([binary, str(root / "Resources")], check=True)
