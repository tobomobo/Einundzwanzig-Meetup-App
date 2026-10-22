#!/usr/bin/env python3
"""ADB accessibility-based test helper. Records screenshots, XML and assertions in an artifact dir.

Example: python3 tools/device_e2e.py --serial emulator-5556 --out /tmp/native-e2e snapshot home
Use only a dedicated AVD: this tool refuses every AVD whose name does not start with e21test.
It never clears data, changes identities or enables the network implicitly.
"""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET


class Device:
    def __init__(self, serial, out, adb=None):
        self.adb = [adb or os.path.expanduser("~/Android/Sdk/platform-tools/adb"), "-s", serial]
        self.out = Path(out)
        self.out.mkdir(parents=True, exist_ok=True)
        name = self.command("emu", "avd", "name").decode().splitlines()[0].strip()
        if not name.startswith("e21test"):
            raise RuntimeError(f"Refusing non-test AVD {name!r}")

    def command(self, *args):
        return subprocess.run(self.adb + list(args), check=True, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, timeout=45).stdout

    def tree(self):
        # uiautomator can exit 0 on an idle timeout without writing a new file.
        # Never accept an earlier screen's XML as evidence for the current screen.
        output = ""
        for _ in range(3):
            self.command("shell", "rm", "-f", "/sdcard/e21-e2e.xml")
            output = self.command("shell", "uiautomator", "dump", "/sdcard/e21-e2e.xml").decode()
            if "dumped to: /sdcard/e21-e2e.xml" in output:
                xml = self.command("shell", "cat", "/sdcard/e21-e2e.xml")
                return xml, ET.fromstring(xml)
            time.sleep(0.3)
        raise RuntimeError(f"UI hierarchy was not refreshed: {output.strip()}")

    def snapshot(self, label):
        if not re.fullmatch(r"[a-zA-Z0-9_-]+", label):
            raise ValueError("Use a simple artifact label")
        xml, root = self.tree()
        (self.out / f"{label}.xml").write_bytes(xml)
        (self.out / f"{label}.png").write_bytes(self.command("exec-out", "screencap", "-p"))
        texts = [n.get("text") or n.get("content-desc") for n in root.iter("node")]
        texts = [t for t in texts if t]
        print(json.dumps({"label": label, "texts": texts}, ensure_ascii=False))
        return root

    def find(self, text, timeout=15, contains=False):
        deadline = time.monotonic() + timeout
        while True:
            _, root = self.tree()
            for node in root.iter("node"):
                values = (node.get("text", ""), node.get("content-desc", ""), node.get("hint", ""))
                if any(text in v if contains else text == v for v in values):
                    return node
            if time.monotonic() >= deadline:
                raise AssertionError(f"Visible text/description not found: {text!r}")
            time.sleep(0.3)

    def tap(self, text, contains=False):
        node = self.find(text, contains=contains)
        bounds = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
        if len(bounds) != 4 or bounds[0] == bounds[2] or bounds[1] == bounds[3]:
            raise AssertionError(f"No visible bounds for {text!r}")
        self.command("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2),
                     str((bounds[1] + bounds[3]) // 2))

    def expect(self, text, contains=False):
        self.find(text, contains=contains)
        record = {"time": time.time(), "assertion": text, "passed": True}
        with (self.out / "assertions.jsonl").open("a") as file:
            file.write(json.dumps(record, ensure_ascii=False) + "\n")
        print(json.dumps(record, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5556")
    parser.add_argument("--out", default="/tmp/native-e2e")
    parser.add_argument("--contains", action="store_true")
    parser.add_argument("action", choices=["snapshot", "tap", "expect", "input", "back", "swipe"])
    parser.add_argument("value", nargs="?")
    args = parser.parse_args()
    device = Device(args.serial, args.out)
    if args.action == "snapshot":
        device.snapshot(args.value)
    elif args.action in ("tap", "expect"):
        getattr(device, args.action)(args.value, contains=args.contains)
    elif args.action == "input":
        device.command("shell", "input", "text", args.value.replace(" ", "%s"))
    elif args.action == "back":
        device.command("shell", "input", "keyevent", "4")
    elif args.action == "swipe":
        coords = (args.value or "460,1500,460,500,350").split(",")
        device.command("shell", "input", "swipe", *coords)


if __name__ == "__main__":
    main()
