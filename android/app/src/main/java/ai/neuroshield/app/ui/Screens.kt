package ai.neuroshield.app.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ai.neuroshield.app.Perms
import ai.neuroshield.app.alert.Warning
import ai.neuroshield.app.alert.Warnings
import ai.neuroshield.app.app
import ai.neuroshield.app.core.RiskLevel
import ai.neuroshield.app.core.ScamAnalyzer
import ai.neuroshield.app.core.TextRisk
import ai.neuroshield.app.data.Alert
import ai.neuroshield.app.listen.ListenService
import ai.neuroshield.app.listen.ListenState
import ai.neuroshield.app.listen.SpeechModels
import ai.neuroshield.app.video.VideoState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Increments every time the screen comes back to the foreground (permissions may have changed in Settings). */
@Composable
fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

@Composable
fun Page(title: String, sub: String? = null, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(NS.surface2)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = NS.text)
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = NS.text)
                if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = NS.muted)
            }
            actions()
        }
        content()
        Spacer(Modifier.height(20.dp))
    }
}

// ------------------------------------------------------------------ protection modules
data class ProtectionItem(val title: String, val why: String, val ok: Boolean, val icon: ImageVector)

fun protectionItems(ctx: Context): List<ProtectionItem> {
    val model = SpeechModels.modelDir(ctx, ctx.app.prefs.speechLang) != null
    return listOf(
        ProtectionItem("Определитель номера", "Оценка звонка до ответа", Perms.screeningRole(ctx), Icons.Filled.Call),
        ProtectionItem("Состояние звонка", "Знать, что идёт разговор", Perms.phone(ctx), Icons.Filled.GppGood),
        ProtectionItem("Контакты", "Не беспокоить при звонках знакомых", Perms.contacts(ctx), Icons.Filled.Contacts),
        ProtectionItem("Коды из SMS", "Тревога, если код пришёл во время звонка", Perms.sms(ctx), Icons.Filled.Sms),
        ProtectionItem("Окно поверх звонка", "Предупреждение прямо на экране звонка", Perms.overlay(ctx), Icons.Filled.Layers),
        ProtectionItem("Уведомления", "Тревоги и быстрые действия", Perms.notifications(ctx), Icons.Filled.NotificationsActive),
        ProtectionItem("Голосовой ИИ", "Микрофон и офлайн-модель речи", Perms.mic(ctx) && model, Icons.Filled.Mic),
        ProtectionItem("Мессенджеры", "WhatsApp, Telegram, Viber и другие", Perms.notificationAccess(ctx), Icons.Filled.Forum),
    )
}

fun sourceIcon(source: String): ImageVector = when (source) {
    "incoming_call" -> Icons.Filled.Call
    "sms_code", "sms_text" -> Icons.Filled.Sms
    "live_speech" -> Icons.Filled.GraphicEq
    "messenger_call", "messenger_text" -> Icons.Filled.Forum
    "video_call" -> Icons.Filled.Videocam
    "face" -> Icons.Filled.Face
    "manual_text" -> Icons.Filled.TextSnippet
    "demo" -> Icons.Filled.PlayCircle
    else -> Icons.Filled.Shield
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale("ru"))
private val dayFmt = SimpleDateFormat("d MMMM", Locale("ru"))

fun dayLabel(ts: Long): String {
    val c = Calendar.getInstance(); val d = Calendar.getInstance().apply { timeInMillis = ts }
    val same = { a: Calendar, b: Calendar -> a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR) }
    if (same(c, d)) return "Сегодня"
    c.add(Calendar.DAY_OF_YEAR, -1)
    if (same(c, d)) return "Вчера"
    return dayFmt.format(Date(ts))
}

/** One threat in a list: severity stripe, source icon, one-line title, time and source. */
@Composable
fun ThreatRow(a: Alert, onClick: () -> Unit) {
    val c = levelColor(a.level)
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(18.dp)).background(NS.surface2)
        .border(1.dp, NS.border, RoundedCornerShape(18.dp)).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(64.dp).background(Brush.verticalGradient(listOf(c, c.copy(alpha = 0.3f)))))
        Spacer(Modifier.width(12.dp))
        IconBadge(sourceIcon(a.source), c, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(a.title, color = NS.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${timeFmt.format(Date(a.ts))} · ${a.sourceRu}", color = NS.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Pill(a.level.let { if (it == RiskLevel.LOW) "Низкий" else if (it == RiskLevel.MEDIUM) "Средний" else "Высокий" }, c)
        Spacer(Modifier.width(12.dp))
    }
}

