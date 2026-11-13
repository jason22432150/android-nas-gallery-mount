package com.nas.gallerymount.mount

import android.content.Context
import java.io.File

/**
 * 在 App 私有目錄建立 `fusermount3`／`rclone` 符號連結。
 * rclone mount 會在 PATH 尋找名為 fusermount3 的執行檔，
 * 但 APK jniLibs 只能以 `lib*.so` 命名打包。
 */
class NativeBinBootstrap(
    context: Context,
    private val toolLocator: ToolLocator,
) {
    private val binDir = File(context.applicationContext.filesDir, "native-bin")

    fun binDir(): File = binDir

    /**
     * 確保 symlink 就緒；失敗時回傳錯誤訊息。
     */
    fun ensure(): String? {
        val rclone = toolLocator.rclonePath()
            ?: return "找不到 librclone.so"
        val fuse = toolLocator.fusermountPath()
            ?: return "找不到 libfusermount3.so"
        return try {
            if (!binDir.exists() && !binDir.mkdirs()) {
                return "無法建立 ${binDir.absolutePath}"
            }
            linkOrCopy("rclone", File(rclone))
            linkOrCopy("fusermount3", File(fuse))
            // 可執行位（symlink 目標已是 755；copy 時需設定）
            File(binDir, "rclone").setExecutable(true, false)
            File(binDir, "fusermount3").setExecutable(true, false)
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    private fun linkOrCopy(name: String, target: File) {
        val dest = File(binDir, name)
        if (dest.exists()) {
            // 目標變更（APK 更新）時重建
            val same = dest.canonicalPath == target.canonicalPath ||
                (dest.isFile && dest.length() == target.length())
            if (same && dest.canExecute()) return
            dest.delete()
        }
        try {
            // Android 上 Runtime.exec("ln -s") 較 symlink API 可靠
            val p = Runtime.getRuntime().exec(
                arrayOf("/system/bin/ln", "-sf", target.absolutePath, dest.absolutePath),
            )
            p.waitFor()
            if (!dest.exists()) {
                target.copyTo(dest, overwrite = true)
            }
        } catch (_: Exception) {
            target.copyTo(dest, overwrite = true)
        }
    }
}
