#!/usr/bin/env python3
"""Device-side navigation regression smoke test; no third-party Python packages."""
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

OUT = pathlib.Path("runtime-report")
OUT.mkdir(exist_ok=True)

def adb(*args):
    return subprocess.check_output(["adb", *args], timeout=30)

def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/mawja-ui.xml")
    return list(ET.fromstring(adb("exec-out", "cat", "/sdcard/mawja-ui.xml")).iter("node"))

def find(label, tap=False):
    for attempt in range(8):
        matches = [node for node in nodes() if label in (node.get("text"), node.get("content-desc"))]
        if matches:
            node = matches[0]
            if tap:
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
                adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
                time.sleep(.5)
            return
        time.sleep(.5)
    raise AssertionError("Missing UI element: " + label)

def snapshot(name):
    (OUT / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))

def back():
    adb("shell", "input", "keyevent", "4")
    time.sleep(.5)

try:
    adb("shell", "am", "force-stop", "com.mawja.player")
    adb("shell", "am", "start", "-W", "-n", "com.mawja.player/.MainActivity")
    source_labels = ["Device", "Audius", "YouTube"]
    def switch_state(label):
        matches = [n for n in nodes() if n.get("text") == label and n.get("checkable") == "true"]
        assert len(matches) == 1, "Missing independent home switch: " + label
        return matches[0].get("checked") == "true"
    assert all(switch_state(label) for label in source_labels)
    for index, label in enumerate(source_labels):
        find(label, tap=True)
        assert [switch_state(name) for name in source_labels] == [i > index for i in range(3)]
    disabled = "Enable Device, Audius or YouTube above to show content."
    find(disabled)
    adb("shell", "am", "force-stop", "com.mawja.player")
    adb("shell", "am", "start", "-W", "-n", "com.mawja.player/.MainActivity")
    find(disabled)
    assert not any(switch_state(label) for label in source_labels), "Source states lost after process restart"
    for label in source_labels:
        find(label, tap=True)
    assert all(switch_state(label) for label in source_labels)
    snapshot("home-independent-sources")
    find("Settings", tap=True)
    snapshot("settings-hub")
    for section, control in [("Appearance", "Language"), ("Device", "File display"), ("Online sources", "Show YouTube"), ("Playback", "Battery saver")]:
        find(section, tap=True)
        find(control)
        snapshot("settings-" + section.replace(" ", "-").lower())
        if section == "Appearance":
            find("Dark", tap=True)
            snapshot("appearance-dark")
            find("Language", tap=True)
            snapshot("language-dialog-dark")
            find("Close", tap=True)
            adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
            adb("shell", "settings", "put", "system", "user_rotation", "1")
            time.sleep(2)
            find("Language")
            adb("shell", "settings", "put", "system", "user_rotation", "0")
            time.sleep(2)
            find("Language")
        back()
        find("Appearance")
        find("Playback")
    back()
    find("Discover · Radio, podcasts and online", tap=True)
    find("Podcasts")
    snapshot("discover")
    find("Subscribe", tap=True)
    find("RSS · HTTPS")
    snapshot("rss-add-dialog")
    find("Cancel", tap=True)
    back()
    find("Settings")
    find("Radio", tap=True)
    find("Local", tap=True)
    find("Use my location")
    assert not any(node.get("text") == "All countries" for node in nodes()), "Local unexpectedly contains a country picker"
    snapshot("radio-local-location-only")
    find("World", tap=True)
    find("All countries", tap=True)
    snapshot("radio-world-country-dialog-dark")
    find("Close", tap=True)
    (OUT / "ui-smoke.txt").write_text("PASS: four settings pages, dark appearance/language dialog, back navigation, rotation restoration, discovery/RSS, location-only Local and World country picker. No subscribed episode playback tested.\n")
finally:
    snapshot("ui-final")
