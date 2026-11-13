package com.nas.gallerymount.util

import android.net.Uri
import android.provider.DocumentsContract
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * 將系統「選擇資料夾」（Storage Access Framework）的 tree URI
 * 轉成 rclone／掛載可用的 `/storage/emulated/0/...` 路徑。
 *
 * 僅支援 primary 外部儲存（emulated）；其他磁碟區回傳 null。
 */
object PrimaryStoragePaths {
    private const val PRIMARY_ROOT = "/storage/emulated/0"
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /**
     * @return 絕對路徑，例如 `/storage/emulated/0/Pictures/SMB`；無法解析則 null
     */
    fun fromTreeUri(uri: Uri): String? {
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val docId = runCatching {
            DocumentsContract.getTreeDocumentId(uri)
        }.getOrNull() ?: return null
        return fromDocumentId(docId)
    }

    /**
     * documentId 形如 `primary:Pictures/SMB` 或 `primary:`。
     */
    fun fromDocumentId(documentId: String): String? {
        val decoded = URLDecoder.decode(documentId, StandardCharsets.UTF_8.name())
        val parts = decoded.split(":", limit = 2)
        if (parts.size != 2) return null
        val volume = parts[0]
        if (!volume.equals("primary", ignoreCase = true)) return null
        val relative = parts[1].trim('/').replace('\\', '/')
        if (relative.contains("..")) return null
        return if (relative.isEmpty()) {
            PRIMARY_ROOT
        } else {
            "$PRIMARY_ROOT/$relative"
        }
    }
}
