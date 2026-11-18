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

test_exit_code=0
if gradle --no-daemon --stacktrace connectedDebugAndroidTest; then
  test_exit_code=0
else
  test_exit_code=$?
fi

adb shell dumpsys activity exit-info app.nestro > "$DIAG/app-exit-info.txt" 2>&1 || true
adb shell dumpsys activity exit-info app.nestro.test > "$DIAG/test-exit-info.txt" 2>&1 || true
adb shell dumpsys package app.nestro.test > "$DIAG/test-package.txt" 2>&1 || true
adb shell pm list instrumentation -f > "$DIAG/instrumentation-list-after.txt" 2>&1 || true
adb logcat -d -b all -v threadtime > "$DIAG/emulator-logcat-final.txt" 2>&1 || true
exit "$test_exit_code"
