package com.nas.gallerymount

import android.app.Application
import com.nas.gallerymount.config.ConfigStore
import com.nas.gallerymount.config.SessionStore
import com.nas.gallerymount.domain.SessionController
import com.nas.gallerymount.media.MediaIndexer
import com.nas.gallerymount.mount.MountManager
import com.nas.gallerymount.mount.RclonePasswordObscurer
import com.nas.gallerymount.mount.SmbBrowser
import com.nas.gallerymount.mount.ToolLocator
import com.nas.gallerymount.probe.DeviceProbe
import com.nas.gallerymount.root.RootExecutor

/**
 * 應用程式進入點：以手動 DI 組裝各層依賴。
 */
class GalleryMountApp : Application() {
    lateinit var rootExecutor: RootExecutor
        private set
    lateinit var configStore: ConfigStore
        private set
    lateinit var sessionStore: SessionStore
        private set
    lateinit var toolLocator: ToolLocator
        private set
    lateinit var smbBrowser: SmbBrowser
        private set
    lateinit var mountManager: MountManager
        private set
    lateinit var deviceProbe: DeviceProbe
        private set
    lateinit var mediaIndexer: MediaIndexer
        private set
    lateinit var sessionController: SessionController
        private set

    override fun onCreate() {
        super.onCreate()
        rootExecutor = RootExecutor()
        configStore = ConfigStore(this)
        sessionStore = SessionStore(this)
        toolLocator = ToolLocator(this)
        configStore.passwordObscurer = RclonePasswordObscurer()
        smbBrowser = SmbBrowser(rootExecutor, toolLocator, configStore)
        deviceProbe = DeviceProbe(rootExecutor)
        mountManager = MountManager(rootExecutor, toolLocator, configStore, deviceProbe)
        mediaIndexer = MediaIndexer(this)
        sessionController = SessionController(
            rootExecutor = rootExecutor,
            configStore = configStore,
            sessionStore = sessionStore,
            toolLocator = toolLocator,
            smbBrowser = smbBrowser,
            mountManager = mountManager,
            mediaIndexer = mediaIndexer,
            deviceProbe = deviceProbe,
        )
    }
}
