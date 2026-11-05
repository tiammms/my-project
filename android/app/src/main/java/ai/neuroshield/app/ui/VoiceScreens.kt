package ai.neuroshield.app.ui

import ai.neuroshield.app.I18n
import ai.neuroshield.app.Perms
import ai.neuroshield.app.app
import ai.neuroshield.app.core.RiskLevel
import ai.neuroshield.app.core.ScamAnalyzer
import ai.neuroshield.app.core.SyntheticVoiceMonitor
import ai.neuroshield.app.core.TextRisk
import ai.neuroshield.app.core.VoiceCheck
import ai.neuroshield.app.core.VoiceInput
import ai.neuroshield.app.listen.AudioFileDecoder
import ai.neuroshield.app.listen.ListenService
import ai.neuroshield.app.listen.SpeechModels
import ai.neuroshield.app.listen.Transcriber
import ai.neuroshield.app.listen.VoiceModel
import ai.neuroshield.app.tr
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** What a voice check produced: the recognised text, the phrase analysis and (if the model is in the build) the voice check. */
data class VoiceResult(val seconds: Int, val transcript: String, val risk: TextRisk, val voice: VoiceCheck?,
                       val level: RiskLevel, val score: Double)

private sealed interface Step {
    data object Idle : Step
    data class Working(val text: String) : Step
    data class Live(val text: String, val seconds: Int) : Step
    data class Done(val r: VoiceResult) : Step
    data class Failed(val text: String) : Step
}

private fun langTitle(lang: String) = SpeechModels.all.firstOrNull { it.lang == lang }?.title ?: lang

/** Saves a risky result to the history (no audio; the recognised text only if the user allowed excerpts). */
internal fun saveResult(ctx: Context, source: String, prefix: String, number: String?, r: VoiceResult) {
    if (r.level == RiskLevel.LOW) return
    val reasons = r.risk.tactics.filter { !it.weak }.map { t -> t.title + (t.evidence.firstOrNull()?.let { " («${it.match}»)" } ?: "") } +
        (r.voice?.takeIf { it.flagged }?.let { listOf("Голос в сообщении " + it.summaryRu + " (экспериментальная проверка, может ошибаться)") } ?: emptyList())
    ctx.app.alerts.add(source, number, r.level, r.score, "$prefix: ${r.risk.summary.lowercase()}", reasons,
        if (ctx.app.prefs.storeExcerpts) r.transcript.take(600) else null)
}

/** Recognises a voice message on the phone (no internet) and checks the words and, experimentally, the voice. */
private fun checkFile(ctx: Context, uri: Uri, lang: String, active: () -> Boolean, progress: (Int) -> Unit): Step {
    val dir = SpeechModels.modelDir(ctx, lang) ?: return Step.Failed(tr("Нет модели распознавания", "Тану моделі жоқ"))
    val vm = VoiceModel.get(ctx)
    val voice = vm?.let { SyntheticVoiceMonitor(it.threshold) }
    var scored = 0
    return try {
        Transcriber(dir).use { tx ->
            val secs = AudioFileDecoder.decode(ctx, uri, cancelled = { !active() }) { pcm, n ->
                tx.feed(pcm, n)
                if (voice != null && vm != null) for (w in voice.onAudio(pcm, n)) if (scored++ < 40) voice.onScore(vm.score(w))
                progress(n)
            }
            val text = tx.finish()
            val risk = ScamAnalyzer.analyze(text)
            val v = voice?.result
            val (level, score) = VoiceInput.verdict(risk, v)
            Step.Done(VoiceResult(secs, text, risk, v, level, score))
        }
    } catch (e: AudioFileDecoder.Rejected) {
        Step.Failed(when (e.reason) {
            "too_long" -> tr("Сообщение длиннее 10 минут — такие не проверяются", "Хабарлама 10 минуттан ұзақ — мұндайлар тексерілмейді")
            "size" -> tr("Файл слишком большой", "Файл тым үлкен")
            "no_audio" -> tr("В файле нет звука", "Файлда дыбыс жоқ")
            else -> tr("Этот файл открыть нельзя", "Бұл файлды ашуға болмайды")
        })
    } catch (e: InterruptedException) { Step.Idle
    } catch (e: Exception) {
        Step.Failed(tr("Не удалось прочитать звук этого файла", "Бұл файлдың дыбысын оқу мүмкін болмады"))
    }
}

