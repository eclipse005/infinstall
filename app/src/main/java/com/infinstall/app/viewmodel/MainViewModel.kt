package com.infinstall.app.viewmodel

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.infinstall.app.adb.ErrorMessages
import com.infinstall.app.adb.RemoteFile
import com.infinstall.app.adb.TvSession
import com.infinstall.app.data.ConnectionHistoryStore
import com.infinstall.app.data.HostEntry
import com.infinstall.app.util.LocalNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class MainTab {
    Connect,
    Install,
    Files,
}

enum class ConnectMode {
    Direct,
    Pair,
}

data class UiState(
    val tab: MainTab = MainTab.Connect,
    val connectMode: ConnectMode = ConnectMode.Direct,
    val hostInput: String = "",
    /** This phone's LAN IP, e.g. 192.168.1.105 — for hint under IP field */
    val localIpv4: String? = null,
    val portInput: String = "5555",
    val pairPortInput: String = "",
    val pairCodeInput: String = "",
    val connecting: Boolean = false,
    val pairing: Boolean = false,
    val connected: Boolean = false,
    val connectedEndpoint: String? = null,
    val connectBanner: String? = null,
    val errorMessage: String? = null,
    val history: List<HostEntry> = emptyList(),
    val installing: Boolean = false,
    val installLog: List<String> = emptyList(),
    val installBanner: String? = null,
    // files
    val remotePath: String = "/sdcard/Download",
    val filesLoading: Boolean = false,
    val files: List<RemoteFile> = emptyList(),
    val filesBanner: String? = null,
    val transferring: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val historyStore = ConnectionHistoryStore(app)
    private val session = TvSession(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var heartbeatJob: Job? = null

    init {
        viewModelScope.launch {
            historyStore.history.collect { list ->
                _ui.update { it.copy(history = list) }
            }
        }
        // Prefill IP with this phone's Wi‑Fi subnet (e.g. 192.168.1.) so user only edits last digits
        viewModelScope.launch(Dispatchers.IO) {
            val localIp = LocalNetwork.primaryIpv4(getApplication())
            val suggested = LocalNetwork.suggestedHostInput(getApplication())
            _ui.update { state ->
                // Don't overwrite if user already typed or history restored something
                val host = if (state.hostInput.isBlank()) suggested else state.hostInput
                state.copy(hostInput = host, localIpv4 = localIp)
            }
        }
    }

    /** Re-detect Wi‑Fi IP (e.g. after switching network) and refresh default host if still empty/prefix-only. */
    fun refreshLocalNetworkHint() {
        viewModelScope.launch(Dispatchers.IO) {
            val localIp = LocalNetwork.primaryIpv4(getApplication())
            val suggested = LocalNetwork.suggestedHostInput(getApplication())
            _ui.update { state ->
                val cur = state.hostInput.trim()
                val shouldReplace =
                    cur.isEmpty() ||
                        cur.endsWith('.') ||
                        (localIp != null && cur == LocalNetwork.subnetPrefix(localIp))
                state.copy(
                    localIpv4 = localIp,
                    hostInput = if (shouldReplace && suggested.isNotEmpty()) suggested else state.hostInput,
                )
            }
        }
    }

    fun selectTab(tab: MainTab) {
        _ui.update { it.copy(tab = tab, errorMessage = null) }
        if (tab == MainTab.Files && session.isConnected && _ui.value.files.isEmpty()) {
            refreshFiles()
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
            _ui.update { it.copy(errorMessage = "请输入 IP 地址") }
            return
        }
        viewModelScope.launch {
            stopHeartbeat()
            _ui.update {
                it.copy(
                    connecting = true,
                    errorMessage = null,
                    connectBanner = "正在连接…",
                    hostInput = h,
                    portInput = p.toString(),
                    files = emptyList(),
                )
            }
            try {
                withTimeout(45_000) { session.connect(h, p) }
                historyStore.rememberSuccess(h, p)
                _ui.update {
                    it.copy(
                        connecting = false,
                        connected = true,
                        connectedEndpoint = "$h:$p",
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
            _ui.update { it.copy(errorMessage = "请输入 IP") }
            return
        }
        if (pairPort == null || pairPort <= 0) {
            _ui.update { it.copy(errorMessage = "请输入配对端口") }
            return
        }
        if (code.length < 5) {
            _ui.update { it.copy(errorMessage = "请输入 6 位配对码") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(pairing = true, errorMessage = null, connectBanner = "配对中…") }
            try {
                withTimeout(60_000) { session.pair(h, pairPort, code) }
                _ui.update {
                    it.copy(
                        pairing = false,
                        connectBanner = "配对成功，请填连接端口后点连接",
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
            session.disconnect()
            _ui.update {
                it.copy(
                    connected = false,
                    connectedEndpoint = null,
                    connectBanner = "已断开",
                    files = emptyList(),
                    installing = false,
                    transferring = false,
                )
            }
        }
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = viewModelScope.launch {
            while (isActive) {
                delay(2_500)
                if (!_ui.value.connected) continue
                // Pure TCP — never blocked by install mutex / stuck shell
                if (!session.isTcpAlive()) {
                    markRemoteGone("设备已断开（网络调试可能已关闭）")
                    break
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private suspend fun markRemoteGone(reason: String) {
        stopHeartbeat()
        runCatching { session.disconnect() }
        _ui.update {
            it.copy(
                connected = false,
                connectedEndpoint = null,
                connecting = false,
                installing = false,
                transferring = false,
                filesLoading = false,
                files = emptyList(),
                errorMessage = reason,
                tab = MainTab.Connect,
                connectBanner = null,
            )
        }
    }

    fun removeHistory(entry: HostEntry) {
        viewModelScope.launch { historyStore.remove(entry.host, entry.port) }
    }

    fun installFromUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (!session.isConnected) {
            _ui.update { it.copy(tab = MainTab.Connect, errorMessage = "请先连接设备") }
            return
        }
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    tab = MainTab.Install,
                    installing = true,
                    installLog = emptyList(),
                    installBanner = null,
                    errorMessage = null,
                )
            }
            val resolver = getApplication<Application>().contentResolver
            val cacheDir = getApplication<Application>().cacheDir
            val logs = mutableListOf<String>()
            fun append(line: String) {
                logs.add(line)
                _ui.update { s -> s.copy(installLog = logs.toList()) }
            }
            var failed = 0
            try {
                for ((index, uri) in uris.withIndex()) {
                    if (!session.isTcpAlive()) {
                        markRemoteGone("传输中设备断开")
                        return@launch
                    }
                    val name = queryDisplayName(uri) ?: "app_${index + 1}.apk"
                    try {
                        withTimeout(180_000) {
                            resolver.openInputStream(uri)?.use { input ->
                                session.installApk(input, name, cacheDir) { append(it) }
                            } ?: run {
                                failed++
                                append("无法读取 $name")
                            }
                        }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        failed++
                        append(ErrorMessages.humanize(t))
                        if (!session.isTcpAlive()) {
                            markRemoteGone("设备已断开")
                            return@launch
                        }
                    }
                }
                _ui.update {
                    it.copy(
                        installing = false,
                        installBanner = if (failed == 0) "安装完成" else "完成，失败 $failed 个",
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

    fun refreshFiles() {
        if (!session.isConnected) return
        viewModelScope.launch {
            _ui.update { it.copy(filesLoading = true, filesBanner = null) }
            try {
                val path = _ui.value.remotePath
                val list = withTimeout(25_000) { session.listDir(path) }
                _ui.update {
                    it.copy(filesLoading = false, files = list, filesBanner = null)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (!session.isTcpAlive()) {
                    markRemoteGone("设备已断开")
                } else {
                    _ui.update {
                        it.copy(
                            filesLoading = false,
                            filesBanner = "无法列出文件：${t.message ?: "错误"}",
                        )
                    }
                }
            }
        }
    }

    fun openRemoteDir(name: String) {
        val cur = _ui.value.remotePath.trimEnd('/')
        val next = "$cur/$name"
        _ui.update { it.copy(remotePath = next, files = emptyList()) }
        refreshFiles()
    }

    fun goUpRemote() {
        val cur = _ui.value.remotePath.trimEnd('/')
        if (cur.isEmpty() || cur == "/" || cur == "/sdcard") return
        val parent = cur.substringBeforeLast('/', "/sdcard").ifEmpty { "/sdcard" }
        _ui.update { it.copy(remotePath = parent, files = emptyList()) }
        refreshFiles()
    }

    fun setRemotePath(path: String) {
        _ui.update { it.copy(remotePath = path.trim().ifEmpty { "/sdcard/Download" }, files = emptyList()) }
        refreshFiles()
    }

    fun uploadUris(uris: List<Uri>) {
        if (uris.isEmpty() || !session.isConnected) return
        viewModelScope.launch {
            _ui.update { it.copy(transferring = true, filesBanner = null, tab = MainTab.Files) }
            val resolver = getApplication<Application>().contentResolver
            val base = _ui.value.remotePath.trimEnd('/')
            var failed = 0
            try {
                for (uri in uris) {
                    if (!session.isTcpAlive()) {
                        markRemoteGone("传输中设备断开")
                        return@launch
                    }
                    val name = queryDisplayName(uri) ?: "file_${System.currentTimeMillis()}"
                    val remote = "$base/$name"
                    try {
                        withTimeout(180_000) {
                            resolver.openInputStream(uri)?.use { input ->
                                session.pushToRemote(input, remote) { msg ->
                                    _ui.update { it.copy(filesBanner = msg) }
                                }
                            } ?: run { failed++ }
                        }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        failed++
                        _ui.update { it.copy(filesBanner = ErrorMessages.humanize(t)) }
                    }
                }
                _ui.update {
                    it.copy(
                        transferring = false,
                        filesBanner = if (failed == 0) "传输完成" else "完成，失败 $failed",
                    )
                }
                refreshFiles()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _ui.update {
                    it.copy(transferring = false, filesBanner = ErrorMessages.humanize(t))
                }
            }
        }
    }

    fun deleteRemote(file: RemoteFile) {
        if (!session.isConnected) return
        viewModelScope.launch {
            val path = _ui.value.remotePath.trimEnd('/') + "/" + file.name
            try {
                withTimeout(20_000) { session.deleteRemote(path) }
                _ui.update { it.copy(filesBanner = "已删除 ${file.name}") }
                refreshFiles()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (!session.isTcpAlive()) markRemoteGone("设备已断开")
                else _ui.update { it.copy(filesBanner = ErrorMessages.humanize(t)) }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val cr = getApplication<Application>().contentResolver
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) return c.getString(i)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    override fun onCleared() {
        stopHeartbeat()
        super.onCleared()
    }
}
