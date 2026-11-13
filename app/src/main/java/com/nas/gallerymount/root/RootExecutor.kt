package com.nas.gallerymount.root

import com.nas.gallerymount.domain.RootStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 以 Magisk `su` 執行白名單命令。參數一律以單引號包起來，避免注入。
 */
class RootExecutor(
    private val defaultTimeoutMs: Long = 30_000L,
) {
    data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val success: Boolean get() = exitCode == 0
        fun combined(): String = buildString {
            if (stdout.isNotBlank()) append(stdout.trim())
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(stderr.trim())
            }
        }
    }

    /**
     * 檢查 Root：`su -c id` 必須含 uid=0(root)。
     */
    suspend fun checkRoot(): RootStatus = withContext(Dispatchers.IO) {
        if (!suBinaryExists()) {
            return@withContext RootStatus(
                available = false,
                message = "找不到 su。請確認已安裝 Magisk，並在「超級使用者」授權本 App。",
            )
        }
        return@withContext try {
            val result = executeRaw("id", timeoutMs = 8_000L)
            val line = result.combined()
            val ok = result.success && line.contains("uid=0")
            RootStatus(
                available = ok,
                uidLine = line.lineSequence().firstOrNull().orEmpty(),
                message = if (ok) {
                    "Root 已授權"
                } else {
                    "Root 未授權或逾時。請到 Magisk「超級使用者」允許本 App，再按重新檢查。"
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RootStatus(
                available = false,
                message = "Root 檢查失敗：${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    /**
     * 在全域 mount namespace 執行（對齊規格的 `su -M`）。
     * 若裝置不支援 `-M`，退回一般 `su -c`。
     */
    suspend fun executeInGlobalMountNs(
        vararg argv: String,
        timeoutMs: Long = defaultTimeoutMs,
    ): ExecResult = withContext(Dispatchers.IO) {
        require(argv.isNotEmpty()) { "命令不可為空" }
        val script = buildShellScript(argv)
        val first = try {
            executeSuScript(script, useGlobalMountNs = true, timeoutMs = timeoutMs)
        } catch (_: Exception) {
            executeSuScript(script, useGlobalMountNs = false, timeoutMs = timeoutMs)
        }
        // -M 若被拒但 exit 非 0，再試一次無 -M
        if (!first.success && first.stderr.contains("-M", ignoreCase = true)) {
            executeSuScript(script, useGlobalMountNs = false, timeoutMs = timeoutMs)
        } else {
            first
        }
    }

    suspend fun execute(
        vararg argv: String,
        timeoutMs: Long = defaultTimeoutMs,
    ): ExecResult = withContext(Dispatchers.IO) {
        require(argv.isNotEmpty()) { "命令不可為空" }
        executeSuScript(buildShellScript(argv), useGlobalMountNs = false, timeoutMs = timeoutMs)
    }

    private fun executeRaw(command: String, timeoutMs: Long): ExecResult {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(false)
            .start()
        return waitFor(process, timeoutMs)
    }

    private fun executeSuScript(
        script: String,
        useGlobalMountNs: Boolean,
        timeoutMs: Long,
    ): ExecResult {
        val args = if (useGlobalMountNs) {
            listOf("su", "-M", "-c", script)
        } else {
            listOf("su", "-c", script)
        }
        val process = ProcessBuilder(args).start()
        return waitFor(process, timeoutMs)
    }

    private fun waitFor(process: Process, timeoutMs: Long): ExecResult {
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return ExecResult(-1, stdout, stderr + "\n命令逾時（${timeoutMs}ms）")
        }
        return ExecResult(process.exitValue(), stdout, stderr)
    }

    private fun suBinaryExists(): Boolean {
        val candidates = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "su")
        return candidates.any { path ->
            if (path == "su") {
                try {
                    val p = ProcessBuilder("which", "su").start()
                    p.waitFor(2, TimeUnit.SECONDS)
                    p.exitValue() == 0
                } catch (_: Exception) {
                    false
                }
            } else {
                File(path).exists()
            }
        } || File("/system/bin/su").canExecute() || tryOpenSu()
    }

    private fun tryOpenSu(): Boolean = try {
        val p = ProcessBuilder("su", "-c", "echo ok").start()
        p.waitFor(2, TimeUnit.SECONDS)
        true
    } catch (_: Exception) {
        false
    }

    companion object {
        /** 將 argv 組成可安全傳遞給 `su -c` 的腳本。 */
        fun buildShellScript(argv: Array<out String>): String =
            argv.joinToString(" ") { shellQuote(it) }

        fun buildShellScript(argv: List<String>): String =
            argv.joinToString(" ") { shellQuote(it) }

        /** POSIX 單引號跳脫。 */
        fun shellQuote(value: String): String {
            if (value.isEmpty()) return "''"
            return "'" + value.replace("'", "'\\''") + "'"
        }
    }
}
