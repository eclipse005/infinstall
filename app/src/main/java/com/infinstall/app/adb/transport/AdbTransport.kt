package com.infinstall.app.adb.transport

import android.util.Log
import com.infinstall.app.adb.InfinstallAdbManager
import com.infinstall.app.adb.model.AdbException
import com.infinstall.app.adb.model.TransferCancelledException
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Byte-level ADB I/O. Serializes all streams with one mutex.
 *
 * Does **not** own connection lifecycle. On failure it throws [AdbException];
 * only when [openStream] proves the socket is gone does it call [onTransportDead].
 *
 * Never disconnects on timeout — timeout is an operation failure only.
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

    fun q(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    suspend fun <T> withSerial(block: () -> T): T = mutex.withLock { block() }

    // ── shell ──────────────────────────────────────────────

    /**
     * Run a single-line shell command. Appends end marker so we do not hang on EOF.
     * Caller must already hold session live.
     */
    fun shell(command: String, timeoutMs: Long): String {
        ensureLive()
        val cmd = command.replace("\r", "").replace('\n', ' ').trim()
        val marker = "__INF_END__"
        val full = "$cmd; echo $marker"
        Log.i(TAG, "shell(${timeoutMs}ms) ${cmd.take(160)}")
        return timed(timeoutMs) {
            var stream: AdbStream? = null
            try {
                stream = openStream("shell:$full")
                readUntilMarker(stream, marker, 4 * 1024 * 1024)
            } catch (t: Throwable) {
                throw mapIo(t)
            } finally {
                closeQuiet(stream)
            }
        }
    }

    // ── push / pull ────────────────────────────────────────

    fun push(
        local: File,
        remotePath: String,
        onProgress: (sent: Long, total: Long) -> Unit,
    ) {
        ensureLive()
        checkCancel()
        val total = local.length()
        if (total <= 0L) throw AdbException("本地文件为空")

        val parent = remotePath.substringBeforeLast('/', "")
        if (parent.isNotEmpty() && parent != remotePath) {
            shell("mkdir -p ${q(parent)}", 10_000)
        }

        val shCmd = "cat > ${q(remotePath)}"
        var stream: AdbStream? = null
        try {
            stream = timed(15_000) { openStream("exec:sh -c ${q(shCmd)}") }
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
                runCatching { shell("rm -f ${q(remotePath)}", 5_000) }
            }
            throw mapIo(t)
        } finally {
            closeQuiet(stream)
        }

        checkCancel()
        val remoteSize = sizeFromLs(shell("ls -l ${q(remotePath)} 2>&1", 8_000))
        if (remoteSize != null && remoteSize != total) {
            throw AdbException("传输不完整：本地 ${total}B，远端 ${remoteSize}B")
        }
        if (remoteSize == null) {
            val ls = shell("ls -l ${q(remotePath)} 2>&1", 8_000)
            if (ls.contains("No such", ignoreCase = true)) {
                throw AdbException("文件未传到设备")
            }
        }
    }

    fun pull(
        remotePath: String,
        local: File,
        onProgress: (got: Long) -> Unit,
    ) {
        ensureLive()
        checkCancel()
        local.parentFile?.mkdirs()
        if (local.exists()) local.delete()

        var stream: AdbStream? = null
        try {
            stream = timed(15_000) { openStream("shell:cat ${q(remotePath)}") }
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
            throw mapIo(t)
        } finally {
            closeQuiet(stream)
        }
        if (!local.exists()) throw AdbException("下载失败")
    }

    // ── manager connect helpers (called under session lock) ─

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

    // ── internals ──────────────────────────────────────────

    private fun ensureLive() {
        if (!isSessionLive()) throw AdbException("未连接设备")
    }

    private fun openStream(dest: String): AdbStream {
        return try {
            manager.openStream(dest)
        } catch (t: Throwable) {
            if (isDefinitiveTransportDeath(t)) {
                Log.e(TAG, "transport dead: ${t.message}")
                onTransportDead(t.message ?: t.javaClass.simpleName)
            }
            throw t
        }
    }

    /**
     * Only hard network/session death — not "permission denied", not "no such file".
     */
    private fun isDefinitiveTransportDeath(t: Throwable): Boolean {
        val m = (t.message ?: "").lowercase()
        return (
            "connection reset" in m ||
                "broken pipe" in m ||
                "not connected" in m ||
                "socket closed" in m ||
                "failed to connect" in m ||
                ("closed" in m && "stream" in m) ||
                t is java.net.SocketException ||
                t is java.io.EOFException
            )
    }

    private fun mapIo(t: Throwable): Throwable {
        if (t is TransferCancelledException || t is AdbException) return t
        val m = (t.message ?: "").lowercase()
        return when {
            "closed" in m ->
                AdbException("通道忙或异常，请重试（会话保持连接）", t)
            else ->
                AdbException(t.message ?: "通信失败", t)
        }
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

    /** Timeout → operation error only. Never tears down the session. */
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
