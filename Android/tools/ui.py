#!/usr/bin/env python3
"""Drives the app on the emulator through adb.

  ui.py list [filter]        every text / description on screen with its centre
  ui.py tap <text>           taps the first element whose text or description contains <text> (case-insensitive)
  ui.py has <text>           exit 0 if such an element is on screen
  ui.py shot <file.png>      saves a screenshot
"""
import os, re, subprocess, sys, xml.etree.ElementTree as ET

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")

def adb(*args, binary=False):
    out = subprocess.run([ADB, *args], capture_output=True, timeout=60).stdout
    return out if binary else out.decode("utf-8", "replace")

def nodes():
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        text = adb("shell", "cat", "/sdcard/ui.xml")
        if text.strip().startswith("<?xml"):
            break
    else:
        return []
    found = []
    for node in ET.fromstring(text).iter("node"):
        label = node.get("text") or node.get("content-desc") or ""
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        found.append({"label": label, "desc": node.get("content-desc") or "", "text": node.get("text") or "",
                      "x": (x1 + x2) // 2, "y": (y1 + y2) // 2, "w": x2 - x1, "h": y2 - y1,
                      "clickable": node.get("clickable") == "true", "id": node.get("resource-id") or ""})
    return found

def find(needle):
    needle = needle.lower()
    exact = [n for n in nodes() if n["label"].lower() == needle or n["id"].lower() == needle]
    if exact:
        return exact[0]
    for n in nodes():
        if needle in n["label"].lower() or needle in n["id"].lower():
            return n
    return None

command = sys.argv[1] if len(sys.argv) > 1 else "list"
if command == "list":
    wanted = sys.argv[2].lower() if len(sys.argv) > 2 else ""
    for n in nodes():
        if (n["label"] or n["id"]) and wanted in (n["label"] + n["id"]).lower():
            print(f'{n["x"]:5d},{n["y"]:5d}  {"*" if n["clickable"] else " "} {n["label"][:70]!r} {n["id"]}')
elif command == "tap":
    n = find(sys.argv[2])
    if not n:
        print("not found:", sys.argv[2]); sys.exit(1)
    adb("shell", "input", "tap", str(n["x"]), str(n["y"]))
    print("tapped", repr(n["label"] or n["id"]), "at", n["x"], n["y"])
elif command == "has":
    sys.exit(0 if find(sys.argv[2]) else 1)
elif command == "shot":
    open(sys.argv[2], "wb").write(adb("exec-out", "screencap", "-p", binary=True))
