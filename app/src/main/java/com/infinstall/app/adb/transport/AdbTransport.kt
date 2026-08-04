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
 * Serial ADB I/O. Never tears down the session on timeout or a single stream error.
 *
 * Shell strategy:
 * - Prefer `exec:sh -c '…'` (cleaner lifecycle than `shell:` on many TV adbd)
 * - End marker so we do not hang waiting for EOF
 * - One automatic retry on transient stream failures
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

    /** Single-quote for Android toybox/sh. */
    fun q(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    suspend fun <T> withSerial(block: () -> T): T = mutex.withLock { block() }

    // ── shell ──────────────────────────────────────────────

    /**
     * Run a single-line shell command. Must be called under [withSerial] (or hold the
     * same logical lock via session).
     */
    fun shell(command: String, timeoutMs: Long): String {
        ensureLive()
        val cmd = command.replace("\r", "").replace('\n', ' ').trim()
        val marker = "__INF_END__"
        // Always end with marker; use ; so marker prints even if cmd fails.
        val script = "$cmd; echo $marker"
        Log.i(TAG, "shell(${timeoutMs}ms) ${cmd.take(180)}")

        var last: Throwable? = null
        // Retry once: first stream after a prior op often fails with "Stream closed" on TV adbd.
        repeat(2) { attempt ->
            try {
                return timed(timeoutMs) {
                    var stream: AdbStream? = null
                    try {
                        stream = openStreamPreferExec(script)
                        readUntilMarker(stream, marker, 4 * 1024 * 1024)
                    } finally {
                        closeQuiet(stream)
                        // Brief settle — some adbd dislike back-to-back openStream.
                        if (attempt == 0) Thread.sleep(40)
                    }
                }
            } catch (t: Throwable) {
                last = t
                if (t is TransferCancelledException || t is AdbException && t.message?.contains("未连接") == true) {
                    throw mapIo(t)
                }
                if (isTransientStreamError(t) && attempt == 0) {
                    Log.w(TAG, "shell attempt0 transient: ${t.message}; retry")
                    Thread.sleep(120)
                    return@repeat
                }
                throw mapIo(t)
            }
        }
        throw mapIo(last ?: AdbException("通信失败"))
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
            stream = timed(15_000) { openStreamPreferExec(shCmd) }
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
            Thread.sleep(40)
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
            // cat binary via exec for better EOF
            stream = timed(15_000) { openStreamPreferExec("cat ${q(remotePath)}") }
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
            Thread.sleep(40)
        }
        if (!local.exists()) throw AdbException("下载失败")
    }

    // ── manager connect helpers ────────────────────────────

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

    /**
     * Prefer exec:sh -c 'script' — avoids interactive shell quirks.
     * Fall back to shell: if exec open fails once.
     */
    private fun openStreamPreferExec(script: String): AdbStream {
        val execDest = "exec:sh -c ${q(script)}"
        return try {
            openStreamOnce(execDest)
        } catch (t: Throwable) {
            if (isDefinitiveTransportDeath(t)) throw t
            Log.w(TAG, "exec open failed (${t.message}), fallback shell:")
            openStreamOnce("shell:$script")
        }
    }

    private fun openStreamOnce(dest: String): AdbStream {
        return try {
            manager.openStream(dest)
        } catch (t: Throwable) {
            Log.e(TAG, "openStream failed dest=${dest.take(80)}: ${t.javaClass.simpleName} ${t.message}")
            // Only kill session on *connection-level* death — NOT "Stream closed"
            // (that is usually a per-stream glitch after a prior op).
            if (isDefinitiveTransportDeath(t)) {
                onTransportDead(t.message ?: t.javaClass.simpleName)
            }
            throw t
        }
    }

    /**
     * Connection is gone for good. Do NOT include "stream closed" — that is transient.
     */
    private fun isDefinitiveTransportDeath(t: Throwable): Boolean {
        val m = (t.message ?: "").lowercase()
        // Explicitly exclude per-stream noise
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
        val name = t.javaClass.simpleName.lowercase()
        return "closed" in m ||
            "stream" in m ||
            "reset" in m ||
            "broken" in m ||
            "timeout" in m ||
            "eof" in name ||
            t is java.io.IOException
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

    /** Timeout → operation error only. Never disconnects. */
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
