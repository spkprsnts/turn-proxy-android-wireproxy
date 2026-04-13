package com.freeturn.app

import com.freeturn.app.viewmodel.WireproxyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object WireproxyServiceState {
    private val _state = MutableStateFlow<WireproxyState>(WireproxyState.Idle)
    val state = _state.asStateFlow()

    fun updateStatus(newStatus: WireproxyState) {
        _state.value = newStatus
    }
}
