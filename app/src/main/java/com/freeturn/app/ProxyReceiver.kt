package com.freeturn.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.freeturn.app.data.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class ProxyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.freeturn.app.wireproxy.START_PROXY" -> {
                ProxyServiceState.clearLogs()
                ProxyServiceState.setStartupResult(null)

                val prefs = AppPreferences(context)
                val cfg = runBlocking { prefs.clientConfigFlow.first() }

                cfg.getValidationErrorResId()?.let { errorRes ->
                    ProxyServiceState.setStartupResult(StartupResult.Failed(context.getString(errorRes)))
                    return
                }

                val serviceIntent = Intent(context, ProxyService::class.java)
                context.startForegroundService(serviceIntent)

                if (cfg.wireproxyEnabled) {
                    val wireproxyIntent = Intent(context, WireproxyService::class.java)
                    context.startForegroundService(wireproxyIntent)
                }
            }
            "com.freeturn.app.wireproxy.STOP_PROXY" -> {
                context.stopService(Intent(context, ProxyService::class.java))
                context.stopService(Intent(context, WireproxyService::class.java))
            }
        }
    }
}
