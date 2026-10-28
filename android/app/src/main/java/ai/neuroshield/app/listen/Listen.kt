package ai.neuroshield.app.listen

import ai.neuroshield.app.tr
import ai.neuroshield.app.I18n

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import ai.neuroshield.app.MainActivity
import ai.neuroshield.app.NeuroShieldApp
import ai.neuroshield.app.Perms
import ai.neuroshield.app.R
import ai.neuroshield.app.alert.Warning
import ai.neuroshield.app.alert.Warnings
import ai.neuroshield.app.app
import ai.neuroshield.app.core.LiveCallMonitor
import ai.neuroshield.app.core.RiskLevel
import ai.neuroshield.app.data.CallRecordings
import ai.neuroshield.app.core.SilenceDetector
import ai.neuroshield.app.core.TextRisk
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

// ------------------------------------------------------------------ offline speech models (Vosk)
/**
 * [sha256] pins the exact archive published by the Vosk project: a changed or damaged file is rejected
 * before anything is unpacked or loaded into the native recogniser.
 */
data class SpeechModel(val lang: String, val title: String, val url: String, val sizeMb: Int, val sha256: String)

object SpeechModels {
    val all = listOf(
        SpeechModel("ru", "Русский", "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip", 45, SHA_RU),
        SpeechModel("kk", "Қазақша", "https://alphacephei.com/vosk/models/vosk-model-small-kz-0.15.zip", 42, SHA_KK),
        SpeechModel("en", "English", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", 40, SHA_EN),
    )
    private const val MAX_DOWNLOAD = 150L * 1024 * 1024      // bytes; the real archives are 40–50 MB
    private const val MAX_UNPACKED = 400L * 1024 * 1024      // protects against "zip bombs"
    private const val MAX_ENTRIES = 5_000

    sealed interface Status {
        data object Missing : Status
        data class Downloading(val percent: Int) : Status
        data class Ready(val dir: File) : Status
        data class Failed(val error: String) : Status
    }

    private val _status = MutableStateFlow<Map<String, Status>>(emptyMap())
    val status: StateFlow<Map<String, Status>> = _status

    private fun root(ctx: Context, lang: String) = File(ctx.filesDir, "vosk/$lang")

    /** The directory that contains the model files (the zip has one top-level folder). */
    fun modelDir(ctx: Context, lang: String): File? {
        val r = root(ctx, lang)
        if (!r.isDirectory) return null
        return r.walkTopDown().maxDepth(3).firstOrNull { File(it, "am").isDirectory && File(it, "conf").isDirectory }
    }

    fun refresh(ctx: Context) {
        _status.value = all.associate { m ->
            val cur = _status.value[m.lang]
            m.lang to (if (cur is Status.Downloading) cur else modelDir(ctx, m.lang)?.let { Status.Ready(it) } ?: (cur as? Status.Failed ?: Status.Missing))
        }
    }

    private fun set(lang: String, s: Status) { _status.value = _status.value + (lang to s) }

    fun delete(ctx: Context, lang: String) { root(ctx, lang).deleteRecursively(); refresh(ctx) }

    /** Downloads, verifies (SHA-256) and unpacks a model once. Internet is used only for this step, over HTTPS. */
    fun download(ctx: Context, scope: CoroutineScope, lang: String): Job = scope.launch(Dispatchers.IO) {
        val m = all.first { it.lang == lang }
        val dst = root(ctx, lang)
        val tmp = File(ctx.cacheDir, "vosk-$lang.zip")
        val staging = File(ctx.filesDir, "vosk/.staging-$lang")
        try {
            set(lang, Status.Downloading(0))
            require(m.url.startsWith("https://")) { "insecure url" }
            val conn = (URL(m.url).openConnection() as javax.net.ssl.HttpsURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = true
            }
            conn.connect()
            if (conn.responseCode !in 200..299) throw DownloadError(tr("сервер недоступен, попробуйте позже", "сервер қолжетімсіз, кейінірек көріңіз"))
            val total = conn.contentLengthLong
            if (total > MAX_DOWNLOAD) throw DownloadError(tr("файл модели неожиданно большой", "модель файлы күтпегендей үлкен"))
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { inp ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(1 shl 16); var done = 0L; var last = -1
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        done += n
                        if (done > MAX_DOWNLOAD) throw DownloadError(tr("файл модели неожиданно большой", "модель файлы күтпегендей үлкен"))
                        out.write(buf, 0, n); digest.update(buf, 0, n)
                        val pct = if (total > 0) (100 * done / total).toInt() else 0
                        if (pct != last) { last = pct; set(lang, Status.Downloading(pct)) }
                        if (!isActive) throw InterruptedException()
                    }
                }
            }
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (!got.equals(m.sha256, ignoreCase = true))
                throw DownloadError(tr("файл не прошёл проверку подлинности и удалён", "файл түпнұсқалық тексеруден өтпеді және жойылды"))
            staging.deleteRecursively(); staging.mkdirs()
            val canonical = staging.canonicalPath + File.separator
            var unpacked = 0L; var entries = 0
            ZipInputStream(tmp.inputStream().buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (++entries > MAX_ENTRIES) throw DownloadError(tr("архив модели повреждён", "модель архиві бүлінген"))
                    val f = File(staging, e.name)
                    if (!f.canonicalPath.startsWith(canonical)) throw DownloadError(tr("архив модели повреждён", "модель архиві бүлінген"))   // zip-slip guard
                    if (e.isDirectory) { f.mkdirs(); continue }
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = z.read(buf); if (n < 0) break
                            unpacked += n
                            if (unpacked > MAX_UNPACKED) throw DownloadError(tr("архив модели повреждён", "модель архиві бүлінген"))
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
            dst.deleteRecursively()
            if (!staging.renameTo(dst)) throw DownloadError(tr("не хватает места на телефоне", "телефонда орын жетпейді"))
            val dir = modelDir(ctx, lang) ?: throw DownloadError(tr("в архиве нет модели", "архивте модель жоқ"))
            set(lang, Status.Ready(dir))
        } catch (e: DownloadError) {
            dst.deleteRecursively(); set(lang, Status.Failed(e.message ?: ""))
        } catch (e: InterruptedException) {
            dst.deleteRecursively(); set(lang, Status.Missing)
        } catch (e: Exception) {
            // technical details (exception text, URLs) are not shown to the user
            dst.deleteRecursively(); set(lang, Status.Failed(tr("нет связи с сервером, проверьте интернет", "сервермен байланыс жоқ, интернетті тексеріңіз")))
        } finally { tmp.delete(); staging.deleteRecursively() }
    }

