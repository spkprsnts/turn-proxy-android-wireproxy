package com.freeturn.app

import com.freeturn.app.viewmodel.WireproxyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object WireproxyServiceState {
    private val _state = MutableStateFlow<WireproxyState>(WireproxyState.Idle)
    val state = _state.asStateFlow()

//    private val _logs = MutableStateFlow<List<String>>(emptyList())
//    val logs = _logs.asStateFlow()

    fun updateStatus(newStatus: WireproxyState) {
        _state.value = newStatus
    }

//    fun addLog(message: String) {
//        _logs.update { currentLogs ->
//            (currentLogs + message).takeLast(100) // Храним последние 100 строк
//        }
//    }
//
//    fun clearLogs() {
//        _logs.value = emptyList()
//    }
}
