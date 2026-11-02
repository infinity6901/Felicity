#!/usr/bin/env bash
# Installs the release APK on the running emulator, launches it, and fails if the app crashes
# or dies. Catches "release build closes immediately" bugs (R8 / missing keep rules) in CI.
# Usage: smoke-test.sh <apk> <outdir>
set -u
APK="$1"; OUT="$2"; PKG="app.simple.felicity"
mkdir -p "$OUT"

adb install -r "$APK" || { echo "::error::APK failed to install"; exit 1; }
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 25

adb logcat -d > "$OUT/logcat-full.txt"
adb logcat -d -b crash > "$OUT/logcat-crash.txt"
adb shell dumpsys activity processes > "$OUT/processes.txt" 2>&1 || true

FAIL=0
if grep -q "FATAL EXCEPTION" "$OUT/logcat-crash.txt" "$OUT/logcat-full.txt"; then
  echo "::error::The release app crashed on launch. First crash:"
  grep -h -m1 -A25 "FATAL EXCEPTION" "$OUT/logcat-crash.txt" "$OUT/logcat-full.txt" | head -40
  FAIL=1
fi
if [ -z "$(adb shell pidof "$PKG" | tr -d '\r')" ]; then
  echo "::error::The app process is not running 25s after launch (it exited or was killed)."
  FAIL=1
fi
[ "$FAIL" = 0 ] && echo "Smoke test passed: app launched and stayed alive."
exit $FAIL
