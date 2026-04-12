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
import com.freeturn.app.WireproxyServiceState
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.ClientConfig
import com.freeturn.app.data.ThemeMode
import com.freeturn.app.domain.AppUpdater
import com.freeturn.app.domain.LocalProxyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import kotlin.system.measureTimeMillis

data class WgConfig(
    val privateKey: String = "",
    val address: String = "",
    val dns: String = "",
    val mtu: String = "",
    val publicKey: String = "",
    val endpoint: String = "",
    val allowedIps: String = "",
    val persistentKeepalive: String = "",
    val httpBindAddress: String = DEFAULT_HTTP_BIND_ADDRESS,
    val socks5BindAddress: String = DEFAULT_SOCKS5_BIND_ADDRESS
) {
    companion object {
        const val DEFAULT_HTTP_BIND_ADDRESS = "127.0.0.1:8080"
        const val DEFAULT_SOCKS5_BIND_ADDRESS = "127.0.0.1:2080"
    }
}

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

    private val _onboardingDone = MutableStateFlow(false)
    val onboardingDone: StateFlow<Boolean> = _onboardingDone.asStateFlow()

    private val _themeMode = MutableStateFlow(ThemeMode.DARK)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _dynamicTheme = MutableStateFlow(true)
    val dynamicTheme: StateFlow<Boolean> = _dynamicTheme.asStateFlow()

    private val _clientConfig = MutableStateFlow(ClientConfig())
    val clientConfig: StateFlow<ClientConfig> = _clientConfig.asStateFlow()

    // WireGuard config (wg.conf)
    private val _wgConfigText = MutableStateFlow("")
    val wgConfigText: StateFlow<String> = _wgConfigText.asStateFlow()

    private val _wgConfig = MutableStateFlow(WgConfig())
    val wgConfig: StateFlow<WgConfig> = _wgConfig.asStateFlow()

    // Custom kernel
    private val _kernelError = MutableStateFlow<String?>(null)
    val kernelError: StateFlow<String?> = _kernelError.asStateFlow()

    private val _wireproxyPing = MutableStateFlow<PingResult?>(null)
    val wireproxyPing: StateFlow<PingResult?> = _wireproxyPing.asStateFlow()

    private var pingJob: Job? = null

    init {
        loadWgConfig()
        viewModelScope.launch {
            // Загружаем все критические настройки до завершения инициализации.
            // Это предотвращает "мелькание" экранов (например, показ онбординга на долю секунды),
            // так как при isInitialized = true все StateFlow уже будут иметь актуальные значения.
            val done = prefs.onboardingDoneFlow.first()
            val theme = prefs.themeModeFlow.first()
            val dynamic = prefs.dynamicThemeFlow.first()
            val config = prefs.clientConfigFlow.first()

            _onboardingDone.value = done
            _themeMode.value = theme
            _dynamicTheme.value = dynamic
            _clientConfig.value = config

            _isInitialized.value = true

            // Запускаем фоновое обновление при изменении в DataStore
            launch { prefs.onboardingDoneFlow.collect { _onboardingDone.value = it } }
            launch { prefs.themeModeFlow.collect { _themeMode.value = it } }
            launch { prefs.dynamicThemeFlow.collect { _dynamicTheme.value = it } }
            launch { prefs.clientConfigFlow.collect { _clientConfig.value = it } }
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
        viewModelScope.launch {
            proxyManager.observeProxyServiceWorking()
        }
        viewModelScope.launch {
            WireproxyServiceState.state
                .map { it is WireproxyState.Running }
                .distinctUntilChanged()
                .collect { isRunning ->
                    if (isRunning) {
                        checkWireproxyPing()
                    } else {
                        _wireproxyPing.value = null
                    }
                }
        }
        proxyManager.syncInitialState()
    }

    override fun onCleared() {
        super.onCleared()
        proxyManager.destroy()
    }

    fun setDynamicTheme(enabled: Boolean) {
        viewModelScope.launch { prefs.setDynamicTheme(enabled) }
    }
    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }

    val vkLinkHistory: StateFlow<List<String>> = prefs.vkLinkHistoryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val serverAddressHistory: StateFlow<List<String>> = prefs.serverAddressHistoryFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _privacyMode = MutableStateFlow(false)
    val privacyMode: StateFlow<Boolean> = _privacyMode.asStateFlow()

    fun setPrivacyMode(enabled: Boolean) { _privacyMode.value = enabled }

    // Local proxy
    fun startProxy() {
        viewModelScope.launch {
            if (clientConfig.value.wireproxyEnabled && !isValidHostPort(_wgConfig.value.socks5BindAddress)) {
                updateWgConfig(_wgConfig.value.copy(socks5BindAddress = WgConfig.DEFAULT_SOCKS5_BIND_ADDRESS))
            }
            prefs.addVkLinkToHistory(clientConfig.value.vkLink)
            prefs.addServerAddressToHistory(clientConfig.value.serverAddress)
            proxyManager.startProxy(clientConfig.value)
        }
    }

    fun stopProxy() {
        proxyManager.stopProxy()
    }

    fun startWireproxy() {
        if (!isValidHostPort(_wgConfig.value.socks5BindAddress)) {
            updateWgConfig(_wgConfig.value.copy(socks5BindAddress = WgConfig.DEFAULT_SOCKS5_BIND_ADDRESS))
        }
        proxyManager.startWireproxy()
    }

    fun stopWireproxy() {
        proxyManager.stopWireproxy()
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

    fun removeVkLinkFromHistory(link: String) {
        viewModelScope.launch { prefs.removeVkLinkFromHistory(link) }
    }

    fun removeServerAddressFromHistory(address: String) {
        viewModelScope.launch { prefs.removeServerAddressFromHistory(address) }
    }

    fun setOnboardingDone() {
        viewModelScope.launch { prefs.setOnboardingDone(true) }
    }

    fun checkWireproxyPing() {
        val socksAddr = _wgConfig.value.socks5BindAddress
        if (!isValidHostPort(socksAddr) || socksAddr.isBlank()) return

        pingJob?.cancel()
        pingJob = viewModelScope.launch {
            _wireproxyPing.value = PingResult.Loading
            
            repeat(5) { attempt ->
                // Отменяем, если Wireproxy перестал работать
                if (WireproxyServiceState.state.value != WireproxyState.Running) {
                    _wireproxyPing.value = null
                    return@launch
                }

                val result = withContext(Dispatchers.IO) {
                    try {
                        val parts = socksAddr.split(":")
                        val proxyHost = parts[0]
                        val proxyPort = parts[1].toInt()

                        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyHost, proxyPort))
                        val time = measureTimeMillis {
                            Socket(proxy).use { socket ->
                                socket.connect(InetSocketAddress("1.1.1.1", 53), 2000)
                            }
                        }
                        PingResult.Success(time)
                    } catch (_: Exception) {
                        null
                    }
                }

                if (result is PingResult.Success) {
                    _wireproxyPing.value = result
                    return@launch
                }
                
                if (attempt < 2) delay(500) // Пауза перед следующей попыткой
            }
            
            _wireproxyPing.value = PingResult.Error
        }
    }

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
        _wgConfig.value = parseWgConfig(text)
        val newText = _wgConfig.value.toWgString()
        _wgConfigText.value = newText
        saveWgConfigInternal(newText)
    }

    private fun saveWgConfigInternal(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(getApplication<Application>().filesDir, "wg.conf")
            file.writeText(text)
        }
    }

    private fun isValidHostPort(address: String): Boolean {
        return try {
            val parts = address.split(":")
            if (parts.size != 2) return false
            val host = parts[0]
            val port = parts[1].toInt()
            host.isNotBlank() && port in 1..65535
        } catch (_: Exception) {
            false
        }
    }

    private fun parseWgConfig(text: String): WgConfig {
        var privateKey = ""; var address = ""; var dns = ""; var mtu = "1280"
        var publicKey = ""; var endpoint = "127.0.0.1:9000"; var allowedIps = ""; var persistentKeepalive = "25"
        var httpBindAddress = WgConfig.DEFAULT_HTTP_BIND_ADDRESS; var socks5BindAddress = WgConfig.DEFAULT_SOCKS5_BIND_ADDRESS

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

    sealed class PingResult {
        object Loading : PingResult()
        data class Success(val ms: Long) : PingResult()
        object Error : PingResult()
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
