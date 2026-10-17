"""Phishing-link check for messages and call transcripts (same rules as the Android app, core/LinkCheck.kt).

Rule-based and explainable, works offline, never opens the link. Flags links that imitate known brands,
hide their target (shorteners), use raw IP addresses, punycode look-alikes, throw-away domains or credential pages.
"""
from __future__ import annotations

import re

_URL = re.compile(r"""(?i)\b((?:https?://|www\.)[^\s<>"'«»]+|(?:[a-z0-9-]+\.)+(?:kz|ru|com|net|org|top|xyz|click|icu|live|online|site|shop|buzz|rest|info|link|io|me)(?:/[^\s<>"'«»]*)?)""")

BRANDS: dict[str, tuple[str, ...]] = {
    "kaspi": ("kaspi.kz",), "halyk": ("halykbank.kz", "homebank.kz"), "homebank": ("homebank.kz",),
    "forte": ("forte.kz", "fortebank.com"), "jusan": ("jusan.kz",), "bcc": ("bcc.kz",),
    "freedom": ("freedombank.kz", "ffin.kz"), "egov": ("egov.kz",), "olx": ("olx.kz",),
    "kolesa": ("kolesa.kz",), "krisha": ("krisha.kz",), "sber": ("sberbank.ru", "sber.ru", "sberbank.kz"),
    "tinkoff": ("tinkoff.ru", "tbank.ru"), "gosuslugi": ("gosuslugi.ru",), "whatsapp": ("whatsapp.com", "wa.me"),
    "telegram": ("telegram.org", "t.me"), "google": ("google.com", "google.kz"), "apple": ("apple.com",),
}
SHORTENERS = {"bit.ly", "clck.ru", "tinyurl.com", "goo.su", "cutt.ly", "is.gd", "t.co", "rb.gy", "u.to", "vk.cc", "shorturl.at"}
RISKY_TLDS = {"top", "xyz", "click", "icu", "live", "online", "site", "shop", "buzz", "rest", "link", "info", "cfd", "sbs"}
_CRED = re.compile(r"(?i)(login|signin|verify|verif|secure|account|update|confirm|bonus|prize|vyigrysh|kompensac|vozvrat|parol|password)")
_IP = re.compile(r"^\d{1,3}(\.\d{1,3}){3}$")


def extract(text: str) -> list[str]:
    out: list[str] = []
    for m in _URL.finditer(text):
        u = m.group(0).rstrip(".,)!?;")
        if u not in out:
            out.append(u)
    return out[:10]


def host(url: str) -> str:
    s = re.sub(r"(?i)^https?://", "", url)
    for sep in "/?#":
        s = s.split(sep, 1)[0]
    return s.rsplit("@", 1)[-1].split(":", 1)[0].lower()


def registrable(h: str) -> str:
    return ".".join(h.split(".")[-2:])


def check(url: str) -> dict:
    h = host(url)
    reg = registrable(h)
    reasons: list[str] = []
    score = 0
    if h in SHORTENERS or reg in SHORTENERS:
        score += 35; reasons.append("Короткая ссылка скрывает настоящий адрес")
    if _IP.match(h):
        score += 50; reasons.append("Вместо адреса сайта — IP-адрес")
    if any(p.startswith("xn--") for p in h.split(".")):
        score += 45; reasons.append("Адрес с похожими буквами (punycode) — так подделывают известные сайты")
    tld = h.rsplit(".", 1)[-1]
    if tld in RISKY_TLDS:
        score += 25; reasons.append(f"Домен .{tld} часто используют для одноразовых мошеннических сайтов")
    for brand, official in BRANDS.items():
        if brand in h and reg not in official and not any(h == o or h.endswith("." + o) for o in official):
            score += 55; reasons.append(f"Похоже на «{brand.capitalize()}», но это не официальный сайт ({official[0]})")
            break
    if h.count("-") >= 2:
        score += 10; reasons.append("Много дефисов в адресе")
    if _CRED.search(url) and score > 0:
        score += 15; reasons.append("Страница просит войти или «подтвердить» данные")
    if "@" in url:
        score += 20; reasons.append("Символ @ в ссылке маскирует настоящий адрес")
    return {"url": url, "host": h, "score": min(score, 100), "reasons": reasons}


def scan(text: str) -> list[dict]:
    return [f for f in (check(u) for u in extract(text)) if f["score"] > 0]
