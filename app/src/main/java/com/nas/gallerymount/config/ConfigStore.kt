package com.nas.gallerymount.config

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.nas.gallerymount.domain.SmbConfig
import java.io.File

/**
 * 持久化 SMB 設定；密碼放在 EncryptedSharedPreferences。
 * 並在 App 私有目錄維護 rclone.conf（權限 0600）。
 */
class ConfigStore(context: Context) {
    private val appContext = context.applicationContext
    /** Keystore 不可用時會降級為未加密 prefs；UI 應顯示警告。 */
    var usingFallbackPrefs: Boolean = false
        private set
    private val prefs: SharedPreferences = createPrefs(appContext)
    private val configDir: File = File(appContext.filesDir, "rclone").also { it.mkdirs() }

    fun load(): SmbConfig = SmbConfig(
        remoteName = prefs.getString(KEY_REMOTE, "nas") ?: "nas",
        host = prefs.getString(KEY_HOST, "") ?: "",
        port = prefs.getInt(KEY_PORT, 445),
        username = prefs.getString(KEY_USER, "") ?: "",
        password = prefs.getString(KEY_PASSWORD, "") ?: "",
        domain = prefs.getString(KEY_DOMAIN, "") ?: "",
        shareName = prefs.getString(KEY_SHARE, "") ?: "",
        subPath = prefs.getString(KEY_SUBPATH, "") ?: "",
        mountPoint = prefs.getString(KEY_MOUNT, SmbConfig.DEFAULT_TEST_MOUNT)
            ?: SmbConfig.DEFAULT_TEST_MOUNT,
        enableGooglePhotosIntegration = prefs.getBoolean(KEY_GPHOTOS, true),
    )

    fun save(config: SmbConfig) {
        prefs.edit()
            .putString(KEY_REMOTE, config.remoteName.trim().ifEmpty { "nas" })
            .putString(KEY_HOST, config.host.trim())
            .putInt(KEY_PORT, config.port)
            .putString(KEY_USER, config.username)
            .putString(KEY_PASSWORD, config.password)
            .putString(KEY_DOMAIN, config.domain)
            .putString(KEY_SHARE, config.shareName.trim())
            .putString(KEY_SUBPATH, config.subPath.trim())
            .putString(KEY_MOUNT, config.mountPoint.trim())
            .putBoolean(KEY_GPHOTOS, config.enableGooglePhotosIntegration)
            .apply()
        writeRcloneConf(config)
    }

    fun rcloneConfigFile(): File = File(configDir, "rclone.conf")

    /**
     * 寫入 rclone SMB remote。obscure 密碼交由呼叫端以 rclone obscure 處理時可再強化；
     * 此處使用 plain pass（檔案在私有目錄 + 0600），避免把密碼放進命令列。
     */
    fun writeRcloneConf(config: SmbConfig) {
        val file = rcloneConfigFile()
        val content = buildString {
            appendLine("[${config.remoteName.trim().ifEmpty { "nas" }}]")
            appendLine("type = smb")
            appendLine("host = ${config.host.trim()}")
            appendLine("port = ${config.port}")
            appendLine("user = ${config.username}")
            appendLine("pass = ${config.password}")
            if (config.domain.isNotBlank()) {
                appendLine("domain = ${config.domain}")
            }
        }
        file.writeText(content)
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
        // 盡力設 0600
        runCatching {
            Runtime.getRuntime().exec(arrayOf("chmod", "600", file.absolutePath)).waitFor()
        }
    }

    private fun createPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Exception) {
            // Keystore 異常時退回私有 prefs，並標記供 UI 警告（仍不進 log）
            usingFallbackPrefs = true
            context.getSharedPreferences(PREFS_FALLBACK, Context.MODE_PRIVATE)
        }
    }

    companion object {
        private const val PREFS_NAME = "smb_secure_prefs"
        private const val PREFS_FALLBACK = "smb_prefs_fallback"
        private const val KEY_REMOTE = "remote"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_USER = "user"
        private const val KEY_PASSWORD = "password"
        private const val KEY_DOMAIN = "domain"
        private const val KEY_SHARE = "share"
        private const val KEY_SUBPATH = "subpath"
        private const val KEY_MOUNT = "mount"
        private const val KEY_GPHOTOS = "gphotos"
    }
}
