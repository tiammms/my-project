package ai.neuroshield.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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

    override fun onCreate(savedInstanceState: Bundle?) {
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
        if (i.getBooleanExtra(EXTRA_START_LISTEN, false)) {
            route.value = "protection"
            startListening()
        }
        i.removeExtra(EXTRA_START_LISTEN); i.removeExtra(EXTRA_ROUTE)
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
        val tabs = listOf(Triple("home", "Главная", Icons.Filled.Home), Triple("protection", "Защита", Icons.Filled.Shield),
            Triple("history", "История", Icons.Filled.History), Triple("settings", "Настройки", Icons.Filled.Settings))
        val tab = when (r) { "text", "face" -> "home"; else -> r }
        Scaffold(
            containerColor = NS.bg,
            bottomBar = {
                NavigationBar(containerColor = NS.surface) {
                    tabs.forEach { (id, label, icon) ->
                        NavigationBarItem(selected = tab == id, onClick = { route.value = id }, icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label, fontSize = 11.sp, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = NS.teal, selectedTextColor = NS.teal, indicatorColor = NS.surface2))
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad)) {
                when (r) {
                    "protection" -> ProtectionScreen(onListen = { startListening() })
                    "history" -> HistoryScreen()
                    "settings" -> SettingsScreen()
                    "text" -> TextCheckScreen(onBack = { route.value = "home" })
                    "face" -> FaceScreen(onBack = { route.value = "home" })
                    else -> HomeScreen(go = { route.value = it })
                }
            }
        }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
        const val EXTRA_START_LISTEN = "start_listen"
    }
}
