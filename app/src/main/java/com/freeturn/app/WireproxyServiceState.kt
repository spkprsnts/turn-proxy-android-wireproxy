package com.freeturn.app

import com.freeturn.app.viewmodel.WireproxyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object WireproxyServiceState {
    private val _state = MutableStateFlow<WireproxyState>(WireproxyState.Idle)
    val state = _state.asStateFlow()

    private val _metricsPort = MutableStateFlow<Int?>(null)
    val metricsPort = _metricsPort.asStateFlow()

    fun updateStatus(newStatus: WireproxyState) {
        _state.value = newStatus
    }

    fun updateMetricsPort(port: Int?) {
        _metricsPort.value = port
    }
}
