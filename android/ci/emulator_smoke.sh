#!/bin/bash
# End-to-end smoke test on an Android emulator: install, grant permissions, open every screen,
# simulate an incoming call from an unknown number and an SMS with a code during the call.
# Screenshots go to shots/, crashes to shots/crash.txt.
set -x
P=ai.neuroshield.app
APK=$(ls app-apk/*universal*.apk | head -1)
mkdir -p shots
shot() { sleep "${2:-3}"; adb exec-out screencap -p > "shots/$1.png"; }
# tap the on-screen element whose text (or content description) is exactly $1
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1
  xy=$(python3 - "$1" <<'PY'
import re,sys
t=open('/tmp/ui.xml',encoding='utf-8').read(); want=sys.argv[1]
for m in re.finditer(r'<node [^>]*>', t):
    n=m.group(0)
    if f'text="{want}"' in n or f'content-desc="{want}"' in n:
        b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            x1,y1,x2,y2=map(int,b.groups()); print((x1+x2)//2,(y1+y2)//2); break
PY
)
  if [ -n "$xy" ]; then adb shell input tap $xy; echo "tapped $1 at $xy"; else echo "not found: $1"; fi
}
adb install -r -g "$APK" || exit 1
for perm in READ_PHONE_STATE READ_CALL_LOG READ_CONTACTS RECEIVE_SMS RECORD_AUDIO CAMERA POST_NOTIFICATIONS; do
  adb shell pm grant $P android.permission.$perm || true
done
adb shell appops set $P SYSTEM_ALERT_WINDOW allow
adb shell cmd role add-role-holder android.app.role.CALL_SCREENING $P || true
adb logcat -c
adb shell am start -n $P/.MainActivity; shot 00-onboarding 8
tap_text "Далее"; shot 00-onboarding-2 2
tap_text "Пропустить"; sleep 2
adb shell am start -n $P/.MainActivity --es route home; shot 01-home 4
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
# redesigned screens with real data in them
adb shell am start -n $P/.MainActivity --es route home; shot 15-home-with-threats 4
adb shell am start -n $P/.MainActivity --el alert_id 2; shot 16-threat-detail 4
adb shell am start -n $P/.MainActivity --es route history; shot 17-threats 3
# phishing link in a messenger message
adb shell cmd notification post -S bigtext -t "+447700900123" ns_phish "'Your Kaspi account is blocked. Verify now: https://kaspi-verify.top/login'"; shot 18-phishing-link 6
# block a number the user marked as a scammer
adb shell am start -n $P/.MainActivity --el alert_id 1; sleep 4; tap_text "Это мошенник"; sleep 1
adb shell am start -n $P/.MainActivity --es route settings; sleep 3; tap_text "Блокировать отмеченных мошенников"; shot 19-settings-blocking 2
adb shell input keyevent KEYCODE_HOME; sleep 1
adb emu gsm call 87015550123; sleep 6; adb emu gsm cancel 87015550123; sleep 2
adb shell am start -n $P/.MainActivity --es route history; shot 20-history-blocked-call 4
# demo scenario for presentations (scripted input, real analysis)
tap_text "Понятно"; sleep 1     # close the phishing warning left from the previous scenario
adb shell am start -n $P/.MainActivity --es route demo; shot 21-demo 4
tap_text "▶  Запустить весь сценарий"; shot 22-demo-call 3
tap_text "Понятно"; shot 23-demo-speech 12
shot 24-demo-speech-alert 6
shot 25-demo-sms 8
shot 26-demo-phishing 5
tap_text "Понятно"; sleep 8
adb shell input swipe 540 1700 540 500 300; shot 27-demo-finished 2
adb shell am start -n $P/.MainActivity --es route history; shot 28-history-demo 4
# --- screen-recorded walkthrough (for people who cannot install the APK) ---
read W H <<< "$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | tr 'x' ' ')"
swipe_up() { adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 450; }
adb shell am start -n $P/.MainActivity --es route home; sleep 3; tap_text "Понятно"; sleep 1
adb shell screenrecord --time-limit 175 --bit-rate 5000000 /sdcard/walk.mp4 &
sleep 2
adb shell am start -n $P/.MainActivity --es route home; sleep 4
swipe_up; sleep 2
tap_text "Тест"; sleep 5; tap_text "Понятно"; sleep 2
adb shell am start -n $P/.MainActivity --es route text; sleep 3
tap_text "Пример"; sleep 2; tap_text "Проверить"; sleep 4; swipe_up; sleep 4
adb shell input keyevent KEYCODE_HOME; sleep 2
adb emu gsm call 87017770011; sleep 7
adb emu gsm accept 87017770011; sleep 4
adb emu sms send 7700 "Kod podtverzhdeniya 4821. Nikomu ne soobshchayte"; sleep 8
adb emu gsm cancel 87017770011; sleep 2
adb shell am start -n $P/.MainActivity --es route demo; sleep 3
tap_text "▶  Запустить весь сценарий"; sleep 45
swipe_up; sleep 4
adb shell am start -n $P/.MainActivity --es route history; sleep 5
adb shell pkill -INT screenrecord; sleep 4
adb pull /sdcard/walk.mp4 shots/walkthrough-raw.mp4 || true
adb logcat -d > shots/logcat.txt
grep -E "FATAL EXCEPTION|AndroidRuntime: " -A 12 shots/logcat.txt > shots/crash.txt || true
adb shell dumpsys window | grep -i "neuroshield" | head -20 > shots/windows.txt || true
echo "alive: $(adb shell pidof $P)" >> shots/crash.txt
exit 0
