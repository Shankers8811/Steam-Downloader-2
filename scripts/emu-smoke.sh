#!/usr/bin/env bash
# Fully-logged DIY emulator boot + instrumented smoke run.
# Every line lands in emulator-tests.log (tee'd by the caller) so a PR
# comment can carry the real failure text back (log hosts are unreliable).
set -uo pipefail

echo "== emu-smoke: env =="
echo "ANDROID_HOME=${ANDROID_HOME:-unset} ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-unset}"
java -version 2>&1 | head -2
gradle --version 2>&1 | head -4 || echo "gradle missing!"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
EMU="$SDK/emulator/emulator"
ADB="$SDK/platform-tools/adb"
# Multiple cmdline-tools trees can coexist on the runner. Prefer latest-2 (the
# copy this job installs): the runner's stock 'latest' binary only understands
# SDK XML v3 and silently fails AVD creation against modern system images.
AVDMGR=""
for p in "$SDK/cmdline-tools/latest-2/bin/avdmanager" \
         "$SDK/cmdline-tools/latest/bin/avdmanager" \
         "$SDK/tools/bin/avdmanager"; do
  if [ -x "$p" ]; then AVDMGR="$p"; break; fi
done
if [ -z "$AVDMGR" ]; then
  echo "INSTRUMENTATION_FAIL no-avdmanager"
  ls "$SDK/cmdline-tools" 2>/dev/null
  exit 1
fi
echo "AVDMGR=$AVDMGR"
echo "HOME=$HOME ANDROID_AVD_HOME=${ANDROID_AVD_HOME:-unset} ANDROID_SDK_HOME=${ANDROID_SDK_HOME:-unset}"

echo "== sdk tree =="
ls "$SDK" || true
ls "$SDK/emulator" || true

echo "== gradle: assemble debug + test apks =="
gradle --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
GR=$?
echo "assemble rc=$GR"
if [ $GR -ne 0 ]; then echo "INSTRUMENTATION_FAIL assemble"; exit 1; fi

echo "== create AVD =="
AVD_NAME="arena_api34"
# Pin the AVD home explicitly BEFORE creation: avdmanager picks its home from
# ANDROID_AVD_HOME / ANDROID_SDK_HOME / $HOME and on this runner image it
# silently chooses something other than $HOME/.android (creation "succeeds"
# with rc=0 yet writes nothing where anyone expects). A pinned, pre-created
# ANDROID_AVD_HOME removes the ambiguity for both avdmanager and the emulator.
export ANDROID_AVD_HOME="$HOME/.android/avd"
mkdir -p "$ANDROID_AVD_HOME"
"$AVDMGR" delete avd -n "$AVD_NAME" >/dev/null 2>&1 || true
echo "no" | "$AVDMGR" create avd -n "$AVD_NAME" \
  -k "system-images;android-34;google_apis;x86_64" \
  --force
CR=$?
echo "avdmanager rc=$CR"
[ $CR -ne 0 ] && { echo "INSTRUMENTATION_FAIL avd"; exit 1; }
echo "== locate avd files =="
ls -la "$ANDROID_AVD_HOME" || true
find "$HOME/.android" /usr/local/lib/android/sdk -maxdepth 3 -name "${AVD_NAME}.ini" 2>/dev/null || true
# Wherever the ini actually landed, point the emulator at that directory.
INI="$(find "$ANDROID_AVD_HOME" "$HOME/.android" /usr/local/lib/android/sdk -maxdepth 3 -name "${AVD_NAME}.ini" 2>/dev/null | head -1)"
if [ -z "$INI" ]; then
  echo "INSTRUMENTATION_FAIL avd-ini-missing"
  exit 1
fi
export ANDROID_AVD_HOME="$(dirname "$INI")"
echo "ANDROID_AVD_HOME=$ANDROID_AVD_HOME"
"$EMU" -list-avds || true

echo "== boot emulator =="
"$EMU" -avd "$AVD_NAME" -no-window -no-snapshot -no-audio -no-boot-anim \
  -camera-back none -camera-front none -gpu swiftshader_indirect -memory 3072 \
  -wipe-data &
EMU_PID=$!
echo "emulator pid=$EMU_PID"

sleep 8
"$ADB" wait-for-device
echo "== waiting for full boot =="
BOOTED=""
for i in $(seq 1 60); do
  BOOTED="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  if [ "$BOOTED" = "1" ]; then echo "boot completed after ~$((i*5))s"; break; fi
  sleep 5
done
if [ "$BOOTED" != "1" ]; then
  echo "INSTRUMENTATION_FAIL boot-timeout"
  kill $EMU_PID 2>/dev/null
  exit 1
fi
"$ADB" shell settings put global window_animation_scale 0
"$ADB" shell settings put global transition_animation_scale 0
"$ADB" shell settings put global animator_duration_scale 0

echo "== connected devices =="
"$ADB" devices -l

echo "== connected androidTest =="
gradle --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.EmulatedBootSmokeTest
TR=$?
echo "connectedTest rc=$TR"

echo "== dump test results =="
find app/build/reports/androidTests -name "*.html" 2>/dev/null | head -5
find app/build/outputs/androidTest-results -name "*.xml" 2>/dev/null | head -5 | while read f; do
  echo "---- $f"; tail -n 40 "$f"
done

kill $EMU_PID 2>/dev/null
if [ $TR -eq 0 ]; then echo "INSTRUMENTATION_OK"; else echo "INSTRUMENTATION_FAIL tests rc=$TR"; exit 1; fi
