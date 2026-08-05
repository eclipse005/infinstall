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
 * ## Design (source-level)
 *
 * 1. **OPEN destinations** — short ASCII only:
 *    - `shell:` / `sync:` (interactive shell body / sync protocol)
 *    - short pure-ASCII `shell:<cmd>` for one-shot (matches host `adb shell cmd`)
 *    Long/UTF-8 destinations stay out of OPEN (libadb buffer limit).
 *
 * 2. **One mutex** — all ops on this connection serialize (one adbd client).
 *
 * 3. **Timeout** closes the active [AdbStream] then cancels the worker
 *    (stream death ≠ session death).
 *
 * 4. **Session lifetime is not owned here.** This layer only reports
 *    [LinkHealth]: whether the **existing** ADB link is still a live transport.
 *    It never opens a second TCP connection to the device port.
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

    /**
     * Result of observing the **existing** ADB link (no second TCP connect).
     *
     * Maps cleanly to session policy:
     * - [Ok] / [Busy] / [Transient] → stay Connected
     * - [Dead] → leave Connected (only proven transport death)
     */
    enum class LinkHealth {
        /** Lightest ADB op succeeded on this session */
        Ok,
        /** Another op holds the bus — link is in active use */
        Busy,
        /** Op failed but does not prove the TCP/TLS session is gone */
        Transient,
        /** Connection-level death: reset / not connected / manager down */
        Dead,
    }

    /**
     * Observe link health on the existing session.
     * Must not be called while already holding [mutex].
     *
     * Never opens a new host:port TCP connection (single-client adbd safe).
     */
    fun observeLink(): LinkHealth {
        if (!manager.isConnected) {
            Log.w(TAG, "observeLink: manager not connected")
            return LinkHealth.Dead
        }
        if (!mutex.tryLock()) {
            return LinkHealth.Busy
        }
        return try {
            if (!isSessionLive()) return LinkHealth.Dead
            if (!manager.isConnected) return LinkHealth.Dead
            // Lightest protocol touch: same as host `adb shell echo …`
            val out = shellOneShotAscii("echo __PING_OK__", LINK_OBSERVE_TIMEOUT_MS)
            if (out.contains("__PING_OK__")) {
                LinkHealth.Ok
            } else {
                // Empty/garbled output: stream issue, not proof the link is dead
                Log.w(TAG, "observeLink transient soft-miss out=${out.take(80)}")
                LinkHealth.Transient
            }
        } catch (t: Throwable) {
            Log.w(TAG, "observeLink: ${t.javaClass.simpleName} ${t.message}")
            if (!manager.isConnected || isConnectionDead(t)) {
                LinkHealth.Dead
            } else {
                LinkHealth.Transient
            }
        } finally {
            mutex.unlock()
        }
    }

    /** Manager still believes the ADB connection is up (no I/O). */
    fun managerReportsConnected(): Boolean = manager.isConnected

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
     * Official one-shot: `adb shell pm install -r -t -d -g <path>`.
     *
     * Path **must** match [SAFE_TMP_APK] (ASCII, short) so OPEN destination
     * stays under libadb's buffer limit and matches host `adb shell` semantics
     * (process exits when pm finishes — no interactive stdin/marker).
     */
    fun pmInstall(remoteApkPath: String, timeoutMs: Long = 120_000): String {
        require(SAFE_TMP_APK.matches(remoteApkPath)) {
            "pm install path must be /data/local/tmp/ii<digits>.apk, got $remoteApkPath"
        }
        // Exactly like: adb shell pm install -r -t -d -g /data/local/tmp/ii….apk
        val cmd = "pm install -r -t -d -g $remoteApkPath"
        val out = shellOneShotAscii(cmd, timeoutMs)
        Log.i(TAG, "pmInstall out=${out.take(300)}")
        return out
    }

    /**
     * Official one-shot shell service: OPEN `shell:<ascii-command>`.
     * Only for short pure-ASCII commands (e.g. pm install on tmp path).
     */
    private fun shellOneShotAscii(command: String, timeoutMs: Long): String {
        require(command.isNotEmpty() && command.all { it.code in 32..126 }) {
            "one-shot shell must be printable ASCII"
        }
        val dest = "shell:$command"
        require(dest.length < 90) {
            "one-shot OPEN too long (${dest.length}): ${dest.take(60)}"
        }
        Log.i(TAG, "shellOneShot(${timeoutMs}ms) $command")
        return timed(timeoutMs) { active ->
            val stream = openStreamDest(dest).also { active.set(it) }
            try {
                // Command is in OPEN; remote runs it and closes when done — read to EOF
                readUntilEof(stream, MAX_SHELL_OUT)
            } finally {
                closeQuiet(stream)
                active.set(null)
            }
        }
    }

    private fun readUntilEof(stream: AdbStream, maxBytes: Int): String {
        val input = stream.openInputStream()
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (bos.size() < maxBytes) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            bos.write(buf, 0, n)
        }
        return bos.toString(StandardCharsets.UTF_8.name()).trim()
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

    /** Interactive shell / sync only. */
    private fun openService(service: String): AdbStream {
        require(service == SERVICE_SHELL || service == SERVICE_SYNC) {
            "illegal OPEN destination: $service"
        }
        return openStreamDest(service)
    }

    /**
     * Open stream with validated destination.
     * Allowed: `shell:`, `sync:`, or short pure-ASCII `shell:<command>` (one-shot).
     */
    private fun openStreamDest(dest: String): AdbStream {
        val ok = when {
            dest == SERVICE_SHELL || dest == SERVICE_SYNC -> true
            dest.startsWith("shell:") &&
                dest.length < 90 &&
                dest.all { it.code in 32..126 } -> true
            else -> false
        }
        require(ok) { "illegal OPEN destination: ${dest.take(48)}" }
        return try {
            manager.openStream(dest)
        } catch (t: Throwable) {
            Log.e(TAG, "openStream ${dest.take(60)}: ${t.javaClass.simpleName} ${t.message}")
            if (isConnectionDead(t)) {
                onTransportDead(t.message ?: t.javaClass.simpleName)
            }
            throw mapIo(t)
        }
    }

    /**
     * True only when the **ADB connection** (TCP/TLS to adbd) is gone —
     * not when a single stream glitches.
     *
     * This is the sole classifier for [LinkHealth.Dead] / [onTransportDead].
     */
    private fun isConnectionDead(t: Throwable): Boolean {
        if (t is java.nio.BufferOverflowException) return false
        // Walk cause chain (libadb often wraps the socket/SSL error)
        val chain = generateSequence(t) { it.cause }.toList()
        val msg = chain.joinToString(" ") {
            listOfNotNull(it.javaClass.simpleName, it.message).joinToString(" ")
        }.lowercase()

        // Single-stream / protocol noise — session stays up
        if ("stream closed" in msg &&
            "connection reset" !in msg &&
            "broken pipe" !in msg &&
            "not connected" !in msg
        ) {
            return false
        }
        if (chain.any { it is java.io.EOFException } &&
            "connection reset" !in msg &&
            "broken pipe" !in msg
        ) {
            return false
        }

        return "connection reset" in msg ||
            "broken pipe" in msg ||
            "not connected" in msg ||
            "failed to connect" in msg ||
            "socket closed" in msg ||
            "connection abort" in msg ||
            "software caused connection abort" in msg ||
            "connection refused" in msg ||
            chain.any { it is java.net.SocketException } ||
            chain.any {
                it is javax.net.ssl.SSLException && (
                    "reset" in (it.message ?: "").lowercase() ||
                        "abort" in (it.message ?: "").lowercase() ||
                        "closed" in (it.message ?: "").lowercase() ||
                        "connection" in (it.message ?: "").lowercase() ||
                        "read error" in (it.message ?: "").lowercase() ||
                        "write error" in (it.message ?: "").lowercase()
                    )
            }
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
        /** Timeout for idle link observation only (op timeout ≠ session death). */
        private const val LINK_OBSERVE_TIMEOUT_MS = 8_000L

        /** Only these temp names are used for pm install (ASCII, no spaces/quotes). */
        val SAFE_TMP_APK: Regex = Regex("""^/data/local/tmp/ii\d+\.apk$""")
    }
}
