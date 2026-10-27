package ai.neuroshield.app.call

import ai.neuroshield.app.tr
import ai.neuroshield.app.I18n

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import ai.neuroshield.app.MainActivity
import ai.neuroshield.app.NeuroShieldApp
import ai.neuroshield.app.LockScreen
import ai.neuroshield.app.Perms
import ai.neuroshield.app.R
import ai.neuroshield.app.app
import ai.neuroshield.app.core.CallChannel
import ai.neuroshield.app.core.CallEvent
import ai.neuroshield.app.core.CallReport
import ai.neuroshield.app.core.CallRiskResult
import ai.neuroshield.app.core.CallSession
import ai.neuroshield.app.core.Evidence
import ai.neuroshield.app.core.Reason
import ai.neuroshield.app.core.ReputationSources
import ai.neuroshield.app.core.RiskLevel
import ai.neuroshield.app.core.SpeechStatus
import ai.neuroshield.app.core.Tactic
import ai.neuroshield.app.core.TextRisk
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * The automatic chain for one call: ring → number check → (answer) → SMS-code and speech checks → hang-up → report.
 * Broadcast receivers and services run in short-lived processes, so the events of the current call are kept in
 * private preferences (no audio, no SMS text, only results) and the report is built when the call ends.
 */
object CallSessions {
    private const val KEY = "call_session"
    private const val NOTIF_REPORT = 3101
    private const val TAG = "SaqtanCall"

