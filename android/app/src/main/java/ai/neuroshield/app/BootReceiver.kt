package ai.neuroshield.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Protection after a restart does not depend on this receiver: Android delivers calls (CallScreeningService),
 * PHONE_STATE and SMS_RECEIVED to the app without it being opened. This receiver only
 *  - drops a call state saved before the restart (CallTracker.reconcile), and
 *  - records that the system did start Saqtan after the restart. On OPPO/ColorOS, Xiaomi, vivo and Huawei an app
 *    whose "auto-start" is off is not started at all, so the missing record is how the Protection screen
 *    can tell the user that auto-start is blocked (PhoneSetup.checks), instead of only "check by hand".
 * Not exported: boot broadcasts come from the system, which reaches non-exported receivers.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.action ?: return
        if (a != Intent.ACTION_BOOT_COMPLETED && a != "android.intent.action.QUICKBOOT_POWERON" &&
            a != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching { ai.neuroshield.app.call.CallTracker.reconcile(ctx) }
        if (a != Intent.ACTION_MY_PACKAGE_REPLACED) BootLog.markStarted(ctx)
        android.util.Log.i("SaqtanBoot", "started by the system: ${a.substringAfterLast('.')}")
    }
}

/** When the system last started Saqtan after a restart (a plain file of the app, no personal data). */
object BootLog {
    private const val FILE = "saqtan_boot"
    private const val KEY = "started_at"

    fun markStarted(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putLong(KEY, System.currentTimeMillis()).apply()
    }

    /**
     * true: the system started Saqtan after the latest restart; false: the phone restarted after the app was installed
     * and Saqtan was not started (auto-start blocked, or the boot broadcast has not arrived yet - some phones send it
     * a few minutes late); null: no restart since the app was installed, nothing to judge yet.
     */
    fun startedAfterLastBoot(ctx: Context): Boolean? = try {
        val now = System.currentTimeMillis()
        val bootAt = now - SystemClock.elapsedRealtime()
        val seen = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong(KEY, 0L)
        val installed = ctx.packageManager.getPackageInfo(ctx.packageName, 0).firstInstallTime
        ai.neuroshield.app.core.BootJudge.judge(bootAt, seen, installed, SystemClock.elapsedRealtime())
    } catch (_: Exception) { null }
}
