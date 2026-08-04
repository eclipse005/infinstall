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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.infinstall.app.viewmodel.UiState

/**
 * TV/box first: main path is IP + port.
 * Pairing-code is secondary (Android 11+ wireless debug only).
 * No QR — TVs have no camera.
 */
@Composable
fun ConnectScreen(
    state: UiState,
    isTablet: Boolean,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onPairPortChange: (String) -> Unit,
    onPairCodeChange: (String) -> Unit,
    onConnectMode: (com.infinstall.app.viewmodel.ConnectMode) -> Unit,
    onConnect: () -> Unit,
    onPair: () -> Unit,
    onDisconnect: () -> Unit,
    onPickHistory: (HostEntry) -> Unit,
    onRemoveHistory: (HostEntry) -> Unit,
    onRefreshLocalNetwork: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showHelp by remember { mutableStateOf(false) }
    var showPair by remember { mutableStateOf(false) }

    // Refresh subnet hint when opening connect page
    LaunchedEffect(Unit) {
        onRefreshLocalNetwork()
    }

    // 失败提示需要配对时，自动展开配对区
    LaunchedEffect(state.errorMessage, state.connectMode) {
        val err = state.errorMessage.orEmpty()
        if (err.contains("配对") || state.connectMode == com.infinstall.app.viewmodel.ConnectMode.Pair) {
            showPair = true
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("连接电视", style = MaterialTheme.typography.headlineMedium)
            Text(
                "同一 Wi‑Fi，填 IP 即可（多数电视端口 5555）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // —— 主路径：输入 IP 连接 ——
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("输入地址", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = state.hostInput,
                        onValueChange = onHostChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("IP 地址") },
                        placeholder = {
                            Text(state.localIpv4?.let { "本机网段，改最后一段" } ?: "例如 192.168.1.8")
                        },
                        supportingText = {
                            val local = state.localIpv4
                            if (local != null) {
                                Text("本机 $local，同一 Wi‑Fi 下改最后一段为电视 IP")
                            } else {
                                Text("与电视同一 Wi‑Fi 时会自动填入网段")
                            }
                        },
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
                        supportingText = { Text("电视网络调试一般为 5555") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { onConnect() }),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Button(
                            onClick = {
                                onConnectMode(com.infinstall.app.viewmodel.ConnectMode.Direct)
                                onConnect()
                            },
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
        }

        item { StatusBlock(state) }

        // —— 次路径：配对码（折叠，不为默认） ——
        item {
            OutlinedButton(
                onClick = {
                    showPair = !showPair
                    if (showPair) {
                        onConnectMode(com.infinstall.app.viewmodel.ConnectMode.Pair)
                    } else {
                        onConnectMode(com.infinstall.app.viewmodel.ConnectMode.Direct)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouch),
            ) {
                Icon(
                    if (showPair) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (showPair) "收起配对码选项"
                    else "连不上？使用配对码（少见）",
                )
            }
        }

        item {
            AnimatedVisibility(
                visible = showPair,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    ),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("配对码连接", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "仅部分手机/平板的「无线调试」需要。电视机顶盒一般用上面的 IP 连接即可，无需配对。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                            supportingText = { Text("配对弹窗上的端口，不是连接端口") },
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
                                Text("配对")
                            }
                        }
                        Text(
                            "配对成功后，回到上方填「连接端口」再点连接。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            TextButton(onClick = { showHelp = !showHelp }) {
                Icon(
                    if (showHelp) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                )
                Spacer(Modifier.width(4.dp))
                Text(if (showHelp) "收起说明" else "电视怎么开网络调试？")
            }
            AnimatedVisibility(
                visible = showHelp,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "1. 电视打开「开发者选项」\n" +
                                "2. 开启「网络调试」或「网络 ADB」（名称因品牌而异）\n" +
                                "3. 查看电视 IP，与手机同一 Wi‑Fi（请关闭 VPN）\n" +
                                "4. 上方填入 IP，端口多为 5555，点连接\n" +
                                "5. 若电视弹出允许调试，在电视上点允许",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        if (state.history.isNotEmpty()) {
            item {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
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
    state.connectBanner?.let { msg ->
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
