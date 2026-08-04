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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.infinstall.app.data.HostEntry
import com.infinstall.app.ui.theme.MinTouch
import com.infinstall.app.viewmodel.ConnectMode
import com.infinstall.app.viewmodel.UiState

@Composable
fun ConnectScreen(
    state: UiState,
    isTablet: Boolean,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onPairPortChange: (String) -> Unit,
    onPairCodeChange: (String) -> Unit,
    onConnectMode: (ConnectMode) -> Unit,
    onConnect: () -> Unit,
    onPair: () -> Unit,
    onDisconnect: () -> Unit,
    onPickHistory: (HostEntry) -> Unit,
    onRemoveHistory: (HostEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("连接设备", style = MaterialTheme.typography.headlineMedium)
            Text(
                "手机与电视/平板在同一 Wi‑Fi。支持直接填 IP，或 Android 11+ 无线调试配对。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.connectMode == ConnectMode.Direct,
                    onClick = { onConnectMode(ConnectMode.Direct) },
                    label = { Text("直接连接") },
                )
                FilterChip(
                    selected = state.connectMode == ConnectMode.Pair,
                    onClick = { onConnectMode(ConnectMode.Pair) },
                    label = { Text("配对设备") },
                )
            }
        }

        item {
            when (state.connectMode) {
                ConnectMode.Direct -> DirectConnectForm(
                    state = state,
                    onHostChange = onHostChange,
                    onPortChange = onPortChange,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                )
                ConnectMode.Pair -> PairForm(
                    state = state,
                    onHostChange = onHostChange,
                    onPairPortChange = onPairPortChange,
                    onPairCodeChange = onPairCodeChange,
                    onPair = onPair,
                )
            }
        }

        item { StatusBlock(state) }

        item { HelpCard(state.connectMode) }

        if (isTablet) {
            // keep single column scroll; history below is fine on both
        }

        item {
            Text("连接历史", style = MaterialTheme.typography.titleMedium)
        }
        if (state.history.isEmpty()) {
            item {
                Text(
                    "成功连接过的地址会显示在这里，点一下可再次连接。",
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

@Composable
private fun DirectConnectForm(
    state: UiState,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "在设备上查看 IP，填入下方。经典网络调试端口多为 5555；无线调试请用调试页上的「连接端口」。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.hostInput,
            onValueChange = onHostChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("IP 地址") },
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
            placeholder = { Text("5555 或无线调试连接端口") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onConnect() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onConnect,
                enabled = !state.connecting && !state.pairing,
                modifier = Modifier
                    .weight(1f)
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
            if (state.connected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = MinTouch),
                ) { Text("断开") }
            }
        }
    }
}

@Composable
private fun PairForm(
    state: UiState,
    onHostChange: (String) -> Unit,
    onPairPortChange: (String) -> Unit,
    onPairCodeChange: (String) -> Unit,
    onPair: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "适用于 Android 11 及以上的「无线调试」。在设备上：开发者选项 → 无线调试 → 使用配对码配对设备，把 IP、配对端口、配对码填到下面。配对成功后再用「直接连接」连一次（用连接端口）。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.hostInput,
            onValueChange = onHostChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("IP 地址") },
            placeholder = { Text("与配对界面上一致") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
        )
        OutlinedTextField(
            value = state.pairPortInput,
            onValueChange = onPairPortChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("配对端口") },
            placeholder = { Text("配对界面上的端口，不是连接端口") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next,
            ),
        )
        OutlinedTextField(
            value = state.pairCodeInput,
            onValueChange = onPairCodeChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("配对码") },
            placeholder = { Text("6 位数字") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onPair() }),
        )
        Button(
            onClick = onPair,
            enabled = !state.pairing && !state.connecting,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouch),
        ) {
            if (state.pairing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text("配对中…")
            } else {
                Text("开始配对")
            }
        }
    }
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
private fun HelpCard(mode: ConnectMode) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("怎么开？", style = MaterialTheme.typography.titleMedium)
            when (mode) {
                ConnectMode.Direct -> Text(
                    "1. 设备打开开发者选项。\n" +
                        "2. 若有「网络调试 / 网络 ADB」：打开后端口多为 5555，填 IP 连接。\n" +
                        "3. 若只有「无线调试」（Android 11+）：先到「配对设备」完成配对，再回到本页，用无线调试主界面上的 IP 与连接端口连接。\n" +
                        "4. 弹出授权时在设备上点允许。",
                    style = MaterialTheme.typography.bodyLarge,
                )
                ConnectMode.Pair -> Text(
                    "1. 设置 → 开发者选项 → 打开「无线调试」。\n" +
                        "2. 点「使用配对码配对设备」，弹窗会显示 IP、配对端口、6 位配对码。\n" +
                        "3. 弹窗不要关闭！立刻在本 App 填好并点「开始配对」。\n" +
                        "4. 配对成功后切到「直接连接」，填无线调试主界面上的 IP 与「连接端口」（不是配对端口）。\n" +
                        "5. 手机与平板须同一 Wi‑Fi；关闭 VPN；路由器勿开 AP 隔离。",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
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
