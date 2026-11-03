package ai.neuroshield.app.alert

import ai.neuroshield.app.I18n
import ai.neuroshield.app.tr
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Says the high-risk warning aloud (opt-in in the settings), for people who do not look at the screen during a call.
 * Uses the phone's own speech engine (media volume); nothing is sent anywhere. When the engine has no Kazakh voice,
 * the Russian phrase is used. The phrases contain no words the rules react to, so on speakerphone the app hearing its
 * own voice does not raise the risk (checked with the analyzer). At most once a minute.
 */
object SpokenAlert {
    const val RU = "Внимание. Возможно, это мошенник. Положите трубку и сами позвоните в банк."
    const val KK = "Абайлаңыз. Бұл алаяқ болуы мүмкін. Тұтқаны қойып, банкке өзіңіз қоңырау шалыңыз."
    private const val MIN_GAP_MS = 60_000L

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending = false
    private var lastAt = 0L
    /** result of the sample played from the settings, shown to the user (null = no sample waiting) */
    private var onResult: ((String) -> Unit)? = null

    /**
     * [preview]: the sample played when the user turns the setting on; it does not use up the once-a-minute limit,
     * and [result] gets a short text for the user: speaking, or why the phone cannot speak.
     */
    @Synchronized fun say(ctx: Context, preview: Boolean = false, result: ((String) -> Unit)? = null) {
        if (!preview) {
            val now = SystemClock.elapsedRealtime()
            if (lastAt != 0L && now - lastAt < MIN_GAP_MS) return
            lastAt = now
        }
        if (result != null) onResult = result
        val t = tts
        if (t != null && ready) { speak(t); return }
        pending = true
        if (t == null) tts = try { TextToSpeech(ctx.applicationContext) { status -> onInit(status) } } catch (_: Exception) { null }
        if (tts == null) report(noEngine())
    }

    private fun noEngine() = tr("Голосовой движок не найден. Установите «Синтезатор речи Google» из Play Маркета и выберите его в настройках телефона: «Синтез речи».",
        "Дауыс қозғалтқышы табылмады. Play Маркеттен «Google синтезаторын» орнатып, телефон баптауларында «Сөйлеу синтезі» бөлімінен таңдаңыз.")

    private fun report(msg: String) {
        val cb = onResult ?: return
        onResult = null
        main.post { cb(msg) }
    }

    @Synchronized private fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            try { t.shutdown() } catch (_: Exception) {}
            tts = null; ready = false; pending = false
            report(noEngine()); return
        }
        ready = true
        try {
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {}
                @Deprecated("Deprecated in Java") override fun onError(id: String?) {
                    report(tr("Телефон не смог произнести фразу. Проверьте громкость мультимедиа и голосовой движок в настройках: «Синтез речи».",
                        "Телефон фразаны айта алмады. Мультимедиа дыбысын және баптаулардағы «Сөйлеу синтезі» бөлімін тексеріңіз."))
                }
            })
        } catch (_: Exception) {}
        if (pending) { pending = false; speak(t) }
    }

    private fun speak(t: TextToSpeech) {
        try {
            val kk = I18n.kk && t.setLanguage(Locale.forLanguageTag("kk-KZ")) >= TextToSpeech.LANG_AVAILABLE
            val ru = kk || t.setLanguage(Locale.forLanguageTag("ru-RU")) >= TextToSpeech.LANG_AVAILABLE
            if (!ru) {
                report(tr("В голосовом движке нет русского и казахского голоса. В настройках телефона «Синтез речи» скачайте русский язык.",
                    "Дауыс қозғалтқышында орыс және қазақ дауысы жоқ. Телефон баптауларындағы «Сөйлеу синтезі» бөлімінде орыс тілін жүктеңіз."))
                return
            }
            val r = t.speak(if (kk) KK else RU, TextToSpeech.QUEUE_FLUSH, null, "saqtan_warning")
            if (r == TextToSpeech.ERROR) report(noEngine())
            else report(if (kk) tr("Говорю по-казахски. Если не слышно, прибавьте громкость мультимедиа.", "Қазақша айтып жатырмын. Естілмесе, мультимедиа дыбысын көтеріңіз.")
                else if (I18n.kk) tr("Казахского голоса нет в телефоне, говорю по-русски. Если не слышно, прибавьте громкость мультимедиа.", "Телефонда қазақ дауысы жоқ, орысша айтамын. Естілмесе, мультимедиа дыбысын көтеріңіз.")
                else tr("Говорю. Если не слышно, прибавьте громкость мультимедиа.", "Айтып жатырмын. Естілмесе, мультимедиа дыбысын көтеріңіз."))
        } catch (_: Exception) { report(noEngine()) }
    }
}