@Composable
private fun LangChips(lang: String?, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val ready = remember { Transcriber.ready(ctx) }
    if (ready.size < 2) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Язык речи:", "Сөйлеу тілі:"), color = NS.muted, style = MaterialTheme.typography.bodySmall)
        ready.forEach { m ->
            val on = m.lang == lang
            Box(Modifier.clip(RoundedCornerShape(50)).background(if (on) NS.cyan.copy(alpha = 0.16f) else NS.surface2)
                .border(1.dp, if (on) NS.cyan.copy(alpha = 0.6f) else NS.border, RoundedCornerShape(50))
                .clickable { onPick(m.lang) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(m.title, color = if (on) NS.cyan else NS.text2, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun NoModel(onNeedModel: () -> Unit) {
    GlassCard(accent = NS.warn) {
        Text(tr("Сначала скачайте модель распознавания речи", "Алдымен сөйлеуді тану моделін жүктеңіз"), style = MaterialTheme.typography.titleMedium, color = NS.text)
        Text(tr("Один раз, около 45 МБ, во вкладке «Защита». Потом всё работает без интернета.",
            "Бір рет, шамамен 45 МБ, «Қорғаныс» бетінде. Кейін бәрі интернетсіз жұмыс істейді."), style = MaterialTheme.typography.bodySmall, color = NS.text2)
        PrimaryButton(tr("Открыть «Защиту»", "«Қорғанысты» ашу"), Modifier.fillMaxWidth(), color = NS.warn, onClick = onNeedModel)
    }
}

@Composable
internal fun ResultCard(r: VoiceResult, heard: String, source: String, prefix: String, number: String?) {
    val ctx = LocalContext.current
    GlassCard(accent = levelColor(r.level)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RingGauge((r.score / 100).toFloat(), levelColor(r.level), 76.dp, 8.dp) {
                Text("${r.score.toInt()}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = NS.text)
            }
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(levelShort(r.level).uppercase(), style = MaterialTheme.typography.labelSmall, color = levelColor(r.level))
                Text(I18n.core(r.risk.summary), style = MaterialTheme.typography.titleMedium, color = NS.text)
                if (r.risk.words < 6) Text(tr("Распознано мало слов — оценка неточная", "Сөз аз танылды — бағалау дәл емес"),
                    style = MaterialTheme.typography.bodySmall, color = NS.muted)
            }
        }
    }
    RiskDetails(r.risk)
    if (r.level != RiskLevel.LOW) GhostButton("✉  " + ai.neuroshield.app.alert.TellFamily.label(ctx), Modifier.fillMaxWidth()) {
        ai.neuroshield.app.alert.TellFamily.open(ctx, source, number, "$prefix: ${r.risk.summary.lowercase()}")
    }
    r.voice?.let { v ->
        ListRow(Icons.Filled.GraphicEq, tr("Голос: робот или человек", "Дауыс: робот па, адам ба"),
            voiceCheckText(v) + tr(" · экспериментально, может ошибаться", " · тәжірибелік, қателесуі мүмкін"),
            tint = if (v.flagged) NS.threat else NS.violet)
    }
    SectionTitle(heard)
    GlassCard(padding = 14.dp) {
        Text(if (r.transcript.isBlank()) tr("Речь не распознана", "Сөйлеу танылмады") else "«${r.transcript.take(2000)}»",
            color = NS.text2, style = MaterialTheme.typography.bodySmall)
        Text(tr("Распознавание на телефоне, без интернета. Отдельные слова могут быть распознаны неточно.",
            "Тану телефонда, интернетсіз. Кейбір сөздер дәл танылмауы мүмкін."), color = NS.muted, style = MaterialTheme.typography.labelSmall)
    }
}

// =========================================================================== VOICE MESSAGE
/**
 * "Проверка голосового сообщения": a voice note shared from WhatsApp/Telegram (Поделиться → Saqtan) or picked as a file.
 * Works on any phone, because the sound comes from the file, not from a call.
 */
@Composable
fun VoiceMessageScreen(initial: Uri?, onBack: () -> Unit, onNeedModel: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf(initial) }
    var lang by remember { mutableStateOf(Transcriber.pick(ctx)) }
    var step by remember { mutableStateOf<Step>(Step.Idle) }
    var job by remember { mutableStateOf<Job?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) uri = u }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    LaunchedEffect(uri, lang) {
        val u = uri ?: return@LaunchedEffect
        val l = lang ?: return@LaunchedEffect
        job?.cancel()
        step = Step.Working(tr("Распознаю речь…", "Сөйлеуді танып жатырмын…"))
        var done = 0L
        job = scope.launch {
            val res = withContext(Dispatchers.Default) {
                checkFile(ctx, u, l, active = { isActive }) { n ->
                    done += n
                    step = Step.Working(tr("Распознаю речь… ${done / ListenService.SAMPLE_RATE} с", "Сөйлеуді танып жатырмын… ${done / ListenService.SAMPLE_RATE} с"))
                }
            }
            step = res
            if (res is Step.Done) saveResult(ctx, "voice_message", "Голосовое сообщение", null, res.r)
        }
    }
    Page(tr("Голосовое сообщение", "Дауыстық хабарлама"), tr("Проверка без интернета, на телефоне", "Интернетсіз, телефонда тексеру"), onBack) {
        if (lang == null) { NoModel(onNeedModel); return@Page }
        GlassCard(accent = NS.cyan) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.AudioFile, NS.cyan, 44.dp)
                Spacer(Modifier.width(12.dp))
                Text(tr("В WhatsApp или Telegram удерживайте голосовое → «Поделиться» → Saqtan. Или выберите аудиофайл.",
                    "WhatsApp не Telegram-да дауыстық хабарламаны басып тұрыңыз → «Бөлісу» → Saqtan. Немесе аудиофайлды таңдаңыз."),
                    style = MaterialTheme.typography.bodyMedium, color = NS.text2)
            }
            PrimaryButton(tr("Выбрать аудиофайл", "Аудиофайлды таңдау"), Modifier.fillMaxWidth()) { pick.launch("audio/*") }
        }
        LangChips(lang) { lang = it }
        when (val s = step) {
            is Step.Working -> GlassCard { Text(s.text, color = NS.text, style = MaterialTheme.typography.bodyLarge) }
            is Step.Failed -> GlassCard(accent = NS.threat) { Text(s.text, color = NS.threat, style = MaterialTheme.typography.bodyLarge) }
            is Step.Done -> {
                Text(tr("Длительность: ${s.r.seconds} с · язык: ${langTitle(lang ?: "")}", "Ұзақтығы: ${s.r.seconds} с · тілі: ${langTitle(lang ?: "")}"),
                    color = NS.muted, style = MaterialTheme.typography.bodySmall)
                ResultCard(s.r, tr("Что сказано в сообщении", "Хабарламада не айтылды"), "voice_message", "Голосовое сообщение", null)
            }
            else -> {}
        }
    }
}

