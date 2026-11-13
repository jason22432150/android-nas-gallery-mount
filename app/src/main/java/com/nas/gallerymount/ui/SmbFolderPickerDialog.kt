package com.nas.gallerymount.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * SMB 共享／子資料夾瀏覽選擇器（以 rclone 列目錄，非手打路徑）。
 */
@Composable
fun SmbFolderPickerDialog(
    initialShare: String,
    initialSubPath: String,
    onDismiss: () -> Unit,
    onConfirm: (share: String, subPath: String) -> Unit,
    listShares: suspend () -> Result<List<String>>,
    listDirectories: suspend (share: String, subPath: String) -> Result<List<String>>,
) {
    var share by remember { mutableStateOf(initialShare.trim()) }
    var subPath by remember { mutableStateOf(initialSubPath.trim().trim('/')) }
    var entries by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun currentLabel(): String = when {
        share.isEmpty() -> "選擇共享"
        subPath.isEmpty() -> share
        else -> "$share/$subPath"
    }

    fun reload() {
        scope.launch {
            loading = true
            error = null
            val result = if (share.isEmpty()) {
                listShares()
            } else {
                listDirectories(share, subPath)
            }
            result.fold(
                onSuccess = { entries = it },
                onFailure = {
                    entries = emptyList()
                    error = it.message ?: "列出失敗"
                },
            )
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("選擇 SMB 資料夾") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("目前：${currentLabel()}", style = MaterialTheme.typography.bodyMedium)
                if (loading) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                } else if (entries.isEmpty()) {
                    Text("此層沒有子資料夾，可直接選用目前位置。")
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                    ) {
                        items(entries) { name ->
                            Text(
                                text = "$name/",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (share.isEmpty()) {
                                            share = name
                                            subPath = ""
                                        } else {
                                            subPath = if (subPath.isEmpty()) {
                                                name
                                            } else {
                                                "$subPath/$name"
                                            }
                                        }
                                        reload()
                                    }
                                    .padding(vertical = 10.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = {
                            when {
                                share.isEmpty() -> Unit
                                subPath.isEmpty() -> {
                                    share = ""
                                    reload()
                                }
                                else -> {
                                    val parts = subPath.split('/').filter { it.isNotEmpty() }
                                    subPath = parts.dropLast(1).joinToString("/")
                                    reload()
                                }
                            }
                        },
                        enabled = share.isNotEmpty() && !loading,
                    ) {
                        Text("上一層")
                    }
                    OutlinedButton(onClick = { reload() }, enabled = !loading) {
                        Text("重新整理")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(share, subPath) },
                enabled = share.isNotEmpty() && !loading,
            ) {
                Text("使用目前資料夾")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
