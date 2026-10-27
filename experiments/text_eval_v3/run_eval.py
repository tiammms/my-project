"""Third test set (v3): bank/SMS/code, eGov/ЭЦП, operator/SIM, loan cancellation, compensation schemes.
A 'warning' = MEDIUM or HIGH. Usage: python -m experiments.text_eval_v3.run_eval (from repo root)."""
import csv, json, pathlib, collections
from backend.app.biometrics import scam_text

here = pathlib.Path(__file__).parent
rows = list(csv.DictReader(open(here / "cases.csv", encoding="utf-8")))
res = collections.defaultdict(lambda: collections.Counter())
details = []
for r in rows:
    out = scam_text.analyze(r["text"])
    lvl = out["risk_level"]; warn = lvl != "LOW"
    res[(r["label"], r["lang"])][lvl] += 1
    res[(r["label"], "all")][lvl] += 1
    details.append({**r, "level": lvl, "score": out["risk_score"], "ok": warn == (r["label"] == "scam")})
summary = {f"{k[0]}/{k[1]}": dict(v) for k, v in sorted(res.items())}
scam = res[("scam", "all")]; norm = res[("normal", "all")]
ns, nn = sum(scam.values()), sum(norm.values())
summary["scam_warned"] = f"{ns - scam['LOW']}/{ns}"
summary["scam_high"] = f"{scam['HIGH']}/{ns}"
summary["normal_false_warnings"] = f"{nn - norm['LOW']}/{nn}"
summary["errors"] = [d for d in details if not d["ok"]]
json.dump(summary, open(here / "results.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(json.dumps(summary, ensure_ascii=False, indent=1))
