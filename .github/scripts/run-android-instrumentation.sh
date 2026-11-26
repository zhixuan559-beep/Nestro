#!/usr/bin/env bash
set -uo pipefail

DIAG="app/build/reports/androidTests/connected/diagnostics"
mkdir -p "$DIAG"

adb wait-for-device
booted=""
for attempt in $(seq 1 30); do
  booted="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  [[ "$booted" == "1" ]] && break
  sleep 2
done
printf 'sys.boot_completed=%s\n' "${booted:-unavailable}" > "$DIAG/device-boot.txt"
adb shell getprop > "$DIAG/device-properties.txt" 2>&1 || true
adb shell pm list instrumentation -f > "$DIAG/instrumentation-list.txt" 2>&1 || true
adb shell pm list packages -3 > "$DIAG/third-party-packages.txt" 2>&1 || true

if [[ "$booted" != "1" ]]; then
  echo "Emulator did not finish booting after the emulator-runner readiness check." > "$DIAG/diagnostic-error.txt"
  exit 2
fi

# `pm path android` can succeed while other system providers (settings,
# storage) are still initializing — run #1648 saw exactly that: three
# install attempts failed in turn with "Can't find service: package",
# "Cannot access system provider: 'settings'", and a StorageManager NPE,
# even though the `pm path` probe had passed. Probe the settings provider
# too, so an install is only attempted once the system services it needs
# are actually up.
emulator_system_ready() {
  adb shell pm path android >/dev/null 2>&1 && adb shell settings get secure android_id >/dev/null 2>&1
}

# sys.boot_completed can become 1 before Package Manager is ready to accept APK installs.
package_ready=""
for attempt in $(seq 1 45); do
  if emulator_system_ready; then
    package_ready="1"
    break
  fi
  sleep 2
done
if [[ "$package_ready" != "1" ]]; then
  echo "Android Package Manager did not become ready after boot." > "$DIAG/diagnostic-error.txt"
  adb logcat -d -b all -v threadtime > "$DIAG/emulator-logcat-package-manager-timeout.txt" 2>&1 || true
  exit 3
fi

install_with_retry() {
  local apk="$1"
  for attempt in $(seq 1 6); do
    # Re-probe before each attempt: the pre-build readiness check goes
    # stale during the ~4.5 minute APK build, and `pm path` alone proved
    # too weak in run #1648 (three installs failed in ~17 seconds while
    # the system was still bringing providers up).
    for ready_attempt in $(seq 1 30); do
      emulator_system_ready && break
      sleep 2
    done
    if adb install -r "$apk"; then
      return 0
    fi
    echo "APK install attempt $attempt failed; waiting for system services and retrying." >&2
    adb wait-for-device || true
    sleep 15
  done
  return 1
}

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
if [[ "${SKIP_ANDROID_APK_BUILD:-false}" != "true" ]]; then
  if ! gradle --no-daemon --stacktrace assembleDebug assembleDebugAndroidTest; then
    exit 1
  fi
fi

if ! install_with_retry app/build/outputs/apk/debug/app-x86_64-debug.apk; then
  echo "Failed to install the application APK after three attempts." > "$DIAG/diagnostic-error.txt"
  exit 4
fi
if ! install_with_retry app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk; then
  echo "Failed to install the instrumentation APK after three attempts." > "$DIAG/diagnostic-error.txt"
  exit 5
fi

run_with_timeout() {
  local limit_seconds="$1"
  shift
  perl -e '
    use strict;
    use warnings;
    my $limit = shift @ARGV;
    my $pid = fork();
    die "Unable to fork instrumentation process: $!" unless defined $pid;
    if ($pid == 0) {
      exec @ARGV or die "Unable to execute instrumentation command: $!";
    }
    $SIG{ALRM} = sub {
      kill "TERM", $pid;
      sleep 10;
      kill "KILL", $pid;
      waitpid($pid, 0);
      exit 124;
    };
    alarm($limit);
    waitpid($pid, 0);
    alarm(0);
    my $status = $?;
    exit(($status & 127) ? 128 + ($status & 127) : ($status >> 8));
  ' "$limit_seconds" "$@"
}

set +e
instrument_args=(-w -r)
if [[ -n "${ANDROID_TEST_FILTER:-}" ]]; then
  instrument_args+=(-e class "$ANDROID_TEST_FILTER")
  echo "Running targeted instrumentation tests: $ANDROID_TEST_FILTER"
fi
run_with_timeout 1500 adb shell am instrument "${instrument_args[@]}" app.nestro.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee "$DIAG/instrumentation-run.txt"
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
