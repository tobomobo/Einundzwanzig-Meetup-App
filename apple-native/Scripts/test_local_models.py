#!/usr/bin/env python3
"""Build portable core, then test the actual app's non-UI import/local-state models."""
import argparse
import pathlib
import shutil
import subprocess
import tempfile
import os
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--scratch-path", help="SwiftPM build cache directory")
parser.add_argument("--source-only", action="store_true", help="Compile the dependency-free MeetupCore source directly; no package/plugin resolution")
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[1]
swift = shutil.which("swift") or "swift"
swiftc = shutil.which("swiftc") or "swiftc"
with tempfile.TemporaryDirectory(prefix="einundzwanzig-model-tests-") as temporary:
    environment = os.environ.copy()
    if args.source_only:
        binary_dir = pathlib.Path(temporary)
        suffix = "dylib" if sys.platform == "darwin" else "so"
        library = str(binary_dir / f"libMeetupCore.{suffix}")
        sources = sorted(str(path) for path in (root / "Packages/MeetupCore/Sources/MeetupCore").glob("*.swift"))
        subprocess.run([swiftc, "-swift-version", "6", "-warnings-as-errors", "-emit-module", "-emit-library", "-module-name", "MeetupCore", *sources, "-o", library], check=True)
        module_dir = binary_dir
        link = ["-L", str(binary_dir), "-lMeetupCore"]
        variable = "DYLD_LIBRARY_PATH" if sys.platform == "darwin" else "LD_LIBRARY_PATH"
        environment[variable] = str(binary_dir) + ":" + environment.get(variable, "")
    else:
        build = [swift, "build", "--package-path", str(root / "Packages/MeetupCore"), "--force-resolved-versions", "--target", "MeetupCore"]
        if args.scratch_path:
            cache_parent = pathlib.Path(args.scratch_path).resolve().parent
            build += ["--scratch-path", args.scratch_path, "--cache-path", str(cache_parent / "einundzwanzig-model-spm-cache"), "--config-path", str(cache_parent / "einundzwanzig-model-spm-config"), "--security-path", str(cache_parent / "einundzwanzig-model-spm-security")]
        subprocess.run(build + ["-Xswiftc", "-warnings-as-errors"], check=True)
        binary_dir = pathlib.Path(subprocess.check_output(build + ["--show-bin-path"], text=True).strip())
        link = sorted(str(p) for p in (binary_dir / "MeetupCore.build").glob("*.swift.o"))
        if not link:
            raise SystemExit(f"No MeetupCore objects in {binary_dir}; cannot link real app model tests")
        module_dir = binary_dir / "Modules"
    binary = str(pathlib.Path(temporary) / "model-tests")
    subprocess.run([swiftc, "-swift-version", "6", "-warnings-as-errors", "-D", "LOCAL_MODEL_TESTS",
                    "-I", str(module_dir), str(root / "App/Models/LocalModels.swift"),
                    str(root / "App/Models/AppLocalization.swift"), str(root / "App/Models/EventFormatting.swift"),
                    str(root / "App/LearningStore.swift"), str(root / "Tests/LocalModelTests.swift"), *link, "-o", binary], check=True)
    subprocess.run([binary, str(root / "Resources")], check=True, env=environment)
