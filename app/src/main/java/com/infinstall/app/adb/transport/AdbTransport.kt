package com.infinstall.app.adb.transport

import android.util.Log
import com.infinstall.app.adb.InfinstallAdbManager
import com.infinstall.app.adb.model.AdbException
import com.infinstall.app.adb.model.RemoteFile
import com.infinstall.app.adb.model.TransferCancelledException
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serial ADB I/O aligned with official practices:
 * - File ops → [AdbSync] (`sync:` service) — same as `adb push/pull/ls`
 * - Commands → `shell:` (rm / mv / mkdir / pm install / probe)
 *
 * Never tears down the session on timeout or a single stream glitch.
 */
class AdbTransport(
    private val manager: InfinstallAdbManager,
    private val isSessionLive: () -> Boolean,
    private val onTransportDead: (String) -> Unit,
) {
    private val mutex = Mutex()
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "adb-transport").apply { isDaemon = true }
    }
    private val cancelFlag = AtomicBoolean(false)

    fun requestCancel() = cancelFlag.set(true)
    fun clearCancel() = cancelFlag.set(false)

    private fun checkCancel() {
        if (cancelFlag.get()) throw TransferCancelledException()
    }

    /** Shell single-quote for paths used in shell: commands. */
    fun q(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    suspend fun <T> withSerial(block: () -> T): T = mutex.withLock { block() }

    // ═══════ shell (commands only) ═══════

    /**
     * Run a shell command on the device.
     *
     * **CRITICAL libadb 3.1.1 workaround** ([issue #25](https://github.com/MuntashirAkon/libadb-android/issues/25)):
     * `AdbProtocol.generateOpen` under-allocates and throws [java.nio.BufferOverflowException]
     * when the OPEN destination is longer than ~104 bytes. Putting the full command in
     * `shell:long-command…` (e.g. delete with Chinese paths) always hits this.
     *
     * Official-safe approach: open short destination `shell:` only, write the command
     * to the stream (same as an interactive adb shell), then read until end marker.
     */
    fun shell(command: String, timeoutMs: Long): String {
        ensureLive()
        val cmd = command.replace("\r", "").replace('\n', ' ').trim()
        val marker = "__INF_END__"
        // One line + marker + exit so the remote shell terminates cleanly
        val script = "$cmd; echo $marker; exit\n"
        Log.i(TAG, "shell(${timeoutMs}ms) ${cmd.take(160)}")

        var last: Throwable? = null
        repeat(2) { attempt ->
            try {
                return timed(timeoutMs) {
                    var stream: AdbStream? = null
                    try {
                        // Destination MUST stay short: "shell:" only (6 bytes)
                        stream = openStreamOnce("shell:")
                        val os = stream.openOutputStream()
                        os.write(script.toByteArray(StandardCharsets.UTF_8))
                        os.flush()
                        readUntilMarker(stream, marker, 4 * 1024 * 1024)
                    } finally {
                        closeQuiet(stream)
                        if (attempt == 0) Thread.sleep(30)
                    }
                }
            } catch (t: Throwable) {
                last = t
                if (t is TransferCancelledException) throw t
                // Surface libadb OPEN bug clearly if it still appears (e.g. other long dest)
                if (t is java.nio.BufferOverflowException ||
                    t.cause is java.nio.BufferOverflowException
                ) {
                    throw AdbException(
                        "ADB 通道缓冲错误（命令过长或库缺陷）。请重试；若持续失败请断开重连。",
                        t,
                    )
                }
                if (isTransientStreamError(t) && attempt == 0) {
                    Log.w(TAG, "shell retry after: ${t.message}")
                    Thread.sleep(100)
                    return@repeat
                }
                throw mapIo(t)
            }
        }
        throw mapIo(last ?: AdbException("通信失败"))
    }

    // ═══════ sync (official file ops) ═══════

    fun syncList(path: String, timeoutMs: Long = 30_000): List<RemoteFile> {
        ensureLive()
        return timed(timeoutMs) {
            withSync { it.list(path) }
        }
    }

    fun syncStat(path: String, timeoutMs: Long = 15_000): AdbSync.Stat {
        ensureLive()
        return timed(timeoutMs) {
            withSync { it.stat(path) }
        }
    }

    fun syncPush(
        local: File,
        remotePath: String,
        onProgress: (sent: Long, total: Long) -> Unit,
        timeoutMs: Long = 300_000,
    ) {
        ensureLive()
        checkCancel()
        if (!local.exists() || local.length() < 0) throw AdbException("本地文件无效")
        // Ensure parent dir via shell mkdir (sync SEND does not create parents on all builds)
        val parent = remotePath.substringBeforeLast('/', "")
        if (parent.isNotEmpty() && parent != remotePath) {
            shell("mkdir -p ${q(parent)}", 10_000)
        }
        timed(timeoutMs) {
            withSync { sync ->
                sync.push(
                    local = local,
                    remotePath = remotePath,
                    onProgress = onProgress,
                    checkCancel = { checkCancel() },
                )
            }
        }
    }

    fun syncPull(
        remotePath: String,
        local: File,
        onProgress: (got: Long) -> Unit,
        timeoutMs: Long = 300_000,
    ) {
        ensureLive()
        checkCancel()
        timed(timeoutMs) {
            withSync { sync ->
                sync.pull(
                    remotePath = remotePath,
                    local = local,
                    onProgress = onProgress,
                    checkCancel = { checkCancel() },
                )
            }
        }
    }

    /** Open one sync: session, run [block], QUIT + close. */
    private fun <T> withSync(block: (AdbSync) -> T): T {
        var stream: AdbStream? = null
        try {
            stream = openStreamOnce("sync:")
            val sync = AdbSync(stream.openInputStream(), stream.openOutputStream())
            return try {
                block(sync)
            } finally {
                runCatching { sync.quit() }
            }
        } catch (t: Throwable) {
            throw mapIo(t)
        } finally {
            closeQuiet(stream)
            Thread.sleep(30)
        }
    }

    // ═══════ manager connect ═══════

    fun managerConnect(host: String, port: Int) {
        timed(30_000) {
            val ok = manager.connect(host, port)
            if (!ok && !manager.isConnected) error("握手未完成")
        }
    }

    fun managerPair(host: String, pairPort: Int, code: String) {
        timed(60_000) {
            if (!manager.pair(host, pairPort, code)) error("配对失败")
        }
    }

    fun managerDisconnect() {
        try {
            manager.disconnect()
        } catch (_: Exception) {
        }
    }

    // ═══════ internals ═══════

    private fun ensureLive() {
        if (!isSessionLive()) throw AdbException("未连接设备")
    }

    private fun openStreamOnce(dest: String): AdbStream {
        return try {
            manager.openStream(dest)
        } catch (t: Throwable) {
            Log.e(TAG, "openStream ${dest.take(60)}: ${t.javaClass.simpleName} ${t.message}")
            if (isDefinitiveTransportDeath(t)) {
                onTransportDead(t.message ?: t.javaClass.simpleName)
            }
            throw t
        }
    }

    private fun isDefinitiveTransportDeath(t: Throwable): Boolean {
        val m = (t.message ?: "").lowercase()
        if ("stream closed" in m || "stream cos" in m) return false
        if (t is java.io.EOFException) return false
        return (
            "connection reset" in m ||
                "broken pipe" in m ||
                "not connected" in m ||
                "failed to connect" in m ||
                "socket closed" in m ||
                (t is java.net.SocketException && "reset" in m)
            )
    }

    private fun isTransientStreamError(t: Throwable): Boolean {
        val m = (t.message ?: "").lowercase()
        return "closed" in m || "stream" in m || "reset" in m ||
            "broken" in m || t is java.io.IOException
    }

    private fun mapIo(t: Throwable): Throwable {
        if (t is TransferCancelledException || t is AdbException) return t
        val detail = buildString {
            append(t.javaClass.simpleName)
            val m = t.message
            if (!m.isNullOrBlank()) append(": ").append(m.take(160))
        }
        val m = (t.message ?: "").lowercase()
        return when {
            "closed" in m ->
                AdbException("通道瞬时异常，请再试一次（不必重连）\n$detail", t)
            else ->
                AdbException("通信失败\n$detail", t)
        }
    }

    private fun readUntilMarker(stream: AdbStream, marker: String, maxBytes: Int): String {
        val input = stream.openInputStream()
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        val text = StringBuilder()
        while (bos.size() < maxBytes) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            bos.write(buf, 0, n)
            text.append(String(buf, 0, n, StandardCharsets.UTF_8))
            if (text.contains(marker)) break
        }
        val full = bos.toString(StandardCharsets.UTF_8.name())
        val idx = full.indexOf(marker)
        return if (idx >= 0) full.substring(0, idx).trimEnd('\r', '\n', ' ')
        else full.trimEnd('\r', '\n', ' ')
    }

    private fun closeQuiet(stream: AdbStream?) {
        try {
            stream?.close()
        } catch (_: Exception) {
        }
    }

    private fun <T> timed(timeoutMs: Long, block: () -> T): T {
        val f = pool.submit(Callable { block() })
        return try {
            f.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            f.cancel(true)
            throw AdbException("操作超时（${timeoutMs / 1000}s），请重试")
        } catch (e: Exception) {
            val c = e.cause ?: e
            when (c) {
                is AdbException -> throw c
                is TransferCancelledException -> throw c
                is RuntimeException -> throw c
                is Exception -> throw AdbException(c.message ?: "操作失败", c)
                else -> throw AdbException(c.message ?: "操作失败", c)
            }
        }
    }

    companion object {
        private const val TAG = "AdbTransport"
    }
}
