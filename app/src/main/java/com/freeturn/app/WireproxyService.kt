package com.freeturn.app

import android.app.Service
import android.content.Intent
import android.os.IBinder

class WireproxyService : Service() {

    // Обязательный метод для любого сервиса
    override fun onBind(intent: Intent?): IBinder? {
        return null // Возвращаем null, если не планируем связываться с сервисом через bindService
    }

    // Обычно также переопределяют onStartCommand для запуска логики
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "WireProxyChannel"
    }
}