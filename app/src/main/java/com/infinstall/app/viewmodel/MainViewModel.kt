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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class MainTab {
    Connect,
    Install,
    Apps,
}

enum class ConnectMode {
    Direct,
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
    /** Connect-tab only messages (pair hints, disconnect notice). Not shown on Install/Apps. */
    val connectBanner: String? = null,
    val errorMessage: String? = null,
    val history: List<HostEntry> = emptyList(),
    val installing: Boolean = false,
    val installLog: List<String> = emptyList(),
    val installBanner: String? = null,
    val appsLoading: Boolean = false,
    val apps: List<TvAppInfo> = emptyList(),
    val appsBanner: String? = null,
    val uninstallingPackage: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val historyStore = ConnectionHistoryStore(app)
    private val session = TvSession(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var heartbeatJob: Job? = null
    private var appsJob: Job? = null
    private var labelJob: Job? = null

    init {
        viewModelScope.launch {
            historyStore.history.collect { list ->
                _ui.update { it.copy(history = list) }
            }
        }
    }

    fun selectTab(tab: MainTab) {
        _ui.update {
            it.copy(
                tab = tab,
                // don't carry connection "已连接" onto other tabs
                errorMessage = if (tab == MainTab.Connect) it.errorMessage else null,
            )
        }
        if (tab == MainTab.Apps && session.isConnected) {
            // only load if empty; avoid re-scan loop every time user opens tab
            if (_ui.value.apps.isEmpty() && !_ui.value.appsLoading) {
                refreshApps()
            }
        }
    }

    fun setConnectMode(mode: ConnectMode) {
        _ui.update { it.copy(connectMode = mode, errorMessage = null, connectBanner = null) }
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
            stopHeartbeat()
            appsJob?.cancel()
            labelJob?.cancel()
            _ui.update {
                it.copy(
                    connecting = true,
                    errorMessage = null,
                    connectBanner = "正在连接…",
                    hostInput = h,
                    portInput = p.toString(),
                    apps = emptyList(),
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
                        // 顶部栏已显示已连接，这里不再重复「已连接」
                        connectBanner = null,
                        errorMessage = null,
                    )
                }
                startHeartbeat()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _ui.update {
                    it.copy(
                        connecting = false,
                        connected = false,
                        connectedEndpoint = null,
                        connectBanner = null,
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
            _ui.update { it.copy(errorMessage = "请输入配对端口。") }
            return
        }
        if (code.length < 5) {
            _ui.update { it.copy(errorMessage = "请输入 6 位配对码。") }
            return
        }
        viewModelScope.launch {
            _ui.update {
                it.copy(pairing = true, errorMessage = null, connectBanner = "正在配对…")
            }
            try {
                session.pair(h, pairPort, code)
                _ui.update {
                    it.copy(
                        pairing = false,
                        connectBanner = "配对成功，请填写连接端口后点「连接」",
                        errorMessage = null,
                        connectMode = ConnectMode.Direct,
                        pairCodeInput = "",
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _ui.update {
                    it.copy(
                        pairing = false,
                        connectBanner = null,
                        errorMessage = ErrorMessages.humanize(t, h, pairPort),
                    )
                }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            stopHeartbeat()
            appsJob?.cancel()
            labelJob?.cancel()
            session.disconnect()
            _ui.update {
                it.copy(
                    connected = false,
                    connectedEndpoint = null,
                    connectBanner = "已断开",
                    apps = emptyList(),
                    appsLoading = false,
                    installing = false,
                )
            }
        }
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = viewModelScope.launch {
            while (isActive) {
                delay(3_500)
                if (!_ui.value.connected) continue
                if (_ui.value.installing) continue // don't interfere with transfer
                val alive = try {
                    session.ping()
                } catch (_: Throwable) {
                    false
                }
                if (!alive) {
                    markRemoteGone()
                    break
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private suspend fun markRemoteGone() {
        stopHeartbeat()
        appsJob?.cancel()
        labelJob?.cancel()
        runCatching { session.disconnect() }
        _ui.update {
            it.copy(
                connected = false,
                connectedEndpoint = null,
                connectBanner = null,
                apps = emptyList(),
                appsLoading = false,
                installing = false,
                // surface on all tabs via error on current + connect banner
                errorMessage = "设备已断开。可能已关闭网络调试，或网络中断。请重新连接。",
                tab = MainTab.Connect,
            )
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
                    errorMessage = "请先连接设备",
                )
            }
            return
        }
        // cancel heavy apps work so mutex is free for install
        appsJob?.cancel()
        labelJob?.cancel()
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    tab = MainTab.Install,
                    installing = true,
                    installLog = emptyList(),
                    installBanner = null,
                    errorMessage = null,
                    appsLoading = false,
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
            try {
                uris.forEachIndexed { index, uri ->
                    if (!session.isConnected) {
                        markRemoteGone()
                        return@launch
                    }
                    val name = uri.lastPathSegment?.substringAfterLast('/')
                        ?: "应用 ${index + 1}.apk"
                    try {
                        resolver.openInputStream(uri)?.use { input ->
                            session.installApk(input, name, cacheDir) { status ->
                                append(status)
                            }
                        } ?: run {
                            failed++
                            append("无法读取：$name")
                        }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        failed++
                        append(ErrorMessages.humanize(t))
                        if (!session.isConnected || t.message?.contains("断开") == true) {
                            markRemoteGone()
                            return@launch
                        }
                    }
                }
                _ui.update {
                    it.copy(
                        installing = false,
                        installBanner = if (failed == 0) {
                            "安装完成（${uris.size}）"
                        } else {
                            "完成 ${uris.size - failed}，失败 $failed"
                        },
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _ui.update {
                    it.copy(
                        installing = false,
                        installBanner = null,
                        errorMessage = ErrorMessages.humanize(t),
                    )
                }
            }
        }
    }

    fun refreshApps() {
        if (!session.isConnected) return
        appsJob?.cancel()
        appsJob = viewModelScope.launch {
            _ui.update {
                it.copy(appsLoading = true, appsBanner = null, errorMessage = null)
            }
            try {
                val apps = withTimeout(25_000) {
                    session.listThirdPartyApps()
                }
                _ui.update {
                    it.copy(
                        appsLoading = false,
                        apps = apps,
                        appsBanner = if (apps.isEmpty()) "暂无第三方应用" else null,
                    )
                }
                // background: improve a few labels without blocking UI
                enrichLabels(apps)
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    _ui.update { it.copy(appsLoading = false) }
                    throw t
                }
                val dead = !session.isConnected ||
                    t.message?.contains("Not connected", ignoreCase = true) == true ||
                    t.message?.contains("未连接", ignoreCase = true) == true
                if (dead) {
                    markRemoteGone()
                } else {
                    _ui.update {
                        it.copy(
                            appsLoading = false,
                            appsBanner = "应用列表加载失败，可点刷新重试",
                            errorMessage = null,
                        )
                    }
                }
            }
        }
    }

    private fun enrichLabels(apps: List<TvAppInfo>) {
        labelJob?.cancel()
        labelJob = viewModelScope.launch {
            // only first 30 to keep light
            val updated = apps.toMutableList()
            var changed = false
            for (i in updated.indices) {
                if (!isActive || !session.isConnected) break
                if (_ui.value.installing) break
                val app = updated[i]
                val better = session.resolveLabel(app.packageName) ?: continue
                if (better.isNotBlank() && better != app.label) {
                    updated[i] = app.copy(label = better)
                    changed = true
                    if (changed && i % 3 == 0) {
                        _ui.update { it.copy(apps = updated.sortedBy { a -> a.label.lowercase() }) }
                    }
                }
                delay(50)
            }
            if (changed) {
                _ui.update { it.copy(apps = updated.sortedBy { a -> a.label.lowercase() }) }
            }
        }
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
                        appsBanner = "已卸载",
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (!session.isConnected) {
                    markRemoteGone()
                } else {
                    _ui.update {
                        it.copy(
                            uninstallingPackage = null,
                            appsBanner = ErrorMessages.humanize(t, session.host, session.port),
                        )
                    }
                }
            }
        }
    }

    override fun onCleared() {
        stopHeartbeat()
        appsJob?.cancel()
        labelJob?.cancel()
        super.onCleared()
    }
}
