package com.infinstall.app.adb

import android.content.Context
import android.util.Log
import com.infinstall.app.adb.model.AdbException
import com.infinstall.app.adb.model.TransferCancelledException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unified ADB transport.
 *
 * Stability rules:
 * - One operation at a time ([mutex])
 * - Our own [linked] flag is the source of truth for UI (manager.isConnected can flap)
 * - Timeouts throw errors but do **not** tear down the session
 * - Session is only dropped on: user disconnect, connect/pair failure, or openStream
 *   proving the transport is dead
 * - Shell commands append an end marker so we don't hang waiting for stream EOF
 */
class AdbClient private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val manager get() = InfinstallAdbManager.get(appContext)
    private val mutex = Mutex()
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "adb-io").apply { isDaemon = true }
    }
    private val cancelFlag = AtomicBoolean(false)

    /** App-level session flag — NOT manager.isConnected (which is flaky after stream errors). */
    private val linked = AtomicBoolean(false)

    @Volatile
    var host: String? = null
        private set

    @Volatile
    var port: Int? = null
        private set

    val isConnected: Boolean
        get() = linked.get() && host != null

    fun requestCancel() = cancelFlag.set(true)
    fun clearCancel() = cancelFlag.set(false)

    private fun checkCancel() {
        if (cancelFlag.get()) throw TransferCancelledException()
    }

    // ═══════ connection ═══════

    suspend fun pair(host: String, pairPort: Int, code: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val h = host.trim()
            val c = code.filter { it.isDigit() }
            if (c.length !in 5..8) throw AdbException("配对码应为 6 位数字")
            tcpCheck(h, pairPort)
            try {
                timed(60_000) {
                    if (!manager.pair(h, pairPort, c)) error("配对失败")
                }
                Log.i(TAG, "pair ok $h:$pairPort")
            } catch (t: Throwable) {
                throw mapConnect(t, h, pairPort, pairing = true)
            }
        }
    }

    suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            disconnectLocked()
            val h = host.trim()
            if (port !in 1..65535) throw AdbException("端口无效：$port")
            tcpCheck(h, port)
            try {
                timed(30_000) {
                    val ok = manager.connect(h, port)
                    if (!ok && !manager.isConnected) {
                        error("连接失败（握手未完成）")
                    }
                }
                // Bookkeeping BEFORE any shell — order matters.
                this@AdbClient.host = h
                this@AdbClient.port = port
                linked.set(true)

                val probe = shellLocked("echo infinstall_ok", 12_000)
                if (!probe.contains("infinstall_ok")) {
                    Log.w(TAG, "probe unexpected: ${probe.take(80)}")
                    // Still accept: some shells wrap output; stream worked.
                }
                Log.i(TAG, "connected $h:$port")
            } catch (t: Throwable) {
                disconnectLocked()
                throw mapConnect(t, h, port, pairing = false)
            }
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        mutex.withLock { disconnectLocked() }
    }

    private fun disconnectLocked() {
        linked.set(false)
        host = null
        port = null
        try {
            manager.disconnect()
        } catch (_: Exception) {
        }
    }

    // ═══════ shell ═══════

    suspend fun shell(command: String, timeoutMs: Long = 15_000): String =
        withContext(Dispatchers.IO) {
            mutex.withLock { shellLocked(command, timeoutMs) }
        }

    /**
     * Must hold [mutex]. Single-line command. Appends end marker for reliable completion.
     */
    fun shellLocked(command: String, timeoutMs: Long): String {
        ensureLinked()
        val cmd = command.replace("\r", "").replace('\n', ' ').trim()
        // Marker lets us finish without relying on stream EOF (hangs on some adbd).
        val marker = "__INF_END__"
        val full = "$cmd; echo $marker"
        Log.i(TAG, "shell(${timeoutMs}ms): ${cmd.take(180)}")
        return timed(timeoutMs) {
            var stream: AdbStream? = null
            try {
                stream = openStreamSafe("shell:$full")
                val raw = readUntilMarker(stream, marker, maxBytes = 4 * 1024 * 1024)
                raw
            } catch (t: Throwable) {
                throw mapStream(t)
            } finally {
                closeQuiet(stream)
            }
        }
    }

    // ═══════ push / pull ═══════

    suspend fun push(
        local: File,
        remotePath: String,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLinked()
            checkCancel()
            val total = local.length()
            if (total <= 0L) throw AdbException("本地文件为空")

            val parent = remotePath.substringBeforeLast('/', "")
            if (parent.isNotEmpty() && parent != remotePath) {
                shellLocked("mkdir -p ${q(parent)}", 10_000)
            }

            val shCmd = "cat > ${q(remotePath)}"
            var stream: AdbStream? = null
            try {
                stream = timed(15_000) {
                    openStreamSafe("exec:sh -c ${q(shCmd)}")
                }
                timed(300_000) {
                    val os = stream!!.openOutputStream()
                    FileInputStream(local).use { fis ->
                        val buf = ByteArray(64 * 1024)
                        var sent = 0L
                        while (true) {
                            checkCancel()
                            val n = fis.read(buf)
                            if (n <= 0) break
                            os.write(buf, 0, n)
                            sent += n
                            onProgress(sent, total)
                        }
                        os.flush()
                    }
                }
            } catch (t: Throwable) {
                if (t is TransferCancelledException) {
                    runCatching { shellLocked("rm -f ${q(remotePath)}", 5_000) }
                }
                throw mapStream(t)
            } finally {
                closeQuiet(stream)
            }

            checkCancel()
            val remoteSize = sizeFromLs(shellLocked("ls -l ${q(remotePath)} 2>&1", 8_000))
            if (remoteSize != null && remoteSize != total) {
                throw AdbException("传输不完整：本地 ${total}B，远端 ${remoteSize}B")
            }
            if (remoteSize == null) {
                val ls = shellLocked("ls -l ${q(remotePath)} 2>&1", 8_000)
                if (ls.contains("No such", ignoreCase = true)) {
                    throw AdbException("文件未传到设备")
                }
            }
        }
    }

    suspend fun pull(
        remotePath: String,
        local: File,
        onProgress: (got: Long) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLinked()
            checkCancel()
            local.parentFile?.mkdirs()
            if (local.exists()) local.delete()

            var stream: AdbStream? = null
            try {
                stream = timed(15_000) {
                    openStreamSafe("shell:cat ${q(remotePath)}")
                }
                timed(300_000) {
                    FileOutputStream(local).use { fos ->
                        val input = stream!!.openInputStream()
                        val buf = ByteArray(64 * 1024)
                        var got = 0L
                        while (true) {
                            checkCancel()
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n == 0) continue
                            fos.write(buf, 0, n)
                            got += n
                            onProgress(got)
                        }
                    }
                }
            } catch (t: Throwable) {
                throw mapStream(t)
            } finally {
                closeQuiet(stream)
            }
            if (!local.exists()) throw AdbException("下载失败")
        }
    }

    // ═══════ helpers ═══════

    fun q(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    private fun ensureLinked() {
        if (!linked.get()) throw AdbException("未连接设备")
    }

    private fun openStreamSafe(dest: String): AdbStream {
        return try {
            manager.openStream(dest)
        } catch (t: Throwable) {
            if (isTransportDead(t)) {
                Log.e(TAG, "transport dead on openStream", t)
                linked.set(false)
            }
            throw t
        }
    }

    private fun isTransportDead(t: Throwable): Boolean {
        val m = (t.message ?: "").lowercase()
        val name = t.javaClass.simpleName.lowercase()
        return "closed" in m ||
            "connection reset" in m ||
            "broken pipe" in m ||
            "not connected" in m ||
            "socket" in m && ("closed" in m || "reset" in m) ||
            name.contains("eof")
    }

    private fun sizeFromLs(lsLine: String): Long? {
        val line = lsLine.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("total") }
            ?: return null
        if (line.contains("No such", ignoreCase = true)) return null
        val tokens = line.split(Regex("\\s+"))
        if (tokens.size >= 5) return tokens[4].toLongOrNull()
        return tokens.firstNotNullOfOrNull { it.toLongOrNull() }
    }

    /**
     * Read shell output until [marker] line appears (or stream ends / max size).
     */
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
        return if (idx >= 0) {
            full.substring(0, idx).trimEnd('\r', '\n', ' ')
        } else {
            full.trimEnd('\r', '\n', ' ')
        }
    }

    private fun closeQuiet(stream: AdbStream?) {
        try {
            stream?.close()
        } catch (_: Exception) {
        }
    }

    /**
     * Hard timeout. On timeout: cancel worker, throw — do **NOT** disconnect the session.
     * Callers close streams in finally; a later op can still use the same connection.
     */
    private fun <T> timed(timeoutMs: Long, block: () -> T): T {
        val f = pool.submit(Callable { block() })
        return try {
            f.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            f.cancel(true)
            throw AdbException("操作超时（${timeoutMs / 1000}s），请重试（连接未断开）")
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

    private fun tcpCheck(host: String, port: Int) {
        try {
            Socket().use { s ->
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), 4_000)
            }
        } catch (t: Throwable) {
            throw AdbException("无法访问 $host:$port（同一 Wi‑Fi、关 VPN、开调试）", t)
        }
    }

    private fun mapStream(t: Throwable): Throwable {
        if (t is TransferCancelledException || t is AdbException) return t
        val m = (t.message ?: "").lowercase()
        if ("closed" in m) {
            // Do not auto-unlink: one bad stream ≠ dead session on all devices.
            return AdbException("通道异常，请重试；若连续失败再点断开重连", t)
        }
        return AdbException(t.message ?: "通信失败", t)
    }

    private fun mapConnect(t: Throwable, host: String, port: Int, pairing: Boolean): Throwable {
        if (t is AdbPairingRequiredException) {
            return AdbException("需要先配对（展开下方「配对码」选项）", t)
        }
        if (t is AdbException) return t
        val m = (t.message ?: t.javaClass.simpleName)
        val head = if (pairing) "配对失败" else "连接失败"
        val hint = if (!pairing) {
            "。无线调试请用主页顶部连接端口（不是配对端口）；电视网络调试多为 5555"
        } else {
            ""
        }
        return AdbException("$head（$host:$port）：$m$hint", t)
    }

    companion object {
        private const val TAG = "AdbClient"

        @Volatile
        private var instance: AdbClient? = null

        fun get(context: Context): AdbClient =
            instance ?: synchronized(this) {
                instance ?: AdbClient(context.applicationContext).also { instance = it }
            }
    }
}
