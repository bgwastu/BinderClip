package net.wastu.binderclip

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class SendClipboardTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return
        tile.state = Tile.STATE_ACTIVE
        tile.subtitle = "Sending…"
        tile.updateTile()

        val serviceIntent = Intent(this, BinderClipService::class.java).apply {
            action = BinderClipService.ACTION_SEND_CURRENT
        }
        startService(serviceIntent)

        android.os.Handler(mainLooper).postDelayed({
            updateTile()
        }, 1_000)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val isConnected = AppRuntime.state.value.peer?.connected == true ||
            AppRuntime.state.value.status.startsWith("Connected")
        tile.state = if (isConnected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.subtitle = if (isConnected) "Send clipboard" else "Not connected"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_binder_clip)
        tile.updateTile()
    }
}
