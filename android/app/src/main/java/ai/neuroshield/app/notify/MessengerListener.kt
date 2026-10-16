package ai.neuroshield.app.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import ai.neuroshield.app.Contacts
import ai.neuroshield.app.alert.Warning
import ai.neuroshield.app.alert.Warnings
import ai.neuroshield.app.app
import ai.neuroshield.app.core.CallFacts
import ai.neuroshield.app.core.CallRisk
import ai.neuroshield.app.core.Messenger
import ai.neuroshield.app.core.MessengerEvent
import ai.neuroshield.app.core.RiskLevel
import ai.neuroshield.app.core.ScamAnalyzer

/**
 * Sees notifications of WhatsApp, Telegram, Viber (only with the user's "notification access" permission).
 * Internet calls are invisible to the phone system, so this is the only way an app can know that
 * someone is calling through a messenger. From the notification the app gets: the caller's name or number
 * (WhatsApp shows the number only for people NOT in your contacts) and the text of incoming messages.
 * Notification texts are analysed in memory and are not stored.
 */
class MessengerListener : NotificationListenerService() {
    private val warnedCalls = mutableMapOf<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        val event = Messenger.parse(sbn.packageName, title, text, n.category == Notification.CATEGORY_CALL) ?: return
        val p = app.prefs
        when (event) {
            is MessengerEvent.IncomingCall -> onIncomingCall(sbn.key, event)
            is MessengerEvent.OngoingCall -> {
                val contact = event.number == null || Contacts.contains(this, event.number)
                val wasActive = p.voipActive && activeKey == sbn.key
                p.voipActive = true; p.voipApp = event.app; p.voipNumber = event.number ?: event.caller; p.voipIsContact = contact
                activeKey = sbn.key
                if (!contact && !wasActive) {
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
            }
            if (sbn.key == ringingKey) ringingKey = null
        }
    }

    private fun onIncomingCall(key: String, e: MessengerEvent.IncomingCall) {
        val p = app.prefs
        // contact names (not numbers) mean WhatsApp knows this person from your address book
        val contact = e.number == null || Contacts.contains(this, e.number)
        ringingKey = key
        p.voipApp = e.app; p.voipNumber = e.number ?: e.caller; p.voipIsContact = contact
        if (contact) return
        val num: String = e.number ?: return
        val now = System.currentTimeMillis()
        if ((warnedCalls[num] ?: 0L) > now - 30_000) return
        warnedCalls[num] = now
        val prior = p.senderRisk(num)
        val facts = CallFacts(num, false, p.homeCallingCode, p.countRecentCall(num), num in p.scamNumbers(),
            num in p.safeNumbers(), messenger = e.app, priorMessageLevel = prior?.first, priorTactics = prior?.second ?: emptyList())
        if (facts.userMarkedSafe) return
        val r = CallRisk.assess(facts)
        Warnings.show(this, Warning(
            level = r.level, score = r.score, source = "messenger_call", number = num,
            title = (if (e.video) "Видеозвонок" else "Звонок") + " в ${e.app} с незнакомого номера",
            reasons = r.reasons.map { it.text }, advice = r.advice,
        ))
    }

    private fun onMessage(e: MessengerEvent.Message) {
        val num = e.number
        val unknown = num != null && !Contacts.contains(this, num)
        val r = ScamAnalyzer.analyze(e.text)
        if (r.level == RiskLevel.LOW) return
        if (num != null) app.prefs.rememberSenderRisk(num, r.level, r.tactics.map { it.title })
        // messages from saved contacts are only flagged when clearly fraudulent (their account may be hacked)
        if (!unknown && r.level != RiskLevel.HIGH) return
        Warnings.show(this, Warning(
            level = r.level, score = r.score, source = "messenger_text", number = num ?: e.sender,
            title = "Подозрительное сообщение в ${e.app} от «${e.sender}»",
            reasons = r.tactics.map { it.title } + (if (!unknown) listOf("Отправитель есть в контактах — возможно, его аккаунт взломан") else emptyList()),
            advice = r.tactics.sortedByDescending { it.weight }.take(2).map { it.advice },
        ))
    }

    companion object {
        @Volatile private var activeKey: String? = null
        @Volatile private var ringingKey: String? = null
    }
}
