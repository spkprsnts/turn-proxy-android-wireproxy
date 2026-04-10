package com.freeturn.app.viewmodel

// Local proxy client states
sealed class ProxyState {
    object Idle : ProxyState()
    object Starting : ProxyState()
    object Running : ProxyState()
    data class Error(val message: String) : ProxyState()
    data class CaptchaRequired(val url: String, val sessionId: Long = 0L) : ProxyState()
}

// App update states
sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class Available(val version: String, val changelog: String) : UpdateState()
    object NoUpdate : UpdateState()
    data class Downloading(val progress: Int) : UpdateState()
    object ReadyToInstall : UpdateState()
    data class Error(val message: String) : UpdateState()
}
