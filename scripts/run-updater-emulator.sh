#!/usr/bin/env bash
# Disposable Android 12+ emulator only, using key-free debug verification APKs.
# Exercises requested installer parameters; never commits an APK installation.
set -euo pipefail
cd "$(dirname "$0")/.."
evidence="${ANDROID_UPDATE_QA_OUTPUT:-work/android-update-qa}"
adb wait-for-device
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ ! "$api" =~ ^[0-9]+$ ]] || (( api < 31 )); then
  echo 'Update-delivery verification requires Android 12 / API 31 or newer' >&2
  exit 1
fi
mkdir -p "$evidence"
printf 'api=%s\nfixture_scope=installer_parameters_uncommitted\n' "$api" > "$evidence/platform.txt"
adb install -r android/hotel/build/outputs/apk/debug/hotel-debug.apk
adb install -r -t android/hotel/build/outputs/apk/androidTest/debug/hotel-debug-androidTest.apk
adb shell pm clear dev.orange.hotel
adb logcat -c
trap 'adb logcat -d > "$evidence/logcat.txt"; adb shell pm revoke dev.orange.hotel android.permission.RECORD_AUDIO > /dev/null 2>&1 || true' EXIT
timeout 180s adb shell am instrument -w -r -e case update-delivery \
  dev.orange.hotel.test/com.agentdeck.hotel.VoiceLifecycleFixture | tee "$evidence/update-delivery.txt"
# adb may return zero after an instrumentation assertion fails.
grep -q 'PASS case=update-delivery ' "$evidence/update-delivery.txt"
grep -q '^INSTRUMENTATION_CODE: -1' "$evidence/update-delivery.txt"

# Also qualify API31's actual native audio/start-threshold path without a provider.
adb shell pm clear dev.orange.hotel
timeout 180s adb shell am instrument -w -r -e case native-audio-burst \
  dev.orange.hotel.test/com.agentdeck.hotel.VoiceLifecycleFixture | tee "$evidence/native-audio-burst.txt"
grep -q 'PASS case=native-audio-burst ' "$evidence/native-audio-burst.txt"
grep -q 'NATIVE_AUDIO_BURST recording=true listening=true playback_head=true released=true provider_calls=0' "$evidence/native-audio-burst.txt"
grep -q '^INSTRUMENTATION_CODE: -1' "$evidence/native-audio-burst.txt"
adb shell pm revoke dev.orange.hotel android.permission.RECORD_AUDIO
