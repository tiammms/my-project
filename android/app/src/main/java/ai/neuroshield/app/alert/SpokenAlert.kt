package ai.neuroshield.app.alert

import ai.neuroshield.app.I18n
import ai.neuroshield.app.tr
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Says a high-risk warning aloud (opt-in in the settings), for people who do not look at the screen.
 * Runs from wherever the warning is raised (call receiver, SMS receiver, notification listener, speech service), with
 * the phone locked or not: it only needs the phone's speech engine. Nothing is sent anywhere.
 *
 * - the phrases contain no words the rules react to, so on speakerphone the app hearing its own voice does not raise
 *   the risk (CoreLogicTest.spokenWarningDoesNotRaiseRiskWhenHeardBack);
 * - Kazakh when the interface is Kazakh and the engine has a Kazakh voice, otherwise Russian;
 * - media volume; other sound is lowered (transient audio focus) while the phrase plays;
 * - at most once a minute, so the same danger is not repeated;
 * - the engine is released two minutes after the last phrase.
 * Log lines (tag SaqtanTTS) contain no numbers or message text.
 */
object SpokenAlert {
    enum class Kind { CALL, MESSAGE }

    val PHRASES = mapOf(
        (Kind.CALL to false) to "Внимание! Возможно, это мошенник. Никому не говорите коды и пароли, ничего не сообщайте о своей карте. Положите трубку и сами позвоните в банк.",
        (Kind.MESSAGE to false) to "Внимание! Возможно, это сообщение от мошенников. Не открывайте ссылки, никому не говорите коды и пароли.",
        (Kind.CALL to true) to "Назар аударыңыз! Бұл алаяқ болуы мүмкін. Кодты, құпия сөзді және картаңыз туралы ешнәрсе айтпаңыз. Тұтқаны қойып, банкке өзіңіз қоңырау шалыңыз.",
        (Kind.MESSAGE to true) to "Назар аударыңыз! Бұл хабарлама алаяқтардан болуы мүмкін. Сілтемені ашпаңыз, кодты және құпия сөзді ешкімге айтпаңыз.",
    )
    private const val TAG = "SaqtanTTS"
    private const val MIN_GAP_MS = 60_000L
    private const val RELEASE_AFTER_MS = 120_000L

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingKind: Kind? = null
    private var lastAt = 0L
    private var focus: AudioFocusRequest? = null
    /** result of the sample played from the settings, shown to the user (null = nobody waiting) */
    private var onResult: ((String) -> Unit)? = null
    private val release = Runnable { shutdown("idle") }

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    /**
     * [preview]: the sample played when the user turns the setting on; it does not use up the once-a-minute limit,
     * and [result] gets a short text for the user: speaking, or why the phone cannot speak.
     */
    @Synchronized fun say(ctx: Context, kind: Kind = Kind.CALL, preview: Boolean = false, result: ((String) -> Unit)? = null) {
        if (!preview) {
            val now = SystemClock.elapsedRealtime()
            if (lastAt != 0L && now - lastAt < MIN_GAP_MS) { Log.i(TAG, "skipped: spoken less than a minute ago"); return }
            lastAt = now
        }
        if (result != null) onResult = result
        app = ctx.applicationContext
        main.removeCallbacks(release)
        val t = tts
        if (t != null && ready) { speak(t, kind); return }
        pendingKind = kind
        if (t == null) {
            tts = try { TextToSpeech(ctx.applicationContext) { status -> main.post { onInit(status) } } } catch (e: Exception) { Log.w(TAG, "create failed: ${e.javaClass.simpleName}"); null }
            if (tts == null) report(noEngine())
        }
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
        Log.i(TAG, "init status=$status")
        if (status != TextToSpeech.SUCCESS) {
            try { t.shutdown() } catch (_: Exception) {}
            tts = null; ready = false; pendingKind = null
            report(noEngine()); return
        }
        ready = true
        try {
            t.setAudioAttributes(attrs)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { Log.i(TAG, "speaking") }
                override fun onDone(id: String?) { Log.i(TAG, "done"); finished() }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { onError(id, -1) }
                override fun onError(id: String?, errorCode: Int) {
                    Log.w(TAG, "engine error=$errorCode"); finished()
                    report(tr("Телефон не смог произнести фразу. Проверьте громкость мультимедиа и голосовой движок в настройках: «Синтез речи».",
                        "Телефон фразаны айта алмады. Мультимедиа дыбысын және баптаулардағы «Сөйлеу синтезі» бөлімін тексеріңіз."))
                }
            })
        } catch (_: Exception) {}
        pendingKind?.let { pendingKind = null; speak(t, it) }
    }

    private fun speak(t: TextToSpeech, kind: Kind) {
        try {
            val kk = I18n.kk && t.setLanguage(Locale.forLanguageTag("kk-KZ")) >= TextToSpeech.LANG_AVAILABLE
            val ru = kk || t.setLanguage(Locale.forLanguageTag("ru-RU")) >= TextToSpeech.LANG_AVAILABLE
            Log.i(TAG, "language kk=$kk ru=$ru")
            if (!ru) {
                report(tr("В голосовом движке нет русского и казахского голоса. В настройках телефона «Синтез речи» скачайте русский язык.",
                    "Дауыс қозғалтқышында орыс және қазақ дауысы жоқ. Телефон баптауларындағы «Сөйлеу синтезі» бөлімінде орыс тілін жүктеңіз."))
                finished(); return
            }
            requestFocus()
            val r = t.speak(PHRASES.getValue(kind to kk), TextToSpeech.QUEUE_FLUSH, null, "saqtan_warning")
            Log.i(TAG, "speak result=$r")
            if (r == TextToSpeech.ERROR) { finished(); report(noEngine()); return }
            report(when {
                kk -> tr("Говорю по-казахски. Если не слышно, прибавьте громкость мультимедиа.", "Қазақша айтып жатырмын. Естілмесе, мультимедиа дыбысын көтеріңіз.")
                I18n.kk -> tr("Казахского голоса нет в телефоне, говорю по-русски. Если не слышно, прибавьте громкость мультимедиа.", "Телефонда қазақ дауысы жоқ, орысша айтамын. Естілмесе, мультимедиа дыбысын көтеріңіз.")
                else -> tr("Говорю. Если не слышно, прибавьте громкость мультимедиа.", "Айтып жатырмын. Естілмесе, мультимедиа дыбысын көтеріңіз.")
            })
        } catch (e: Exception) { Log.w(TAG, "speak failed: ${e.javaClass.simpleName}"); finished(); report(noEngine()) }
    }

    private fun requestFocus() {
        try {
            val am = app?.getSystemService(AudioManager::class.java) ?: return
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
            focus = req
            am.requestAudioFocus(req)
        } catch (_: Exception) {}
    }

    /** phrase over (or failed): give the audio focus back, release the engine later if nothing else is said */
    private fun finished() {
        main.post {
            try { focus?.let { app?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(it) } } catch (_: Exception) {}
            focus = null
            main.removeCallbacks(release); main.postDelayed(release, RELEASE_AFTER_MS)
        }
    }

    @Synchronized private fun shutdown(why: String) {
        val t = tts ?: return
        if (pendingKind != null) return
        try { t.stop(); t.shutdown() } catch (_: Exception) {}
        tts = null; ready = false
        Log.i(TAG, "released ($why)")
    }
}
