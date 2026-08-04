package com.infinstall.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infinstall.app.adb.TvAppInfo
import com.infinstall.app.ui.theme.MinTouch
import com.infinstall.app.viewmodel.UiState
import kotlin.math.absoluteValue

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
            title = { Text("卸载应用") },
            text = {
                Text("确定卸载「${app.label}」？")
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
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "应用",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                if (state.connected) {
                    OutlinedButton(
                        onClick = onRefresh,
                        enabled = !state.appsLoading,
                        modifier = Modifier.heightIn(min = MinTouch),
                    ) {
                        if (state.appsLoading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("刷新")
                    }
                }
            }
        }

        if (!state.connected) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("还没连接设备", style = MaterialTheme.typography.titleMedium)
                        Button(
                            onClick = onGoConnect,
                            modifier = Modifier.heightIn(min = MinTouch),
                        ) { Text("去连接") }
                    }
                }
            }
        }

        // only apps-related banners — not global "已连接"
        state.appsBanner?.let { msg ->
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(msg, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (state.appsLoading && state.apps.isEmpty()) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(vertical = 24.dp),
                ) {
                    CircularProgressIndicator()
                    Text("加载中…", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        if (state.connected && !state.appsLoading && state.apps.isEmpty() && state.appsBanner == null) {
            item {
                Text(
                    "暂无第三方应用",
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
                    .heightIn(min = 64.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppAvatar(label = app.label, packageName = app.packageName)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            app.label,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // 包名缩小为次要信息，有中文名时不抢戏
                        if (app.label != app.packageName &&
                            !app.label.equals(app.packageName.substringAfterLast('.'), ignoreCase = true)
                        ) {
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
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

@Composable
private fun AppAvatar(label: String, packageName: String) {
    val letter = remember(label) {
        label.trim().firstOrNull()?.uppercaseChar()?.toString()
            ?: packageName.firstOrNull()?.uppercaseChar()?.toString()
            ?: "?"
    }
    val bg = remember(packageName) { colorForPackage(packageName) }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun colorForPackage(packageName: String): Color {
    val palette = listOf(
        Color(0xFF1565C0),
        Color(0xFF2E7D32),
        Color(0xFF6A1B9A),
        Color(0xFFC62828),
        Color(0xFF00838F),
        Color(0xFFEF6C00),
        Color(0xFF4527A0),
        Color(0xFF37474F),
    )
    val idx = packageName.hashCode().absoluteValue % palette.size
    return palette[idx]
}
