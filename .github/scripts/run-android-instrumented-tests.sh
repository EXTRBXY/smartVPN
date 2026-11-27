#!/usr/bin/env bash
set -euo pipefail

adb shell getprop ro.product.cpu.abi | grep -qw x86_64

if bash ./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.injected.build.abi=x86_64; then
    exit 0
else
    status=$?
fi

echo '::group::adb logcat (crash buffer)'
adb logcat -b crash -d -v threadtime || true
echo '::endgroup::'
exit "$status"