// =========================================================================== RETELL AFTER A CALL
/**
 * "Расскажите, что вам сказали": right after a call the user retells it in their own words; outside a call the
 * microphone works on every phone, so this works where Android gives apps silence during the call itself.
 */
@SuppressLint("MissingPermission")
@Composable
fun RetellScreen(number: String?, onBack: () -> Unit, onNeedModel: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lang by remember { mutableStateOf(Transcriber.pick(ctx)) }
    var step by remember { mutableStateOf<Step>(Step.Idle) }
    val stop = remember { AtomicBoolean(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { stop.set(true); job?.cancel() } }

    fun listen(l: String) {
        stop.set(false)
        step = Step.Live("", 0)
        job = scope.launch {
            val res = withContext(Dispatchers.Default) {
                val dir = SpeechModels.modelDir(ctx, l) ?: return@withContext Step.Failed(tr("Нет модели распознавания", "Тану моделі жоқ"))
                val rate = ListenService.SAMPLE_RATE
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val rec = try { AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate)) }
                    catch (_: Exception) { null }
                if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { rec?.release()
                    return@withContext Step.Failed(tr("Микрофон сейчас недоступен", "Микрофон қазір қолжетімсіз")) }
                try {
                    Transcriber(dir).use { tx ->
                        val buf = ShortArray(rate / 5)
                        var total = 0L
                        rec.startRecording()
                        while (isActive && !stop.get() && total < VoiceInput.RETELL_MAX_SECONDS.toLong() * rate) {
                            val n = rec.read(buf, 0, buf.size)
                            if (n <= 0) continue
                            tx.feed(buf, n); total += n
                            step = Step.Live(tx.live(), (total / rate).toInt())
                        }
                        val text = tx.finish()
                        val risk = ScamAnalyzer.analyze(text)
                        Step.Done(VoiceResult((total / rate).toInt(), text, risk, null, risk.level, risk.score))
                    }
                } catch (_: Exception) {
                    Step.Failed(tr("Не удалось распознать речь", "Сөйлеуді тану мүмкін болмады"))
                } finally { runCatching { rec.stop() }; rec.release() }
            }
            step = res
            if (res is Step.Done) saveResult(ctx, "retell", "Пересказ звонка", number, res.r)
        }
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) lang?.let { listen(it) } else step = Step.Failed(tr("Без доступа к микрофону пересказ не работает", "Микрофонға рұқсатсыз әңгімелеу жұмыс істемейді"))
    }

    Page(tr("Что вам сказали?", "Сізге не айтты?"), tr("Расскажите своими словами — Saqtan проверит", "Өз сөзіңізбен айтыңыз — Saqtan тексереді"), onBack) {
        if (lang == null) { NoModel(onNeedModel); return@Page }
        number?.let { ListRow(Icons.Filled.Info, it, tr("Звонок, о котором рассказываете", "Сіз айтып отырған қоңырау"), tint = NS.text2) }
        if (ctx.app.prefs.callState == "OFFHOOK") GlassCard(accent = NS.warn) {
            Text(tr("Сейчас идёт звонок: на многих телефонах микрофон во время разговора недоступен. Расскажите после звонка.",
                "Қазір қоңырау жүріп жатыр: көп телефонда сөйлесу кезінде микрофон қолжетімсіз. Қоңыраудан кейін айтыңыз."),
                color = NS.warn, style = MaterialTheme.typography.bodySmall)
        }
        LangChips(lang) { if (step !is Step.Live) lang = it }
        val s = step
        val live = s is Step.Live
        GlassCard(accent = if (live) NS.threat else NS.cyan) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clip(CircleShape).background((if (live) NS.threat else NS.cyan).copy(alpha = 0.16f))
                    .border(2.dp, if (live) NS.threat else NS.cyan, CircleShape)
                    .clickable {
                        if (live) stop.set(true)
                        else if (!Perms.mic(ctx)) mic.launch(Manifest.permission.RECORD_AUDIO)
                        else lang?.let { listen(it) }
                    }, contentAlignment = Alignment.Center) {
                    Icon(if (live) Icons.Filled.Stop else Icons.Filled.Mic, null, tint = if (live) NS.threat else NS.cyan, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (live) tr("Слушаю… нажмите, когда закончите", "Тыңдап тұрмын… аяқтағанда басыңыз")
                        else tr("Нажмите и расскажите, что от вас просили", "Басып, сізден не сұрағанын айтыңыз"),
                        color = NS.text, style = MaterialTheme.typography.titleMedium)
                    Text(if (live) "${(s as Step.Live).seconds} / ${VoiceInput.RETELL_MAX_SECONDS} с"
                        else tr("Например: «Звонили из банка, сказали, что карту взломали, просили продиктовать код из СМС»",
                            "Мысалы: «Банктен хабарласты, картаңыз бұзылды деді, SMS-тегі кодты айтуды сұрады»"),
                        color = NS.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (s is Step.Live && s.text.isNotBlank()) Text("«${s.text.takeLast(400)}»", color = NS.text2, style = MaterialTheme.typography.bodySmall)
        }
        when (s) {
            is Step.Failed -> GlassCard(accent = NS.threat) { Text(s.text, color = NS.threat, style = MaterialTheme.typography.bodyLarge) }
            is Step.Done -> ResultCard(s.r, tr("Что распознано", "Не танылды"), "retell", "Пересказ звонка", number)
            else -> {}
        }
        Text(tr("Запись не сохраняется: звук сразу превращается в текст на телефоне.",
            "Дыбыс сақталмайды: ол бірден телефонда мәтінге айналады."), color = NS.muted, style = MaterialTheme.typography.labelSmall)
    }
}
