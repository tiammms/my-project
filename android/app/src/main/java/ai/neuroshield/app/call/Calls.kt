package ai.neuroshield.app.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telephony.TelephonyManager
import ai.neuroshield.app.Contacts
import ai.neuroshield.app.alert.Warning
import ai.neuroshield.app.alert.Warnings
import ai.neuroshield.app.app
import ai.neuroshield.app.core.CallFacts
import ai.neuroshield.app.core.CallRisk
import ai.neuroshield.app.core.RiskLevel
import ai.neuroshield.app.listen.ListenService

/**
 * Android calls this service for every incoming call from a number that is NOT in contacts
 * (the app must hold the "call screening / caller ID & spam" role). The call is never blocked:
 * the service only assesses the number and warns before the user answers.
 */
class ScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        respondToCall(details, CallResponse.Builder().build())   // allow the call, always
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) return
        val number = details.handle?.schemeSpecificPart
        CallTracker.onIncoming(this, number, screened = true)
    }
}

/** Follows the call state (ringing / in a call / ended), for incoming and outgoing calls. */
class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        @Suppress("DEPRECATION") val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> if (number != null) CallTracker.onIncoming(ctx, number, screened = false)
            TelephonyManager.EXTRA_STATE_OFFHOOK -> CallTracker.onOffhook(ctx, number)
            TelephonyManager.EXTRA_STATE_IDLE -> CallTracker.onIdle(ctx)
        }
    }
}

object CallTracker {
    private var lastWarnedNumber: String? = null
    private var lastWarnedAt = 0L

    fun isCallActiveOrRecent(ctx: Context, withinMs: Long = 10 * 60_000): Boolean {
        val p = ctx.app.prefs
        return p.callState == "OFFHOOK" || p.callState == "RINGING" || System.currentTimeMillis() - p.callEndedAt < withinMs
    }

    data class RiskyCall(val number: String?, val active: Boolean, val channel: String)

    /** A cellular or messenger call with someone not in contacts that is going on now or ended < 10 min ago. */
    fun riskyCall(ctx: Context, withinMs: Long = 10 * 60_000): RiskyCall? {
        val p = ctx.app.prefs
        val now = System.currentTimeMillis()
        val cellActive = p.callState == "OFFHOOK" || p.callState == "RINGING"
        if ((cellActive || now - p.callEndedAt < withinMs) && !p.callIsContact)
            return RiskyCall(p.callNumber, p.callState == "OFFHOOK", "звонка")
        if ((p.voipActive || now - p.voipEndedAt < withinMs) && !p.voipIsContact)
            return RiskyCall(p.voipNumber, p.voipActive, "звонка в ${p.voipApp ?: "мессенджере"}")
        return null
    }

    fun onIncoming(ctx: Context, number: String?, screened: Boolean) {
        val app = ctx.app
        val p = app.prefs
        val n = CallRisk.normalizeNumber(number)
        val now = System.currentTimeMillis()
        // the screening service and the phone-state broadcast both report the same call: handle it once
        if (n == lastWarnedNumber && now - lastWarnedAt < 30_000) { p.callState = "RINGING"; return }
        lastWarnedNumber = n; lastWarnedAt = now
        val contact = Contacts.contains(ctx, n)
        p.callState = "RINGING"; p.callNumber = n; p.callIsContact = contact
        if (contact) return
        val facts = CallFacts(n, contact, p.homeCallingCode, p.countRecentCall(n), n != null && n in p.scamNumbers(), n != null && n in p.safeNumbers())
        val r = CallRisk.assess(facts)
        if (r.level == RiskLevel.LOW && !p.warnUnknownCallers) return
        if (facts.userMarkedSafe) return
        Warnings.show(ctx, Warning(
            level = r.level, score = r.score, source = "incoming_call", number = n ?: "скрытый номер",
            title = if (r.level == RiskLevel.LOW) "Звонит незнакомый номер" else "Подозрительный входящий звонок",
            reasons = r.reasons.map { it.text } + (if (screened) emptyList() else listOf("Проверка до ответа: роль «защита от спама» не выдана")),
            advice = r.advice,
        ))
    }

    fun onOffhook(ctx: Context, number: String?) {
        val p = ctx.app.prefs
        val was = p.callState
        p.callState = "OFFHOOK"
        p.callStartedAt = System.currentTimeMillis()
        if (was != "RINGING") {          // outgoing call: number usually unknown to apps since Android 10
            val n = CallRisk.normalizeNumber(number)
            p.callNumber = n; p.callIsContact = Contacts.contains(ctx, n)
        }
        Warnings.hide()
        if (!p.callIsContact && p.autoListen && ai.neuroshield.app.Perms.overlay(ctx)) {
            // automatic mode: open the app for a moment (allowed with the overlay permission), start listening, go back to the call
            Warnings.openListen(ctx, auto = true)
        } else if (!p.callIsContact && p.offerListening) {
            Warnings.showListenOffer(ctx)
            Warnings.notifyListenOffer(ctx, p.callNumber)
        }
    }

    fun onIdle(ctx: Context) {
        val p = ctx.app.prefs
        if (p.callState != "IDLE") p.callEndedAt = System.currentTimeMillis()
        p.callState = "IDLE"
        Warnings.hideListenOffer()
        Warnings.cancelListenOffer(ctx)
        ListenService.stop(ctx, reason = "Звонок завершён")
    }
}
