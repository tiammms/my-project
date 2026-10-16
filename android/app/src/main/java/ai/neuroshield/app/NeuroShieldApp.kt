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
        alerts = AlertStore(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Предупреждения о мошенничестве", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Срочные предупреждения во время звонков и при получении кодов"
            enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_CALL, "Защита звонка", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Предложение включить анализ речи во время звонка с незнакомого номера"
        })
        nm.createNotificationChannel(NotificationChannel(CH_LISTEN, "Анализ речи", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Показывается, пока приложение слушает звонок через микрофон"
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

object Contacts {
    /** true if the number is saved in the phone's contacts (needs READ_CONTACTS; false when not granted). */
    fun contains(ctx: Context, number: String?): Boolean {
        if (number.isNullOrBlank() || !Perms.contacts(ctx)) return false
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.count > 0 } ?: false
        } catch (e: Exception) { false }
    }
}
