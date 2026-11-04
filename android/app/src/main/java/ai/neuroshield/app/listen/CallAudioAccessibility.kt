package ai.neuroshield.app.listen

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * EXPERIMENTAL, off by default; the user turns it on by hand in Settings → Accessibility.
 *
 * Since Android 10 the system gives ordinary apps silence from the microphone while a phone call is active
 * (measured on OPPO A52: a 30 s recording on speakerphone was silent). Android keeps capturing open for an app whose
 * accessibility service is running, and call-recording apps rely on this; whether it helps depends on the phone.
 * This service therefore does nothing itself: it reads no window content (canRetrieveWindowContent=false), performs no
 * actions and ignores every event. Its only effect is that Saqtan's own speech analysis (ListenService) may get sound.
 */
class CallAudioAccessibility : AccessibilityService() {
    override fun onServiceConnected() { connected = true; android.util.Log.i("SaqtanA11y", "connected") }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { connected = false; return super.onUnbind(intent) }
    override fun onDestroy() { connected = false; super.onDestroy() }

    companion object {
        @Volatile var connected = false; private set

        /** true when the user has turned the service on in the phone's Accessibility settings */
        fun enabled(ctx: Context): Boolean = try {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val full = ctx.packageName + "/" + CallAudioAccessibility::class.java.name
            val short = ctx.packageName + "/.listen.CallAudioAccessibility"
            list.split(':').any { it.equals(full, ignoreCase = true) || it.equals(short, ignoreCase = true) }
        } catch (_: Exception) { false }
    }
}
