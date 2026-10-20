#!/usr/bin/env bash
set -euo pipefail
mkdir -p emulator-pool
apk=$(find builds -name app-debug.apk -print -quit)
test_apk=$(find builds -name '*debug-androidTest.apk' -print -quit)
adb install -r "$apk"
adb install -r "$test_apk"
adb logcat -c
adb shell am instrument -w com.instagram.insfav.test/com.instagram.insfav.fetcher.PoolInstrumentation | tee emulator-pool/instrumentation.txt
grep -q POOL_MATRIX_OK emulator-pool/instrumentation.txt
adb exec-out run-as com.instagram.insfav cat files/pool-matrix.json > emulator-pool/pool-matrix.json
adb exec-out run-as com.instagram.insfav cat files/background-fixture.json > emulator-pool/background-fixture.json
adb shell am start -W -n com.instagram.insfav/.MainActivity
sleep 4
adb shell dumpsys activity activities > emulator-pool/activity.txt
grep -E 'mResumedActivity.*com.instagram.insfav/.MainActivity' emulator-pool/activity.txt
adb logcat -b crash -d > emulator-pool/crash.txt
