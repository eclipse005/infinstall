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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Sole owner of connection lifecycle + ADB operations.
 *
 * State machine: Disconnected ↔ Connecting/Pairing → Connected.
 * Leave Connected only via [disconnect], failed [connect], or proven transport death.
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
            Log.w(TAG, "transport dead: $reason")
            // Use manager directly — do not touch `transport` during its own init/callback
            try {
                manager.disconnect()
            } catch (_: Exception) {
            }
            _state.value = SessionState.Disconnected
        },
    )

    fun requestCancel() = transport.requestCancel()
    fun clearCancel() = transport.clearCancel()

    suspend fun pair(host: String, pairPort: Int, code: String) = withContext(Dispatchers.IO) {
        lifecycleLock.withLock {
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
            val h = host.trim()
            if (port !in 1..65535) throw AdbException("端口无效：$port")
            transport.managerDisconnect()
            _state.value = SessionState.Connecting(h, port)
            try {
                // Direct libadb connect — no extra TCP probe (avoids second client to adbd)
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
            } catch (t: Throwable) {
                transport.managerDisconnect()
                _state.value = SessionState.Disconnected
                throw mapConnect(t, h, port, pairing = false)
            }
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        lifecycleLock.withLock {
            transport.managerDisconnect()
            _state.value = SessionState.Disconnected
            Log.i(TAG, "disconnected by user")
        }
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

        @Volatile
        private var instance: AdbSession? = null

        fun get(context: Context): AdbSession =
            instance ?: synchronized(this) {
                instance ?: AdbSession(context.applicationContext).also { instance = it }
            }
    }
}
