package ai.neuroshield.app.ui

import ai.neuroshield.app.tr
import ai.neuroshield.app.I18n

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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Назад", "Артқа"), tint = NS.text)
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
        ProtectionItem(tr("Определитель номера", "Нөмір анықтағыш"), tr("Оценка звонка до ответа", "Жауап бергенге дейін қоңырауды бағалау"), Perms.screeningRole(ctx), Icons.Filled.Call),
        ProtectionItem(tr("Состояние звонка", "Қоңырау күйі"), tr("Знать, что идёт разговор", "Сөйлесу жүріп жатқанын білу"), Perms.phone(ctx), Icons.Filled.GppGood),
        ProtectionItem(tr("Контакты", "Контактілер"), tr("Не беспокоить при звонках знакомых", "Таныстар қоңырау шалғанда мазаламау"), Perms.contacts(ctx), Icons.Filled.Contacts),
        ProtectionItem(tr("Коды из SMS", "SMS кодтары"), tr("Тревога, если код пришёл во время звонка", "Код қоңырау кезінде келсе — дабыл"), Perms.sms(ctx), Icons.Filled.Sms),
        ProtectionItem(tr("Окно поверх звонка", "Қоңырау үстіндегі терезе"), tr("Предупреждение прямо на экране звонка", "Ескерту тікелей қоңырау экранында"), Perms.overlay(ctx), Icons.Filled.Layers),
        ProtectionItem(tr("Уведомления", "Хабарландырулар"), tr("Тревоги и быстрые действия", "Дабылдар және жылдам әрекеттер"), Perms.notifications(ctx), Icons.Filled.NotificationsActive),
        ProtectionItem(tr("Голосовой ИИ", "Дауыстық ЖИ"), tr("Микрофон и офлайн-модель речи", "Микрофон және офлайн сөйлеу моделі"), Perms.mic(ctx) && model, Icons.Filled.Mic),
        ProtectionItem(tr("Мессенджеры", "Мессенджерлер"), tr("WhatsApp, Telegram, Viber и другие", "WhatsApp, Telegram, Viber және басқалар"), Perms.notificationAccess(ctx), Icons.Filled.Forum),
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
    if (same(c, d)) return tr("Сегодня", "Бүгін")
    c.add(Calendar.DAY_OF_YEAR, -1)
    if (same(c, d)) return tr("Вчера", "Кеше")
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
            Text(I18n.core(a.title), color = NS.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${timeFmt.format(Date(a.ts))} · ${a.sourceRu}", color = NS.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Pill(a.level.let { if (it == RiskLevel.LOW) tr("Низкий", "Төмен") else if (it == RiskLevel.MEDIUM) tr("Средний", "Орташа") else tr("Высокий", "Жоғары") }, c)
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
                Icon(Icons.Filled.Settings, tr("Настройки", "Баптаулар"), tint = NS.text2)
            }
        }

        // hero
        GlassCard(accent = accent, padding = 20.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusOrb(accent, 96.dp, if (full) Icons.Filled.Shield else Icons.Filled.GppMaybe)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr("СТАТУС ЗАЩИТЫ", "ҚОРҒАНЫС КҮЙІ"), style = MaterialTheme.typography.labelSmall, color = NS.muted)
                    Text(if (full) tr("Под защитой", "Қорғалған") else tr("Частично", "Ішінара"), style = MaterialTheme.typography.headlineSmall, color = NS.text, maxLines = 1)
                    Text(tr("$on из ${items.size} модулей активны", "${items.size} модульдің $on белсенді"), style = MaterialTheme.typography.bodySmall, color = NS.text2)
                }
            }
            if (!full) PrimaryButton(tr("Включить все модули", "Барлық модульдерді қосу"), Modifier.fillMaxWidth(), color = NS.warn) { go("protection") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(Modifier.weight(1f), "$today", tr("за сутки", "тәулік ішінде"), NS.cyan) { go("history") }
            StatTile(Modifier.weight(1f), "$high", tr("высокий риск", "жоғары қауіп"), NS.threat) { go("history") }
            StatTile(Modifier.weight(1f), "${alerts.size}", tr("всего", "барлығы"), NS.text2) { go("history") }
        }

        if (alerts.isNotEmpty()) WeekChart(alerts)

        // demo scenario for presentations: scripted call, real analysis
        GlassCard(accent = NS.violet, onClick = { go("demo") }, padding = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.PlayCircle, NS.violet, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("Демо-показ", "Демо-көрсетілім"), style = MaterialTheme.typography.titleMedium, color = NS.text)
                    Text(tr("Сценарий звонка мошенника по шагам — для презентации", "Алаяқ қоңырауының сценарийі қадам бойынша — таныстырылым үшін"), style = MaterialTheme.typography.bodySmall, color = NS.text2)
                }
                Icon(Icons.Filled.ChevronRight, null, tint = NS.violet)
            }
        }

        SectionTitle(tr("Быстрые проверки", "Жылдам тексерулер"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(Modifier.weight(1f), Icons.Filled.TextSnippet, tr("Текст", "Мәтін"), tr("SMS и слова звонящего", "SMS және қоңырау шалушының сөздері"), NS.cyan) { go("text") }
            ActionTile(Modifier.weight(1f), Icons.Filled.Videocam, tr("Видео", "Бейне"), tr("Живой ли собеседник", "Әңгімелесуші тірі ме"), NS.violet) { go("video") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(Modifier.weight(1f), Icons.Filled.Face, tr("Лицо", "Бет"), tr("Проверка живости", "Тірілікті тексеру"), NS.safe) { go("face") }
            ActionTile(Modifier.weight(1f), Icons.Filled.Warning, tr("Тест", "Тест"), tr("Пример окна угрозы", "Қауіп терезесінің үлгісі"), NS.threat) {
                Warnings.show(ctx, Warning(RiskLevel.HIGH, tr("Просят продиктовать код из SMS", "SMS-тегі кодты айтуды сұрайды"),
                    listOf(tr("Звонящий представился службой безопасности банка", "Қоңырау шалушы өзін банктің қауіпсіздік қызметі деп таныстырды"), tr("Просит назвать код из SMS", "SMS-тегі кодты айтуды сұрайды")),
                    listOf(tr("Положите трубку и позвоните в банк сами", "Тұтқаны қойып, банкке өзіңіз хабарласыңыз")), source = "test", number = "+7 700 000 00 00", score = 92.0, isTest = true))
            }
        }

        SectionTitle(tr("Последние угрозы", "Соңғы қауіптер"), if (alerts.isNotEmpty()) tr("Все", "Барлығы") else null) { go("history") }
        if (alerts.isEmpty()) EmptyState(tr("Угроз не обнаружено", "Қауіп анықталған жоқ"), tr("Saqtan предупредит, если звонок или сообщение покажутся подозрительными", "Қоңырау не хабарлама күдікті болса, Saqtan ескертеді"))
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
            Text(tr("Угрозы за 7 дней", "7 күндегі қауіптер"), style = MaterialTheme.typography.titleMedium, color = NS.text, modifier = Modifier.weight(1f))
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
        Manifest.permission.RECEIVE_SMS,   // the microphone is asked separately, only when the user turns on speech analysis
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

    Page(tr("Защита звонков", "Қоңырауларды қорғау"), tr("Модули, голосовой и видео-анализ", "Модульдер, дауыс және бейне талдауы")) {
        // ---- overview
        GlassCard(accent = if (on == items.size) NS.cyan else NS.warn) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RingGauge(on / items.size.toFloat(), if (on == items.size) NS.cyan else NS.warn, 92.dp) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$on/${items.size}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = NS.text)
                        Text(tr("модулей", "модуль"), fontSize = 11.sp, color = NS.muted)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (on == items.size) tr("Все модули активны", "Барлық модульдер белсенді") else tr("Включите все модули", "Барлық модульдерді қосыңыз"), style = MaterialTheme.typography.titleMedium, color = NS.text)
                    Text(tr("Данные анализируются на телефоне и никуда не отправляются", "Деректер телефонда талданады және ешқайда жіберілмейді"), style = MaterialTheme.typography.bodySmall, color = NS.muted)
                }
            }
            if (on < items.size) PrimaryButton(tr("Разрешить всё", "Бәріне рұқсат беру"), Modifier.fillMaxWidth()) { runtimeAll() }
        }

        SectionTitle(tr("Модули", "Модульдер"))
        GlassCard(padding = 10.dp) {
            items.forEachIndexed { i, item ->
                ListRow(item.icon, item.title, item.why, tint = if (item.ok) NS.cyan else NS.muted,
                    onClick = if (item.ok) null else ({ fix(i) })) {
                    if (item.ok) Icon(Icons.Filled.CheckCircle, tr("включено", "қосулы"), tint = NS.safe, modifier = Modifier.size(22.dp))
                    else Text(tr("Включить", "Қосу"), color = NS.cyan, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // ---- voice AI
        SectionTitle(tr("Голосовой ИИ", "Дауыстық ЖИ"))
        GlassCard(accent = if (ui.running) (ui.risk?.level?.let { levelColor(it) } ?: NS.cyan) else null) {
            if (ui.running) {
                val r = ui.risk
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RingGauge(((r?.score ?: 0.0) / 100).toFloat(), r?.level?.let { levelColor(it) } ?: NS.cyan, 84.dp, 8.dp) {
                        Text("${(r?.score ?: 0.0).toInt()}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = NS.text)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (ui.inCall) tr("Слушаю звонок", "Қоңырауды тыңдап жатырмын") else tr("Проверка микрофона", "Микрофонды тексеру"), style = MaterialTheme.typography.titleMedium, color = NS.text)
                        Text(if (ui.speakerOn) tr("Громкая связь включена", "Дауыс зорайтқыш қосулы") else tr("Включите громкую связь", "Дауыс зорайтқышты қосыңыз"), style = MaterialTheme.typography.bodySmall,
                            color = if (ui.speakerOn) NS.safe else NS.warn)
                        LevelBars(ui.micLevel)
                    }
                }
                if (ui.transcript.isNotBlank())
                    Text("«…${ui.transcript.takeLast(180)}»", color = NS.text2, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp)
                r?.tactics?.takeIf { it.isNotEmpty() }?.let { t ->
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        t.forEach { Pill(I18n.core(it.title), levelColor(r.level)) }
                    }
                }
                GhostButton(tr("Остановить", "Тоқтату"), Modifier.fillMaxWidth()) { ListenService.stop(ctx, tr("Остановлено", "Тоқтатылды")) }
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
                        PrimaryButton(if (ctx.app.prefs.callState == "OFFHOOK") tr("Слушать звонок", "Қоңырауды тыңдау") else tr("Проверить микрофон", "Микрофонды тексеру"), Modifier.fillMaxWidth()) { onListen() }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("Модель речи «${m.title}» установлена · офлайн", "«${m.title}» сөйлеу моделі орнатылды · офлайн"), color = NS.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(tr("Удалить", "Жою"), color = NS.text2, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { SpeechModels.delete(ctx, lang); refresh++ }.padding(6.dp))
                        }
                    }
                    is SpeechModels.Status.Downloading -> {
                        Text(tr("Загрузка модели… ${s.percent}%", "Модель жүктелуде… ${s.percent}%"), color = NS.text2, style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = NS.cyan, trackColor = NS.surface3)
                    }
                    is SpeechModels.Status.Failed -> {
                        Text(tr("Не удалось скачать: ${s.error}", "Жүктеп алу мүмкін болмады: ${s.error}"), color = NS.threat, style = MaterialTheme.typography.bodySmall)
                        PrimaryButton(tr("Повторить · ${m.sizeMb} МБ", "Қайталау · ${m.sizeMb} МБ"), Modifier.fillMaxWidth()) { SpeechModels.download(ctx, scope, lang) }
                    }
                    SpeechModels.Status.Missing -> PrimaryButton(tr("Скачать модель речи · ${m.sizeMb} МБ", "Сөйлеу моделін жүктеу · ${m.sizeMb} МБ"), Modifier.fillMaxWidth()) { SpeechModels.download(ctx, scope, lang) }
                }
            }
            ui.message?.let { Text(it, color = if (ui.silenceDetected) NS.threat else NS.text2, style = MaterialTheme.typography.bodySmall) }
            Text(tr("Звук звонка доступен только через громкую связь — ограничение Android", "Қоңырау дыбысы тек дауыс зорайтқыш арқылы қолжетімді — Android шектеуі"), color = NS.muted, style = MaterialTheme.typography.bodySmall)
        }

        // ---- video AI
        SectionTitle(tr("Видео-анализ", "Бейне талдау"))
        GlassCard(accent = vui.result?.level?.let { levelColor(it) }) {
            if (vui.running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RingGauge(vui.seconds / 30f, NS.violet, 72.dp, 7.dp) { Text(tr("${vui.seconds}с", "${vui.seconds} с"), color = NS.text, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(tr("Анализ лица на экране", "Экрандағы бетті талдау"), style = MaterialTheme.typography.titleMedium, color = NS.text)
                        Text(tr("Кадров с лицом: ${vui.faceFrames} из ${vui.frames}", "Беті бар кадрлар: ${vui.frames} ішінен ${vui.faceFrames}"), style = MaterialTheme.typography.bodySmall, color = NS.muted)
                    }
                }
                vui.prompt?.let { Text(it, color = NS.violet, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold) }
            } else {
                ListRow(Icons.Filled.Videocam, tr("Проверить видеозвонок", "Бейнеқоңырауды тексеру"), tr("30 секунд · лицо и два задания собеседнику", "30 секунд · бет және әңгімелесушіге екі тапсырма"), tint = NS.violet, onClick = onVideo) {
                    Icon(Icons.Filled.ChevronRight, null, tint = NS.muted)
                }
            }
            vui.result?.let { v ->
                Verdict(v.level, I18n.core(v.title), tr("Морганий ${v.blinks} · лицо видно ${(v.facePresentRatio * 100).toInt()}% · ${v.seconds.toInt()} с", "Жыпылықтау: ${v.blinks} · бет көрінді: ${(v.facePresentRatio * 100).toInt()}% · ${v.seconds.toInt()} с"))
                v.challenges.forEach { c ->
                    Bullet((if (c.passed) tr("Выполнено: ", "Орындалды: ") else tr("Не выполнено: ", "Орындалмады: ")) + I18n.core(c.challenge.kind.done) + " — " + I18n.core(c.detail),
                        if (c.passed) NS.safe else NS.warn)
                }
                Text(tr("Задания мешают обмануть фото или заранее записанным видео. Дипфейк, который повторяет просьбы, они не распознают.", "Тапсырмалар фотосуретпен немесе алдын ала жазылған бейнемен алдауға кедергі жасайды. Өтініштерді қайталайтын дипфейкті олар анықтамайды."),
                    style = MaterialTheme.typography.bodySmall, color = NS.muted)
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
                Text(I18n.core(t.title), color = NS.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                t.evidence.firstOrNull()?.let { e -> Text("«${e.context}»", color = NS.text2, style = MaterialTheme.typography.bodySmall) }
                Text(I18n.core(t.advice), color = NS.cyan, style = MaterialTheme.typography.bodySmall)
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
    Page(tr("Проверка текста", "Мәтінді тексеру"), tr("SMS, сообщение или слова звонящего", "SMS, хабарлама немесе қоңырау шалушының сөздері"), onBack) {
        OutlinedTextField(value = text, onValueChange = { text = it.take(ScamAnalyzer.MAX_TEXT) }, modifier = Modifier.fillMaxWidth().height(170.dp),
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NS.cyan, unfocusedBorderColor = NS.border,
                focusedContainerColor = NS.surface, unfocusedContainerColor = NS.surface),
            placeholder = { Text(tr("«Это служба безопасности банка, продиктуйте код из СМС…»", "«Бұл банктің қауіпсіздік қызметі, SMS-тегі кодты айтыңыз…»"), color = NS.muted) })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(tr("Проверить", "Тексеру"), Modifier.weight(1f), enabled = text.isNotBlank()) {
                val r = ScamAnalyzer.analyze(text); result = r
                if (r.level != RiskLevel.LOW) ctx.app.alerts.add("manual_text", null, r.level, r.score, "Проверка текста: ${r.summary.lowercase()}",
                    r.tactics.map { it.title }, if (ctx.app.prefs.storeExcerpts) text.take(600) else null)
            }
            GhostButton(tr("Пример", "Үлгі")) { text = tr("Здравствуйте, вас беспокоит служба безопасности банка. С вашей карты пытаются списать деньги. Срочно продиктуйте код из СМС и никому не говорите об этом звонке.", "Сәлеметсіз бе, банктің қауіпсіздік қызметі. Кодты айтыңыз, жедел! Ешкімге айтпаңыз.") }
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
                        Text(I18n.core(r.summary), style = MaterialTheme.typography.titleMedium, color = NS.text)
                        if (r.words < 8) Text(tr("Текст короткий — оценка неточная", "Мәтін қысқа — бағалау дәл емес"), style = MaterialTheme.typography.bodySmall, color = NS.muted)
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

    Page(tr("Угрозы", "Қауіптер"), tr("История хранится только на телефоне", "Тарих тек телефонда сақталады"), actions = {
        if (list.isNotEmpty()) {
            IconButton(onClick = { shareReport(ctx, shown) }, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(NS.surface2)) {
                Icon(Icons.Filled.Share, tr("Поделиться отчётом", "Есептемені бөлісу"), tint = NS.text2)
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { confirm = true }, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(NS.surface2)) {
                Icon(Icons.Filled.DeleteOutline, tr("Очистить", "Тазалау"), tint = NS.text2)
            }
        }
    }) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill(tr("Все · ${list.size}", "Барлығы · ${list.size}"), filter == null, NS.cyan) { filter = null }
            FilterPill(tr("Высокий · ${list.count { it.level == RiskLevel.HIGH }}", "Жоғары · ${list.count { it.level == RiskLevel.HIGH }}"), filter == RiskLevel.HIGH, NS.threat) { filter = RiskLevel.HIGH }
            FilterPill(tr("Средний · ${list.count { it.level == RiskLevel.MEDIUM }}", "Орташа · ${list.count { it.level == RiskLevel.MEDIUM }}"), filter == RiskLevel.MEDIUM, NS.warn) { filter = RiskLevel.MEDIUM }
            FilterPill(tr("Низкий · ${list.count { it.level == RiskLevel.LOW }}", "Төмен · ${list.count { it.level == RiskLevel.LOW }}"), filter == RiskLevel.LOW, NS.safe) { filter = RiskLevel.LOW }
        }
        if (shown.isEmpty()) EmptyState(tr("Здесь пока пусто", "Әзірге бос"), tr("Подозрительные звонки и сообщения появятся в этом списке", "Күдікті қоңыраулар мен хабарламалар осы тізімде пайда болады"))
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
        confirmButton = { TextButton(onClick = { ctx.app.alerts.clear(); confirm = false }) { Text(tr("Удалить всё", "Барлығын жою"), color = NS.threat) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(tr("Отмена", "Болдырмау"), color = NS.text2) } },
        title = { Text(tr("Очистить историю?", "Тарихты тазалау керек пе?"), color = NS.text) }, text = { Text(tr("Все записи об угрозах будут удалены с телефона.", "Қауіптер туралы барлық жазба телефоннан жойылады."), color = NS.text2) })
}

