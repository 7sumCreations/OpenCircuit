#!/bin/bash
# Runs the instrumented HealthCardGrantStatesTest in each Health Connect grant state: nothing
# granted, some (all but SpO2 and sleep), everything. Revoking a health permission ends the app's
# process, so the grants are set here, from outside the test, before each run.
#
# Usage: ANDROID_SERIAL=emulator-5584 scripts/health-card-grants.sh
# Needs the debug app and its test APK installed:
#   ./gradlew --no-daemon :app:installDebug :app:installDebugAndroidTest
set -euo pipefail

SERIAL="${ANDROID_SERIAL:?set ANDROID_SERIAL to your own emulator, e.g. emulator-5584}"
ADB="${ADB:-adb}"
PKG=io.github.opencircuit.android
RUNNER=io.github.opencircuit.android.test/androidx.test.runner.AndroidJUnitRunner
PERMS=(HEART_RATE RESTING_HEART_RATE HEART_RATE_VARIABILITY OXYGEN_SATURATION RESPIRATORY_RATE SKIN_TEMPERATURE
  STEPS DISTANCE ACTIVE_CALORIES_BURNED BASAL_METABOLIC_RATE SLEEP MENSTRUATION)

failed=0
for state in none some all; do
  "$ADB" -s "$SERIAL" shell am force-stop "$PKG"
  for p in "${PERMS[@]}"; do
    case "$state:$p" in
      none:* | some:OXYGEN_SATURATION | some:SLEEP) "$ADB" -s "$SERIAL" shell pm revoke "$PKG" "android.permission.health.WRITE_$p" ;;
      *) "$ADB" -s "$SERIAL" shell pm grant "$PKG" "android.permission.health.WRITE_$p" ;;
    esac
  done
  granted=$("$ADB" -s "$SERIAL" shell dumpsys package "$PKG" | grep -c 'health.WRITE_.*granted=true' || true)
  out=$("$ADB" -s "$SERIAL" shell am instrument -w -e class io.github.opencircuit.app.HealthCardGrantStatesTest -e hcCardExpect "$state" "$RUNNER")
  if grep -q '^OK (1 test)' <<<"$out"; then
    echo "HEALTH CARD GRANTS: $state ($granted of 12 granted) — PASS"
  else
    echo "HEALTH CARD GRANTS: $state ($granted of 12 granted) — FAIL"
    grep -E 'Error|expected' <<<"$out" | head -3
    failed=1
  fi
done
exit "$failed"
