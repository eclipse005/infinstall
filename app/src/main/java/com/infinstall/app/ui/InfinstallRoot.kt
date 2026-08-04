package com.infinstall.app.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.infinstall.app.ui.screens.ConnectScreen
import com.infinstall.app.ui.screens.FilesScreen
import com.infinstall.app.ui.screens.InstallScreen
import com.infinstall.app.ui.theme.ContentMaxWidth
import com.infinstall.app.ui.theme.TabletBreakpoint
import com.infinstall.app.viewmodel.MainTab
import com.infinstall.app.viewmodel.MainViewModel

private data class NavItem(
    val tab: MainTab,
    val label: String,
    val icon: ImageVector,
)

private val navItems = listOf(
    NavItem(MainTab.Connect, "连接", Icons.Default.Tv),
    NavItem(MainTab.Install, "安装", Icons.Default.Download),
    NavItem(MainTab.Files, "文件", Icons.Default.Folder),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfinstallRoot(vm: MainViewModel) {
    val state by vm.ui.collectAsStateWithLifecycle()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val isTablet = maxWidth >= TabletBreakpoint

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("无限安装")
                            Text(
                                text = state.connectedEndpoint?.let { "已连接 · $it" } ?: "未连接",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            },
            bottomBar = {
                if (!isTablet) {
                    NavigationBar {
                        navItems.forEach { item ->
                            NavigationBarItem(
                                selected = state.tab == item.tab,
                                onClick = { vm.selectTab(item.tab) },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                if (isTablet) {
                    NavigationRail {
                        Spacer(Modifier.height(12.dp))
                        navItems.forEach { item ->
                            NavigationRailItem(
                                selected = state.tab == item.tab,
                                onClick = { vm.selectTab(item.tab) },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
                Surface(
                    Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = if (isTablet) 24.dp else 16.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val contentModifier = Modifier
                            .widthIn(max = ContentMaxWidth)
                            .fillMaxWidth()
                            .weight(1f)

                        when (state.tab) {
                            MainTab.Connect -> ConnectScreen(
                                state = state,
                                isTablet = isTablet,
                                onHostChange = vm::updateHost,
                                onPortChange = vm::updatePort,
                                onPairPortChange = vm::updatePairPort,
                                onPairCodeChange = vm::updatePairCode,
                                onConnectMode = vm::setConnectMode,
                                onConnect = { vm.connect() },
                                onPair = vm::pairDevice,
                                onDisconnect = vm::disconnect,
                                onPickHistory = { e -> vm.connect(e.host, e.port) },
                                onRemoveHistory = vm::removeHistory,
                                modifier = contentModifier,
                            )
                            MainTab.Install -> InstallScreen(
                                state = state,
                                onInstallUris = vm::installFromUris,
                                onGoConnect = { vm.selectTab(MainTab.Connect) },
                                modifier = contentModifier,
                            )
                            MainTab.Files -> FilesScreen(
                                state = state,
                                onRefresh = vm::refreshFiles,
                                onUpload = vm::uploadUris,
                                onOpenDir = vm::openRemoteDir,
                                onGoUp = vm::goUpRemote,
                                onDelete = vm::deleteRemote,
                                onGoConnect = { vm.selectTab(MainTab.Connect) },
                                onGoDownloadDir = { vm.setRemotePath("/sdcard/Download") },
                                modifier = contentModifier,
                            )
                        }
                    }
                }
            }
        }
    }
}
