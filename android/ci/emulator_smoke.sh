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
adb shell am start -n $P/.MainActivity --ez start_listen true; shot 07-listen-without-model 5
# messenger protection: notifications posted from the shell stand in for WhatsApp (the app labels them as an ADB test)
adb shell cmd notification allow_listener $P/$P.notify.MessengerListener
adb shell input keyevent KEYCODE_HOME; sleep 3
adb shell cmd notification post -S bigtext -t "+447911123456" ns_msg "'This is the bank security team, read me the verification code right now'"; shot 08-messenger-scam-message 6
adb shell cmd notification post -t "+447911123456" ns_call "'Incoming voice call'"; shot 09-messenger-call-after-message 6
adb shell cmd notification cancel ns_call || true
adb shell cmd notification post -t "+15551230000" ns_video "'Incoming video call'"; shot 10-messenger-video-call 6
# video-call check: a still photo on screen must be flagged as "photo instead of a live person"
if [ -f face.jpg ]; then
  adb push face.jpg /sdcard/Pictures/face.jpg
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/face.jpg; sleep 4
  id=$(adb shell content query --uri content://media/external/images/media --projection _id | grep -o '_id=[0-9]*' | tail -1 | cut -d= -f2)
  if [ -n "$id" ]; then adb shell am start -a android.intent.action.VIEW -d content://media/external/images/media/$id -t image/jpeg
  else adb shell am start -a android.intent.action.VIEW -d file:///sdcard/Pictures/face.jpg -t image/jpeg; fi
  shot 11-photo-on-screen 5
  adb shell appops set $P PROJECT_MEDIA allow
  adb shell am start -n $P/.MainActivity --ez start_video true; shot 12-video-check-running 6
  sleep 34; shot 13-video-check-result 4
  adb shell am start -n $P/.MainActivity --es route protection; shot 14-video-check-in-app 4
fi
adb logcat -d > shots/logcat.txt
grep -E "FATAL EXCEPTION|AndroidRuntime: " -A 12 shots/logcat.txt > shots/crash.txt || true
adb shell dumpsys window | grep -i "neuroshield" | head -20 > shots/windows.txt || true
echo "alive: $(adb shell pidof $P)" >> shots/crash.txt
exit 0
