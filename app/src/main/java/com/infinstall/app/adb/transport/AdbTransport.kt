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
import java.util.concurrent.atomic.AtomicReference

/**
 * Single serial ADB channel.
 *
 * ## Design (source-level, not workarounds)
 *
 * 1. **OPEN destinations are only short ASCII service names**:
 *    - `shell:` — interactive shell; commands written to the stream
 *    - `sync:`  — file sync protocol
 *    Never put command text / paths into OPEN (libadb 3.1.1 still under-allocates
 *    OPEN buffers for long/UTF-8 destinations; also matches clean service model).
 *
 * 2. **One mutex** — all user-visible ops serialize.
 *
 * 3. **Timeout always closes the active [AdbStream]** then cancels the worker,
 *    so half-open streams do not poison the session.
 *
 * 4. **Session lifetime is not owned here** — only [onTransportDead] for proven
 *    connection-level death.
 */
class AdbTransport(
    private val manager: InfinstallAdbManager,
    private val isSessionLive: () -> Boolean,
    private val onTransportDead: (String) -> Unit,
) {
    private val mutex = Mutex()
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "adb-io").apply { isDaemon = true }
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
     * Run a remote shell command (design: OPEN `shell:` only, write script to stdin).
     *
     * Output is truncated at end marker. Returns text before marker.
     * Exit code is not trusted alone — callers should verify with sync when needed.
     */
    fun shell(command: String, timeoutMs: Long = 15_000): String {
        ensureLive()
        val cmd = command.replace("\r", "").replace('\n', ' ').trim()
        require(cmd.isNotEmpty()) { "empty shell command" }
        val marker = "__INF_END__"
        // Single script: run cmd, print marker, exit (clean one-shot over interactive shell)
        val script = "$cmd; echo $marker; exit\n"
        Log.i(TAG, "shell(${timeoutMs}ms) ${cmd.take(160)}")

        return timed(timeoutMs) { active ->
            val stream = openService(SERVICE_SHELL).also { active.set(it) }
            try {
                stream.openOutputStream().use { os ->
                    os.write(script.toByteArray(StandardCharsets.UTF_8))
                    os.flush()
                }
                readUntilMarker(stream, marker, MAX_SHELL_OUT)
            } finally {
                closeQuiet(stream)
                active.set(null)
            }
        }
    }

    /**
     * `pm install` via the same shell channel (not a second OPEN style).
     * Path must already be a short ASCII path under /data/local/tmp.
     */
    fun pmInstall(remoteApkPath: String, timeoutMs: Long = 120_000): String {
        require(remoteApkPath.startsWith("/data/local/tmp/")) {
            "pm install path must be under /data/local/tmp"
        }
        // -r replace, -t allow test, -d allow downgrade (debug-friendly), -g grant runtime perms
        val out = shell("pm install -r -t -d -g ${q(remoteApkPath)}", timeoutMs)
        Log.i(TAG, "pmInstall out=${out.take(240)}")
        return out
    }

    // ── sync ───────────────────────────────────────────────

    fun syncList(path: String, timeoutMs: Long = 30_000): List<RemoteFile> {
        ensureLive()
        return timed(timeoutMs) { active ->
            withSync(active) { it.list(path) }
        }
    }

    fun syncStat(path: String, timeoutMs: Long = 15_000): AdbSync.Stat {
        ensureLive()
        return timed(timeoutMs) { active ->
            withSync(active) { it.stat(path) }
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
        if (!local.isFile || local.length() < 0) throw AdbException("本地文件无效")
        val parent = remotePath.substringBeforeLast('/', "")
        if (parent.isNotEmpty() && parent != remotePath) {
            // Parent create via shell channel (sync SEND does not mkdir -p on all devices)
            shell("mkdir -p ${q(parent)}", 12_000)
        }
        timed(timeoutMs) { active ->
            withSync(active) { sync ->
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
        timed(timeoutMs) { active ->
            withSync(active) { sync ->
                sync.pull(
                    remotePath = remotePath,
                    local = local,
                    onProgress = onProgress,
                    checkCancel = { checkCancel() },
                )
            }
        }
    }

    private fun <T> withSync(active: AtomicReference<AdbStream?>, block: (AdbSync) -> T): T {
        val stream = openService(SERVICE_SYNC).also { active.set(it) }
        try {
            val sync = AdbSync(stream.openInputStream(), stream.openOutputStream())
            return try {
                block(sync)
            } finally {
                runCatching { sync.quit() }
            }
        } finally {
            closeQuiet(stream)
            active.set(null)
        }
    }

    // ── connection helpers ─────────────────────────────────

    fun managerConnect(host: String, port: Int) {
        timed(30_000) { _ ->
            val ok = manager.connect(host, port)
            if (!ok && !manager.isConnected) error("握手未完成")
        }
    }

    fun managerPair(host: String, pairPort: Int, code: String) {
        timed(60_000) { _ ->
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
     * Only short service names. Enforces design rule at the single open gate.
     */
    private fun openService(service: String): AdbStream {
        require(service == SERVICE_SHELL || service == SERVICE_SYNC) {
            "illegal OPEN destination: $service"
        }
        return try {
            manager.openStream(service)
        } catch (t: Throwable) {
            Log.e(TAG, "openStream $service: ${t.javaClass.simpleName} ${t.message}")
            if (isConnectionDead(t)) {
                onTransportDead(t.message ?: t.javaClass.simpleName)
            }
            throw mapIo(t)
        }
    }

    private fun isConnectionDead(t: Throwable): Boolean {
        val m = (t.message ?: "").lowercase()
        if (t is java.nio.BufferOverflowException) return false
        if ("stream closed" in m) return false
        if (t is java.io.EOFException) return false
        return "connection reset" in m ||
            "broken pipe" in m ||
            "not connected" in m ||
            "failed to connect" in m ||
            "socket closed" in m ||
            (t is java.net.SocketException && "reset" in m)
    }

    private fun mapIo(t: Throwable): Throwable {
        if (t is TransferCancelledException || t is AdbException) return t
        val detail = listOfNotNull(t.javaClass.simpleName, t.message?.take(160))
            .joinToString(": ")
        val m = (t.message ?: "").lowercase()
        return when {
            t is java.nio.BufferOverflowException || "bufferoverflow" in m.replace(" ", "") ->
                AdbException("ADB OPEN 缓冲错误（库缺陷）。请断开后重连。", t)
            "closed" in m ->
                AdbException("通道异常，请重试（无需立刻重连）\n$detail", t)
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

    /**
     * Run [block] with hard timeout. On timeout: close active stream first, then cancel worker.
     */
    private fun <T> timed(timeoutMs: Long, block: (AtomicReference<AdbStream?>) -> T): T {
        val active = AtomicReference<AdbStream?>(null)
        val f = pool.submit(Callable { block(active) })
        return try {
            f.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            closeQuiet(active.getAndSet(null))
            f.cancel(true)
            throw AdbException("操作超时（${timeoutMs / 1000}s），请重试")
        } catch (e: Exception) {
            closeQuiet(active.getAndSet(null))
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
        private const val SERVICE_SHELL = "shell:"
        private const val SERVICE_SYNC = "sync:"
        private const val MAX_SHELL_OUT = 4 * 1024 * 1024
    }
}