    private class DownloadError(msg: String) : Exception(msg)
}

// ------------------------------------------------------------------ live state for the UI
data class ListenUi(
    val running: Boolean = false,
    val transcript: String = "",
    val risk: TextRisk? = null,
    val micLevel: Double = 0.0,
    val silenceDetected: Boolean = false,
    val speakerOn: Boolean = false,
    val inCall: Boolean = false,
    val message: String? = null,
    /** Experimental synthetic-voice check: null = the model is not in this build or could not be loaded. */
    val voice: ai.neuroshield.app.core.VoiceCheck? = null,
)

object ListenState {
    val ui = MutableStateFlow(ListenUi())
}

/**
 * Foreground service (type "microphone"): listens through the microphone, recognises speech OFFLINE with Vosk and
 * analyses the text for scam tactics. During a cellular call this hears the other person only on speakerphone,
 * and some phones give apps silence during calls: this is detected and shown to the user (never hidden).
 */
class ListenService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelfNow(intent.getStringExtra("reason")); return START_NOT_STICKY }
        val notif = notification(tr("Слушаю разговор: анализ речи на телефоне, без интернета", "Әңгімені тыңдап жатырмын: сөйлеу телефонда, интернетсіз талданады"))
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            ListenState.ui.value = ListenUi(message = tr("Android не разрешил запустить прослушивание: ${e.message}", "Android тыңдауды іске қосуға рұқсат бермеді: ${e.message}"))
            stopSelf(); return START_NOT_STICKY
        }
        if (job?.isActive != true) job = scope.launch { run() }
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 11, MainActivity.internalIntent(this).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP); putExtra(MainActivity.EXTRA_ROUTE, "protection")
        }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 12, Intent(this, ListenService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, NeuroShieldApp.CH_LISTEN)
            .setSmallIcon(R.drawable.ic_shield).setContentTitle(tr("Saqtan слушает звонок", "Saqtan қоңырауды тыңдап жатыр")).setContentText(text)
            .setOngoing(true).setContentIntent(open).addAction(0, tr("Остановить", "Тоқтату"), stop).build()
    }

    @SuppressLint("MissingPermission")
    private suspend fun run() {
        val lang = app.prefs.speechLang
        val dir = SpeechModels.modelDir(this, lang)
        val record = app.prefs.recordCalls && app.prefs.callState == "OFFHOOK" && Perms.mic(this)
        if (dir == null && !record) { finish(tr("Сначала скачайте модель распознавания речи (${lang}) на вкладке «Защита»", "Алдымен «Қорғаныс» бетінде сөйлеуді тану моделін (${lang}) жүктеңіз")); return }
        if (!Perms.mic(this)) { finish(tr("Нет разрешения на микрофон", "Микрофонға рұқсат жоқ")); return }
        val am = getSystemService(AudioManager::class.java)
        val inCall = app.prefs.callState == "OFFHOOK"
        ListenState.ui.value = ListenUi(running = true, inCall = inCall, speakerOn = isSpeakerOn(am),
            message = tr("Загружаю модель распознавания…", "Тану моделі жүктелуде…"))
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val model = if (dir == null) null else try { Model(dir.absolutePath) } catch (e: Exception) {
            if (!record) { finish(tr("Модель не загрузилась", "Модель жүктелмеді")); return } else null
        }
        val rec = model?.let { Recognizer(it, SAMPLE_RATE.toFloat()) }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audio = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE))
        if (audio.state != AudioRecord.STATE_INITIALIZED) { rec?.close(); model?.close(); finish(tr("Микрофон занят или недоступен", "Микрофон бос емес немесе қолжетімсіз")); return }
        val monitor = LiveCallMonitor()
        // experimental synthetic-voice check (4-second pieces scored on a separate thread; pieces are dropped while busy)
        val voiceModel = VoiceModel.get(this)
        val voice = voiceModel?.let { ai.neuroshield.app.core.SyntheticVoiceMonitor(it.threshold) }
        val voiceExec = java.util.concurrent.Executors.newSingleThreadExecutor()
        val voiceBusy = java.util.concurrent.atomic.AtomicBoolean(false)
        val voiceWarned = java.util.concurrent.atomic.AtomicBoolean(false)
        if (voice != null) {
            ListenState.ui.value = ListenState.ui.value.copy(voice = voice.result)
            // the report must say the check ran even if no piece with speech came (e.g. the phone gave silence)
            ai.neuroshield.app.call.CallSessions.on(this, ai.neuroshield.app.core.CallEvent.Voice(System.currentTimeMillis(), voice.result))
        }
        val silence = SilenceDetector(SAMPLE_RATE)
        val buf = ShortArray(SAMPLE_RATE / 5)
        val started = System.currentTimeMillis()
        // call recording (only if the user turned it on): encrypted, stored on the phone only
        val recording = if (record) CallRecordings.start(this) else null
        var attached = false
        if (recording != null) {
            if (ai.neuroshield.app.call.CallSessions.active(this)) { ai.neuroshield.app.call.CallSessions.attachRecording(this, recording.id); attached = true }
            getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, notification(
                tr("Идёт запись разговора. Она зашифрована и хранится только на телефоне", "Әңгіме жазылып жатыр. Жазба шифрланған және тек телефонда сақталады")))
        }
        audio.startRecording()
        ai.neuroshield.app.call.CallSessions.on(this, ai.neuroshield.app.core.CallEvent.SpeechState(System.currentTimeMillis(), ai.neuroshield.app.core.SpeechStatus.STARTED))
        var silenceRecorded = false
        ListenState.ui.value = ListenState.ui.value.copy(message = if (inCall) tr("Слушаю звонок", "Қоңырауды тыңдап жатырмын") else tr("Проверка микрофона: скажите фразу", "Микрофонды тексеру: бір сөйлем айтыңыз"))
        try {
            while (scope.isActive && job?.isActive == true) {
                val n = audio.read(buf, 0, buf.size)
                if (n <= 0) continue
                recording?.write(buf, n)
                if (voice != null && voiceModel != null) for (w in voice.onAudio(buf, n)) {
                    if (!voiceBusy.compareAndSet(false, true)) continue
                    voiceExec.execute {
                        try {
                            voice.onScore(voiceModel.score(w))
                            onVoice(voice.result, monitor.lastResult, voiceWarned)
                        } catch (_: Exception) {
                        } finally { voiceBusy.set(false) }
                    }
                }
                if (silence.onBuffer(buf, n)) {
                    if (!silenceRecorded) {
                        silenceRecorded = true
                        ai.neuroshield.app.call.CallSessions.on(this, ai.neuroshield.app.core.CallEvent.SpeechState(System.currentTimeMillis(), ai.neuroshield.app.core.SpeechStatus.SILENCE))
                    }
                    ListenState.ui.value = ListenState.ui.value.copy(silenceDetected = true,
                        message = tr("Телефон отдаёт приложению тишину: на этом устройстве Android не разрешает слушать звонок. ", "Телефон қосымшаға тек тыныштық береді: бұл құрылғыда Android қоңырауды тыңдауға рұқсат бермейді. ") +
                            tr("Используйте проверку текста или SMS-защиту.", "Мәтінді тексеруді немесе SMS-қорғанысты пайдаланыңыз."))
                }
                val escalation = if (rec == null) null
                    else if (rec.acceptWaveForm(buf, n)) monitor.onFinal(JSONObject(rec.result).optString("text"))
                    else monitor.onPartial(JSONObject(rec.partialResult).optString("partial"))
                ListenState.ui.value = ListenState.ui.value.copy(transcript = monitor.transcript.takeLast(1500), risk = monitor.lastResult,
                    micLevel = silence.level, speakerOn = isSpeakerOn(am), inCall = app.prefs.callState == "OFFHOOK")
                if (escalation != null) warn(escalation.risk, escalation.transcript)
                if (System.currentTimeMillis() - started > MAX_MS) break
            }
            rec?.let { r -> monitor.onFinal(JSONObject(r.finalResult).optString("text"))?.let { warn(it.risk, it.transcript) } }
        } finally {
            voiceExec.shutdown()
            runCatching { voiceExec.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS) }
            try { audio.stop() } catch (_: Exception) {}
            audio.release(); rec?.close(); model?.close()
            if (recording != null) {
                runCatching { recording.close() }
                // a recording made outside a call report (e.g. a call with a saved contact) gets its own history record
                if (!attached) app.alerts.add("recording", app.prefs.callNumber, RiskLevel.LOW, 0.0, "Запись разговора",
                    listOf("Длительность: ${recording.samples / SAMPLE_RATE} с"), null, recording.id)
            }
        }
        finish(null)
    }

    private fun isSpeakerOn(am: AudioManager): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT >= 31) am.communicationDevice?.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        else @Suppress("DEPRECATION") am.isSpeakerphoneOn
    } catch (_: Exception) { false }

    /** A new 4-second piece was scored: update the screen, the call report and (once) warn. */
    private fun onVoice(v: ai.neuroshield.app.core.VoiceCheck, speech: TextRisk, warned: java.util.concurrent.atomic.AtomicBoolean) {
        ListenState.ui.value = ListenState.ui.value.copy(voice = v)
        ai.neuroshield.app.call.CallSessions.on(this, ai.neuroshield.app.core.CallEvent.Voice(System.currentTimeMillis(), v))
        if (!v.flagged || !warned.compareAndSet(false, true)) return
        val withScam = speech.level != RiskLevel.LOW
        Warnings.show(this, Warning(
            level = if (withScam) RiskLevel.HIGH else RiskLevel.MEDIUM, score = if (withScam) maxOf(75.0, speech.score) else 40.0,
            source = "synthetic_voice", number = app.prefs.callNumber,
            title = "Голос похож на синтезированный (робот или ИИ)",
            reasons = listOf("Голос в разговоре " + v.summaryRu + " (экспериментальная проверка, может ошибаться)") +
                speech.tactics.filter { !it.weak }.map { t -> t.title + (t.evidence.firstOrNull()?.let { " («${it.match}»)" } ?: "") },
            advice = listOf("Если звонил «близкий человек» или «банк» — положите трубку и перезвоните сами по номеру, который знаете",
                "Не называйте коды и не переводите деньги, пока не убедитесь, кто звонит"),
        ))
    }

    private fun warn(r: TextRisk, transcript: String) {
        if (r.level == RiskLevel.LOW) return
        ai.neuroshield.app.call.CallSessions.on(this, ai.neuroshield.app.core.CallEvent.Speech(System.currentTimeMillis(), r))
        // scam phrases together with a voice that looks synthesized: HIGH
        val vf = ListenState.ui.value.voice?.takeIf { it.flagged }
        Warnings.show(this, Warning(
            level = if (vf != null) RiskLevel.HIGH else r.level, score = if (vf != null) maxOf(75.0, r.score) else r.score,
            source = "live_speech", number = app.prefs.callNumber,
            title = "В разговоре звучат фразы мошенников",
            reasons = r.tactics.map { t -> t.title + (t.evidence.firstOrNull()?.let { " («${it.match}»)" } ?: "") } +
                (vf?.let { listOf("Голос в разговоре " + it.summaryRu + " (экспериментальная проверка, может ошибаться)") } ?: emptyList<String>()),
            advice = r.tactics.sortedByDescending { it.weight }.take(2).map { it.advice },
            excerpt = transcript.takeLast(600),
        ))
    }

    private fun finish(message: String?) {
        ListenState.ui.value = ListenState.ui.value.copy(running = false, message = message ?: ListenState.ui.value.message)
        stopSelfNow(null)
    }

    private fun stopSelfNow(reason: String?) {
        job?.cancel()
        if (reason != null) ListenState.ui.value = ListenState.ui.value.copy(running = false, message = reason)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { scope.cancel(); ListenState.ui.value = ListenState.ui.value.copy(running = false); super.onDestroy() }

    companion object {
        const val SAMPLE_RATE = 16000
        const val NOTIF_ID = 2001
        const val ACTION_STOP = "ai.neuroshield.STOP_LISTEN"
        const val MAX_MS = 30 * 60_000L

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ListenService::class.java))
        }

        fun stop(ctx: Context, reason: String) {
            if (!ListenState.ui.value.running) return
            try { ctx.startService(Intent(ctx, ListenService::class.java).setAction(ACTION_STOP).putExtra("reason", reason)) } catch (_: Exception) {}
        }
    }
}
