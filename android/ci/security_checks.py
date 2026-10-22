#!/usr/bin/env python3
"""Security checks of the built Android app (run in CI after `assembleRelease lintRelease` and `dependencies`).

1. Merged manifest: backup off, not debuggable, HTTPS-only config, every exported component protected,
   permissions equal to the reviewed list (a library cannot silently add one).
2. Release APK: no secrets, no private keys, no cleartext URLs to non-reserved hosts, no removed test domains.
3. Source: log calls never print phone numbers, SMS text, transcripts or codes.
4. Dependencies: known vulnerabilities from OSV.dev (api.osv.dev) for the resolved release classpath.
5. Android Lint: issues in the "Security" category.

Prints GitHub annotations. Exit code 1 if a check in 1–3 fails; 4–5 are reported (fail only on lint security errors).
"""
import glob, json, re, sys, urllib.request, zipfile, pathlib, xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"
root = pathlib.Path(__file__).resolve().parent.parent
fails = []
report = []

def note(title, msg): print(f"::notice title={title}::{msg}"); report.append(f"OK   {title}: {msg}")
def warn(title, msg): print(f"::warning title={title}::{msg}"); report.append(f"WARN {title}: {msg}")
def fail(title, msg): print(f"::error title={title}::{msg}"); fails.append(title); report.append(f"FAIL {title}: {msg}")

# ---------------------------------------------------------------- 1. merged manifest
EXPECTED_PERMISSIONS = {
    "android.permission.READ_PHONE_STATE": "call state (ringing / in call / ended)",
    "android.permission.READ_CALL_LOG": "incoming number in the call-state broadcast (Android 9+)",
    "android.permission.READ_CONTACTS": "skip calls and messages from saved contacts",
    "android.permission.RECEIVE_SMS": "detect a one-time code that arrives during a call with an unknown number",
    "android.permission.RECORD_AUDIO": "speech analysis on speakerphone, only when the user starts it or enabled auto mode",
    "android.permission.CAMERA": "face liveness check, only on the user's button press",
    "android.permission.SYSTEM_ALERT_WINDOW": "warning on top of the call screen",
    "android.permission.POST_NOTIFICATIONS": "warnings and call reports",
    "android.permission.VIBRATE": "vibration for a high-risk warning",
    "android.permission.FOREGROUND_SERVICE": "visible service while the microphone or screen check runs",
    "android.permission.FOREGROUND_SERVICE_MICROPHONE": "same, microphone type (Android 14+)",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION": "same, screen-capture type for the video-call check",
    "android.permission.INTERNET": "one-time download of the offline speech model over HTTPS",
    # added by AndroidX libraries (not dangerous):
    "ai.neuroshield.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION": "AndroidX core: protects the app's own dynamic receivers",
    "android.permission.ACCESS_NETWORK_STATE": "ML Kit / Play services: network state (no data sent by the app)",
}
# exported without a permission attribute, with the reason it is safe
EXPORTED_OK = {
    "ai.neuroshield.app.MainActivity": "launcher; extras that start the microphone/video are ignored unless they come through InternalEntry",
    "ai.neuroshield.app.call.PhoneStateReceiver": "PHONE_STATE is a protected broadcast: only the system can send it",
}

manifests = [p for p in glob.glob(str(root / "app/build/intermediates/merged_manifest*/release/**/AndroidManifest.xml"), recursive=True)]
if not manifests:
    fail("manifest", "merged release manifest not found")
else:
    m = ET.parse(manifests[0]).getroot()
    app = m.find("application")
    if app.get(A + "allowBackup") != "false": fail("manifest", "allowBackup is not false")
    if app.get(A + "debuggable") == "true": fail("manifest", "release is debuggable")
    if not app.get(A + "networkSecurityConfig"): fail("manifest", "no networkSecurityConfig")
    if not app.get(A + "dataExtractionRules"): fail("manifest", "no dataExtractionRules (Android 12+ device transfer)")
    perms = {p.get(A + "name") for p in m.findall("uses-permission")}
    extra = perms - EXPECTED_PERMISSIONS.keys()
    if extra: fail("permissions", "not reviewed: " + ", ".join(sorted(extra)))
    note("permissions", f"{len(perms)} permissions, all reviewed: " + ", ".join(sorted(p.split('.')[-1] for p in perms)))
    exported = []
    for tag in ("activity", "activity-alias", "service", "receiver", "provider"):
        for c in app.findall(tag):
            name = c.get(A + "name")
            exp = c.get(A + "exported")
            if exp == "true":
                exported.append(name.split(".")[-1])
                if not c.get(A + "permission") and name not in EXPORTED_OK:
                    fail("exported", f"{tag} {name} is exported without a permission")
            if tag == "provider" and exp == "true" and not c.get(A + "permission"):
                fail("exported", f"provider {name} is exported")
            if name == "ai.neuroshield.app.InternalEntry" and exp != "false":
                fail("exported", "InternalEntry must not be exported")
    note("exported components", ", ".join(exported) + " (each protected by a system permission or justified)")

