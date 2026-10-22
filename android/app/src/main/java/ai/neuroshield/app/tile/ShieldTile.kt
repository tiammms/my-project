package ai.neuroshield.app.tile

import ai.neuroshield.app.tr
import ai.neuroshield.app.I18n

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import ai.neuroshield.app.MainActivity
import ai.neuroshield.app.listen.ListenState

/** Quick Settings tile: during a call, pull down the shade and tap «NeuroShield» to start the speech analysis. */
class ShieldTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            val running = ListenState.ui.value.running
            state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= 29) subtitle = if (running) tr("слушает звонок", "қоңырауды тыңдап жатыр") else tr("проверить звонок", "қоңырауды тексеру")
            updateTile()
        }
    }

    override fun onClick() {
        val i = MainActivity.internalIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_START_LISTEN, true)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 31, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }
}