    private fun load(ctx: Context): CallSession? {
        val raw = ctx.getSharedPreferences("neuroshield_session", Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return try {
            val arr = JSONArray(raw)
            val s = CallSession(ReputationSources.status(), ai.neuroshield.app.listen.VoiceModel.bundled(ctx))
            for (i in 0 until arr.length()) s.on(decode(arr.getJSONObject(i)))
            s
        } catch (_: Exception) { null }
    }

    private fun save(ctx: Context, s: CallSession?) {
        val e = ctx.getSharedPreferences("neuroshield_session", Context.MODE_PRIVATE).edit()
        if (s == null) e.remove(KEY) else e.putString(KEY, JSONArray(s.events.map { encode(it) }).toString())
        e.commit()
    }

    @Synchronized fun start(ctx: Context, number: String?, channel: CallChannel, app: String?, pre: CallRiskResult) {
        val prev = load(ctx)
        if (prev != null && prev.isStarted && !prev.isEnded) {
            // the screening service and the phone-state broadcast report the same call; a new number = a new call
            val r = prev.events.first() as CallEvent.Ringing
            if (r.number == number && System.currentTimeMillis() - r.tMs < 120_000) return
            finish(ctx, prev)
        }
        val s = CallSession(ReputationSources.status(), ai.neuroshield.app.listen.VoiceModel.bundled(ctx))
        s.on(CallEvent.Ringing(System.currentTimeMillis(), number, channel, app, pre))
        save(ctx, s)
        Log.i(TAG, "start $channel level=${pre.level}")
    }

    /** Adds an event to the current call, if there is one (calls with contacts have no session). */
    @Synchronized fun on(ctx: Context, e: CallEvent) {
        val s = load(ctx) ?: return
        if (s.isEnded) return
        Log.i(TAG, "event ${e.javaClass.simpleName}")
        s.on(e)
        save(ctx, s)
    }

    /** Links a call recording (its id) to the current call: the report after the call gets a "listen" button. */
    fun attachRecording(ctx: Context, id: String) {
        ctx.getSharedPreferences("neuroshield_session", Context.MODE_PRIVATE).edit().putString("recording", id).commit()
    }

    private fun takeRecording(ctx: Context): String? {
        val p = ctx.getSharedPreferences("neuroshield_session", Context.MODE_PRIVATE)
        return p.getString("recording", null).also { p.edit().remove("recording").commit() }
    }

    fun active(ctx: Context): Boolean = load(ctx)?.let { it.isStarted && !it.isEnded } ?: false

    /** Ends the current call. [channel] = only if the current call came through that channel. */
    @Synchronized fun end(ctx: Context, channel: CallChannel? = null) {
        val s = load(ctx) ?: run { Log.i(TAG, "end: no session"); return }
        if (channel != null && (s.events.firstOrNull() as? CallEvent.Ringing)?.channel != channel) return
        if (!s.isEnded) s.on(CallEvent.Ended(System.currentTimeMillis()))
        finish(ctx, s)
    }

    private fun finish(ctx: Context, s: CallSession) {
        save(ctx, null)
        val r = s.report() ?: return
        Log.i(TAG, "report level=${r.level} answered=${r.answered}")
        val reasons = r.findings + r.checks.map { "Проверка — $it" } + r.advice.map { "Совет — $it" }
        val id = ctx.app.alerts.add("call_report", r.number ?: "скрытый номер", r.level, r.score, r.title, reasons, null, takeRecording(ctx))
        notifyReport(ctx, r, id)
    }

    private fun notifyReport(ctx: Context, r: CallReport, alertId: Long) {
        if (!Perms.notifications(ctx)) { Log.i(TAG, "no notification permission"); return }
        val open = PendingIntent.getActivity(ctx, 31, MainActivity.internalIntent(ctx).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_ROUTE, "history"); putExtra(MainActivity.EXTRA_ALERT_ID, alertId)
        }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val who = r.number ?: tr("скрытый номер", "жасырын нөмір")
        val how = when { r.channel == CallChannel.MESSENGER -> tr("звонок в ${r.app ?: "мессенджере"}", "${r.app ?: "мессенджер"} арқылы қоңырау"); r.answered -> tr("звонок ${r.durationSec / 60} мин ${r.durationSec % 60} с", "қоңырау ${r.durationSec / 60} мин ${r.durationSec % 60} с"); else -> tr("пропущенный звонок", "қабылданбаған қоңырау") }
        val body = (listOf("${I18n.core(r.level.ru)} · $who · $how") + r.findings.take(4).map { "• " + I18n.core(it) } + r.advice.take(2).map { "→ " + I18n.core(it) }).joinToString("\n")
        val risky = r.level != RiskLevel.LOW
        val n = NotificationCompat.Builder(ctx, if (risky) NeuroShieldApp.CH_ALERTS else NeuroShieldApp.CH_CALL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(tr("Отчёт о звонке: ", "Қоңырау туралы есептеме: ") + I18n.core(r.title))
            .setContentText("$who · ${I18n.core(r.level.ru)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (risky) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open).setAutoCancel(true)
        ReportActionReceiver.addTo(n, ctx, r.number, risky, NOTIF_REPORT)
        LockScreen.hideDetails(n, ctx, if (risky) NeuroShieldApp.CH_ALERTS else NeuroShieldApp.CH_CALL)
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_REPORT, n.build())
    }

    // ---- JSON (results only: no audio, no SMS text; transcript fragments only as tactic evidence) ----
    private fun encode(e: CallEvent): JSONObject = JSONObject().put("t", e.tMs).apply {
        when (e) {
            is CallEvent.Ringing -> put("k", "ring").put("n", e.number).put("ch", e.channel.name).put("app", e.app)
                .put("score", e.pre.score).put("level", e.pre.level.name)
                .put("reasons", JSONArray(e.pre.reasons.map { JSONObject().put("x", it.text).put("p", it.points) }))
                .put("advice", JSONArray(e.pre.advice))
            is CallEvent.Answered -> put("k", "ans")
            is CallEvent.SmsCodeArrived -> put("k", "sms").put("from", e.sender).put("m", e.masked)
            is CallEvent.Speech -> put("k", "sp").put("score", e.risk.score).put("level", e.risk.level.name).put("w", e.risk.words)
                .put("tac", JSONArray(e.risk.tactics.map { t -> JSONObject().put("id", t.id).put("ti", t.title).put("ad", t.advice)
                    .put("we", t.weight).put("ev", t.evidence.firstOrNull()?.match ?: "") }))
            is CallEvent.SpeechState -> put("k", "ss").put("s", e.status.name)
            is CallEvent.Voice -> put("k", "vo").put("w", e.check.windows).put("syn", e.check.synthetic)
                .put("max", e.check.maxP).put("thr", e.check.threshold)
            is CallEvent.Ended -> put("k", "end")
        }
    }

    private fun decode(o: JSONObject): CallEvent {
        val t = o.getLong("t")
        return when (o.getString("k")) {
            "ring" -> {
                val rs = o.getJSONArray("reasons"); val ad = o.getJSONArray("advice")
                CallEvent.Ringing(t, o.optString("n").ifEmpty { null }, CallChannel.valueOf(o.getString("ch")), o.optString("app").ifEmpty { null },
                    CallRiskResult(o.getDouble("score"), RiskLevel.valueOf(o.getString("level")),
                        (0 until rs.length()).map { Reason(rs.getJSONObject(it).getString("x"), rs.getJSONObject(it).getInt("p")) },
                        (0 until ad.length()).map { ad.getString(it) }))
            }
            "ans" -> CallEvent.Answered(t)
            "sms" -> CallEvent.SmsCodeArrived(t, o.getString("from"), o.getString("m"))
            "sp" -> {
                val ta = o.getJSONArray("tac")
                CallEvent.Speech(t, TextRisk(o.getDouble("score"), RiskLevel.valueOf(o.getString("level")),
                    (0 until ta.length()).map { val x = ta.getJSONObject(it)
                        Tactic(x.getString("id"), x.getString("ti"), x.getString("ad"), x.getInt("we"),
                            x.getString("ev").let { m -> if (m.isEmpty()) emptyList() else listOf(Evidence(m, m)) },
                            ai.neuroshield.app.core.ScamAnalyzer.isWeak(x.getString("id"))) }, o.getInt("w")))
            }
            "ss" -> CallEvent.SpeechState(t, SpeechStatus.valueOf(o.getString("s")))
            "vo" -> CallEvent.Voice(t, ai.neuroshield.app.core.VoiceCheck(o.getInt("w"), o.getInt("syn"), o.getDouble("max"), o.getDouble("thr")))
            else -> CallEvent.Ended(t)
        }
    }
}
