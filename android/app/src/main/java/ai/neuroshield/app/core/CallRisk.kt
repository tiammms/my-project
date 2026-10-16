package ai.neuroshield.app.core

import kotlin.math.max

/** Facts about one phone call that the app can legitimately know. */
data class CallFacts(
    val number: String?,          // null / empty = hidden number
    val inContacts: Boolean,
    val homeCallingCode: String,  // e.g. "+7"
    val callsFromNumberLastHour: Int,
    val userMarkedScam: Boolean,
    val userMarkedSafe: Boolean,
    val messenger: String? = null,              // "WhatsApp", "Telegram"... for internet calls; null = cellular call
    val priorMessageLevel: RiskLevel? = null,   // the same number recently sent a message rated MEDIUM/HIGH
    val priorTactics: List<String> = emptyList(),
)

data class Reason(val text: String, val points: Int)
data class CallRiskResult(val score: Double, val level: RiskLevel, val reasons: List<Reason>, val advice: List<String>)

/**
 * Risk of an incoming call BEFORE anything is said, from the number alone.
 * It cannot know whether a stranger is a scammer; it only says how much caution fits.
 */
object CallRisk {
    fun normalizeNumber(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val digits = raw.filter { it.isDigit() || it == '+' }
        return digits.ifEmpty { null }
    }

    fun assess(f: CallFacts): CallRiskResult {
        val reasons = mutableListOf<Reason>()
        val n = normalizeNumber(f.number)
        if (f.userMarkedSafe) return CallRiskResult(0.0, RiskLevel.LOW, listOf(Reason("Вы отметили этот номер как надёжный", 0)), emptyList())
        if (f.userMarkedScam) reasons += Reason("Вы ранее отметили этот номер как мошеннический", 80)
        if (n == null) {
            reasons += Reason("Номер скрыт", 30)
        } else {
            if (!f.inContacts) reasons += Reason("Номера нет в ваших контактах", 10)
            if (n.startsWith("+") && !n.startsWith(f.homeCallingCode)) reasons += Reason("Международный номер ($n)", 25)
            else if (n.startsWith("00")) reasons += Reason("Международный номер ($n)", 25)
            if (f.callsFromNumberLastHour >= 3) reasons += Reason("Звонит уже ${f.callsFromNumberLastHour}-й раз за час", 20)
            val local = n.removePrefix(f.homeCallingCode).removePrefix("+")
            if (!f.inContacts && local.length in 3..6 && f.messenger == null) reasons += Reason("Короткий номер: мошенники подделывают номера банков", 10)
        }
        if (f.messenger != null && !f.inContacts)
            reasons += Reason("Звонок через ${f.messenger} с незнакомого номера: так обходят антиспам оператора", 15)
        when (f.priorMessageLevel) {
            RiskLevel.HIGH -> reasons += Reason("Этот номер недавно писал вам сообщение с признаками мошенничества" +
                (if (f.priorTactics.isNotEmpty()) ": " + f.priorTactics.take(2).joinToString("; ").lowercase() else ""), 65)
            RiskLevel.MEDIUM -> reasons += Reason("Этот номер недавно писал подозрительное сообщение" +
                (if (f.priorTactics.isNotEmpty()) ": " + f.priorTactics.first().lowercase() else ""), 35)
            else -> {}
        }
        val score = if (reasons.isEmpty()) 0.0 else 100 * (1 - reasons.fold(1.0) { a, r -> a * (1 - r.points / 100.0) })
        val s = if (f.userMarkedScam) max(score, 80.0) else score
        val advice = mutableListOf<String>()
        if (!f.inContacts) {
            advice += "Если звонящий представится банком или полицией — положите трубку и перезвоните сами по официальному номеру."
            advice += "Никому не называйте коды из SMS."
            if (f.messenger != null) advice += "Банки и госорганы не звонят через ${f.messenger}."
        }
        return CallRiskResult(Math.round(s * 10) / 10.0, levelOf(s), reasons, advice)
    }
}

/** One-time codes in SMS ("Код: 482913", "Your code is 1234", "Растаушы код 5531"). */
object SmsCode {
    private val keyword = Regex("(код|code|пароль|password|otp|parol|құпия|kod|подтвержд|никому)", RegexOption.IGNORE_CASE)
    private val codeRx = Regex("(?<![0-9])([0-9]{4,8})(?![0-9])")

    data class Found(val code: String, val masked: String)

    fun find(text: String): Found? {
        val t = text.lowercase()
        if (!keyword.containsMatchIn(t)) return null
        val m = codeRx.find(t) ?: return null
        val c = m.groupValues[1]
        return Found(c, c.take(1) + "•".repeat((c.length - 2).coerceAtLeast(1)) + c.takeLast(1))
    }
}
