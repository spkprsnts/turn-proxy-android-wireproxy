package com.freeturn.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicReference

class WireproxyService : Service() {

    private val process = AtomicReference<Process?>()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Wireproxy", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wireproxy")
            .setContentText("WireGuard tunnel is active")
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            startWireproxy()
        }

        return START_STICKY
    }

    private suspend fun startWireproxy() {
        val executable = "${applicationInfo.nativeLibraryDir}/libwireproxy.so"
        val configFile = File(filesDir, "wg.conf")

        if (!configFile.exists()) {
            ProxyServiceState.addLog("Wireproxy: wg.conf not found")
            stopSelf()
            return
        }

        val cmdArgs = mutableListOf<String>()
        
        // wireproxy is likely a PIE binary. On some Android versions/devices, 
        // we might need to use the linker if it's in a location that doesn't allow direct execution,
        // but since it's in nativeLibraryDir, it should be fine.
        cmdArgs.add(executable)
        cmdArgs.add("--config")
        cmdArgs.add(configFile.absolutePath)

        try {
            ProxyServiceState.addLog("Wireproxy starting: ${cmdArgs.joinToString(" ")}")
            val proc = withContext(Dispatchers.IO) {
                ProcessBuilder(cmdArgs)
                    .redirectErrorStream(true)
                    .start()
            }
            process.set(proc)

            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    ProxyServiceState.addLog("Wireproxy: $line")
                }
            }
            withContext(Dispatchers.IO) {
                proc.waitFor()
            }
        } catch (e: InterruptedIOException) {
            // pass
        } catch (e: Exception) {
            ProxyServiceState.addLog("Wireproxy Error: ${e.message}")
        } finally {
            ProxyServiceState.addLog("Wireproxy stopped")
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        process.get()?.destroyForcibly()
        serviceScope.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "WireProxyChannel"
    }
}
