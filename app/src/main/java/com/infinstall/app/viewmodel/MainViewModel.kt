package com.infinstall.app.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.infinstall.app.adb.ErrorMessages
import com.infinstall.app.adb.TvAppInfo
import com.infinstall.app.adb.TvSession
import com.infinstall.app.data.ConnectionHistoryStore
import com.infinstall.app.data.HostEntry
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

enum class ConnectMode {
    /** Main path for TV/box: IP + port */
    Direct,
    /** Secondary: pairing code (wireless debugging, uncommon for TVs) */
    Pair,
}

data class UiState(
    val tab: MainTab = MainTab.Connect,
    val connectMode: ConnectMode = ConnectMode.Direct,
    val hostInput: String = "",
    val portInput: String = "5555",
    val pairPortInput: String = "",
    val pairCodeInput: String = "",
    val connecting: Boolean = false,
    val pairing: Boolean = false,
    val connected: Boolean = false,
    val connectedEndpoint: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
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

    fun setConnectMode(mode: ConnectMode) {
        _ui.update { it.copy(connectMode = mode, errorMessage = null, statusMessage = null) }
    }

    fun updateHost(value: String) {
        _ui.update { it.copy(hostInput = value, errorMessage = null) }
    }

    fun updatePort(value: String) {
        _ui.update { it.copy(portInput = value.filter { ch -> ch.isDigit() }.take(5), errorMessage = null) }
    }

    fun updatePairPort(value: String) {
        _ui.update { it.copy(pairPortInput = value.filter { ch -> ch.isDigit() }.take(5), errorMessage = null) }
    }

    fun updatePairCode(value: String) {
        _ui.update { it.copy(pairCodeInput = value.filter { ch -> ch.isDigit() }.take(8), errorMessage = null) }
    }

    fun connect(host: String? = null, port: Int? = null) {
        val h = (host ?: _ui.value.hostInput).trim()
        val p = port ?: _ui.value.portInput.toIntOrNull() ?: 5555
        if (h.isEmpty()) {
            _ui.update { it.copy(errorMessage = "请输入设备的 IP 地址。") }
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
                        statusMessage = "已连接 $h:$p",
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

    fun pairDevice() {
        val h = _ui.value.hostInput.trim()
        val pairPort = _ui.value.pairPortInput.toIntOrNull()
        val code = _ui.value.pairCodeInput.trim()
        if (h.isEmpty()) {
            _ui.update { it.copy(errorMessage = "请输入设备 IP 地址。") }
            return
        }
        if (pairPort == null || pairPort <= 0) {
            _ui.update { it.copy(errorMessage = "请输入配对端口（在「使用配对码配对设备」界面上）。") }
            return
        }
        if (code.length < 5) {
            _ui.update { it.copy(errorMessage = "请输入设备上显示的配对码（一般为 6 位数字）。") }
            return
        }
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    pairing = true,
                    errorMessage = null,
                    statusMessage = "正在配对 $h:$pairPort …",
                )
            }
            try {
                session.pair(h, pairPort, code)
                _ui.update {
                    it.copy(
                        pairing = false,
                        statusMessage = "配对成功。请在上方填写连接端口，再点「连接」。",
                        errorMessage = null,
                        connectMode = ConnectMode.Direct,
                        pairCodeInput = "",
                    )
                }
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(
                        pairing = false,
                        statusMessage = null,
                        errorMessage = ErrorMessages.humanize(t, h, pairPort),
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
                    errorMessage = "请先连接设备，再安装应用。",
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
                        "安装完成（${uris.size}）"
                    } else {
                        "完成 ${uris.size - failed}，失败 $failed"
                    },
                )
            }
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
