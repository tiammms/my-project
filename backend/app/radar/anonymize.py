"""Remove personal details from a scam phrase before it leaves the device or is stored on the server.

Kept: the words of the scheme and brand / authority names (Kaspi, МВД, eGov...), and the host of a link
(the fake domain is what identifies the scheme). Removed: phone, card, account and ID numbers, e-mail
addresses, link paths, sums and other long numbers, codes, and people's names.

The rules are deliberately greedy: losing a word is acceptable, leaking a name is not.
"""
from __future__ import annotations

import re

MAX_LEN = 500

# brands, authorities and app names that are part of the scheme, not of a person
KEEP = {w.lower() for w in """
Kaspi Каспи Halyk Халык Homebank Jusan Jysan Freedom ForteBank Forte BCC ЦентрКредит Bereke Береке Otbasy Отбасы
RBK ВТБ VTB Nurbank Altyn Eurasian Евразийский Home Credit Хоум Сбер Sber Tinkoff
eGov Egov Еgov ЦОН ХҚКО Kazpost Казпочта KazPost Beeline Билайн Kcell Activ Актив Tele2 Altel
WhatsApp Ватсап Telegram Телеграм Instagram Инстаграм TikTok Google Apple Android iPhone Wildberries Ozon OLX Krisha Kolesa
МВД ІІМ КНБ ҰҚК АФМ ҚМА Нацбанк Ұлттық Национальный Банк Банк Банка Банке Банком Госуслуги Генпрокуратура Прокуратура Полиция
Казахстан Қазақстан Казахстана Республики Республика ҚР РК Астана Алматы Шымкент
SMS СМС PIN ПИН CVV IBAN ИИН ЖСН QR
""".split()}

# beginnings of organisation names, so inflected forms stay too (Нацбанка, Kaspi-ге, банкінің, Центробанка)
KEEP_STEMS = ("kaspi", "каспи", "halyk", "халык", "халық", "jusan", "freedom", "forte", "bereke", "otbasy", "отбасы",
              "банк", "нацбанк", "центробанк", "ұлттық", "национальн", "полиц", "прокурат", "мвд", "кнб", "минфин", "министерств",
              "налог", "салық", "акимат", "әкімдік", "цон", "egov", "егов", "госуслуг", "kazpost", "казпочт", "beeline", "kcell",
              "whatsapp", "ватсап", "telegram", "телеграм", "instagram", "инстаграм", "olx", "wildberries", "ozon")

# words that start a message but are not names
STARTERS = {w.lower() for w in """
Здравствуйте Здравствуй Привет Добрый Доброе Уважаемый Уважаемая Уважаемые Алло Ало Срочно Внимание Мама Папа Сынок Дочка
Сәлеметсіз Сәлем Қайырлы Құрметті Апа Әке Ана Анашым Балам Ассалаумағалейкум Салем Hello Hi Dear
""".split()}

_EMAIL = re.compile(r"[\w.+-]+@[\w-]+(\.[\w-]+)+", re.U)
_URL = re.compile(r"\b(?:https?://|www\.)([^\s/?#]+)[^\s]*", re.I)
_IBAN = re.compile(r"\bKZ\d{2}[A-Z0-9]{13,16}\b", re.I)
_CARD = re.compile(r"\b(?:\d[ -]?){15,18}\d\b")
_PHONE = re.compile(r"(?:\+?\d[\s()\-]*){10,12}\d")
_IIN = re.compile(r"\b\d{12}\b")
_SUM = re.compile(r"\b\d[\d\s.,]*\s?(?:тг|тенге|теңге|₸|kzt|руб|рублей|\$|usd|долл\w*|евро|eur|мың|тыс\w*|млн|млрд)\b", re.I)
_LONGNUM = re.compile(r"\b\d{5,}\b")
_CODE = re.compile(r"\b\d{4}\b")
_INITIALS = re.compile(r"\b[А-ЯЁӘҒҚҢӨҰҮҺІA-Z]\.\s?[А-ЯЁӘҒҚҢӨҰҮҺІA-Z]\.(?:\s?[А-ЯЁӘҒҚҢӨҰҮҺІA-Z]\.)?")
_WORD = re.compile(r"[A-Za-zА-Яа-яЁёӘәҒғҚқҢңӨөҰұҮүҺһІі][\w'’-]*", re.U)


def _host_only(m: re.Match) -> str:
    host = m.group(1).split("@")[-1].split(":")[0].lower()
    return host if "." in host else "[ссылка]"


def _names(text: str) -> str:
    out, pos = [], 0
    sentence_start = True
    for m in _WORD.finditer(text):
        w = m.group(0)
        gap = text[pos:m.start()]
        if re.search(r"[.!?\n]", gap):
            sentence_start = True
        out.append(gap)
        low = w.lower()
        head = re.split(r"[-'’]", w)[0]
        name_like = (w[0].isupper() and not w.isupper() and not (len(head) > 1 and head.isupper())
                     and low not in KEEP and head.lower() not in KEEP and not low.startswith(KEEP_STEMS))
        if name_like and not sentence_start:
            w = "[имя]"
        elif name_like and sentence_start and low not in STARTERS and text[m.end():m.end() + 1] == ",":
            w = "[имя]"                      # "Айгерим, переведи..." - an address at the start
        out.append(w)
        pos = m.end()
        sentence_start = False
    out.append(text[pos:])
    return re.sub(r"(\[имя\])(\s+\[имя\])+", r"\1", "".join(out))


def anonymize(text: str) -> str:
    t = " ".join((text or "").split())[:MAX_LEN * 2]
    t = _EMAIL.sub("[почта]", t)
    t = _URL.sub(_host_only, t)
    t = _IBAN.sub("[счёт]", t)
    t = _CARD.sub("[карта]", t)
    t = _IIN.sub("[ИИН]", t)
    t = _PHONE.sub("[номер]", t)
    t = _SUM.sub("[сумма]", t)
    t = _LONGNUM.sub("[число]", t)
    t = _CODE.sub("[код]", t)
    t = _INITIALS.sub("[имя]", t)
    t = _names(t)
    return t[:MAX_LEN].strip()
