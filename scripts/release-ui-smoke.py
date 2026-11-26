#!/usr/bin/env python3
"""Smoke-test the actual optimized signed APK through its public UI."""
import pathlib,re,subprocess,time,xml.etree.ElementTree as ET
out=pathlib.Path("release-smoke");out.mkdir(exist_ok=True)
def adb(*args):return subprocess.check_output(["adb",*args],timeout=30)
def nodes():
    adb("shell","uiautomator","dump","/sdcard/mawja-release-ui.xml")
    return list(ET.fromstring(adb("exec-out","cat","/sdcard/mawja-release-ui.xml")).iter("node"))
def bounds(n):return list(map(int,re.findall(r"\d+",n.get("bounds",""))))
def find(label,tap=False,attempts=6):
    for _ in range(attempts):
        for n in nodes():
            if label in (n.get("text"),n.get("content-desc")):
                if tap:
                    x1,y1,x2,y2=bounds(n)
                    adb("shell","input","tap",str((x1+x2)//2),str((y1+y2)//2));time.sleep(.5)
                return n
        time.sleep(.5)
    raise AssertionError("Missing release UI: "+label)
try:
    subprocess.run(["python","scripts/ui-refinement-smoke.py"],check=True)
    world=find("World");_,y1,_,y2=bounds(world);y=(y1+y2)//2
    size=adb("shell","wm","size").decode();width=int(re.search(r"(\d+)x\d+",size)[1])
    for _ in range(8):
        matches=[n for n in nodes() if n.get("text")=="TV channel audio"]
        if matches:break
        adb("shell","input","swipe",str(width*9//10),str(y),str(width//10),str(y),"350");time.sleep(.3)
    find("TV channel audio",tap=True)
    find("فرانس 24 عربي",tap=True)
    deadline=time.monotonic()+35
    sessions=""
    while time.monotonic()<deadline:
        sessions=adb("shell","dumpsys","media_session").decode(errors="replace")
        if "com.mawja.player" in sessions and "فرانس 24 عربي" in sessions and re.search(r"state=3\b",sessions):break
        time.sleep(1)
    else:raise AssertionError("Signed release never reached playing state")
    (out/"media-session.txt").write_text(sessions)
    crashes=adb("logcat","-d","-b","crash").decode(errors="replace")
    (out/"crashes.txt").write_text(crashes)
    assert "FATAL EXCEPTION" not in crashes,"Release crashed"
    (out/"result.txt").write_text("PASS: optimized signed APK settings, theme, language, rotation, radio countries, TV catalog and live France24 audio through public UI. No car head unit used.\n")
finally:
    (out/"screen.png").write_bytes(adb("exec-out","screencap","-p"))
