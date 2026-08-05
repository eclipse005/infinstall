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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sole owner of connection lifecycle + ADB operations.
 *
 * Leave Connected only via:
 * - user [disconnect]
 * - failed [connect]
 * - proven transport death
 * - light keepalive: consecutive probe failures while idle
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
     * Cleared on next successful connect.
     */
    @Volatile
    var lastDropReason: String? = null
        private set

    val isConnected: Boolean get() = _state.value.isConnected
    val host: String? get() = (_state.value as? SessionState.Connected)?.host
    val port: Int? get() = (_state.value as? SessionState.Connected)?.port

    private var keepAliveJob: Job? = null
    private val probeFailStreak = AtomicInteger(0)

    private val transport = AdbTransport(
        manager = manager,
        isSessionLive = { _state.value is SessionState.Connected },
        onTransportDead = { reason ->
            Log.w(TAG, "transport dead: $reason")
            dropToDisconnected(
                reason = "设备端调试已关闭或网络中断",
                fromTransport = true,
            )
        },
    )

    fun requestCancel() = transport.requestCancel()
    fun clearCancel() = transport.clearCancel()

    suspend fun pair(host: String, pairPort: Int, code: String) = withContext(Dispatchers.IO) {
        lifecycleLock.withLock {
            stopKeepAlive()
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
            stopKeepAlive()
            lastDropReason = null
            val h = host.trim()
            if (port !in 1..65535) throw AdbException("端口无效：$port")
            transport.managerDisconnect()
            _state.value = SessionState.Connecting(h, port)
            try {
                transport.managerConnect(h, port)
                _state.value = SessionState.Connected(
                    host = h,
                    port = port,
                    sinceMs = SystemClock.elapsedRealtime(),
                )
                val probe = transport.withSerial {
                    transport.shell("echo infinstall_ok", 12_000)
                }
                if (!probe.contains("infinstall_ok")) {
                    Log.w(TAG, "probe soft-miss: ${probe.take(80)}")
                }
                Log.i(TAG, "connected $h:$port")
                startKeepAlive()
            } catch (t: Throwable) {
                stopKeepAlive()
                transport.managerDisconnect()
                _state.value = SessionState.Disconnected
                throw mapConnect(t, h, port, pairing = false)
            }
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        lifecycleLock.withLock {
            stopKeepAlive()
            lastDropReason = null
            transport.managerDisconnect()
            _state.value = SessionState.Disconnected
            Log.i(TAG, "disconnected by user")
        }
    }

    /**
     * Light keepalive:
     * - Every [KEEPALIVE_INTERVAL_MS] while Connected
     * - Skip when transport is busy (real op holds mutex) → treat as alive
     * - Tiny shell probe; need [KEEPALIVE_FAIL_THRESHOLD] consecutive fails to drop
     */
    private fun startKeepAlive() {
        stopKeepAlive()
        probeFailStreak.set(0)
        keepAliveJob = scope.launch {
            Log.i(TAG, "keepalive start interval=${KEEPALIVE_INTERVAL_MS}ms failNeed=$KEEPALIVE_FAIL_THRESHOLD")
            while (isActive) {
                delay(KEEPALIVE_INTERVAL_MS)
                if (_state.value !is SessionState.Connected) break

                when (val r = transport.tryLightPing()) {
                    AdbTransport.LightPing.Busy -> {
                        // Real work in progress — connection is usable
                        probeFailStreak.set(0)
                    }
                    AdbTransport.LightPing.Alive -> {
                        probeFailStreak.set(0)
                    }
                    AdbTransport.LightPing.Fail -> {
                        val n = probeFailStreak.incrementAndGet()
                        Log.w(TAG, "keepalive fail streak=$n")
                        if (n >= KEEPALIVE_FAIL_THRESHOLD) {
                            dropToDisconnected(
                                reason = "设备端调试已关闭或网络中断，请重新连接",
                                fromTransport = false,
                            )
                            break
                        }
                    }
                    AdbTransport.LightPing.Dead -> {
                        dropToDisconnected(
                            reason = "设备端调试已关闭或网络中断，请重新连接",
                            fromTransport = true,
                        )
                        break
                    }
                }
            }
        }
    }

    private fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        probeFailStreak.set(0)
    }

    private fun dropToDisconnected(reason: String, fromTransport: Boolean) {
        stopKeepAlive()
        lastDropReason = reason
        if (!fromTransport) {
            try {
                manager.disconnect()
            } catch (_: Exception) {
            }
        } else {
            // transport callback already disconnects manager in some paths;
            // still ensure clean
            try {
                manager.disconnect()
            } catch (_: Exception) {
            }
        }
        if (_state.value is SessionState.Connected ||
            _state.value is SessionState.Connecting
        ) {
            _state.value = SessionState.Disconnected
        }
        Log.w(TAG, "session dropped: $reason")
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
        /** Idle probe interval */
        private const val KEEPALIVE_INTERVAL_MS = 12_000L
        /** Need this many consecutive probe failures while idle to drop session */
        private const val KEEPALIVE_FAIL_THRESHOLD = 2

        @Volatile
        private var instance: AdbSession? = null

        fun get(context: Context): AdbSession =
            instance ?: synchronized(this) {
                instance ?: AdbSession(context.applicationContext).also { instance = it }
            }
    }
}
