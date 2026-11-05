package ai.neuroshield.app.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import ai.neuroshield.app.Contacts
import ai.neuroshield.app.alert.Warning
import ai.neuroshield.app.alert.Warnings
import ai.neuroshield.app.app
import ai.neuroshield.app.call.CallSessions
import ai.neuroshield.app.call.CallTracker
import ai.neuroshield.app.core.CallChannel
import ai.neuroshield.app.core.CallEvent
import ai.neuroshield.app.core.CallFacts
import ai.neuroshield.app.core.CallRisk
import ai.neuroshield.app.core.Messenger
import ai.neuroshield.app.core.MessengerEvent
import ai.neuroshield.app.core.RiskLevel
import ai.neuroshield.app.core.ScamAnalyzer
import ai.neuroshield.app.core.SenderStatus

/**
 * Sees notifications of WhatsApp, Telegram, Viber (only with the user's "notification access" permission).
 * Internet calls are invisible to the phone system, so this is the only way an app can know that
 * someone is calling through a messenger. From the notification the app gets: the caller's name or number
 * (WhatsApp shows the number only for people NOT in your contacts) and the text of incoming messages.
 * Notification texts are analysed in memory and are not stored.
 */
class MessengerListener : NotificationListenerService() {
    private val warnedCalls = mutableMapOf<String, Long>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        ai.neuroshield.app.core.BankApps.parse(sbn.packageName, title, text)?.let { onBankPush(sbn.key, it); return }
        val event = Messenger.parse(sbn.packageName, title, text, n.category == Notification.CATEGORY_CALL) ?: return
        val p = app.prefs
        when (event) {
            is MessengerEvent.IncomingCall -> onIncomingCall(sbn.key, event)
            is MessengerEvent.OngoingCall -> {
                val st = status(event.app, event.number, event.caller)
                val stranger = st == SenderStatus.STRANGER
                val wasActive = p.voipActive && activeKey == sbn.key
                // only a verified contact switches off the "code during a call" alarm; a matching name is not enough
                p.voipActive = true; p.voipApp = event.app; p.voipNumber = event.number ?: event.caller; p.voipIsContact = st.trusted
                activeKey = sbn.key
                if (stranger && !wasActive) CallSessions.on(this, CallEvent.Answered(System.currentTimeMillis()))
                if (stranger && !wasActive) {
                    if (event.video) Warnings.showVideoOffer(this) else if (p.offerListening) Warnings.showListenOffer(this)
                }
            }
            is MessengerEvent.Message -> onMessage(event)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == activeKey || sbn.key == ringingKey) {
            val p = app.prefs
            if (sbn.key == activeKey) {
                p.voipActive = false; p.voipEndedAt = System.currentTimeMillis(); activeKey = null
                Warnings.hideListenOffer(); Warnings.hideVideoOffer()
                CallSessions.end(this, CallChannel.MESSENGER)
            }
            if (sbn.key == ringingKey) {
                ringingKey = null
                // not answered (missed or declined): the "ongoing call" notification never appears
                main.postDelayed({ if (!app.prefs.voipActive && activeKey == null) CallSessions.end(this, CallChannel.MESSENGER) }, 8_000)
            }
        }
    }

    /** Contact check for a messenger notification: by number if it is shown, otherwise by name (see [SenderStatus]). */
    private fun status(app: String, number: String?, name: String): SenderStatus =
        Messenger.senderStatus(app, number, number != null && Contacts.contains(this, number), number == null && Contacts.hasName(this, name))

    private fun onIncomingCall(key: String, e: MessengerEvent.IncomingCall) {
        val p = app.prefs
        val st = status(e.app, e.number, e.caller)
        ringingKey = key
        p.voipApp = e.app; p.voipNumber = e.number ?: e.caller; p.voipIsContact = st.trusted
        // a verified contact, or a name equal to a contact's name: no warning before the call (the "code during a call"
        // alarm stays on for a name match, see OngoingCall)
        if (st != SenderStatus.STRANGER) return
        val num: String? = e.number
        val now = System.currentTimeMillis()
        val dedup = num ?: "name:${e.caller}"
        if ((warnedCalls[dedup] ?: 0L) > now - 30_000) return
        warnedCalls[dedup] = now
        val prior = num?.let { p.senderRisk(it) }
        val reports = num?.let { app.alerts.reportCounts(it) } ?: (0 to 0)
        val facts = CallFacts(num, false, p.homeCallingCode, if (num != null) p.countRecentCall(num) else 1, num != null && num in p.scamNumbers(),
            num != null && num in p.safeNumbers(), messenger = e.app, priorMessageLevel = prior?.first, priorTactics = prior?.second ?: emptyList(),
            highReports = reports.first, mediumReports = reports.second, callerName = if (num == null) e.caller else null)
        if (facts.userMarkedSafe) return
        val r = if (num != null) CallTracker.withReputation(CallRisk.assess(facts), num) else CallRisk.assess(facts)
        CallSessions.start(this, num, CallChannel.MESSENGER, e.app, r)
        Warnings.show(this, Warning(
            level = r.level, score = r.score, source = "messenger_call", number = num ?: e.caller,
            title = (if (e.video) "Видеозвонок" else "Звонок") + " в ${e.app} " + (if (num != null) "с незнакомого номера" else "от незнакомого собеседника"),
            reasons = r.reasons.map { it.text }, advice = r.advice,
        ))
    }

    /**
     * A bank app's notification (code, login, transfer, changed limit) while — or within 5 minutes after — talking to a
     * stranger: the caller is probably guiding the person through it. The notification text is not stored.
     */
    private fun onBankPush(key: String, b: ai.neuroshield.app.core.BankPush) {
        val call = CallTracker.riskyCall(this, 5 * 60_000) ?: return
        val now = System.currentTimeMillis()
        val dedup = "${b.bank}:${b.kind}"
        if ((warnedCalls[dedup] ?: 0L) > now - 60_000) return        // banks update the same notification several times
        warnedCalls[dedup] = now
        android.util.Log.i("SaqtanBank", "bank notification during a risky call: kind=${b.kind}")
        CallSessions.on(this, CallEvent.BankPushArrived(now, b.bank, b.kind, b.masked))
        val bankNo = app.prefs.bankNumber.takeIf { it.isNotBlank() }
        Warnings.show(this, Warning(
            level = RiskLevel.HIGH, score = b.kind.score, source = "bank_push", number = call.number ?: "номер неизвестен",
            title = "Уведомление ${b.bank} во время звонка с незнакомым номером",
            reasons = listOf("${b.bank}: ${b.kind.ru}" + (b.masked?.let { " ($it)" } ?: ""), "Звонящего нет в ваших контактах",
                "Так действуют мошенники: держат на линии, пока человек входит в банк или переводит деньги"),
            advice = listOf("Не подтверждайте вход и переводы, не называйте коды",
                "Положите трубку и позвоните в банк сами" + (bankNo?.let { ": $it" } ?: "")),
            offerListening = false,
        ))
    }

    private fun onMessage(e: MessengerEvent.Message) {
        val num = e.number
        val r = ScamAnalyzer.analyze(e.text)
        if (r.level == RiskLevel.LOW) return
        val st = status(e.app, num, e.sender)
        val unknown = st == SenderStatus.STRANGER
        if (num != null) app.prefs.rememberSenderRisk(num, r.level, r.tactics.map { it.title })
        // messages from saved contacts (or from a name equal to a contact's) are flagged only when clearly fraudulent
        if (!unknown && r.level != RiskLevel.HIGH) return
        val who = when (st) {
            SenderStatus.NAME_LIKE_CONTACT -> listOf("Имя совпадает с контактом, но номер не виден — это может быть другой человек")
            SenderStatus.STRANGER -> emptyList()
            else -> listOf("Отправитель есть в контактах — возможно, его аккаунт взломан")
        }
        Warnings.show(this, Warning(
            level = r.level, score = r.score, source = "messenger_text", number = num ?: e.sender,
            title = "Подозрительное сообщение в ${e.app} от «${e.sender}»",
            reasons = r.tactics.map { it.title } + who,
            advice = r.tactics.sortedByDescending { it.weight }.take(2).map { it.advice },
        ))
    }

    companion object {
        @Volatile private var activeKey: String? = null
        @Volatile private var ringingKey: String? = null
    }
}
