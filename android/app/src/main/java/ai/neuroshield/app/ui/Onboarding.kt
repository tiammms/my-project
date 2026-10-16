package ai.neuroshield.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private data class Slide(val icon: ImageVector, val color: Color, val title: String, val text: String)

private val SLIDES = listOf(
    Slide(Icons.Filled.Shield, NS.cyan, "Защита от телефонных мошенников",
        "NeuroShield оценивает каждый звонок с незнакомого номера и предупреждает прямо поверх экрана звонка."),
    Slide(Icons.Filled.GraphicEq, NS.threat, "Код из SMS во время звонка — тревога",
        "Самый частый обман: «продиктуйте код». Если код пришёл, пока вы говорите с незнакомцем, вы сразу увидите предупреждение."),
    Slide(Icons.Filled.Forum, NS.violet, "Мессенджеры, ссылки и видео",
        "Звонки в WhatsApp и Telegram, фишинговые ссылки в сообщениях и проверка видеозвонка: живой ли человек в кадре."),
    Slide(Icons.Filled.Lock, NS.safe, "Всё на вашем телефоне",
        "Распознавание речи и анализ работают без интернета. Звук, SMS и кадры не сохраняются и никуда не отправляются."),
)

/** First launch: four short slides, then straight to enabling the protection modules. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pager = rememberPagerState { SLIDES.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == SLIDES.lastIndex
    Column(Modifier.fillMaxSize().background(NS.bg).padding(horizontal = 24.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("NeuroShield", style = MaterialTheme.typography.titleLarge, color = NS.text)
            Text(" AI", style = MaterialTheme.typography.titleLarge, color = NS.cyan)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDone) { Text("Пропустить", color = NS.muted) }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { i ->
            val s = SLIDES[i]
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                StatusOrb(s.color, 190.dp, s.icon)
                Spacer(Modifier.height(28.dp))
                Text(s.title, style = MaterialTheme.typography.headlineSmall, color = NS.text, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(s.text, style = MaterialTheme.typography.bodyLarge, color = NS.text2, textAlign = TextAlign.Center)
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.Center) {
            SLIDES.indices.forEach { i ->
                val on = i == pager.currentPage
                val w by animateDpAsState(if (on) 24.dp else 8.dp, label = "dot")
                val c by animateColorAsState(if (on) NS.cyan else NS.surface3, label = "dotc")
                Box(Modifier.padding(horizontal = 4.dp).width(w).height(8.dp).clip(RoundedCornerShape(4.dp)).background(c))
            }
        }
        PrimaryButton(if (last) "Включить защиту" else "Далее", Modifier.fillMaxWidth()) {
            if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
        }
    }
}
