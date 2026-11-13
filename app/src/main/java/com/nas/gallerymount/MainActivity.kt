package com.nas.gallerymount

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nas.gallerymount.ui.HomeScreen
import com.nas.gallerymount.ui.MainViewModel
import com.nas.gallerymount.ui.theme.NasGalleryTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        val app = application as GalleryMountApp
        MainViewModel.Factory(app.sessionController)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* 權限結果不阻塞設定編輯；掃描時再反映查詢能力 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestMediaPermissions()
        setContent {
            NasGalleryTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                HomeScreen(
                    state = state,
                    onConfigChange = viewModel::onConfigChange,
                    onSave = viewModel::saveConfig,
                    onRecheckRoot = viewModel::recheckRoot,
                    onTest = viewModel::testConnection,
                    onList = viewModel::listSource,
                    onConnect = viewModel::connect,
                    onDisconnect = viewModel::disconnect,
                    onRefresh = viewModel::refreshStatus,
                    listShares = viewModel::browseShares,
                    listDirectories = viewModel::browseDirectories,
                )
            }
        }
    }

    private fun requestMediaPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (!granted(Manifest.permission.READ_MEDIA_IMAGES)) {
                needed += Manifest.permission.READ_MEDIA_IMAGES
            }
            if (!granted(Manifest.permission.READ_MEDIA_VIDEO)) {
                needed += Manifest.permission.READ_MEDIA_VIDEO
            }
        } else {
            if (!granted(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                needed += Manifest.permission.READ_EXTERNAL_STORAGE
            }
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
