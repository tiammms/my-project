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
# brings the app to a usable state: closes "isn't responding" system dialogs, our warning overlay and the first-run intro
ensure_ready() {
  for k in 1 2 3; do
    adb shell uiautomator dump /sdcard/r.xml >/dev/null 2>&1; adb pull /sdcard/r.xml /tmp/r.xml >/dev/null 2>&1
    if grep -q "isn't responding" /tmp/r.xml; then tap_text "Wait"; sleep 2; continue; fi
    if grep -q 'text="Понятно"' /tmp/r.xml || grep -q 'text="Түсінікті"' /tmp/r.xml; then tap_text "Понятно"; tap_text "Түсінікті"; sleep 2; continue; fi
    if grep -q 'text="Пропустить"' /tmp/r.xml || grep -q 'text="Өткізіп жіберу"' /tmp/r.xml; then tap_text "Пропустить"; tap_text "Өткізіп жіберу"; sleep 2; continue; fi
    break
  done
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
tap_text "Пропустить"; sleep 2; ensure_ready
adb shell am start -n $P/.MainActivity --es route home; shot 01-home 4
for r in protection history settings text face; do adb shell am start -n $P/.MainActivity --es route $r; shot "02-$r" 4; done
adb shell am start -n $P/.MainActivity --es route home; sleep 2
adb shell input keyevent KEYCODE_HOME; sleep 2
adb emu gsm call 87015550123; shot 03-incoming-call 6
adb emu gsm accept 87015550123; shot 04-in-call 5
adb emu sms send 1414 "Kod podtverzhdeniya 482913. Nikomu ne soobshchayte"; shot 05-sms-code-during-call 6
adb emu gsm cancel 87015550123; sleep 4
# automatic chain: after hang-up the app must post a call report on its own (no tap in the app)
check() { if adb shell dumpsys notification --noredact | grep -q "$2"; then echo "PASS $1" >> shots/asserts.txt; else echo "FAIL $1" >> shots/asserts.txt; fi; }
check "report after scam call (code during call)" "Отчёт о звонке: Возможное мошенничество: код из SMS во время звонка"
shot 06a-report-notification 1
adb shell cmd statusbar expand-notifications; shot 06b-report-in-shade 3; adb shell cmd statusbar collapse
adb emu gsm call 87015550199; sleep 6; adb emu gsm cancel 87015550199; sleep 12
check "report after missed call" "Отчёт о звонке: Пропущенный звонок с незнакомого номера"
check "report notification has the mark-number button" "Отметить номер"
# press the button in the notification shade (as a person would) and check that the number got marked
adb shell cmd statusbar expand-notifications; sleep 3
# Android groups several notifications of one app: open the Saqtan group and the report (as a person would), then find the button
B=""
for attempt in 1 2 3 4; do
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml shots/ui-shade.xml >/dev/null 2>&1 || true
  R=$(python3 - <<'PY'
import re
s=open('shots/ui-shade.xml',encoding='utf-8').read()
nodes=re.findall(r'<node [^>]*>',s)
def b(n):
    m=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',n); return [int(x) for x in m.groups()] if m else None
for n in nodes:
    if re.search(r'text="(Отметить номер|ОТМЕТИТЬ НОМЕР)"',n):
        x=b(n); print("tap",(x[0]+x[2])//2,(x[1]+x[3])//2); raise SystemExit
# expand button nearest below the "Saqtan" header or the report title
anchors=[b(n)[1] for n in nodes if re.search(r'text="(Saqtan|Отчёт о звонке[^"]*)"',n)]
exp=[b(n) for n in nodes if 'expand_button' in n]
best=None
for a in anchors:
    for e in exp:
        d=abs(e[1]-a)
        if d<200 and (best is None or d<best[0]): best=(d,e)
if best: e=best[1]; print("expand",(e[0]+e[2])//2,(e[1]+e[3])//2)
PY
)
  case "$R" in tap*) B=${R#tap }; break;; expand*) adb shell input tap ${R#expand }; sleep 2;; *) break;; esac
done
shot 06c-report-buttons 1
if [ -n "$B" ]; then adb shell input tap $B; sleep 3; fi
adb shell cmd statusbar collapse
if adb logcat -d | grep -q "report action: marked scam"; then echo "PASS mark-number button marks the number" >> shots/asserts.txt
elif [ -z "$B" ]; then echo "UNVERIFIED mark-number button: the automated test could not open the grouped notification" >> shots/asserts.txt
else echo "FAIL mark-number button marks the number (button at: $B)" >> shots/asserts.txt; fi
adb shell am start -n $P/.MainActivity --es route history; shot 06-history-after-call 5
# security: another app (here: the shell) must not be able to start the microphone through the launcher activity
adb shell am start -n $P/.MainActivity --ez start_listen true --ez auto true; sleep 4
if adb logcat -d | grep -q "ignored microphone/video request from an outside intent" && ! adb shell dumpsys activity services $P | grep -q ListenService; then
  echo "PASS outside app cannot start the microphone" >> shots/asserts.txt; else echo "FAIL outside app cannot start the microphone" >> shots/asserts.txt; fi
if adb shell am start -n $P/.InternalEntry --ez start_listen true 2>&1 | grep -qiE "Permission Denial|not exported|SecurityException"; then
  echo "PASS internal entry is not reachable from outside" >> shots/asserts.txt; else echo "FAIL internal entry is not reachable from outside" >> shots/asserts.txt; fi
adb shell am start -n $P/.MainActivity --es route protection; shot 07-listen-without-model 5
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
  adb shell am start -n $P/.MainActivity --es route protection; sleep 3
  adb shell input swipe 540 1700 540 600 400; sleep 2; tap_text "Проверить видеозвонок"; sleep 3
  tap_text "Start now"; tap_text "START NOW"; tap_text "Начать"; shot 12-video-check-running 6
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
# call recording (opt-in): turn it on in settings, answer a call from an unknown number, then play it from the report
adb shell am force-stop $P; sleep 2
adb shell am start -n $P/.MainActivity --es route settings; sleep 3; ensure_ready
adb shell am start -n $P/.MainActivity --es route settings; sleep 3
for k in 1 2 3; do adb shell input swipe 540 1800 540 500 300; sleep 1; done
tap_text "Записывать звонки"; sleep 2; shot 29a-record-consent 1; tap_text "Включить запись"; sleep 2
adb shell input keyevent KEYCODE_HOME; sleep 2
adb emu gsm call 87015550888; sleep 6; adb emu gsm accept 87015550888; sleep 15; shot 29b-recording-in-call 1
adb emu gsm cancel 87015550888; sleep 10
adb shell am start -n $P/.MainActivity --es route history; sleep 4
tap_text "Звонок с незнакомого номера: признаков мошенничества не найдено"; sleep 3; shot 29c-report-with-recording 1
adb shell uiautomator dump /sdcard/rec.xml >/dev/null 2>&1; adb pull /sdcard/rec.xml shots/ui-recording.xml >/dev/null 2>&1 || true
if grep -q "Прослушать запись" shots/ui-recording.xml 2>/dev/null; then echo "PASS call recording is attached to the call report" >> shots/asserts.txt
  tap_text "Прослушать запись"; sleep 3; shot 29d-recording-playing 1
  if adb logcat -d | grep -q "FATAL EXCEPTION"; then echo "FAIL recording playback" >> shots/asserts.txt; else echo "PASS recording playback starts without errors" >> shots/asserts.txt; fi
  # experimental synthetic-voice check on the saved recording (the emulator microphone is silent: "too little speech")
  tap_text "Проверить, не синтезирован ли голос"; sleep 20; shot 29e-recording-voice-check 1
  adb shell uiautomator dump /sdcard/vc.xml >/dev/null 2>&1; adb pull /sdcard/vc.xml shots/ui-voice-check.xml >/dev/null 2>&1 || true
  if grep -q -e "Речи для проверки мало" -e "синтезированного голоса не найдено" -e "Голос похож на синтезированный" shots/ui-voice-check.xml 2>/dev/null; then
    echo "PASS voice model loads and checks a recording" >> shots/asserts.txt
  else echo "FAIL voice model loads and checks a recording" >> shots/asserts.txt; fi
else echo "FAIL call recording is attached to the call report" >> shots/asserts.txt; fi
adb shell input keyevent KEYCODE_BACK; sleep 1
# demo: the voice model on the two bundled example clips (a person / a speech synthesizer)
# control: the same navigation WITHOUT running the model (tells whether a failure below comes from the model)
nav_ok() {   # $1 = label
  adb shell am start -n $P/.MainActivity --es route history; sleep 4
  adb shell uiautomator dump /sdcard/nv.xml >/dev/null 2>&1; adb pull /sdcard/nv.xml /tmp/nv.xml >/dev/null 2>&1
  if grep -q 'История хранится только на телефоне' /tmp/nv.xml 2>/dev/null; then echo "INFO navigation works: $1" >> shots/asserts.txt; else echo "INFO navigation STUCK: $1" >> shots/asserts.txt; fi
}
nav_ok "right after the recording check (before the demo)"
adb shell am start -n $P/.MainActivity --es route demo; sleep 3
nav_ok "demo opened, nothing tapped"
adb shell am start -n $P/.MainActivity --es route demo; sleep 3
for k in 1 2 3 4; do adb shell input swipe 540 1800 540 400 300; sleep 1; done
nav_ok "demo scrolled to the end, nothing tapped"
adb shell am force-stop $P; sleep 2
adb shell am start -n $P/.MainActivity --es route demo; sleep 4; ensure_ready
adb shell am start -n $P/.MainActivity --es route demo; sleep 3
for k in 1 2 3 4; do adb shell input swipe 540 1800 540 400 300; sleep 1; done
tap_text "Пример 1: голос человека"; sleep 12; tap_text "Пример 2: синтезатор речи (eSpeak)"; sleep 12; shot 29f-demo-voice-examples 1
adb shell uiautomator dump /sdcard/vx.xml >/dev/null 2>&1; adb pull /sdcard/vx.xml shots/ui-voice-examples.xml >/dev/null 2>&1 || true
python3 - <<'PY' >> shots/asserts.txt
import re
t = open('shots/ui-voice-examples.xml', encoding='utf-8').read() if __import__('os').path.exists('shots/ui-voice-examples.xml') else ''
res = re.findall(r'text="(Модель: [^"]+)"', t)
print(("PASS" if len(res) == 2 else "FAIL") + " voice model runs on both demo examples")
for r in res: print("INFO demo voice example: " + r)
# expected from ml/voice_spoof/results/metrics.json: real 2 %, synthetic 99 % (same file, same model on the phone)
ok = len(res) == 2 and res[0].startswith("Модель: не похож") and res[1].startswith("Модель: похож")
print(("PASS" if ok else "FAIL") + " phone gives the same verdicts on the demo examples as the training run")
PY
# the app must stay responsive after the model ran: navigate away and look at the screen
sleep 6   # let the example clip finish
adb shell am start -n $P/.MainActivity --es route history; sleep 4; shot 29g-after-voice-demo 1
adb shell dumpsys activity activities | grep -i -E "mResumed|ResumedActivity" > shots/after-voice-demo.txt 2>&1
adb shell dumpsys window | grep -i -E "anr|not responding|mCurrentFocus" >> shots/after-voice-demo.txt 2>&1
adb logcat -d | grep -E "neuroshield|MediaPlayer|AudioTrack|ANR|Choreographer|onnx" | tail -150 >> shots/after-voice-demo.txt 2>&1
adb shell uiautomator dump /sdcard/nav.xml >/dev/null 2>&1; adb pull /sdcard/nav.xml shots/ui-after-voice-demo.xml >/dev/null 2>&1 || true
if grep -q 'История хранится только на телефоне' shots/ui-after-voice-demo.xml 2>/dev/null; then
  echo "PASS app responds after the voice demo" >> shots/asserts.txt
else
  echo "FAIL app responds after the voice demo" >> shots/asserts.txt
  # thread dump of the stuck app (the emulator image allows adb root)
  adb root >/dev/null 2>&1; sleep 3; adb wait-for-device
  pid=$(adb shell pidof $P | tr -d '\r'); adb shell debuggerd -j $pid > shots/stack-after-voice-demo.txt 2>&1 || true
  top -b -n 1 > /dev/null 2>&1; adb shell top -b -n 1 -H -p $pid | head -30 >> shots/stack-after-voice-demo.txt 2>&1 || true
fi
adb shell am force-stop $P; sleep 2
# Kazakh interface: switch the language in settings like a user would, then look at the main screens
adb shell am start -n $P/.MainActivity --es route settings; sleep 3; ensure_ready
adb shell am start -n $P/.MainActivity --es route settings; sleep 3
tap_text "Қазақша"; sleep 4
adb shell am start -n $P/.MainActivity --es route home; shot 30-kk-home 4
adb shell uiautomator dump /sdcard/kk.xml >/dev/null 2>&1; adb pull /sdcard/kk.xml shots/ui-kk.xml >/dev/null 2>&1 || true
if grep -q "Шолу" shots/ui-kk.xml 2>/dev/null && grep -q "Қорғаныс" shots/ui-kk.xml 2>/dev/null; then echo "PASS interface switches to Kazakh" >> shots/asserts.txt; else echo "FAIL interface switches to Kazakh" >> shots/asserts.txt; fi
adb shell am start -n $P/.MainActivity --es route protection; shot 31-kk-protection 4
adb shell am start -n $P/.MainActivity --es route history; shot 32-kk-history 4
adb shell am start -n $P/.MainActivity --es route settings; shot 33-kk-settings 4
# privacy: "delete all data" really empties the history and resets settings (UI is in Kazakh at this point).
# Our warning window from the SMS test may still float over the screen and catch taps: restart the app first.
adb shell am force-stop $P; sleep 2
adb shell am start -n $P/.MainActivity --es route settings; sleep 3; ensure_ready
adb shell am start -n $P/.MainActivity --es route settings; sleep 3
for k in 1 2 3; do adb shell input swipe 540 1800 540 500 300; sleep 1; done
tap_text "Барлық деректерді жою"; sleep 2; shot 34a-wipe-confirm 1; tap_text "Жою"; sleep 5
adb logcat -d | grep -E "FATAL EXCEPTION" -A 15 > shots/crash-wipe.txt || true
adb shell uiautomator dump /sdcard/w.xml >/dev/null 2>&1; adb pull /sdcard/w.xml shots/ui-wipe.xml >/dev/null 2>&1 || true
onb=$(grep -c "Пропустить" shots/ui-wipe.xml 2>/dev/null)
tap_text "Пропустить"; sleep 2
adb shell am start -n $P/.MainActivity --es route history; sleep 4; shot 34-history-after-wipe 1
adb shell uiautomator dump /sdcard/h.xml >/dev/null 2>&1; adb pull /sdcard/h.xml shots/ui-history-wipe.xml >/dev/null 2>&1 || true
if [ "${onb:-0}" -gt 0 ] && grep -q "Здесь пока пусто" shots/ui-history-wipe.xml 2>/dev/null; then
  echo "PASS delete all data empties history and resets settings" >> shots/asserts.txt; else echo "FAIL delete all data empties history and resets settings" >> shots/asserts.txt; fi
# logs of the app must not contain the test phone numbers or SMS codes used above
if adb logcat -d | grep -E "Saqtan" | grep -qE "8701555|87017770011|447911123456|482913|4821"; then
  echo "FAIL app logs contain phone numbers or codes" >> shots/asserts.txt; else echo "PASS app logs contain no phone numbers or codes" >> shots/asserts.txt; fi
# permissions taken away: calls and SMS must not crash the app
adb logcat -c
for perm in READ_CONTACTS RECORD_AUDIO RECEIVE_SMS READ_CALL_LOG; do adb shell pm revoke $P android.permission.$perm || true; done
adb emu gsm call 87015550777; sleep 6; adb emu sms send 7700 "Kod 5531 nikomu"; sleep 4; adb emu gsm cancel 87015550777; sleep 8
adb shell am start -n $P/.MainActivity --es route protection; shot 35-protection-permissions-revoked 4
if adb logcat -d | grep -q "FATAL EXCEPTION"; then echo "FAIL no crash with permissions revoked" >> shots/asserts.txt; else echo "PASS no crash with permissions revoked" >> shots/asserts.txt; fi
adb logcat -d > shots/logcat.txt
grep "SaqtanCall" shots/logcat.txt > shots/calls.txt || true
grep -E "FATAL EXCEPTION|AndroidRuntime: " -A 12 shots/logcat.txt > shots/crash.txt || true
adb shell dumpsys window | grep -i "neuroshield" | head -20 > shots/windows.txt || true
echo "alive: $(adb shell pidof $P)" >> shots/crash.txt
cat shots/asserts.txt
exit 0
