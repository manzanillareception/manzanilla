#!/usr/bin/env bash
# Run only on a disposable emulator with the key-free debug build.
set -euo pipefail
cd "$(dirname "$0")/.."
evidence="${ANDROID_QA_OUTPUT:-work/android-emulator-qa}"
mkdir -p "$evidence"
adb wait-for-device
# Refuse destructive fixture resets on a physical device.
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb install -r android/hotel/build/outputs/apk/debug/hotel-debug.apk
adb install -r android/hotel/build/outputs/apk/androidTest/debug/hotel-debug-androidTest.apk
adb shell dumpsys webviewupdate > "$evidence/webview.txt"
adb shell cmd package set-home-activity com.android.launcher3/.Launcher
adb logcat -c
trap 'adb logcat -d > "$evidence/logcat.txt"; adb shell pm revoke dev.orange.hotel android.permission.RECORD_AUDIO > /dev/null 2>&1 || true; adb shell locksettings clear --old 2468 > /dev/null 2>&1 || true' EXIT
run_fixture() {
  local fixture="$1"
  local label="${2:-$1}"
  # Reset only this disposable, key-free test app between independent scenarios.
  adb shell pm clear dev.orange.hotel
  timeout 240s adb shell am instrument -w -r -e case "$fixture" \
    dev.orange.hotel.test/com.agentdeck.hotel.VoiceLifecycleFixture | tee "$evidence/$label.txt"
  if [[ "$fixture" == native-audio-burst ]]; then
    # Revoke only after instrumentation finishes: revocation can terminate its target process.
    adb shell pm revoke dev.orange.hotel android.permission.RECORD_AUDIO
    grep -q 'NATIVE_AUDIO_BURST recording=true listening=true playback_head=true released=true provider_calls=0' "$evidence/$label.txt"
  fi
  # adb itself returns zero even when an instrumentation assertion fails.
  grep -q "PASS case=$fixture " "$evidence/$label.txt"
  grep -q '^INSTRUMENTATION_CODE: -1' "$evidence/$label.txt"
}
for fixture in reception-bridge translation-unavailable permission-lifecycle native-audio-burst retired-credentials update-delivery; do
  run_fixture "$fixture"
done

# Staff management screen: capture real pixels and pull the PNG into the evidence artifact.
run_fixture management-screen
shot_path=$(grep -o 'screenshot=[^ ]*' "$evidence/management-screen.txt" | head -1 | cut -d= -f2- | tr -d '\r')
test -n "$shot_path"
adb pull "$shot_path" "$evidence/management-screen.png" > /dev/null
# Personal translation setup must work whether Android has a screen lock or not.
# Only the known synthetic PIN may be cleared on this disposable emulator.
adb shell locksettings clear --old 2468 > /dev/null 2>&1 || adb shell locksettings clear > /dev/null
run_fixture voice-setup voice-setup-no-lock
grep -q 'VOICE_SETUP deviceSecure=false' "$evidence/voice-setup-no-lock.txt"
adb shell locksettings set-pin 2468
run_fixture voice-setup voice-setup-device-pin
grep -q 'VOICE_SETUP deviceSecure=true' "$evidence/voice-setup-device-pin.txt"
