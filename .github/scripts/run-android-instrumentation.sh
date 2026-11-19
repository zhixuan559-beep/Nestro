#!/usr/bin/env bash
set -uo pipefail

DIAG="app/build/reports/androidTests/connected/diagnostics"
mkdir -p "$DIAG"

adb wait-for-device
booted=""
for attempt in $(seq 1 300); do
  booted="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  [[ "$booted" == "1" ]] && break
  sleep 2
done
printf 'sys.boot_completed=%s\n' "${booted:-unavailable}" > "$DIAG/device-boot.txt"
adb shell getprop > "$DIAG/device-properties.txt" 2>&1 || true
adb shell pm list instrumentation -f > "$DIAG/instrumentation-list.txt" 2>&1 || true
adb shell pm list packages -3 > "$DIAG/third-party-packages.txt" 2>&1 || true

if [[ "$booted" != "1" ]]; then
  echo "Emulator did not finish booting within 600 seconds." > "$DIAG/diagnostic-error.txt"
  exit 2
fi

adb logcat -c || true
adb logcat -b all -v threadtime > "$DIAG/emulator-logcat.txt" 2>&1 &
LOGCAT_PID=$!
cleanup() {
  kill "$LOGCAT_PID" 2>/dev/null || true
  wait "$LOGCAT_PID" 2>/dev/null || true
}
trap cleanup EXIT

# Build APKs, then invoke AndroidJUnitRunner directly. This bypasses AGP's
# UTP driver, whose additionalTestOutputDir is inaccessible on this emulator.
if ! gradle --no-daemon --stacktrace assembleDebug assembleDebugAndroidTest; then
  exit 1
fi

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

set +e
timeout 1500 adb shell am instrument -w -r app.nestro.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$DIAG/instrumentation-run.txt"
instrumentation_exit=${PIPESTATUS[0]}
set -e

adb shell dumpsys activity exit-info app.nestro > "$DIAG/app-exit-info.txt" 2>&1 || true
adb shell dumpsys activity exit-info app.nestro.test > "$DIAG/test-exit-info.txt" 2>&1 || true
adb shell dumpsys package app.nestro.test > "$DIAG/test-package.txt" 2>&1 || true
adb shell dumpsys activity lastanr > "$DIAG/last-anr.txt" 2>&1 || true
adb shell pm list instrumentation -f > "$DIAG/instrumentation-list-after.txt" 2>&1 || true
adb logcat -d -b all -v threadtime > "$DIAG/emulator-logcat-final.txt" 2>&1 || true

if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|Error:' "$DIAG/instrumentation-run.txt"; then
  exit 1
fi
if [[ "$instrumentation_exit" -ne 0 ]] || ! grep -q 'OK (' "$DIAG/instrumentation-run.txt"; then
  exit 1
fi
