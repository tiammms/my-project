"""Generates the scam-phrase rules for the Android app, the iOS app and the server from ONE file:
shared/scam_rules.json. Run after editing the JSON:  python scripts/gen_scam_rules.py
CI runs it with --check and fails if a generated file is out of date, so the three copies cannot drift apart.
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "shared" / "scam_rules.json"
KT = ROOT / "android/app/src/main/java/ai/neuroshield/app/core/ScamRules.kt"
SWIFT = ROOT / "ios/Core/ScamRules.swift"
PY = ROOT / "backend/app/biometrics/scam_rules.py"
HEAD = "GENERATED from shared/scam_rules.json by scripts/gen_scam_rules.py — do not edit by hand."


def q(s: str) -> str:
    return json.dumps(s, ensure_ascii=False)


def kotlin(d) -> str:
    out = [f"// {HEAD}", "package ai.neuroshield.app.core", "",
           "internal class ScamRule(val id: String, val title: String, val advice: String, val weight: Int, val weak: Boolean, val patterns: List<String>)",
           "", "internal object ScamRules {",
           "    val CRITICAL = setOf(" + ", ".join(q(c) for c in d["critical"]) + ")",
           "    val CATEGORIES = listOf("]
    for c in d["categories"]:
        out.append(f"        ScamRule({q(c['id'])}, {q(c['title'])},\n            {q(c['advice'])}, {c['weight']}, {str(c.get('weak', False)).lower()}, listOf(")
        for p in c["patterns"]:
            assert '"""' not in p
            out.append('                """' + p.replace("$", "${'$'}") + '""",')
        out.append("            )),")
    out += ["    )", "}", ""]
    return "\n".join(out)


def swift(d) -> str:
    out = [f"// {HEAD}", "", "struct ScamRule { let id: String; let title: String; let advice: String; let weight: Int; let weak: Bool; let patterns: [String] }",
           "", "enum ScamRules {",
           "    static let critical: Set<String> = [" + ", ".join(q(c) for c in d["critical"]) + "]",
           "    static let categories: [ScamRule] = ["]
    for c in d["categories"]:
        out.append(f"        ScamRule(id: {q(c['id'])}, title: {q(c['title'])},\n                 advice: {q(c['advice'])}, weight: {c['weight']}, weak: {str(c.get('weak', False)).lower()}, patterns: [")
        for p in c["patterns"]:
            assert '"#' not in p
            out.append('            #"' + p + '"#,')
        out.append("        ]),")
    out += ["    ]", "}", ""]
    return "\n".join(out)


def python(d) -> str:
    out = [f'"""{HEAD}"""', "", "CRITICAL = {" + ", ".join(q(c) for c in d["critical"]) + "}", "", "CATEGORIES = {"]
    for c in d["categories"]:
        out.append(f"    {q(c['id'])}: {{\n        \"title\": {q(c['title'])},\n        \"advice\": {q(c['advice'])},\n"
                   f"        \"weight\": {c['weight']}, \"weak\": {c.get('weak', False)},\n        \"patterns\": [")
        for p in c["patterns"]:
            out.append("            " + repr(p) + ",")
        out.append("        ]},")
    out += ["}", ""]
    return "\n".join(out)


def main():
    d = json.loads(SRC.read_text(encoding="utf-8"))
    ids = [c["id"] for c in d["categories"]]
    assert len(ids) == len(set(ids)), "duplicate category id"
    for c in d["categories"]:
        for p in c["patterns"]:
            re.compile(p)                      # every pattern must at least compile
    files = {KT: kotlin(d), SWIFT: swift(d), PY: python(d)}
    if "--check" in sys.argv:
        stale = [str(f.relative_to(ROOT)) for f, t in files.items() if not f.exists() or f.read_text(encoding="utf-8") != t]
        if stale:
            sys.exit("out of date, run python scripts/gen_scam_rules.py: " + ", ".join(stale))
        print("scam rules: generated files are up to date")
        return
    for f, t in files.items():
        f.write_text(t, encoding="utf-8")
    print("written:", ", ".join(str(f.relative_to(ROOT)) for f in files))


if __name__ == "__main__":
    main()
