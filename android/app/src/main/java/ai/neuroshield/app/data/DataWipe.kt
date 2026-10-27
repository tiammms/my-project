package ai.neuroshield.app.data

import android.app.NotificationManager
import android.content.Context
import ai.neuroshield.app.app

/**
 * "Delete all data": history and local reputation (database file), settings and number marks,
 * the state of the current call, the Keystore key for fragments, and Saqtan notifications.
 * Downloaded speech models contain no personal data; they are removed separately on the "Protection" tab.
 */
object DataWipe {
    fun all(ctx: Context) {
        val a = ctx.app
        a.alerts.wipe()
        CallRecordings.deleteAll(ctx)
        a.prefs.wipe()
        ctx.getSharedPreferences("neuroshield_session", Context.MODE_PRIVATE).edit().clear().commit()
        LocalCrypto.deleteKey()
        ctx.getSystemService(NotificationManager::class.java).cancelAll()
        ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }
}
