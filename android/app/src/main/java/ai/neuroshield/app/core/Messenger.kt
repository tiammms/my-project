package ai.neuroshield.app.core

/** What a messenger notification is about (parsed from its title/text; nothing else is available to other apps). */
sealed interface MessengerEvent {
    val app: String
    data class IncomingCall(override val app: String, val caller: String, val number: String?, val video: Boolean) : MessengerEvent
    data class OngoingCall(override val app: String, val caller: String, val number: String?, val video: Boolean = false) : MessengerEvent
    data class Message(override val app: String, val sender: String, val number: String?, val text: String) : MessengerEvent
}

object Messenger {
    val APPS = mapOf(
        "com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business",
        "org.telegram.messenger" to "Telegram", "org.telegram.messenger.web" to "Telegram",
        "com.viber.voip" to "Viber", "com.imo.android.imoim" to "imo",
        "org.thoughtcrime.securesms" to "Signal", "com.instagram.android" to "Instagram", "com.facebook.orca" to "Messenger",
        "com.skype.raider" to "Skype", "com.discord" to "Discord", "jp.naver.line.android" to "LINE",
        "com.google.android.apps.tachyon" to "Google Meet", "com.microsoft.teams" to "Teams", "kik.android" to "Kik",
        "com.android.shell" to "WhatsApp (тест через ADB)",
    )

    private val incomingCall = Regex("(входящ\\p{L}*\\s+(голосов\\p{L}*\\s+|видео)?\\s*(звонок|вызов|видеозвонок))|(incoming\\s+(voice\\s+|video\\s+)?call)|(кіріс\\p{L}*\\s+қоңырау)|(звонит\\s)|(is\\s+calling)|(calling\\s+you)", RegexOption.IGNORE_CASE)
    private val ongoingCall = Regex("(текущ\\p{L}*\\s+(голосов\\p{L}*\\s+|видео)?(звонок|вызов|видеозвонок))|(ongoing\\s+(voice\\s+|video\\s+)?call)|(идет\\s+звонок)|(идёт\\s+звонок)", RegexOption.IGNORE_CASE)
    private val videoRx = Regex("видео|video", RegexOption.IGNORE_CASE)

    /** "+44 7911 123456" -> "+447911123456"; a contact name -> null. WhatsApp shows the number only for non-contacts. */
    fun phoneFromTitle(title: String): String? {
        val t = title.trim().replace('‪', ' ').replace('‬', ' ').replace('‎', ' ').trim()
        if (t.isEmpty() || !t.all { it.isDigit() || it in "+ ()-. " }) return null
        val digits = t.count { it.isDigit() }
        return if (digits >= 7) CallRisk.normalizeNumber(t) else null
    }

    fun parse(pkg: String, title: String?, text: String?, isCallCategory: Boolean): MessengerEvent? {
        val app = APPS[pkg] ?: return null
        val tt = (title ?: "").trim()
        val body = (text ?: "").trim()
        if (tt.isEmpty() && body.isEmpty()) return null
        val number = phoneFromTitle(tt)
        return when {
            ongoingCall.containsMatchIn(body) -> MessengerEvent.OngoingCall(app, tt, number, videoRx.containsMatchIn(body))
            incomingCall.containsMatchIn(body) || (isCallCategory && !ongoingCall.containsMatchIn(body)) ->
                MessengerEvent.IncomingCall(app, tt, number, videoRx.containsMatchIn(body))
            body.isNotEmpty() -> MessengerEvent.Message(app, tt, number, body)
            else -> null
        }
    }
}
