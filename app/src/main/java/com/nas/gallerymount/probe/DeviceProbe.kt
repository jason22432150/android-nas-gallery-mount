package com.nas.gallerymount.probe

import android.os.Build
import com.nas.gallerymount.domain.ProbeResult
import com.nas.gallerymount.root.RootExecutor

/**
 * 裝置／ROM 探測。第一版以 Redmi 7 + LineageOS 21 為已知 profile；
 * 不符時盡力探測 MediaProvider，失敗則明確拒絕。
 */
class DeviceProbe(
    private val rootExecutor: RootExecutor,
) {
    data class MediaProviderInfo(
        val processName: String,
        val pid: Int,
    )

    suspend fun probe(): ProbeResult {
        val profile = detectProfileName()
        val provider = resolveMediaProvider()
            ?: return ProbeResult(
                supported = false,
                profileName = profile,
                reason = "找不到唯一的 MediaProvider 程序。請確認 ROM 媒體服務狀態。",
            )
        return ProbeResult(
            supported = true,
            profileName = profile,
            mediaProviderProcess = "${provider.processName} (pid=${provider.pid})",
            reason = if (profile == PROFILE_REDMI7_LOS21) {
                "符合首發驗收 profile"
            } else {
                "非首發驗收機（$profile），已探測到 MediaProvider；行為可能因 OEM 而異"
            },
        )
    }

    suspend fun resolveMediaProvider(): MediaProviderInfo? {
        for (name in CANDIDATE_PROCESSES) {
            val result = rootExecutor.execute("/system/bin/pidof", name, timeoutMs = 5_000L)
            if (!result.success) continue
            val pids = result.stdout.trim()
                .split(Regex("\\s+"))
                .mapNotNull { it.toIntOrNull() }
                .distinct()
            when {
                pids.isEmpty() -> continue
                pids.size > 1 -> {
                    // 規格：多 PID 不可整串交給 nsenter
                    return null
                }
                else -> return MediaProviderInfo(name, pids.first())
            }
        }
        return null
    }

    /**
     * 將共用儲存路徑轉成 MediaProvider 常見的 /mnt/user/0/emulated/0/... 視圖。
     */
    fun toMediaProviderPath(emulatedPath: String): String {
        val prefix = "/storage/emulated/0"
        return if (emulatedPath.startsWith(prefix)) {
            "/mnt/user/0/emulated/0" + emulatedPath.removePrefix(prefix)
        } else {
            emulatedPath
        }
    }

    fun detectProfileName(): String {
        val model = Build.MODEL.orEmpty()
        val device = Build.DEVICE.orEmpty()
        val display = Build.DISPLAY.orEmpty()
        val isRedmi7 = model.contains("Redmi 7", ignoreCase = true) ||
            device.equals("onclite", ignoreCase = true) ||
            device.equals("onc", ignoreCase = true)
        val isLos21 = display.contains("lineage_21", ignoreCase = true) ||
            display.contains("lineage-21", ignoreCase = true) ||
            (Build.VERSION.SDK_INT == 34 && display.contains("lineage", ignoreCase = true))
        return when {
            isRedmi7 && isLos21 -> PROFILE_REDMI7_LOS21
            isRedmi7 -> "Redmi7_other"
            else -> "${Build.MANUFACTURER}_${model}_API${Build.VERSION.SDK_INT}"
        }
    }

    companion object {
        const val PROFILE_REDMI7_LOS21 = "Redmi7Lineage21"
        val CANDIDATE_PROCESSES = listOf(
            "com.android.providers.media.module",
            "com.google.android.providers.media.module",
            "com.android.providers.media",
        )
    }
}
