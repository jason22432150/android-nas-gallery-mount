package com.nas.gallerymount.ui

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nas.gallerymount.domain.IndexAvailability
import com.nas.gallerymount.domain.SessionPhase
import com.nas.gallerymount.domain.SessionUiState
import com.nas.gallerymount.domain.SmbConfig
import com.nas.gallerymount.util.PrimaryStoragePaths

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: SessionUiState,
    logs: List<String>,
    onConfigChange: (SmbConfig) -> Unit,
    onSave: () -> Unit,
    onRecheckRoot: () -> Unit,
    onTest: () -> Unit,
    onList: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    onCancelScan: () -> Unit,
    onClearMediaIndex: () -> Unit,
    onToggleLogs: () -> Unit,
    onClearLogs: () -> Unit,
    listShares: suspend () -> Result<List<String>>,
    listDirectories: suspend (share: String, subPath: String) -> Result<List<String>>,
) {
    val cfg = state.config
    val rootOk = state.root.available
    val canRootOps = rootOk && !state.busy
    val context = LocalContext.current
    val scanning = state.phase is SessionPhase.Scanning

    var showSmbPicker by remember { mutableStateOf(false) }
    var showClearIndexConfirm by remember { mutableStateOf(false) }
    var mountPickError by remember { mutableStateOf<String?>(null) }

    val mountTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val path = PrimaryStoragePaths.fromTreeUri(uri)
        if (path == null) {
            mountPickError = "僅支援內部共用儲存（primary）。請選擇／storage/emulated/0 底下的資料夾。"
        } else {
            mountPickError = null
            onConfigChange(cfg.copy(mountPoint = path))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("NAS Gallery Mount") })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(state)

            if (scanning || state.scan.percent > 0) {
                ScanProgressSection(
                    percent = state.scan.percent,
                    message = state.scan.message,
                    scanning = scanning,
                    onCancel = onCancelScan,
                )
            }

            Text("Root 授權", style = MaterialTheme.typography.titleMedium)
            Text(state.root.message)
            if (state.root.uidLine.isNotBlank()) {
                Text(state.root.uidLine, style = MaterialTheme.typography.bodySmall)
            }
            if (!rootOk) {
                Text(
                    "請開啟 Magisk → 超級使用者 → 允許「NAS Gallery Mount」，然後重新檢查。",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(onClick = onRecheckRoot, enabled = !state.busy || scanning) {
                Text("重新檢查 Root / 工具 / 裝置")
            }

            Text("SMB 連線設定", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = cfg.host,
                onValueChange = { onConfigChange(cfg.copy(host = it)) },
                label = { Text("主機") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = cfg.port.toString(),
                onValueChange = { v ->
                    v.toIntOrNull()?.let { onConfigChange(cfg.copy(port = it)) }
                },
                label = { Text("連接埠") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = cfg.username,
                onValueChange = { onConfigChange(cfg.copy(username = it)) },
                label = { Text("帳號") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = cfg.password,
                onValueChange = { onConfigChange(cfg.copy(password = it)) },
                label = { Text("密碼") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = cfg.domain,
                onValueChange = { onConfigChange(cfg.copy(domain = it)) },
                label = { Text("網域（可選）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            PathSelectRow(
                label = "SMB 來源資料夾",
                value = when {
                    cfg.shareName.isBlank() -> "尚未選擇"
                    cfg.subPath.isBlank() -> cfg.shareName
                    else -> "${cfg.shareName}/${cfg.subPath}"
                },
                buttonText = "選擇資料夾",
                enabled = canRootOps || scanning,
                onClick = { showSmbPicker = true },
            )
            Text(
                "需先填好主機與帳密，並已授權 Root。可從共享往下點選子資料夾。",
                style = MaterialTheme.typography.bodySmall,
            )

            PathSelectRow(
                label = "手機掛載位置",
                value = cfg.mountPoint.ifBlank { "尚未選擇" },
                buttonText = "選擇資料夾",
                enabled = !state.busy || scanning,
                onClick = {
                    mountPickError = null
                    val initial = DocumentsContract.buildTreeDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:Pictures",
                    )
                    mountTreeLauncher.launch(initial)
                },
            )
            if (mountPickError != null) {
                Text(mountPickError!!, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    onConfigChange(cfg.copy(mountPoint = SmbConfig.DEFAULT_TEST_MOUNT))
                }) { Text("測試目錄") }
                OutlinedButton(onClick = {
                    onConfigChange(cfg.copy(mountPoint = SmbConfig.DEFAULT_PROD_MOUNT))
                }) { Text("正式目錄") }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = cfg.enableGooglePhotosIntegration,
                    onCheckedChange = {
                        onConfigChange(cfg.copy(enableGooglePhotosIntegration = it))
                    },
                )
                Text("啟用 Google 相簿整合（MediaProvider bind + 掃描）")
            }
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                Text("儲存設定")
            }

            Text("來源與掛載", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTest, enabled = canRootOps) { Text("測試連線") }
                Button(onClick = onList, enabled = canRootOps) { Text("列出來源") }
            }
            if (state.sourceListing.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("來源列表（最多顯示 50 項）")
                        state.sourceListing.take(50).forEach { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onConnect,
                    enabled = canRootOps && state.phase !is SessionPhase.Mounted &&
                        state.phase !is SessionPhase.Unmounting &&
                        state.phase !is SessionPhase.Scanning,
                ) { Text("連線並掃描") }
                Button(
                    onClick = onDisconnect,
                    enabled = state.phase is SessionPhase.Mounted ||
                        state.phase is SessionPhase.Scanning ||
                        state.mount.fuseMounted ||
                        state.mount.bindMounted,
                ) { Text("斷線") }
                OutlinedButton(onClick = onRefresh, enabled = !state.busy || scanning) {
                    Text("重新整理狀態")
                }
            }

            OutlinedButton(
                onClick = { showClearIndexConfirm = true },
                enabled = canRootOps &&
                    !state.mount.fuseMounted &&
                    !state.mount.bindMounted &&
                    state.phase !is SessionPhase.Scanning &&
                    state.phase !is SessionPhase.Mounted &&
                    state.phase !is SessionPhase.MountingFuse &&
                    state.phase !is SessionPhase.BindingMediaProvider &&
                    state.phase !is SessionPhase.Unmounting,
            ) {
                Text("清除媒體索引")
            }
            Text(
                "須先斷線。清除後 Google 相簿本地清單應不再顯示此掛載路徑（不刪 SMB 原檔／雲端備份）。" +
                    "若仍見縮圖，可強制停止 Google 相簿。",
                style = MaterialTheme.typography.bodySmall,
            )

            if (state.busy && !scanning) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator()
                    Text(phaseLabel(state.phase))
                }
            }

            Text(
                "掃描範圍：${cfg.mountPoint}\n" +
                    "若 Google 相簿開啟備份，媒體可能上傳雲端。",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("診斷 Logs", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onToggleLogs) {
                    Text(if (state.showLogs) "隱藏 Logs" else "顯示 Logs")
                }
                OutlinedButton(onClick = onClearLogs) { Text("清除 Logs") }
            }
            Text(
                "ADB：./scripts/adb-cmd.sh status｜logs｜connect｜cancel｜clear-index",
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.showLogs) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 320.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        Modifier
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (logs.isEmpty()) {
                            Text("（尚無 logs）", style = MaterialTheme.typography.bodySmall)
                        } else {
                            logs.takeLast(200).forEach { line ->
                                Text(
                                    line,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showSmbPicker) {
        SmbFolderPickerDialog(
            initialShare = cfg.shareName,
            initialSubPath = cfg.subPath,
            onDismiss = { showSmbPicker = false },
            onConfirm = { share, sub ->
                onConfigChange(cfg.copy(shareName = share, subPath = sub))
                showSmbPicker = false
            },
            listShares = listShares,
            listDirectories = listDirectories,
        )
    }

    if (showClearIndexConfirm) {
        AlertDialog(
            onDismissRequest = { showClearIndexConfirm = false },
            title = { Text("清除媒體索引？") },
            text = {
                Text(
                    "將刪除 MediaStore 中掛載點「${cfg.mountPoint}」底下的照片／影片索引列。" +
                        "請確認已斷線。不會刪除 SMB 原檔。" +
                        "Google 相簿若已備份到雲端，雲端副本不受影響。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearIndexConfirm = false
                        onClearMediaIndex()
                    },
                ) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { showClearIndexConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ScanProgressSection(
    percent: Int,
    message: String,
    scanning: Boolean,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("掃描進度：$percent%", style = MaterialTheme.typography.titleSmall)
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(message, style = MaterialTheme.typography.bodySmall)
            if (scanning) {
                Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("中斷掃描")
                }
            }
        }
    }
}

@Composable
private fun PathSelectRow(
    label: String,
    value: String,
    buttonText: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = value,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            readOnly = true,
            singleLine = true,
            enabled = false,
        )
        OutlinedButton(onClick = onClick, enabled = enabled) {
            Text(buttonText)
        }
    }
}

@Composable
private fun StatusCard(state: SessionUiState) {
    val availabilityText = when (state.indexAvailability) {
        IndexAvailability.Available -> "索引：可用"
        IndexAvailability.UnavailableAfterDisconnect -> "索引：已斷線／檔案不可用（列仍保留）"
        IndexAvailability.Cleared -> "索引：已清除"
        IndexAvailability.Unknown -> "索引：未知"
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("狀態：${phaseLabel(state.phase)}", style = MaterialTheme.typography.titleSmall)
            Text(state.statusMessage)
            Text("工具：${state.tools.message}")
            state.probe?.let {
                Text("裝置：${it.profileName} — ${it.reason}")
                it.mediaProviderProcess?.let { p -> Text("MediaProvider：$p") }
            }
            Text(
                "掛載：FUSE=${state.mount.fuseMounted} bind=${state.mount.bindMounted} " +
                    "pid=${state.mount.mediaProviderPid ?: "-"}",
            )
            Text(availabilityText)
            Text(
                "掃描：${state.scan.percent}%｜發現 ${state.scan.discovered}／提交 ${state.scan.submitted}／" +
                    "索引 ${state.scan.indexed}／失敗 ${state.scan.failed}" +
                    if (state.scan.cancelled) "｜已中斷" else "",
            )
        }
    }
}

private fun phaseLabel(phase: SessionPhase): String = when (phase) {
    SessionPhase.Idle -> "已斷線／閒置"
    SessionPhase.CheckingRoot -> "檢查 Root"
    SessionPhase.Ready -> "就緒"
    SessionPhase.Testing -> "測試連線"
    SessionPhase.MountingFuse -> "掛載 FUSE"
    SessionPhase.BindingMediaProvider -> "Bind MediaProvider"
    SessionPhase.Scanning -> "掃描中"
    SessionPhase.Mounted -> "已掛載"
    SessionPhase.Unmounting -> "斷線中"
    is SessionPhase.Failed -> "失敗（${phase.stage}）"
}
