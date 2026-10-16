package ai.neuroshield.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.neuroshield.app.core.RiskLevel

object NS {
    val bg = Color(0xFF0B1016)
    val surface = Color(0xFF121821)
    val surface2 = Color(0xFF18202B)
    val border = Color(0xFF253141)
    val text = Color(0xFFE6EDF5)
    val text2 = Color(0xFFB3C0D0)
    val muted = Color(0xFF7D8DA1)
    val teal = Color(0xFF2CC5B0)
    val blue = Color(0xFF4C8DFF)
    val low = Color(0xFF45C845)
    val medium = Color(0xFFFAB219)
    val high = Color(0xFFFF6B6B)
}

fun levelColor(l: RiskLevel) = when (l) { RiskLevel.HIGH -> NS.high; RiskLevel.MEDIUM -> NS.medium; RiskLevel.LOW -> NS.low }

@Composable
fun NeuroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NS.teal, onPrimary = Color(0xFF00201C), secondary = NS.blue, background = NS.bg, onBackground = NS.text,
            surface = NS.surface, onSurface = NS.text, surfaceVariant = NS.surface2, onSurfaceVariant = NS.text2,
            error = NS.high, outline = NS.border,
        ),
        content = content,
    )
}

@Composable
fun SectionCard(title: String? = null, sub: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = NS.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = NS.text)
            if (sub != null) Text(sub, fontSize = 13.sp, color = NS.muted)
            content()
        }
    }
}

@Composable
fun LevelChip(level: RiskLevel) {
    val c = levelColor(level)
    Box(Modifier.background(c.copy(alpha = 0.16f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(level.ru, color = c, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Verdict(level: RiskLevel, title: String, sub: String? = null) {
    val c = levelColor(level)
    Column(
        Modifier.fillMaxWidth().background(c.copy(alpha = 0.13f), RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(level.ru.uppercase(), color = c, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(title, color = NS.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        if (sub != null) Text(sub, color = NS.text2, fontSize = 13.sp)
    }
}

@Composable
fun Bullet(text: String, color: Color = NS.text2) {
    Row(verticalAlignment = Alignment.Top) {
        Text("•  ", color = color, fontSize = 14.sp)
        Text(text, color = color, fontSize = 14.sp)
    }
}
