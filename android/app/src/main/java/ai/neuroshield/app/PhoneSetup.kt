package ai.neuroshield.app

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech

/**
 * Phone settings that decide whether a warning is seen on a locked phone and whether the app is started in the
 * background. Read from the system where Android allows it (ok = true/false); where a phone maker hides the state
 * (auto-start on OPPO/realme/Xiaomi/vivo/Huawei), ok = null and the button opens the right page to check by hand.
 */
object PhoneSetup {
    data class Check(val id: String, val title: String, val why: String, val ok: Boolean?)

    private val maker get() = Build.MANUFACTURER.lowercase()
    /** phones whose system stops apps in the background unless "auto-start" is allowed by hand */
    val strictMaker: Boolean get() = listOf("oppo", "realme", "oneplus", "xiaomi", "redmi", "poco", "vivo", "huawei", "honor").any { maker.contains(it) }

    fun checks(ctx: Context): List<Check> {
        val out = mutableListOf<Check>()
        out += Check("alerts", tr("Срочные уведомления", "Шұғыл хабарландырулар"),
            tr("Предупреждение всплывает и видно на экране блокировки", "Ескерту қалқып шығады және құлыптаулы экранда көрінеді"), alertsChannelOk(ctx))
        lockScreenNotificationsOn(ctx)?.let { on ->
            out += Check("lockscreen", tr("Уведомления на экране блокировки", "Құлыптаулы экрандағы хабарландырулар"),
                tr("Общая настройка телефона: без неё Android прячет их у всех приложений", "Телефонның жалпы баптауы: онсыз Android оларды барлық қосымшада жасырады"), on)
        }
        out += Check("battery", tr("Без ограничения батареи", "Батарея шектеусіз"),
            tr("Иначе система может усыпить Saqtan, и он не заметит звонок", "Әйтпесе жүйе Saqtan-ды ұйықтатып, қоңырауды байқамауы мүмкін"),
            try { ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) } catch (_: Exception) { null })
        if (strictMaker) out += Check("autostart", tr("Автозапуск", "Автоіске қосу"),
            tr("На ${Build.MANUFACTURER} без него Saqtan не запускается при звонке, пока его не откроют. Проверьте вручную", "${Build.MANUFACTURER} телефонында онсыз Saqtan қоңырау кезінде іске қосылмайды. Қолмен тексеріңіз"), null)
        out += Check("tts", tr("Голосовой движок", "Дауыс қозғалтқышы"),
            tr("Нужен, чтобы предупреждение звучало вслух", "Ескерту дауыстап айтылуы үшін қажет"), ttsEngineInstalled(ctx))
        return out
    }

    fun alertsChannelOk(ctx: Context): Boolean = try {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val ch = nm.getNotificationChannel(NeuroShieldApp.CH_ALERTS)
        nm.areNotificationsEnabled() && ch != null && ch.importance >= NotificationManager.IMPORTANCE_HIGH &&
            ch.lockscreenVisibility != Notification.VISIBILITY_SECRET
    } catch (_: Exception) { false }

    /** the phone-wide "show notifications on the lock screen" switch; null when the phone does not let apps read it */
    fun lockScreenNotificationsOn(ctx: Context): Boolean? = try {
        val v = Settings.Secure.getInt(ctx.contentResolver, "lock_screen_show_notifications", -1)
        if (v == -1) null else v != 0
    } catch (_: Exception) { null }

    fun ttsEngineInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isNotEmpty()
    } catch (_: Exception) { false }

    /** Opens the settings page for a check. Every phone maker names pages differently: each step falls back to a safer one. */
    fun open(ctx: Context, id: String) {
        val pkg = ctx.packageName
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        val tries: List<Intent> = when (id) {
            "alerts" -> listOf(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg).putExtra(Settings.EXTRA_CHANNEL_ID, NeuroShieldApp.CH_ALERTS),
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg), details)
            "lockscreen" -> listOf(Intent("android.settings.NOTIFICATION_SETTINGS"),
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg), details)
            "battery" -> listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), details)
            "autostart" -> listOf(
                // OPPO / realme / OnePlus (ColorOS)
                Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
                Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
                Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
                // Xiaomi / Redmi / POCO (MIUI, HyperOS)
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                // vivo
                Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
                // Huawei / Honor
                Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
                details)
            "tts" -> listOf(Intent("com.android.settings.TTS_SETTINGS"),
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.tts")), details)
            else -> listOf(details)
        }
        for (i in tries) {
            try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
        }
    }
}
