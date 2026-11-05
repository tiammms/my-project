package ai.neuroshield.app.core

/** What a bank app's notification says. */
enum class BankPushKind(val ru: String, val score: Double) {
    CODE("код подтверждения", 90.0),
    LOGIN("вход в приложение банка", 85.0),
    TRANSFER("перевод или списание", 80.0),
    CHANGE("смена лимитов, номера или пароля", 85.0),
}

data class BankPush(val bank: String, val kind: BankPushKind, val masked: String?)

/**
 * Notifications of Kazakhstan bank apps. During (or right after) a call with a stranger, a bank notification about a
 * code, a login, a transfer or a changed limit is the moment the scam happens: the caller keeps the person on the line
 * while they confirm it. Only the notification shown on the phone is read (with the user's notification access);
 * the bank app itself is never opened or read.
 */
object BankApps {
    // part of the Android package name -> bank. Short parts only count for packages starting with "kz."
    private val STRONG = listOf("kaspi" to "Kaspi", "homebank" to "Halyk", "halyk" to "Halyk", "jusan" to "Jusan", "jysan" to "Jusan")
    private val KZ = listOf("kkb" to "Halyk", "freedom" to "Freedom", "forte" to "ForteBank", "bcc" to "BCC", "bereke" to "Bereke",
        "home.capp" to "Home Credit", "homecredit" to "Home Credit", "otbasy" to "Отбасы банк", "rbk" to "Bank RBK", "vtb" to "ВТБ",
        "nurbank" to "Nurbank", "altyn" to "Altyn Bank", "eubank" to "Eurasian Bank")

    fun bankOf(pkg: String?): String? {
        val p = pkg?.lowercase() ?: return null
        STRONG.firstOrNull { p.contains(it.first) }?.let { return it.second }
        if (!p.startsWith("kz.")) return null
        return KZ.firstOrNull { p.contains(it.first) }?.second
    }

    private val LOGIN = Regex("(вход\\b|вход[аеу]|выполнен вход|вошли|войти|авторизац|нов\\w+ устройств|кіру|кірді|жаңа құрылғы|sign.?in|log.?in|new device)")
    private val CHANGE = Regex("(лимит|смен\\w* (номер|парол|телефон)|изменен\\w* (номер|парол|данн|лимит)|шектеу|limit)")
    private val TRANSFER = Regex("(перевод|списан|оплат|снят|плат[её]ж|аударым|аударылды|төлем|шешілді|transfer|payment|debited)")

    fun parse(pkg: String?, title: String?, text: String?): BankPush? {
        val bank = bankOf(pkg) ?: return null
        val t = listOfNotNull(title, text).joinToString(" ").lowercase().replace('ё', 'е')
        if (t.isBlank()) return null
        SmsCode.find(t)?.let { return BankPush(bank, BankPushKind.CODE, it.masked) }
        val kind = when {
            LOGIN.containsMatchIn(t) -> BankPushKind.LOGIN
            CHANGE.containsMatchIn(t) -> BankPushKind.CHANGE
            TRANSFER.containsMatchIn(t) -> BankPushKind.TRANSFER
            else -> return null
        }
        return BankPush(bank, kind, null)
    }
}

/**
 * Official short numbers of bank call centres in Kazakhstan, as listed by two independent reviews (finance.kz and
 * moneypanda.kz, checked 05.11.2026). The user picks a bank once; «Позвонить в банк» then dials the real number.
 * The numbers must be re-checked against the banks' own sites before every release.
 */
object BankDirectory {
    data class Bank(val name: String, val number: String)
    val ALL = listOf(
        Bank("Kaspi", "9999"), Bank("Halyk", "7111"), Bank("Jusan", "7711"), Bank("Freedom", "595"), Bank("ForteBank", "7575"),
        Bank("BCC (ЦентрКредит)", "505"), Bank("Bereke", "5030"), Bank("Отбасы банк", "300"), Bank("Bank RBK", "7888"),
        Bank("ВТБ", "5050"), Bank("Nurbank", "2552"),
    )

    fun byNumber(number: String?): Bank? {
        val d = number?.filter { it.isDigit() } ?: return null
        return ALL.firstOrNull { it.number == d }
    }
}
