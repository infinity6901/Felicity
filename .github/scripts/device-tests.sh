#!/usr/bin/env bash
# Runs on a booted emulator (via reactivecircus/android-emulator-runner).
# Automates the parts of test-plan stage 2 that need no human and no physical audio hardware:
#   - Gradle instrumented tests (connected*AndroidTest)
#   - install + cold launch of the debug APK
#   - background/foreground, rotation, lock/unlock and process-restart cycles (plan 2.7)
#   - crash / ANR detection and PSS memory trend (plan 2.7)
#   - logcat capture exactly as in plan 3.2, plus MediaSession / audio dumpsys snapshots
# It does NOT assert audible playback, crossfade quality, DSP, USB DAC, Bluetooth or battery:
# those stay NOT RUN in the QA report.
# Usage: device-tests.sh <debug-apk> <outdir> [cycles]
set -u
APK="$1"; OUT="$2"; CYCLES="${3:-5}"; PKG="app.simple.felicity"
mkdir -p "$OUT"
export CI_LOG_DIR="$OUT/ci-logs" CI_STATUS_DIR="$OUT/ci-status"
# shellcheck source=/dev/null
source "$(dirname "$0")/ci-step.sh"

adb wait-for-device
adb shell getprop ro.product.model         > "$OUT/device-model.txt"
adb shell getprop ro.build.version.release > "$OUT/android-version.txt"
adb shell getprop ro.build.display.id      > "$OUT/android-build.txt"
adb shell getprop ro.product.cpu.abi       > "$OUT/device-abi.txt"

# --- 1. Gradle instrumented tests -----------------------------------------------------------
ci_step instrumented-tests "Gradle instrumented tests (connected*AndroidTest, all modules)" \
  ./gradlew connectedFossDebugAndroidTest connectedDebugAndroidTest --continue --stacktrace --no-build-cache

# --- 2. Install the artifact that CI built --------------------------------------------------
adb logcat -c
adb logcat -v time > "$OUT/felicity-logcat.txt" 2>&1 &      # plan 3.2
LOGCAT_PID=$!
trap 'kill $LOGCAT_PID 2>/dev/null' EXIT

# Gradle's connected tests may have left a differently-signed copy behind; start clean.
adb uninstall "$PKG" >/dev/null 2>&1 || true
ci_step apk-install "Install debug APK on emulator" adb install -r -g "$APK"

alive()  { [ -n "$(adb shell pidof "$PKG" | tr -d '\r')" ]; }
crashed() { adb logcat -d -b crash | grep -A3 "FATAL EXCEPTION" | grep -q "Process: $PKG"; }
anr()    { adb logcat -d | grep -q "ANR in $PKG"; }
pss_kb() { adb shell dumpsys meminfo "$PKG" | awk '/TOTAL PSS:/ {print $3; exit} /^ *TOTAL +[0-9]/ {print $2; exit}'; }

# check_phase <id> <description> : asserts the app survived the phase that just ran
check_phase() {
  local id="$1" desc="$2" rc=0
  sleep 3
  alive   || { echo "::error::app process is gone after: $desc"; rc=1; }
  crashed && { echo "::error::FATAL EXCEPTION after: $desc"; rc=1; }
  anr     && { echo "::error::ANR after: $desc"; rc=1; }
  if [ $rc -eq 0 ]; then ci_record "$id" PASS "$desc"; else ci_record "$id" FAIL "$desc"
    adb logcat -d -t 400 > "$CI_LOG_DIR/$id.log" 2>&1; fi
}

# --- 3. Test tones so MediaStore has something to index (informational, playback not asserted) -
python3 - "$OUT" <<'PY'
import math, struct, sys, wave
out = sys.argv[1]
for i, f in enumerate((220.0, 330.0, 440.0), 1):
    with wave.open(f"{out}/tone{i}.wav", "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(44100)
        frames = b"".join(struct.pack("<hh", s, s) for s in
                          (int(9000 * math.sin(2 * math.pi * f * n / 44100)) for n in range(44100 * 20)))
        w.writeframes(frames)
PY
adb shell mkdir -p /sdcard/Music/felicity-ci
for i in 1 2 3; do
  adb push "$OUT/tone$i.wav" /sdcard/Music/felicity-ci/ >/dev/null
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
      -d "file:///sdcard/Music/felicity-ci/tone$i.wav" >/dev/null 2>&1 || true
done

# --- 4. Cold launch -------------------------------------------------------------------------
adb shell am start -W -n "$PKG/.activities.MainActivity" >/dev/null 2>&1 \
  || adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 20
check_phase cold-launch "Cold launch: app stays alive, no crash/ANR for 20 s"
PSS_BEFORE=$(pss_kb)

# --- 5. Lifecycle cycles (plan 2.1 screen off / backgrounded, 2.7 lifecycle) ---------------------
for i in $(seq 1 "$CYCLES"); do
  adb shell input keyevent KEYCODE_HOME; sleep 2
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 2
done
check_phase background-foreground "Background/foreground x$CYCLES"

adb shell settings put system accelerometer_rotation 0
for i in $(seq 1 "$CYCLES"); do
  adb shell settings put system user_rotation 1; sleep 2
  adb shell settings put system user_rotation 0; sleep 2
done
check_phase rotation "Rotate portrait/landscape x$CYCLES"

for i in $(seq 1 "$CYCLES"); do
  adb shell input keyevent KEYCODE_SLEEP; sleep 2
  adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard; sleep 2
done
check_phase lock-unlock "Screen off/on x$CYCLES"

# Process restart: force-stop kills the playback service together with the UI, then relaunch.
adb shell am force-stop "$PKG"; sleep 2
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 15
check_phase process-restart "Force-stop then relaunch: app restarts cleanly"

# --- 6. Memory trend (informational) ---------------------------------------------------------
PSS_AFTER=$(pss_kb)
printf 'pss_before_kb=%s\npss_after_kb=%s\n' "${PSS_BEFORE:-n/a}" "${PSS_AFTER:-n/a}" > "$OUT/memory-trend.txt"

# --- 7. Snapshots for later inspection (MediaSession / audio / services) --------------------------
adb shell dumpsys media_session                > "$OUT/dumpsys-media_session.txt" 2>&1
adb shell dumpsys audio                        > "$OUT/dumpsys-audio.txt"         2>&1
adb shell dumpsys activity services "$PKG"     > "$OUT/dumpsys-services.txt"      2>&1
adb shell dumpsys meminfo "$PKG"               > "$OUT/dumpsys-meminfo.txt"       2>&1
adb logcat -d -b crash                         > "$OUT/logcat-crash.txt"          2>&1
adb exec-out screencap -p                      > "$OUT/final-screen.png"          2>/dev/null || true
kill $LOGCAT_PID 2>/dev/null
exit 0
