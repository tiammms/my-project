package ai.neuroshield.app.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import java.text.SimpleDateFormat
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
fun Page(title: String, sub: String? = null, onBack: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Назад", tint = NS.text) }
            Column {
                Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = NS.text)
                if (sub != null) Text(sub, fontSize = 13.sp, color = NS.muted)
            }
        }
        content()
        Spacer(Modifier.height(24.dp))
    }
}

data class ProtectionItem(val title: String, val why: String, val ok: Boolean)

fun protectionItems(ctx: Context): List<ProtectionItem> {
    val model = SpeechModels.modelDir(ctx, ctx.app.prefs.speechLang) != null
    return listOf(
        ProtectionItem("Проверка входящих до ответа", "Роль «Определитель номера и защита от спама»", Perms.screeningRole(ctx)),
        ProtectionItem("Состояние звонка", "Телефон и журнал звонков: знать, идёт ли разговор", Perms.phone(ctx)),
        ProtectionItem("Контакты", "Не тревожить при звонках знакомых", Perms.contacts(ctx)),
        ProtectionItem("Коды из SMS", "Предупредить, если код пришёл во время звонка", Perms.sms(ctx)),
        ProtectionItem("Окно поверх экрана звонка", "Показать «⚠️ ВНИМАНИЕ!» прямо во время звонка", Perms.overlay(ctx)),
        ProtectionItem("Уведомления", "Предупреждения и кнопка «Слушать звонок»", Perms.notifications(ctx)),
        ProtectionItem("Анализ речи", "Микрофон + офлайн-модель распознавания", Perms.mic(ctx) && model),
        ProtectionItem("WhatsApp, Telegram, Viber", "Доступ к уведомлениям: видеть звонки и сообщения в мессенджерах", Perms.notificationAccess(ctx)),
    )
}

private val dateFmt = SimpleDateFormat("dd.MM HH:mm", Locale("ru"))

// =========================================================================== HOME
@Composable
fun HomeScreen(go: (String) -> Unit) {
    val ctx = LocalContext.current
    rememberResumeTick()
    val version by ctx.app.alerts.version.collectAsState()
    val items = protectionItems(ctx)
    val on = items.count { it.ok }
    val alerts = remember(version) { ctx.app.alerts.list(100) }
    val today = alerts.count { System.currentTimeMillis() - it.ts < 86_400_000 }
    Page("NeuroShield AI", "Защита от телефонных мошенников") {
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Shield, null, tint = if (on == items.size) NS.teal else NS.medium, modifier = Modifier.size(44.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (on == items.size) "Защита включена полностью" else "Защита включена частично", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = NS.text)
                    Text("Работает $on из ${items.size} функций", color = NS.muted, fontSize = 13.sp)
                }
            }
            LinearProgressIndicator(progress = { on / items.size.toFloat() }, modifier = Modifier.fillMaxWidth(), color = NS.teal, trackColor = NS.surface2)
            if (on < items.size) Button(onClick = { go("protection") }) { Text("Настроить защиту") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box2(Modifier.weight(1f), "$today", "предупреждений за сутки")
            Box2(Modifier.weight(1f), "${alerts.count { it.level == RiskLevel.HIGH }}", "высокий риск всего")
        }
        SectionCard("Проверить самому") {
            ActionRow(Icons.Filled.Sms, "Проверить сообщение или текст разговора", "Вставьте SMS, сообщение из мессенджера или то, что говорил звонящий") { go("text") }
            HorizontalDivider(color = NS.border)
            ActionRow(Icons.Filled.Face, "Проверка лица (живой человек)", "Повороты головы и моргание, модель ML Kit на телефоне") { go("face") }
            HorizontalDivider(color = NS.border)
            ActionRow(Icons.Filled.Warning, "Показать пример предупреждения", "Тест окна поверх других приложений. В историю не попадает") {
                Warnings.show(ctx, Warning(RiskLevel.HIGH, "Пример: просят продиктовать код из SMS",
                    listOf("Звонящий представился службой безопасности банка", "Просит назвать код из SMS"),
                    listOf("Положите трубку и позвоните в банк сами"), source = "test", number = "+7 700 000 00 00", isTest = true))
            }
        }
        SectionCard("Последние предупреждения") {
            if (alerts.isEmpty()) Text("Пока ничего подозрительного.", color = NS.muted)
            alerts.take(3).forEach { AlertRow(it) { go("history") } }
        }
        SectionCard("Как работает защита", "Честно о возможностях Android") {
            Bullet("До ответа: оценивает номер (нет в контактах, скрытый, международный, звонит много раз, вы отмечали его).")
            Bullet("Во время звонка: если пришёл код из SMS, а вы говорите с незнакомым номером, сразу показывает большое предупреждение.")
            Bullet("WhatsApp, Telegram, Viber: по уведомлению видит звонок с незнакомого номера и оценивает его (иностранный номер, частые звонки, этот номер раньше писал подозрительное). Подозрительные сообщения тоже проверяются.")
            Bullet("Речь собеседника: Android запрещает приложениям записывать звук звонка. Работает только через громкую связь и микрофон, и не на всех телефонах — приложение сообщит, если звука нет.")
            Bullet("Распознавание речи и анализ работают на телефоне, без интернета. По звучанию голоса никого мошенником не называем — оцениваются слова.")
        }
    }
}

