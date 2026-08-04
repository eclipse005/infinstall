package com.infinstall.app.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.infinstall.app.adb.AdbKeys
import com.infinstall.app.adb.DiscoveredDevice
import com.infinstall.app.adb.ErrorMessages
import com.infinstall.app.adb.LanScanner
import com.infinstall.app.adb.TvAppInfo
import com.infinstall.app.adb.TvSession
import com.infinstall.app.data.ConnectionHistoryStore
import com.infinstall.app.data.HostEntry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MainTab {
    Connect,
    Install,
    Apps,
}

data class UiState(
    val tab: MainTab = MainTab.Connect,
    val hostInput: String = "",
    val portInput: String = "5555",
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val connectedEndpoint: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val scanning: Boolean = false,
    val scanProgress: Pair<Int, Int>? = null,
    val discovered: List<DiscoveredDevice> = emptyList(),
    val history: List<HostEntry> = emptyList(),
    val installing: Boolean = false,
    val installLog: List<String> = emptyList(),
    val appsLoading: Boolean = false,
    val apps: List<TvAppInfo> = emptyList(),
    val uninstallingPackage: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val historyStore = ConnectionHistoryStore(app)
    private val session = TvSession(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            historyStore.history.collect { list ->
                _ui.update { it.copy(history = list) }
            }
        }
    }

    fun selectTab(tab: MainTab) {
        _ui.update { it.copy(tab = tab, errorMessage = null) }
        if (tab == MainTab.Apps && session.isConnected) {
            refreshApps()
        }
    }

    fun updateHost(value: String) {
        _ui.update { it.copy(hostInput = value, errorMessage = null) }
    }

    fun updatePort(value: String) {
        _ui.update { it.copy(portInput = value.filter { ch -> ch.isDigit() }.take(5), errorMessage = null) }
    }

    fun clearMessages() {
        _ui.update { it.copy(errorMessage = null, statusMessage = null) }
    }

    fun connect(host: String? = null, port: Int? = null) {
        val h = (host ?: _ui.value.hostInput).trim()
        val p = port ?: _ui.value.portInput.toIntOrNull() ?: 5555
        if (h.isEmpty()) {
            _ui.update { it.copy(errorMessage = "请输入电视的 IP 地址，或先扫描设备。") }
            return
        }
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    connecting = true,
                    errorMessage = null,
                    statusMessage = "正在连接 $h:$p …",
                    hostInput = h,
                    portInput = p.toString(),
                )
            }
            try {
                session.connect(h, p)
                historyStore.rememberSuccess(h, p)
                _ui.update {
                    it.copy(
                        connecting = false,
                        connected = true,
                        connectedEndpoint = "$h:$p",
                        statusMessage = "已连接 $h:$p。若电视弹出授权提示，请在电视上点「允许」。",
                        errorMessage = null,
                    )
                }
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(
                        connecting = false,
                        connected = false,
                        connectedEndpoint = null,
                        statusMessage = null,
                        errorMessage = ErrorMessages.humanize(t, h, p),
                    )
                }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            session.disconnect()
            _ui.update {
                it.copy(
                    connected = false,
                    connectedEndpoint = null,
                    statusMessage = "已断开连接",
                    apps = emptyList(),
                )
            }
        }
    }

    fun startScan() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _ui.update {
                it.copy(
                    scanning = true,
                    scanProgress = 0 to 1,
                    discovered = emptyList(),
                    errorMessage = null,
                    statusMessage = "正在扫描局域网，请稍候…",
                )
            }
            try {
                val keyPair = AdbKeys.loadOrCreate(getApplication())
                val list = LanScanner.scan(
                    keyPair = keyPair,
                    ports = listOf(5555),
                    onProgress = { phase, done, total ->
                        val label = when (phase) {
                            "adb" -> "正在确认是否为可安装的电视/盒子"
                            else -> "正在扫描局域网端口"
                        }
                        _ui.update { state ->
                            state.copy(
                                scanProgress = done to total,
                                statusMessage = "$label（$done / $total）…",
                            )
                        }
                    },
                )
                _ui.update {
                    it.copy(
                        scanning = false,
                        scanProgress = null,
                        discovered = list,
                        statusMessage = if (list.isEmpty()) {
                            "未发现已开启网络调试的设备。可检查电视设置，或改用手动输入 IP。"
                        } else {
                            "发现 ${list.size} 台可用设备（已排除仅端口开放、不是调试服务的主机），点选即可连接。"
                        },
                    )
                }
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(
                        scanning = false,
                        scanProgress = null,
                        errorMessage = ErrorMessages.humanize(t),
                        statusMessage = null,
                    )
                }
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        _ui.update {
            it.copy(scanning = false, scanProgress = null, statusMessage = "已停止扫描")
        }
    }

    fun removeHistory(entry: HostEntry) {
        viewModelScope.launch {
            historyStore.remove(entry.host, entry.port)
        }
    }

    fun installFromUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (!session.isConnected) {
            _ui.update {
                it.copy(
                    tab = MainTab.Connect,
                    errorMessage = "请先连接电视，再安装应用。",
                )
            }
            return
        }
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    tab = MainTab.Install,
                    installing = true,
                    installLog = emptyList(),
                    errorMessage = null,
                    statusMessage = null,
                )
            }
            val resolver = getApplication<Application>().contentResolver
            val cacheDir = getApplication<Application>().cacheDir
            val logs = mutableListOf<String>()
            fun append(line: String) {
                logs.add(line)
                _ui.update { state -> state.copy(installLog = logs.toList()) }
            }
            var failed = 0
            uris.forEachIndexed { index, uri ->
                val name = uri.lastPathSegment?.substringAfterLast('/')
                    ?: "应用 ${index + 1}.apk"
                try {
                    resolver.openInputStream(uri)?.use { input ->
                        session.installApk(input, name, cacheDir) { status ->
                            append(status)
                        }
                    } ?: run {
                        failed++
                        append("无法读取文件：$name")
                    }
                } catch (t: Throwable) {
                    failed++
                    append(ErrorMessages.humanize(t))
                }
            }
            _ui.update {
                it.copy(
                    installing = false,
                    statusMessage = if (failed == 0) {
                        "全部安装完成（${uris.size} 个）"
                    } else {
                        "完成：成功 ${uris.size - failed}，失败 $failed"
                    },
                )
            }
            // refresh apps list quietly
            if (session.isConnected) {
                runCatching { refreshAppsInternal() }
            }
        }
    }

    fun refreshApps() {
        viewModelScope.launch {
            try {
                refreshAppsInternal()
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(
                        appsLoading = false,
                        errorMessage = ErrorMessages.humanize(t, session.host, session.port),
                    )
                }
            }
        }
    }

    private suspend fun refreshAppsInternal() {
        _ui.update { it.copy(appsLoading = true, errorMessage = null) }
        val apps = session.listThirdPartyApps()
        _ui.update { it.copy(appsLoading = false, apps = apps) }
    }

    fun uninstall(packageName: String) {
        if (!session.isConnected) return
        viewModelScope.launch {
            _ui.update { it.copy(uninstallingPackage = packageName, errorMessage = null) }
            try {
                session.uninstall(packageName)
                val apps = session.listThirdPartyApps()
                _ui.update {
                    it.copy(
                        uninstallingPackage = null,
                        apps = apps,
                        statusMessage = "已卸载 $packageName",
                    )
                }
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(
                        uninstallingPackage = null,
                        errorMessage = ErrorMessages.humanize(t, session.host, session.port),
                    )
                }
            }
        }
    }
}