// =========================================================================== HOME
@Composable
fun HomeScreen(go: (String) -> Unit, openAlert: (Long) -> Unit = { go("history") }) {
    val ctx = LocalContext.current
    rememberResumeTick()
    val version by ctx.app.alerts.version.collectAsState()
    val items = protectionItems(ctx)
    val on = items.count { it.ok }
    val full = on == items.size
    val alerts = remember(version) { ctx.app.alerts.list(100) }
    val today = alerts.count { System.currentTimeMillis() - it.ts < 86_400_000 }
    val high = alerts.count { it.level == RiskLevel.HIGH }
    val accent = if (full) NS.cyan else NS.warn

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text("Saq", style = MaterialTheme.typography.titleLarge, color = NS.text)
            Text("tan", style = MaterialTheme.typography.titleLarge, color = NS.cyan)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { go("settings") }, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(NS.surface2)) {
                Icon(Icons.Filled.Settings, "Настройки", tint = NS.text2)
            }
        }

        // hero
        GlassCard(accent = accent, padding = 20.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusOrb(accent, 96.dp, if (full) Icons.Filled.Shield else Icons.Filled.GppMaybe)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("СТАТУС ЗАЩИТЫ", style = MaterialTheme.typography.labelSmall, color = NS.muted)
                    Text(if (full) "Под защитой" else "Частично", style = MaterialTheme.typography.headlineSmall, color = NS.text, maxLines = 1)
                    Text("$on из ${items.size} модулей активны", style = MaterialTheme.typography.bodySmall, color = NS.text2)
                }
            }
            if (!full) PrimaryButton("Включить все модули", Modifier.fillMaxWidth(), color = NS.warn) { go("protection") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(Modifier.weight(1f), "$today", "за сутки", NS.cyan) { go("history") }
            StatTile(Modifier.weight(1f), "$high", "высокий риск", NS.threat) { go("history") }
            StatTile(Modifier.weight(1f), "${alerts.size}", "всего", NS.text2) { go("history") }
        }

        if (alerts.isNotEmpty()) WeekChart(alerts)

        // demo scenario for presentations: scripted call, real analysis
        GlassCard(accent = NS.violet, onClick = { go("demo") }, padding = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.PlayCircle, NS.violet, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Демо-показ", style = MaterialTheme.typography.titleMedium, color = NS.text)
                    Text("Сценарий звонка мошенника по шагам — для презентации", style = MaterialTheme.typography.bodySmall, color = NS.text2)
                }
                Icon(Icons.Filled.ChevronRight, null, tint = NS.violet)
            }
        }

        SectionTitle("Быстрые проверки")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(Modifier.weight(1f), Icons.Filled.TextSnippet, "Текст", "SMS и слова звонящего", NS.cyan) { go("text") }
            ActionTile(Modifier.weight(1f), Icons.Filled.Videocam, "Видео", "Живой ли собеседник", NS.violet) { go("video") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(Modifier.weight(1f), Icons.Filled.Face, "Лицо", "Проверка живости", NS.safe) { go("face") }
            ActionTile(Modifier.weight(1f), Icons.Filled.Warning, "Тест", "Пример окна угрозы", NS.threat) {
                Warnings.show(ctx, Warning(RiskLevel.HIGH, "Просят продиктовать код из SMS",
                    listOf("Звонящий представился службой безопасности банка", "Просит назвать код из SMS"),
                    listOf("Положите трубку и позвоните в банк сами"), source = "test", number = "+7 700 000 00 00", score = 92.0, isTest = true))
            }
        }

        SectionTitle("Последние угрозы", if (alerts.isNotEmpty()) "Все" else null) { go("history") }
        if (alerts.isEmpty()) EmptyState("Угроз не обнаружено", "Saqtan предупредит, если звонок или сообщение покажутся подозрительными")
        else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { alerts.take(3).forEach { a -> ThreatRow(a) { openAlert(a.id) } } }
    }
}

