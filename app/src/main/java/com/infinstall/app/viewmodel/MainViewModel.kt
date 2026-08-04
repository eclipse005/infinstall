package com.infinstall.app.viewmodel

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.infinstall.app.adb.ErrorMessages
import com.infinstall.app.adb.RemoteFile
import com.infinstall.app.adb.RemoteFileProps
import com.infinstall.app.adb.TvSession
import com.infinstall.app.adb.model.SessionState
import com.infinstall.app.data.ConnectionHistoryStore
import com.infinstall.app.data.HostEntry
import com.infinstall.app.util.LocalNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream

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
    /** Default to Install — primary product action */
    val tab: MainTab = MainTab.Install,
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
    /** 0f..1f during install/upload/download; null when idle */
    val transferProgress: Float? = null,
    val transferLabel: String? = null,
    // files
    val remotePath: String = "/sdcard/Download",
    val filesLoading: Boolean = false,
    val files: List<RemoteFile> = emptyList(),
    val filesBanner: String? = null,
    val transferring: Boolean = false,
    val fileSort: FileSort = FileSort.NameAsc,
    val clipboard: FileClipboard? = null,
    val propsLoading: Boolean = false,
    val fileProps: RemoteFileProps? = null,
    /** Local path after pull, for system share/open */
    val lastDownloadedLocalPath: String? = null,
)

enum class FileSort {
    NameAsc,
    NameDesc,
    SizeDesc,
    TimeDesc,
}

