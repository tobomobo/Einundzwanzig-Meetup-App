#!/usr/bin/env python3
"""Erzeugt App-Icons aus assets/app_icon.png mit sips oder ImageMagick.

Die PNGs sind eingecheckt. Dieses Skript wird nur bei einem Icon-Wechsel benoetigt.
Auf macOS genuegt das vorinstallierte sips; auf Linux wird magick verwendet.
"""

import json
from pathlib import Path
import shutil
import subprocess


APPLE_ROOT = Path(__file__).resolve().parents[1]
SOURCE = APPLE_ROOT.parent / "assets" / "app_icon.png"
CATALOG = APPLE_ROOT / "Resources" / "Assets.xcassets"


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def main():
    if not SOURCE.is_file():
        raise SystemExit(f"Icon-Vorlage fehlt: {SOURCE}")
    sips = shutil.which("sips")
    magick = shutil.which("magick")
    if not (sips or magick):
        raise SystemExit("sips (macOS) oder ImageMagick/magick wird benoetigt.")
    icon_set = CATALOG / "AppIcon.appiconset"
    icon_set.mkdir(parents=True, exist_ok=True)
    for pixels in (16, 32, 64, 128, 256, 512, 1024):
        destination = icon_set / f"AppIcon-{pixels}.png"
        if sips:
            subprocess.run(
                [sips, "-s", "format", "png", "-z", str(pixels), str(pixels),
                 str(SOURCE), "--out", str(destination)],
                check=True, stdout=subprocess.DEVNULL,
            )
        else:
            subprocess.run(
                [magick, str(SOURCE), "-resize", f"{pixels}x{pixels}!",
                 "-alpha", "off", "-strip", f"PNG24:{destination}"],
                check=True,
            )
    images = [{"filename": "AppIcon-1024.png", "idiom": "universal",
               "platform": "ios", "size": "1024x1024"}]
    for points in (16, 32, 128, 256, 512):
        for scale in (1, 2):
            images.append({"filename": f"AppIcon-{points * scale}.png",
                           "idiom": "mac", "scale": f"{scale}x",
                           "size": f"{points}x{points}"})
    info = {"author": "xcode", "version": 1}
    write_json(icon_set / "Contents.json", {"images": images, "info": info})
    write_json(CATALOG / "Contents.json", {"info": info})
    logo_set = CATALOG / "MeetupLogo.imageset"
    logo_set.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(SOURCE, logo_set / "logo.png")
    write_json(logo_set / "Contents.json", {
        "images": [{"filename": "logo.png", "idiom": "universal"}], "info": info,
    })
    write_json(CATALOG / "AccentColor.colorset" / "Contents.json", {
        "colors": [{"color": {"color-space": "srgb", "components": {
            "alpha": "1.000", "red": "0.969", "green": "0.576", "blue": "0.102",
        }}, "idiom": "universal"}], "info": info,
    })
    print(f"AppIcon, MeetupLogo und AccentColor erzeugt: {CATALOG}")


if __name__ == "__main__":
    main()
