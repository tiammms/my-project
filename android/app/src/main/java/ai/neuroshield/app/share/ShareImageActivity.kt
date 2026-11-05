package ai.neuroshield.app.share

import ai.neuroshield.app.core.VoiceInput
import ai.neuroshield.app.ocr.OcrData
import ai.neuroshield.app.ui.ImageCheckScreen
import ai.neuroshield.app.ui.NS
import ai.neuroshield.app.ui.NeuroTheme
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
 * "Поделиться → Saqtan" for a screenshot or photo. Exported, because another app's share sheet starts it; it reads only
 * the one shared content:// image (size limited) and starts nothing else. The only network use is the one-time
 * text-recognition download, and only when the user presses the button.
 */
class ShareImageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        OcrData.refresh(this)
        val uri = shared(intent)
        setContent { NeuroTheme { Box(Modifier.fillMaxSize().background(NS.bg).systemBarsPadding()) { ImageCheckScreen(uri, onBack = { finish() }) } } }
    }

    private fun shared(i: Intent?): Uri? {
        if (i == null || i.action != Intent.ACTION_SEND || !VoiceInput.acceptImageMime(i.type)) return null
        val u = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        return (u ?: i.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri)?.takeIf { VoiceInput.acceptScheme(it.scheme) }
    }
}
