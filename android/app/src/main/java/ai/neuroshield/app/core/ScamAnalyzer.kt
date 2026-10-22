package ai.neuroshield.app.core

import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

enum class RiskLevel(val ru: String) { LOW("Низкий риск"), MEDIUM("Средний риск"), HIGH("Высокий риск") }

fun levelOf(score: Double, t: RiskThresholds = RiskEngine.thresholds): RiskLevel = when {
    score >= t.high -> RiskLevel.HIGH
    score >= t.medium -> RiskLevel.MEDIUM
    else -> RiskLevel.LOW
}

data class Evidence(val match: String, val context: String)
data class Tactic(val id: String, val title: String, val advice: String, val weight: Int, val evidence: List<Evidence>)
data class TextRisk(val score: Double, val level: RiskLevel, val tactics: List<Tactic>, val words: Int) {
    val summary: String
        get() = if (tactics.isEmpty()) "Признаков мошеннического сценария не найдено" else "Найдено признаков обмана: ${tactics.size}"
}

/**
 * Rule-based detector of social-engineering tactics in what is SAID (speech transcript) or WRITTEN (SMS, message).
 * Russian, Kazakh and English. Transparent on purpose: every warning shows the phrase that triggered it.
 * It judges the content of the conversation, never the person or the sound of their voice.
 * Same rules as the NeuroShield server (backend/app/biometrics/scam_text.py), adapted for speech recognition output.
 */
object ScamAnalyzer {
    private class Category(val id: String, val title: String, val advice: String, val weight: Int, val patterns: List<String>)

    private val CATEGORIES = listOf(
        Category("credentials_request", "Просят код, пароль или данные карты",
            "Никому не сообщайте коды из SMS, пароли, PIN и CVV. Банк никогда их не спрашивает.", 45, listOf(
                """код\w*\s+(из|с|в)\s+(смс|sms|эсэмэс|сообщени)""",
                """(продиктуй|назови|скажи|сообщи|скинь|отправь|подтверди|прочитай|зачитай)\w*\s+(мне\s+)?(пожалуйста\s+)?(этот\s+|ваш\s+|свой\s+)?код""",
                """код\w*\s+подтвержд""", """\bcvv\b|\bcvc\b|\bсвв\b|три\s+цифры\s+на\s+обороте|три\s+цифры\s+сзади""",
                """пин[\s-]?код|\bpin\b""", """(ваш|свой|назовите)\s+пароль|пароль\s+от""", """срок\s+действия\s+карты""",
                """номер\s+(вашей\s+)?карты""", """кодты?\s+(айт|жібер)""", """құпия\s+сөз""",
                """(tell|give|read|send)\s+(me\s+)?(the\s+|your\s+)?(code|password|pin)""", """verification\s+code|one[\s-]time\s+(code|password)""",
            )),
        Category("safe_account_transfer", "Просят перевести деньги на «безопасный» или чужой счёт",
            "«Безопасных» и «резервных» счетов не существует. Не переводите деньги по просьбе звонящего.", 40, listOf(
                """(безопасн|резервн|защищенн|специальн|страхов)\w*\s+(сч[её]т|ячейк)""",
                """(перевед|переведит|перечисл|отправ)\w*\s+(все\s+|ваши\s+)?(деньги|средства|сбережения|сумм)""",
                """(снимит|снять|обналичь)\w*\s+(все\s+)?(деньги|наличн|средства)""", """через\s+банкомат\s+(на|внес)""", """задеклариру""",
                """қауіпсіз\s+шот""", """ақша\w*\s+аудар""",
                """safe\s+account|secure\s+account|transfer\s+(all\s+)?(your\s+)?(money|funds|savings)""",
            )),
        Category("urgency_pressure", "Давят срочностью",
            "Срочность — главный инструмент мошенников. Сделайте паузу: настоящий банк подождёт.", 20, listOf(
                """\bсрочно\b""", """немедленно""", """прямо\s+сейчас""", """в\s+течени[ие]\s+(\d+|пят\w*|десят\w*|пятнадцат\w*)\s+минут""",
                """(у\s+вас\s+)?(нет|мало)\s+времени""", """иначе\s+(вы\s+)?(потеря|лишит|деньги|сч[её]т|будет)""", """пока\s+не\s+поздно""",
                """последн\w+\s+(шанс|возможност)""", """не\s+кладите\s+трубку""", """жедел|дереу|қазір\s+бірден""",
                """\burgent\b|immediately|right\s+now|within\s+\d+\s+minutes|before\s+it'?s\s+too\s+late""",
            )),
        Category("authority_impersonation", "Представляются банком, полицией или госорганом",
            "Положите трубку и сами перезвоните в банк по номеру на обратной стороне карты.", 25, listOf(
                """служб\w+\s+безопасности(\s+банка)?""", """(сотрудник|специалист|оператор)\w*\s+(банка|службы)""", """\bполици\w*""", """следовател""",
                """(национальн|центральн)\w*\s+банк|\bнацбанк|\bцб\s+рф|\bафм\b|\bкнб\b|\bфсб\b|прокуратур|\bмвд\b""",
                """банктің\s+қауіпсіздік|полиция\s+қызметкері""",
                """(bank'?s?\s+)?security\s+(department|team|service)|\bpolice\b|fraud\s+department""",
            )),
        Category("secrecy_isolation", "Просят держать разговор в тайне",
            "Требование никому не говорить — тревожный признак. Обсудите ситуацию с близкими.", 20, listOf(
                """никому\s+не\s+(говорите|рассказывайте|сообщайте)""", """(тайн\w+|секрет\w*)\s+следстви""",
                """не\s+(говорите|сообщайте)\s+(родственникам|близким|сотрудникам\s+банка)""", """это\s+конфиденциальн""", """ешкімге\s+айтпаңыз""",
                """don'?t\s+tell\s+(anyone|your\s+family|the\s+bank)|keep\s+(this|it)\s+(secret|confidential)""",
            )),
        Category("remote_access", "Просят установить программу или показать экран",
            "Не устанавливайте программы удалённого доступа по просьбе звонящего.", 35, listOf(
                """anydesk|эни\s?деск|teamviewer|тимвьюер|rustdesk|rust\s+desk|quick\s?support""",
                """(установит|скачайт|скачать|установить)\w*\s+(приложени|программ)""",
                """демонстраци\w+\s+экрана|покажите\s+(ваш\s+)?экран""", """screen\s+shar|install\s+(the\s+)?(app|application)|remote\s+access""",
            )),
        Category("threat_fear", "Пугают: блокировка, кредит на ваше имя, уголовное дело",
            "Не принимайте решения в панике. Проверьте информацию сами в приложении банка.", 15, listOf(
                """(ваш\w*\s+)?(сч[её]т|карт\w+)\s+(заблокир|будет\s+заблокир)""", """(оформ|взял|взят)\w*\s+кредит\s+на\s+(ваше|вас)""",
                """подозрительн\w+\s+(операци|перевод)""", """уголовн|\bарест|соучастни""", """мошенники\s+пытаются""",
                """your\s+(\w+\s+)?(account|card)\s+(is|has\s+been|will\s+be)\s+(blocked|frozen|compromised|suspended)|arrest|loan\s+in\s+your\s+name""",
            )),
    )

