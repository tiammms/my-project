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
import ai.neuroshield.app.Perms
import ai.neuroshield.app.core.CallChannel
import ai.neuroshield.app.core.CallEvent
import ai.neuroshield.app.core.CallFacts
import ai.neuroshield.app.core.CallRisk
import ai.neuroshield.app.core.CallRiskResult
import ai.neuroshield.app.core.CallStateGuard
import ai.neuroshield.app.core.CellularCallWindow
import ai.neuroshield.app.core.Reason
import ai.neuroshield.app.core.ReputationSources
import ai.neuroshield.app.core.RiskLevel
import ai.neuroshield.app.core.SpeechStatus
import ai.neuroshield.app.listen.ListenService
import ai.neuroshield.app.listen.ListenState
import ai.neuroshield.app.listen.SpeechModels

/**
 * Android calls this service for every incoming call from a number that is NOT in contacts
 * (the app must hold the "call screening / caller ID & spam" role). The call is never blocked:
 * the service only assesses the number and warns before the user answers.
 */
class ScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val number = details.handle?.schemeSpecificPart
        val n = CallRisk.normalizeNumber(number)
        val p = app.prefs
        // the only case a call is ever rejected: the user marked this number as a scammer AND turned blocking on
        if (details.callDirection == Call.Details.DIRECTION_INCOMING && p.blockMarked && n != null && n in p.scamNumbers()) {
            respondToCall(details, CallResponse.Builder().setDisallowCall(true).setRejectCall(true)
                .setSkipCallLog(false).setSkipNotification(false).build())
            app.alerts.add("blocked_call", n, RiskLevel.HIGH, 100.0, "Звонок от отмеченного мошенника заблокирован",
                listOf("Вы отметили этот номер как мошеннический", "Включена блокировка отмеченных номеров"), null)
            return
        }
        respondToCall(details, CallResponse.Builder().build())   // otherwise: always allow
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) return
        CallTracker.onIncoming(this, number, screened = true)
    }
}

/** Follows the call state (ringing / in a call / ended), for incoming and outgoing calls. */
class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        @Suppress("DEPRECATION") val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        android.util.Log.i("SaqtanCall", "phone state $state number=${number != null}")
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> if (number != null) CallTracker.onIncoming(ctx, number, screened = false)
            TelephonyManager.EXTRA_STATE_OFFHOOK -> CallTracker.onOffhook(ctx, number)
            TelephonyManager.EXTRA_STATE_IDLE -> CallTracker.onIdle(ctx)
        }
    }
}

object CallTracker {
    /** how long the automatic start of speech analysis may take before the one-tap fallback is shown */
    private const val AUTO_LISTEN_CHECK_MS = 6_000L
    private var lastWarnedNumber: String? = null
    private var lastWarnedAt = 0L

    /**
     * Drops a saved "ringing / in a call" state that can no longer be true (audit C-03): saved before the phone
     * restarted, the system reports no call (an IDLE broadcast was missed), or far too old. A call session left
     * from before a restart is discarded without a report (its real end and duration are unknown).
     * Called when the app process starts and before every decision that depends on the call state.
     */
    fun reconcile(ctx: Context) {
        val p = ctx.app.prefs
        val stored = p.callState
        if (stored == "IDLE") return
        val now = System.currentTimeMillis()
        val bootAt = now - android.os.SystemClock.elapsedRealtime()
        val eff = CallStateGuard.effective(stored, p.callStateAt, now, bootAt, systemInCall(ctx))
        if (eff == stored) return
        android.util.Log.i("SaqtanCall", "stale call state $stored reset")
        p.callState = "IDLE"
        CallSessions.dropIfStartedBefore(ctx, if (p.callStateAt < bootAt) bootAt else now)
    }

    /** TelecomManager.isInCall needs READ_PHONE_STATE; null when it cannot be asked. */
    private fun systemInCall(ctx: Context): Boolean? = try {
        if (!Perms.granted(ctx, android.Manifest.permission.READ_PHONE_STATE)) null
        else ctx.getSystemService(android.telecom.TelecomManager::class.java)?.isInCall
    } catch (_: Exception) { null }

    fun isCallActiveOrRecent(ctx: Context, withinMs: Long = 10 * 60_000): Boolean {
        reconcile(ctx)
        val p = ctx.app.prefs
        return p.callState == "OFFHOOK" || p.callState == "RINGING" || System.currentTimeMillis() - p.callEndedAt < withinMs
    }

    data class RiskyCall(val number: String?, val active: Boolean, val channel: String)

