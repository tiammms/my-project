package ai.neuroshield.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import ai.neuroshield.app.listen.ListenService
import ai.neuroshield.app.listen.ListenState
import ai.neuroshield.app.listen.SpeechModels
import ai.neuroshield.app.video.VideoCheckService
import ai.neuroshield.app.video.VideoState
import ai.neuroshield.app.ui.FaceScreen
import ai.neuroshield.app.ui.HistoryScreen
import ai.neuroshield.app.ui.HomeScreen
import ai.neuroshield.app.ui.NS
import ai.neuroshield.app.ui.NeuroTheme
import ai.neuroshield.app.ui.ProtectionScreen
import ai.neuroshield.app.ui.SettingsScreen
import ai.neuroshield.app.ui.TextCheckScreen

class MainActivity : ComponentActivity() {
    private val route = mutableStateOf("home")
    private val openAlert = mutableStateOf<Long?>(null)
    private val capture = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) {
            VideoCheckService.start(this, r.resultCode, data)
            moveTaskToBack(true)       // back to the video call; the result pops up on top of it
        } else VideoState.ui.value = VideoState.ui.value.copy(running = false, message = "Запись экрана не разрешена — проверка видео отменена")
    }

    fun startVideoCheck() {
        val mpm = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        capture.launch(mpm.createScreenCaptureIntent())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        SpeechModels.refresh(this)
        handle(intent)
        setContent { NeuroTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        i ?: return
        i.getStringExtra(EXTRA_ROUTE)?.let { route.value = it }
        i.getLongExtra(EXTRA_ALERT_ID, -1L).takeIf { it > 0 }?.let { openAlert.value = it; route.value = "history" }
        i.removeExtra(EXTRA_ALERT_ID)
        if (i.getBooleanExtra(EXTRA_START_LISTEN, false)) {
            route.value = "protection"
            startListening()
            if (i.getBooleanExtra(EXTRA_AUTO, false) && ListenState.ui.value.message?.startsWith("Скачайте") != true) moveTaskToBack(true)
        }
        if (i.getBooleanExtra(EXTRA_START_VIDEO, false)) {
            ai.neuroshield.app.alert.Warnings.hide()       // our own warning must not cover the caller's face
            route.value = "protection"; startVideoCheck()
        }
        i.removeExtra(EXTRA_START_LISTEN); i.removeExtra(EXTRA_ROUTE); i.removeExtra(EXTRA_START_VIDEO); i.removeExtra(EXTRA_AUTO)
    }

    /** Started from the activity (app in the foreground), as Android requires for a microphone service. */
    fun startListening() {
        val lang = app.prefs.speechLang
        when {
            !Perms.mic(this) -> ListenState.ui.value = ListenState.ui.value.copy(message = "Разрешите доступ к микрофону в списке ниже")
            SpeechModels.modelDir(this, lang) == null -> ListenState.ui.value = ListenState.ui.value.copy(message = "Скачайте модель распознавания речи ниже (один раз)")
            else -> ListenService.start(this)
        }
    }

    @Composable
    private fun Root() {
        val r by route
        val tabs = listOf(
            Triple("home", "Обзор", Icons.Outlined.GridView), Triple("protection", "Защита", Icons.Outlined.Shield),
            Triple("history", "Угрозы", Icons.Outlined.Radar), Triple("settings", "Настройки", Icons.Outlined.Tune))
        val tab = when (r) { "text", "face" -> "home"; else -> r }
        Scaffold(containerColor = NS.bg, bottomBar = { BottomBar(tabs, tab) { route.value = it } }) { pad ->
            Box(Modifier.fillMaxSize().padding(pad).background(Brush.verticalGradient(listOf(Color(0xFF0B0E13), NS.bg)))) {
                AnimatedContent(targetState = r, label = "screens", transitionSpec = {
                    (fadeIn(tween(260)) + slideInVertically(tween(320)) { it / 20 } + scaleIn(tween(320), initialScale = 0.985f)) togetherWith fadeOut(tween(140))
                }) { screen ->
                    when (screen) {
                        "protection" -> ProtectionScreen(onListen = { startListening() }, onVideo = { startVideoCheck() })
                        "history" -> HistoryScreen(openId = openAlert.value, onOpened = { openAlert.value = null })
                        "settings" -> SettingsScreen()
                        "text" -> TextCheckScreen(onBack = { route.value = "home" })
                        "face" -> FaceScreen(onBack = { route.value = "home" })
                        else -> HomeScreen(
                            go = { if (it == "video") { route.value = "protection"; startVideoCheck() } else route.value = it },
                            openAlert = { id -> openAlert.value = id; route.value = "history" })
                    }
                }
            }
        }
    }

    @Composable
    private fun BottomBar(tabs: List<Triple<String, String, androidx.compose.ui.graphics.vector.ImageVector>>, selected: String, onSelect: (String) -> Unit) {
        Row(Modifier.fillMaxWidth().background(NS.bg).navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(24.dp)).background(NS.surface).border(1.dp, NS.border, RoundedCornerShape(24.dp)).padding(6.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            tabs.forEach { (id, label, icon) ->
                val on = selected == id
                Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                    .background(if (on) NS.cyan.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onSelect(id) }
                    .padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icon, contentDescription = label, tint = if (on) NS.cyan else NS.muted, modifier = Modifier.size(22.dp))
                    Text(label, fontSize = 11.sp, maxLines = 1, color = if (on) NS.cyan else NS.muted,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                    Box(Modifier.padding(top = 3.dp).width(if (on) 16.dp else 0.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(NS.cyan))
                }
            }
        }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
        const val EXTRA_START_LISTEN = "start_listen"
        const val EXTRA_START_VIDEO = "start_video"
        const val EXTRA_AUTO = "auto"
        const val EXTRA_ALERT_ID = "alert_id"
    }
}
