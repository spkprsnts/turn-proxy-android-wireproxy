package com.freeturn.app.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.freeturn.app.ProxyService
import com.freeturn.app.WireproxyService
import com.freeturn.app.ProxyServiceState
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.ClientConfig
import com.freeturn.app.data.ThemeMode
import com.freeturn.app.domain.AppUpdater
import com.freeturn.app.domain.LocalProxyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class WgConfig(
    val privateKey: String = "",
    val address: String = "",
    val dns: String = "",
    val mtu: String = "",
    val publicKey: String = "",
    val endpoint: String = "",
    val allowedIps: String = "",
    val persistentKeepalive: String = "",
    val httpBindAddress: String = "127.0.0.1:8080",
    val socks5BindAddress: String = "127.0.0.1:1080"
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)
    private val proxyManager = LocalProxyManager(application)
    private val appUpdater = AppUpdater(application)

    val proxyState: StateFlow<ProxyState> = proxyManager.proxyState
    val logs: StateFlow<List<String>> = ProxyServiceState.logs
    val customKernelExists: StateFlow<Boolean> = proxyManager.customKernelExists
    val updateState: StateFlow<UpdateState> = appUpdater.state

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    // WireGuard config (wg.conf)
    private val _wgConfigText = MutableStateFlow("")
    val wgConfigText: StateFlow<String> = _wgConfigText.asStateFlow()

    private val _wgConfig = MutableStateFlow(WgConfig())
    val wgConfig: StateFlow<WgConfig> = _wgConfig.asStateFlow()

    init {
        loadWgConfig()
        viewModelScope.launch {
            prefs.onboardingDoneFlow.first()
            _isInitialized.value = true
        }
        viewModelScope.launch {
            proxyManager.observeProxyLifecycle()
        }
        viewModelScope.launch {
            proxyManager.observeProxyServiceStatus()
        }
        viewModelScope.launch {
            proxyManager.observeCaptchaEvents()
        }
        proxyManager.syncInitialState()
    }

    override fun onCleared() {
        super.onCleared()
        proxyManager.destroy()
    }

    val clientConfig: StateFlow<ClientConfig> = prefs.clientConfigFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClientConfig())

    val onboardingDone: StateFlow<Boolean> = prefs.onboardingDoneFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val dynamicTheme: StateFlow<Boolean> = prefs.dynamicThemeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setDynamicTheme(enabled: Boolean) {
        viewModelScope.launch { prefs.setDynamicTheme(enabled) }
    }
    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }
    val themeMode: StateFlow<ThemeMode> = prefs.themeModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.DARK)

    private val _privacyMode = MutableStateFlow(false)
    val privacyMode: StateFlow<Boolean> = _privacyMode.asStateFlow()

    fun setPrivacyMode(enabled: Boolean) { _privacyMode.value = enabled }

    // Local proxy
    fun startProxy() {
        viewModelScope.launch {
            proxyManager.startProxy(clientConfig.value)
        }
    }

    fun stopProxy() {
        proxyManager.stopProxy()
    }

    fun dismissCaptcha() {
        proxyManager.dismissCaptcha()
    }

    fun clearLogs() {
        ProxyServiceState.clearLogs()
    }

    // Preferences
    fun saveClientConfig(config: ClientConfig) {
        viewModelScope.launch { prefs.saveClientConfig(config) }
    }

    fun setOnboardingDone() {
        viewModelScope.launch { prefs.setOnboardingDone(true) }
    }

    // Custom kernel
    private val _kernelError = MutableStateFlow<String?>(null)
    val kernelError: StateFlow<String?> = _kernelError.asStateFlow()

    fun setCustomKernel(uri: Uri) {
        viewModelScope.launch {
            _kernelError.value = proxyManager.setCustomKernel(uri)
        }
    }

    fun clearCustomKernel() {
        proxyManager.clearCustomKernel()
    }

    fun clearKernelError() {
        _kernelError.value = null
    }

    // App update
    fun checkForUpdate() {
        viewModelScope.launch { appUpdater.checkForUpdate(silent = false) }
    }

    fun downloadUpdate() {
        viewModelScope.launch { appUpdater.downloadUpdate() }
    }

    fun installUpdate() {
        appUpdater.installUpdate()
    }

    fun resetUpdateState() {
        appUpdater.resetState()
    }

    private fun loadWgConfig() {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(getApplication<Application>().filesDir, "wg.conf")
            if (file.exists()) {
                val text = file.readText()
                _wgConfigText.value = text
                _wgConfig.value = parseWgConfig(text)
            }
        }
    }

    fun updateWgConfig(config: WgConfig) {
        _wgConfig.value = config
        val text = config.toWgString()
        _wgConfigText.value = text
        saveWgConfigInternal(text)
    }

    fun updateWgConfigText(text: String) {
        _wgConfigText.value = text
        _wgConfig.value = parseWgConfig(text)
        saveWgConfigInternal(text)
    }

    private fun saveWgConfigInternal(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(getApplication<Application>().filesDir, "wg.conf")
            file.writeText(text)
        }
    }

    private fun parseWgConfig(text: String): WgConfig {
        var privateKey = ""; var address = ""; var dns = ""; var mtu = ""
        var publicKey = ""; var endpoint = ""; var allowedIps = ""; var persistentKeepalive = ""
        var httpBindAddress = ""; var socks5BindAddress = ""

        var currentSection = ""
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.substring(1, trimmed.length - 1).lowercase()
            } else if (trimmed.contains("=")) {
                val parts = trimmed.split("=", limit = 2)
                val key = parts[0].trim().lowercase()
                val value = parts[1].trim()
                when (currentSection) {
                    "interface" -> when (key) {
                        "privatekey" -> privateKey = value
                        "address" -> address = value
                        "dns" -> dns = value
                        "mtu" -> mtu = value
                    }
                    "peer" -> when (key) {
                        "publickey" -> publicKey = value
                        "endpoint" -> endpoint = value
                        "allowedips" -> allowedIps = value
                        "persistentkeepalive" -> persistentKeepalive = value
                    }
                    "http" -> if (key == "bindaddress") httpBindAddress = value
                    "socks5" -> if (key == "bindaddress") socks5BindAddress = value
                }
            }
        }
        return WgConfig(privateKey, address, dns, mtu, publicKey, endpoint, allowedIps, persistentKeepalive, httpBindAddress, socks5BindAddress)
    }

    private fun WgConfig.toWgString(): String {
        val sb = StringBuilder()
        sb.append("[Interface]\n")
        sb.append("PrivateKey = $privateKey\n")
        sb.append("Address = $address\n")
        if (dns.isNotBlank()) sb.append("DNS = $dns\n")
        if (mtu.isNotBlank()) sb.append("MTU = $mtu\n")
        sb.append("\n[Peer]\n")
        sb.append("PublicKey = $publicKey\n")
        sb.append("Endpoint = $endpoint\n")
        sb.append("AllowedIPs = $allowedIps\n")
        if (persistentKeepalive.isNotBlank()) sb.append("PersistentKeepalive = $persistentKeepalive\n")
        if (httpBindAddress.isNotBlank()) {
            sb.append("\n[http]\n")
            sb.append("BindAddress = $httpBindAddress\n")
        }
        if (socks5BindAddress.isNotBlank()) {
            sb.append("\n[Socks5]\n")
            sb.append("BindAddress = $socks5BindAddress\n")
        }
        return sb.toString()
    }

    fun resetAllSettings(context: Context) {
        viewModelScope.launch {
            if (ProxyServiceState.isRunning.value) {
                context.stopService(Intent(context, ProxyService::class.java))
                context.stopService(Intent(context, WireproxyService::class.java))
            }
            prefs.resetAll()
            proxyManager.clearState()
            ProxyServiceState.clearLogs()
            
            // Delete wg.conf as well
            val file = File(getApplication<Application>().filesDir, "wg.conf")
            if (file.exists()) file.delete()
            _wgConfig.value = WgConfig()
            _wgConfigText.value = ""

            val intent = (context as? android.app.Activity)?.intent
                ?: Intent(context, com.freeturn.app.MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
        }
    }
}
