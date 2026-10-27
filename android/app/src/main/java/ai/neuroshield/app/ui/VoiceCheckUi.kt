package ai.neuroshield.app.ui

import ai.neuroshield.app.core.VoiceCheck
import ai.neuroshield.app.data.CallRecordings
import ai.neuroshield.app.listen.VoiceModel
import ai.neuroshield.app.tr
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun pct(p: Double) = "${(p * 100).toInt()}%"

/** One line about a checked recording, in the interface language. */
fun voiceCheckText(v: VoiceCheck): String = when {
    v.windows == 0 -> tr("Речи для проверки мало", "Тексеру үшін сөйлеу аз")
    v.flagged -> tr("Голос похож на синтезированный (робот или ИИ): ${v.synthetic} из ${v.windows} фрагментов по 4 с",
        "Дауыс синтезделгенге ұқсайды (робот немесе ЖИ): 4 секундтық ${v.windows} фрагменттің ${v.synthetic}-і")
    else -> tr("Признаков синтезированного голоса не найдено (${v.windows} фрагм. по 4 с)",
        "Синтезделген дауыс белгілері табылмады (4 секундтық ${v.windows} фрагмент)")
}

/** Report screen: check a saved call recording for a synthesized voice (experimental). */
@Composable
fun RecordingVoiceCheck(id: String) {
    val ctx = LocalContext.current
    if (!VoiceModel.bundled(ctx)) return
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    ListRow(Icons.Filled.GraphicEq,
        if (busy) tr("Проверяю голос…", "Дауысты тексеріп жатырмын…") else tr("Проверить, не синтезирован ли голос", "Дауыс синтезделмегенін тексеру"),
        result ?: tr("Экспериментальная проверка на телефоне, может ошибаться", "Телефондағы тәжірибелік тексеру, қателесуі мүмкін"),
        tint = NS.violet, onClick = {
            if (busy) return@ListRow
            busy = true
            scope.launch {
                result = withContext(Dispatchers.Default) {
                    val model = VoiceModel.get(ctx)
                    val wav = CallRecordings.toWav(ctx, id)
                    try {
                        if (model == null || wav == null) tr("Проверку не удалось выполнить", "Тексеру орындалмады")
                        else voiceCheckText(model.checkWav(wav)) + tr(" · экспериментально", " · тәжірибелік")
                    } catch (_: Exception) { tr("Проверку не удалось выполнить", "Тексеру орындалмады") } finally { wav?.delete() }
                }
                busy = false
            }
        })
}

/** Demo screen: the model on two short example clips (one person, one speech synthesizer), as heard through a phone. */
@Composable
fun VoiceExamples() {
    val ctx = LocalContext.current
    if (!VoiceModel.bundled(ctx)) return
    val scope = rememberCoroutineScope()
    val player = remember { arrayOfNulls<android.media.MediaPlayer>(1) }
    var playing by remember { mutableStateOf<String?>(null) }
    val results = remember { mutableStateOf(mapOf<String, String>()) }
    // stopping/releasing a MediaPlayer can wait for the audio output: never do it on the main thread
    fun stop() {
        val p = player[0]; player[0] = null; playing = null
        if (p != null) Thread { runCatching { p.stop() }; runCatching { p.release() } }.start()
    }
    DisposableEffect(Unit) { onDispose { stop() } }
    fun file(name: String): File = File(ctx.cacheDir, "example_$name.wav").also { f ->
        if (!f.exists()) ctx.assets.open("voice/example_$name.wav").use { i -> f.outputStream().use { i.copyTo(it) } }
    }
    GlassCard(accent = NS.violet, padding = 12.dp) {
        Text(tr("Проверка голоса: робот или человек", "Дауысты тексеру: робот па, адам ба"), style = MaterialTheme.typography.titleMedium, color = NS.text)
        Text(tr("Два коротких примера, как они звучат через телефон. Нажмите ▶, чтобы прослушать, и «Проверить», чтобы модель оценила звук. ",
            "Телефон арқылы естілетін екі қысқа мысал. Тыңдау үшін ▶, модель бағалауы үшін «Тексеру» түймесін басыңыз. ") +
            tr("Модель экспериментальная и может ошибаться.", "Модель тәжірибелік, қателесуі мүмкін."),
            style = MaterialTheme.typography.bodySmall, color = NS.text2)
        for ((name, title) in listOf("real" to tr("Пример 1: голос человека", "1-мысал: адам дауысы"),
                "synthetic" to tr("Пример 2: синтезатор речи (eSpeak)", "2-мысал: сөйлеу синтезаторы (eSpeak)"))) {
            ListRow(if (playing == name) Icons.Filled.Stop else Icons.Filled.PlayArrow, title,
                results.value[name] ?: tr("Проверить", "Тексеру"), tint = NS.violet,
                onClick = {
                    if (playing == name) { stop(); return@ListRow }
                    stop()
                    runCatching {
                        val mp = android.media.MediaPlayer()
                        player[0] = mp
                        mp.setDataSource(file(name).absolutePath)
                        mp.setOnPreparedListener { it.start() }
                        mp.setOnCompletionListener { if (player[0] === it) stop() }
                        mp.prepareAsync()
                        playing = name
                    }
                    scope.launch {
                        val text = withContext(Dispatchers.Default) {
                            val m = VoiceModel.get(ctx) ?: return@withContext tr("Модель не загрузилась", "Модель жүктелмеді")
                            val p = m.scoreWav(file(name))
                            (if (p >= m.threshold) tr("Модель: похож на синтезированный", "Модель: синтезделгенге ұқсайды")
                             else tr("Модель: не похож на синтезированный", "Модель: синтезделгенге ұқсамайды")) +
                                tr(" (оценка ${pct(p)}, порог ${pct(m.threshold)})", " (баға ${pct(p)}, шек ${pct(m.threshold)})")
                        }
                        results.value = results.value + (name to text)
                    }
                })
        }
    }
}
