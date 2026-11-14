#!/usr/bin/env python3
"""A random walk through the app on the emulator: taps things, swipes, goes back, and watches for crashes.

  walk.py <steps> [seed]

It never opens the sign-in page (nothing may be typed into Google's form) and never types text. It stops at the
first crash or "not responding", and prints what it did last.
"""
import os, random, re, subprocess, sys, time, xml.etree.ElementTree as ET

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PACKAGE = "io.github.boomboom.android"
AVOID = re.compile(r"sign in|sign-in|add account|add another|google|report a problem|share|sign out|remove account", re.I)

def adb(*args):
    return subprocess.run([ADB, *args], capture_output=True, timeout=90).stdout.decode("utf-8", "replace")

def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    text = adb("shell", "cat", "/sdcard/ui.xml")
    if not text.strip().startswith("<?xml"):
        return [], ""
    found, package = [], ""
    for node in ET.fromstring(text).iter("node"):
        package = package or node.get("package", "")
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        if x2 - x1 < 20 or y2 - y1 < 20 or y1 < 60:
            continue
        label = node.get("text") or node.get("content-desc") or ""
        found.append({"label": label, "x": (x1 + x2) // 2, "y": (y1 + y2) // 2, "clickable": node.get("clickable") == "true",
                      "package": node.get("package", "")})
    return found, package

def crashed():
    log = adb("logcat", "-d", "-s", "AndroidRuntime:E", "ActivityManager:E")
    hit = [l for l in log.splitlines() if "FATAL EXCEPTION" in l or (PACKAGE in l and ("ANR in" in l or "has died" in l))]
    return hit

def foreground():
    out = adb("shell", "dumpsys", "activity", "activities")
    m = re.search(r"(?:mResumedActivity|topResumedActivity|ResumedActivity)[^\n]*? ([\w.]+)/([\w.$]+)", out)
    return (m.group(1), m.group(2)) if m else ("?", "?")

steps = int(sys.argv[1]) if len(sys.argv) > 1 else 60
random.seed(int(sys.argv[2]) if len(sys.argv) > 2 else 7)
adb("logcat", "-c")
history = []
counts = {"tap": 0, "swipe": 0, "back": 0, "home-and-back": 0, "skipped": 0, "relaunch": 0}
for step in range(steps):
    package, activity = foreground()
    if package != PACKAGE:
        # Outside the app (a share sheet, the launcher): come back.
        adb("shell", "am", "start", "-n", f"{PACKAGE}/io.github.boomboom.MainActivity")
        counts["relaunch"] += 1
        time.sleep(1.5)
        continue
    if "SignIn" in activity:
        adb("shell", "input", "keyevent", "4")
        history.append("left the sign-in page")
        time.sleep(1)
        continue
    found, _ = nodes()
    roll = random.random()
    if roll < 0.62 and found:
        choices = [n for n in found if n["package"] == PACKAGE and (n["clickable"] or n["label"])]
        choices = [n for n in choices if not AVOID.search(n["label"])]
        if not choices:
            counts["skipped"] += 1
            continue
        n = random.choice(choices)
        adb("shell", "input", "tap", str(n["x"]), str(n["y"]))
        history.append(f"tap {n['label'][:40]!r} at {n['x']},{n['y']}")
        counts["tap"] += 1
    elif roll < 0.82:
        x1, y1 = random.randint(150, 930), random.randint(500, 2000)
        dx, dy = random.choice([(0, -700), (0, 700), (-600, 0), (600, 0), (0, -300), (300, 0)])
        x2, y2 = min(1050, max(30, x1 + dx)), min(2250, max(200, y1 + dy))
        adb("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(random.choice([120, 300, 600])))
        history.append(f"swipe {x1},{y1} -> {x2},{y2}")
        counts["swipe"] += 1
    elif roll < 0.95:
        adb("shell", "input", "keyevent", "4")
        history.append("back")
        counts["back"] += 1
    else:
        adb("shell", "input", "keyevent", "3")
        time.sleep(1.2)
        adb("shell", "am", "start", "-n", f"{PACKAGE}/io.github.boomboom.MainActivity")
        history.append("home, then back to the app")
        counts["home-and-back"] += 1
    time.sleep(random.choice([1.0, 1.8, 3.0]))
    bad = crashed()
    if bad:
        print("CRASH after step", step)
        print("\n".join(bad[:6]))
        print("last actions:", *history[-8:], sep="\n  ")
        sys.exit(2)
alive = bool(adb("shell", "pidof", PACKAGE).strip())
print(f"walk finished: {steps} steps, {counts}, app process alive: {alive}")
print("last actions:", *history[-5:], sep="\n  ")
