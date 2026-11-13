package com.nas.gallerymount.domain

/**
 * SMB 連線與掛載設定（密碼另存於加密偏好，不寫入日誌）。
 */
data class SmbConfig(
    val remoteName: String = "nas",
    val host: String = "",
    val port: Int = 445,
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val shareName: String = "",
    val subPath: String = "",
    /** 手機端掛載點，預設為測試用專用目錄 */
    val mountPoint: String = DEFAULT_TEST_MOUNT,
    val enableGooglePhotosIntegration: Boolean = true,
) {
    /** rclone 遠端路徑，例如 nas:share/sub */
    fun remotePath(): String {
        val share = shareName.trim().trim('/')
        val sub = subPath.trim().trim('/')
        val path = when {
            share.isEmpty() -> ""
            sub.isEmpty() -> share
            else -> "$share/$sub"
        }
        return if (path.isEmpty()) "$remoteName:" else "$remoteName:$path"
    }

    companion object {
        const val DEFAULT_TEST_MOUNT = "/storage/emulated/0/Pictures/SMBFolderTest"
        const val DEFAULT_PROD_MOUNT = "/storage/emulated/0/DCIM/Camera/smb"
    }
}

/**
 * Session 狀態機：連線與斷線互斥。
 */
sealed class SessionPhase {
    data object Idle : SessionPhase()
    data object CheckingRoot : SessionPhase()
    data object Ready : SessionPhase()
    data object Testing : SessionPhase()
    data object MountingFuse : SessionPhase()
    data object BindingMediaProvider : SessionPhase()
    data object Scanning : SessionPhase()
    data object Mounted : SessionPhase()
    data object Unmounting : SessionPhase()
    data class Failed(val stage: String, val reason: String) : SessionPhase()
}

data class RootStatus(
    val available: Boolean,
    val uidLine: String = "",
    val message: String = "",
)

data class ToolStatus(
    val rclonePath: String?,
    val fusermountPath: String?,
    val rcloneVersion: String? = null,
    val ok: Boolean = false,
    val message: String = "",
)

data class ProbeResult(
    val supported: Boolean,
    val profileName: String,
    val mediaProviderProcess: String? = null,
    val reason: String = "",
)

data class ScanProgress(
    val discovered: Int = 0,
    val submitted: Int = 0,
    val indexed: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    /** 0–100；列舉中為 0，提交中依 submitted/discovered */
    val percent: Int = 0,
    val cancelled: Boolean = false,
    val message: String = "",
) {
    companion object {
        fun calcPercent(discovered: Int, submitted: Int, failed: Int): Int {
            if (discovered <= 0) return 0
            return ((submitted + failed) * 100 / discovered).coerceIn(0, 100)
        }
    }
}

data class MountSnapshot(
    val fuseMounted: Boolean = false,
    val bindMounted: Boolean = false,
    val mediaProviderPid: Int? = null,
    val mountPoint: String = "",
    val remotePath: String = "",
)

/**
 * MediaStore 索引狀態（斷線預設保留列；可手動清除）。
 */
enum class IndexAvailability {
    Unknown,
    Available,
    UnavailableAfterDisconnect,
    /** 已主動清除本掛載路徑的 MediaStore 列 */
    Cleared,
}

data class SessionUiState(
    val phase: SessionPhase = SessionPhase.Idle,
    val root: RootStatus = RootStatus(false, message = "尚未檢查"),
    val tools: ToolStatus = ToolStatus(null, null, message = "尚未檢查"),
    val probe: ProbeResult? = null,
    val config: SmbConfig = SmbConfig(),
    val sourceListing: List<String> = emptyList(),
    val statusMessage: String = "",
    val scan: ScanProgress = ScanProgress(),
    val mount: MountSnapshot = MountSnapshot(),
    val indexAvailability: IndexAvailability = IndexAvailability.Unknown,
    val busy: Boolean = false,
    /** 是否在 UI 顯示詳細 logs */
    val showLogs: Boolean = false,
)
