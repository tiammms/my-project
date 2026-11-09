"""Draft radar summary as a self-contained HTML page (everything escaped: the text comes from users and a model)."""
from __future__ import annotations

import html
import time

WHO = {"bank": "банк", "police": "полиция / КНБ / прокуратура", "government": "госорган, ЦОН, eGov", "operator": "оператор связи",
       "relative": "родственник или знакомый", "shop_delivery": "магазин, доставка, объявления", "employer": "работодатель", "other": "другое"}


def render(report: dict, phrases: list[dict], model: str, source_note: str = "") -> str:
    e = lambda s: html.escape(str(s))
    by_id = {p["id"]: p for p in phrases}
    rows = []
    for i, s in enumerate(report["schemes"], 1):
        ex = "".join(f"<li><span class=l>{e(by_id[p]['lang'])}</span> {e(by_id[p]['text'])}</li>" for p in s["phrase_ids"][:3] if p in by_id)
        rows.append(f"""<section class="sc"><div class="h"><b>{i}. {e(s['title_ru'])}</b><span class="n">{s['count']} фраз</span>
{'<span class="new">новая</span>' if s['is_new'] else ''}</div><p class="kk">{e(s['title_kk'])}</p>
<p>{e(s['how_it_works_ru'])}</p><p class="m">Кем представляются: {e(WHO.get(s['impersonates'], s['impersonates']))}{(' · ' + e(', '.join(s['organisations']))) if s['organisations'] else ''}</p>
<p class="m">Слова для распознавания: {e(', '.join(s['keywords']))}</p><p class="adv">Совет: {e(s['advice_ru'])}</p><ul>{ex}</ul></section>""")
    return f"""<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Радар схем Saqtan</title><style>
body{{font:16px/1.5 system-ui,Segoe UI,Arial,sans-serif;margin:0;background:#f3f6fd;color:#14213d}}
main{{max-width:980px;margin:0 auto;padding:28px 18px 60px}} h1{{margin:0 0 4px;font-size:30px}}
.draft{{background:#fff4e0;border:1px solid #f0b85a;border-radius:12px;padding:10px 14px;margin:14px 0}}
.sum{{background:#1d4ed8;color:#fff;border-radius:14px;padding:16px 18px;margin:14px 0}} .sum p{{margin:4px 0}}
.sc{{background:#fff;border-radius:14px;padding:14px 18px;margin:12px 0;box-shadow:0 4px 16px rgba(20,33,61,.06)}}
.h{{display:flex;gap:10px;align-items:center;flex-wrap:wrap}} .h b{{font-size:19px}} .n{{background:#e8eefc;border-radius:99px;padding:2px 10px;font-size:13px}}
.new{{background:#e5484d;color:#fff;border-radius:99px;padding:2px 10px;font-size:13px}} .kk{{color:#5c6b85;margin:2px 0 8px}}
.m{{color:#3d4a63;font-size:14px;margin:4px 0}} .adv{{color:#1f7a4c;font-weight:600}} ul{{margin:6px 0 0;padding-left:18px;color:#3d4a63;font-size:14px}}
.l{{background:#eef1f6;border-radius:6px;padding:0 6px;font-size:12px;margin-right:4px}} .foot{{color:#5c6b85;font-size:13px}}
</style></head><body><main>
<h1>Радар схем Saqtan</h1><div class="foot">{e(time.strftime('%d.%m.%Y %H:%M'))} · фраз: {len(phrases)} · модель: {e(model)}{(' · ' + e(source_note)) if source_note else ''}</div>
<div class="draft"><b>Черновик.</b> Составлен автоматически моделью Gemini по обезличенным фразам. Перед обновлением правил и
отправкой в банки его проверяет человек: модель может ошибаться.</div>
<div class="sum"><p>{e(report['summary_ru'])}</p><p>{e(report['summary_kk'])}</p></div>
{''.join(rows) or '<p>Схем не найдено.</p>'}
<p class="foot">Не отнесены к схемам: {len(report.get('unassigned', []))} фраз. В фразах нет номеров, сумм и имён: они удалены до анализа.</p>
</main></body></html>"""
