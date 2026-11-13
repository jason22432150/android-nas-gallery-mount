package com.nas.gallerymount.probe

import com.nas.gallerymount.root.RootExecutor
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceProbePathTest {
    @Test
    fun toMediaProviderPath_rewritesEmulated() {
        val probe = DeviceProbe(RootExecutor())
        val input = "/storage/emulated/0/DCIM/Camera/smb"
        assertEquals(
            "/mnt/user/0/emulated/0/DCIM/Camera/smb",
            probe.toMediaProviderPath(input),
        )
    }
}
