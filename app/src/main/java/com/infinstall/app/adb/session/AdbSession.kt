package com.infinstall.app.adb.session

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.infinstall.app.adb.InfinstallAdbManager
import com.infinstall.app.adb.model.AdbException
import com.infinstall.app.adb.model.SessionState
import com.infinstall.app.adb.transport.AdbTransport
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Sole owner of connection lifecycle.
 *
 * ## Contract (best practice)
 * 1. **Connect once → stay [SessionState.Connected]** while remote adbd keeps our session.
 * 2. Only three ways to leave Connected:
 *    - User calls [disconnect]
 *    - [connect] fails before reaching Connected
 *    - Transport proves the socket is dead ([onTransportDead]) — rare, definitive
 * 3. Operation timeout / permission / parse errors update [SessionState.Connected.lastError]
 *    but **never** disconnect.
 * 4. No heartbeat that kills the session. Remote adbd idle is fine.
 * 5. All I/O goes through [transport] under serial mutex.
 */
class AdbSession private constructor(context: Context) {
    private val app = context.applicationContext
    private val manager = InfinstallAdbManager.get(app)
    private val lifecycleLock = Mutex()

    private val _state = MutableStateFlow<SessionState>(SessionState.Disconnected)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    val isConnected: Boolean get() = _state.value.isConnected
    val host: String? get() = (_state.value as? SessionState.Connected)?.host
    val port: Int? get() = (_state.value as? SessionState.Connected)?.port

    private val transport = AdbTransport(
        manager = manager,
        isSessionLive = { _state.value is SessionState.Connected },
        onTransportDead = { reason ->
            // Only path that auto-drops Connected (definitive I/O death).
            Log.w(TAG, "transport dead → Disconnected: $reason")
            forceDisconnectLocked()
            _state.value = SessionState.Disconnected
        },
    )

    fun requestCancel() = transport.requestCancel()
    fun clearCancel() = transport.clearCancel()

    // ═══════ lifecycle ═══════

    suspend fun pair(host: String, pairPort: Int, code: String) = withContext(Dispatchers.IO) {
        lifecycleLock.withLock {
            val h = host.trim()
            val c = code.filter { it.isDigit() }
            if (c.length !in 5..8) throw AdbException("配对码应为 6 位数字")
            if (pairPort !in 1..65535) throw AdbException("配对端口无效")
            _state.value = SessionState.Pairing(h, pairPort)
            try {
                tcpReachable(h, pairPort)
                transport.managerPair(h, pairPort, c)
                Log.i(TAG, "pair ok $h:$pairPort")
                // Pair does not open a data session — back to disconnected until connect().
                _state.value = SessionState.Disconnected
            } catch (t: Throwable) {
                _state.value = SessionState.Disconnected
                throw mapConnect(t, h, pairPort, pairing = true)
            }
        }
    }

    suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        lifecycleLock.withLock {
            val h = host.trim()
            if (port !in 1..65535) throw AdbException("端口无效：$port")
            // Tear previous session cleanly before new connect.
            forceDisconnectLocked()
            _state.value = SessionState.Connecting(h, port)
            try {
                tcpReachable(h, port)
                transport.managerConnect(h, port)
                // Enter Connected BEFORE probe so shell is allowed.
                _state.value = SessionState.Connected(
                    host = h,
                    port = port,
                    sinceMs = SystemClock.elapsedRealtime(),
                )
                val probe = transport.withSerial {
                    transport.shell("echo infinstall_ok", 12_000)
                }
                if (!probe.contains("infinstall_ok")) {
                    Log.w(TAG, "probe soft-miss: ${probe.take(60)}")
                }
                Log.i(TAG, "session connected $h:$port")
            } catch (t: Throwable) {
                forceDisconnectLocked()
                _state.value = SessionState.Disconnected
                throw mapConnect(t, h, port, pairing = false)
            }
        }
    }

    /**
     * Explicit user disconnect. The only intentional leave of Connected
     * (besides proven transport death).
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        lifecycleLock.withLock {
            forceDisconnectLocked()
            _state.value = SessionState.Disconnected
            Log.i(TAG, "session disconnected by user")
        }
    }

    private fun forceDisconnectLocked() {
        transport.managerDisconnect()
    }

    // ═══════ operations (stay Connected on failure) ═══════

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

    suspend fun push(
        local: File,
        remotePath: String,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        requireConnected()
        try {
            transport.withSerial { transport.push(local, remotePath, onProgress) }
        } catch (t: Throwable) {
            noteOpError(t)
            throw t
        }
    }

    suspend fun pull(
        remotePath: String,
        local: File,
        onProgress: (got: Long) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        requireConnected()
        try {
            transport.withSerial { transport.pull(remotePath, local, onProgress) }
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

    /** Record last op error without leaving Connected. */
    private fun noteOpError(t: Throwable) {
        val msg = t.message?.take(200) ?: return
        _state.update { cur ->
            if (cur is SessionState.Connected) cur.copy(lastError = msg) else cur
        }
    }

    private fun tcpReachable(host: String, port: Int) {
        try {
            Socket().use { s ->
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), 4_000)
            }
        } catch (t: Throwable) {
            throw AdbException("无法访问 $host:$port（同一 Wi‑Fi、关 VPN、开调试）", t)
        }
    }

    private fun mapConnect(t: Throwable, host: String, port: Int, pairing: Boolean): Throwable {
        if (t is AdbPairingRequiredException) {
            return AdbException("需要先配对（展开下方「配对码」选项）", t)
        }
        if (t is AdbException) return t
        val head = if (pairing) "配对失败" else "连接失败"
        return AdbException(
            "$head（$host:$port）：${t.message ?: t.javaClass.simpleName}",
            t,
        )
    }

    companion object {
        private const val TAG = "AdbSession"

        @Volatile
        private var instance: AdbSession? = null

        fun get(context: Context): AdbSession =
            instance ?: synchronized(this) {
                instance ?: AdbSession(context.applicationContext).also { instance = it }
            }
    }
}
