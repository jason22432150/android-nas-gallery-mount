package com.nas.gallerymount.domain

import com.nas.gallerymount.config.ConfigStore
import com.nas.gallerymount.config.SessionStore
import com.nas.gallerymount.media.MediaIndexer
import com.nas.gallerymount.mount.MountManager
import com.nas.gallerymount.mount.SmbBrowser
import com.nas.gallerymount.mount.ToolLocator
import com.nas.gallerymount.probe.DeviceProbe
import com.nas.gallerymount.root.RootExecutor
import com.nas.gallerymount.util.AppLogger
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
        AppLogger.d(
            "Config",
            "更新設定 host=${config.host} share=${config.shareName} " +
                "sub=${config.subPath} mount=${config.mountPoint}",
        )
    }

    fun setShowLogs(show: Boolean) {
        _state.update { it.copy(showLogs = show) }
        AppLogger.i("UI", "顯示 logs=$show")
    }

    fun toggleShowLogs() {
        setShowLogs(!_state.value.showLogs)
    }

    /** 中斷進行中的媒體掃描（已掛載可保留；僅停止提交新掃描）。 */
    fun cancelScan() {
        AppLogger.w("Scan", "收到中斷掃描請求")
        mediaIndexer.stopSubmitting()
        _state.update {
            it.copy(
                statusMessage = "正在中斷掃描…",
                scan = it.scan.copy(cancelled = true, message = "正在中斷掃描…"),
            )
        }
    }

    fun persistConfig() {
        try {
            configStore.save(_state.value.config)
            AppLogger.i("Config", "設定已儲存 mount=${_state.value.config.mountPoint}")
            _state.update { it.copy(statusMessage = "設定已儲存（rclone 密碼已 obscure）") }
        } catch (e: Exception) {
            AppLogger.e("Config", "儲存失敗：${e.message}")
            _state.update {
                it.copy(statusMessage = "儲存失敗：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    suspend fun refreshRootAndTools() {
        AppLogger.i("Root", "開始檢查 Root／工具／裝置")
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
        val statusMessage = listOf(
            when (phase) {
                is SessionPhase.Ready -> "就緒"
                is SessionPhase.Failed -> phase.reason
                else -> "檢查完成"
            },
            encryptWarn,
        ).filter { msg -> msg.isNotBlank() }.joinToString(" ")
        AppLogger.i("Root", "檢查結果 phase=$phase root=${root.available} tools=${tools.ok}")
        _state.update {
            it.copy(
                phase = phase,
                root = root,
                tools = tools,
                probe = probe,
                busy = false,
                statusMessage = statusMessage,
            )
        }
    }

    suspend fun testConnection() = mutex.withLock {
        if (_state.value.busy) return
        if (!_state.value.root.available) {
            _state.update { it.copy(statusMessage = "請先完成 Root 授權") }
            return
        }
        AppLogger.i("SMB", "測試連線 host=${_state.value.config.host}")
        _state.update {
            it.copy(phase = SessionPhase.Testing, busy = true, statusMessage = "測試 SMB 連線…")
        }
        val result = smbBrowser.testConnection(_state.value.config)
        AppLogger.i("SMB", "測連線結果 ok=${result.ok} ${result.message}")
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

    suspend fun connectAndScan() {
        val snapshot: MountSnapshot
        val config: SmbConfig
        mutex.withLock {
            if (_state.value.busy) return
            if (!_state.value.root.available || !_state.value.tools.ok) {
                _state.update { it.copy(statusMessage = "Root 或工具未就緒") }
                return
            }
            config = _state.value.config
            AppLogger.i(
                "Session",
                "連線並掃描 remote=${config.remotePath()} mount=${config.mountPoint}",
            )
            configStore.save(config)

            // FUSE
            _state.update {
                it.copy(phase = SessionPhase.MountingFuse, busy = true, statusMessage = "掛載 rclone FUSE…")
            }
            val fuse = mountManager.mountFuse(config)
            if (!fuse.ok) {
                AppLogger.e("Mount", fuse.message)
                fail("MountingFuse", fuse.message)
                return
            }
            AppLogger.i("Mount", fuse.message)
            _state.update { it.copy(mount = fuse.snapshot, statusMessage = fuse.message) }

            // Bind（若啟用 Google 相簿整合）
            var snap = fuse.snapshot
            if (config.enableGooglePhotosIntegration) {
                _state.update {
                    it.copy(
                        phase = SessionPhase.BindingMediaProvider,
                        statusMessage = "對齊 MediaProvider 掛載視圖…",
                    )
                }
                val bind = mountManager.bindMediaProvider(config)
                if (!bind.ok) {
                    AppLogger.e("Bind", bind.message)
                    mountManager.unmountAll(config, null)
                    fail("BindingMediaProvider", bind.message)
                    return
                }
                AppLogger.i("Bind", bind.message)
                snap = bind.snapshot
                _state.update { it.copy(mount = snap, statusMessage = bind.message) }
            }
            snapshot = snap

            // 掃描在鎖外執行，才能即時 cancel／disconnect
            mediaIndexer.allowSubmitting()
            _state.update {
                it.copy(
                    phase = SessionPhase.Scanning,
                    busy = true,
                    statusMessage = buildScanWarning(config),
                    indexAvailability = IndexAvailability.Available,
                    scan = ScanProgress(message = "準備掃描…", percent = 0),
                )
            }
        }

        AppLogger.i("Scan", "開始掃描 ${config.mountPoint}")
        val scan = mediaIndexer.scanMountPoint(config.mountPoint) { progress ->
            _state.update { s -> s.copy(scan = progress, statusMessage = progress.message) }
        }
        AppLogger.i(
            "Scan",
            "結束 percent=${scan.progress.percent} cancelled=${scan.progress.cancelled} " +
                "indexed=${scan.progress.indexed}",
        )

        mutex.withLock {
            // 若掃描期間已開始斷線，勿覆寫 Unmounting／Idle
            if (_state.value.phase is SessionPhase.Unmounting ||
                _state.value.phase is SessionPhase.Idle
            ) {
                AppLogger.i("Scan", "掃描結束但 Session 已在斷線流程，略過 Mounted 狀態")
                return
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
    }

    suspend fun disconnect() {
        // 先停掃描，再搶鎖卸載
        mediaIndexer.stopSubmitting()
        mutex.withLock {
            if (_state.value.phase is SessionPhase.Unmounting) return
            AppLogger.i("Session", "斷線開始")
            _state.update {
                it.copy(phase = SessionPhase.Unmounting, busy = true, statusMessage = "斷線中…")
            }
            val config = _state.value.config
            val result = mountManager.unmountAll(config, _state.value.mount.mediaProviderPid)
            if (!result.ok) {
                AppLogger.e("Session", "斷線失敗：${result.message}")
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
            AppLogger.i("Session", "已斷線 $note")
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
    }

    /**
     * 清除目前掛載點在 MediaStore 的索引列。
     * 必須先斷線；Google 相簿本地清單應隨之消失（雲端備份不受影響）。
     */
    suspend fun clearMediaIndex() {
        mutex.withLock {
            if (_state.value.busy) {
                _state.update { it.copy(statusMessage = "忙碌中，無法清除索引") }
                return
            }
            val phase = _state.value.phase
            if (phase is SessionPhase.Scanning || phase is SessionPhase.MountingFuse ||
                phase is SessionPhase.BindingMediaProvider || phase is SessionPhase.Unmounting ||
                phase is SessionPhase.Mounted
            ) {
                _state.update {
                    it.copy(statusMessage = "請先斷線後再清除索引（避免刪除掛載中的檔案）")
                }
                return
            }
            val mountPoint = _state.value.config.mountPoint
            // 以最新掛載快照為準，不信任過期 UI 狀態
            val snap = mountManager.refreshSnapshot(_state.value.config)
            _state.update { it.copy(mount = snap) }
            if (snap.fuseMounted || snap.bindMounted) {
                _state.update {
                    it.copy(
                        statusMessage = "偵測到仍有掛載（FUSE=${snap.fuseMounted} bind=${snap.bindMounted}），請先斷線再清除索引",
                    )
                }
                return
            }
            AppLogger.i("Index", "開始清除 MediaStore 索引 mount=$mountPoint")
            _state.update {
                it.copy(busy = true, statusMessage = "清除 MediaStore 索引中…")
            }
            try {
                val result = mediaIndexer.clearIndexedForMount(
                    mountPoint = mountPoint,
                    fuseMounted = false,
                    bindMounted = false,
                )
                if (result.ok) {
                    sessionStore.saveMount(snap, IndexAvailability.Cleared)
                    AppLogger.i("Index", result.message)
                    _state.update {
                        it.copy(
                            indexAvailability = IndexAvailability.Cleared,
                            scan = it.scan.copy(indexed = 0, message = result.message),
                            statusMessage = result.message,
                        )
                    }
                } else {
                    AppLogger.e("Index", result.message)
                    _state.update {
                        it.copy(
                            scan = it.scan.copy(
                                indexed = result.after.coerceAtLeast(it.scan.indexed),
                            ),
                            statusMessage = result.message,
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = "清除索引例外：${e.message ?: e.javaClass.simpleName}"
                AppLogger.e("Index", msg)
                _state.update { it.copy(statusMessage = msg) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    suspend fun refreshStatus() {
        val config = _state.value.config
        val snap = mountManager.refreshSnapshot(config)
        val prefix = mediaIndexer.relativePathOf(config.mountPoint)
        val readable = snap.fuseMounted
        val stored = sessionStore.loadIndexAvailability()
        val count = mediaIndexer.countIndexed(prefix)
        val availability = when {
            snap.fuseMounted && count > 0 -> IndexAvailability.Available
            snap.fuseMounted -> IndexAvailability.Available
            stored == IndexAvailability.Cleared && count <= 0 -> IndexAvailability.Cleared
            stored == IndexAvailability.UnavailableAfterDisconnect ->
                IndexAvailability.UnavailableAfterDisconnect
            else -> IndexAvailability.Unknown
        }
        val status = when (availability) {
            IndexAvailability.Cleared ->
                "MediaStore 索引已清除（$prefix）。｜FUSE=${snap.fuseMounted} bind=${snap.bindMounted}"
            else -> mediaIndexer.describeAvailability(prefix, readable) +
                "｜FUSE=${snap.fuseMounted} bind=${snap.bindMounted}"
        }
        _state.update {
            it.copy(
                mount = snap,
                indexAvailability = availability,
                statusMessage = status,
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
        AppLogger.e("Session", "失敗[$stage] $reason")
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