    // Java regex \w and \b are ASCII-only on the JVM; make them Unicode-aware the same way on JVM and Android.
    private const val W = """[\p{L}\p{N}_]"""
    private fun portable(p: String): String = p
        .replace("""\b""", """(?:(?<!$W)(?=$W)|(?<=$W)(?!$W))""")
        .replace("""\w""", W)

    private val compiled: List<Pair<Category, List<Pattern>>> = CATEGORIES.map { c ->
        c to c.patterns.map { Pattern.compile(portable(it), Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE) }
    }

    const val MAX_TEXT = 20_000

    fun normalize(t: String): String = t.lowercase().replace('ё', 'е').replace(Regex("\\s+"), " ").trim()

    fun analyze(text: String): TextRisk {
        val t = normalize(text.take(MAX_TEXT))
        val found = mutableListOf<Tactic>()
        for ((c, pats) in compiled) {
            val hits = mutableListOf<Evidence>()
            val spans = mutableListOf<IntRange>()
            loop@ for (p in pats) {
                val m = p.matcher(t)
                while (m.find()) {
                    if (spans.any { m.start() < it.last && m.end() > it.first }) continue
                    spans += m.start()..m.end()
                    val a = max(0, m.start() - 40)
                    val b = min(t.length, m.end() + 40)
                    hits += Evidence(m.group(), (if (a > 0) "…" else "") + t.substring(a, b) + (if (b < t.length) "…" else ""))
                    if (hits.size >= 3) break@loop
                }
            }
            if (hits.isNotEmpty()) found += Tactic(c.id, c.title, c.advice, c.weight, hits)
        }
        // links: checked by LinkCheck (look-alike brands, shorteners, IP hosts, punycode, throw-away domains)
        val links = LinkCheck.scan(text.take(MAX_TEXT)).filter { it.score >= 30 }
        if (links.isNotEmpty()) {
            val worst = links.maxBy { it.score }
            found += Tactic("phishing_link", "Подозрительная ссылка: ${worst.host}",
                "Не открывайте ссылку и не вводите на ней данные. Зайдите в приложение банка или на сайт сами.",
                worst.score.coerceIn(30, 60), links.take(3).map { Evidence(it.host, it.reasons.joinToString("; ")) })
        }
        val ids = found.map { it.id }.toSet()
        var score = if (found.isEmpty()) 0.0 else 100 * (1 - found.fold(1.0) { acc, f -> acc * (1 - f.weight / 100.0) })
        // A single tactic alone never yields HIGH (same safeguard as the NeuroShield risk engine).
        // A fake bank/government site is strong evidence on its own (brand in the address but not the official domain + risky signs).
        val forgedSite = links.any { it.score >= 70 }
        if (ids.size <= 1 && !forgedSite) score = min(score, 45.0)
        if (forgedSite) score = max(score, 75.0)
        val critical = "credentials_request" in ids || "safe_account_transfer" in ids || "remote_access" in ids ||
            (links.any { it.score >= 50 })
        if (critical && ids.size >= 2) score = max(score, 75.0)
        val words = if (t.isBlank()) 0 else t.split(" ").size
        return TextRisk(Math.round(score * 10) / 10.0, levelOf(score), found, words)
    }
}