    /** A cellular or messenger call with someone not in contacts that is going on now or ended < 10 min ago. */
    fun riskyCall(ctx: Context, withinMs: Long = 10 * 60_000): RiskyCall? {
        reconcile(ctx)        // a call left over from before a restart must not turn an ordinary SMS code into an alarm
        val p = ctx.app.prefs
        val now = System.currentTimeMillis()
        CellularCallWindow.riskyCall(p.callState, p.callEndedAt, now, p.callIsContact, withinMs)?.let { active ->
            return RiskyCall(p.callNumber, active, "звонка")
        }
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
        android.util.Log.i("SaqtanCall", "incoming screened=$screened")
        lastWarnedNumber = n; lastWarnedAt = now
        if (p.blockMarked && n != null && n in p.scamNumbers()) { p.callState = "RINGING"; return }   // already rejected
        val contact = Contacts.contains(ctx, n)
        p.callState = "RINGING"; p.callNumber = n; p.callIsContact = contact
        if (contact) return
        val prior = p.senderRisk(n)
        val (high, medium) = app.alerts.reportCounts(n)
        val facts = CallFacts(n, contact, p.homeCallingCode, p.countRecentCall(n), n != null && n in p.scamNumbers(), n != null && n in p.safeNumbers(),
            priorMessageLevel = prior?.first, priorTactics = prior?.second ?: emptyList(), highReports = high, mediumReports = medium)
        if (facts.userMarkedSafe) return
        val r = withReputation(CallRisk.assess(facts), n)
        CallSessions.start(ctx, n, CallChannel.CELLULAR, null, r)
        if (r.level == RiskLevel.LOW && !p.warnUnknownCallers) return
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
        Warnings.hide(ctx)
        if (p.callIsContact || !CallSessions.active(ctx)) return
        CallSessions.on(ctx, CallEvent.Answered(System.currentTimeMillis()))
        // automatic speech analysis: only when it can actually run, otherwise record why and offer the manual way
        val status = when {
            !p.autoListen && !p.recordCalls -> SpeechStatus.AUTO_OFF
            !Perms.mic(ctx) -> SpeechStatus.NO_PERMISSION
            SpeechModels.modelDir(ctx, p.speechLang) == null && !p.recordCalls -> SpeechStatus.NO_MODEL
            !Perms.overlay(ctx) -> SpeechStatus.AUTO_OFF
            else -> null
        }
        if (status == null) {
            // open the app for a moment (allowed with the overlay permission), start listening, go back to the call
            Warnings.openListen(ctx, auto = true)
            // some phones (OPPO/ColorOS, a call answered on the lock screen) silently refuse to bring the app forward, so the
            // microphone never starts and nothing at all happens during the call; then say so and offer the one-tap way
            val app = ctx.applicationContext
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (app.app.prefs.callState == "OFFHOOK" && !ListenState.ui.value.running) {
                    android.util.Log.i("SaqtanCall", "auto listening did not start")
                    CallSessions.on(app, CallEvent.SpeechState(System.currentTimeMillis(), SpeechStatus.BLOCKED))
                    Warnings.showListenOffer(app); Warnings.notifyListenOffer(app, app.app.prefs.callNumber)
                }
            }, AUTO_LISTEN_CHECK_MS)
        } else {
            CallSessions.on(ctx, CallEvent.SpeechState(System.currentTimeMillis(), status))
            if (p.offerListening) { Warnings.showListenOffer(ctx); Warnings.notifyListenOffer(ctx, p.callNumber) }
        }
    }

    /** Adds verdicts of connected outside reputation sources (none in this build) to the number check. */
    fun withReputation(r: CallRiskResult, n: String?): CallRiskResult {
        if (n == null) return r
        val hits = ReputationSources.lookup(n)
        if (hits.isEmpty()) return r
        val pts = hits.maxOf { if (it.level == RiskLevel.HIGH) 75 else if (it.level == RiskLevel.MEDIUM) 40 else 0 }
        val score = maxOf(r.score, pts.toDouble())
        return r.copy(score = score, level = ai.neuroshield.app.core.levelOf(score),
            reasons = r.reasons + hits.map { Reason("${it.source}: ${it.reason}", if (it.level == RiskLevel.LOW) 0 else pts) })
    }

    fun onIdle(ctx: Context) {
        val p = ctx.app.prefs
        if (p.callState != "IDLE") p.callEndedAt = System.currentTimeMillis()
        p.callState = "IDLE"
        Warnings.cancelLowCallTip(ctx)
        Warnings.hideListenOffer()
        Warnings.cancelListenOffer(ctx)
        ListenService.stop(ctx, reason = "Звонок завершён")
        CallSessions.end(ctx, CallChannel.CELLULAR)
    }
}
