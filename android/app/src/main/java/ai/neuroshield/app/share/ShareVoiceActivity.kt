package ai.neuroshield.app.share

import ai.neuroshield.app.MainActivity
import ai.neuroshield.app.core.VoiceInput
import ai.neuroshield.app.listen.SpeechModels
import ai.neuroshield.app.ui.NS
import ai.neuroshield.app.ui.NeuroTheme
import ai.neuroshield.app.ui.VoiceMessageScreen
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier

/**
 * "Поделиться → Saqtan" for a voice message from WhatsApp, Telegram or any app. Exported, because the share sheet
 * of another app starts it; it only reads the one shared content:// stream (the sender grants that permission),
 * checks size and length, and starts nothing else: no microphone, no network.
 */
class ShareVoiceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        SpeechModels.refresh(this)
        val uri = shared(intent)
        setContent {
            NeuroTheme {
                Box(Modifier.fillMaxSize().background(NS.bg).systemBarsPadding()) {
                    VoiceMessageScreen(uri, onBack = { finish() }, onNeedModel = {
                        startActivity(MainActivity.internalIntent(this@ShareVoiceActivity)
                            .putExtra(MainActivity.EXTRA_ROUTE, "protection").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    })
                }
            }
        }
    }

    private fun shared(i: Intent?): Uri? {
        if (i == null || i.action != Intent.ACTION_SEND || !VoiceInput.acceptMime(i.type)) return null
        val u = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        return (u ?: i.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri)?.takeIf { VoiceInput.acceptScheme(it.scheme) }
    }
}
