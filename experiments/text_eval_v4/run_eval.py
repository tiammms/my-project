"""Set v4: Kazakh and mixed Kazakh-Russian speech. Two files:
  dev.csv     - the rules may be tuned on it;
  holdout.csv - written and saved BEFORE the rule changes; never used to tune rules, only measured.
A 'warning' = MEDIUM or HIGH. Usage: python -m experiments.text_eval_v4.run_eval [dev|holdout] (from repo root)."""
import csv, json, sys, pathlib, collections
from backend.app.biometrics import scam_text

here = pathlib.Path(__file__).parent


def run(name: str) -> dict:
    rows = list(csv.DictReader(open(here / f"{name}.csv", encoding="utf-8")))
    c = collections.Counter(); errors = []
    for r in rows:
        out = scam_text.analyze(r["text"]); lvl = out["risk_level"]
        c[(r["label"], lvl)] += 1
        if (lvl != "LOW") != (r["label"] == "scam"):
            errors.append({"label": r["label"], "text": r["text"], "level": lvl, "score": out["risk_score"]})
    ns = sum(v for (l, _), v in c.items() if l == "scam"); nn = sum(v for (l, _), v in c.items() if l == "normal")
    return {"set": name, "scam_warned": f"{ns - c[('scam', 'LOW')]}/{ns}", "scam_high": f"{c[('scam', 'HIGH')]}/{ns}",
            "normal_false_warnings": f"{nn - c[('normal', 'LOW')]}/{nn}", "errors": errors}


if __name__ == "__main__":
    print(json.dumps(run(sys.argv[1] if len(sys.argv) > 1 else "dev"), ensure_ascii=False, indent=1))
