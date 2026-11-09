"""Public set: scammer phrases quoted in publications by police, the MVD RK, the Bank of Russia and finance media
(phrases.csv, source URL per row). Not written by the team, never used to tune the rules.
Each phrase is ONE line of a call, so two measures are reported:
  warned  - MEDIUM or HIGH risk from this single line (by design one tactic alone stays below HIGH, and a mild one
            such as urgency or secrecy alone stays LOW: a real call adds up several lines);
  noticed - at least one scam tactic recognised in the line (shown to the user as a reason in the call report).
Usage: python -m experiments.text_eval_public.run_eval (from repo root)."""
import csv, json, pathlib, collections
from backend.app.biometrics import scam_text

here = pathlib.Path(__file__).parent


def run() -> dict:
    rows = list(csv.DictReader(open(here / "phrases.csv", encoding="utf-8")))
    c = collections.defaultdict(collections.Counter); out = []
    for r in rows:
        a = scam_text.analyze(r["text"]); lvl = a["risk_level"]
        tactics = [t["category"] for t in a.get("tactics", []) if t["category"] != "sensitive_topic"]
        g = c[r["lang"]]; g["n"] += 1
        g["warned"] += lvl != "LOW"; g["high"] += lvl == "HIGH"; g["noticed"] += bool(tactics)
        out.append({"lang": r["lang"], "source": r["source"], "text": r["text"], "level": lvl, "score": a["risk_score"], "tactics": tactics})
    tot = collections.Counter(); [tot.update(g) for g in c.values()]
    fmt = lambda g: {"warned": f"{g['warned']}/{g['n']}", "high": f"{g['high']}/{g['n']}", "noticed": f"{g['noticed']}/{g['n']}"}
    return {"by_language": {k: fmt(g) for k, g in sorted(c.items())}, "total": fmt(tot), "phrases": out}


if __name__ == "__main__":
    print(json.dumps(run(), ensure_ascii=False, indent=1))