/** Share a plain-text report (e.g. to a relative or the bank's fraud department). No transcripts, no SMS bodies. */
fun shareReport(ctx: Context, items: List<Alert>) {
    val f = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru"))
    val body = buildString {
        appendLine(tr("Saqtan — отчёт об угрозах (${items.size})", "Saqtan — қауіптер туралы есептеме (${items.size})"))
        appendLine()
        items.take(100).forEach { a ->
            appendLine("${f.format(Date(a.ts))} · ${levelShort(a.level)} · ${a.sourceRu}")
            appendLine("  ${I18n.core(a.title)}" + (a.number?.let { " · $it" } ?: ""))
            a.reasons.take(3).forEach { appendLine("  – " + I18n.core(it)) }
        }
    }
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, tr("Отчёт Saqtan", "Saqtan есептемесі")).putExtra(Intent.EXTRA_TEXT, body)
    ctx.startActivity(Intent.createChooser(send, tr("Поделиться отчётом", "Есептемені бөлісу")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
                Text(I18n.core(a.title), style = MaterialTheme.typography.titleMedium, color = NS.text)
                Text("${dayLabel(a.ts)}, ${timeFmt.format(Date(a.ts))} · ${a.sourceRu}", style = MaterialTheme.typography.bodySmall, color = NS.muted)
            }
        }
        a.number?.let { ListRow(Icons.Filled.Public, it, tr("Номер / отправитель", "Нөмір / жіберуші"), tint = NS.text2) }
        if (a.reasons.isNotEmpty()) {
            SectionTitle(tr("Почему это подозрительно", "Неліктен бұл күдікті"))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { a.reasons.forEach { Bullet(I18n.core(it), NS.text) } }
        }
        if (ai.neuroshield.app.data.CallRecordings.exists(ctx, a.audio)) RecordingPlayer(a)
        a.excerpt?.let {
            SectionTitle(tr("Фрагмент", "Үзінді"))
            Text("«$it»", color = NS.text2, style = MaterialTheme.typography.bodySmall)
        }
        val num = a.number?.takeIf { n -> n.any { it.isDigit() } }
        if (num != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(tr("Это мошенник", "Алаяқ нөмірі"), Modifier.weight(1f), color = NS.threat) { ctx.app.prefs.mark(num, scam = true); marked = tr("Номер отмечен как мошеннический", "Нөмір алаяқтық деп белгіленді") }
                GhostButton(tr("Не мошенник", "Сенімді нөмір"), Modifier.weight(1f)) { ctx.app.prefs.mark(num, scam = false); marked = tr("Номер отмечен как надёжный", "Нөмір сенімді деп белгіленді") }
            }
            marked?.let { Text(it, color = NS.safe, style = MaterialTheme.typography.bodySmall) }
        }
        GhostButton(tr("Удалить из истории", "Тарихтан жою"), Modifier.fillMaxWidth(), color = NS.text2) { ctx.app.alerts.delete(a.id); onClose() }
    }
}

