package ai.neuroshield.app.ui

import ai.neuroshield.app.tr
import ai.neuroshield.app.I18n

import ai.neuroshield.app.Perms
import ai.neuroshield.app.alert.Warning
import ai.neuroshield.app.alert.Warnings
import ai.neuroshield.app.app
import ai.neuroshield.app.core.CallRisk
import ai.neuroshield.app.core.DemoScenario
import ai.neuroshield.app.core.RiskLevel
import ai.neuroshield.app.core.ScamAnalyzer
import ai.neuroshield.app.core.SmsCode
import ai.neuroshield.app.core.TextRisk
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class StepResult(val level: RiskLevel, val score: Double, val headline: String, val details: List<String>)

/**
 * Presentation mode: a scripted scam call played step by step. Number, speech and messages are made up;
 * every verdict on screen and in the warning window is computed by the real analyzers.
 */
@Composable
fun DemoScreen(onBack: () -> Unit, openHistory: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.app
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var running by remember { mutableStateOf(false) }
    var runId by remember { mutableIntStateOf(0) }
    var active by remember { mutableIntStateOf(0) }            // 0 = not started, 1..5 = current step, 6 = finished
    var lines by remember { mutableIntStateOf(0) }
    var speechWarned by remember { mutableStateOf(false) }
    val results = remember { mutableStateOf(mapOf<Int, StepResult>()) }
    val version by app.alerts.version.collectAsState()
    val demoCount = remember(version) { app.alerts.countSource("demo") }
    val overlay = Perms.overlay(ctx)
    // entering the demo clears a warning left on screen from earlier, so it cannot cover the presentation
    DisposableEffect(Unit) { Warnings.hide(); onDispose { job?.cancel() } }

    fun put(step: Int, r: StepResult) { results.value = results.value + (step to r) }

    fun warn(level: RiskLevel, score: Double, title: String, reasons: List<String>, advice: List<String>, number: String?, excerpt: String?) =
        Warnings.show(ctx, Warning(level = level, title = title, reasons = reasons, advice = advice, source = "demo",
            number = number, score = score, excerpt = excerpt, demo = true))

    fun stepCall() {
        active = 1
        val r = CallRisk.assess(DemoScenario.callFacts(app.prefs.homeCallingCode))
        put(1, StepResult(r.level, r.score, "Входящий звонок: ${r.level.ru.lowercase()}", r.reasons.map { it.text }))
        warn(r.level, r.score, "Демо · Подозрительный входящий звонок", r.reasons.map { it.text }, r.advice, DemoScenario.NUMBER, null)
    }

    fun speechResult(n: Int): TextRisk = ScamAnalyzer.analyze(DemoScenario.callerText(n))

    suspend fun stepSpeech(pace: Long) {
        active = 2; lines = 0; speechWarned = false
        Warnings.hide()
        for (i in 1..DemoScenario.CALL.size) {
            delay(pace); lines = i
            val r = speechResult(i)
            put(2, StepResult(r.level, r.score, "Речь звонящего: ${r.level.ru.lowercase()}", r.tactics.map { it.title }))
            if (r.level == RiskLevel.HIGH && !speechWarned) {
                speechWarned = true
                warn(r.level, r.score, "Демо · " + (r.tactics.firstOrNull()?.title ?: "Признаки обмана в разговоре"),
                    r.tactics.map { it.title }, r.tactics.map { it.advice }, DemoScenario.NUMBER, DemoScenario.callerText(i))
            }
        }
    }

    fun stepSms() {
        active = 3
        val code = SmsCode.find(DemoScenario.SMS)
        if (code != null) {
            val reasons = listOf("Код ${code.masked} пришёл во время звонка с незнакомого номера", "Звонящий просил продиктовать код")
            put(3, StepResult(RiskLevel.HIGH, 95.0, "SMS с кодом во время звонка: высокий риск", reasons))
            warn(RiskLevel.HIGH, 95.0, "Демо · Код из SMS во время звонка с незнакомым номером", reasons,
                listOf("Никому не называйте этот код. Банк его никогда не спрашивает."), DemoScenario.NUMBER, null)
        }
    }

    fun stepPhishing() {
        active = 4
        val r = ScamAnalyzer.analyze(DemoScenario.PHISHING)
        put(4, StepResult(r.level, r.score, "Сообщение со ссылкой: ${r.level.ru.lowercase()}",
            r.tactics.map { it.title } + r.tactics.flatMap { t -> t.evidence.filter { t.id == "phishing_link" }.map { it.context } }))
        warn(r.level, r.score, "Демо · " + (r.tactics.firstOrNull { it.id == "phishing_link" }?.title ?: "Подозрительное сообщение"),
            r.tactics.map { it.title }, r.tactics.map { it.advice }, null, DemoScenario.PHISHING)
    }

    fun stepNormal() {
        active = 5
        Warnings.hide()
        val r = ScamAnalyzer.analyze(DemoScenario.NORMAL)
        put(5, StepResult(r.level, r.score, "Обычное сообщение: ${r.level.ru.lowercase()}",
            listOf(if (r.tactics.isEmpty()) "Тактик мошенников не найдено — предупреждения нет" else r.summary)))
    }

    fun launchRun(block: suspend () -> Unit) {
        job?.cancel()
        val token = ++runId
        running = true
        Warnings.hide()          // a warning left on screen from earlier must not cover the demo
        job = scope.launch { try { block() } finally { if (runId == token) running = false } }
    }

    fun runOne(step: Int) {
        launchRun {
            when (step) {
                1 -> stepCall(); 2 -> stepSpeech(1400); 3 -> stepSms(); 4 -> stepPhishing(); 5 -> stepNormal()
            }
        }
    }

    fun runAll() {
        results.value = emptyMap(); lines = 0
        launchRun {
            stepCall(); delay(5000)
            stepSpeech(2200); delay(4500)
            stepSms(); delay(5000)
            stepPhishing(); delay(5000)
            stepNormal(); delay(2500)
            active = 6
        }
    }

    Page(tr("Демо-показ", "Демо-көрсетілім"), tr("Сценарий звонка мошенника", "Алаяқ қоңырауының сценарийі"), onBack) {
        GlassCard(accent = NS.violet, padding = 16.dp) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Info, null, tint = NS.violet, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(tr("Номер, речь и сообщения в этом сценарии выдуманы. Оценки считаются по-настоящему — теми же правилами, ", "Бұл сценарийдегі нөмір, сөз және хабарламалар ойдан алынған. Бағалар шын есептеледі — нақты қоңыраулардағы ") +
                    tr("что при реальных звонках. Записи в истории помечаются «Демо».", "ережелермен. Тарихтағы жазбалар «Демо» деп белгіленеді."), style = MaterialTheme.typography.bodySmall, color = NS.text2)
            }
            if (!overlay) Text(tr("Окно поверх экрана не разрешено — предупреждения придут уведомлениями. Разрешение: «Защита» → «Окно поверх звонка».", "Экран үстіндегі терезеге рұқсат жоқ — ескертулер хабарландыру ретінде келеді. Рұқсат: «Қорғаныс» → «Қоңырау үстіндегі терезе»."),
                style = MaterialTheme.typography.bodySmall, color = NS.warn)
        }

        if (running) GhostButton(tr("■  Остановить", "■  Тоқтату"), Modifier.fillMaxWidth(), color = NS.threat) { job?.cancel(); Warnings.hide() }
        else PrimaryButton(if (active == 0) tr("▶  Запустить весь сценарий", "▶  Бүкіл сценарийді іске қосу") else tr("▶  Показать заново", "▶  Қайта көрсету"), Modifier.fillMaxWidth(), color = NS.violet) { runAll() }

        DemoStep(1, Icons.Filled.Call, tr("Звонок с незнакомого номера", "Бейтаныс нөмірден қоңырау"), tr("Оценка номера до ответа: ${DemoScenario.NUMBER}", "Жауап бергенге дейін нөмірді бағалау: ${DemoScenario.NUMBER}"),
            active, results.value[1], running) { runOne(1) }
        DemoStep(2, Icons.Filled.GraphicEq, tr("Звонящий говорит", "Қоңырау шалушы сөйлейді"), tr("Анализ речи по фразам: риск растёт с каждой репликой", "Сөйлеуді фразалар бойынша талдау: әр сөйлеммен қауіп өседі"),
            active, results.value[2], running, extra = { if (lines > 0) Transcript(lines, speechResult(lines)) }) { runOne(2) }
        DemoStep(3, Icons.Filled.Sms, tr("Приходит SMS с кодом", "Кодпен SMS келеді"), "«${DemoScenario.SMS}»", active, results.value[3], running) { runOne(3) }
        DemoStep(4, Icons.Filled.Forum, tr("Сообщение с поддельной ссылкой", "Жалған сілтемесі бар хабарлама"), "«${DemoScenario.PHISHING}»", active, results.value[4], running) { runOne(4) }
        DemoStep(5, Icons.Filled.VerifiedUser, tr("Для сравнения: обычное сообщение", "Салыстыру үшін: қарапайым хабарлама"), "«${DemoScenario.NORMAL}»", active, results.value[5], running) { runOne(5) }

        AnimatedVisibility(active == 6, enter = fadeIn() + expandVertically()) {
            GlassCard(accent = NS.safe) {
                Text(tr("Сценарий завершён", "Сценарий аяқталды"), style = MaterialTheme.typography.titleMedium, color = NS.text)
                Bullet(tr("Звонок: предупреждение ещё до ответа", "Қоңырау: жауап бергенге дейін ескерту"))
                Bullet(tr("Разговор: тревога, как только звонящий попросил код и перевод", "Әңгіме: қоңырау шалушы код пен аударым сұраған бойда дабыл"))
                Bullet(tr("SMS с кодом во время звонка: высокий риск", "Қоңырау кезінде кодты SMS: жоғары қауіп"))
                Bullet(tr("Поддельный сайт банка: высокий риск", "Банктің жалған сайты: жоғары қауіп"))
                Bullet(tr("Обычное сообщение: без тревоги — ложной паники нет", "Қарапайым хабарлама: дабылсыз — жалған үрей жоқ"))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(tr("Открыть историю", "Тарихты ашу"), Modifier.weight(1f)) { openHistory() }
            GhostButton(if (demoCount > 0) tr("Удалить демо ($demoCount)", "Демоны жою ($demoCount)") else tr("Демо-записей нет", "Демо жазбалар жоқ"), Modifier.weight(1f), color = NS.muted) {
                if (demoCount > 0) app.alerts.deleteSource("demo")
            }
        }
        VoiceExamples()
        Text(tr("Ограничение: в реальном звонке речь слышна только на громкой связи и не на всех телефонах; синтезированный голос проверяется экспериментальной моделью, которая может ошибаться.", "Шектеу: нақты қоңырауда сөз тек дауыс зорайтқышта және барлық телефонда емес естіледі; синтезделген дауысты тәжірибелік модель тексереді, ол қателесуі мүмкін."),
            style = MaterialTheme.typography.bodySmall, color = NS.muted)
    }
}

