package com.nas.gallerymount.media

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import com.nas.gallerymount.domain.ScanProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * 有界批次媒體掃描；尊重 .nomedia；不刪除 MediaStore 列。
 */
class MediaIndexer(
    context: Context,
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
        val root = File(mountPoint)
        if (!root.exists()) {
            val p = ScanProgress(message = "掛載點不存在")
            onProgress(p)
            return@withContext ScanResult(p, relativePathOf(mountPoint))
        }

        var discovered = 0
        var submitted = 0
        var indexed = 0
        var skipped = 0
        var failed = 0

        val files = mutableListOf<File>()
        collectMediaFiles(root, files, maxFiles) { skipped++ }
        discovered = files.size

        var progress = ScanProgress(
            discovered = discovered,
            skipped = skipped,
            message = "發現 $discovered 個媒體檔，開始提交掃描",
        )
        onProgress(progress)

        for (file in files) {
            if (!submitEnabled) {
                progress = progress.copy(
                    message = "已停止提交新掃描（Android 可能仍處理已提交項目）",
                )
                onProgress(progress)
                break
            }
            val ok = scanFileAwait(file.absolutePath)
            if (ok) {
                submitted++
                if (isIndexed(file)) indexed++ else {
                    // 掃描回呼成功但查詢可能因權限延遲；先計 submitted
                }
            } else {
                failed++
            }
            progress = ScanProgress(
                discovered = discovered,
                submitted = submitted,
                indexed = indexed,
                skipped = skipped,
                failed = failed,
                message = "掃描中：$submitted / $discovered",
            )
            onProgress(progress)
        }

        // 再查一次 MediaStore 計數
        val prefix = relativePathOf(mountPoint)
        indexed = countIndexed(prefix)
        progress = ScanProgress(
            discovered = discovered,
            submitted = submitted,
            indexed = indexed,
            skipped = skipped,
            failed = failed,
            message = "掃描結束：MediaStore 約 $indexed 筆（relative_path LIKE '$prefix%'）",
        )
        onProgress(progress)
        ScanResult(progress, prefix)
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

    private fun isIndexed(file: File): Boolean {
        val rel = relativePathOf(file.parent ?: return false)
        // 簡化：以路徑字首計數變化較重；此處用 display name 查一次
        return try {
            appContext.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(file.name),
                null,
            )?.use { it.count > 0 } == true ||
                appContext.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                    arrayOf(file.name),
                    null,
                )?.use { it.count > 0 } == true
        } catch (_: Exception) {
            false
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

    private fun collectMediaFiles(
        dir: File,
        out: MutableList<File>,
        maxFiles: Int,
        onSkip: () -> Unit,
    ) {
        if (out.size >= maxFiles) return
        if (!dir.isDirectory) return
        val children = dir.listFiles() ?: return
        // 尊重 .nomedia：此目錄及其子樹略過
        if (children.any { it.name == ".nomedia" }) {
            onSkip()
            return
        }
        for (child in children.sortedBy { it.name }) {
            if (out.size >= maxFiles) return
            when {
                child.isDirectory -> collectMediaFiles(child, out, maxFiles, onSkip)
                child.isFile && isMediaFile(child) -> out += child
                else -> onSkip()
            }
        }
    }

    private fun isMediaFile(file: File): Boolean {
        val name = file.name.lowercase()
        return MEDIA_EXT.any { name.endsWith(it) }
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
