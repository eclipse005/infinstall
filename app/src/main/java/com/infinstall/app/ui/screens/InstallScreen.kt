package com.infinstall.app.ui.screens

import android.net.Uri
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.infinstall.app.ui.theme.MinTouch
import com.infinstall.app.viewmodel.UiState

@Composable
fun InstallScreen(
    state: UiState,
    onInstallUris: (List<Uri>) -> Unit,
    onGoConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val multiPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) onInstallUris(uris)
    }

    val singlePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onInstallUris(listOf(uri))
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("安装应用", style = MaterialTheme.typography.headlineMedium)
            Text(
                "从本机选择 APK，无线装到已连接的电视。也可在其它 App 里用「分享」到无限安装。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!state.connected) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("尚未连接电视", style = MaterialTheme.typography.titleMedium)
                        Text("请先在「连接」页扫描或输入 IP。", style = MaterialTheme.typography.bodyLarge)
                        Button(
                            onClick = onGoConnect,
                            modifier = Modifier.heightIn(min = MinTouch),
                        ) { Text("去连接") }
                    }
                }
            }
        }

        item {
            Button(
                onClick = {
                    // Prefer multi-select; MIME for APK
                    multiPicker.launch(arrayOf(
                        "application/vnd.android.package-archive",
                        "application/octet-stream",
                        "*/*",
                    ))
                },
                enabled = state.connected && !state.installing,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouch),
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("选择 APK（可多选）")
            }
            OutlinedButton(
                onClick = {
                    singlePicker.launch(arrayOf(
                        "application/vnd.android.package-archive",
                        "application/octet-stream",
                        "*/*",
                    ))
                },
                enabled = state.connected && !state.installing,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouch),
            ) {
                Text("选择单个文件")
            }
        }

        if (state.installing) {
            item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                RowLoading("正在安装，请勿离开…")
            }
        }

        state.errorMessage?.let { msg ->
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(msg, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        state.statusMessage?.let { msg ->
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(msg, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        if (state.installLog.isNotEmpty()) {
            item {
                Text("安装过程", style = MaterialTheme.typography.titleMedium)
            }
            items(state.installLog) { line ->
                Text("· $line", style = MaterialTheme.typography.bodyLarge)
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun RowLoading(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(Modifier.size(28.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