/** Threats of the last 7 days, stacked by level. */
@Composable
fun WeekChart(alerts: List<Alert>) {
    val days = (6 downTo 0).map { back ->
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -back); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val start = c.timeInMillis; val end = start + 86_400_000
        val inDay = alerts.filter { it.ts in start until end }
        Triple(SimpleDateFormat("EE", Locale("ru")).format(Date(start)).take(2), inDay, back == 0)
    }
    val max = (days.maxOf { it.second.size }).coerceAtLeast(1)
    val anim by androidx.compose.animation.core.animateFloatAsState(1f, androidx.compose.animation.core.tween(700), label = "bars")
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Угрозы за 7 дней", style = MaterialTheme.typography.titleMedium, color = NS.text, modifier = Modifier.weight(1f))
            Text("${days.sumOf { it.second.size }}", style = MaterialTheme.typography.titleMedium, color = NS.cyan)
        }
        Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            days.forEach { (label, list, today) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    val h = 70f * list.size / max * anim
                    Column(Modifier.width(16.dp).height((h.coerceAtLeast(3f)).dp).clip(RoundedCornerShape(6.dp)).background(NS.surface3)) {
                        listOf(RiskLevel.HIGH, RiskLevel.MEDIUM, RiskLevel.LOW).forEach { lv ->
                            val n = list.count { it.level == lv }
                            if (n > 0) Box(Modifier.fillMaxWidth().weight(n.toFloat()).background(levelColor(lv)))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(label, fontSize = 11.sp, color = if (today) NS.cyan else NS.muted)
                }
            }
        }
    }
}

@Composable
private fun StatTile(m: Modifier, value: String, label: String, color: Color, onClick: () -> Unit) {
    Column(m.clip(RoundedCornerShape(18.dp)).background(NS.surface2).border(1.dp, NS.border, RoundedCornerShape(18.dp))
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = NS.muted, maxLines = 1)
    }
}

