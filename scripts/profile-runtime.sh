#!/usr/bin/env bash
set -euo pipefail
package=com.mawja.player
mkdir -p runtime-report
adb logcat -c
adb shell dumpsys batterystats --reset > runtime-report/battery-reset.txt
adb shell am start -W -n "$package/.MainActivity" > runtime-report/startup.txt
sleep 10
adb shell dumpsys meminfo "$package" > runtime-report/memory-start.txt
for iteration in $(seq 1 8); do
  adb shell input keyevent KEYCODE_HOME
  adb shell am start -W -n "$package/.MainActivity" >> runtime-report/resume.txt
  sleep 2
done
adb shell dumpsys meminfo "$package" > runtime-report/memory-resumed.txt
adb shell monkey -p "$package" --throttle 150 --pct-syskeys 0 -s 38 150 > runtime-report/monkey.txt 2>&1
adb shell input keyevent KEYCODE_HOME
sleep 30
adb shell dumpsys meminfo "$package" > runtime-report/memory-background.txt
adb shell dumpsys batterystats "$package" > runtime-report/batterystats-emulator.txt
adb logcat -d -b crash > runtime-report/crashes.txt
if grep -q 'FATAL EXCEPTION' runtime-report/crashes.txt; then exit 1; fi