@Composable
private fun Box2(m: Modifier, value: String, label: String) {
    androidx.compose.material3.Card(m, colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = NS.surface)) {
        Column(Modifier.padding(14.dp)) {
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = NS.text)
            Text(label, fontSize = 12.sp, color = NS.muted)
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = NS.teal)
        Spacer(Modifier.width(12.dp))
        Column { Text(title, color = NS.text, fontWeight = FontWeight.Medium); Text(sub, color = NS.muted, fontSize = 12.sp) }
    }
}

@Composable
fun AlertRow(a: Alert, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(a.title, color = NS.text, fontSize = 14.sp, maxLines = 2)
            Text("${dateFmt.format(Date(a.ts))} · ${a.sourceRu}${a.number?.let { " · $it" } ?: ""}", color = NS.muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        LevelChip(a.level)
    }
}

// =========================================================================== PROTECTION
@Composable
fun ProtectionScreen(onListen: () -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    var refresh by remember { mutableIntStateOf(0) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    val items = remember(tick, refresh) { protectionItems(ctx) }
    val scope = rememberCoroutineScope()
    val models by SpeechModels.status.collectAsState()
    val ui by ListenState.ui.collectAsState()
    var lang by remember { mutableStateOf(ctx.app.prefs.speechLang) }

    fun request(vararg p: String) = perms.launch(p.toList().toTypedArray())
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

    Page("Защита звонков", "Что разрешено приложению и анализ речи во время звонка") {
        SectionCard("Разрешения", "Каждое разрешение включает одну функцию. Данные не покидают телефон.") {
            items.forEachIndexed { i, item ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Icon(if (item.ok) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null, tint = if (item.ok) NS.low else NS.medium)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = NS.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(item.why, color = NS.muted, fontSize = 12.sp)
                    }
                    if (!item.ok && i != 6) TextButton(onClick = { fix(i) }) { Text("Разрешить") }
                    if (!item.ok && i == 6 && !Perms.mic(ctx)) TextButton(onClick = { fix(6) }) { Text("Микрофон") }
                }
            }
            Button(onClick = {
                request(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS,
                    Manifest.permission.RECEIVE_SMS, Manifest.permission.RECORD_AUDIO,
                    *(if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()))
            }, modifier = Modifier.fillMaxWidth()) { Text("Разрешить всё сразу") }
        }

        SectionCard("Модель распознавания речи", "Vosk (Kaldi), работает без интернета. Скачивается один раз.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpeechModels.all.forEach { m ->
                    FilterChip(selected = lang == m.lang, onClick = { lang = m.lang; ctx.app.prefs.speechLang = m.lang; refresh++ }, label = { Text(m.title) })
                }
            }
            val m = SpeechModels.all.first { it.lang == lang }
            when (val s = models[lang] ?: SpeechModels.Status.Missing) {
                is SpeechModels.Status.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Модель «${m.title}» установлена", color = NS.low, modifier = Modifier.weight(1f))
                    TextButton(onClick = { SpeechModels.delete(ctx, lang); refresh++ }) { Text("Удалить") }
                }
                is SpeechModels.Status.Downloading -> { Text("Загрузка… ${s.percent}%", color = NS.text2); LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth()) }
                is SpeechModels.Status.Failed -> {
                    Text("Не удалось скачать: ${s.error}. Проверьте интернет.", color = NS.high, fontSize = 13.sp)
                    Button(onClick = { SpeechModels.download(ctx, scope, lang) }) { Text("Повторить (${m.sizeMb} МБ)") }
                }
                SpeechModels.Status.Missing -> Button(onClick = { SpeechModels.download(ctx, scope, lang) }) { Text("Скачать модель (${m.sizeMb} МБ)") }
            }
        }

        SectionCard("Анализ речи во время звонка", "Включите громкую связь — микрофон должен слышать собеседника") {
            if (ui.running) {
                Verdict(ui.risk?.level ?: RiskLevel.LOW, ui.risk?.summary ?: "Слушаю…",
                    (if (ui.inCall) "Идёт звонок" else "Звонка нет — проверка микрофона") + " · " + (if (ui.speakerOn) "громкая связь включена" else "громкая связь выключена"))
                LinearProgressIndicator(progress = { (ui.micLevel * 20).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth(), color = NS.teal, trackColor = NS.surface2)
                OutlinedButton(onClick = { ListenService.stop(ctx, "Остановлено") }, modifier = Modifier.fillMaxWidth()) { Text("Остановить") }
            } else {
                Button(onClick = onListen, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = NS.teal)) {
                    Text(if (ctx.app.prefs.callState == "OFFHOOK") "Слушать звонок" else "Проверить микрофон и распознавание")
                }
            }
            ui.message?.let { Text(it, color = if (ui.silenceDetected) NS.high else NS.text2, fontSize = 13.sp) }
            if (ui.transcript.isNotBlank()) {
                Text("Распознанный текст", color = NS.muted, fontSize = 12.sp)
                Text(ui.transcript, color = NS.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
            ui.risk?.let { RiskDetails(it) }
            Text("Во время звонка обычные приложения не получают звук звонка (ограничение Android с версии 10). Поэтому анализ возможен " +
                "только через громкую связь. Если телефон отдаёт приложению тишину, вы увидите это сообщение здесь.", color = NS.muted, fontSize = 12.sp)
        }
    }
}

