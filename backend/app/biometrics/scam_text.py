"""Rule-based analysis of what is SAID in a call (transcript or pasted text).

It looks for well-known social-engineering tactics (asking for codes, "safe account", urgency, posing as the
bank or police, secrecy, remote-access apps). It is transparent and rule-based, not a trained model.
It assesses the content of the conversation - it never labels the speaker as a fraudster.
Languages: Russian, Kazakh, English (case-insensitive, ё = е).
"""
from __future__ import annotations

import re

from . import link_check

CATEGORIES: dict[str, dict] = {
    "credentials_request": {
        "title": "Просят код, пароль или данные карты",
        "advice": "Никому не сообщайте коды из SMS, пароли, PIN, CVV. Банк никогда их не спрашивает.",
        "weight": 45,
        "patterns": [
            r"код\w*\s+(из|с|в)\s+(смс|sms|сообщени)", r"(продиктуй|назови|скажи|сообщи|скинь|отправь|подтверди)\w*\s+(мне\s+)?(пожалуйста\s+)?(этот\s+|ваш\s+|свой\s+)?код",
            r"код\w*\s+подтвержд", r"\bcvv\b|\bcvc\b|\bсвв\b|три\s+цифры\s+на\s+обороте|три\s+цифры\s+сзади",
            r"пин[\s-]?код|\bpin\b", r"(ваш|свой|назовите)\s+пароль|пароль\s+от", r"срок\s+действия\s+карты",
            r"номер\s+(вашей\s+)?карты", r"кодты?\s+(айт|жібер)", r"құпия\s+сөз",
            r"(tell|give|read|send)\s+(me\s+)?(the\s+|your\s+)?(code|password|pin)", r"verification\s+code|one[\s-]time\s+(code|password)",
            r"подтвердите\s+(ваши\s+|свои\s+)?(данные|личност)",
        ]},
    "safe_account_transfer": {
        "title": "Просят перевести деньги на «безопасный» или чужой счёт",
        "advice": "«Безопасных» и «резервных» счетов не существует. Не переводите деньги по просьбе звонящего.",
        "weight": 40,
        "patterns": [
            r"(безопасн|резервн|защищенн|специальн|страхов)\w*\s+(сч[её]т|ячейк)", r"(перевед|переведит|перечисл|отправ)\w*\s+(все\s+|ваши\s+)?(деньги|средства|сбережения|сумм)",
            r"(снимит|снять|обналичь)\w*\s+(все\s+)?(деньги|наличн|средства)", r"через\s+банкомат\s+(на|внес)", r"задеклариру",
            r"қауіпсіз\s+шот", r"ақша\w*\s+аудар",
            r"safe\s+account|secure\s+account|transfer\s+(all\s+)?(your\s+)?(money|funds|savings)",
        ]},
    "urgency_pressure": {
        "title": "Давят срочностью",
        "advice": "Срочность — главный инструмент мошенников. Сделайте паузу: настоящий банк подождёт.",
        "weight": 20,
        "patterns": [
            r"\bсрочно\b", r"немедленно", r"прямо\s+сейчас", r"в\s+течени[ие]\s+\d+\s+минут", r"(у\s+вас\s+)?(нет|мало)\s+времени",
            r"иначе\s+(вы\s+)?(потеря|лишит|деньги|сч[её]т|будет)", r"пока\s+не\s+поздно", r"последн\w+\s+(шанс|возможност)",
            r"не\s+кладите\s+трубку", r"жедел|дереу|қазір\s+бірден",
            r"\burgent\b|immediately|right\s+now|within\s+\d+\s+minutes|before\s+it'?s\s+too\s+late",
            r"оставайтесь\s+на\s+линии|не\s+(прерывайте|отключайтесь|отключайте)", r"stay\s+on\s+the\s+line|do\s+not\s+hang\s+up|don'?t\s+hang\s+up",
        ]},
    "authority_impersonation": {
        "title": "Представляются банком, полицией или госорганом",
        "advice": "Положите трубку и сами перезвоните в банк по номеру на обратной стороне карты.",
        "weight": 25,
        "patterns": [
            r"служб\w+\s+безопасности(\s+банка)?", r"(сотрудник|специалист|оператор)\w*\s+(банка|службы)", r"\bполици\w*", r"следовател",
            r"(национальн|центральн)\w*\s+банк|\bнацбанк|\bцб\s+рф|\bафм\b|\bкнб\b|\bфсб\b|прокуратур|\bмвд\b",
            r"банктің\s+қауіпсіздік|полиция\s+қызметкері",
            r"(bank'?s?\s+)?security\s+(department|team|service)|\bpolice\b|fraud\s+department",
            r"служб\w+\s+поддержки|(сотрудник|служб\w*|специалист)\w*\s+(kaspi|каспи|halyk|халык)", r"банк\s+қызметкер",
        ]},
    "secrecy_isolation": {
        "title": "Просят держать разговор в тайне",
        "advice": "Требование никому не говорить — тревожный признак. Обсудите ситуацию с близкими.",
        "weight": 20,
        "patterns": [
            r"никому\s+не\s+(говорите|рассказывайте|сообщайте)", r"(тайн\w+|секрет\w*)\s+следстви", r"не\s+(говорите|сообщайте)\s+(родственникам|близким|сотрудникам\s+банка)",
            r"это\s+конфиденциальн", r"ешкімге\s+айтпаңыз",
            r"don'?t\s+tell\s+(anyone|your\s+family|the\s+bank)|keep\s+(this|it)\s+(secret|confidential)",
            r"не\s+говори\s+(маме|папе|никому)|никому\s+не\s+говори", r"don'?t\s+tell\s+(mom|dad|your\s+parents)",
        ]},
    "remote_access": {
        "title": "Просят установить программу или показать экран",
        "advice": "Не устанавливайте программы удалённого доступа по просьбе звонящего.",
        "weight": 35,
        "patterns": [
            r"anydesk|эни\s?деск|teamviewer|тимвьюер|rustdesk|rust\s+desk|quick\s?support", r"(установит|скачайт|скачать|установить)\w*\s+(приложени|программ)",
            r"демонстраци\w+\s+экрана|покажите\s+(ваш\s+)?экран", r"screen\s+shar|install\s+(the\s+)?(app|application)|remote\s+access",
        ]},
    "threat_fear": {
        "title": "Пугают: блокировка, кредит на ваше имя, уголовное дело",
        "advice": "Не принимайте решения в панике. Проверьте информацию сами в приложении банка.",
        "weight": 15,
        "patterns": [
            r"(ваш\w*\s+)?(сч[её]т|карт\w+)\s+(заблокир|будет\s+заблокир)", r"(оформ|взял|взят)\w*\s+кредит\s+на\s+(ваше|вас)", r"подозрительн\w+\s+(операци|перевод)",
            r"уголовн|\bарест|соучастни", r"мошенники\s+пытаются", r"your\s+(\w+\s+)?(account|card)\s+(is|has\s+been|will\s+be)\s+(blocked|frozen|compromised|suspended)|arrest|loan\s+in\s+your\s+name",
            r"(аккаунт|профил|учетн\w+\s+запис)\w*\s+(\w+\s+)?(будет\s+)?заблокир", r"заявк\w+\s+на\s+кредит|кредит\w*\s+(будет\s+)?одобрен", r"шот\w*\s+бұғат", r"your\s+(\w+\s+)?(account|card)\s+is\s+being\s+(compromised|blocked|hacked)",
        ]},
    "family_emergency": {
        "title": "Рассказывают, что близкий человек в беде",
        "advice": "Положите трубку и сами перезвоните родственнику по его старому номеру. Задайте вопрос, ответ на который знает только он.",
        "weight": 35,
        "patterns": [
            r"это\s+я[\s,]+(тво[йя]|ваш[аи]?)\s+(сын|дочь|внук|внучка|племянни\w*)",
            r"(сын|дочь|дочка|внук|внучка|муж|жена|брат|сестра|ребен)\w*\s+(\w+\s+)?(в\s+больниц|в\s+реанимац|в\s+полици|задержа|попал|сбил|разбил)",
            r"(сбил|сбила)\s+(человека|пешехода)|попал\w*\s+в\s+(авари|дтп|беду)|после\s+авари",
            r"\bя\s+в\s+беде|на\s+операци\w*\s+(нужн|надо)|(у\s+меня|это\s+мой)\s+новый\s+номер|телефон\s+(украли|разбил|сломал)",
            r"бұл\s+мен[\s,]+(балаң|немерең|ұлың|қызың)|жол\s+апат|апатқа\s+түст",
            r"it'?s\s+me[\s,]+(your\s+)?(grandson|granddaughter|son|daughter)|grandma[\s,]+it'?s\s+me|i'?m\s+in\s+(jail|trouble|the\s+hospital)|bail\s+money|had\s+an\s+accident",
        ]},
    "money_request": {
        "title": "Просят срочно прислать деньги",
        "advice": "Прежде чем переводить, перезвоните человеку сами по номеру, который вы знаете.",
        "weight": 20,
        "patterns": [
            r"(кинь|скинь|переведи|перекинь|одолжи|займи|пришли)\w*[\s,]+(мне[\s,]+)?(пожалуйста[\s,]+)?(\d+|деньги|денег)",
            r"на\s+(эту|мою|этот|другую|новую)\s+(карт|номер)",
            r"передать\s+деньги|нужн\w*\s+\d+\s*(тысяч|тыс|тенге|₸)",
            r"ақша\s+жібер",
            r"send\s+(me\s+)?(the\s+|some\s+)?money|send\s+it\s+now|wire\s+(me\s+)?(the\s+)?money",
        ]},
    "investment_promise": {
        "title": "Обещают гарантированный или очень высокий доход",
        "advice": "Гарантированного высокого дохода не бывает. Проверьте компанию в реестре АРРФР и не вносите деньги по звонку или рекламе.",
        "weight": 35,
        "patterns": [
            r"гарантирован\w*\s+(доход|прибыл|выплат)|пассивн\w+\s+доход",
            r"\d+\s*%\s+(в|за)\s+(день|сутки|неделю|месяц)|в\s+(два|три|пять|\d+)\s+раза\s+больше",
            r"инвестиционн\w+\s+(программ|проект|платформ)|ежемесячн\w+\s+выплат|(вложите|вложить|внесите|инвестируйте)\s+(от\s+)?\d+",
            r"(выгодн|перспективн)\w*\s+криптовалют",
            r"\d+\s+пайыз\s+табыс|ақша\s+салыңыз",
            r"guaranteed\s+(return|profit|income)s?|\d+\s*%\s+(per|a)\s+(day|week|month)|invest\s+(today|now)",
        ]},
    "prize_delivery": {
        "title": "Сообщают о выигрыше или проблеме с посылкой",
        "advice": "Проверьте посылку по трек-номеру на официальном сайте службы доставки. Если вы ни в чём не участвовали, выигрыша нет.",
        "weight": 20,
        "patterns": [
            r"вы\s+выиграли|ваш\s+номер\s+выиграл|(получить|забрать)\s+приз|розыгрыш|выигрыш",
            r"посылк\w*\s+(\w+\s+)?задержан|задержан\w*\s+на\s+таможн|адрес\w*\s+доставки\s+указан\s+неверно|не\s+удалось\s+доставить",
            r"ұтыс|сыйлық",
            r"you\s+(have\s+)?won|claim\s+your\s+prize|could\s+not\s+be\s+delivered|delivery\s+failed",
        ]},
    "fee_request": {
        "title": "Просят оплатить «налог», пошлину или доставку, чтобы что-то получить",
        "advice": "Настоящий выигрыш или посылка не требуют оплаты по ссылке из сообщения или по звонку.",
        "weight": 35,
        "patterns": [
            r"(оплатит|оплатить|заплатит|заплатить)\w*\s+(налог|пошлин|комисси|сбор|доставк)",
            r"налог\w*\s+(на\s+выигрыш|\d+)",
            r"салық\w*\s+төле",
            r"pay\s+(a\s+|the\s+)?(small\s+)?(processing\s+|redelivery\s+|delivery\s+|customs\s+)?fee",
        ]},
}

