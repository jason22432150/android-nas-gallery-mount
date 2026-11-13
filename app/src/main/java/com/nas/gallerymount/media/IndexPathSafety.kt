package com.nas.gallerymount.media

/**
 * 清除 MediaStore 索引前的路徑安全檢查，避免誤刪系統相簿目錄。
 */
object IndexPathSafety {
    /** 禁止整層清除的相對路徑（比對時轉小寫） */
    private val PROTECTED_PREFIXES = setOf(
        "dcim/",
        "dcim/camera/",
        "dcim/screenshots/",
        "pictures/",
        "movies/",
        "download/",
        "downloads/",
        "music/",
        "alarms/",
        "ringtones/",
        "notifications/",
        "podcasts/",
        "audiobooks/",
        "recordings/",
    )

    // 禁止 _（LIKE 萬用字元）與 %，避免誤比對
    private val SAFE_CHARS = Regex("^[A-Za-z0-9./\\- ]+$")

    /**
     * @return 若不可清除則回傳錯誤訊息；可清除則回傳 null。
     */
    fun validateClearableRelativePath(relativePath: String): String? {
        val prefix = normalizeRelativePrefix(relativePath)
        if (prefix == "/" || prefix.isBlank()) {
            return "掛載相對路徑無效，拒絕清除"
        }
        if (!SAFE_CHARS.matches(prefix)) {
            return "掛載路徑含非法字元（不可含 _ % 等），拒絕清除"
        }
        if (prefix.contains("..")) {
            return "掛載路徑不可含 ..，拒絕清除"
        }
        val lower = prefix.lowercase()
        if (lower in PROTECTED_PREFIXES) {
            return "拒絕清除系統相簿目錄：$prefix（請使用專用子目錄作為掛載點）"
        }
        val segments = prefix.trim('/').split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) {
            return "掛載相對路徑過短，拒絕清除"
        }
        if (segments.size == 2 &&
            segments[0].equals("DCIM", ignoreCase = true) &&
            segments[1].equals("Camera", ignoreCase = true)
        ) {
            return "拒絕清除整個 DCIM/Camera/，請掛到子目錄（例如 DCIM/Camera/smb）"
        }
        // 拒絕 storage/emulated/0/ 這類誤轉路徑
        if (lower.startsWith("storage/")) {
            return "掛載相對路徑異常（$prefix），拒絕清除"
        }
        return null
    }

    fun normalizeRelativePrefix(relativePath: String): String =
        relativePath.trim().trimEnd('/') + "/"

    /** 將字面路徑編成 SQLite LIKE 字面值（ESCAPE '\\'）。 */
    fun likeLiteral(relativePathPrefix: String): String {
        val prefix = normalizeRelativePrefix(relativePathPrefix)
        return prefix
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_") + "%"
    }
}
