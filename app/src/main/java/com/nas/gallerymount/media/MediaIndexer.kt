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
 * 有界批次媒體掃描；尊重 .nomedia。
 * 清除索引僅限本 App 掛載路徑（見 [clearIndexedForMount]），預設斷線不刪列。
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

    data class ClearIndexResult(
        val ok: Boolean,
        val message: String,
        /** 清除前筆數；查詢失敗為 -1 */
        val before: Int = -1,
        /** 清除後筆數；未執行／失敗為 -1 */
        val after: Int = -1,
        val deleted: Int = 0,
    )

    suspend fun scanMountPoint(
        mountPoint: String,
        maxFiles: Int = DEFAULT_MAX_FILES,
        onProgress: (ScanProgress) -> Unit = {},
    ): ScanResult = withContext(Dispatchers.IO) {
        allowSubmitting()
        val rootPath = mountPoint.trimEnd('/')

        var cancelled = false
        onProgress(
            ScanProgress(
                percent = 0,
                message = "以 Root 列舉媒體檔（上限 $maxFiles）…",
            ),
        )
        val discoveredPaths = discoverMediaViaRoot(rootPath, maxFiles)
        if (discoveredPaths.isFailure) {
            val err = discoveredPaths.exceptionOrNull()?.message ?: "列舉失敗"
            val p = ScanProgress(message = err, percent = 0)
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
            percent = if (discovered == 0) 100 else 0,
            message = if (discovered == 0) {
                "Root 列舉結果為 0。請確認掛載點有媒體，且 MediaProvider bind 已完成。"
            } else {
                "發現 $discovered 個媒體檔，開始提交掃描"
            },
        )
        onProgress(progress)

        for (path in files) {
            if (!submitEnabled) {
                cancelled = true
                progress = progress.copy(
                    cancelled = true,
                    percent = ScanProgress.calcPercent(discovered, submitted, failed),
                    message = "已中斷掃描（已提交 $submitted／$discovered；Android 可能仍處理已提交項目）",
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
            val pct = ScanProgress.calcPercent(discovered, submitted, failed)
            progress = ScanProgress(
                discovered = discovered,
                submitted = submitted,
                indexed = indexed,
                failed = failed,
                percent = pct,
                message = "掃描中：$submitted / $discovered（$pct%）",
            )
            onProgress(progress)
        }

        // 目錄級掃描備援（中斷時略過）
        if (discovered > 0 && !cancelled) {
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
        val endPct = if (cancelled) {
            ScanProgress.calcPercent(discovered, submitted, failed)
        } else {
            100
        }
        progress = ScanProgress(
            discovered = discovered,
            submitted = submitted,
            indexed = indexed,
            failed = failed,
            percent = endPct,
            cancelled = cancelled,
            message = buildString {
                if (cancelled) append("掃描已中斷。") else append("掃描結束。")
                append("MediaStore 約 $indexed 筆（relative_path LIKE '$prefix%'）$cappedNote")
            },
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
        // 深層媒體庫常為多層 hash／uuid 目錄，限制深度避免整庫拖死
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

    /**
     * 清除本掛載點在 MediaStore 的圖片／影片列。
     *
     * **必須先斷線（FUSE／bind 皆卸載）**：MediaProvider 的 delete 可能連帶刪實體檔；
     * 卸載後路徑上已無檔案，通常只清 DB 列，不會動到 SMB 原檔。
     */
    suspend fun clearIndexedForMount(
        mountPoint: String,
        fuseMounted: Boolean,
        bindMounted: Boolean,
    ): ClearIndexResult = withContext(Dispatchers.IO) {
        if (fuseMounted || bindMounted) {
            return@withContext ClearIndexResult(
                ok = false,
                message = "請先斷線（卸載 FUSE／bind）後再清除索引，以免 MediaProvider 嘗試刪除掛載中的檔案。",
            )
        }
        val prefix = relativePathOf(mountPoint)
        val safety = IndexPathSafety.validateClearableRelativePath(prefix)
        if (safety != null) {
            return@withContext ClearIndexResult(false, safety)
        }
        val before = countIndexed(prefix)
        if (before < 0) {
            return@withContext ClearIndexResult(
                ok = false,
                message = "無法查詢 MediaStore（權限不足）。請授予讀取媒體權限後重試。",
                before = -1,
                after = -1,
            )
        }
        if (before == 0) {
            return@withContext ClearIndexResult(
                ok = true,
                message = "沒有可清除的索引（relative_path LIKE '$prefix%'）",
                before = 0,
                after = 0,
                deleted = 0,
            )
        }

        val like = IndexPathSafety.likeLiteral(prefix)
        // ESCAPE '\'：路徑中的 _/% 已跳脫（安全檢查亦禁止這些字元）
        val whereImages = "relative_path LIKE '$like' ESCAPE '\\'"
        val whereVideos = whereImages
        val whereFiles =
            "relative_path LIKE '$like' ESCAPE '\\' AND (mime_type LIKE 'image/%' OR mime_type LIKE 'video/%')"

        val imagesDeleted = deleteViaRoot(
            uri = "content://media/external/images/media",
            where = whereImages,
        )
        val videosDeleted = deleteViaRoot(
            uri = "content://media/external/video/media",
            where = whereVideos,
        )
        val filesDeleted = deleteViaRoot(
            uri = "content://media/external/file",
            where = whereFiles,
        )
        val resolverDeleted = deleteViaResolver(prefix)

        val afterRaw = countIndexed(prefix)
        if (afterRaw < 0) {
            return@withContext ClearIndexResult(
                ok = false,
                message = "已嘗試清除，但事後無法查詢 MediaStore 確認結果。",
                before = before,
                after = -1,
                deleted = 0,
            )
        }
        val after = afterRaw
        val deletedApprox = (before - after).coerceAtLeast(0)
        val rootOk = imagesDeleted.success || videosDeleted.success ||
            filesDeleted.success || resolverDeleted > 0
        val detail = buildString {
            append("前 $before → 後 $after；")
            append("root img=${fmtExec(imagesDeleted)} vid=${fmtExec(videosDeleted)} ")
            append("file=${fmtExec(filesDeleted)} resolver=$resolverDeleted")
        }
        return@withContext when {
            after == 0 -> ClearIndexResult(
                ok = true,
                message = "已清除 MediaStore 約 $deletedApprox 筆（$prefix）。$detail",
                before = before,
                after = 0,
                deleted = deletedApprox,
            )
            deletedApprox > 0 -> ClearIndexResult(
                ok = false,
                message = "部分清除：$deletedApprox 筆已刪，仍剩 $after 筆。$detail",
                before = before,
                after = after,
                deleted = deletedApprox,
            )
            else -> ClearIndexResult(
                ok = false,
                message = "清除失敗（rootOk=$rootOk），索引仍約 $after 筆。$detail",
                before = before,
                after = after,
                deleted = 0,
            )
        }
    }

    private fun fmtExec(r: RootExecutor.ExecResult): String =
        r.combined().ifBlank { "exit=${r.exitCode}" }

    private suspend fun deleteViaRoot(uri: String, where: String): RootExecutor.ExecResult {
        return rootExecutor.execute(
            "content", "delete",
            "--uri", uri,
            "--where", where,
            timeoutMs = 120_000L,
        )
    }

    private fun deleteViaResolver(relativePathPrefix: String): Int {
        val prefix = relativePathPrefix.trimEnd('/') + "/"
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("$prefix%")
        var total = 0
        total += deleteResolverUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, selection, args)
        total += deleteResolverUri(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, selection, args)
        return total
    }

    private fun deleteResolverUri(uri: Uri, selection: String, args: Array<String>): Int {
        return try {
            appContext.contentResolver.delete(uri, selection, args)
        } catch (_: SecurityException) {
            0
        } catch (_: Exception) {
            0
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
