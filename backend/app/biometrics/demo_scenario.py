"""Scripted presentation scenario (same as the Android app, core/DemoScenario.kt).

The INPUT is made up: a reserved fictional number, the caller's words, an SMS and a message.
Every RESULT is computed by the real analyzers (call_risk, scam_text, link_check); nothing is hard-coded.
"""
NUMBER = "+447700900123"     # Ofcom range reserved for drama / fiction

CALL = [
    {"caller": True, "text": "Здравствуйте! Это служба безопасности Kaspi банка."},
    {"caller": False, "text": "Здравствуйте, слушаю."},
    {"caller": True, "text": "По вашей карте подозрительная операция: на ваше имя пытаются оформить кредит."},
    {"caller": True, "text": "Действовать нужно срочно, у вас нет времени, иначе деньги спишут."},
    {"caller": False, "text": "Что мне делать?"},
    {"caller": True, "text": "Вам придёт СМС. Продиктуйте мне код из СМС."},
    {"caller": True, "text": "И никому не говорите об этом звонке, это тайна следствия."},
    {"caller": True, "text": "Потом переведите все деньги на безопасный счёт, я продиктую номер."},
]
SMS = "Kaspi.kz: код подтверждения 4821. Никому не сообщайте этот код."
PHISHING = "Kaspi: ваша карта заблокирована. Чтобы разблокировать, подтвердите данные: https://kaspi-bonus.example/verify"
NORMAL = "Привет! Это Айгерим. Во сколько завтра встречаемся у университета? Я возьму конспекты."


def caller_text(up_to: int) -> str:
    return " ".join(l["text"] for l in CALL[:up_to] if l["caller"])


def script() -> dict:
    return {"number": NUMBER, "call": CALL, "sms": SMS, "phishing": PHISHING, "normal": NORMAL,
            "note": "Номер, речь и сообщения выдуманы; оценки считаются настоящими правилами анализа."}
