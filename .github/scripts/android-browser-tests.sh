#!/usr/bin/env bash
set -euo pipefail
gradle :android:connectedDebugAndroidTest --no-daemon --stacktrace
adb install -r android/build/outputs/apk/androidTest/debug/android-debug-androidTest.apk
mkdir -p android/build/browser-test
for phase in publish restore logout verifyLogout; do
  adb shell am force-stop io.grimoire.sessions.android.test
  log="android/build/browser-test/$phase.log"
  adb shell am instrument -w -r \
    -e class 'io.grimoire.sessions.android.WebViewCookieStoreTest#persistentCookieAndLogout' \
    -e phase "$phase" \
    io.grimoire.sessions.android.test/androidx.test.runner.AndroidJUnitRunner | tee "$log"
  grep -Fq 'OK (1 test)' "$log"
  grep -Fq 'INSTRUMENTATION_CODE: -1' "$log"
  if grep -Eq 'FAILURES|INSTRUMENTATION_(FAILED|ABORTED)' "$log"; then exit 1; fi
done
