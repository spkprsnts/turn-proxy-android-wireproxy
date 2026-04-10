package com.freeturn.app.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.freeturn.app.ProxyService
import com.freeturn.app.ProxyServiceState
import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.ClientConfig
import com.freeturn.app.domain.AppUpdater
import com.freeturn.app.domain.LocalProxyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    init {
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

        // Автоматическая проверка обновлений при холодном запуске (silent — без ошибок)
//        viewModelScope.launch {
//            appUpdater.checkForUpdate(silent = true)
//        }
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

    fun resetAllSettings(context: Context) {
        viewModelScope.launch {
            if (ProxyServiceState.isRunning.value) {
                context.stopService(Intent(context, ProxyService::class.java))
            }
            prefs.resetAll()
            proxyManager.clearState()
            ProxyServiceState.clearLogs()

            val intent = (context as? android.app.Activity)?.intent
                ?: Intent(context, com.freeturn.app.MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
        }
    }
}
