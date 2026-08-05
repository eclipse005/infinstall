package com.infinstall.app.adb.session

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.infinstall.app.adb.InfinstallAdbManager
import com.infinstall.app.adb.model.AdbException
import com.infinstall.app.adb.model.RemoteFile
import com.infinstall.app.adb.model.SessionState
import com.infinstall.app.adb.transport.AdbSync
import com.infinstall.app.adb.transport.AdbTransport
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Sole owner of connection lifecycle + ADB operations.
 *
 * ## Session lifetime (sticky, aligned with host adb semantics)
 *
 * Leave [SessionState.Connected] **only** when:
 * 1. User calls [disconnect]
 * 2. [connect] fails
 * 3. Transport proves the **link is dead** (reset / broken pipe / manager not connected)
 *
 * Do **not** leave Connected because of:
 * - single op timeout, Stream closed, permission error, empty listing
 * - soft/garbled probe output
 * - a second TCP connect to host:port (forbidden: single-client adbd)
 *
 * ## Idle link observation
 *
 * While Connected, a background loop occasionally touches the **existing**
 * session (same serial bus as real ops). Its only job is to surface
 * link death when the user is idle (e.g. peer turned off wireless debugging).
 * [AdbTransport.LinkHealth.Transient] never changes session state.
 */
class AdbSession private constructor(context: Context) {
    private val app = context.applicationContext
    private val manager = InfinstallAdbManager.get(app)
    private val lifecycleLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<SessionState>(SessionState.Disconnected)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /**
     * Human-readable reason when session drops without user tapping disconnect.
     * Cleared on next successful connect / user disconnect.
     */
    @Volatile
    var lastDropReason: String? = null
        private set

    val isConnected: Boolean get() = _state.value.isConnected
    val host: String? get() = (_state.value as? SessionState.Connected)?.host
    val port: Int? get() = (_state.value as? SessionState.Connected)?.port

    private var linkWatchJob: Job? = null

    private val transport = AdbTransport(
        manager = manager,
        // I/O allowed while Connecting (handshake) or Connected — not while idle Disconnected
        isSessionLive = {
            when (_state.value) {
                is SessionState.Connected, is SessionState.Connecting -> true
                else -> false
            }
        },
        onTransportDead = { detail ->
            Log.w(TAG, "transport proved dead during op: $detail")
            dropBecauseLinkDead(fromTransportCallback = true)
        },
    )

    fun requestCancel() = transport.requestCancel()
    fun clearCancel() = transport.clearCancel()

    suspend fun pair(host: String, pairPort: Int, code: String) = withContext(Dispatchers.IO) {
        lifecycleLock.withLock {
            stopLinkWatch()
            val h = host.trim()
            val c = code.filter { it.isDigit() }
            if (c.length !in 5..8) throw AdbException("配对码应为 6 位数字")
            if (pairPort !in 1..65535) throw AdbException("配对端口无效")
            _state.value = SessionState.Pairing(h, pairPort)
            try {
                transport.managerPair(h, pairPort, c)
                Log.i(TAG, "pair ok $h:$pairPort")
                _state.value = SessionState.Disconnected
            } catch (t: Throwable) {
                _state.value = SessionState.Disconnected
                throw mapConnect(t, h, pairPort, pairing = true)
            }
        }
    }

    suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        lifecycleLock.withLock {
            stopLinkWatch()
            lastDropReason = null
            val h = host.trim()
            if (port !in 1..65535) throw AdbException("端口无效：$port")
            transport.managerDisconnect()
            _state.value = SessionState.Connecting(h, port)
            try {
                // 1) ADB CNXN/TLS handshake — this is the real "connect"
                transport.managerConnect(h, port)
                if (!transport.managerReportsConnected()) {
                    error("握手未完成")
                }
                // 2) Advertise Connected as soon as the link exists (sticky model)
                _state.value = SessionState.Connected(
                    host = h,
                    port = port,
                    sinceMs = SystemClock.elapsedRealtime(),
                )
                // 3) Soft shell check — failure here is op noise unless link is proven dead
                try {
                    val probe = transport.withSerial {
                        transport.shell("echo infinstall_ok", 12_000)
                    }
                    if (!probe.contains("infinstall_ok")) {
                        Log.w(TAG, "connect shell soft-miss: ${probe.take(80)}")
                    }
                } catch (t: Throwable) {
                    if (!transport.managerReportsConnected()) {
                        throw t
                    }
                    // Link still up — stay Connected (do not tear down the session)
                    Log.w(TAG, "connect shell soft-fail (stay up): ${t.message}")
                }
                Log.i(TAG, "connected $h:$port")
                startLinkWatch()
            } catch (t: Throwable) {
                stopLinkWatch()
                transport.managerDisconnect()
                _state.value = SessionState.Disconnected
                throw mapConnect(t, h, port, pairing = false)
            }
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        lifecycleLock.withLock {
            stopLinkWatch()
            lastDropReason = null
            transport.managerDisconnect()
            _state.value = SessionState.Disconnected
            Log.i(TAG, "disconnected by user")
        }
    }

