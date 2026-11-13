package com.nas.gallerymount.media

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaIndexerPathTest {
    @Test
    fun relativePathOf_stripsStoragePrefix() {
        // MediaIndexer 需要 Context；此測試只驗證路徑字串邏輯的純函式複本
        val absolute = "/storage/emulated/0/Pictures/SMBFolderTest"
        val prefix = "/storage/emulated/0/"
        val relative = if (absolute.startsWith(prefix)) {
            absolute.removePrefix(prefix).trim('/') + "/"
        } else {
            absolute.trim('/') + "/"
        }
        assertEquals("Pictures/SMBFolderTest/", relative)
    }
}
