package ai.neuroshield.app.core

/**
 * Scripted presentation scenario. The INPUT is made up (number, words of the caller, SMS, message);
 * every RESULT comes from the real analyzers (CallRisk, ScamAnalyzer, LinkCheck, SmsCode), nothing is hard-coded.
 */
object DemoScenario {
    const val NUMBER = "+447700900123"          // reserved fictional UK range (Ofcom drama numbers)
    const val BRAND = "Kaspi"

    data class Line(val caller: Boolean, val text: String)

    val CALL = listOf(
        Line(true, "Здравствуйте! Это служба безопасности Kaspi банка."),
        Line(false, "Здравствуйте, слушаю."),
        Line(true, "По вашей карте подозрительная операция: на ваше имя пытаются оформить кредит."),
        Line(true, "Действовать нужно срочно, у вас нет времени, иначе деньги спишут."),
        Line(false, "Что мне делать?"),
        Line(true, "Вам придёт СМС. Продиктуйте мне код из СМС."),
        Line(true, "И никому не говорите об этом звонке, это тайна следствия."),
        Line(true, "Потом переведите все деньги на безопасный счёт, я продиктую номер."),
    )

    const val SMS = "Kaspi.kz: код подтверждения 4821. Никому не сообщайте этот код."
    const val PHISHING = "Kaspi: ваша карта заблокирована. Чтобы разблокировать, подтвердите данные: https://kaspi-bonus.example/verify"
    const val NORMAL = "Привет! Это Айгерим. Во сколько завтра встречаемся у университета? Я возьму конспекты."

    fun callerText(upTo: Int): String = CALL.take(upTo).filter { it.caller }.joinToString(" ") { it.text }

    fun callFacts(homeCallingCode: String) = CallFacts(NUMBER, inContacts = false, homeCallingCode = homeCallingCode,
        callsFromNumberLastHour = 1, userMarkedScam = false, userMarkedSafe = false)
}