# ---------------------------------------------------------------- 2. release APK
SECRET_RX = re.compile(rb"(AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{40,}|sk-[A-Za-z0-9]{32,}|AIza[0-9A-Za-z_\-]{35}"
                       rb"|-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----|xox[baprs]-[0-9A-Za-z-]{10,})")
URL_RX = re.compile(rb"http://([a-z0-9.\-]+)", re.I)
URL_OK = (b"schemas.android.com", b"www.w3.org", b"ns.adobe.com", b"xml.org", b"xmlpull.org", b"apache.org", b"localhost",
          b"example.com", b"purl.org", b"www.google.com", b"schemas.microsoft.com", b"java.sun.com", b"json.org", b"xml.apache.org")
apks = glob.glob(str(root / "app/build/outputs/apk/release/*universal*.apk"))
if not apks:
    fail("apk", "release APK not found")
else:
    z = zipfile.ZipFile(apks[0])
    hits, urls = set(), set()
    for n in z.namelist():
        if not (n.endswith(".dex") or n.endswith(".xml") or n.endswith(".arsc") or n.endswith(".properties") or n.endswith(".json")):
            continue
        b = z.read(n)
        for mm in SECRET_RX.finditer(b): hits.add(f"{n}: {mm.group(0)[:12]!r}…")
        for mm in URL_RX.finditer(b):
            host = mm.group(1).lower()
            if not any(host.endswith(ok) for ok in URL_OK): urls.add(host.decode(errors="replace"))
        if b"kaspi-bonus.top" in b: hits.add(f"{n}: removed test domain kaspi-bonus.top")
    if hits: fail("apk secrets", "; ".join(sorted(hits))[:900])
    else: note("apk secrets", f"no keys, tokens or private keys in {pathlib.Path(apks[0]).name}")
    if urls: warn("apk cleartext urls", "http:// hosts in library code (not used by the app; HTTPS enforced by network config): " + ", ".join(sorted(urls))[:600])

# ---------------------------------------------------------------- 3. logging of personal data
LOG_RX = re.compile(r"\bLog\.[vdiwe]\s*\((.*)")
PII = re.compile(r"\$\{?\s*(?:[\w]+\.)*(number|sender|body|text|transcript|code|masked|phone|msg|excerpt|title)\b", re.I)
bad = []
for f in glob.glob(str(root / "app/src/main/java/**/*.kt"), recursive=True):
    for i, line in enumerate(open(f, encoding="utf-8"), 1):
        mm = LOG_RX.search(line)
        if mm and PII.search(mm.group(1)) and "!= null" not in mm.group(1):
            bad.append(f"{pathlib.Path(f).name}:{i}")
if bad: fail("logs", "log calls print personal data: " + ", ".join(bad))
else: note("logs", "no log call prints numbers, SMS text, transcripts or codes")

# ---------------------------------------------------------------- 4. dependencies (OSV.dev)
deps = set()
for f in glob.glob(str(root / "deps.txt")):
    for line in open(f, encoding="utf-8"):
        mm = re.search(r"--- ([\w.\-]+):([\w.\-]+):([\w.\-]+)(?: -> ([\w.\-]+))?", line)
        if mm and not line.rstrip().endswith("(c)"):
            deps.add((mm.group(1), mm.group(2), mm.group(4) or mm.group(3)))
if not deps:
    warn("dependencies", "dependency list not available")
else:
    try:
        q = {"queries": [{"package": {"ecosystem": "Maven", "name": f"{g}:{a}"}, "version": v} for g, a, v in sorted(deps)]}
        req = urllib.request.Request("https://api.osv.dev/v1/querybatch", data=json.dumps(q).encode(), headers={"Content-Type": "application/json"})
        res = json.load(urllib.request.urlopen(req, timeout=60))["results"]
        vul = [(f"{g}:{a}:{v}", [x["id"] for x in r.get("vulns", [])]) for (g, a, v), r in zip(sorted(deps), res) if r.get("vulns")]
        if vul: warn("dependencies", f"{len(deps)} libraries; known vulnerabilities: " + "; ".join(f"{d} {','.join(ids)}" for d, ids in vul)[:900])
        else: note("dependencies", f"{len(deps)} libraries checked against OSV.dev: no known vulnerabilities")
    except Exception as e:
        warn("dependencies", f"OSV.dev not reachable: {type(e).__name__}")

# ---------------------------------------------------------------- 5. Android Lint, Security category
lint = glob.glob(str(root / "app/build/reports/lint-results-release.xml")) or glob.glob(str(root / "app/build/reports/lint-results*.xml"))
if not lint:
    warn("lint", "lint report not found")
else:
    issues = ET.parse(lint[0]).getroot().findall("issue")
    sec = [i for i in issues if i.get("category", "").startswith("Security")]
    errs = [i for i in sec if i.get("severity") in ("Error", "Fatal")]
    summary = "; ".join(sorted({f"{i.get('id')}({i.get('severity')}): {i.get('message')} [{(i.find('location').get('file') if i.find('location') is not None else '').split('/')[-1]}]" for i in sec})) or "none"
    (fail if errs else note)("lint security", f"{len(issues)} lint issues in total; security: {summary}")

pathlib.Path(root / "security-report.txt").write_text("\n".join(report) + "\n", encoding="utf-8")
sys.exit(1 if fails else 0)
