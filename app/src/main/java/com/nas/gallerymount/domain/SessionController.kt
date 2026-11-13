package com.nas.gallerymount.domain

import com.nas.gallerymount.config.ConfigStore
import com.nas.gallerymount.config.SessionStore
import com.nas.gallerymount.media.MediaIndexer
import com.nas.gallerymount.mount.MountManager
import com.nas.gallerymount.mount.SmbBrowser
import com.nas.gallerymount.mount.ToolLocator
import com.nas.gallerymount.probe.DeviceProbe
import com.nas.gallerymount.root.RootExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 連線／斷線互斥狀態機與用例入口。
 */
class SessionController(
    private val rootExecutor: RootExecutor,
    private val configStore: ConfigStore,
    private val sessionStore: SessionStore,
    private val toolLocator: ToolLocator,
    private val smbBrowser: SmbBrowser,
    private val mountManager: MountManager,
    private val mediaIndexer: MediaIndexer,
    private val deviceProbe: DeviceProbe,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(
        SessionUiState(
            config = configStore.load(),
            mount = sessionStore.loadMount(),
            indexAvailability = sessionStore.loadIndexAvailability(),
        ),
    )
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    fun updateConfig(config: SmbConfig) {
        _state.update { it.copy(config = config) }
    }

    fun persistConfig() {
        try {
            configStore.save(_state.value.config)
            _state.update { it.copy(statusMessage = "設定已儲存（rclone 密碼已 obscure）") }
        } catch (e: Exception) {
            _state.update {
                it.copy(statusMessage = "儲存失敗：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    suspend fun refreshRootAndTools() {
        _state.update {
            it.copy(phase = SessionPhase.CheckingRoot, busy = true, statusMessage = "檢查 Root…")
        }
        val root = rootExecutor.checkRoot()
        val tools = if (root.available) {
            toolLocator.verify(rootExecutor)
        } else {
            ToolStatus(null, null, message = "Root 未就緒，略過工具檢查")
        }
        val probe = if (root.available) deviceProbe.probe() else null
        val phase = when {
            !root.available -> SessionPhase.Failed("Root", root.message)
            !tools.ok -> SessionPhase.Failed("Tools", tools.message)
            probe?.supported != true -> SessionPhase.Failed(
                "Probe",
                probe?.reason ?: "裝置探測失敗",
            )
            else -> SessionPhase.Ready
        }
        val encryptWarn = if (configStore.usingFallbackPrefs) {
            "警告：加密儲存不可用，設定以未加密私有 prefs 保存。"
        } else {
            ""
        }
        _state.update {
            it.copy(
                phase = phase,
                root = root,
                tools = tools,
                probe = probe,
                busy = false,
                statusMessage = listOf(
                    when (phase) {
                        is SessionPhase.Ready -> "就緒"
                        is SessionPhase.Failed -> phase.reason
                        else -> it.statusMessage
                    },
                    encryptWarn,
                ).filter { msg -> msg.isNotBlank() }.joinToString(" "),
            )
        }
    }

    suspend fun testConnection() = mutex.withLock {
        if (_state.value.busy) return
        if (!_state.value.root.available) {
            _state.update { it.copy(statusMessage = "請先完成 Root 授權") }
            return
        }
        _state.update {
            it.copy(phase = SessionPhase.Testing, busy = true, statusMessage = "測試 SMB 連線…")
        }
        val result = smbBrowser.testConnection(_state.value.config)
        _state.update {
            it.copy(
                phase = if (result.ok) SessionPhase.Ready else SessionPhase.Failed("Testing", result.message),
                sourceListing = result.lines,
                busy = false,
                statusMessage = result.message,
            )
        }
    }

    suspend fun listSource() = mutex.withLock {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, statusMessage = "列出來源…") }
        val result = smbBrowser.listPath(_state.value.config)
        _state.update {
            it.copy(
                sourceListing = result.lines,
                busy = false,
                statusMessage = result.message,
                phase = if (result.ok) SessionPhase.Ready
                else SessionPhase.Failed("Browse", result.message),
            )
        }
    }

    suspend fun connectAndScan() = mutex.withLock {
        if (_state.value.busy) return
        if (!_state.value.root.available || !_state.value.tools.ok) {
            _state.update { it.copy(statusMessage = "Root 或工具未就緒") }
            return
        }
        val config = _state.value.config
        configStore.save(config)

        // FUSE
        _state.update {
            it.copy(phase = SessionPhase.MountingFuse, busy = true, statusMessage = "掛載 rclone FUSE…")
        }
        val fuse = mountManager.mountFuse(config)
        if (!fuse.ok) {
            fail("MountingFuse", fuse.message)
            return
        }
        _state.update { it.copy(mount = fuse.snapshot, statusMessage = fuse.message) }

        // Bind（若啟用 Google 相簿整合）
        var snapshot = fuse.snapshot
        if (config.enableGooglePhotosIntegration) {
            _state.update {
                it.copy(
                    phase = SessionPhase.BindingMediaProvider,
                    statusMessage = "對齊 MediaProvider 掛載視圖…",
                )
            }
            val bind = mountManager.bindMediaProvider(config)
            if (!bind.ok) {
                // 盡力卸載剛建立的 FUSE
                mountManager.unmountAll(config, null)
                fail("BindingMediaProvider", bind.message)
                return
            }
            snapshot = bind.snapshot
            _state.update { it.copy(mount = snapshot, statusMessage = bind.message) }
        }

        // 掃描
        _state.update {
            it.copy(
                phase = SessionPhase.Scanning,
                statusMessage = buildScanWarning(config),
                indexAvailability = IndexAvailability.Available,
            )
        }
        val scan = mediaIndexer.scanMountPoint(config.mountPoint) { progress ->
            _state.update { s -> s.copy(scan = progress, statusMessage = progress.message) }
        }

        sessionStore.saveMount(snapshot, IndexAvailability.Available)
        _state.update {
            it.copy(
                phase = SessionPhase.Mounted,
                busy = false,
                mount = snapshot,
                scan = scan.progress,
                indexAvailability = IndexAvailability.Available,
                statusMessage = "已掛載。" + scan.progress.message,
            )
        }
    }

    suspend fun disconnect() = mutex.withLock {
        if (_state.value.busy) return
        _state.update {
            it.copy(phase = SessionPhase.Unmounting, busy = true, statusMessage = "斷線中…")
        }
        mediaIndexer.stopSubmitting()
        val config = _state.value.config
        val result = mountManager.unmountAll(config, _state.value.mount.mediaProviderPid)
        if (!result.ok) {
            _state.update {
                it.copy(
                    phase = SessionPhase.Failed("Unmounting", result.message),
                    busy = false,
                    mount = result.snapshot,
                    statusMessage = result.message,
                )
            }
            return
        }
        val prefix = mediaIndexer.relativePathOf(config.mountPoint)
        val note = mediaIndexer.describeAvailability(prefix, filesExpectedReadable = false)
        sessionStore.saveMount(result.snapshot, IndexAvailability.UnavailableAfterDisconnect)
        sessionStore.clearActiveFlags()
        _state.update {
            it.copy(
                phase = SessionPhase.Idle,
                busy = false,
                mount = result.snapshot,
                indexAvailability = IndexAvailability.UnavailableAfterDisconnect,
                statusMessage = "已斷線。$note",
            )
        }
    }

    suspend fun refreshStatus() {
        val config = _state.value.config
        val snap = mountManager.refreshSnapshot(config)
        val prefix = mediaIndexer.relativePathOf(config.mountPoint)
        val readable = snap.fuseMounted
        val availability = when {
            snap.fuseMounted -> IndexAvailability.Available
            sessionStore.loadIndexAvailability() == IndexAvailability.UnavailableAfterDisconnect ->
                IndexAvailability.UnavailableAfterDisconnect
            else -> IndexAvailability.Unknown
        }
        _state.update {
            it.copy(
                mount = snap,
                indexAvailability = availability,
                statusMessage = mediaIndexer.describeAvailability(prefix, readable) +
                    "｜FUSE=${snap.fuseMounted} bind=${snap.bindMounted}",
            )
        }
    }

    /** 供 SMB 資料夾選擇器：列出共享（不佔用連線／斷線 busy 鎖）。 */
    suspend fun browseShares(): Result<List<String>> {
        if (!_state.value.root.available) {
            return Result.failure(IllegalStateException("請先完成 Root 授權"))
        }
        val result = smbBrowser.listShares(_state.value.config)
        return if (result.ok) {
            Result.success(result.lines)
        } else {
            Result.failure(IllegalStateException(result.message))
        }
    }

    /** 供 SMB 資料夾選擇器：列出指定共享下的子目錄。 */
    suspend fun browseDirectories(share: String, subPath: String): Result<List<String>> {
        if (!_state.value.root.available) {
            return Result.failure(IllegalStateException("請先完成 Root 授權"))
        }
        val result = smbBrowser.listDirectories(_state.value.config, share, subPath)
        return if (result.ok) {
            Result.success(result.lines)
        } else {
            Result.failure(IllegalStateException(result.message))
        }
    }

    private fun fail(stage: String, reason: String) {
        _state.update {
            it.copy(
                phase = SessionPhase.Failed(stage, reason),
                busy = false,
                statusMessage = "[$stage] $reason",
            )
        }
    }

    private fun buildScanWarning(config: SmbConfig): String {
        val range = config.mountPoint
        return "即將掃描：$range\n" +
            "若 Google 相簿已開啟裝置資料夾備份，索引後的媒體可能被上傳到雲端。"
    }
}