data class FileClipboard(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val isCut: Boolean,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val historyStore = ConnectionHistoryStore(app)
    private val session = TvSession(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var transferJob: Job? = null
    /** True while user-initiated connect/pair in flight (UI spinner). */
    private var userConnectInFlight = false

    init {
        viewModelScope.launch {
            historyStore.history.collect { list ->
                _ui.update { it.copy(history = list) }
            }
        }
        // Single source of truth: AdbSession state machine → UI connected flag.
        // Operation errors do NOT change this. Only connect/disconnect/transport-dead do.
        viewModelScope.launch {
            session.state.collect { st ->
                when (st) {
                    is SessionState.Connected -> {
                        if (!userConnectInFlight) {
                            _ui.update {
                                it.copy(
                                    connected = true,
                                    connectedEndpoint = st.endpoint,
                                    connecting = false,
                                )
                            }
                        }
                    }
                    is SessionState.Connecting -> {
                        _ui.update {
                            it.copy(
                                connecting = true,
                                connectBanner = "正在连接 ${st.host}:${st.port} …",
                            )
                        }
                    }
                    is SessionState.Pairing -> {
                        _ui.update {
                            it.copy(pairing = true, connectBanner = "配对中…")
                        }
                    }
                    SessionState.Disconnected -> {
                        // Do not clear banners/errors here if user is mid-connect attempt —
                        // connect() catch block owns failure UI.
                        if (!userConnectInFlight && _ui.value.connected) {
                            _ui.update {
                                it.copy(
                                    connected = false,
                                    connectedEndpoint = null,
                                    connecting = false,
                                    pairing = false,
                                    // Soft notice only when we were connected and session dropped
                                    connectBanner = it.connectBanner ?: "连接已结束",
                                )
                            }
                        }
                    }
                }
            }
        }
        // Prefill IP with this phone's Wi‑Fi subnet (e.g. 192.168.1.) so user only edits last digits
        viewModelScope.launch(Dispatchers.IO) {
            val localIp = LocalNetwork.primaryIpv4(getApplication())
            val suggested = LocalNetwork.suggestedHostInput(getApplication())
            _ui.update { state ->
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
        val rawPort = (port?.toString() ?: _ui.value.portInput).filter { it.isDigit() }
        val p = rawPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 5555
        if (h.isEmpty()) {
            _ui.update { it.copy(errorMessage = "请输入 IP 地址") }
            return
        }
        // Guard: pairing port must not be reused as connect port
        val pairPort = _ui.value.pairPortInput.toIntOrNull()
        if (pairPort != null && pairPort == p) {
            _ui.update {
                it.copy(
                    errorMessage = "端口 $p 是配对端口，不能用来连接。" +
                        "请填无线调试主页顶部的「连接端口」（和配对弹窗里的不同）。",
                    portInput = p.toString(),
                )
            }
            return
        }
        viewModelScope.launch {
            userConnectInFlight = true
            _ui.update {
                it.copy(
                    connecting = true,
                    errorMessage = null,
                    connectBanner = "正在连接 $h:$p …",
                    hostInput = h,
                    portInput = p.toString(),
                    files = emptyList(),
                )
            }
            try {
                // No outer withTimeout — AdbSession owns timeouts; cancel would race the mutex.
                session.connect(h, p)
                historyStore.rememberSuccess(h, p)
                _ui.update {
                    it.copy(
                        connecting = false,
                        connected = true,
                        connectedEndpoint = "$h:$p",
                        connectBanner = null,
                        errorMessage = null,
                        tab = MainTab.Install,
                    )
                }
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
            } finally {
                userConnectInFlight = false
            }
        }
    }

    fun pairDevice() {
        val h = _ui.value.hostInput.trim()
        val pairPort = _ui.value.pairPortInput.toIntOrNull()
        val code = _ui.value.pairCodeInput.filter { it.isDigit() }
        if (h.isEmpty()) {
            _ui.update { it.copy(errorMessage = "请输入 IP") }
            return
        }
        if (pairPort == null || pairPort !in 1..65535) {
            _ui.update { it.copy(errorMessage = "请输入配对端口") }
            return
        }
        if (code.length < 5) {
            _ui.update { it.copy(errorMessage = "请输入 6 位配对码") }
            return
        }
        viewModelScope.launch {
            userConnectInFlight = true
            _ui.update { it.copy(pairing = true, errorMessage = null, connectBanner = "配对中…") }
            try {
                session.pair(h, pairPort, code)
                _ui.update {
                    it.copy(
                        pairing = false,
                        connectBanner = "配对成功！请填上方「连接端口」" +
                            "（无线调试主页顶部 IP:端口，不是配对端口 $pairPort）",
                        connectMode = ConnectMode.Direct,
                        pairCodeInput = "",
                        portInput = "",
                        errorMessage = null,
                        tab = MainTab.Connect,
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
            } finally {
                userConnectInFlight = false
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            cancelTransfer()
            session.disconnect()
            _ui.update {
                it.copy(
                    connected = false,
                    connectedEndpoint = null,
                    connectBanner = "已断开",
                    files = emptyList(),
                    installing = false,
                    transferring = false,
                    transferProgress = null,
                    transferLabel = null,
                    errorMessage = null,
                )
            }
        }
    }

    fun cancelTransfer() {
        session.requestCancel()
        transferJob?.cancel()
        transferJob = null
        _ui.update { s ->
            val wasInstalling = s.installing
            val wasTransferring = s.transferring
            s.copy(
                installing = false,
                transferring = false,
                transferProgress = null,
                transferLabel = null,
                installBanner = if (wasInstalling) "已取消安装" else s.installBanner,
                filesBanner = if (wasTransferring) "已取消传输" else s.filesBanner,
            )
        }
    }

    fun removeHistory(entry: HostEntry) {
        viewModelScope.launch { historyStore.remove(entry.host, entry.port) }
    }

    fun installFromUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (!session.isConnected) {
            _ui.update {
                it.copy(
                    tab = MainTab.Connect,
                    errorMessage = "请先连接电视/设备，再安装",
                )
            }
            return
        }
        transferJob?.cancel()
        session.clearCancel()
        transferJob = viewModelScope.launch {
            _ui.update {
                it.copy(
                    tab = MainTab.Install,
                    installing = true,
                    installLog = emptyList(),
                    installBanner = null,
                    errorMessage = null,
                    transferProgress = 0f,
                    transferLabel = "准备中…",
                )
            }
            val resolver = getApplication<Application>().contentResolver
            val cacheDir = getApplication<Application>().cacheDir
            // Detail log: only milestones / errors — never per-% spam (progress bar covers that)
            val logs = mutableListOf<String>()
            fun appendImportant(line: String) {
                val t = line.trim()
                if (t.isEmpty()) return
                // Drop pure progress lines if any slip through
                if (t.matches(Regex("""传输\s*\d+%"""))) return
                if (t.matches(Regex("""\d+%"""))) return
                logs.add(t)
                _ui.update { s -> s.copy(installLog = logs.toList()) }
            }
            var failed = 0
            var lastError: String? = null
            try {
                for ((index, uri) in uris.withIndex()) {
                    if (!session.isConnected) {
                        appendImportant("未连接设备")
                        lastError = "未连接设备"
                        break
                    }
                    val name = queryDisplayName(uri) ?: "app_${index + 1}.apk"
                    appendImportant(name)
                    try {
                        resolver.openInputStream(uri)?.use { input ->
                            session.installApk(input, name, cacheDir) { p ->
                                // Progress bar only — do not flood installLog
                                _ui.update {
                                    it.copy(
                                        transferProgress = p.fraction,
                                        transferLabel = p.label,
                                    )
                                }
                            }
                        } ?: run {
                            failed++
                            val msg = "无法读取文件"
                            lastError = msg
                            appendImportant("失败：$msg")
                            return@for
                        }
                        appendImportant("安装成功")
                    } catch (t: Throwable) {
                        if (t is CancellationException ||
                            t.message?.contains("取消") == true ||
                            t is com.infinstall.app.adb.TransferCancelledException
                        ) {
                            appendImportant("已取消")
                            _ui.update {
                                it.copy(
                                    installing = false,
                                    transferProgress = null,
                                    transferLabel = null,
                                    installBanner = "已取消",
                                )
                            }
                            return@launch
                        }
                        failed++
                        val msg = ErrorMessages.humanize(t)
                        lastError = msg
                        appendImportant(msg)
                    }
                }
                val banner = when {
                    failed == 0 && logs.isNotEmpty() -> "全部安装完成"
                    failed == 0 -> "安装完成"
                    uris.size == 1 -> lastError ?: "安装失败"
                    else -> "完成：成功 ${uris.size - failed}，失败 $failed"
                }
                _ui.update {
                    it.copy(
                        installing = false,
                        transferProgress = null,
                        transferLabel = null,
                        installBanner = banner,
                        // Single-file failure also shows as error card for visibility
                        errorMessage = if (failed > 0 && uris.size == 1) lastError else null,
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    _ui.update {
                        it.copy(
                            installing = false,
                            transferProgress = null,
                            installBanner = "已取消",
                        )
                    }
                    throw t
                }
                val msg = ErrorMessages.humanize(t)
                _ui.update {
                    it.copy(
                        installing = false,
                        transferProgress = null,
                        transferLabel = null,
                        installBanner = null,
                        errorMessage = msg,
                        installLog = logs + msg,
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
                // AdbSession owns list timeout.
                val list = session.listDir(path)
                _ui.update {
                    it.copy(
                        filesLoading = false,
                        files = sortFiles(list, it.fileSort),
                        filesBanner = null,
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _ui.update {
                    it.copy(
                        filesLoading = false,
                        filesBanner = "无法列出文件：${ErrorMessages.humanize(t)}",
                    )
                }
            }
        }
    }

    fun setFileSort(sort: FileSort) {
        _ui.update { state ->
            state.copy(fileSort = sort, files = sortFiles(state.files, sort))
        }
    }

    private fun sortFiles(list: List<RemoteFile>, sort: FileSort): List<RemoteFile> {
        val dirsFirst = compareBy<RemoteFile> { !it.isDir }
        return when (sort) {
            FileSort.NameAsc -> list.sortedWith(dirsFirst.thenBy { it.name.lowercase() })
            FileSort.NameDesc -> list.sortedWith(dirsFirst.thenByDescending { it.name.lowercase() })
            FileSort.SizeDesc -> list.sortedWith(dirsFirst.thenByDescending { it.size })
            FileSort.TimeDesc -> list.sortedWith(dirsFirst.thenByDescending { it.mtimeSec })
        }
    }

    fun openRemoteDir(name: String) {
        val cur = _ui.value.remotePath.trimEnd('/')
        val next = if (cur.isEmpty() || cur == "/") "/$name" else "$cur/$name"
        _ui.update { it.copy(remotePath = next, files = emptyList()) }
        refreshFiles()
    }

    fun goUpRemote() {
        val cur = _ui.value.remotePath.trimEnd('/')
        if (cur.isEmpty() || cur == "/") return
        val parent = if (!cur.contains('/')) "/"
        else cur.substringBeforeLast('/').ifEmpty { "/" }
        _ui.update { it.copy(remotePath = parent.ifEmpty { "/" }, files = emptyList()) }
        refreshFiles()
    }

    fun setRemotePath(path: String) {
        val p = path.trim().ifEmpty { "/sdcard/Download" }
        _ui.update { it.copy(remotePath = p, files = emptyList()) }
        refreshFiles()
    }

    fun uploadUris(uris: List<Uri>) {
        if (uris.isEmpty() || !session.isConnected) return
        transferJob?.cancel()
        session.clearCancel()
        transferJob = viewModelScope.launch {
            _ui.update {
                it.copy(
                    transferring = true,
                    filesBanner = null,
                    tab = MainTab.Files,
                    transferProgress = 0f,
                    transferLabel = "准备上传…",
                )
            }
            val resolver = getApplication<Application>().contentResolver
            val cacheDir = getApplication<Application>().cacheDir
            val base = _ui.value.remotePath.trimEnd('/').ifEmpty { "/sdcard" }
            var failed = 0
            try {
                for (uri in uris) {
                    if (!session.isConnected) {
                        break
                    }
                    val name = queryDisplayName(uri) ?: "file_${System.currentTimeMillis()}"
                    val remote = "$base/$name"
                    try {
                        resolver.openInputStream(uri)?.use { input ->
                            session.pushToRemote(input, remote, cacheDir) { p ->
                                _ui.update {
                                    it.copy(
                                        filesBanner = p.label,
                                        transferProgress = p.fraction,
                                        transferLabel = p.label,
                                    )
                                }
                            }
                        } ?: run { failed++ }
                    } catch (t: Throwable) {
                        if (t is CancellationException ||
                            t is com.infinstall.app.adb.TransferCancelledException
                        ) {
                            _ui.update {
                                it.copy(
                                    transferring = false,
                                    transferProgress = null,
                                    transferLabel = null,
                                    filesBanner = "已取消",
                                )
                            }
                            return@launch
                        }
                        failed++
                        _ui.update { it.copy(filesBanner = ErrorMessages.humanize(t)) }
                    }
                }
                _ui.update {
                    it.copy(
                        transferring = false,
                        transferProgress = null,
                        transferLabel = null,
                        filesBanner = if (failed == 0) "上传完成" else "上传完成，失败 $failed",
                    )
                }
                refreshFiles()
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    _ui.update {
                        it.copy(transferring = false, transferProgress = null, filesBanner = "已取消")
                    }
                    throw t
                }
                _ui.update {
                    it.copy(
                        transferring = false,
                        transferProgress = null,
                        filesBanner = ErrorMessages.humanize(t),
                    )
                }
            }
        }
    }

    fun deleteRemote(file: RemoteFile) {
        if (!session.isConnected) return
        viewModelScope.launch {
            val path = file.fullPath(_ui.value.remotePath)
            // Optimistic remove so UI feels instant; refresh reconciles.
            val prev = _ui.value.files
            _ui.update {
                it.copy(
                    filesBanner = "正在删除 ${file.name}…",
                    filesLoading = true,
                    files = it.files.filterNot { f -> f.name == file.name },
                )
            }
            try {
                session.deleteRemote(path)
                _ui.update { it.copy(filesBanner = "已删除 ${file.name}", filesLoading = false) }
                // Soft refresh — failure only updates banner, keeps session
                try {
                    val list = session.listDir(_ui.value.remotePath)
                    _ui.update {
                        it.copy(files = sortFiles(list, it.fileSort), filesLoading = false)
                    }
                } catch (refreshErr: Throwable) {
                    if (refreshErr is CancellationException) throw refreshErr
                    // Delete already done; don't scare user with refresh glitch as "通信失败"
                    _ui.update {
                        it.copy(
                            filesLoading = false,
                            filesBanner = "已删除 ${file.name}（列表刷新稍后重试）",
                        )
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // Restore list on hard delete failure
                _ui.update {
                    it.copy(
                        files = prev,
                        filesBanner = ErrorMessages.humanize(t),
                        filesLoading = false,
                    )
                }
            }
        }
    }

    fun createFolder(name: String) {
        val n = name.trim()
        if (n.isEmpty() || n.contains('/')) {
            _ui.update { it.copy(filesBanner = "文件夹名无效") }
            return
        }
        if (!session.isConnected) return
        viewModelScope.launch {
            val path = _ui.value.remotePath.trimEnd('/') + "/" + n
            try {
                session.mkdirRemote(path)
                _ui.update { it.copy(filesBanner = "已创建 $n") }
                refreshFiles()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _ui.update { it.copy(filesBanner = ErrorMessages.humanize(t)) }
            }
        }
    }

    fun renameRemote(file: RemoteFile, newName: String) {
        val n = newName.trim()
        if (n.isEmpty() || n.contains('/')) {
            _ui.update { it.copy(filesBanner = "名称无效") }
            return
        }
        if (!session.isConnected) return
        viewModelScope.launch {
            val from = file.fullPath(_ui.value.remotePath)
            val to = _ui.value.remotePath.trimEnd('/') + "/" + n
            try {
                session.renameRemote(from, to)
                _ui.update { it.copy(filesBanner = "已重命名为 $n") }
                refreshFiles()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _ui.update { it.copy(filesBanner = ErrorMessages.humanize(t)) }
            }
        }
    }

    fun copyToClipboard(file: RemoteFile, cut: Boolean) {
        val path = file.fullPath(_ui.value.remotePath)
        _ui.update {
            it.copy(
                clipboard = FileClipboard(path, file.name, file.isDir, isCut = cut),
                filesBanner = if (cut) "已剪切 ${file.name}" else "已复制 ${file.name}",
            )
        }
    }

    fun clearClipboard() {
        _ui.update { it.copy(clipboard = null) }
    }

    fun pasteClipboard() {
        val clip = _ui.value.clipboard ?: return
        if (!session.isConnected) return
        viewModelScope.launch {
            val destDir = _ui.value.remotePath.trimEnd('/')
            var dest = "$destDir/${clip.name}"
            // avoid overwrite: if same path, add suffix
            if (dest == clip.path) {
                dest = "$destDir/copy_${clip.name}"
            }
            _ui.update { it.copy(transferring = true, filesBanner = "粘贴中…") }
            try {
                if (clip.isCut) {
                    session.moveRemote(clip.path, dest)
                } else {
                    session.copyRemote(clip.path, dest)
                }
                _ui.update {
                    it.copy(
                        transferring = false,
                        clipboard = if (clip.isCut) null else clip,
                        filesBanner = "已粘贴 ${clip.name}",
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

    fun loadProps(file: RemoteFile) {
        if (!session.isConnected) {
            _ui.update { it.copy(filesBanner = "未连接设备") }
            return
        }
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    propsLoading = true,
                    fileProps = null,
                    filesBanner = "正在读取属性…",
                )
            }
            try {
                val path = file.fullPath(_ui.value.remotePath)
                val props = session.statRemote(path)
                _ui.update {
                    it.copy(
                        propsLoading = false,
                        fileProps = props,
                        filesBanner = null,
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val basic = RemoteFileProps(
                    path = file.fullPath(_ui.value.remotePath),
                    name = file.name,
                    isDir = file.isDir,
                    isLink = file.isLink,
                    size = file.size,
                    mtimeSec = file.mtimeSec,
                    permissions = file.permissions,
                    owner = "?",
                    typeLabel = if (file.isDir) "文件夹" else "文件",
                    readable = true,
                    writable = true,
                    linkTarget = null,
                )
                _ui.update {
                    it.copy(
                        propsLoading = false,
                        fileProps = basic,
                        // Only banner if we have zero info; otherwise just show sheet
                        filesBanner = if (file.size <= 0 && file.permissions == "?") {
                            "属性不完整：${t.message ?: "请重试"}"
                        } else null,
                    )
                }
            }
        }
    }

    fun dismissProps() {
        _ui.update { it.copy(fileProps = null, propsLoading = false) }
    }

    fun downloadRemote(file: RemoteFile, destUri: Uri) {
        if (!session.isConnected || file.isDir) return
        viewModelScope.launch {
            _ui.update { it.copy(transferring = true, filesBanner = "下载中…") }
            val cache = File(getApplication<Application>().cacheDir, "dl_${System.currentTimeMillis()}_${file.name}")
            try {
                val remote = file.fullPath(_ui.value.remotePath)
                session.pullToLocal(remote, cache) { got ->
                    if (file.size > 0) {
                        _ui.update {
                            it.copy(filesBanner = "下载 ${got * 100 / file.size.coerceAtLeast(1)}%")
                        }
                    }
                }
                getApplication<Application>().contentResolver.openOutputStream(destUri)?.use { out ->
                    FileInputStream(cache).use { it.copyTo(out) }
                } ?: error("无法写入保存位置")
                _ui.update {
                    it.copy(
                        transferring = false,
                        filesBanner = "已保存到手机",
                        lastDownloadedLocalPath = destUri.toString(),
                    )
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _ui.update {
                    it.copy(transferring = false, filesBanner = ErrorMessages.humanize(t))
                }
            } finally {
                cache.delete()
            }
        }
    }

    fun installRemoteApk(file: RemoteFile) {
        if (!session.isConnected || file.isDir) return
        if (!file.name.endsWith(".apk", ignoreCase = true)) {
            _ui.update { it.copy(filesBanner = "只能安装 .apk 文件") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(transferring = true, filesBanner = "正在安装 ${file.name}…") }
            try {
                val remote = file.fullPath(_ui.value.remotePath)
                session.installRemoteApk(remote)
                _ui.update {
                    it.copy(transferring = false, filesBanner = "安装成功：${file.name}")
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _ui.update {
                    it.copy(transferring = false, filesBanner = ErrorMessages.humanize(t))
                }
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
        transferJob?.cancel()
        session.requestCancel()
        super.onCleared()
    }
}
