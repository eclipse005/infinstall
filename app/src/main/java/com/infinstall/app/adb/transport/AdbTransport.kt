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
            // Same path as real ops: interactive shell: + short script (not a second TCP)
            val out = shell("echo __PING_OK__", LINK_OBSERVE_TIMEOUT_MS)
            if (out.contains("__PING_OK__")) {
                LinkHealth.Ok
            } else {
                Log.w(TAG, "observeLink transient soft-miss out=${out.take(80)}")
                LinkHealth.Transient
            }
        } catch (t: Throwable) {
            Log.w(TAG, "observeLink: ${t.javaClass.simpleName} ${t.message}")
            // Dead only when manager is down or error is unmistakable link death
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
     * Result of the two-phase install recipe (push may be skipped when staged).
     */
    data class InstallRun(
        val pmOutput: String,
        /** True when remote APK was already on device and push was skipped */
        val reusedTransfer: Boolean,
        val remotePath: String,
        /** True when remote tmp was removed (install succeeded) */
        val cleanedRemote: Boolean,
    )

    /**
     * Official two-phase install (host `adb install`), under caller's serial hold:
     *
     * 1. **Transfer** — `sync SEND` unless [reuseRemotePath] already has the correct bytes
     * 2. **Install** — `shell:pm install -r -t -d -g …`
     * 3. **Cleanup** — `rm` **only on install Success** (keep staged file on failure for retry)
     */
    fun installPushPmRm(
        local: File,
        /** Path to use when a new push is required */
        newRemotePath: String,
        /** If set and STAT size matches local, skip push and install from this path */
        reuseRemotePath: String? = null,
        onProgress: (sent: Long, total: Long) -> Unit,
        pushTimeoutMs: Long = 300_000,
        pmTimeoutMs: Long = 180_000,
    ): InstallRun {
        require(SAFE_TMP_APK.matches(newRemotePath)) {
            "install path must be /data/local/tmp/ii<digits>.apk, got $newRemotePath"
        }
        if (reuseRemotePath != null) {
            require(SAFE_TMP_APK.matches(reuseRemotePath)) {
                "reuse path must be /data/local/tmp/ii<digits>.apk, got $reuseRemotePath"
            }
        }
        ensureLive()
        checkCancel()
        val expected = local.length()
        if (!local.isFile || expected <= 0L) throw AdbException("本地文件无效")

        var remote = newRemotePath
        var reused = false

        // ── Phase 1: transfer (or reuse staged) ──
        if (reuseRemotePath != null) {
            val st = runCatching { syncStat(reuseRemotePath, 15_000) }.getOrNull()
            if (st != null && st.size == expected && st.size > 0L) {
                remote = reuseRemotePath
                reused = true
                onProgress(expected, expected)
                Log.i(TAG, "install reuse staged $remote size=$expected")
            }
        }

        if (!reused) {
            // Different package than last stage — drop the old remote if any
            if (reuseRemotePath != null && reuseRemotePath != newRemotePath) {
                runCatching { shell("rm -f ${q(reuseRemotePath)}", 8_000) }
            }
            timed(pushTimeoutMs) { active ->
                withSync(active) { sync ->
                    sync.push(
                        local = local,
                        remotePath = newRemotePath,
                        onProgress = onProgress,
                        checkCancel = { checkCancel() },
                    )
                }
            }
            val st = syncStat(newRemotePath, 15_000)
            if (st.size > 0L && st.size != expected) {
                runCatching { shell("rm -f ${q(newRemotePath)}", 8_000) }
                throw AdbException("传输不完整：本地 ${expected}B，远端 ${st.size}B")
            }
            remote = newRemotePath
        }

        // Phase 1 done — remote bytes are good. Phase 2 may fail; keep remote for retry.
        // ── Phase 2: pm install ──
        val raw = try {
            pmInstall(remote, pmTimeoutMs)
        } catch (t: Throwable) {
            // Staged file left on device; caller records remotePath via [InstallStageException]
            throw InstallStageException(remotePath = remote, size = expected, cause = t)
        }
        val ok = "success" in raw.lowercase() && "failure" !in raw.lowercase()

        // ── Phase 3: rm only on success ──
        var cleaned = false
        if (ok) {
            runCatching { shell("rm -f ${q(remote)}", 8_000) }
            cleaned = true
        } else {
            Log.i(TAG, "install pm failed — keep staged $remote for retry")
        }
        return InstallRun(
            pmOutput = raw,
            reusedTransfer = reused,
            remotePath = remote,
            cleanedRemote = cleaned,
        )
    }

    /**
     * Push finished, pm (or post-push step) failed — remote APK is still on device for reuse.
     */
    class InstallStageException(
        val remotePath: String,
        val size: Long,
        cause: Throwable,
    ) : Exception(cause.message ?: "安装阶段失败", cause)

    /**
     * Official one-shot: `adb shell pm install -r -t -d -g <path>`.
     *
     * Path **must** match [SAFE_TMP_APK] (ASCII, short). Remote closes the stream when
     * pm finishes — that close is **success EOF**, not a session failure.
     */
    fun pmInstall(remoteApkPath: String, timeoutMs: Long = 180_000): String {
        require(SAFE_TMP_APK.matches(remoteApkPath)) {
            "pm install path must be /data/local/tmp/ii<digits>.apk, got $remoteApkPath"
        }
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
        try {
            while (bos.size() < maxBytes) {
                val n = try {
                    input.read(buf)
                } catch (t: Throwable) {
                    // Peer closed service after command — normal EOF for one-shot shell
                    if (isStreamClosedError(t)) break
                    throw t
                }
                if (n < 0) break
                if (n == 0) continue
                bos.write(buf, 0, n)
            }
        } catch (t: Throwable) {
            if (bos.size() > 0 && isStreamClosedError(t)) {
                // Got payload then stream closed — keep what we read (often "Success")
                Log.w(TAG, "readUntilEof stream closed after ${bos.size()}B (keep payload)")
            } else {
                throw t
            }
        }
        return bos.toString(StandardCharsets.UTF_8.name()).trim()
    }

    private fun isStreamClosedError(t: Throwable): Boolean {
        val msg = generateSequence(t) { it.cause }
            .joinToString(" ") { listOfNotNull(it.javaClass.simpleName, it.message).joinToString(" ") }
            .lowercase()
        return "stream closed" in msg ||
            (t is java.io.IOException && "closed" in msg && "connection reset" !in msg)
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
            // Official research: single stream failure may be transient — retry once
            // when the ADB link itself is still up (common after previous stream close).
            if (manager.isConnected && isStreamClosedError(t)) {
                Log.w(TAG, "openStream retry once after stream closed: ${dest.take(48)}")
                try {
                    return manager.openStream(dest)
                } catch (t2: Throwable) {
                    Log.e(TAG, "openStream retry failed: ${t2.javaClass.simpleName} ${t2.message}")
                    if (!manager.isConnected || isConnectionDead(t2)) {
                        onTransportDead(t2.message ?: t2.javaClass.simpleName)
                    }
                    throw mapIo(t2)
                }
            }
            Log.e(TAG, "openStream ${dest.take(60)}: ${t.javaClass.simpleName} ${t.message}")
            if (!manager.isConnected || isConnectionDead(t)) {
                onTransportDead(t.message ?: t.javaClass.simpleName)
            }
            throw mapIo(t)
        }
    }

    /**
     * Unmistakable **connection** death (whole ADB link to adbd).
     *
     * Strict by design: single-stream errors (Stream closed, EOF, generic SocketException,
     * most SSL read/write noise) must NOT kill the session — that caused connect→instant drop.
     */
    private fun isConnectionDead(t: Throwable): Boolean {
        if (t is java.nio.BufferOverflowException) return false
        val msg = generateSequence(t) { it.cause }
            .joinToString(" ") {
                listOfNotNull(it.javaClass.simpleName, it.message).joinToString(" ")
            }
            .lowercase()
        // Always treat as stream-local
        if ("stream closed" in msg) return false
        if ("bufferoverflow" in msg.replace(" ", "")) return false
        return "connection reset" in msg ||
            "broken pipe" in msg ||
            "not connected" in msg ||
            "failed to connect" in msg ||
            "software caused connection abort" in msg
    }

    private fun mapIo(t: Throwable): Throwable {
        if (t is TransferCancelledException || t is AdbException) return t
        if (t is InstallStageException) return t
        val m = generateSequence(t) { it.cause }
            .joinToString(" ") { listOfNotNull(it.javaClass.simpleName, it.message).joinToString(" ") }
            .lowercase()
        return when {
            t is java.nio.BufferOverflowException || "bufferoverflow" in m.replace(" ", "") ->
                AdbException("ADB OPEN 缓冲错误（库缺陷）。请断开后重连。", t)
            isStreamClosedError(t) || "stream closed" in m ->
                AdbException("通道瞬时异常，请再试一次（一般不用重连）", t)
            else ->
                AdbException("通信失败，请重试", t)
        }
    }

    private fun readUntilMarker(stream: AdbStream, marker: String, maxBytes: Int): String {
        val input = stream.openInputStream()
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        val text = StringBuilder()
        try {
            while (bos.size() < maxBytes) {
                val n = try {
                    input.read(buf)
                } catch (t: Throwable) {
                    if (isStreamClosedError(t)) break
                    throw t
                }
                if (n < 0) break
                if (n == 0) continue
                bos.write(buf, 0, n)
                text.append(String(buf, 0, n, StandardCharsets.UTF_8))
                if (text.contains(marker)) break
            }
        } catch (t: Throwable) {
            if (!(bos.size() > 0 && isStreamClosedError(t))) throw t
            Log.w(TAG, "readUntilMarker stream closed after ${bos.size()}B (keep payload)")
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
            // Future.get wraps worker failures in ExecutionException
            val c = if (e is java.util.concurrent.ExecutionException) (e.cause ?: e) else (e.cause ?: e)
            when (c) {
                is AdbException -> throw c
                is TransferCancelledException -> throw c
                is InstallStageException -> throw c
                else -> throw mapIo(c)
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