@Composable
private fun ActionTile(m: Modifier, icon: ImageVector, title: String, sub: String, tint: Color, onClick: () -> Unit) {
    Column(m.height(118.dp).clip(RoundedCornerShape(20.dp))
        .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.12f), NS.surface2)))
        .border(1.dp, tint.copy(alpha = 0.22f), RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween) {
        IconBadge(icon, tint, 36.dp)
        Column {
            Text(title, color = NS.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(sub, color = NS.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun EmptyState(title: String, sub: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(NS.surface).border(1.dp, NS.border, RoundedCornerShape(20.dp))
        .padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        IconBadge(Icons.Filled.GppGood, NS.safe, 48.dp)
        Text(title, color = NS.text, style = MaterialTheme.typography.titleMedium)
        Text(sub, color = NS.muted, style = MaterialTheme.typography.bodySmall)
    }
}

// =========================================================================== PROTECTION
@Composable
fun ProtectionScreen(onListen: () -> Unit, onVideo: () -> Unit = {}) {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    var refresh by remember { mutableIntStateOf(0) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    val items = remember(tick, refresh) { protectionItems(ctx) }
    val scope = rememberCoroutineScope()
    val models by SpeechModels.status.collectAsState()
    val ui by ListenState.ui.collectAsState()
    val vui by VideoState.ui.collectAsState()
    var lang by remember { mutableStateOf(ctx.app.prefs.speechLang) }
    val on = items.count { it.ok }

    fun request(vararg p: String) = perms.launch(p.toList().toTypedArray())
    fun runtimeAll() = request(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS,
        Manifest.permission.RECEIVE_SMS, Manifest.permission.RECORD_AUDIO,
        *(if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()))
    fun fix(i: Int) {
        when (i) {
            0 -> ctx.getSystemService(RoleManager::class.java)?.let { rm ->
                if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) role.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
            }
            1 -> request(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG)
            2 -> request(Manifest.permission.READ_CONTACTS)
            3 -> request(Manifest.permission.RECEIVE_SMS)
            4 -> ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            5 -> if (android.os.Build.VERSION.SDK_INT >= 33) request(Manifest.permission.POST_NOTIFICATIONS)
            6 -> request(Manifest.permission.RECORD_AUDIO)
            7 -> ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    Page("Защита звонков", "Модули, голосовой и видео-анализ") {
        // ---- overview
        GlassCard(accent = if (on == items.size) NS.cyan else NS.warn) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RingGauge(on / items.size.toFloat(), if (on == items.size) NS.cyan else NS.warn, 92.dp) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$on/${items.size}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = NS.text)
                        Text("модулей", fontSize = 11.sp, color = NS.muted)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (on == items.size) "Все модули активны" else "Включите все модули", style = MaterialTheme.typography.titleMedium, color = NS.text)
                    Text("Данные анализируются на телефоне и никуда не отправляются", style = MaterialTheme.typography.bodySmall, color = NS.muted)
                }
            }
            if (on < items.size) PrimaryButton("Разрешить всё", Modifier.fillMaxWidth()) { runtimeAll() }
        }

        SectionTitle("Модули")
        GlassCard(padding = 10.dp) {
            items.forEachIndexed { i, item ->
                ListRow(item.icon, item.title, item.why, tint = if (item.ok) NS.cyan else NS.muted,
                    onClick = if (item.ok) null else ({ fix(i) })) {
                    if (item.ok) Icon(Icons.Filled.CheckCircle, "включено", tint = NS.safe, modifier = Modifier.size(22.dp))
                    else Text("Включить", color = NS.cyan, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // ---- voice AI
        SectionTitle("Голосовой ИИ")
        GlassCard(accent = if (ui.running) (ui.risk?.level?.let { levelColor(it) } ?: NS.cyan) else null) {
            if (ui.running) {
                val r = ui.risk
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RingGauge(((r?.score ?: 0.0) / 100).toFloat(), r?.level?.let { levelColor(it) } ?: NS.cyan, 84.dp, 8.dp) {
                        Text("${(r?.score ?: 0.0).toInt()}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = NS.text)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (ui.inCall) "Слушаю звонок" else "Проверка микрофона", style = MaterialTheme.typography.titleMedium, color = NS.text)
                        Text(if (ui.speakerOn) "Громкая связь включена" else "Включите громкую связь", style = MaterialTheme.typography.bodySmall,
                            color = if (ui.speakerOn) NS.safe else NS.warn)
                        LevelBars(ui.micLevel)
                    }
                }
                if (ui.transcript.isNotBlank())
                    Text("«…${ui.transcript.takeLast(180)}»", color = NS.text2, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp)
                r?.tactics?.takeIf { it.isNotEmpty() }?.let { t ->
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        t.forEach { Pill(it.title, levelColor(r.level)) }
                    }
                }
                GhostButton("Остановить", Modifier.fillMaxWidth()) { ListenService.stop(ctx, "Остановлено") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpeechModels.all.forEach { m ->
                        val sel = lang == m.lang
                        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if (sel) NS.cyan.copy(alpha = 0.16f) else NS.surface3)
                            .border(1.dp, if (sel) NS.cyan.copy(alpha = 0.5f) else NS.border, RoundedCornerShape(12.dp))
                            .clickable { lang = m.lang; ctx.app.prefs.speechLang = m.lang; refresh++ }.padding(horizontal = 12.dp, vertical = 7.dp)) {
                            Text(m.title, color = if (sel) NS.cyan else NS.text2, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                val m = SpeechModels.all.first { it.lang == lang }
                when (val s = models[lang] ?: SpeechModels.Status.Missing) {
                    is SpeechModels.Status.Ready -> {
                        PrimaryButton(if (ctx.app.prefs.callState == "OFFHOOK") "Слушать звонок" else "Проверить микрофон", Modifier.fillMaxWidth()) { onListen() }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Модель речи «${m.title}» установлена · офлайн", color = NS.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text("Удалить", color = NS.text2, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { SpeechModels.delete(ctx, lang); refresh++ }.padding(6.dp))
                        }
                    }
                    is SpeechModels.Status.Downloading -> {
                        Text("Загрузка модели… ${s.percent}%", color = NS.text2, style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = NS.cyan, trackColor = NS.surface3)
                    }
                    is SpeechModels.Status.Failed -> {
                        Text("Не удалось скачать: ${s.error}", color = NS.threat, style = MaterialTheme.typography.bodySmall)
                        PrimaryButton("Повторить · ${m.sizeMb} МБ", Modifier.fillMaxWidth()) { SpeechModels.download(ctx, scope, lang) }
                    }
                    SpeechModels.Status.Missing -> PrimaryButton("Скачать модель речи · ${m.sizeMb} МБ", Modifier.fillMaxWidth()) { SpeechModels.download(ctx, scope, lang) }
                }
            }
            ui.message?.let { Text(it, color = if (ui.silenceDetected) NS.threat else NS.text2, style = MaterialTheme.typography.bodySmall) }
            Text("Звук звонка доступен только через громкую связь — ограничение Android", color = NS.muted, style = MaterialTheme.typography.bodySmall)
        }

        // ---- video AI
        SectionTitle("Видео-анализ")
        GlassCard(accent = vui.result?.level?.let { levelColor(it) }) {
            if (vui.running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RingGauge(vui.seconds / 30f, NS.violet, 72.dp, 7.dp) { Text("${vui.seconds}с", color = NS.text, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Анализ лица на экране", style = MaterialTheme.typography.titleMedium, color = NS.text)
                        Text("Кадров с лицом: ${vui.faceFrames} из ${vui.frames}", style = MaterialTheme.typography.bodySmall, color = NS.muted)
                    }
                }
            } else {
                ListRow(Icons.Filled.Videocam, "Проверить видеозвонок", "30 секунд · фото или запись вместо человека?", tint = NS.violet, onClick = onVideo) {
                    Icon(Icons.Filled.ChevronRight, null, tint = NS.muted)
                }
            }
            vui.result?.let { v ->
                Verdict(v.level, v.title, "Морганий ${v.blinks} · лицо видно ${(v.facePresentRatio * 100).toInt()}% · ${v.seconds.toInt()} с")
            }
            vui.message?.let { Text(it, color = NS.text2, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun LevelBars(level: Double) {
    val n = 14
    val lit = ((level * 20).coerceIn(0.0, 1.0) * n).toInt()
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.height(18.dp)) {
        for (i in 0 until n) {
            val h = 6 + (i % 5) * 3
            Box(Modifier.width(4.dp).height(h.dp).clip(RoundedCornerShape(2.dp)).background(if (i < lit) NS.cyan else NS.surface3))
        }
    }
}

@Composable
fun RiskDetails(r: TextRisk) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        r.tactics.forEach { t ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(NS.surface3).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t.title, color = NS.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                t.evidence.firstOrNull()?.let { e -> Text("«${e.context}»", color = NS.text2, style = MaterialTheme.typography.bodySmall) }
                Text(t.advice, color = NS.cyan, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// =========================================================================== TEXT CHECK
@Composable
fun TextCheckScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<TextRisk?>(null) }
    Page("Проверка текста", "SMS, сообщение или слова звонящего", onBack) {
        OutlinedTextField(value = text, onValueChange = { text = it.take(ScamAnalyzer.MAX_TEXT) }, modifier = Modifier.fillMaxWidth().height(170.dp),
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NS.cyan, unfocusedBorderColor = NS.border,
                focusedContainerColor = NS.surface, unfocusedContainerColor = NS.surface),
            placeholder = { Text("«Это служба безопасности банка, продиктуйте код из СМС…»", color = NS.muted) })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Проверить", Modifier.weight(1f), enabled = text.isNotBlank()) {
                val r = ScamAnalyzer.analyze(text); result = r
                if (r.level != RiskLevel.LOW) ctx.app.alerts.add("manual_text", null, r.level, r.score, "Проверка текста: ${r.summary.lowercase()}",
                    r.tactics.map { it.title }, if (ctx.app.prefs.storeExcerpts) text.take(600) else null)
            }
            GhostButton("Пример") { text = "Здравствуйте, вас беспокоит служба безопасности банка. С вашей карты пытаются списать деньги. Срочно продиктуйте код из СМС и никому не говорите об этом звонке." }
        }
        result?.let { r ->
            GlassCard(accent = levelColor(r.level)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RingGauge((r.score / 100).toFloat(), levelColor(r.level), 76.dp, 8.dp) {
                        Text("${r.score.toInt()}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = NS.text)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(levelShort(r.level).uppercase(), style = MaterialTheme.typography.labelSmall, color = levelColor(r.level))
                        Text(r.summary, style = MaterialTheme.typography.titleMedium, color = NS.text)
                        if (r.words < 8) Text("Текст короткий — оценка неточная", style = MaterialTheme.typography.bodySmall, color = NS.muted)
                    }
                }
            }
            RiskDetails(r)
        }
    }
}

// =========================================================================== HISTORY
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(openId: Long? = null, onOpened: () -> Unit = {}) {
    val ctx = LocalContext.current
    val version by ctx.app.alerts.version.collectAsState()
    val list = remember(version) { ctx.app.alerts.list() }
    var filter by remember { mutableStateOf<RiskLevel?>(null) }
    var sheet by remember { mutableStateOf<Alert?>(null) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(openId, list) {
        if (openId != null) { list.firstOrNull { it.id == openId }?.let { sheet = it }; onOpened() }
    }
    val shown = list.filter { filter == null || it.level == filter }

    Page("Угрозы", "История хранится только на телефоне", actions = {
        if (list.isNotEmpty()) {
            IconButton(onClick = { shareReport(ctx, shown) }, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(NS.surface2)) {
                Icon(Icons.Filled.Share, "Поделиться отчётом", tint = NS.text2)
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { confirm = true }, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(NS.surface2)) {
                Icon(Icons.Filled.DeleteOutline, "Очистить", tint = NS.text2)
            }
        }
    }) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill("Все · ${list.size}", filter == null, NS.cyan) { filter = null }
            FilterPill("Высокий · ${list.count { it.level == RiskLevel.HIGH }}", filter == RiskLevel.HIGH, NS.threat) { filter = RiskLevel.HIGH }
            FilterPill("Средний · ${list.count { it.level == RiskLevel.MEDIUM }}", filter == RiskLevel.MEDIUM, NS.warn) { filter = RiskLevel.MEDIUM }
            FilterPill("Низкий · ${list.count { it.level == RiskLevel.LOW }}", filter == RiskLevel.LOW, NS.safe) { filter = RiskLevel.LOW }
        }
        if (shown.isEmpty()) EmptyState("Здесь пока пусто", "Подозрительные звонки и сообщения появятся в этом списке")
        shown.groupBy { dayLabel(it.ts) }.forEach { (day, group) ->
            SectionTitle(day)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { group.forEach { a -> ThreatRow(a) { sheet = a } } }
        }
    }

    sheet?.let { a ->
        ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = NS.surface, dragHandle = { Box(Modifier.padding(top = 10.dp).width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(NS.borderHi)) }) {
            ThreatDetail(a, onClose = { sheet = null })
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, containerColor = NS.surface2,
        confirmButton = { TextButton(onClick = { ctx.app.alerts.clear(); confirm = false }) { Text("Удалить всё", color = NS.threat) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена", color = NS.text2) } },
        title = { Text("Очистить историю?", color = NS.text) }, text = { Text("Все записи об угрозах будут удалены с телефона.", color = NS.text2) })
}

/** Share a plain-text report (e.g. to a relative or the bank's fraud department). No transcripts, no SMS bodies. */
fun shareReport(ctx: Context, items: List<Alert>) {
    val f = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru"))
    val body = buildString {
        appendLine("Saqtan — отчёт об угрозах (${items.size})")
        appendLine()
        items.take(100).forEach { a ->
            appendLine("${f.format(Date(a.ts))} · ${levelShort(a.level)} · ${a.sourceRu}")
            appendLine("  ${a.title}" + (a.number?.let { " · $it" } ?: ""))
            a.reasons.take(3).forEach { appendLine("  – $it") }
        }
    }
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Отчёт Saqtan").putExtra(Intent.EXTRA_TEXT, body)
    ctx.startActivity(Intent.createChooser(send, "Поделиться отчётом").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun NeoField(value: String, label: String, type: KeyboardType, icon: ImageVector, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(label) }, leadingIcon = { Icon(icon, null, tint = NS.muted) }, shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = type),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NS.cyan, unfocusedBorderColor = NS.border, focusedLabelColor = NS.cyan,
            unfocusedLabelColor = NS.muted, focusedContainerColor = NS.surface, unfocusedContainerColor = NS.surface))
}

@Composable
private fun FilterPill(text: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(if (selected) color.copy(alpha = 0.16f) else NS.surface2)
        .border(1.dp, if (selected) color.copy(alpha = 0.6f) else NS.border, RoundedCornerShape(50))
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text, color = if (selected) color else NS.text2, style = MaterialTheme.typography.labelLarge)
    }
}

/** Full analysis of one threat (opened from the list or from the warning's «Подробнее»). */
@Composable
fun ThreatDetail(a: Alert, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val c = levelColor(a.level)
    var marked by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RingGauge((a.score / 100).toFloat(), c, 78.dp, 8.dp) { Text("${a.score.toInt()}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = NS.text) }
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(levelShort(a.level).uppercase(), style = MaterialTheme.typography.labelSmall, color = c)
                Text(a.title, style = MaterialTheme.typography.titleMedium, color = NS.text)
                Text("${dayLabel(a.ts)}, ${timeFmt.format(Date(a.ts))} · ${a.sourceRu}", style = MaterialTheme.typography.bodySmall, color = NS.muted)
            }
        }
        a.number?.let { ListRow(Icons.Filled.Public, it, "Номер / отправитель", tint = NS.text2) }
        if (a.reasons.isNotEmpty()) {
            SectionTitle("Почему это подозрительно")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { a.reasons.forEach { Bullet(it, NS.text) } }
        }
        a.excerpt?.let {
            SectionTitle("Фрагмент")
            Text("«$it»", color = NS.text2, style = MaterialTheme.typography.bodySmall)
        }
        val num = a.number?.takeIf { n -> n.any { it.isDigit() } }
        if (num != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Это мошенник", Modifier.weight(1f), color = NS.threat) { ctx.app.prefs.mark(num, scam = true); marked = "Номер отмечен как мошеннический" }
                GhostButton("Не мошенник", Modifier.weight(1f)) { ctx.app.prefs.mark(num, scam = false); marked = "Номер отмечен как надёжный" }
            }
            marked?.let { Text(it, color = NS.safe, style = MaterialTheme.typography.bodySmall) }
        }
        GhostButton("Удалить запись", Modifier.fillMaxWidth(), color = NS.text2) { ctx.app.alerts.delete(a.id); onClose() }
    }
}

