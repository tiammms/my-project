package ai.neuroshield.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import ai.neuroshield.app.Contacts
import ai.neuroshield.app.alert.Warning
import ai.neuroshield.app.alert.Warnings
import ai.neuroshield.app.app
import ai.neuroshield.app.call.CallTracker
import ai.neuroshield.app.core.RiskLevel
import ai.neuroshield.app.core.ScamAnalyzer
import ai.neuroshield.app.core.SmsCode

/**
 * The most common phone-scam step: the caller makes the bank send a one-time code and asks the victim to read it out.
 * When a code arrives while (or shortly after) the user talks to a number that is not in contacts, warn loudly.
 * The SMS text is analysed in memory and not stored.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (msgs.isEmpty()) return
        val sender = msgs[0].displayOriginatingAddress
        val body = msgs.joinToString("") { it.displayMessageBody ?: "" }
        val p = ctx.app.prefs
        val code = SmsCode.find(body)
        val inRiskyCall = CallTracker.isCallActiveOrRecent(ctx) && !p.callIsContact
        if (code != null && inRiskyCall) {
            val active = p.callState == "OFFHOOK"
            Warnings.show(ctx, Warning(
                level = RiskLevel.HIGH, score = 90.0, source = "sms_code", number = p.callNumber ?: "номер неизвестен",
                title = "Пришёл код из SMS " + (if (active) "во время разговора" else "сразу после разговора") + " с незнакомым номером",
                reasons = listOf("Код ${code.masked} от «$sender»",
                    "Звонящего нет в ваших контактах",
                    "Так действуют мошенники: просят «продиктовать код для отмены операции»"),
                advice = listOf("НЕ называйте этот код никому", "Положите трубку и позвоните в банк сами"),
                offerListening = active,
            ))
            return
        }
        if (Contacts.contains(ctx, sender)) return
        val r = ScamAnalyzer.analyze(body)
        if (r.level != RiskLevel.LOW) {
            Warnings.show(ctx, Warning(
                level = r.level, score = r.score, source = "sms_text", number = sender,
                title = "Подозрительное SMS от «$sender»",
                reasons = r.tactics.map { it.title }, advice = r.tactics.take(2).map { it.advice },
            ))
        }
    }
}
