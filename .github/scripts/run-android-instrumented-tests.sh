#!/usr/bin/env bash
set -euo pipefail

adb shell getprop ro.product.cpu.abilist | grep -qw arm64-v8a

if bash ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.build.abi=arm64-v8a; then
    exit 0
else
    status=$?
fi

echo '::group::adb logcat (crash buffer)'
adb logcat -b crash -d -v threadtime || true
echo '::endgroup::'
exit "$status"