@Composable
fun RiskDetails(r: TextRisk) {
    r.tactics.forEach { t ->
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(t.title, color = NS.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            t.evidence.take(2).forEach { e -> Text("«${e.context}»", color = NS.text2, fontSize = 13.sp) }
            Text("→ ${t.advice}", color = NS.teal, fontSize = 13.sp)
        }
    }
}

// =========================================================================== TEXT CHECK
@Composable
fun TextCheckScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<TextRisk?>(null) }
    Page("Проверка текста", "SMS, сообщение или то, что сказал звонящий", onBack) {
        OutlinedTextField(value = text, onValueChange = { text = it.take(ScamAnalyzer.MAX_TEXT) }, modifier = Modifier.fillMaxWidth().height(180.dp),
            placeholder = { Text("Например: «Это служба безопасности банка, продиктуйте код из СМС…»") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val r = ScamAnalyzer.analyze(text); result = r
                if (r.level != RiskLevel.LOW) ctx.app.alerts.add("manual_text", null, r.level, r.score, "Проверка текста: ${r.summary.lowercase()}",
                    r.tactics.map { it.title }, if (ctx.app.prefs.storeExcerpts) text.take(600) else null)
            }, enabled = text.isNotBlank()) { Text("Проверить") }
            OutlinedButton(onClick = { text = "Здравствуйте, вас беспокоит служба безопасности банка. С вашей карты пытаются списать деньги. Срочно продиктуйте код из СМС и никому не говорите об этом звонке." }) { Text("Пример") }
        }
        result?.let { r ->
            Verdict(r.level, r.summary, "Оценивается содержание текста, а не личность собеседника")
            RiskDetails(r)
            if (r.words < 8) Text("Текст очень короткий — оценка ненадёжна.", color = NS.muted, fontSize = 12.sp)
        }
    }
}

