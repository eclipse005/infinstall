package com.infinstall.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.infinstall.app.adb.RemoteFile
import com.infinstall.app.adb.RemoteFileProps
import com.infinstall.app.ui.theme.MinTouch
import com.infinstall.app.viewmodel.FileSort
import com.infinstall.app.viewmodel.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onUpload: (List<Uri>) -> Unit,
    onOpenDir: (String) -> Unit,
    onGoUp: () -> Unit,
    onDelete: (RemoteFile) -> Unit,
    onGoConnect: () -> Unit,
    onShortcut: (String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRename: (RemoteFile, String) -> Unit,
    onCopy: (RemoteFile) -> Unit,
    onCut: (RemoteFile) -> Unit,
    onPaste: () -> Unit,
    onClearClipboard: () -> Unit,
    onLoadProps: (RemoteFile) -> Unit,
    onDismissProps: () -> Unit,
    onDownload: (RemoteFile, Uri) -> Unit,
    onInstallApk: (RemoteFile) -> Unit,
    onSort: (FileSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) onUpload(uris) }

    var menuFile by remember { mutableStateOf<RemoteFile?>(null) }
    var pendingDelete by remember { mutableStateOf<RemoteFile?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<RemoteFile?>(null) }
    var renameText by remember { mutableStateOf("") }
    var downloadTarget by remember { mutableStateOf<RemoteFile?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }

    val createDoc = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val f = downloadTarget
        downloadTarget = null
        if (uri != null && f != null) onDownload(f, uri)
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // —— dialogs ——
    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除") },
            text = {
                Text(
                    if (file.isDir) "确定删除文件夹「${file.name}」及其全部内容？"
                    else "确定删除「${file.name}」？",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDelete(file)
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    if (showNewFolder) {
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    singleLine = true,
                    label = { Text("名称") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNewFolder = false
                        onCreateFolder(newFolderName)
                        newFolderName = ""
                    },
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolder = false }) { Text("取消") }
            },
        )
    }

    renameTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("新名称") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val f = file
                        renameTarget = null
                        onRename(f, renameText)
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }

    if (state.propsLoading || state.fileProps != null) {
        ModalBottomSheet(
            onDismissRequest = onDismissProps,
            sheetState = sheetState,
        ) {
            if (state.propsLoading) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                state.fileProps?.let { props ->
                    PropertiesContent(props)
                }
            }
        }
    }

    menuFile?.let { file ->
        ModalBottomSheet(
            onDismissRequest = { menuFile = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                HorizontalDivider()
                SheetAction("属性", Icons.Default.Info) {
                    menuFile = null
                    onLoadProps(file)
                }
                if (file.isDir) {
                    SheetAction("打开") {
                        menuFile = null
                        onOpenDir(file.name)
                    }
                }
                SheetAction("重命名") {
                    menuFile = null
                    renameText = file.name
                    renameTarget = file
                }
                SheetAction("复制", Icons.Default.ContentCopy) {
                    menuFile = null
                    onCopy(file)
                }
                SheetAction("剪切", Icons.Default.ContentCut) {
                    menuFile = null
                    onCut(file)
                }
                if (!file.isDir) {
                    SheetAction("下载到手机", Icons.Default.Download) {
                        menuFile = null
                        downloadTarget = file
                        createDoc.launch(file.name)
                    }
                }
                if (!file.isDir && file.name.endsWith(".apk", ignoreCase = true)) {
                    SheetAction("安装此 APK") {
                        menuFile = null
                        onInstallApk(file)
                    }
                }
                SheetAction("删除", Icons.Default.Delete) {
                    menuFile = null
                    pendingDelete = file
                }
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("文件", style = MaterialTheme.typography.headlineMedium)
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
        } else {
            // path + shortcuts
            item {
                Text(
                    state.remotePath,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        "下载" to "/sdcard/Download",
                        "存储卡" to "/sdcard",
                        "图片" to "/sdcard/Pictures",
                        "影片" to "/sdcard/Movies",
                        "音乐" to "/sdcard/Music",
                        "DCIM" to "/sdcard/DCIM",
                    ).forEach { (label, path) ->
                        FilterChip(
                            selected = state.remotePath == path,
                            onClick = { onShortcut(path) },
                            label = { Text(label) },
                        )
                    }
                }
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onGoUp,
                        modifier = Modifier.heightIn(min = MinTouch),
                    ) { Text("上级") }
                    OutlinedButton(
                        onClick = onRefresh,
                        enabled = !state.filesLoading && !state.transferring,
                        modifier = Modifier.heightIn(min = MinTouch),
                    ) {
                        if (state.filesLoading) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text("刷新")
                    }
                    BoxSort(
                        sort = state.fileSort,
                        expanded = showSortMenu,
                        onExpand = { showSortMenu = true },
                        onDismiss = { showSortMenu = false },
                        onSort = {
                            showSortMenu = false
                            onSort(it)
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            newFolderName = ""
                            showNewFolder = true
                        },
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "新建文件夹")
                    }
                }
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        enabled = !state.transferring,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        if (state.transferring) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("传输中")
                        } else {
                            Icon(Icons.Default.Upload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("上传")
                        }
                    }
                    OutlinedButton(
                        onClick = onPaste,
                        enabled = state.clipboard != null && !state.transferring,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            when {
                                state.clipboard == null -> "粘贴"
                                state.clipboard.isCut -> "粘贴(剪切)"
                                else -> "粘贴"
                            },
                        )
                    }
                }
            }
            state.clipboard?.let { clip ->
                item {
                    Text(
                        "${if (clip.isCut) "剪切" else "复制"}: ${clip.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onClearClipboard() },
                    )
                }
            }
        }

        state.filesBanner?.let { msg ->
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(msg, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (state.filesLoading && state.files.isEmpty() && state.connected) {
            item {
                Row(
                    Modifier.padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text("加载中…")
                }
            }
        }

        if (state.connected && !state.filesLoading && state.files.isEmpty()) {
            item {
                Text(
                    "此目录为空",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(state.files, key = { "${it.isDir}:${it.name}" }) { file ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .combinedClickable(
                            onClick = {
                                if (file.isDir) onOpenDir(file.name)
                                else menuFile = file
                            },
                            onLongClick = { menuFile = file },
                        )
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        when {
                            file.isDir -> Icons.Default.Folder
                            else -> Icons.AutoMirrored.Filled.InsertDriveFile
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            file.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            fileSubtitle(file),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { menuFile = file }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SheetAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun BoxSort(
    sort: FileSort,
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onSort: (FileSort) -> Unit,
) {
    Row {
        OutlinedButton(onClick = onExpand, modifier = Modifier.heightIn(min = MinTouch)) {
            Text(
                when (sort) {
                    FileSort.NameAsc -> "名称"
                    FileSort.NameDesc -> "名称↓"
                    FileSort.SizeDesc -> "大小"
                    FileSort.TimeDesc -> "时间"
                },
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            DropdownMenuItem(text = { Text("名称 A→Z") }, onClick = { onSort(FileSort.NameAsc) })
            DropdownMenuItem(text = { Text("名称 Z→A") }, onClick = { onSort(FileSort.NameDesc) })
            DropdownMenuItem(text = { Text("大小") }, onClick = { onSort(FileSort.SizeDesc) })
            DropdownMenuItem(text = { Text("修改时间") }, onClick = { onSort(FileSort.TimeDesc) })
        }
    }
}

@Composable
private fun PropertiesContent(props: RemoteFileProps) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("属性", style = MaterialTheme.typography.titleLarge)
        PropRow("名称", props.name)
        PropRow("路径", props.path)
        PropRow("类型", props.typeLabel + if (props.isLink) "（链接）" else "")
        if (props.linkTarget != null) PropRow("链接到", props.linkTarget)
        if (!props.isDir) PropRow("大小", formatSize(props.size) + "（${props.size} 字节）")
        PropRow("修改时间", formatTime(props.mtimeSec))
        PropRow("权限", props.permissions)
        PropRow("所有者", props.owner)
        PropRow("可读", if (props.readable) "是" else "否")
        PropRow("可写", if (props.writable) "是" else "否")
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PropRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun fileSubtitle(file: RemoteFile): String {
    val parts = mutableListOf<String>()
    if (file.isDir) parts.add("文件夹")
    else parts.add(formatSize(file.size))
    if (file.mtimeSec > 0) parts.add(formatTime(file.mtimeSec))
    if (file.permissions != "?" && file.permissions.isNotBlank()) parts.add(file.permissions)
    return parts.joinToString(" · ")
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return "${bytes / 1024} KB"
    if (bytes < 1024L * 1024 * 1024) return "%.1f MB".format(bytes / (1024.0 * 1024.0))
    return "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

private fun formatTime(epochSec: Long): String {
    if (epochSec <= 0) return "—"
    return try {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(epochSec * 1000))
    } catch (_: Exception) {
        "—"
    }
}
