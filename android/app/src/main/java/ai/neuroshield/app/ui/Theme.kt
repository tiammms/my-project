package ai.neuroshield.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.neuroshield.app.core.RiskLevel

/** Design tokens: Dark Premium Cybersecurity. Graphite base, cyan = AI, neon red = threat. */
object NS {
    val bg = Color(0xFF07080A)
    val surface = Color(0xFF0F1115)
    val surface2 = Color(0xFF161920)
    val surface3 = Color(0xFF1D2129)
    val border = Color(0xFF232833)
    val borderHi = Color(0xFF2F3644)
    val text = Color(0xFFF2F4F7)
    val text2 = Color(0xFFB4BCC8)
    val muted = Color(0xFF7A8494)
    val cyan = Color(0xFF22D3EE)       // AI / primary
    val cyanDeep = Color(0xFF0891B2)
    val violet = Color(0xFF8B5CF6)
    val threat = Color(0xFFFF3B5C)     // neon red
    val warn = Color(0xFFFFB020)
    val safe = Color(0xFF2EE59D)
    // legacy names used by older screens
    val teal = cyan
    val blue = cyan
    val low = safe
    val medium = warn
    val high = threat
}

fun levelColor(l: RiskLevel) = when (l) { RiskLevel.HIGH -> NS.threat; RiskLevel.MEDIUM -> NS.warn; RiskLevel.LOW -> NS.safe }
fun levelShort(l: RiskLevel) = when (l) { RiskLevel.HIGH -> "Высокий риск"; RiskLevel.MEDIUM -> "Средний риск"; RiskLevel.LOW -> "Безопасно" }

private val AppType = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp, lineHeight = 38.sp),
    headlineSmall = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
)

@Composable
fun NeuroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NS.cyan, onPrimary = Color(0xFF001A20), secondary = NS.violet, background = NS.bg, onBackground = NS.text,
            surface = NS.surface, onSurface = NS.text, surfaceVariant = NS.surface2, onSurfaceVariant = NS.text2,
            surfaceContainer = NS.surface, surfaceContainerHigh = NS.surface2, surfaceContainerLow = NS.surface,
            error = NS.threat, outline = NS.border, outlineVariant = NS.border,
        ),
        typography = AppType,
        content = content,
    )
}

// ------------------------------------------------------------------ containers
@Composable
fun GlassCard(modifier: Modifier = Modifier, accent: Color? = null, onClick: (() -> Unit)? = null, padding: Dp = 18.dp,
              content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val border = if (accent != null) Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.08f)))
    else Brush.linearGradient(listOf(NS.borderHi, NS.border.copy(alpha = 0.4f)))
    Column(
        modifier.fillMaxWidth().clip(shape)
            .background(Brush.verticalGradient(listOf(NS.surface2, NS.surface)))
            .border(BorderStroke(1.dp, border), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = NS.muted, modifier = Modifier.weight(1f))
        if (action != null && onAction != null)
            Text(action, color = NS.cyan, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(6.dp))
    }
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(tint.copy(alpha = 0.13f))
        .border(1.dp, tint.copy(alpha = 0.25f), RoundedCornerShape(size * 0.32f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun Pill(text: String, color: Color, filled: Boolean = false) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = if (filled) 0.9f else 0.12f))
        .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, color = if (filled) NS.bg else color, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp)
    }
}

@Composable
fun LevelChip(level: RiskLevel) = Pill(levelShort(level), levelColor(level))

// ------------------------------------------------------------------ buttons & rows
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, color: Color = NS.cyan, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.height(50.dp), shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = NS.bg, disabledContainerColor = NS.surface3, disabledContentColor = NS.muted),
        contentPadding = PaddingValues(horizontal = 18.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, color: Color = NS.text, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.height(46.dp), shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, NS.borderHi), colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = 14.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ListRow(icon: ImageVector, title: String, sub: String? = null, tint: Color = NS.cyan, onClick: (() -> Unit)? = null,
            trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(vertical = 8.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, tint, 38.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = NS.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (sub != null) Text(sub, color = NS.muted, style = MaterialTheme.typography.bodySmall)
        }
        trailing()
    }
}

