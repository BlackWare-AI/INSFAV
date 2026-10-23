#!/usr/bin/env bash
set -euo pipefail
mkdir -p emulator-ui
apk=$(find builds -name app-debug.apk -print -quit)
test_apk=$(find builds -name '*debug-androidTest.apk' -print -quit)
adb install -r "$apk"
adb install -r "$test_apk"
adb shell pm grant com.instagram.insfav android.permission.POST_NOTIFICATIONS
adb logcat -c
adb shell am instrument -w -e scope ui-refinement com.instagram.insfav.test/com.instagram.insfav.fetcher.PoolInstrumentation | tee emulator-ui/instrumentation.txt
grep -q REFINEMENT_UI_OK emulator-ui/instrumentation.txt
grep -q SCROLL_UI_OK emulator-ui/instrumentation.txt
for name in scroll-ui.json scroll-device-150.png matrix-device-150.png preference-device-150.png; do
  adb exec-out run-as com.instagram.insfav cat "files/$name" > "emulator-ui/$name"
done
adb logcat -b crash -d > emulator-ui/crash.txt
if grep -F 'Process: com.instagram.insfav' emulator-ui/crash.txt; then exit 1; fi
