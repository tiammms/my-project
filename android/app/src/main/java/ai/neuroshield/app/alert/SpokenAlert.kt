package ai.neuroshield.app.alert

import ai.neuroshield.app.I18n
import android.content.Context
import android.media.AudioAttributes
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Says the high-risk warning aloud (opt-in in the settings), for people who do not look at the screen during a call.
 * Uses the phone's own speech engine; nothing is sent anywhere. When the engine has no Kazakh voice, the Russian
 * phrase is used. The phrases contain no words the rules react to, so on speakerphone the app hearing its own
 * voice does not raise the risk (checked with the analyzer). At most once a minute.
 */
object SpokenAlert {
    const val RU = "Внимание. Возможно, это мошенник. Положите трубку и сами позвоните в банк."
    const val KK = "Абайлаңыз. Бұл алаяқ болуы мүмкін. Тұтқаны қойып, банкке өзіңіз қоңырау шалыңыз."
    private const val MIN_GAP_MS = 60_000L

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending = false
    private var lastAt = 0L

    /** [preview]: the sample played when the user turns the setting on; it does not use up the once-a-minute limit. */
    @Synchronized fun say(ctx: Context, preview: Boolean = false) {
        if (!preview) {
            val now = SystemClock.elapsedRealtime()
            if (lastAt != 0L && now - lastAt < MIN_GAP_MS) return
            lastAt = now
        }
        val t = tts
        if (t != null && ready) { speak(t); return }
        pending = true
        if (t == null) tts = try { TextToSpeech(ctx.applicationContext) { status -> onInit(status) } } catch (_: Exception) { null }
    }

    @Synchronized private fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) { try { t.shutdown() } catch (_: Exception) {}; tts = null; pending = false; return }
        ready = true
        try {
            t.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        } catch (_: Exception) {}
        if (pending) { pending = false; speak(t) }
    }

    private fun speak(t: TextToSpeech) {
        try {
            val kk = I18n.kk && t.setLanguage(Locale.forLanguageTag("kk-KZ")) >= TextToSpeech.LANG_AVAILABLE
            if (!kk) t.setLanguage(Locale.forLanguageTag("ru-RU"))
            t.speak(if (kk) KK else RU, TextToSpeech.QUEUE_FLUSH, null, "saqtan_warning")
        } catch (_: Exception) {}
    }
}
