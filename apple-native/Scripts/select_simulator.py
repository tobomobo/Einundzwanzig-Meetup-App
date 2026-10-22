#!/usr/bin/env python3
"""Select an available iPhone on an installed iOS 26+ simulator runtime."""
import json
import re
import subprocess
import sys

available = json.loads(subprocess.check_output(["xcrun", "simctl", "list", "devices", "available", "--json"], text=True))
candidates = []
for runtime, devices in available["devices"].items():
    match = re.search(r"iOS-(\d+)-(\d+)(?:-(\d+))?", runtime)
    if not match or int(match[1]) < 26:
        continue
    version = tuple(int(part or 0) for part in match.groups())
    for device in devices:
        if device.get("isAvailable") and device["name"].startswith("iPhone"):
            candidates.append((version, device["name"], device["udid"]))
if not candidates:
    sys.exit("No available iPhone with an iOS 26+ runtime; install a matching simulator before running app smoke tests.")
print(max(candidates)[2])
