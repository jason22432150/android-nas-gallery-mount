package com.nas.gallerymount.mount

import com.nas.gallerymount.config.ConfigStore
import com.nas.gallerymount.domain.MountSnapshot
import com.nas.gallerymount.domain.SmbConfig
import com.nas.gallerymount.probe.DeviceProbe
import com.nas.gallerymount.root.MountInfoParser
import com.nas.gallerymount.root.RootExecutor
import java.io.File

/**
 * 管理 rclone FUSE 掛載與 MediaProvider bind mount。
 */
class MountManager(
    private val rootExecutor: RootExecutor,
    private val toolLocator: ToolLocator,
    private val configStore: ConfigStore,
    private val deviceProbe: DeviceProbe,
) {
    data class ActionResult(
        val ok: Boolean,
        val message: String,
        val snapshot: MountSnapshot = MountSnapshot(),
    )

    /**
     * 驗證掛載點位於允許的共用儲存前綴下，防止路徑跳脫。
     */
    fun validateMountPoint(path: String): String? {
        val normalized = path.trim()
        if (normalized.isEmpty()) return "掛載點不可為空"
        if (!normalized.startsWith("/storage/emulated/0/")) {
            return "掛載點必須位於 /storage/emulated/0/ 之下"
        }
        if (normalized.contains("..")) return "掛載點不可包含 .."
        val forbidden = listOf("/data/", "/system/", "/proc/", "/dev/")
        if (forbidden.any { normalized.contains(it) }) {
            return "掛載點路徑不在允許範圍"
        }
        return null
    }

    suspend fun mountFuse(config: SmbConfig): ActionResult {
        validateMountPoint(config.mountPoint)?.let {
            return ActionResult(false, it)
        }
        configStore.save(config)
        val rclone = toolLocator.rclonePath()
            ?: return ActionResult(false, "rclone 不存在")
        val fuseBin = toolLocator.fusermountPath()
            ?: return ActionResult(false, "fusermount3 不存在")
        val mountPoint = config.mountPoint.trimEnd('/')
        val conf = configStore.rcloneConfigFile().absolutePath
        val remote = config.remotePath()

        // 若已正確掛載則重用
        val existing = readSelfMount(mountPoint)
        if (existing != null && existing.fsType.contains("fuse", ignoreCase = true)) {
            return ActionResult(
                ok = true,
                message = "已存在 rclone 掛載，重用中",
                snapshot = MountSnapshot(
                    fuseMounted = true,
                    mountPoint = mountPoint,
                    remotePath = remote,
                ),
            )
        }

        val mkdir = rootExecutor.executeInGlobalMountNs(
            "/system/bin/mkdir", "-p", mountPoint,
        )
        if (!mkdir.success) {
            return ActionResult(false, "無法建立掛載點：${mkdir.combined()}")
        }

        // PATH／LD_LIBRARY_PATH 指向 jniLibs，讓 rclone 找得到 fusermount3 與 libfuse3
        val binDir = File(fuseBin).parent
        val pathEnv = "$binDir:/system/bin:/system/xbin"
        val mountCmd = arrayOf(
            "/system/bin/env",
            "PATH=$pathEnv",
            "LD_LIBRARY_PATH=$binDir",
            rclone, "mount", remote, mountPoint,
            "--config", conf,
            "--read-only",
            "--allow-other",
            "--daemon",
            "--daemon-wait", "20s",
        )
        val mount = rootExecutor.executeInGlobalMountNs(*mountCmd, timeoutMs = 60_000L)
        if (!mount.success) {
            return ActionResult(false, "rclone mount 失敗：${mount.combined()}")
        }

        val verify = readSelfMount(mountPoint)
            ?: return ActionResult(false, "掛載後 mountinfo 找不到掛載點")
        if (!verify.fsType.contains("fuse", ignoreCase = true)) {
            return ActionResult(false, "掛載類型異常：${verify.fsType}")
        }
        val ls = rootExecutor.executeInGlobalMountNs("/system/bin/ls", "-A", mountPoint)
        return ActionResult(
            ok = true,
            message = "FUSE 已掛載（${if (ls.success) "可列出內容" else "列出警告：${ls.combined()}"}）",
            snapshot = MountSnapshot(
                fuseMounted = true,
                mountPoint = mountPoint,
                remotePath = remote,
            ),
        )
    }

    suspend fun bindMediaProvider(config: SmbConfig): ActionResult {
        validateMountPoint(config.mountPoint)?.let {
            return ActionResult(false, it)
        }
        val provider = deviceProbe.resolveMediaProvider()
            ?: return ActionResult(false, "無法解析唯一 MediaProvider PID")
        val mountPoint = config.mountPoint.trimEnd('/')
        val mpPath = deviceProbe.toMediaProviderPath(mountPoint)

        // 確認掃描器在 /mnt/user/... 視圖看得到 rclone
        val lsSrc = rootExecutor.executeInGlobalMountNs(
            "/system/bin/nsenter", "-t", provider.pid.toString(), "-m", "--",
            "/system/bin/ls", "-A", mpPath,
            timeoutMs = 15_000L,
        )
        if (!lsSrc.success) {
            return ActionResult(
                false,
                "MediaProvider 看不到 rclone 來源 $mpPath：${lsSrc.combined()}",
            )
        }

        // 若 /storage/... 在 MediaProvider ns 已是 fuse 則重用
        val existingTarget = rootExecutor.execute(
            "/system/bin/grep", "-F", " $mountPoint ", "/proc/${provider.pid}/mountinfo",
        )
        val alreadyBound = existingTarget.success &&
            existingTarget.stdout.contains("fuse", ignoreCase = true)
        if (!alreadyBound) {
            val bind = rootExecutor.executeInGlobalMountNs(
                "/system/bin/nsenter", "-t", provider.pid.toString(), "-m", "--",
                "/system/bin/mount", "--bind", mpPath, mountPoint,
                timeoutMs = 20_000L,
            )
            if (!bind.success) {
                return ActionResult(false, "bind mount 失敗：${bind.combined()}")
            }
        }

        // 必須在 MediaProvider ns 內對 /storage/... 路徑驗證（這才是掃描器實際用的路徑）
        val verifyLs = rootExecutor.executeInGlobalMountNs(
            "/system/bin/nsenter", "-t", provider.pid.toString(), "-m", "--",
            "/system/bin/ls", "-A", mountPoint,
            timeoutMs = 15_000L,
        )
        if (!verifyLs.success || verifyLs.stdout.trim().isEmpty()) {
            return ActionResult(
                false,
                "bind 後 MediaProvider 的 $mountPoint 仍為空：" +
                    verifyLs.combined().ifBlank { "（無輸出）" },
            )
        }
        val mi = rootExecutor.execute(
            "/system/bin/grep", "-F", " $mountPoint ", "/proc/${provider.pid}/mountinfo",
        )
        if (!mi.success || !mi.stdout.contains("fuse", ignoreCase = true)) {
            return ActionResult(
                false,
                "bind 後 mountinfo 未見 fuse 於 $mountPoint",
            )
        }
        return ActionResult(
            ok = true,
            message = if (alreadyBound) {
                "MediaProvider bind 已存在，重用中"
            } else {
                "MediaProvider bind 完成（掃描器可見 ${verifyLs.stdout.lines().size} 項）"
            },
            snapshot = MountSnapshot(
                fuseMounted = true,
                bindMounted = true,
                mediaProviderPid = provider.pid,
                mountPoint = mountPoint,
                remotePath = config.remotePath(),
            ),
        )
    }

    suspend fun unmountAll(config: SmbConfig, previousPid: Int?): ActionResult {
        val mountPoint = config.mountPoint.trimEnd('/')
        val messages = mutableListOf<String>()

        // 1) 移除 MediaProvider bind
        val provider = deviceProbe.resolveMediaProvider()
        val pid = provider?.pid ?: previousPid
        if (pid != null) {
            val umountBind = rootExecutor.executeInGlobalMountNs(
                "/system/bin/nsenter", "-t", pid.toString(), "-m", "--",
                "/system/bin/umount", mountPoint,
                timeoutMs = 20_000L,
            )
            if (!umountBind.success) {
                val busy = umountBind.combined().contains("busy", ignoreCase = true) ||
                    umountBind.combined().contains("Device or resource busy", ignoreCase = true)
                messages += if (busy) {
                    "掃描器 bind 卸載忙碌中，請稍後重試：${umountBind.combined()}"
                } else {
                    "掃描器 bind 卸載：${umountBind.combined()}"
                }
                if (busy) {
                    return ActionResult(false, messages.joinToString("\n"))
                }
            } else {
                messages += "已移除 MediaProvider bind"
            }
        } else {
            messages += "略過 bind 卸載（無 MediaProvider PID）"
        }

        // 2) fusermount3 -u
        val fuseBin = toolLocator.fusermountPath()
        if (fuseBin != null) {
            val libDir = File(fuseBin).parent
            val umountFuse = rootExecutor.executeInGlobalMountNs(
                "/system/bin/env",
                "LD_LIBRARY_PATH=$libDir",
                fuseBin, "-u", mountPoint,
                timeoutMs = 30_000L,
            )
            if (!umountFuse.success) {
                // 備援 umount
                val fallback = rootExecutor.executeInGlobalMountNs(
                    "/system/bin/umount", mountPoint,
                    timeoutMs = 20_000L,
                )
                if (!fallback.success) {
                    messages += "rclone 卸載失敗：${umountFuse.combined()}"
                    return ActionResult(false, messages.joinToString("\n"))
                }
            }
            messages += "已卸載 rclone FUSE"
        }

        // 3) 雙視圖核對
        val selfLeft = readSelfMount(mountPoint)
        val pidNow = deviceProbe.resolveMediaProvider()?.pid
        val mpStill = if (pidNow != null) {
            rootExecutor.execute(
                "/system/bin/grep", "-F", " $mountPoint ", "/proc/$pidNow/mountinfo",
            ).success
        } else {
            false
        }
        if (selfLeft != null || mpStill) {
            messages += "卸載後仍偵測到殘留掛載，請重試"
            return ActionResult(
                false,
                messages.joinToString("\n"),
                MountSnapshot(
                    fuseMounted = selfLeft != null,
                    bindMounted = mpStill,
                    mediaProviderPid = pidNow,
                    mountPoint = mountPoint,
                    remotePath = config.remotePath(),
                ),
            )
        }

        return ActionResult(
            ok = true,
            message = messages.joinToString("\n"),
            snapshot = MountSnapshot(
                fuseMounted = false,
                bindMounted = false,
                mountPoint = mountPoint,
                remotePath = config.remotePath(),
            ),
        )
    }

    suspend fun refreshSnapshot(config: SmbConfig): MountSnapshot {
        val mountPoint = config.mountPoint.trimEnd('/')
        val fuse = readSelfMount(mountPoint) != null
        val provider = deviceProbe.resolveMediaProvider()
        val bind = if (provider != null) {
            rootExecutor.execute(
                "/system/bin/grep", "-F", " $mountPoint ",
                "/proc/${provider.pid}/mountinfo",
            ).success
        } else {
            false
        }
        return MountSnapshot(
            fuseMounted = fuse,
            bindMounted = bind,
            mediaProviderPid = provider?.pid,
            mountPoint = mountPoint,
            remotePath = config.remotePath(),
        )
    }

    private suspend fun readSelfMount(mountPoint: String) =
        rootExecutor.execute("/system/bin/cat", "/proc/self/mountinfo").let { result ->
            if (!result.success) null
            else MountInfoParser.findExactMountPoint(result.stdout, mountPoint)
        }
}
