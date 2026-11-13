package com.nas.gallerymount.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nas.gallerymount.GalleryMountApp
import com.nas.gallerymount.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 接收 ADB broadcast，對應 App 按鈕操作，結果寫入 files/debug/。
 */
class CommandReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val app = context.applicationContext as? GalleryMountApp ?: return
        val pending = goAsync()
        scope.launch {
            try {
                val result = dispatch(app, action, intent)
                writeResult(app, result)
                AppLogger.i("Cmd", "$action → $result")
            } catch (e: Exception) {
                val msg = "ERROR: ${e.message ?: e.javaClass.simpleName}"
                writeResult(app, msg)
                AppLogger.e("Cmd", "$action → $msg")
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun dispatch(
        app: GalleryMountApp,
        action: String,
        intent: Intent,
    ): String {
        val session = app.sessionController
        return when (action) {
            AppCommands.ACTION_RECHECK -> {
                session.refreshRootAndTools()
                session.refreshStatus()
                dumpStatus(app)
                "OK recheck phase=${session.state.value.phase}"
            }
            AppCommands.ACTION_SAVE -> {
                session.persistConfig()
                "OK save ${session.state.value.statusMessage}"
            }
            AppCommands.ACTION_TEST -> {
                session.testConnection()
                "OK test ${session.state.value.statusMessage}"
            }
            AppCommands.ACTION_LIST -> {
                session.listSource()
                "OK list count=${session.state.value.sourceListing.size}"
            }
            AppCommands.ACTION_CONNECT -> {
                session.connectAndScan()
                dumpStatus(app)
                "OK connect phase=${session.state.value.phase} scan%=${session.state.value.scan.percent}"
            }
            AppCommands.ACTION_DISCONNECT -> {
                session.disconnect()
                dumpStatus(app)
                "OK disconnect phase=${session.state.value.phase}"
            }
            AppCommands.ACTION_REFRESH -> {
                session.refreshStatus()
                dumpStatus(app)
                "OK refresh ${session.state.value.statusMessage}"
            }
            AppCommands.ACTION_CANCEL_SCAN -> {
                session.cancelScan()
                "OK cancel_scan"
            }
            AppCommands.ACTION_CLEAR_INDEX -> {
                session.clearMediaIndex()
                val s = session.state.value
                "OK clear_index availability=${s.indexAvailability} ${s.statusMessage}"
            }
            AppCommands.ACTION_DUMP_STATUS -> {
                dumpStatus(app)
                "OK status → ${AppCommands.STATUS_RELATIVE}"
            }
            AppCommands.ACTION_DUMP_LOGS -> {
                AppLogger.dumpToFile(app)
                "OK logs → ${AppCommands.LOG_RELATIVE} lines=${AppLogger.snapshot().size}"
            }
            AppCommands.ACTION_CLEAR_LOGS -> {
                AppLogger.clear()
                "OK logs cleared"
            }
            AppCommands.ACTION_SET_SHOW_LOGS -> {
                val show = intent.getBooleanExtra(AppCommands.EXTRA_SHOW, true)
                session.setShowLogs(show)
                "OK show_logs=$show"
            }
            AppCommands.ACTION_SET_MOUNT -> {
                val path = intent.getStringExtra(AppCommands.EXTRA_PATH)
                    ?: return "ERROR missing path"
                session.updateConfig(session.state.value.config.copy(mountPoint = path))
                "OK mount=$path"
            }
            AppCommands.ACTION_SET_SMB -> {
                val cfg = session.state.value.config
                val next = cfg.copy(
                    host = intent.getStringExtra(AppCommands.EXTRA_HOST) ?: cfg.host,
                    port = intent.getIntExtra(AppCommands.EXTRA_PORT, cfg.port),
                    username = intent.getStringExtra(AppCommands.EXTRA_USER) ?: cfg.username,
                    password = intent.getStringExtra(AppCommands.EXTRA_PASSWORD) ?: cfg.password,
                    domain = intent.getStringExtra(AppCommands.EXTRA_DOMAIN) ?: cfg.domain,
                    shareName = intent.getStringExtra(AppCommands.EXTRA_SHARE) ?: cfg.shareName,
                    subPath = intent.getStringExtra(AppCommands.EXTRA_SUBPATH) ?: cfg.subPath,
                    enableGooglePhotosIntegration = if (intent.hasExtra(AppCommands.EXTRA_GPHOTOS)) {
                        intent.getBooleanExtra(AppCommands.EXTRA_GPHOTOS, true)
                    } else {
                        cfg.enableGooglePhotosIntegration
                    },
                )
                session.updateConfig(next)
                "OK smb host=${next.host} share=${next.shareName} sub=${next.subPath}"
            }
            else -> "ERROR unknown action=$action"
        }
    }

    private fun dumpStatus(app: GalleryMountApp) {
        val s = app.sessionController.state.value
        val text = buildString {
            appendLine("phase=${s.phase}")
            appendLine("busy=${s.busy}")
            appendLine("status=${s.statusMessage}")
            appendLine("root=${s.root.available} ${s.root.message}")
            appendLine("tools=${s.tools.ok} ${s.tools.message}")
            appendLine("fuse=${s.mount.fuseMounted} bind=${s.mount.bindMounted} pid=${s.mount.mediaProviderPid}")
            appendLine("mountPoint=${s.config.mountPoint}")
            appendLine("remote=${s.config.remotePath()}")
            appendLine(
                "scan discovered=${s.scan.discovered} submitted=${s.scan.submitted} " +
                    "indexed=${s.scan.indexed} failed=${s.scan.failed} percent=${s.scan.percent} " +
                    "cancelled=${s.scan.cancelled}",
            )
            appendLine("indexAvailability=${s.indexAvailability}")
            appendLine("showLogs=${s.showLogs}")
        }
        val dir = File(app.filesDir, "debug").also { it.mkdirs() }
        File(dir, "status.txt").writeText(text)
    }

    private fun writeResult(app: GalleryMountApp, result: String) {
        val dir = File(app.filesDir, "debug").also { it.mkdirs() }
        File(dir, "last_result.txt").writeText(result.trim() + "\n")
    }
}
