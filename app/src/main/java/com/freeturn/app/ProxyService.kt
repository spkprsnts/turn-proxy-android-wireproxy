package com.freeturn.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.freeturn.app.data.AppPreferences
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InterruptedIOException

sealed class StartupResult {
    data object Success : StartupResult()
    data class Failed(val message: String) : StartupResult()
}

class ProxyService : Service() {

    companion object {
        const val MAX_RESTARTS = 8
        // Жёстко привязываемся к строке-объявлению капчи в бинарнике, чтобы
        // случайные localhost-URL в других логах не открывали диалог.
        private val CAPTCHA_URL_REGEX =
            Pattern.compile("""Open this URL in your browser:\s*(https?://\S+)""")
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var openAppIntent: PendingIntent? = null

    private val process = AtomicReference<Process?>(null)
    private val userStopped = AtomicBoolean(false)
    private val sessionKillScheduled = AtomicBoolean(false)

    private val handler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var networkInitialized = false
    private var restartCount = 0

    private lateinit var serviceScope: CoroutineScope

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val channel = NotificationChannel("ProxyChannel", "Proxy", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ProxyServiceState.isRunning.value) return START_STICKY

        openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(this, "ProxyChannel")
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.proxy_connecting))
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()
        startForeground(1, notification)

        ProxyServiceState.setRunning(true)
        userStopped.set(false)
        restartCount = 0

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VkTurn::BgLock")
        wakeLock?.acquire(TimeUnit.HOURS.toMillis(24))

        registerNetworkCallback()

        ProxyServiceState.addLog(getString(R.string.log_proxy_start))
        serviceScope.launch { startBinaryProcess() }

        return START_STICKY
    }

    private suspend fun startBinaryProcess() {
        if (userStopped.get()) return

        val cfg = AppPreferences(applicationContext).clientConfigFlow.first()

        val customBin = File(filesDir, "custom_vkturn")
        val useCustom = customBin.exists()
        val executable = if (useCustom) {
            ProxyServiceState.addLog(getString(R.string.log_custom_kernel))
            customBin.absolutePath
        } else {
            ProxyServiceState.addLog(getString(R.string.log_standard_kernel))
            "${applicationInfo.nativeLibraryDir}/libvkturn.so"
        }

        val cmdArgs = mutableListOf<String>()

        if (cfg.isRawMode) {
            val parts = cfg.rawCommand.trim().split("\\s+".toRegex())
            cmdArgs.add(executable)
            if (parts.isNotEmpty()) cmdArgs.addAll(parts.subList(0, parts.size))
        } else {
            cmdArgs.add(executable)
            cmdArgs.add("-peer"); cmdArgs.add(cfg.serverAddress)

            if (cfg.vkLink.contains("yandex")) {
                cmdArgs.add("-yandex-link")
                cmdArgs.add(cfg.vkLink)
                if (cfg.telemostDc) cmdArgs.add("-telemost-dc")
            } else {
                cmdArgs.add("-vk-link")
                cmdArgs.add(cfg.vkLink)
                if (cfg.manualCaptcha) cmdArgs.add("--manual-captcha")
            }
            cmdArgs.add("-listen"); cmdArgs.add(cfg.localPort)
            if (cfg.threads > 0) { cmdArgs.add("-n"); cmdArgs.add(cfg.threads.toString()) }
            if (cfg.vlessMode) cmdArgs.add("-vless")
            else if (cfg.useUdp) cmdArgs.add("-udp")
            if (cfg.noDtls) cmdArgs.add("-no-dtls")
            if (cfg.forceTurnPort443) { cmdArgs.add("-port"); cmdArgs.add("443") }
        }

        // Кастомное ядро лежит в filesDir, откуда SELinux (untrusted_app) запрещает execve.
        // Запускаем через системный линкер: /system/bin/linker* — ему execve разрешён,
        // а целевой ELF мапится как данные. Работает для PIE-бинарников
        // (Go-сборки android/arm64 PIE по умолчанию).
        if (useCustom) {
            val linker = if (Build.SUPPORTED_ABIS.firstOrNull()?.contains("64") == true) {
                "/system/bin/linker64"
            } else {
                "/system/bin/linker"
            }
            cmdArgs.add(0, linker)
        }

        var exitCode = -1
        val startedAt = System.currentTimeMillis()
        var startupEmitted = false
        var startupFailed = false
        var captchaActive = false
        var captchaSessionCounter = 0L
        try {
            ProxyServiceState.addLog(getString(R.string.log_command, cmdArgs.joinToString(" ")))

            val proc = withContext(Dispatchers.IO) {
                ProcessBuilder(cmdArgs)
                    .redirectErrorStream(true)
                    .start()
            }
            process.set(proc)

            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    ProxyServiceState.addLog(l)

                    if (!useCustom && (l.contains("Established") || l.contains("listening on")) && !l.contains("[Wireproxy]")) {
                        ProxyServiceState.setWorking(true)
                    }

                    // Старт новой капча-сессии: бинарник логирует это перед открытием
                    // локального HTTP-сервера капчи. Сам URL прилетает следующей строкой.
                    if (l.contains("Triggering manual captcha fallback")) {
                        captchaActive = true
                    }

                    // Детекция URL ручной капчи. Каждый раз выдаём новый sessionId,
                    // чтобы диалог пересоздавал WebView, даже если URL не поменялся
                    // (бинарник всегда использует http://localhost:8765).
                    val captchaMatcher = CAPTCHA_URL_REGEX.matcher(l)
                    if (captchaMatcher.find()) {
                        val url = captchaMatcher.group(1)!!
                        captchaSessionCounter += 1
                        ProxyServiceState.setCaptchaSession(
                            CaptchaSession(url, captchaSessionCounter)
                        )
                        captchaActive = true
                    }

                    // Капча-сессия закончилась: бинарник либо завершил auth-чейн
                    // (Failed/Success), либо сама капча провалилась (timeout). Закрываем
                    // диалог — следующая капча-сессия откроет его заново через новый sessionId.
                    if (captchaActive && (
                            l.contains("[VK Auth] Failed") ||
                            l.contains("[VK Auth] Success") ||
                            (l.contains("[Captcha]") && l.contains("failed"))
                        )) {
                        ProxyServiceState.setCaptchaSession(null)
                        captchaActive = false
                    }

                    if (!startupEmitted) {
                        val lower = l.lowercase()
                        if (lower.contains("panic") || lower.contains("fatal") ||
                            lower.contains("rate limit")) {
                            ProxyServiceState.setStartupResult(StartupResult.Failed(l))
                            updateNotification(getString(R.string.notification_title), getString(R.string.error_connecting))
                            startupFailed = true
                        } else {
                            ProxyServiceState.setStartupResult(StartupResult.Success)
                            updateNotification(getString(R.string.notification_title), getString(R.string.proxy_active))
                        }
                        startupEmitted = true
                    }

                    // compareAndSet гарантирует единственный postDelayed даже при параллельных quota-ошибках
                    if (isQuotaError(l) && sessionKillScheduled.compareAndSet(false, true)) {
                        ProxyServiceState.addLog(getString(R.string.log_quota_error))
                        handler.postDelayed({
                            sessionKillScheduled.set(false)
                            if (!userStopped.get()) {
                                restartCount = 0
                                process.get()?.destroyForcibly()
                            }
                        }, 2_000)
                    }
                }
            }

            exitCode = if (withContext(Dispatchers.IO) {
                    proc.waitFor(5, TimeUnit.MINUTES)
                }) proc.exitValue() else -1
            ProxyServiceState.addLog(getString(R.string.log_process_stopped, exitCode))
            if (!startupEmitted) {
                ProxyServiceState.setStartupResult(StartupResult.Failed(
                    getString(R.string.error_process_no_output, exitCode)))
            }

        } catch (e: InterruptedIOException) {
            // pass
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("error=13") || msg.contains("Permission denied")) {
                ProxyServiceState.addLog(getString(R.string.error_kernel_permission_denied))
                ProxyServiceState.setStartupResult(StartupResult.Failed(msg))
                startupFailed = true
            } else {
                ProxyServiceState.addLog(getString(R.string.error_critical_format, e.message))
            }
        } finally {
            ProxyServiceState.setCaptchaSession(null)
            process.set(null)
            when {
                userStopped.get() -> {
                    ProxyServiceState.setRunning(false)
                    stopSelf()
                }
                startupFailed -> {
                    ProxyServiceState.addLog(getString(R.string.log_startup_failed_no_watchdog))
                    ProxyServiceState.setRunning(false)
                    // Убираем proxyFailed.tryEmit, так как startProxy и так обработает StartupResult.Failed
                    stopSelf()
                }
                exitCode == 0 -> {
                    val uptime = System.currentTimeMillis() - startedAt
                    if (uptime < 5_000L) {
                        ProxyServiceState.addLog(getString(R.string.log_quick_exit, uptime))
                    } else {
                        ProxyServiceState.addLog(getString(R.string.log_session_finished))
                    }
                    ProxyServiceState.setRunning(false)
                    stopSelf()
                }
                else -> scheduleWatchdogRestart()
            }
        }
    }

    // Watchdog

    private fun scheduleWatchdogRestart() {
        restartCount++
        if (restartCount > MAX_RESTARTS) {
            ProxyServiceState.addLog(getString(R.string.log_watchdog_limit, MAX_RESTARTS))
            ProxyServiceState.setRunning(false)
            ProxyServiceState.emitFailed()
            stopSelf()
            return
        }
        ProxyServiceState.setWorking(false)
        val baseDelay = minOf(1_000L * restartCount, 30_000L)
        val jitter = Random.nextLong(0, 500)
        val delay = baseDelay + jitter
        ProxyServiceState.addLog(getString(R.string.log_watchdog_restart, delay, restartCount, MAX_RESTARTS))
        updateNotification(getString(R.string.notification_title), getString(R.string.notification_reconnecting, restartCount, MAX_RESTARTS))
        handler.postDelayed({
            if (!userStopped.get()) serviceScope.launch { startBinaryProcess() }
        }, delay)
    }

    // Network handover

    private var networkDebounceJob: kotlinx.coroutines.Job? = null

    private fun registerNetworkCallback() {
        networkInitialized = false
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!networkInitialized) {
                    networkInitialized = true
                    return
                }
                
                // Дебаунс: отменяем предыдущий ждущий перезапуск, если он был
                networkDebounceJob?.cancel()
                networkDebounceJob = serviceScope.launch {
                    kotlinx.coroutines.delay(2000)
                    if (!userStopped.get() && process.get() != null) {
                        ProxyServiceState.addLog(getString(R.string.log_network_change))
                        updateNotification(getString(R.string.notification_title), getString(R.string.notification_network_change))
                        restartCount = 0
                        val p = process.get()
                        p?.destroyForcibly()
                    }
                }
            }
        }
        networkCallback = cb
        cm.registerDefaultNetworkCallback(cb)
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { cb ->
            try {
                (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(cb)
            } catch (_: Exception) {}
        }
        networkCallback = null
    }

    // Notification

    private fun updateNotification(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, "ProxyChannel")
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()
        getSystemService(NotificationManager::class.java).notify(1, notification)
    }

    // Helpers

    private fun isQuotaError(line: String): Boolean {
        val l = line.lowercase()
        return l.contains("486") || l.contains("quota") || l.contains("allocation quota")
    }

    override fun onDestroy() {
        super.onDestroy()
        userStopped.set(true)
        ProxyServiceState.setWorking(false)
        ProxyServiceState.setRunning(false)
        handler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        ProxyServiceState.addLog(getString(R.string.log_stop_ui))
        process.get()?.destroyForcibly()
        serviceScope.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }
}
