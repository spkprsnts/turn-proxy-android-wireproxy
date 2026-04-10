package com.freeturn.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class ProxyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.freeturn.app.wireproxy.START_PROXY" -> {
                ProxyServiceState.clearLogs()
                ProxyServiceState.setStartupResult(null)
                val serviceIntent = Intent(context, ProxyService::class.java)
                val wireproxyIntent = Intent(context, WireproxyService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                    context.startForegroundService(wireproxyIntent)
                } else {
                    context.startService(serviceIntent)
                    context.startService(wireproxyIntent)
                }
            }
            "com.freeturn.app.wireproxy.STOP_PROXY" -> {
                context.stopService(Intent(context, ProxyService::class.java))
                context.stopService(Intent(context, WireproxyService::class.java))
            }
        }
    }
}
