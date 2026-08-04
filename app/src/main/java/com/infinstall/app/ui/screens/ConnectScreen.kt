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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.infinstall.app.adb.DiscoveredDevice
import com.infinstall.app.data.HostEntry
import com.infinstall.app.ui.theme.MinTouch
import com.infinstall.app.viewmodel.UiState

@Composable
fun ConnectScreen(
    state: UiState,
    isTablet: Boolean,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onScan: () -> Unit,
    onCancelScan: () -> Unit,
    onPickDiscovered: (DiscoveredDevice) -> Unit,
    onPickHistory: (HostEntry) -> Unit,
    onRemoveHistory: (HostEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isTablet) {
        Row(
            modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ConnectForm(state, onHostChange, onPortChange, onConnect, onDisconnect, onScan, onCancelScan)
                HelpCard()
            }
            Column(Modifier.weight(1f).fillMaxSize()) {
                DeviceLists(
                    state = state,
                    onPickDiscovered = onPickDiscovered,
                    onPickHistory = onPickHistory,
                    onRemoveHistory = onRemoveHistory,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ConnectForm(state, onHostChange, onPortChange, onConnect, onDisconnect, onScan, onCancelScan)
            }
            item { StatusBlock(state) }
            item { HelpCard() }
            item {
                Text("扫描结果", style = MaterialTheme.typography.titleMedium)
            }
            if (state.discovered.isEmpty()) {
                item {
                    Text(
                        if (state.scanning) "扫描中…" else "暂无。点「扫描设备」自动查找。",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(state.discovered, key = { it.endpoint }) { d ->
                    DeviceRow(title = d.endpoint, subtitle = "点按连接") {
                        onPickDiscovered(d)
                    }
                }
            }
            item {
                Spacer(Modifier.height(4.dp))
                Text("连接历史", style = MaterialTheme.typography.titleMedium)
            }
            if (state.history.isEmpty()) {
                item {
                    Text(
                        "成功连接过的电视会显示在这里。",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(state.history, key = { it.endpoint }) { e ->
                    HistoryRow(e, onPickHistory, onRemoveHistory)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ConnectForm(
    state: UiState,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onScan: () -> Unit,
    onCancelScan: () -> Unit,
) {
    Text("连接电视", style = MaterialTheme.typography.headlineMedium)
    Text(
        "优先扫描同一 Wi‑Fi 下已开启网络调试的电视/盒子（会校验，不是端口开了就算）。也可手输 IP。",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (state.scanning) {
            OutlinedButton(
                onClick = onCancelScan,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = MinTouch),
            ) { Text("停止扫描") }
        } else {
            Button(
                onClick = onScan,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = MinTouch),
            ) {
                Icon(Icons.Default.Radar, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("扫描设备")
            }
        }
        if (state.connected) {
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = MinTouch),
            ) { Text("断开") }
        }
    }

    if (state.scanning) {
        val progress = state.scanProgress
        if (progress != null && progress.second > 0) {
            LinearProgressIndicator(
                progress = { progress.first.toFloat() / progress.second },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "已扫描 ${progress.first} / ${progress.second}",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }

    OutlinedTextField(
        value = state.hostInput,
        onValueChange = onHostChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("电视 IP 地址") },
        placeholder = { Text("例如 192.168.1.8") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
        ),
    )
    OutlinedTextField(
        value = state.portInput,
        onValueChange = onPortChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("端口") },
        placeholder = { Text("5555") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onConnect() }),
    )

    Button(
        onClick = onConnect,
        enabled = !state.connecting,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouch),
    ) {
        if (state.connecting) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(8.dp))
            Text("连接中…")
        } else {
            Text(if (state.connected) "重新连接" else "连接")
        }
    }

    StatusBlock(state)
}

@Composable
private fun StatusBlock(state: UiState) {
    state.errorMessage?.let { msg ->
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                msg,
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
    state.statusMessage?.let { msg ->
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                msg,
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun HelpCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("电视上怎么开？", style = MaterialTheme.typography.titleMedium)
            Text(
                "1. 打开电视「设置」→ 关于 / 系统，连续点击版本号打开开发者选项（各品牌路径略有不同）。\n" +
                    "2. 在开发者选项中开启「网络调试」或「网络 ADB」。\n" +
                    "3. 手机/平板与电视连接同一 Wi‑Fi。\n" +
                    "4. 本页点「扫描设备」，或手动输入电视 IP（端口多为 5555）。\n" +
                    "5. 若电视弹出授权提示，在电视上点「允许」。",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun DeviceLists(
    state: UiState,
    onPickDiscovered: (DiscoveredDevice) -> Unit,
    onPickHistory: (HostEntry) -> Unit,
    onRemoveHistory: (HostEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("扫描结果", style = MaterialTheme.typography.titleMedium) }
        if (state.discovered.isEmpty()) {
            item {
                Text(
                    if (state.scanning) "扫描中…" else "暂无设备。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(state.discovered, key = { it.endpoint }) { d ->
                DeviceRow(d.endpoint, "点按连接") { onPickDiscovered(d) }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text("连接历史", style = MaterialTheme.typography.titleMedium)
        }
        if (state.history.isEmpty()) {
            item {
                Text("暂无历史", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(state.history, key = { it.endpoint }) { e ->
                HistoryRow(e, onPickHistory, onRemoveHistory)
            }
        }
    }
}

@Composable
private fun DeviceRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouch),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HistoryRow(
    entry: HostEntry,
    onPick: (HostEntry) -> Unit,
    onRemove: (HostEntry) -> Unit,
) {
    Card(
        onClick = { onPick(entry) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouch),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(8.dp)) {
                Text(entry.endpoint, style = MaterialTheme.typography.titleMedium)
                Text("点按重新连接", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { onRemove(entry) }) {
                Icon(Icons.Default.Delete, contentDescription = "删除历史")
            }
        }
    }
}
