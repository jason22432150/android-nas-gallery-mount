package com.nas.gallerymount.mount

import com.nas.gallerymount.config.ConfigStore
import com.nas.gallerymount.domain.SmbConfig
import com.nas.gallerymount.root.RootExecutor

/**
 * 以 rclone 唯讀驗證 SMB 並列出共享／子資料夾。
 */
class SmbBrowser(
    private val rootExecutor: RootExecutor,
    private val toolLocator: ToolLocator,
    private val configStore: ConfigStore,
) {
    data class BrowseResult(
        val ok: Boolean,
        val lines: List<String>,
        val message: String,
    )

    suspend fun testConnection(config: SmbConfig): BrowseResult {
        configStore.save(config)
        val rclone = toolLocator.rclonePath()
            ?: return BrowseResult(false, emptyList(), "rclone 工具不存在")
        val conf = configStore.rcloneConfigFile().absolutePath
        val remote = "${config.remoteName}:"
        val result = rootExecutor.execute(
            rclone, "lsd", remote,
            "--config", conf,
            "--contimeout", "15s",
            "--timeout", "30s",
            timeoutMs = 45_000L,
        )
        if (!result.success) {
            // 伺服器可能禁止列舉共享：若已填 share，改測具體路徑
            if (config.shareName.isNotBlank()) {
                return listPath(config, maxDepth = 1)
            }
            return BrowseResult(false, emptyList(), "測連線失敗：${result.combined()}")
        }
        val lines = result.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return BrowseResult(true, lines, "連線成功，已列出共享／目錄")
    }

    suspend fun listPath(config: SmbConfig, maxDepth: Int = 1): BrowseResult {
        configStore.save(config)
        val rclone = toolLocator.rclonePath()
            ?: return BrowseResult(false, emptyList(), "rclone 工具不存在")
        val conf = configStore.rcloneConfigFile().absolutePath
        val path = config.remotePath()
        val result = rootExecutor.execute(
            rclone, "lsf", path,
            "--config", conf,
            "--max-depth", maxDepth.toString(),
            "--contimeout", "15s",
            "--timeout", "60s",
            timeoutMs = 90_000L,
        )
        if (!result.success) {
            return BrowseResult(false, emptyList(), "列出來源失敗：${result.combined()}")
        }
        val lines = result.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return BrowseResult(true, lines, "來源可讀，共 ${lines.size} 項")
    }
}
