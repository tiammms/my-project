#!/bin/bash
# End-to-end smoke test on an Android emulator: install, grant permissions, open every screen,
# simulate an incoming call from an unknown number and an SMS with a code during the call.
# Screenshots go to shots/, crashes to shots/crash.txt.
set -x
P=ai.neuroshield.app
APK=$(ls app-apk/*universal*.apk | head -1)
mkdir -p shots
shot() { sleep "${2:-3}"; adb exec-out screencap -p > "shots/$1.png"; }
adb install -r -g "$APK" || exit 1
for perm in READ_PHONE_STATE READ_CALL_LOG READ_CONTACTS RECEIVE_SMS RECORD_AUDIO CAMERA POST_NOTIFICATIONS; do
  adb shell pm grant $P android.permission.$perm || true
done
adb shell appops set $P SYSTEM_ALERT_WINDOW allow
adb shell cmd role add-role-holder android.app.role.CALL_SCREENING $P || true
adb logcat -c
adb shell am start -n $P/.MainActivity; shot 01-home 8
for r in protection history settings text face; do adb shell am start -n $P/.MainActivity --es route $r; shot "02-$r" 4; done
adb shell am start -n $P/.MainActivity --es route home; sleep 2
adb shell input keyevent KEYCODE_HOME; sleep 2
adb emu gsm call 87015550123; shot 03-incoming-call 6
adb emu gsm accept 87015550123; shot 04-in-call 5
adb emu sms send 1414 "Kod podtverzhdeniya 482913. Nikomu ne soobshchayte"; shot 05-sms-code-during-call 6
adb emu gsm cancel 87015550123; sleep 3
adb shell am start -n $P/.MainActivity --es route history; shot 06-history-after-call 5
adb logcat -d > shots/logcat.txt
grep -E "FATAL EXCEPTION|AndroidRuntime: " -A 12 shots/logcat.txt > shots/crash.txt || true
adb shell dumpsys window | grep -i "neuroshield" | head -20 > shots/windows.txt || true
echo "alive: $(adb shell pidof $P)" >> shots/crash.txt
exit 0
