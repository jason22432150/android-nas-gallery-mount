package com.nas.gallerymount.media

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import com.nas.gallerymount.domain.ScanProgress
import com.nas.gallerymount.root.RootExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * 有界批次媒體掃描；尊重 .nomedia；不刪除 MediaStore 列。
 *
 * 檔案發現必須走 Root `find`：App 程序受 Scoped Storage 限制，
 * 對 `/storage/emulated/0/DCIM/...` 的 `File.listFiles()` 常為 Permission denied／空結果。
 */
class MediaIndexer(
    context: Context,
    private val rootExecutor: RootExecutor,
) {
    private val appContext = context.applicationContext

    @Volatile
    private var submitEnabled: Boolean = true

    fun stopSubmitting() {
        submitEnabled = false
    }

    fun allowSubmitting() {
        submitEnabled = true
    }

    data class ScanResult(
        val progress: ScanProgress,
        val relativePathPrefix: String,
    )

    suspend fun scanMountPoint(
        mountPoint: String,
        maxFiles: Int = DEFAULT_MAX_FILES,
        onProgress: (ScanProgress) -> Unit = {},
    ): ScanResult = withContext(Dispatchers.IO) {
        allowSubmitting()
        val rootPath = mountPoint.trimEnd('/')

        onProgress(ScanProgress(message = "以 Root 列舉媒體檔（上限 $maxFiles）…"))
        val discoveredPaths = discoverMediaViaRoot(rootPath, maxFiles)
        if (discoveredPaths.isFailure) {
            val err = discoveredPaths.exceptionOrNull()?.message ?: "列舉失敗"
            val p = ScanProgress(message = err)
            onProgress(p)
            return@withContext ScanResult(p, relativePathOf(rootPath))
        }

        val files = discoveredPaths.getOrDefault(emptyList())
        val discovered = files.size
        var submitted = 0
        var indexed = 0
        var failed = 0

        var progress = ScanProgress(
            discovered = discovered,
            message = if (discovered == 0) {
                "Root 列舉結果為 0。請確認掛載點有媒體，且 MediaProvider bind 已完成。"
            } else {
                "發現 $discovered 個媒體檔，開始提交掃描"
            },
        )
        onProgress(progress)

        for (path in files) {
            if (!submitEnabled) {
                progress = progress.copy(
                    message = "已停止提交新掃描（Android 可能仍處理已提交項目）",
                )
                onProgress(progress)
                break
            }
            val ok = scanFileAwait(path)
            if (ok) {
                submitted++
            } else {
                failed++
            }
            progress = ScanProgress(
                discovered = discovered,
                submitted = submitted,
                indexed = indexed,
                failed = failed,
                message = "掃描中：$submitted / $discovered",
            )
            onProgress(progress)
        }

        // 目錄級掃描備援（部分 ROM 對整樹較有效）
        if (discovered > 0) {
            rootExecutor.execute(
                "content", "call",
                "--uri", "content://media",
                "--method", "scan_file",
                "--arg", rootPath,
                timeoutMs = 120_000L,
            )
        }

        val prefix = relativePathOf(rootPath)
        indexed = countIndexed(prefix)
        val cappedNote = if (discovered >= maxFiles) {
            "（已達上限 $maxFiles，其餘未提交）"
        } else {
            ""
        }
        progress = ScanProgress(
            discovered = discovered,
            submitted = submitted,
            indexed = indexed,
            failed = failed,
            message = "掃描結束：MediaStore 約 $indexed 筆（relative_path LIKE '$prefix%'）$cappedNote",
        )
        onProgress(progress)
        ScanResult(progress, prefix)
    }

    /**
     * 以 Root find 發現媒體路徑（避開 App Scoped Storage）。
     */
    private suspend fun discoverMediaViaRoot(
        mountPoint: String,
        maxFiles: Int,
    ): Result<List<String>> {
        // 路徑已由掛載白名單驗證；此處再拒單引號以免破壞 sh -c
        if (mountPoint.contains('\'')) {
            return Result.failure(IllegalArgumentException("掛載路徑不可含單引號"))
        }
        val nameTests = MEDIA_EXT.joinToString(" -o ") { ext ->
            "-iname '*${ext.lowercase()}'"
        }
        // Immich 結構約 mount/uuid/xx/yy/file，限制深度避免整庫拖死
        val script = buildString {
            append("/system/bin/find '$mountPoint' -maxdepth 6 ")
            append("\\( -name '.nomedia' -prune \\) -o ")
            append("\\( -type f \\( $nameTests \\) -print \\) ")
            append("2>/dev/null | /system/bin/head -n $maxFiles")
        }
        val result = rootExecutor.execute(
            "/system/bin/sh", "-c", script,
            timeoutMs = 180_000L,
        )
        if (!result.success && result.stdout.isBlank()) {
            return Result.failure(
                IllegalStateException("Root find 失敗：${result.combined()}"),
            )
        }
        val paths = result.stdout.lines()
            .map { it.trim() }
            .filter { it.startsWith(mountPoint) && File(it).name.isNotEmpty() }
            .distinct()
        return Result.success(paths)
    }

    fun countIndexed(relativePathPrefix: String): Int {
        val images = queryCount(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            relativePathPrefix,
        )
        val videos = queryCount(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            relativePathPrefix,
        )
        return images + videos
    }

    /**
     * 斷線後不刪索引；僅回報目前可讀數量供 UI 標示。
     */
    fun describeAvailability(relativePathPrefix: String, filesExpectedReadable: Boolean): String {
        val count = countIndexed(relativePathPrefix)
        return if (!filesExpectedReadable) {
            "MediaStore 仍有約 $count 筆索引，檔案目前不可用（已斷線）。重掛後可修復。"
        } else {
            "MediaStore 約 $count 筆索引，檔案應可讀。"
        }
    }

    private fun queryCount(uri: Uri, relativePathPrefix: String): Int {
        val prefix = relativePathPrefix.trimEnd('/') + "/"
        return try {
            appContext.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("$prefix%"),
                null,
            )?.use { it.count } ?: 0
        } catch (_: SecurityException) {
            -1
        } catch (_: Exception) {
            0
        }
    }

    private suspend fun scanFileAwait(path: String): Boolean =
        suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(
                appContext,
                arrayOf(path),
                null,
            ) { _, uri ->
                if (cont.isActive) cont.resume(uri != null)
            }
        }

    fun relativePathOf(absolutePath: String): String {
        val prefix = "/storage/emulated/0/"
        val path = absolutePath.trimEnd('/')
        return if (path.startsWith(prefix)) {
            path.removePrefix(prefix).trim('/') + "/"
        } else {
            path.trim('/') + "/"
        }
    }

    companion object {
        const val DEFAULT_MAX_FILES = 200
        private val MEDIA_EXT = listOf(
            ".jpg", ".jpeg", ".png", ".webp", ".heic", ".gif",
            ".mp4", ".mkv", ".webm", ".3gp", ".mov",
        )
    }
}
