package com.nas.gallerymount.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MountInfoParserTest {
    @Test
    fun unescape_octalSpaces() {
        val raw = "/storage/emulated/0/Pictures/SMB\\040Test"
        assertEquals("/storage/emulated/0/Pictures/SMB Test", MountInfoParser.unescape(raw))
    }

    @Test
    fun parseLine_basicFuse() {
        val line = "391 32 0:69 / /storage/emulated/0/Pictures/SMBFolderTest " +
            "ro,nosuid,nodev,noexec,relatime - fuse.rclone nas:share/path rw,user_id=0,group_id=0"
        val entry = MountInfoParser.parseLine(line)
        assertNotNull(entry)
        assertEquals("/storage/emulated/0/Pictures/SMBFolderTest", entry!!.mountPoint)
        assertEquals("fuse.rclone", entry.fsType)
        assertEquals("nas:share/path", entry.source)
        assertTrue(entry.isReadOnly)
    }

    @Test
    fun findExactMountPoint_matchesTrimmed() {
        val content = """
            1 0 0:1 / / rw - rootfs rootfs rw
            10 1 0:50 / /storage/emulated/0/Pictures/SMBFolderTest ro - fuse.rclone nas:x rw
        """.trimIndent()
        val found = MountInfoParser.findExactMountPoint(
            content,
            "/storage/emulated/0/Pictures/SMBFolderTest/",
        )
        assertNotNull(found)
        assertEquals("fuse.rclone", found!!.fsType)
    }
}
