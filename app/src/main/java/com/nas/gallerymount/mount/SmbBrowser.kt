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
        prepareConfig(config).onFailure { return BrowseResult(false, emptyList(), it.message ?: "設定失敗") }
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
            return BrowseResult(false, emptyList(), "測連線失敗：${sanitizeRcloneError(result.combined())}")
        }
        val lines = result.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return BrowseResult(true, lines, "連線成功，已列出共享／目錄")
    }

    suspend fun listPath(config: SmbConfig, maxDepth: Int = 1): BrowseResult {
        prepareConfig(config).onFailure { return BrowseResult(false, emptyList(), it.message ?: "設定失敗") }
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
            return BrowseResult(false, emptyList(), "列出來源失敗：${sanitizeRcloneError(result.combined())}")
        }
        val lines = result.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return BrowseResult(true, lines, "來源可讀，共 ${lines.size} 項")
    }

    /**
     * 列出遠端共享名稱（`rclone lsd remote:`）。
     * 輸出解析為共享／頂層目錄名稱列表。
     */
    suspend fun listShares(config: SmbConfig): BrowseResult {
        prepareConfig(config).onFailure { return BrowseResult(false, emptyList(), it.message ?: "設定失敗") }
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
            return BrowseResult(false, emptyList(), "無法列舉共享：${sanitizeRcloneError(result.combined())}")
        }
        // lsd 格式：約「          -1 2024-01-01 00:00:00        -1 name」
        val names = result.stdout.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line -> line.substringAfterLast(' ').trim() }
            .filter { it.isNotEmpty() }
        return BrowseResult(true, names, "共 ${names.size} 個共享／頂層目錄")
    }

    /**
     * 只列目錄，供子資料夾選擇器使用。
     * @param share 共享名稱
     * @param subPath 相對共享的子路徑（可空）
     */
    suspend fun listDirectories(
        config: SmbConfig,
        share: String,
        subPath: String,
    ): BrowseResult {
        val trimmedShare = share.trim().trim('/')
        if (trimmedShare.isEmpty()) {
            return listShares(config)
        }
        prepareConfig(config).onFailure { return BrowseResult(false, emptyList(), it.message ?: "設定失敗") }
        val rclone = toolLocator.rclonePath()
            ?: return BrowseResult(false, emptyList(), "rclone 工具不存在")
        val conf = configStore.rcloneConfigFile().absolutePath
        val sub = subPath.trim().trim('/')
        val remote = if (sub.isEmpty()) {
            "${config.remoteName}:$trimmedShare"
        } else {
            "${config.remoteName}:$trimmedShare/$sub"
        }
        val result = rootExecutor.execute(
            rclone, "lsf", remote,
            "--config", conf,
            "--dirs-only",
            "--max-depth", "1",
            "--contimeout", "15s",
            "--timeout", "60s",
            timeoutMs = 90_000L,
        )
        if (!result.success) {
            return BrowseResult(false, emptyList(), "無法列出資料夾：${sanitizeRcloneError(result.combined())}")
        }
        val dirs = result.stdout.lines()
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() }
            .map { it.substringAfterLast('/') }
            .distinct()
        return BrowseResult(true, dirs, "共 ${dirs.size} 個子資料夾")
    }

    private fun prepareConfig(config: SmbConfig): Result<Unit> = runCatching {
        configStore.save(config)
    }.recoverCatching { e ->
        throw IllegalStateException(e.message ?: "寫入 rclone.conf 失敗")
    }

    /** 縮短 rclone 錯誤並標出常見的密碼 obscure 問題。 */
    private fun sanitizeRcloneError(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.contains("base64 decode failed", ignoreCase = true) ||
            trimmed.contains("is it obscured", ignoreCase = true)
        ) {
            return "密碼格式錯誤：rclone.conf 需要 obscure 後的密碼。請重新儲存設定後再試。"
        }
        // 對話框空間有限，只取最後幾行
        return trimmed.lines().takeLast(4).joinToString("\n")
    }
}
