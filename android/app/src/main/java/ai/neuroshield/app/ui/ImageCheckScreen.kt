package ai.neuroshield.app.ui

import ai.neuroshield.app.core.ScamAnalyzer
import ai.neuroshield.app.ocr.ImageText
import ai.neuroshield.app.ocr.OcrData
import ai.neuroshield.app.tr
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface ImgStep {
    data object Idle : ImgStep
    data object Working : ImgStep
    data class Done(val r: VoiceResult) : ImgStep
    data class Failed(val text: String) : ImgStep
}

/**
 * "Скриншот или фото": a picture shared from any app (Поделиться → Saqtan) or picked from the gallery — a «letter from the
 * police», a chat screenshot, a «bank notice». The text is read on the phone (no internet after the one-time download)
 * and checked with the same rules as SMS, including look-alike bank links.
 */
@Composable
fun ImageCheckScreen(initial: Uri?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var uri by remember { mutableStateOf(initial) }
    var step by remember { mutableStateOf<ImgStep>(ImgStep.Idle) }
    val ocr by OcrData.status.collectAsState()
    LaunchedEffect(Unit) { OcrData.refresh(ctx) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) uri = u }
    LaunchedEffect(uri, ocr) {
        val u = uri ?: return@LaunchedEffect
        if (ocr !is OcrData.Status.Ready || step is ImgStep.Done) return@LaunchedEffect
        step = ImgStep.Working
        step = withContext(Dispatchers.Default) {
            try {
                val text = ImageText.recognize(ctx, u).replace(Regex("\\s+"), " ").trim()
                val risk = ScamAnalyzer.analyze(text)
                ImgStep.Done(VoiceResult(0, text, risk, null, risk.level, risk.score)).also {
                    saveResult(ctx, "image_check", "Скриншот", null, it.r)
                }
            } catch (e: ImageText.Rejected) {
                ImgStep.Failed(if (e.reason == "size") tr("Файл слишком большой", "Файл тым үлкен") else tr("Эту картинку открыть нельзя", "Бұл суретті ашуға болмайды"))
            } catch (e: Exception) {
                ImgStep.Failed(tr("Не удалось прочитать текст на картинке", "Суреттегі мәтінді оқу мүмкін болмады"))
            }
        }
    }
    Page(tr("Скриншот или фото", "Скриншот немесе фото"), tr("Текст на картинке проверяется на телефоне", "Суреттегі мәтін телефонда тексеріледі"), onBack) {
        GlassCard(accent = NS.cyan) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.Image, NS.cyan, 44.dp)
                Spacer(Modifier.width(12.dp))
                Text(tr("Скриншот переписки, «письмо из полиции», «уведомление банка»: «Поделиться» → Saqtan, или выберите картинку.",
                    "Хат алмасу скриншоты, «полициядан хат», «банк хабарламасы»: «Бөлісу» → Saqtan, немесе суретті таңдаңыз."),
                    style = MaterialTheme.typography.bodyMedium, color = NS.text2)
            }
            when (val o = ocr) {
                is OcrData.Status.Ready -> PrimaryButton(tr("Выбрать картинку", "Суретті таңдау"), Modifier.fillMaxWidth()) {
                    step = ImgStep.Idle; pick.launch("image/*")
                }
                is OcrData.Status.Downloading -> {
                    Text(tr("Скачиваю распознавание текста… ${o.percent}%", "Мәтінді тану жүктелуде… ${o.percent}%"), color = NS.text, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { o.percent / 100f }, modifier = Modifier.fillMaxWidth(), color = NS.cyan, trackColor = NS.surface3)
                }
                else -> {
                    Text(tr("Один раз нужно скачать распознавание текста: русский, казахский, английский, около ${OcrData.SIZE_MB} МБ. Потом — без интернета.",
                        "Мәтінді тануды бір рет жүктеу керек: орыс, қазақ, ағылшын, шамамен ${OcrData.SIZE_MB} МБ. Кейін — интернетсіз."),
                        color = NS.muted, style = MaterialTheme.typography.bodySmall)
                    if (o is OcrData.Status.Failed) Text(o.error, color = NS.threat, style = MaterialTheme.typography.bodySmall)
                    PrimaryButton(tr("Скачать (${OcrData.SIZE_MB} МБ)", "Жүктеу (${OcrData.SIZE_MB} МБ)"), Modifier.fillMaxWidth()) { OcrData.download(ctx.applicationContext) }
                }
            }
        }
        when (val s = step) {
            ImgStep.Working -> GlassCard { Text(tr("Читаю текст на картинке…", "Суреттегі мәтінді оқып жатырмын…"), color = NS.text, style = MaterialTheme.typography.bodyLarge) }
            is ImgStep.Failed -> GlassCard(accent = NS.threat) { Text(s.text, color = NS.threat, style = MaterialTheme.typography.bodyLarge) }
            is ImgStep.Done -> ResultCard(s.r, tr("Текст на картинке", "Суреттегі мәтін"), "image_check", "Скриншот", null)
            else -> {}
        }
        Text(tr("Картинка не сохраняется и никуда не отправляется.", "Сурет сақталмайды және ешқайда жіберілмейді."), color = NS.muted, style = MaterialTheme.typography.labelSmall)
    }
}
