package ai.neuroshield.app.call

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import ai.neuroshield.app.app
import androidx.core.app.NotificationCompat

/**
 * Buttons in the call-report notification. Marking a number is done here without opening the app;
 * calling a trusted person or the bank only opens the dialer with the number filled in (the user presses "call").
 */
class ReportActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val number = i.getStringExtra(EXTRA_NUMBER) ?: return
        val scam = i.action == ACTION_MARK_SCAM
        ctx.app.prefs.mark(number, scam)
        Log.i("SaqtanCall", "report action: marked ${if (scam) "scam" else "safe"}")
        ctx.getSystemService(NotificationManager::class.java).cancel(i.getIntExtra(EXTRA_NOTIF, 0))
        Toast.makeText(ctx, if (scam) "Номер отмечен. Следующий звонок с него будет помечен как опасный"
            else "Номер отмечен как надёжный", Toast.LENGTH_LONG).show()
    }

    companion object {
        const val ACTION_MARK_SCAM = "ai.neuroshield.app.REPORT_MARK_SCAM"
        const val ACTION_MARK_SAFE = "ai.neuroshield.app.REPORT_MARK_SAFE"
        private const val EXTRA_NUMBER = "number"
        private const val EXTRA_NOTIF = "notif"

        private fun mark(ctx: Context, number: String, scam: Boolean, notif: Int): PendingIntent =
            PendingIntent.getBroadcast(ctx, if (scam) 41 else 42,
                Intent(ctx, ReportActionReceiver::class.java).setAction(if (scam) ACTION_MARK_SCAM else ACTION_MARK_SAFE)
                    .putExtra(EXTRA_NUMBER, number).putExtra(EXTRA_NOTIF, notif),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private fun dial(ctx: Context, code: Int, number: String): PendingIntent =
            PendingIntent.getActivity(ctx, code, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        /**
         * Risky report: "mark the number", "call a trusted person", "call the bank" (the last two only if set in settings).
         * Calm report: "mark the number" and "the number is safe". A notification shows at most three buttons.
         */
        fun addTo(b: NotificationCompat.Builder, ctx: Context, number: String?, risky: Boolean, notif: Int) {
            val p = ctx.app.prefs
            val actions = mutableListOf<Pair<String, PendingIntent>>()
            if (number != null) actions += "Отметить номер" to mark(ctx, number, true, notif)
            if (risky) {
                if (p.trustedNumber.isNotBlank()) actions += "Позвонить близкому" to dial(ctx, 43, p.trustedNumber)
                if (p.bankNumber.isNotBlank()) actions += "Позвонить в банк" to dial(ctx, 44, p.bankNumber)
            } else if (number != null) actions += "Номер надёжный" to mark(ctx, number, false, notif)
            actions.take(3).forEach { (label, pi) -> b.addAction(0, label, pi) }
        }
    }
}