// =========================================================================== SETTINGS
@Composable
fun SettingsScreen(onReplayOnboarding: () -> Unit = {}) {
    val ctx = LocalContext.current
    val p = ctx.app.prefs
    var code by remember { mutableStateOf(p.homeCallingCode) }
    var warnUnknown by remember { mutableStateOf(p.warnUnknownCallers) }
    var offer by remember { mutableStateOf(p.offerListening) }
    var auto by remember { mutableStateOf(p.autoListen) }
    var excerpts by remember { mutableStateOf(p.storeExcerpts) }
    var marks by remember { mutableIntStateOf(0) }
    var about by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }
    var trustedName by remember { mutableStateOf(p.trustedName) }
    var trustedNumber by remember { mutableStateOf(p.trustedNumber) }
    var bankNumber by remember { mutableStateOf(p.bankNumber) }
    var block by remember { mutableStateOf(p.blockMarked) }
    Page("Настройки") {
        SectionTitle("Звонки")
        GlassCard(padding = 10.dp) {
            ListRow(Icons.Filled.Public, "Код страны", "Звонки с других кодов считаются международными") {
                OutlinedTextField(value = code, onValueChange = { v -> code = v.filter { it.isDigit() || it == '+' }.take(5); if (code.startsWith("+")) p.homeCallingCode = code },
                    singleLine = true, modifier = Modifier.width(86.dp), shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NS.cyan, unfocusedBorderColor = NS.border))
            }
            ListRow(Icons.Filled.Call, "Любой незнакомый номер", "Короткий совет при каждом таком звонке",
                onClick = { warnUnknown = !warnUnknown; p.warnUnknownCallers = warnUnknown }) { NeoSwitch(warnUnknown) { warnUnknown = it; p.warnUnknownCallers = it } }
            ListRow(Icons.Filled.Mic, "Кнопка анализа речи", "Во время звонка с незнакомцем",
                onClick = { offer = !offer; p.offerListening = offer }) { NeoSwitch(offer) { offer = it; p.offerListening = it } }
            ListRow(Icons.Filled.GraphicEq, "Автоанализ речи", "Начинать слушать сразу после ответа",
                onClick = { auto = !auto; p.autoListen = auto }) { NeoSwitch(auto) { auto = it; p.autoListen = it } }
            ListRow(Icons.Filled.Block, "Блокировать отмеченных мошенников", if (Perms.screeningRole(ctx)) "Звонки с отмеченных номеров сбрасываются" else "Нужен модуль «Определитель номера»",
                tint = NS.threat, onClick = { block = !block; p.blockMarked = block }) { NeoSwitch(block) { block = it; p.blockMarked = it } }
        }

        SectionTitle("Экстренная помощь")
        GlassCard(padding = 14.dp) {
            Text("При высоком риске в окне угрозы появятся кнопки «Позвонить близкому» и «Позвонить в банк». Открывается набор номера — звонок не начинается сам.",
                color = NS.muted, style = MaterialTheme.typography.bodySmall)
            NeoField(trustedName, "Имя близкого (например, «Сын»)", KeyboardType.Text, Icons.Filled.FavoriteBorder) { trustedName = it.take(30); p.trustedName = trustedName }
            NeoField(trustedNumber, "Телефон близкого", KeyboardType.Phone, Icons.Filled.Call) { trustedNumber = it.filter { c -> c.isDigit() || c in "+ ()-" }.take(20); p.trustedNumber = trustedNumber }
            NeoField(bankNumber, "Номер вашего банка (с карты)", KeyboardType.Phone, Icons.Filled.AccountBalance) { bankNumber = it.filter { c -> c.isDigit() || c in "+ ()-" }.take(20); p.bankNumber = bankNumber }
        }

        SectionTitle("Конфиденциальность")
        GlassCard(padding = 10.dp) {
            ListRow(Icons.Filled.Lock, "Хранить фрагменты разговоров", "По умолчанию — только причины угрозы",
                onClick = { excerpts = !excerpts; p.storeExcerpts = excerpts }) { NeoSwitch(excerpts) { excerpts = it; p.storeExcerpts = it } }
            ListRow(Icons.Filled.DeleteOutline, "Очистить историю угроз", if (cleared) "История удалена" else "Удалить все записи с телефона", tint = NS.threat,
                onClick = { ctx.app.alerts.clear(); cleared = true })
        }

        SectionTitle("Отмеченные номера")
        GlassCard(padding = 10.dp) {
            val scam = remember(marks) { p.scamNumbers() }; val safe = remember(marks) { p.safeNumbers() }
            if (scam.isEmpty() && safe.isEmpty()) Text("Нет отмеченных номеров. Отметить можно в карточке угрозы.", color = NS.muted,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
            (scam.map { it to true } + safe.map { it to false }).forEach { (n, isScam) ->
                ListRow(if (isScam) Icons.Filled.Warning else Icons.Filled.GppGood, n, if (isScam) "Мошенник" else "Надёжный",
                    tint = if (isScam) NS.threat else NS.safe) {
                    Text("Убрать", color = NS.text2, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { p.unmark(n); marks++ }.padding(8.dp))
                }
            }
        }

        SectionTitle("О защите")
        GlassCard(padding = 14.dp) {
            Row(Modifier.fillMaxWidth().clickable { about = !about }, verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.Info, NS.cyan, 38.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Как работает Saqtan", color = NS.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text("Возможности и честные ограничения", color = NS.muted, style = MaterialTheme.typography.bodySmall)
                }
                Icon(if (about) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = NS.muted)
            }
            AnimatedVisibility(about, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Bullet("До ответа оценивает номер: нет в контактах, скрытый, международный, частые звонки, ваши отметки.")
                    Bullet("Код из SMS во время звонка с незнакомцем — сразу тревога.")
                    Bullet("Мессенджеры: по уведомлению видит звонки и проверяет сообщения.")
                    Bullet("Речь: офлайн-распознавание Vosk, только через громкую связь (ограничение Android).")
                    Bullet("Видео: 30 секунд анализа лица на экране (ML Kit) — фото или запись вместо человека.")
                    Bullet("Не распознаёт дипфейки и клонированный голос; по голосу человека мошенником не называет.")
                    Text("Исследовательский прототип · не заменяет банк", color = NS.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        GlassCard(padding = 10.dp) {
            ListRow(Icons.Filled.Replay, "Показать знакомство снова", "Короткий рассказ о возможностях", onClick = onReplayOnboarding)
        }
    }
}
