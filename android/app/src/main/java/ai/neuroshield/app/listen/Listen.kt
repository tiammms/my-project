package ai.neuroshield.app.listen

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
data class SpeechModel(val lang: String, val title: String, val url: String, val sizeMb: Int)

object SpeechModels {
    val all = listOf(
        SpeechModel("ru", "Русский", "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip", 45),
        SpeechModel("kk", "Қазақша", "https://alphacephei.com/vosk/models/vosk-model-small-kz-0.15.zip", 42),
        SpeechModel("en", "English", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", 40),
    )

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

    /** Downloads and unpacks a model once (needs internet only for this step). */
    fun download(ctx: Context, scope: CoroutineScope, lang: String): Job = scope.launch(Dispatchers.IO) {
        val m = all.first { it.lang == lang }
        val dst = root(ctx, lang)
        val tmp = File(ctx.cacheDir, "vosk-$lang.zip")
        try {
            set(lang, Status.Downloading(0))
            val conn = (URL(m.url).openConnection() as HttpURLConnection).apply { connectTimeout = 20_000; readTimeout = 60_000 }
            conn.connect()
            if (conn.responseCode !in 200..299) throw IllegalStateException("сервер ответил ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { inp ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(1 shl 16); var done = 0L; var last = -1
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        val pct = if (total > 0) (100 * done / total).toInt() else 0
                        if (pct != last) { last = pct; set(lang, Status.Downloading(pct)) }
                        if (!isActive) throw InterruptedException()
                    }
                }
            }
            dst.deleteRecursively(); dst.mkdirs()
            val canonical = dst.canonicalPath + File.separator
            ZipInputStream(tmp.inputStream().buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val f = File(dst, e.name)
                    if (!f.canonicalPath.startsWith(canonical)) throw SecurityException("архив модели повреждён")   // zip-slip guard
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); FileOutputStream(f).use { z.copyTo(it) } }
                }
            }
            val dir = modelDir(ctx, lang) ?: throw IllegalStateException("в архиве нет модели")
            set(lang, Status.Ready(dir))
        } catch (e: Exception) {
            dst.deleteRecursively()
            set(lang, Status.Failed(e.message ?: e.javaClass.simpleName))
        } finally { tmp.delete() }
    }
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
        val notif = notification("Слушаю разговор: анализ речи на телефоне, без интернета")
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            ListenState.ui.value = ListenUi(message = "Android не разрешил запустить прослушивание: ${e.message}")
            stopSelf(); return START_NOT_STICKY
        }
        if (job?.isActive != true) job = scope.launch { run() }
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 11, Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP); putExtra(MainActivity.EXTRA_ROUTE, "protection")
        }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 12, Intent(this, ListenService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, NeuroShieldApp.CH_LISTEN)
            .setSmallIcon(R.drawable.ic_shield).setContentTitle("Saqtan слушает звонок").setContentText(text)
            .setOngoing(true).setContentIntent(open).addAction(0, "Остановить", stop).build()
    }

    @SuppressLint("MissingPermission")
    private suspend fun run() {
        val lang = app.prefs.speechLang
        val dir = SpeechModels.modelDir(this, lang)
        if (dir == null) { finish("Сначала скачайте модель распознавания речи (${lang}) на вкладке «Защита»"); return }
        if (!Perms.mic(this)) { finish("Нет разрешения на микрофон"); return }
        val am = getSystemService(AudioManager::class.java)
        val inCall = app.prefs.callState == "OFFHOOK"
        ListenState.ui.value = ListenUi(running = true, inCall = inCall, speakerOn = isSpeakerOn(am),
            message = "Загружаю модель распознавания…")
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val model = try { Model(dir.absolutePath) } catch (e: Exception) { finish("Модель не загрузилась: ${e.message}"); return }
        val rec = Recognizer(model, SAMPLE_RATE.toFloat())
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audio = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE))
        if (audio.state != AudioRecord.STATE_INITIALIZED) { rec.close(); model.close(); finish("Микрофон занят или недоступен"); return }
        val monitor = LiveCallMonitor()
        val silence = SilenceDetector(SAMPLE_RATE)
        val buf = ShortArray(SAMPLE_RATE / 5)
        val started = System.currentTimeMillis()
        audio.startRecording()
        ListenState.ui.value = ListenState.ui.value.copy(message = if (inCall) "Слушаю звонок" else "Проверка микрофона: скажите фразу")
        try {
            while (scope.isActive && job?.isActive == true) {
                val n = audio.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (silence.onBuffer(buf, n)) {
                    ListenState.ui.value = ListenState.ui.value.copy(silenceDetected = true,
                        message = "Телефон отдаёт приложению тишину: на этом устройстве Android не разрешает слушать звонок. " +
                            "Используйте проверку текста или SMS-защиту.")
                }
                val escalation = if (rec.acceptWaveForm(buf, n)) monitor.onFinal(JSONObject(rec.result).optString("text"))
                else monitor.onPartial(JSONObject(rec.partialResult).optString("partial"))
                ListenState.ui.value = ListenState.ui.value.copy(transcript = monitor.transcript.takeLast(1500), risk = monitor.lastResult,
                    micLevel = silence.level, speakerOn = isSpeakerOn(am), inCall = app.prefs.callState == "OFFHOOK")
                if (escalation != null) warn(escalation.risk, escalation.transcript)
                if (System.currentTimeMillis() - started > MAX_MS) break
            }
            monitor.onFinal(JSONObject(rec.finalResult).optString("text"))?.let { warn(it.risk, it.transcript) }
        } finally {
            try { audio.stop() } catch (_: Exception) {}
            audio.release(); rec.close(); model.close()
        }
        finish(null)
    }

    private fun isSpeakerOn(am: AudioManager): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT >= 31) am.communicationDevice?.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        else @Suppress("DEPRECATION") am.isSpeakerphoneOn
    } catch (_: Exception) { false }

    private fun warn(r: TextRisk, transcript: String) {
        if (r.level == RiskLevel.LOW) return
        Warnings.show(this, Warning(
            level = r.level, score = r.score, source = "live_speech", number = app.prefs.callNumber,
            title = "В разговоре звучат фразы мошенников",
            reasons = r.tactics.map { t -> t.title + (t.evidence.firstOrNull()?.let { " («${it.match}»)" } ?: "") },
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
