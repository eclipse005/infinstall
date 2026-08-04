package com.infinstall.app.ui.screens

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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.infinstall.app.adb.TvAppInfo
import com.infinstall.app.ui.theme.MinTouch
import com.infinstall.app.viewmodel.UiState

@Composable
fun AppsScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onUninstall: (String) -> Unit,
    onGoConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingUninstall by remember { mutableStateOf<TvAppInfo?>(null) }

    pendingUninstall?.let { app ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text("确认卸载") },
            text = {
                Text("确定从电视卸载「${app.label}」\n（${app.packageName}）？")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingUninstall = null
                        onUninstall(app.packageName)
                    },
                ) { Text("卸载") }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) { Text("取消") }
            },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("应用管理", style = MaterialTheme.typography.headlineMedium)
            Text(
                "查看电视上已安装的第三方应用，并可卸载。",
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
                        Button(
                            onClick = onGoConnect,
                            modifier = Modifier.heightIn(min = MinTouch),
                        ) { Text("去连接") }
                    }
                }
            }
        } else {
            item {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !state.appsLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MinTouch),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.appsLoading) "刷新中…" else "刷新列表")
                }
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

        if (state.appsLoading && state.apps.isEmpty()) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text("正在读取电视应用列表…", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        if (state.connected && !state.appsLoading && state.apps.isEmpty()) {
            item {
                Text(
                    "没有第三方应用，或列表为空。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(state.apps, key = { it.packageName }) { app ->
            val busy = state.uninstallingPackage == app.packageName
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouch),
            ) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            app.packageName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = { pendingUninstall = app },
                        enabled = !busy && state.uninstallingPackage == null,
                        modifier = Modifier.heightIn(min = MinTouch),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("卸载")
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
