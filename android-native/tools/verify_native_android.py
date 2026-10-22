#!/usr/bin/env python3
"""Fail-closed validation of a built native APK before uploading any release artifacts.

This tool never signs, installs, publishes or contacts a server. The release certificate pin
must come from the official Flutter APK (RELEASE_CERT_SHA256), never from the APK being checked.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile


ANDROID = "{http://schemas.android.com/apk/res/android}"
APP = "space.einundzwanzig.meetup"

# Exact component type/name -> required permission. No wildcard package exemptions.
# Media3 deliberately exports PlebRap for lock-screen/Bluetooth/Auto/Wear controls;
# PlebrapPlaybackService.ConnectionGate rejects untrusted external controllers.
EXPORTED_COMPONENTS = {
    ("activity", APP + ".MainActivity"): None,
    ("service", APP + ".data.community.plebrap.PlebrapPlaybackService"): None,
    ("service", "androidx.glance.appwidget.GlanceRemoteViewsService"): "android.permission.BIND_REMOTEVIEWS",
    ("activity", "androidx.media3.session.BluetoothValidationActivity"): "android.permission.BLUETOOTH_PRIVILEGED",
    ("service", "androidx.work.impl.background.systemjob.SystemJobService"): "android.permission.BIND_JOB_SERVICE",
    ("receiver", "androidx.work.impl.diagnostics.DiagnosticsReceiver"): "android.permission.DUMP",
    ("service", "com.google.android.gms.auth.api.signin.RevocationBoundService"):
        "com.google.android.gms.auth.api.signin.permission.REVOCATION_NOTIFICATION",
    ("receiver", "androidx.profileinstaller.ProfileInstallReceiver"): "android.permission.DUMP",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def component_name(package, name):
    if name.startswith("."):
        return package + name
    return name if "." in name else package + "." + name


def verify_manifest(path, package):
    root = ET.parse(path).getroot()
    require(root.get("package") == package, "Merged manifest package does not match APK")
    app = root.find("application")
    require(app is not None, "Missing application")
    require(app.get(ANDROID + "allowBackup") == "false", "Application backup must stay disabled")
    require(app.get(ANDROID + "debuggable", "false") == "false", "Distribution APK must not be debuggable")
    components = {}
    for node in app:
        if node.tag not in {"activity", "activity-alias", "service", "receiver", "provider"}:
            continue
        name = component_name(package, node.get(ANDROID + "name", ""))
        key = (node.tag, name)
        require(key not in components, "Duplicate component: " + name)
        components[key] = node
        exported = node.get(ANDROID + "exported")
        require(exported in {None, "true", "false"}, "Unresolved exported value: " + name)
        # An intent-filter without explicit exported is unsafe even if the build tools accepted it.
        if exported is None and node.find("intent-filter") is not None:
            raise ValueError("Component with intent-filter lacks explicit exported: " + name)
        if exported != "true":
            continue
        require(key in EXPORTED_COMPONENTS, "Unexpected exported component: " + str(key))
        permission = node.get(ANDROID + "permission", app.get(ANDROID + "permission"))
        require(permission == EXPORTED_COMPONENTS[key], "Wrong permission on exported component: " + name)

    for kind, name, exported in [
        ("activity", APP + ".MainActivity", "true"),
        ("activity", APP + ".WidgetRouterActivity", "false"),
        ("receiver", APP + ".MeetupWidgetProvider", "false"),
        ("service", APP + ".data.community.plebrap.PlebrapPlaybackService", "true"),
    ]:
        node = components.get((kind, name))
        require(node is not None and node.get(ANDROID + "exported") == exported,
                "Missing component or wrong exported flag: " + name)
    media = components[("service", APP + ".data.community.plebrap.PlebrapPlaybackService")]
    require(media.get(ANDROID + "foregroundServiceType") == "mediaPlayback", "PlebRap must be a mediaPlayback service")
    actions = {n.get(ANDROID + "name") for n in media.findall("intent-filter/action")}
    require(actions == {"androidx.media3.session.MediaSessionService"}, "Unexpected PlebRap service actions")


def normalize_pin(pin):
    pin = re.sub(r"[:\s]", "", pin).lower()
    require(re.fullmatch(r"[a-f0-9]{64}", pin) is not None, "RELEASE_CERT_SHA256 must be a SHA-256 certificate fingerprint")
    return pin


def verify_certificate(output, expected_pin=None, release=True):
    # SDK 37 uses "V2 Signer:" / "V3 Signer:"; older SDKs use "Signer #1".
    # Multiple scheme reports must all identify the same certificate.
    counts = re.findall(r"^Number of signers:\s*(\d+)\s*$", output, re.MULTILINE)
    require(not counts or counts == ["1"], "Expected exactly one APK signer certificate")
    ids = set(re.findall(r"^Signer #(\d+) certificate SHA-256 digest:", output, re.MULTILINE))
    require(not ids or ids == {"1"}, "Expected exactly one APK signer certificate")
    fingerprints = set(re.findall(
        r"^(?:Signer #\d+|V\d+(?:\.\d+)? Signer(?: #\d+)?:) certificate SHA-256 digest:\s*([a-fA-F0-9]+)\s*$",
        output, re.MULTILINE,
    ))
    require(len(fingerprints) == 1, "Expected exactly one APK signer certificate")
    fingerprint = normalize_pin(next(iter(fingerprints)))
    if release:
        require(expected_pin is not None, "RELEASE_CERT_SHA256 is required for release validation")
        require(fingerprint == normalize_pin(expected_pin), "APK certificate does not match the pinned Flutter release certificate")
        for scheme in (2, 3):
            require(re.search(rf"^Verified using v{scheme} scheme .*: true$", output, re.MULTILINE) is not None,
                    f"Release APK must verify with signature scheme v{scheme}")
    return fingerprint


def verify_badging(output, variant):
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", output, re.MULTILINE)
    require(match is not None, "Cannot read APK package/version metadata")
    package, code, version = match.groups()
    expected_package = APP if variant == "release" else APP + ".preview"
    require(package == expected_package, "Unexpected APK applicationId: " + package)
    suffix = "" if variant == "release" else "-preview"
    semver = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)" + re.escape(suffix), version)
    require(semver is not None, "Unexpected versionName: " + version)
    major, minor, patch = map(int, semver.groups())
    require(minor < 100 and patch < 100, "Version components exceed versionCode scheme")
    require(int(code) == major * 10_000 + minor * 100 + patch and int(code) > 4031,
            "versionCode must use the native scheme and exceed all Flutter split-ABI codes")
    require("application-debuggable" not in output, "Distribution APK must not be debuggable")
    require(re.search(r"^(?:sdkVersion|minSdkVersion):'26'$", output, re.MULTILINE) is not None,
            "Expected minSdk 26")
    return package, version


def verify_abis(apk):
    with zipfile.ZipFile(apk) as archive:
        abis = {p.split("/")[1] for p in archive.namelist() if p.startswith("lib/") and p.endswith(".so")}
    require(abis == {"armeabi-v7a", "arm64-v8a", "x86_64"}, "Expected one universal APK with the three Flutter ABIs")


def run_tool(path, *args):
    result = subprocess.run([str(path), *map(str, args)], check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True, help="Matching AGP merged manifest XML")
    parser.add_argument("--mapping", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--variant", choices=("release", "preview"), required=True)
    parser.add_argument("--output-dir", type=Path)
    args = parser.parse_args()
    require(args.apk.is_file(), "Missing APK")
    require(args.mapping.is_file() and args.mapping.stat().st_size > 0, "Missing R8 mapping.txt")
    # Reject a missing pin before invoking any expensive build-tool verification.
    pin = os.environ.get("RELEASE_CERT_SHA256") if args.variant == "release" else None
    if args.variant == "release":
        normalize_pin(pin or "")
    package, version = verify_badging(run_tool(args.build_tools / "aapt2", "dump", "badging", args.apk), args.variant)
    verify_manifest(args.manifest, package)
    cert = verify_certificate(
        run_tool(args.build_tools / "apksigner", "verify", "--verbose", "--print-certs", args.apk),
        pin, release=args.variant == "release",
    )
    verify_abis(args.apk)
    run_tool(args.build_tools / "zipalign", "-c", "-P", "16", "-v", "4", args.apk)
    if args.output_dir:
        args.output_dir.mkdir(parents=True, exist_ok=True)
        date = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        name = (f"einundzwanzig-meetup-v{version}-{date}.apk" if args.variant == "release"
                else f"einundzwanzig-native-{version}.{os.environ.get('GITHUB_RUN_NUMBER', 'local')}.apk")
        target = args.output_dir / name
        shutil.copyfile(args.apk, target)
        mapping = args.output_dir / "mapping.txt"
        shutil.copyfile(args.mapping, mapping)
        sums = "".join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n" for p in (target, mapping))
        (args.output_dir / "SHA256SUMS.txt").write_text(sums)
    print(f"Validated {package} {version}: certificate {cert}, 16 KB ZIP alignment, universal ABIs, R8 mapping and exported allow-list")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, ET.ParseError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print("Native APK validation failed: " + str(error), file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError) and error.output:
            print(error.output, file=sys.stderr)
        sys.exit(1)