@Composable
private fun DemoStep(n: Int, icon: ImageVector, title: String, sub: String, active: Int, result: StepResult?, running: Boolean,
                     extra: @Composable () -> Unit = {}, onShow: () -> Unit) {
    val isActive = active == n
    val color = result?.let { levelColor(it.level) } ?: if (isActive) NS.violet else NS.muted
    GlassCard(accent = if (isActive || result != null) color else null, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).background(color.copy(alpha = 0.16f), CircleShape).border(1.dp, color.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center) {
                if (result != null && !isActive) Icon(Icons.Filled.CheckCircle, null, tint = color, modifier = Modifier.size(18.dp))
                else Text("$n", color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Spacer(Modifier.width(12.dp))
            Icon(icon, null, tint = NS.text2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, color = NS.text, modifier = Modifier.weight(1f))
            if (!running) Text(tr("Показать", "Көрсету"), color = NS.cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onShow).padding(horizontal = 8.dp, vertical = 6.dp))
        }
        Text(sub, style = MaterialTheme.typography.bodySmall, color = NS.text2, maxLines = 3)
        extra()
        if (result != null) {
            DangerMeter(result.level, result.score)
            Text(I18n.core(result.headline), style = MaterialTheme.typography.bodyMedium, color = levelColor(result.level), fontWeight = FontWeight.SemiBold)
            result.details.take(5).forEach { Bullet(I18n.core(it)) }
        }
    }
}

@Composable
private fun Transcript(shown: Int, r: TextRisk) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        DemoScenario.CALL.take(shown).forEach { l ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (l.caller) Arrangement.Start else Arrangement.End) {
                val hit = l.caller && r.tactics.any { t -> t.evidence.any { e -> ScamAnalyzer.normalize(l.text).contains(e.match) } }
                Text(l.text, fontSize = 13.sp, color = if (hit) NS.text else NS.text2,
                    modifier = Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (!l.caller) NS.surface3 else if (hit) NS.threat.copy(alpha = 0.16f) else NS.surface2)
                        .border(1.dp, if (hit) NS.threat.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp))
            }
        }
    }
}

