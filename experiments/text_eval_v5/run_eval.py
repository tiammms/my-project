"""Set v5: Kazakh speech and retold calls ("мне позвонили и попросили код…") in Russian and Kazakh.
  holdout.csv - written and committed BEFORE the v5 rule changes; never used to tune rules, only measured.
  dev.csv     - the rules may be tuned on it.
A 'warning' = MEDIUM or HIGH. Usage: python -m experiments.text_eval_v5.run_eval [dev|holdout] (from repo root)."""
import csv, json, sys, pathlib, collections
from backend.app.biometrics import scam_text

here = pathlib.Path(__file__).parent


def run(name: str) -> dict:
    rows = list(csv.DictReader(open(here / f"{name}.csv", encoding="utf-8")))
    groups = collections.defaultdict(collections.Counter); errors = []
    for r in rows:
        out = scam_text.analyze(r["text"]); lvl = out["risk_level"]
        g = groups[f"{r['lang']}/{r['kind']}"]
        g[r["label"]] += 1
        if r["label"] == "scam" and lvl != "LOW": g["warned"] += 1
        if r["label"] == "scam" and lvl == "HIGH": g["high"] += 1
        if r["label"] == "normal" and lvl != "LOW": g["false"] += 1
        if (lvl != "LOW") != (r["label"] == "scam"):
            errors.append({"group": f"{r['lang']}/{r['kind']}", "label": r["label"], "text": r["text"], "level": lvl, "score": out["risk_score"]})
    res = {k: {"scam_warned": f"{g['warned']}/{g['scam']}", "scam_high": f"{g['high']}/{g['scam']}",
               "normal_false_warnings": f"{g['false']}/{g['normal']}"} for k, g in sorted(groups.items())}
    tot = collections.Counter(); [tot.update(g) for g in groups.values()]
    res["total"] = {"scam_warned": f"{tot['warned']}/{tot['scam']}", "scam_high": f"{tot['high']}/{tot['scam']}",
                    "normal_false_warnings": f"{tot['false']}/{tot['normal']}"}
    return {"set": name, "groups": res, "errors": errors}


if __name__ == "__main__":
    print(json.dumps(run(sys.argv[1] if len(sys.argv) > 1 else "dev"), ensure_ascii=False, indent=1))