// =========================================================================== HISTORY
@Composable
fun HistoryScreen() {
    val ctx = LocalContext.current
    val version by ctx.app.alerts.version.collectAsState()
    val list = remember(version) { ctx.app.alerts.list() }
    var open by remember { mutableStateOf<Long?>(null) }
    var confirm by remember { mutableStateOf(false) }
    Page("История предупреждений", "Хранится только на телефоне") {
        if (list.isEmpty()) SectionCard { Text("Предупреждений пока нет.", color = NS.muted) }
        list.forEach { a ->
            SectionCard {
                AlertRow(a) { open = if (open == a.id) null else a.id }
                if (open == a.id) {
                    a.reasons.forEach { Bullet(it) }
                    a.excerpt?.let { Text("Фрагмент: «$it»", color = NS.muted, fontSize = 12.sp) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val num = a.number?.takeIf { n -> n.any { it.isDigit() } }
                        if (num != null) {
                            OutlinedButton(onClick = { ctx.app.prefs.mark(num, scam = true) }) { Text("Это мошенник") }
                            OutlinedButton(onClick = { ctx.app.prefs.mark(num, scam = false) }) { Text("Не мошенник") }
                        }
                        TextButton(onClick = { ctx.app.alerts.delete(a.id) }) { Text("Удалить") }
                    }
                }
            }
        }
        if (list.isNotEmpty()) OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Очистить историю") }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        confirmButton = { TextButton(onClick = { ctx.app.alerts.clear(); confirm = false }) { Text("Удалить всё") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } },
        title = { Text("Очистить историю?") }, text = { Text("Все предупреждения будут удалены с телефона.") })
}

// =========================================================================== SETTINGS
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val p = ctx.app.prefs
    var code by remember { mutableStateOf(p.homeCallingCode) }
    var warnUnknown by remember { mutableStateOf(p.warnUnknownCallers) }
    var offer by remember { mutableStateOf(p.offerListening) }
    var excerpts by remember { mutableStateOf(p.storeExcerpts) }
    var marks by remember { mutableIntStateOf(0) }
    Page("Настройки") {
        SectionCard("Звонки") {
            OutlinedTextField(value = code, onValueChange = { v -> code = v.filter { it.isDigit() || it == '+' }.take(5); if (code.startsWith("+")) p.homeCallingCode = code },
                label = { Text("Код вашей страны") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
            Toggle("Предупреждать о любом незнакомом номере", "Небольшое окно с советом при каждом звонке не из контактов", warnUnknown) { warnUnknown = it; p.warnUnknownCallers = it }
            Toggle("Предлагать анализ речи во время звонка", "Кнопка «Проверить звонок» при разговоре с незнакомым номером", offer) { offer = it; p.offerListening = it }
        }
        SectionCard("Конфиденциальность") {
            Toggle("Сохранять фрагменты разговоров в истории", "По умолчанию выключено: хранятся только причины предупреждения", excerpts) { excerpts = it; p.storeExcerpts = it }
            Text("Звук и SMS не сохраняются и никуда не отправляются. Интернет нужен только для загрузки модели речи.", color = NS.muted, fontSize = 12.sp)
            OutlinedButton(onClick = { ctx.app.alerts.clear() }) { Text("Удалить историю предупреждений") }
        }
        SectionCard("Отмеченные номера") {
            val scam = remember(marks) { p.scamNumbers() }; val safe = remember(marks) { p.safeNumbers() }
            if (scam.isEmpty() && safe.isEmpty()) Text("Нет. Отмечать номера можно в истории.", color = NS.muted)
            (scam.map { it to true } + safe.map { it to false }).forEach { (n, isScam) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(n, color = NS.text, modifier = Modifier.weight(1f))
                    Text(if (isScam) "мошенник" else "надёжный", color = if (isScam) NS.high else NS.low, fontSize = 12.sp)
                    TextButton(onClick = { p.unmark(n); marks++ }) { Text("Убрать") }
                }
            }
        }
        SectionCard("О приложении") {
            Text("NeuroShield AI · исследовательский прототип. Не заменяет банк и не гарантирует защиту.", color = NS.text2, fontSize = 13.sp)
            Text("ИИ: офлайн-распознавание речи Vosk, детекция лица Google ML Kit; анализ фраз — прозрачные правила (русский, казахский, английский). " +
                "Детектора синтетического (клонированного) голоса нет: проверенной открытой модели для телефона не существует.", color = NS.muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Toggle(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) { Text(title, color = NS.text, fontSize = 14.sp); Text(sub, color = NS.muted, fontSize = 12.sp) }
        Switch(checked = value, onCheckedChange = onChange)
    }
}