    /**
     * Idle link watch: periodically observe the existing ADB link.
     *
     * Policy (source rule, not thresholds-as-patches):
     * - [LinkHealth.Dead] → leave Connected once
     * - Ok / Busy / Transient → stay Connected
     *
     * No fail counters, no “stale force drop”, no second TCP to host:port.
     */
    private fun startLinkWatch() {
        stopLinkWatch()
        linkWatchJob = scope.launch {
            Log.i(TAG, "link-watch start every ${LINK_WATCH_INTERVAL_MS}ms (drop only on proven death)")
            while (isActive) {
                delay(LINK_WATCH_INTERVAL_MS)
                if (_state.value !is SessionState.Connected) break

                // Cheap flag from libadb — no I/O
                if (!transport.managerReportsConnected()) {
                    Log.w(TAG, "link-watch: manager reports not connected")
                    dropBecauseLinkDead(fromTransportCallback = false)
                    break
                }

                when (val health = transport.observeLink()) {
                    AdbTransport.LinkHealth.Ok,
                    AdbTransport.LinkHealth.Busy,
                    -> {
                        Log.d(TAG, "link-watch $health")
                    }
                    AdbTransport.LinkHealth.Transient -> {
                        // Op glitch / timeout / soft-miss — stay Connected (sticky)
                        Log.w(TAG, "link-watch transient (session stays connected)")
                    }
                    AdbTransport.LinkHealth.Dead -> {
                        Log.w(TAG, "link-watch proven dead")
                        dropBecauseLinkDead(fromTransportCallback = false)
                        break
                    }
                }
            }
            Log.i(TAG, "link-watch end")
        }
    }

    private fun stopLinkWatch() {
        linkWatchJob?.cancel()
        linkWatchJob = null
    }

    private fun dropBecauseLinkDead(fromTransportCallback: Boolean) {
        stopLinkWatch()
        lastDropReason = DROP_REASON_LINK_DEAD
        try {
            manager.disconnect()
        } catch (_: Exception) {
        }
        val cur = _state.value
        if (cur is SessionState.Connected || cur is SessionState.Connecting) {
            _state.value = SessionState.Disconnected
        }
        Log.w(TAG, "session dropped (link dead) fromTransport=$fromTransportCallback")
    }

    suspend fun shell(command: String, timeoutMs: Long = 15_000): String =
        withContext(Dispatchers.IO) {
            requireConnected()
            try {
                transport.withSerial { transport.shell(command, timeoutMs) }
            } catch (t: Throwable) {
                noteOpError(t)
                throw t
            }
        }

    suspend fun syncList(path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        requireConnected()
        try {
            transport.withSerial { transport.syncList(path) }
        } catch (t: Throwable) {
            noteOpError(t)
            throw t
        }
    }

    suspend fun syncStat(path: String): AdbSync.Stat = withContext(Dispatchers.IO) {
        requireConnected()
        try {
            transport.withSerial { transport.syncStat(path) }
        } catch (t: Throwable) {
            noteOpError(t)
            throw t
        }
    }

    suspend fun syncPush(
        local: File,
        remotePath: String,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        requireConnected()
        try {
            transport.withSerial { transport.syncPush(local, remotePath, onProgress) }
        } catch (t: Throwable) {
            noteOpError(t)
            throw t
        }
    }

    suspend fun syncPull(
        remotePath: String,
        local: File,
        onProgress: (got: Long) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        requireConnected()
        try {
            transport.withSerial { transport.syncPull(remotePath, local, onProgress) }
        } catch (t: Throwable) {
            noteOpError(t)
            throw t
        }
    }

    suspend fun pmInstall(remoteApkPath: String): String = withContext(Dispatchers.IO) {
        requireConnected()
        try {
            transport.withSerial { transport.pmInstall(remoteApkPath) }
        } catch (t: Throwable) {
            noteOpError(t)
            throw t
        }
    }

    fun q(path: String): String = transport.q(path)

    private fun requireConnected(): SessionState.Connected {
        val s = _state.value
        if (s is SessionState.Connected) return s
        throw AdbException("未连接设备")
    }

    private fun noteOpError(t: Throwable) {
        val msg = t.message?.take(200) ?: return
        _state.update { cur ->
            if (cur is SessionState.Connected) cur.copy(lastError = msg) else cur
        }
    }

    private fun mapConnect(t: Throwable, host: String, port: Int, pairing: Boolean): Throwable {
        if (t is AdbPairingRequiredException) {
            return AdbException("需要先配对（展开下方「配对码」选项）", t)
        }
        if (t is AdbException) return t
        val head = if (pairing) "配对失败" else "连接失败"
        val m = t.message ?: t.javaClass.simpleName
        return AdbException("$head（$host:$port）：$m", t)
    }

    companion object {
        private const val TAG = "AdbSession"
        /** How often to observe the existing link while idle (I/O only if bus free). */
        private const val LINK_WATCH_INTERVAL_MS = 15_000L
        private const val DROP_REASON_LINK_DEAD =
            "设备端调试已关闭或网络中断，请重新连接"

        @Volatile
        private var instance: AdbSession? = null

        fun get(context: Context): AdbSession =
            instance ?: synchronized(this) {
                instance ?: AdbSession(context.applicationContext).also { instance = it }
            }
    }
}
