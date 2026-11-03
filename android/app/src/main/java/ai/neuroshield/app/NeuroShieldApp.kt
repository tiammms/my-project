package ai.neuroshield.app

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import ai.neuroshield.app.data.AlertStore
import ai.neuroshield.app.data.Prefs
import androidx.core.content.ContextCompat

class NeuroShieldApp : Application() {
    lateinit var prefs: Prefs; private set
    lateinit var alerts: AlertStore; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        ai.neuroshield.app.core.PhoneNumbers.homeCode = prefs.homeCallingCode
        I18n.kk = prefs.uiLang == "kk"
        alerts = AlertStore(this)
        // a "call in progress" saved before a restart (or a missed IDLE broadcast) must not stay in force
        runCatching { ai.neuroshield.app.call.CallTracker.reconcile(this) }
        Thread {
            runCatching { alerts.purgeOld(); alerts.encryptLegacyExcerpts() }
            // decrypted copies made for playback/voice check are deleted after use; this removes any left behind
            // if the app was killed in the middle (they must never outlive the session)
            runCatching { cacheDir.listFiles { f -> f.name.startsWith("play_rec_") && f.name.endsWith(".wav") }?.forEach { it.delete() } }
            // a recording interrupted by a crash never got its history entry: add one, so it can be played or deleted
            // (the process has just started, so no recording is being written now)
            runCatching {
                val known = alerts.audioIds() + listOfNotNull(ai.neuroshield.app.call.CallSessions.pendingRecording(this))
                ai.neuroshield.app.data.CallRecordings.ids(this).filter { it !in known }.forEach { id ->
                    alerts.add("recording", null, ai.neuroshield.app.core.RiskLevel.LOW, 0.0, "Запись разговора",
                        listOf("Запись оборвалась: приложение было остановлено во время звонка"), null, id)
                }
            }
        }.start()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, tr("Предупреждения о мошенничестве", "Алаяқтық туралы ескертулер"), NotificationManager.IMPORTANCE_HIGH).apply {
            description = tr("Срочные предупреждения во время звонков и при получении кодов", "Қоңырау кезінде және код келгенде шұғыл ескертулер")
            enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_CALL, tr("Защита звонка", "Қоңырауды қорғау"), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = tr("Предложение включить анализ речи во время звонка с незнакомого номера", "Бейтаныс нөмірмен сөйлескенде сөйлеуді талдауды қосу ұсынысы")
        })
        nm.createNotificationChannel(NotificationChannel(CH_LISTEN, tr("Анализ речи", "Сөйлеуді талдау"), NotificationManager.IMPORTANCE_LOW).apply {
            description = tr("Показывается, пока приложение слушает звонок через микрофон", "Қосымша қоңырауды микрофон арқылы тыңдап тұрғанда көрсетіледі")
        })
    }

    companion object {
        const val CH_ALERTS = "alerts"
        const val CH_CALL = "call"
        const val CH_LISTEN = "listen"
        lateinit var instance: NeuroShieldApp; private set
    }
}

val Context.app: NeuroShieldApp get() = applicationContext as NeuroShieldApp

/** What the app is allowed to do right now (checked live, nothing cached). */
object Perms {
    fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    fun phone(ctx: Context) = granted(ctx, Manifest.permission.READ_PHONE_STATE) && granted(ctx, Manifest.permission.READ_CALL_LOG)
    fun contacts(ctx: Context) = granted(ctx, Manifest.permission.READ_CONTACTS)
    fun sms(ctx: Context) = granted(ctx, Manifest.permission.RECEIVE_SMS)
    fun mic(ctx: Context) = granted(ctx, Manifest.permission.RECORD_AUDIO)
    fun camera(ctx: Context) = granted(ctx, Manifest.permission.CAMERA)
    fun notifications(ctx: Context) =
        android.os.Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
    fun overlay(ctx: Context) = Settings.canDrawOverlays(ctx)
    fun notificationAccess(ctx: Context) =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    fun screeningRole(ctx: Context): Boolean {
        val rm = ctx.getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }
}

/**
 * Lock-screen privacy: numbers, SMS senders and call details are shown only after unlocking.
 * On the lock screen the user sees a neutral notice that Saqtan has a warning.
 */
object LockScreen {
    /** On the lock screen only [title] and "unlock to see" are shown, never the number or the message text. */
    fun hideDetails(b: androidx.core.app.NotificationCompat.Builder, ctx: Context, channel: String, title: String = "Saqtan",
                    text: String = tr("Есть предупреждение. Разблокируйте телефон, чтобы посмотреть", "Ескерту бар. Көру үшін телефонның құлпын ашыңыз")): androidx.core.app.NotificationCompat.Builder =
        b.setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(androidx.core.app.NotificationCompat.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
                .build())
}

object Contacts {
    /** true if the number is saved in the phone's contacts (needs READ_CONTACTS; false when not granted). */
    fun contains(ctx: Context, number: String?): Boolean {
        if (number.isNullOrBlank() || !Perms.contacts(ctx)) return false
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.count > 0 } ?: false
        } catch (e: Exception) { false }
    }

    /**
     * true if a saved contact has exactly this display name (case-insensitive). Only a hint: a messenger shows the name it
     * wants, and a stranger can use the same name (see SenderStatus).
     */
    fun hasName(ctx: Context, name: String?): Boolean {
        val n = name?.trim().orEmpty()
        if (n.isEmpty() || !Perms.contacts(ctx)) return false
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(n))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null)?.use { c ->
                var found = false
                while (!found && c.moveToNext()) found = c.getString(0)?.trim().equals(n, ignoreCase = true)
                found
            } ?: false
        } catch (e: Exception) { false }
    }
}
