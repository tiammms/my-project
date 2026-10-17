#!/bin/bash
# Builds the app for the iOS Simulator, runs it and takes screenshots of every screen and of the demo scenario.
set -u
cd "$(dirname "$0")/.."
mkdir -p shots
UDID=$(xcrun simctl list devices available -j | python3 -c "
import json,sys
d=json.load(sys.stdin)['devices']
c=[x for r,l in d.items() if 'iOS' in r for x in l if x['name'].startswith('iPhone') and 'Pro Max' not in x['name'] and 'Plus' not in x['name']]
c.sort(key=lambda x: x['name'], reverse=True); print(c[0]['udid'])")
echo "simulator: $UDID"
xcrun simctl boot "$UDID" || true
xcrun simctl bootstatus "$UDID" -b
xcrun simctl status_bar "$UDID" override --time "9:41" --batteryLevel 100 --cellularBars 4 || true
APP=build/Build/Products/Debug-iphonesimulator/NeuroShield.app
xcrun simctl install "$UDID" "$APP"
B=ai.neuroshield.app
shot() { sleep "${2:-3}"; xcrun simctl io "$UDID" screenshot "shots/$1.png" >/dev/null 2>&1; echo "shot $1"; }
run() { xcrun simctl terminate "$UDID" $B >/dev/null 2>&1; xcrun simctl launch "$UDID" $B -skipOnboarding YES "$@" >/dev/null; }

xcrun simctl launch "$UDID" $B >/dev/null; shot 00-onboarding 5
run; shot 01-home 4
run -tab protection; shot 02-protection 4
run -route text; shot 03-text 4
run -route face; shot 04-face 4
run -tab settings; shot 05-settings 4
run -route demo; shot 06-demo 4
run -route demo -autoplay YES; shot 07-demo-call 3
shot 08-demo-speech 13
shot 09-demo-speech-alert 5
shot 10-demo-sms 8
shot 11-demo-phishing 5
shot 12-demo-finished 8
run -tab history; shot 13-history 4
run; shot 14-home-with-threats 4
# crash check
if xcrun simctl spawn "$UDID" launchctl list | grep -q "$B"; then echo "alive: yes" > shots/status.txt; else echo "alive: NO" > shots/status.txt; fi
ls ~/Library/Logs/DiagnosticReports 2>/dev/null | grep -i neuroshield >> shots/status.txt || true
exit 0
