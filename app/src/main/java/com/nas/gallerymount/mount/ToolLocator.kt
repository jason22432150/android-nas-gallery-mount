package com.nas.gallerymount.mount

import android.content.Context
import com.nas.gallerymount.domain.ToolStatus
import com.nas.gallerymount.root.RootExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 定位 APK 隨附的 rclone / fusermount3（jniLibs 以 lib*.so 命名）。
 */
class ToolLocator(context: Context) {
    private val appContext = context.applicationContext

    fun rclonePath(): String? = findNative("librclone.so")
    fun fusermountPath(): String? = findNative("libfusermount3.so")

    fun nativeLibraryDir(): String =
        appContext.applicationInfo.nativeLibraryDir

    suspend fun verify(rootExecutor: RootExecutor): ToolStatus = withContext(Dispatchers.IO) {
        val rclone = rclonePath()
        val fuse = fusermountPath()
        if (rclone == null) {
            return@withContext ToolStatus(
                rclonePath = null,
                fusermountPath = fuse,
                message = "找不到 librclone.so。請執行 scripts/fetch-native-tools.sh 後重新建置。",
            )
        }
        if (fuse == null) {
            return@withContext ToolStatus(
                rclonePath = rclone,
                fusermountPath = null,
                message = "找不到 libfusermount3.so。請執行 scripts/fetch-native-tools.sh 後重新建置。",
            )
        }
        val version = rootExecutor.execute(rclone, "version", timeoutMs = 15_000L)
        val versionLine = version.stdout.lineSequence().firstOrNull().orEmpty()
        if (!version.success) {
            return@withContext ToolStatus(
                rclonePath = rclone,
                fusermountPath = fuse,
                message = "rclone 無法執行：${version.combined()}",
            )
        }
        ToolStatus(
            rclonePath = rclone,
            fusermountPath = fuse,
            rcloneVersion = versionLine,
            ok = true,
            message = "工具就緒：$versionLine",
        )
    }

    private fun findNative(name: String): String? {
        val dir = File(nativeLibraryDir())
        val direct = File(dir, name)
        if (direct.exists()) return direct.absolutePath
        // 部分裝置會拆到子目錄
        return dir.walkTopDown().maxDepth(2)
            .firstOrNull { it.isFile && it.name == name }
            ?.absolutePath
    }
}
