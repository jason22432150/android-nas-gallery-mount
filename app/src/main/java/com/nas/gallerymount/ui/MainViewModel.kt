package com.nas.gallerymount.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nas.gallerymount.domain.SessionController
import com.nas.gallerymount.domain.SessionUiState
import com.nas.gallerymount.domain.SmbConfig
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(
    private val session: SessionController,
) : ViewModel() {
    val state: StateFlow<SessionUiState> = session.state

    init {
        viewModelScope.launch {
            session.refreshRootAndTools()
            session.refreshStatus()
        }
    }

    fun onConfigChange(config: SmbConfig) = session.updateConfig(config)

    fun saveConfig() = session.persistConfig()

    fun recheckRoot() = viewModelScope.launch { session.refreshRootAndTools() }

    fun testConnection() = viewModelScope.launch { session.testConnection() }

    fun listSource() = viewModelScope.launch { session.listSource() }

    fun connect() = viewModelScope.launch { session.connectAndScan() }

    fun disconnect() = viewModelScope.launch { session.disconnect() }

    fun refreshStatus() = viewModelScope.launch { session.refreshStatus() }

    class Factory(
        private val session: SessionController,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(session) as T
        }
    }
}