_COMPILED = {k: [re.compile(p, re.I | re.U) for p in v["patterns"]] for k, v in CATEGORIES.items()}
MAX_TEXT = 20000


def _norm(t: str) -> str:
    return re.sub(r"\s+", " ", t.lower().replace("ё", "е")).strip()


def analyze(text: str) -> dict:
    t = _norm(text[:MAX_TEXT])
    found = []
    for k, pats in _COMPILED.items():
        hits, spans = [], []
        for p in pats:
            for m in p.finditer(t):
                if any(m.start() < e and m.end() > st for st, e in spans):
                    continue          # overlapping match of another pattern: same evidence
                spans.append((m.start(), m.end()))
                a, b = max(0, m.start() - 40), min(len(t), m.end() + 40)
                hits.append({"match": m.group(0), "context": ("…" if a else "") + t[a:b] + ("…" if b < len(t) else "")})
                if len(hits) >= 3:
                    break
            if len(hits) >= 3:
                break
        if hits:
            c = CATEGORIES[k]
            found.append({"category": k, "title": c["title"], "weight": c["weight"], "advice": c["advice"], "evidence": hits})
    # links: look-alike brands, shorteners, IP hosts, punycode, throw-away domains (same rules as the Android app)
    links = [f for f in link_check.scan(text[:MAX_TEXT]) if f["score"] >= 30]
    if links:
        worst = max(links, key=lambda f: f["score"])
        found.append({"category": "phishing_link", "title": f"Подозрительная ссылка: {worst['host']}",
                      "weight": max(30, min(60, worst["score"])),
                      "advice": "Не открывайте ссылку и не вводите на ней данные. Зайдите в приложение банка или на сайт сами.",
                      "evidence": [{"match": f["host"], "context": "; ".join(f["reasons"])} for f in links[:3]]})
    cats = {f["category"] for f in found}
    score = 100 * (1 - __import__("math").prod(1 - f["weight"] / 100 for f in found)) if found else 0.0
    # A single tactic alone never yields HIGH (same safeguard as the transaction engine) —
    # except a forged bank/government site (brand in the address but not the official domain + risky signs).
    forged_site = any(f["score"] >= 70 for f in links)
    if len(cats) <= 1 and not forged_site:
        score = min(score, 45.0)
    if forged_site:
        score = max(score, 75.0)
    critical = ("credentials_request" in cats or "safe_account_transfer" in cats or "remote_access" in cats
                or "family_emergency" in cats or "fee_request" in cats
                or any(f["score"] >= 50 for f in links))
    if critical and len(cats) >= 2:
        score = max(score, 75.0)
    level = "HIGH" if score >= 70 else "MEDIUM" if score >= 30 else "LOW"
    words = len(t.split())
    return {"risk_score": round(score, 1), "risk_level": level, "tactics": found, "tactics_count": len(cats),
            "words_analyzed": words,
            "summary": ("Признаков мошеннического сценария не найдено." if not found else
                        f"Найдено тактик социальной инженерии: {len(cats)}."),
            "method": "Правила (словари фраз на русском, казахском, английском) и проверка ссылок — прозрачно, не обученная модель",
            "disclaimer": "Оценивается содержание разговора, а не личность собеседника. Голос или манера речи не делают человека мошенником.",
            "short_text_note": "Текст очень короткий — оценка ненадёжна." if words < 8 else None}