/** Plays a call recording: decrypted into a temporary file only while it plays, deleted afterwards. */
@Composable
fun RecordingPlayer(a: Alert) {
    val ctx = LocalContext.current
    val id = a.audio ?: return
    var playing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var gone by remember { mutableStateOf(false) }
    val player = remember { arrayOfNulls<android.media.MediaPlayer>(1) }
    val temp = remember { arrayOfNulls<java.io.File>(1) }
    fun stop() {
        player[0]?.runCatching { stop(); release() }; player[0] = null
        temp[0]?.delete(); temp[0] = null; playing = false
    }
    DisposableEffect(id) { onDispose { stop() } }
    if (gone) return
    SectionTitle(tr("Запись разговора", "Әңгіме жазбасы"))
    GlassCard(padding = 10.dp) {
        val secs = ai.neuroshield.app.data.CallRecordings.seconds(ctx, id)
        ListRow(if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            if (playing) tr("Остановить", "Тоқтату") else tr("Прослушать запись", "Жазбаны тыңдау"),
            tr("${secs / 60} мин ${secs % 60} с · с микрофона телефона", "${secs / 60} мин ${secs % 60} с · телефон микрофонынан"), tint = NS.cyan,
            onClick = {
                if (playing) stop() else {
                    val f = ai.neuroshield.app.data.CallRecordings.toWav(ctx, id)
                    if (f == null) error = tr("Запись не удалось открыть", "Жазбаны ашу мүмкін болмады") else try {
                        temp[0] = f
                        player[0] = android.media.MediaPlayer().apply {
                            setDataSource(f.absolutePath); prepare(); setOnCompletionListener { stop() }; start()
                        }
                        playing = true; error = null
                    } catch (e: Exception) { stop(); error = tr("Запись не удалось воспроизвести", "Жазбаны ойнату мүмкін болмады") }
                }
            })
        ListRow(Icons.Filled.DeleteOutline, tr("Удалить запись разговора", "Әңгіме жазбасын жою"), tr("Отчёт о звонке останется", "Қоңырау туралы есептеме қалады"), tint = NS.threat,
            onClick = { stop(); ctx.app.alerts.deleteAudio(a.id, id); gone = true })
    }
    error?.let { Text(it, color = NS.threat, style = MaterialTheme.typography.bodySmall) }
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
    var autoAsk by remember { mutableStateOf(false) }
    var excerpts by remember { mutableStateOf(p.storeExcerpts) }
    var record by remember { mutableStateOf(p.recordCalls) }
    var recordAsk by remember { mutableStateOf(false) }
    val micAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var marks by remember { mutableIntStateOf(0) }
    var about by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }
    var wipeAsk by remember { mutableStateOf(false) }
    var trustedName by remember { mutableStateOf(p.trustedName) }
    var trustedNumber by remember { mutableStateOf(p.trustedNumber) }
    var bankNumber by remember { mutableStateOf(p.bankNumber) }
    var block by remember { mutableStateOf(p.blockMarked) }
    Page(tr("Настройки", "Баптаулар")) {
        SectionTitle(tr("Язык интерфейса", "Интерфейс тілі"))
        GlassCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ru" to "Русский", "kk" to "Қазақша").forEach { (code, name) ->
                    val sel = (code == "kk") == I18n.kk
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if (sel) NS.cyan.copy(alpha = 0.16f) else NS.surface3)
                        .border(1.dp, if (sel) NS.cyan.copy(alpha = 0.5f) else NS.border, RoundedCornerShape(12.dp))
                        .clickable {
                            if (!sel) {
                                p.uiLang = code; I18n.kk = code == "kk"
                                (ctx as? android.app.Activity)?.recreate()
                            }
                        }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Text(name, color = if (sel) NS.cyan else NS.text2, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(tr("Язык речи для анализа звонка выбирается отдельно, на вкладке «Защита».",
                    "Қоңырауды талдауға арналған сөйлеу тілі «Қорғаныс» бетінде бөлек таңдалады. Қазақша аударма алдын ала нұсқа: оны тіл иесі тексеруі керек."),
                style = MaterialTheme.typography.bodySmall, color = NS.muted)
        }
        SectionTitle(tr("Звонки", "Қоңыраулар"))
        GlassCard(padding = 10.dp) {
            ListRow(Icons.Filled.Public, tr("Код страны", "Ел коды"), tr("Звонки с других кодов считаются международными", "Басқа кодтардан келген қоңыраулар халықаралық деп саналады")) {
                OutlinedTextField(value = code, onValueChange = { v -> code = v.filter { it.isDigit() || it == '+' }.take(5); if (code.startsWith("+")) p.homeCallingCode = code },
                    singleLine = true, modifier = Modifier.width(86.dp), shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NS.cyan, unfocusedBorderColor = NS.border))
            }
            ListRow(Icons.Filled.Call, tr("Любой незнакомый номер", "Кез келген бейтаныс нөмір"), tr("Короткий совет при каждом таком звонке", "Әр осындай қоңырауда қысқа кеңес"),
                onClick = { warnUnknown = !warnUnknown; p.warnUnknownCallers = warnUnknown }) { NeoSwitch(warnUnknown) { warnUnknown = it; p.warnUnknownCallers = it } }
            ListRow(Icons.Filled.Mic, tr("Кнопка анализа речи", "Сөйлеуді талдау түймесі"), tr("Во время звонка с незнакомцем", "Бейтаныс адаммен сөйлескенде"),
                onClick = { offer = !offer; p.offerListening = offer }) { NeoSwitch(offer) { offer = it; p.offerListening = it } }
            ListRow(Icons.Filled.GraphicEq, tr("Автоанализ речи", "Сөйлеуді автоталдау"), tr("Сам включается после ответа незнакомцу: нужны модель речи и громкая связь", "Бейтанысқа жауап бергеннен кейін өзі қосылады: сөйлеу моделі мен дауыс зорайтқыш қажет"),
                onClick = { if (auto) { auto = false; p.autoListen = false } else autoAsk = true }) {
                    NeoSwitch(auto) { on -> if (on) autoAsk = true else { auto = false; p.autoListen = false } } }
            if (autoAsk) AlertDialog(onDismissRequest = { autoAsk = false }, containerColor = NS.surface2,
                confirmButton = { TextButton(onClick = { auto = true; p.autoListen = true; autoAsk = false }) { Text(tr("Включить", "Қосу"), color = NS.cyan) } },
                dismissButton = { TextButton(onClick = { autoAsk = false }) { Text(tr("Отмена", "Болдырмау"), color = NS.text2) } },
                title = { Text(tr("Автоматический анализ речи", "Сөйлеуді автоматты талдау"), color = NS.text) },
                text = { Text(tr("После ответа на звонок с незнакомого номера Saqtan сам включит микрофон и будет распознавать речь на телефоне. Звук не записывается и никуда не отправляется. Пока микрофон работает, в шторке видно уведомление, его можно остановить в любой момент. Звонки с номеров из контактов не анализируются.",
                    "Бейтаныс нөмірден келген қоңырауға жауап бергеннен кейін Saqtan микрофонды өзі қосып, сөйлеуді телефонда таниды. Дыбыс жазылмайды және ешқайда жіберілмейді. Микрофон жұмыс істеп тұрғанда хабарландыру көрінеді, оны кез келген уақытта тоқтатуға болады. Контактілердегі нөмірлерден келген қоңыраулар талданбайды."), color = NS.text2) })
            ListRow(Icons.Filled.Block, tr("Блокировать отмеченных мошенников", "Белгіленген алаяқ нөмірлерді бұғаттау"), if (Perms.screeningRole(ctx)) tr("Звонки с отмеченных номеров сбрасываются", "Белгіленген нөмірлерден келген қоңыраулар қабылданбайды") else tr("Нужен модуль «Определитель номера»", "«Нөмір анықтағыш» модулі қажет"),
                tint = NS.threat, onClick = { block = !block; p.blockMarked = block }) { NeoSwitch(block) { block = it; p.blockMarked = it } }
        }

        SectionTitle(tr("Экстренная помощь", "Шұғыл көмек"))
        GlassCard(padding = 14.dp) {
            Text(tr("При высоком риске в окне угрозы появятся кнопки «Позвонить близкому» и «Позвонить в банк». Открывается набор номера — звонок не начинается сам.", "Қауіп жоғары болса, қауіп терезесінде «Жақын адамға қоңырау» және «Банкке қоңырау шалу» түймелері пайда болады. Нөмір теру ашылады — қоңырау өздігінен басталмайды."),
                color = NS.muted, style = MaterialTheme.typography.bodySmall)
            NeoField(trustedName, tr("Имя близкого (например, «Сын»)", "Жақын адамның аты (мысалы, «Ұлым»)"), KeyboardType.Text, Icons.Filled.FavoriteBorder) { trustedName = it.take(30); p.trustedName = trustedName }
            NeoField(trustedNumber, tr("Телефон близкого", "Жақын адамның телефоны"), KeyboardType.Phone, Icons.Filled.Call) { trustedNumber = it.filter { c -> c.isDigit() || c in "+ ()-" }.take(20); p.trustedNumber = trustedNumber }
            NeoField(bankNumber, tr("Номер вашего банка (с карты)", "Банкіңіздің нөмірі (картадан)"), KeyboardType.Phone, Icons.Filled.AccountBalance) { bankNumber = it.filter { c -> c.isDigit() || c in "+ ()-" }.take(20); p.bankNumber = bankNumber }
        }

        SectionTitle(tr("Конфиденциальность", "Құпиялылық"))
        GlassCard(padding = 10.dp) {
            ListRow(Icons.Filled.Lock, tr("Хранить фрагменты разговоров", "Әңгіме үзінділерін сақтау"), tr("По умолчанию — только причины угрозы", "Әдепкі бойынша — тек қауіп себептері"),
                onClick = { excerpts = !excerpts; p.storeExcerpts = excerpts }) { NeoSwitch(excerpts) { excerpts = it; p.storeExcerpts = it } }
            ListRow(Icons.Filled.Mic, tr("Записывать звонки", "Қоңырауларды жазу"),
                tr("Незнакомые номера, через микрофон на громкой связи; хранится ${ai.neuroshield.app.data.CallRecordings.RETENTION_DAYS} дней", "Бейтаныс нөмірлер, дауыс зорайтқышта микрофон арқылы; ${ai.neuroshield.app.data.CallRecordings.RETENTION_DAYS} күн сақталады"),
                onClick = { if (record) { record = false; p.recordCalls = false } else recordAsk = true }) {
                NeoSwitch(record) { on -> if (on) recordAsk = true else { record = false; p.recordCalls = false } } }
            if (recordAsk) AlertDialog(onDismissRequest = { recordAsk = false }, containerColor = NS.surface2,
                confirmButton = { TextButton(onClick = { record = true; p.recordCalls = true; recordAsk = false; if (!ai.neuroshield.app.Perms.mic(ctx)) micAsk.launch(Manifest.permission.RECORD_AUDIO) }) { Text(tr("Включить запись", "Жазуды қосу"), color = NS.cyan) } },
                dismissButton = { TextButton(onClick = { recordAsk = false }) { Text(tr("Отмена", "Болдырмау"), color = NS.text2) } },
                title = { Text(tr("Запись звонков", "Қоңырауларды жазу"), color = NS.text) },
                text = { Text(tr("После ответа на звонок с незнакомого номера Saqtan включит микрофон и будет записывать разговор, пока звонок не закончится. Android не даёт приложениям звук самого звонка, поэтому собеседника слышно только на громкой связи. Запись шифруется, хранится только на этом телефоне, не отправляется никуда и удаляется через ${ai.neuroshield.app.data.CallRecordings.RETENTION_DAYS} дней. Прослушать её можно в отчёте о звонке на вкладке «Угрозы». Пока идёт запись, в шторке видно уведомление. Предупреждайте собеседника о записи: правила записи разговоров зависят от закона.",
                    "Бейтаныс нөмірден келген қоңырауға жауап бергеннен кейін Saqtan микрофонды қосып, қоңырау аяқталғанша әңгімені жазады. Android қосымшаларға қоңыраудың өз дыбысын бермейді, сондықтан әңгімелесуші тек дауыс зорайтқышта естіледі. Жазба шифрланады, тек осы телефонда сақталады, ешқайда жіберілмейді және ${ai.neuroshield.app.data.CallRecordings.RETENTION_DAYS} күннен кейін жойылады. Оны «Қауіптер» бетіндегі қоңырау есептемесінен тыңдауға болады. Жазу жүріп жатқанда хабарландыру көрінеді. Әңгімелесушіге жазба туралы ескертіңіз: әңгімені жазу ережелері заңға байланысты."), color = NS.text2) })
            ListRow(Icons.Filled.DeleteOutline, tr("Очистить историю угроз", "Қауіптер тарихын тазалау"), if (cleared) tr("История удалена", "Тарих жойылды") else tr("Удалить все записи с телефона", "Барлық жазбаны телефоннан жою"), tint = NS.threat,
                onClick = { ctx.app.alerts.clear(); cleared = true })
            ListRow(Icons.Filled.DeleteOutline, tr("Удалить все данные", "Барлық деректерді жою"),
                tr("История, отметки номеров, настройки и ключ шифрования", "Тарих, нөмір белгілері, баптаулар және шифрлау кілті"), tint = NS.threat,
                onClick = { wipeAsk = true })
        }
        Text(tr("Записи хранятся только на телефоне и удаляются автоматически через ${ai.neuroshield.app.data.AlertStore.RETENTION_DAYS} дней. Приложение не отправляет данные на серверы, в нём нет рекламы и аналитики. Интернет нужен только для однократного скачивания модели речи.",
                "Жазбалар тек телефонда сақталады және ${ai.neuroshield.app.data.AlertStore.RETENTION_DAYS} күннен кейін автоматты түрде жойылады. Қосымша деректерді серверлерге жібермейді, онда жарнама мен аналитика жоқ. Интернет тек сөйлеу моделін бір рет жүктеу үшін қажет."),
            style = MaterialTheme.typography.bodySmall, color = NS.muted, modifier = Modifier.padding(horizontal = 4.dp))
        if (wipeAsk) AlertDialog(onDismissRequest = { wipeAsk = false }, containerColor = NS.surface2,
            confirmButton = { TextButton(onClick = {
                wipeAsk = false
                ai.neuroshield.app.data.DataWipe.all(ctx)
                I18n.kk = false
                (ctx as? android.app.Activity)?.recreate()
            }) { Text(tr("Удалить", "Жою"), color = NS.threat) } },
            dismissButton = { TextButton(onClick = { wipeAsk = false }) { Text(tr("Отмена", "Болдырмау"), color = NS.text2) } },
            title = { Text(tr("Удалить все данные?", "Барлық деректерді жою керек пе?"), color = NS.text) },
            text = { Text(tr("История угроз, отмеченные номера, настройки и ключ шифрования будут удалены с телефона. Разрешения Android отзываются в настройках телефона.",
                "Қауіптер тарихы, белгіленген нөмірлер, баптаулар және шифрлау кілті телефоннан жойылады. Android рұқсаттары телефон баптауларында қайтарылады."), color = NS.text2) })

        SectionTitle(tr("Отмеченные номера", "Белгіленген нөмірлер"))
        GlassCard(padding = 10.dp) {
            val scam = remember(marks) { p.scamNumbers() }; val safe = remember(marks) { p.safeNumbers() }
            if (scam.isEmpty() && safe.isEmpty()) Text(tr("Нет отмеченных номеров. Отметить можно в карточке угрозы.", "Белгіленген нөмір жоқ. Қауіп карточкасында белгілеуге болады."), color = NS.muted,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
            (scam.map { it to true } + safe.map { it to false }).forEach { (n, isScam) ->
                ListRow(if (isScam) Icons.Filled.Warning else Icons.Filled.GppGood, n, if (isScam) tr("Мошенник", "Алаяқ") else tr("Надёжный", "Сенімді"),
                    tint = if (isScam) NS.threat else NS.safe) {
                    Text(tr("Убрать", "Алып тастау"), color = NS.text2, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { p.unmark(n); marks++ }.padding(8.dp))
                }
            }
        }

        SectionTitle(tr("О защите", "Қорғаныс туралы"))
        GlassCard(padding = 14.dp) {
            Row(Modifier.fillMaxWidth().clickable { about = !about }, verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.Info, NS.cyan, 38.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("Как работает Saqtan", "Saqtan қалай жұмыс істейді"), color = NS.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(tr("Возможности и честные ограничения", "Мүмкіндіктер және шынайы шектеулер"), color = NS.muted, style = MaterialTheme.typography.bodySmall)
                }
                Icon(if (about) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = NS.muted)
            }
            AnimatedVisibility(about, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Bullet(tr("До ответа оценивает номер: нет в контактах, скрытый, международный, частые звонки, ваши отметки.", "Жауап бергенге дейін нөмірді бағалайды: контактілерде жоқ, жасырын, халықаралық, жиі қоңыраулар, сіздің белгілеріңіз."))
                    Bullet(tr("Код из SMS во время звонка с незнакомцем — сразу тревога.", "Бейтаныс адаммен сөйлесіп тұрғанда SMS-код келсе — бірден дабыл."))
                    Bullet(tr("Мессенджеры: по уведомлению видит звонки и проверяет сообщения.", "Мессенджерлер: хабарландыру арқылы қоңырауларды көреді және хабарламаларды тексереді."))
                    Bullet(tr("Речь: офлайн-распознавание Vosk, только через громкую связь (ограничение Android).", "Сөйлеу: Vosk офлайн тануы, тек дауыс зорайтқыш арқылы (Android шектеуі)."))
                    Bullet(tr("Видео: 30 секунд анализа лица на экране (ML Kit) и два случайных задания собеседнику — против фото и заранее записанного видео. Дипфейки не распознаются.", "Бейне: экрандағы бетті 30 секунд талдау (ML Kit) және әңгімелесушіге екі кездейсоқ тапсырма — фотоға және алдын ала жазылған бейнеге қарсы. Дипфейктер анықталмайды."))
                    Bullet(tr("Не распознаёт дипфейки и клонированный голос; по голосу человека мошенником не называет.", "Дипфейктер мен клондалған дауысты танымайды; адамды дауысы бойынша алаяқ деп атамайды."))
                    Text(tr("Исследовательский прототип · не заменяет банк", "Зерттеу прототипі · банкті алмастырмайды"), color = NS.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        GlassCard(padding = 10.dp) {
            ListRow(Icons.Filled.Replay, tr("Показать знакомство снова", "Таныстыруды қайта көрсету"), tr("Короткий рассказ о возможностях", "Мүмкіндіктер туралы қысқаша"), onClick = onReplayOnboarding)
        }
    }
}