@Composable
fun NeoSwitch(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(checked = checked, onCheckedChange = onChange,
    colors = SwitchDefaults.colors(checkedThumbColor = NS.bg, checkedTrackColor = NS.cyan, uncheckedThumbColor = NS.muted,
        uncheckedTrackColor = NS.surface3, uncheckedBorderColor = NS.borderHi))

// ------------------------------------------------------------------ indicators
/** Segmented danger meter: 3 neon segments, filled up to the level, with the score. */
@Composable
fun DangerMeter(level: RiskLevel, score: Double? = null, modifier: Modifier = Modifier) {
    val c = levelColor(level)
    val filled = level.ordinal + 1
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        for (i in 0 until 3) {
            val on = i < filled
            val alpha by animateFloatAsState(if (on) 1f else 0.18f, tween(500, delayMillis = i * 120), label = "seg")
            Box(Modifier.width(22.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.copy(alpha = alpha)))
        }
        if (score != null) {
            Spacer(Modifier.width(6.dp))
            Text("${score.toInt()}", color = c, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("/100", color = NS.muted, fontSize = 11.sp)
        }
    }
}

/** Animated circular gauge 0..1 with a gradient arc. */
@Composable
fun RingGauge(progress: Float, color: Color, size: Dp, stroke: Dp = 10.dp, content: @Composable () -> Unit = {}) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(900, easing = FastOutSlowInEasing), label = "gauge")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val s = stroke.toPx()
            val arc = Size(this.size.width - s, this.size.height - s)
            val tl = Offset(s / 2, s / 2)
            drawArc(NS.surface3, 135f, 270f, false, tl, arc, style = Stroke(s, cap = StrokeCap.Round))
            drawArc(Brush.sweepGradient(listOf(color.copy(alpha = 0.5f), color, color)), 135f, 270f * p, false, tl, arc,
                style = Stroke(s, cap = StrokeCap.Round))
        }
        content()
    }
}

/** Pulsing shield orb for the home hero: cyan when fully protected, amber when partly. */
@Composable
fun StatusOrb(color: Color, size: Dp, icon: ImageVector) {
    val t = rememberInfiniteTransition(label = "orb")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
    val glow by t.animateFloat(0.35f, 0.7f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = glow * 0.45f), Color.Transparent), radius = r), r)
            drawCircle(color.copy(alpha = (1 - pulse) * 0.5f), r * (0.55f + 0.45f * pulse), style = Stroke(2.dp.toPx()))
            drawCircle(color.copy(alpha = 0.22f), r * 0.55f, style = Stroke(1.dp.toPx()))
        }
        Box(Modifier.size(size * 0.46f).clip(CircleShape)
            .background(Brush.linearGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.55f)))), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = NS.bg, modifier = Modifier.size(size * 0.24f))
        }
    }
}

// ------------------------------------------------------------------ legacy helpers (used by Face / detail views)
@Composable
fun SectionCard(title: String? = null, sub: String? = null, content: @Composable ColumnScope.() -> Unit) {
    GlassCard {
        if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, color = NS.text)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = NS.muted)
        content()
    }
}

@Composable
fun Verdict(level: RiskLevel, title: String, sub: String? = null) {
    val c = levelColor(level)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .background(Brush.horizontalGradient(listOf(c.copy(alpha = 0.16f), c.copy(alpha = 0.04f))))
        .border(1.dp, c.copy(alpha = 0.35f), RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DangerMeter(level)
        Text(title, color = NS.text, style = MaterialTheme.typography.titleMedium)
        if (sub != null) Text(sub, color = NS.text2, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun Bullet(text: String, color: Color = NS.text2) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 8.dp).size(5.dp).clip(CircleShape).background(NS.cyan.copy(alpha = 0.8f)))
        Spacer(Modifier.width(10.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}
