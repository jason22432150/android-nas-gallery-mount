package com.nas.gallerymount.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IndexPathSafetyTest {
    @Test
    fun allowsDedicatedMountFolders() {
        assertNull(IndexPathSafety.validateClearableRelativePath("SMB-GooglePhotos/"))
        assertNull(IndexPathSafety.validateClearableRelativePath("Pictures/SMBFolderTest/"))
        assertNull(IndexPathSafety.validateClearableRelativePath("DCIM/Camera/smb/"))
    }

    @Test
    fun rejectsProtectedSystemAlbums() {
        assertNotNull(IndexPathSafety.validateClearableRelativePath("DCIM/"))
        assertNotNull(IndexPathSafety.validateClearableRelativePath("DCIM/Camera/"))
        assertNotNull(IndexPathSafety.validateClearableRelativePath("dcim/"))
        assertNotNull(IndexPathSafety.validateClearableRelativePath("Pictures/"))
        assertNotNull(IndexPathSafety.validateClearableRelativePath("Movies/"))
    }

    @Test
    fun rejectsUnsafeCharactersAndRoots() {
        assertNotNull(IndexPathSafety.validateClearableRelativePath("foo';bar/"))
        assertNotNull(IndexPathSafety.validateClearableRelativePath("../etc/"))
        assertNotNull(IndexPathSafety.validateClearableRelativePath("SMB_Photos/"))
        assertNotNull(IndexPathSafety.validateClearableRelativePath("storage/emulated/0/"))
    }

    @Test
    fun likeLiteralEscapesWildcards() {
        // 安全路徑不含 _/%，但仍驗證 ESCAPE 編碼
        assertEquals("SMB-GooglePhotos/%", IndexPathSafety.likeLiteral("SMB-GooglePhotos/"))
    }
}
