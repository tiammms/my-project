package ai.neuroshield.app.core

/**
 * The message "tell someone close": prepared by the app, sent by the USER (the SMS app or the share sheet opens with
 * the text filled in; Saqtan never sends anything by itself and has no SMS-sending permission).
 * Short and calm, so a relative understands it at once and calls back.
 */
object FamilyAlert {
    private val CALL_SOURCES = setOf("incoming_call", "call_report", "live_speech", "sms_code", "blocked_call", "messenger_call",
        "retell", "synthetic_voice", "test", "demo")

    /** [title] is already in the interface language. */
    fun text(kk: Boolean, source: String, number: String?, title: String): String {
        val call = source in CALL_SOURCES
        val num = number?.takeIf { n -> n.count(Char::isDigit) >= 3 }
        return if (kk) {
            val who = (if (num != null) "$num нөмірінен " else "") + (if (call) "қоңырау шалып жатыр" else "жазып жатыр")
            "Маған қазір $who. Saqtan ескертті: ${title.trim().trimEnd('.')}. Мен ақша аудармаймын және код айтпаймын. Маған қоңырау шалшы, өтінемін."
        } else {
            val who = (if (call) "звонят" else "пишут") + (if (num != null) " с номера $num" else "")
            "Мне сейчас $who. Saqtan предупредил: ${title.trim().trimEnd('.')}. Я ничего не перевожу и коды не называю. Перезвони мне, пожалуйста."
        }
    }
}
