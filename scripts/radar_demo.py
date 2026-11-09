"""Scheme radar demo: 48 real scam phrases from police and bank publications -> anonymised -> Gemini -> draft summary.

Run from the project folder:  python -m scripts.radar_demo      (Windows: double-click radar_demo_windows.bat)
Needs only Python and the GEMINI_API_KEY environment variable. Nothing else is installed.
Writes backend/data/radar_report.html and opens it in the browser.
"""
from __future__ import annotations

import csv
import sys
import webbrowser
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from backend.app.radar import gemini                     # noqa: E402
from backend.app.radar.analyze import analyze             # noqa: E402
from backend.app.radar.anonymize import anonymize         # noqa: E402
from backend.app.radar.report import render               # noqa: E402

PHRASES = ROOT / "experiments/text_eval_public/phrases.csv"
OUT = ROOT / "backend/data/radar_report.html"

NO_KEY = """
Ключ Gemini не найден (переменная среды GEMINI_API_KEY пуста).
  Windows: Пуск -> «Изменение переменных среды текущего пользователя» -> Создать:
           имя GEMINI_API_KEY, значение - ваш ключ. Потом закройте это окно и запустите снова.
           Если всё равно не видно - выйдите из Windows и войдите заново.
Ключ никуда не пишите в файлы проекта и никому не присылайте.
"""


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass
    rows = list(csv.DictReader(PHRASES.open(encoding="utf-8")))
    phrases = [{"id": i, "lang": "kk" if r["lang"] == "kz" else r["lang"], "source": "manual", "text": anonymize(r["original"])}
               for i, r in enumerate(rows, 1)]
    print(f"Фраз из публикаций полиции и банков: {len(phrases)}. После обезличивания, например:")
    pairs = list(zip(rows, phrases))
    changed = [x for x in pairs if " ".join(x[0]["original"].split()) != x[1]["text"]]
    for r, p in (changed or pairs)[:3]:
        print(f"  было:  {r['original'][:110]}\n  стало: {p['text'][:110]}")
    if not gemini.api_key():
        print(NO_KEY)
        return 2
    print("\nОтправляю обезличенные фразы в Gemini (ключ берётся из переменной среды, в файлы не пишется)...")
    try:
        report, model = analyze(phrases)
    except gemini.GeminiError as e:
        print(f"\nОшибка: {e}")
        return 1
    print(f"\nМодель: {model}\n{report['summary_ru']}\n")
    for i, s in enumerate(report["schemes"], 1):
        print(f"{i}. {s['title_ru']} — {s['count']} фраз{' (НОВАЯ)' if s['is_new'] else ''}")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(render(report, phrases, model, "демо на публичных фразах из СМИ"), encoding="utf-8")
    print(f"\nСводка: {OUT}")
    try:
        webbrowser.open(OUT.as_uri())
    except Exception:
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
