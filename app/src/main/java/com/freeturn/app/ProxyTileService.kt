package com.freeturn.app

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class ProxyTileService : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var statusJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        statusJob?.cancel()
        statusJob = ProxyServiceState.isRunning
            .onEach { isRunning ->
                updateTile(isRunning)
            }
            .launchIn(serviceScope)
    }

    override fun onStopListening() {
        super.onStopListening()
        statusJob?.cancel()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = ProxyServiceState.isRunning.value
        val action = if (isRunning) {
            "com.freeturn.app.wireproxy.STOP_PROXY"
        } else {
            "com.freeturn.app.wireproxy.START_PROXY"
        }
        
        val intent = Intent(this, ProxyReceiver::class.java).apply {
            this.action = action
        }
        sendBroadcast(intent)
    }

    private fun updateTile(isRunning: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isRunning) getString(R.string.tile_active) else getString(R.string.tile_inactive)
        }
        tile.updateTile()
    }
}
