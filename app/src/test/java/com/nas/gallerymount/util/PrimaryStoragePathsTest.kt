package com.nas.gallerymount.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrimaryStoragePathsTest {
    @Test
    fun fromDocumentId_primarySubdir() {
        assertEquals(
            "/storage/emulated/0/Pictures/SMBFolderTest",
            PrimaryStoragePaths.fromDocumentId("primary:Pictures/SMBFolderTest"),
        )
    }

    @Test
    fun fromDocumentId_primaryRoot() {
        assertEquals(
            "/storage/emulated/0",
            PrimaryStoragePaths.fromDocumentId("primary:"),
        )
    }

    @Test
    fun fromDocumentId_rejectsNonPrimary() {
        assertNull(PrimaryStoragePaths.fromDocumentId("XXXX-XXXX:DCIM"))
    }

    @Test
    fun fromDocumentId_rejectsTraversal() {
        assertNull(PrimaryStoragePaths.fromDocumentId("primary:Pictures/../../data"))
    }
}
