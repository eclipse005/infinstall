package com.infinstall.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var showHelp by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("连接", style = MaterialTheme.typography.headlineMedium)
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
                    label = { Text("配对") },
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

        item {
            TextButton(onClick = { showHelp = !showHelp }) {
                Icon(
                    if (showHelp) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                )
                Spacer(Modifier.width(4.dp))
                Text(if (showHelp) "收起说明" else "不会用？点这里")
            }
            AnimatedVisibility(
                visible = showHelp,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                HelpCard(state.connectMode)
            }
        }

        if (state.history.isNotEmpty()) {
            item {
                Text("最近连接", style = MaterialTheme.typography.titleMedium)
            }
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
        OutlinedTextField(
            value = state.hostInput,
            onValueChange = onHostChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("IP 地址") },
            placeholder = { Text("192.168.x.x") },
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
                    Text("连接中")
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
        OutlinedTextField(
            value = state.hostInput,
            onValueChange = onHostChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("IP 地址") },
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
                Text("配对中")
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
                style = MaterialTheme.typography.bodyMedium,
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
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun HelpCard(mode: ConnectMode) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (mode) {
                ConnectMode.Direct -> Text(
                    "填设备 IP 和端口后点连接。\n" +
                        "无线调试：先完成「配对」，再用调试页上的连接端口。\n" +
                        "关闭 VPN，保持同一 Wi‑Fi。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ConnectMode.Pair -> Text(
                    "1. 设备打开「无线调试」→「使用配对码配对」\n" +
                        "2. 弹窗不要关，把 IP、配对端口、配对码填到上面\n" +
                        "3. 配对成功后，切到「直接连接」，用连接端口连接",
                    style = MaterialTheme.typography.bodyMedium,
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
            Text(
                entry.endpoint,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(8.dp),
            )
            IconButton(onClick = { onRemove(entry) }) {
                Icon(Icons.Default.Delete, contentDescription = "删除")
            }
        }
    }
}
