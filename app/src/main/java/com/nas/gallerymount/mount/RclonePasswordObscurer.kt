package com.nas.gallerymount.mount

import java.util.concurrent.TimeUnit

/**
 * 將明文密碼轉成 rclone.conf 所需的 obscure 字串。
 * rclone 會把 `pass =` 當成已 obscure 的值再解碼；寫入明文會出現
 *「base64 decode failed when revealing password」。
 *
 * 以 App 程序直接執行 librclone.so（不經 su），避免密碼進 Root 指令歷史。
 */
class RclonePasswordObscurer(
    private val toolLocator: ToolLocator,
) {
    /**
     * @return obscure 後的字串；空密碼回傳空字串
     * @throws IllegalStateException 工具不存在或 obscure 失敗
     */
    fun obscure(plainPassword: String): String {
        if (plainPassword.isEmpty()) return ""
        val rclone = toolLocator.rclonePath()
            ?: error("找不到 rclone，無法 obscure 密碼")
        val process = ProcessBuilder(rclone, "obscure", plainPassword)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("rclone obscure 逾時")
        }
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.exitValue() != 0 || output.isEmpty()) {
            // 不回傳 output 全文，避免意外含敏感資訊
            error("rclone obscure 失敗（exit=${process.exitValue()}）")
        }
        return output.lineSequence().first().trim()
    }
}
