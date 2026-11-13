package com.nas.gallerymount.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nas.gallerymount.domain.SessionController
import com.nas.gallerymount.domain.SessionUiState
import com.nas.gallerymount.domain.SmbConfig
import com.nas.gallerymount.util.AppLogger
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(
    private val session: SessionController,
) : ViewModel() {
    val state: StateFlow<SessionUiState> = session.state
    val logs: StateFlow<List<String>> = AppLogger.lines.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppLogger.snapshot(),
    )

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

    fun cancelScan() = session.cancelScan()

    fun toggleShowLogs() = session.toggleShowLogs()

    fun clearLogs() = AppLogger.clear()

    suspend fun browseShares(): Result<List<String>> = session.browseShares()

    suspend fun browseDirectories(share: String, subPath: String): Result<List<String>> =
        session.browseDirectories(share, subPath)

    class Factory(
        private val session: SessionController,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(session) as T
        }
    }
}
