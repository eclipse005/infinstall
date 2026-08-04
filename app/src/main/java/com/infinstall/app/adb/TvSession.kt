package com.infinstall.app.adb

import android.content.Context
import android.util.Log
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
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

data class RemoteFile(
    val name: String,
    val isDir: Boolean,
    val size: Long = 0L,
    /** Unix epoch seconds, 0 if unknown */
    val mtimeSec: Long = 0L,
    /** e.g. -rw-rw---- or ? */
    val permissions: String = "?",
    val isLink: Boolean = false,
) {
    fun fullPath(parent: String): String {
        val base = parent.trimEnd('/')
        return if (base.isEmpty() || base == "/") "/$name" else "$base/$name"
    }
}

data class RemoteFileProps(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val isLink: Boolean,
    val size: Long,
    val mtimeSec: Long,
    val permissions: String,
    val owner: String,
    val typeLabel: String,
    val readable: Boolean,
    val writable: Boolean,
    val linkTarget: String? = null,
)

/**
 * Remote session. Every network/ADB call is hard-timeout'd so UI never spins forever.
 */
class TransferCancelledException : Exception("已取消")

data class TransferProgress(
    /** 0f..1f, or -1 if indeterminate */
    val fraction: Float,
    val label: String,
)

class TvSession(private val appContext: Context) {
    private val mutex = Mutex()
    private val manager get() = InfinstallAdbManager.get(appContext)
    private val ioPool = Executors.newCachedThreadPool()
    private val cancelFlag = AtomicBoolean(false)

    @Volatile
    var host: String? = null
        private set

    @Volatile
    var port: Int? = null
        private set

    val isConnected: Boolean
        get() = manager.isConnected && host != null

    fun requestCancel() {
        cancelFlag.set(true)
    }

    fun clearCancel() {
        cancelFlag.set(false)
    }

    private fun checkCancel() {
        if (cancelFlag.get()) throw TransferCancelledException()
    }

    suspend fun pair(host: String, pairingPort: Int, pairingCode: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val h = host.trim()
                val code = pairingCode.filter { it.isDigit() }
                require(code.length in 5..8) { "配对码应为 6 位数字" }
                ensureTcpReachable(h, pairingPort)
                try {
                    callTimed(60_000) {
                        if (!manager.pair(h, pairingPort, code)) error("配对失败")
                    }
                } catch (t: Throwable) {
                    throw wrap(t, h, pairingPort, pairing = true)
                }
            }
        }

    suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            safeDisconnect()
            val h = host.trim()
            ensureTcpReachable(h, port)
            try {
                callTimed(30_000) {
                    val ok = manager.connect(h, port)
                    if (!ok && !manager.isConnected) {
                        error("连接失败")
                    }
                }
                // quick shell probe with hard timeout
                val probe = shell("echo infinstall_ok", 8_000)
                Log.i(TAG, "probe=${probe.take(40)}")
                this@TvSession.host = h
                this@TvSession.port = port
            } catch (t: Throwable) {
                safeDisconnect()
                this@TvSession.host = null
                this@TvSession.port = null
                throw wrap(t, h, port, pairing = false)
            }
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        mutex.withLock {
            safeDisconnect()
            host = null
            port = null
        }
    }

    /**
     * Whether we still believe the ADB session is up.
     *
     * IMPORTANT: Do NOT open a second TCP connection to the debug port while already
     * connected — many adbd instances only allow one client; a probe connect is refused
     * and was falsely reported as "设备已断开".
     */
    fun isSessionUp(): Boolean {
        return host != null && port != null && manager.isConnected
    }

    /**
     * In-band liveness: run a tiny shell on the existing connection.
     * If the session is busy (install/list holds the mutex), treat as alive.
     */
    suspend fun pingInBand(): Boolean = withContext(Dispatchers.IO) {
        if (!isSessionUp()) return@withContext false
        if (!mutex.tryLock()) return@withContext true
        try {
            val out = shell("echo ping_ok", 5_000)
            out.contains("ping_ok")
        } catch (t: Throwable) {
            Log.w(TAG, "pingInBand failed: ${t.message}")
            // Only declare dead if manager also dropped
            manager.isConnected && false
            false
        } finally {
            mutex.unlock()
        }
    }

    /** @deprecated use [isSessionUp] / [pingInBand] — name kept for call sites */
    fun isTcpAlive(): Boolean = isSessionUp()

    /**
     * Install APK: push to /data/local/tmp then pm install, then verify.
     * Supports cancel via [requestCancel]. Reports [TransferProgress].
     */
    suspend fun installApk(
        input: InputStream,
        displayName: String,
        cacheDir: File,
        onProgress: (TransferProgress) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        clearCancel()
        mutex.withLock {
            checkLinked()
            checkCancel()
            onProgress(TransferProgress(0f, "准备 $displayName"))
            val local = File(cacheDir, "apk_${System.currentTimeMillis()}.apk")
            try {
                FileOutputStream(local).use { out -> input.copyTo(out) }
                val size = local.length()
                if (size <= 0L) error("APK 文件为空")
                checkCancel()
                val remote = "/data/local/tmp/infinstall_${System.currentTimeMillis()}.apk"
                onProgress(TransferProgress(0.02f, "开始传输（${size / 1024} KB）"))
                pushFile(local, remote, onProgress = { sent ->
                    checkCancel()
                    val frac = (sent.toFloat() / size.coerceAtLeast(1)).coerceIn(0f, 1f)
                    // push is ~0..85% of overall install
                    onProgress(TransferProgress(0.05f + frac * 0.80f, "传输 ${ (frac * 100).toInt() }%"))
                })
                checkCancel()
                onProgress(TransferProgress(0.88f, "校验远端文件…"))
                val remoteSize = remoteFileSize(remote)
                if (remoteSize != null && remoteSize != size) {
                    error("传输不完整：本地 ${size}B，远端 ${remoteSize}B")
                }
                checkCancel()
                onProgress(TransferProgress(0.90f, "正在安装…"))
                val result = shell("pm install -r -t -g \"$remote\" 2>&1; echo __EC:\$?", 90_000)
                Log.i(TAG, "pm install: ${result.take(300)}")
                // cleanup best-effort
                runCatching { shell("rm -f \"$remote\"", 8_000) }
                val ok = result.contains("Success", ignoreCase = true) ||
                    result.contains("__EC:0")
                val fail = result.contains("Failure", ignoreCase = true) ||
                    result.contains("Error", ignoreCase = true)
                when {
                    ok && !fail -> {
                        onProgress(TransferProgress(1f, "安装成功"))
                    }
                    result.isBlank() -> error("安装无输出，可能失败。请到设备上确认。")
                    else -> {
                        val detail = result
                            .lineSequence()
                            .filter { !it.contains("__EC:") }
                            .joinToString("\n")
                            .trim()
                            .ifBlank { result.trim() }
                        error("安装失败\n$detail")
                    }
                }
            } finally {
                local.delete()
            }
        }
    }

    suspend fun pushToRemote(
        input: InputStream,
        remotePath: String,
        sizeHint: Long = -1,
        onProgress: (TransferProgress) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        clearCancel()
        mutex.withLock {
            checkLinked()
            checkCancel()
            val cache = File(appContext.cacheDir, "push_${System.currentTimeMillis()}.bin")
            try {
                FileOutputStream(cache).use { out -> input.copyTo(out) }
                val size = cache.length().coerceAtLeast(1)
                onProgress(TransferProgress(0f, "开始传输 ${size / 1024} KB"))
                pushFile(cache, remotePath) { sent ->
                    checkCancel()
                    val frac = (sent.toFloat() / size).coerceIn(0f, 1f)
                    onProgress(TransferProgress(frac, "传输 ${(frac * 100).toInt()}%"))
                }
                val remoteSize = remoteFileSize(remotePath)
                if (remoteSize != null && remoteSize > 0 && remoteSize != cache.length()) {
                    error("传输不完整：本地 ${cache.length()}B，远端 ${remoteSize}B")
                }
                onProgress(TransferProgress(1f, "已保存 $remotePath"))
            } finally {
                cache.delete()
            }
        }
    }

    private fun remoteFileSize(path: String): Long? {
        val out = shell("wc -c < \"$path\" 2>/dev/null || stat -c %s \"$path\" 2>/dev/null", 10_000)
        return out.lineSequence()
            .map { it.trim().replace(Regex("\\s+"), "") }
            .mapNotNull { it.toLongOrNull() }
            .firstOrNull()
    }

    /**
     * List via `ls -lA` so we get size/permissions in one reliable command.
     * (ls -1Ap had no sizes — all showed 0.)
     */
    suspend fun listDir(path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkLinked()
            val p = normalizePath(path)
            val out = shell("ls -lA ${shellQuote(p)}", 20_000)
            val lower = out.lowercase()
            if (lower.contains("no such file") || lower.contains("not a directory") ||
                lower.contains("permission denied")
            ) {
                error(out.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "无法打开目录")
            }
            out.lineSequence()
                .map { it.trimEnd('\r') }
                .mapNotNull { parseLsLongLine(it) }
                .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
                .toList()
        }
    }

    /**
     * Props via single `ls -ld` — works on toybox without GNU stat -c.
     */
    suspend fun statRemote(path: String): RemoteFileProps = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkLinked()
            val p = path.trim()
            val out = shell("ls -ld ${shellQuote(p)}", 12_000)
            val line = out.lineSequence()
                .map { it.trimEnd('\r').trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("total") }
                ?: error("无法读取属性")
            if (line.lowercase().contains("no such file")) {
                error("文件不存在：$p")
            }
            val parsed = parseLsLongLine(line)
                ?: error("无法解析属性：$line")
            // name from ls -ld may be full path or basename
            val displayName = p.trimEnd('/').substringAfterLast('/').ifBlank { parsed.name }
            RemoteFileProps(
                path = p,
                name = displayName,
                isDir = parsed.isDir,
                isLink = parsed.isLink,
                size = parsed.size,
                mtimeSec = parsed.mtimeSec,
                permissions = parsed.permissions,
                owner = "?",
                typeLabel = when {
                    parsed.isDir -> "文件夹"
                    parsed.isLink -> "链接"
                    else -> "文件"
                },
                readable = true,
                writable = true,
                linkTarget = null,
            )
        }
    }

    suspend fun deleteRemote(path: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkLinked()
            val p = path.trim()
            // Use exec+sh for reliability; semicolon so OK still prints if rm warns
            val out = shell("rm -rf ${shellQuote(p)}; echo __DEL_DONE__", 30_000)
            Log.i(TAG, "delete $p => ${out.take(200)}")
            if (!out.contains("__DEL_DONE__")) {
                error(humanShellError("删除失败", out.ifBlank { "无返回（路径 $p）" }))
            }
            // If still exists, report
            val check = shell("ls -ld ${shellQuote(p)} 2>&1; echo __CHK__", 10_000)
            if (!check.contains("No such") && !check.contains("No such file") &&
                check.lines().any { it.isNotBlank() && !it.contains("__CHK__") && it[0].let { c -> c == '-' || c == 'd' || c == 'l' } }
            ) {
                // still listed → permission or busy
                error("删除失败：文件仍存在（可能无权限）\n$p")
            }
        }
    }

    suspend fun mkdirRemote(path: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkLinked()
            val out = shell("mkdir -p ${shellQuote(path.trim())}; echo __OK__", 12_000)
            if (!out.contains("__OK__")) error(humanShellError("创建失败", out))
        }
    }

    suspend fun renameRemote(fromPath: String, toPath: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkLinked()
            val out = shell(
                "mv ${shellQuote(fromPath)} ${shellQuote(toPath)}; echo __OK__",
                20_000,
            )
            if (!out.contains("__OK__")) error(humanShellError("重命名失败", out))
        }
    }

    suspend fun copyRemote(fromPath: String, toPath: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkLinked()
            val out = shell(
                "cp -a ${shellQuote(fromPath)} ${shellQuote(toPath)}; echo __OK__",
                120_000,
            )
            if (!out.contains("__OK__")) error(humanShellError("复制失败", out))
        }
    }

    suspend fun moveRemote(fromPath: String, toPath: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkLinked()
            val out = shell(
                "mv ${shellQuote(fromPath)} ${shellQuote(toPath)}; echo __OK__",
                60_000,
            )
            if (!out.contains("__OK__")) error(humanShellError("移动失败", out))
        }
    }

    /**
     * Parse a single `ls -l` / `ls -ld` line from Android toybox/toolbox.
     *
     * Examples:
     *  -rw-rw---- 1 u0_a123 media_rw 29136 2024-08-04 03:14 file.apk
     *  drwxrwx--x 2 u0_a123 media_rw  3452 2024-08-01 12:00 Download
     *  -rw-r--r-- 1 root root 123 Jan  1  2020 old.txt
     */
    private fun parseLsLongLine(line: String): RemoteFile? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("total", ignoreCase = true)) return null
        if (trimmed.length < 10) return null
        val mode = trimmed.substringBefore(' ')
        if (mode.isEmpty()) return null
        val typeChar = mode[0]
        if (typeChar != '-' && typeChar != 'd' && typeChar != 'l' && typeChar != 'c' &&
            typeChar != 'b' && typeChar != 'p' && typeChar != 's'
        ) {
            // not a long listing line
            return null
        }
        val isDir = typeChar == 'd'
        val isLink = typeChar == 'l'
        val rest = trimmed.substring(mode.length).trim()
        val tokens = rest.split(Regex("\\s+"))
        if (tokens.size < 5) return null

        // tokens: nlink owner group size date... name
        // size is usually index 3
        var sizeIdx = 3
        var size = tokens.getOrNull(sizeIdx)?.toLongOrNull()
        if (size == null) {
            // find first pure number as size
            sizeIdx = tokens.indexOfFirst { it.toLongOrNull() != null && it.length < 15 }
            size = tokens.getOrNull(sizeIdx)?.toLongOrNull() ?: 0L
        }
        if (sizeIdx < 0) sizeIdx = 3

        // date/time consume 2 or 3 tokens after size
        var nameStart = sizeIdx + 1
        if (nameStart < tokens.size) {
            val t = tokens[nameStart]
            when {
                // 2024-08-04 03:14 name
                t.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) -> nameStart += 2
                // Jan 1 2020 or Jan 1 03:14
                t.matches(Regex("[A-Za-z]{3}")) -> nameStart += 3
                else -> nameStart += 2
            }
        }
        if (nameStart >= tokens.size) return null
        var name = tokens.subList(nameStart, tokens.size).joinToString(" ")
        // symlink: "name -> target"
        if (isLink && " -> " in name) {
            name = name.substringBefore(" -> ")
        }
        name = name.trim().trimEnd('/')
        // ls -ld may show full path as name
        if (name.contains('/')) {
            name = name.substringAfterLast('/')
        }
        if (name.isEmpty() || name == "." || name == "..") return null

        return RemoteFile(
            name = name,
            isDir = isDir,
            size = if (isDir) 0L else size,
            mtimeSec = 0L,
            permissions = mode,
            isLink = isLink,
        )
    }

    private fun normalizePath(path: String): String {
        val p = path.trim().ifEmpty { "/sdcard" }
        return if (p != "/" && p.endsWith('/')) p.trimEnd('/') else p
    }

    /** Safe single-quoted string for Android toybox/sh. */
    private fun shellQuote(s: String): String {
        // 'foo'\''bar' for embedded quotes
        return "'" + s.replace("'", "'\\''") + "'"
    }

    private fun humanShellError(prefix: String, out: String): String {
        val msg = out.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "__OK__" }
            .joinToString(" ")
            .ifBlank { "未知错误" }
        // expand common truncated errors
        val full = when {
            msg.contains("Stream closed", ignoreCase = true) ||
                msg.contains("stream cos", ignoreCase = true) ->
                "连接通道已关闭，请断开后重新连接再试"
            msg.contains("closed", ignoreCase = true) ->
                "连接已中断，请重新连接设备"
            else -> msg
        }
        return "$prefix：$full"
    }

    suspend fun pullToLocal(
        remotePath: String,
        localFile: File,
        onProgress: (Long) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkLinked()
            localFile.parentFile?.mkdirs()
            if (localFile.exists()) localFile.delete()
            val stream = callTimed(15_000) {
                manager.openStream("exec:cat \"$remotePath\"")
            }
            try {
                callTimed(180_000) {
                    FileOutputStream(localFile).use { fos ->
                        val input = stream.openInputStream()
                        val buf = ByteArray(64 * 1024)
                        var got = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n == 0) continue
                            fos.write(buf, 0, n)
                            got += n
                            if (got % (256 * 1024) < 64 * 1024) onProgress(got)
                        }
                        fos.flush()
                    }
                }
            } finally {
                try {
                    callTimed(10_000) { stream.close() }
                } catch (_: Exception) {
                }
            }
            if (!localFile.exists() || localFile.length() <= 0L) {
                // empty file might be valid; only error if missing
                if (!localFile.exists()) error("下载失败：未写入本地文件")
            }
        }
    }

    suspend fun installRemoteApk(remotePath: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkLinked()
            val result = shell("pm install -r -t \"$remotePath\"", 90_000)
            if (!result.contains("Success", ignoreCase = true)) {
                error(result.ifBlank { "安装失败" })
            }
        }
    }

    // ————— internals —————

    private fun checkLinked() {
        if (host == null || !manager.isConnected) {
            error("未连接设备")
        }
        // Do not open a second TCP socket here — that breaks single-client adbd.
    }

    private fun safeDisconnect() {
        try {
            manager.disconnect()
        } catch (_: Exception) {
        }
    }

    /**
     * Push local file by streaming into `cat > remote` then closing AdbStream (EOF).
     */
    private fun pushFile(
        local: File,
        remotePath: String,
        onProgress: (Long) -> Unit = {},
    ) {
        checkCancel()
        val parent = remotePath.substringBeforeLast('/', "")
        if (parent.isNotEmpty() && parent != remotePath) {
            shell("mkdir -p \"$parent\"", 10_000)
        }
        val size = local.length()
        // Prefer dd with known count when size is reasonable; cat works broadly
        val stream = callTimed(15_000) {
            manager.openStream("exec:sh -c 'cat > \"$remotePath\"'")
        }
        val closed = AtomicBoolean(false)
        try {
            callTimed(180_000) {
                val os = stream.openOutputStream()
                FileInputStream(local).use { fis ->
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        checkCancel()
                        val n = fis.read(buf)
                        if (n <= 0) break
                        os.write(buf, 0, n)
                        sent += n
                        onProgress(sent)
                    }
                    os.flush()
                }
            }
        } catch (t: Throwable) {
            if (t is TransferCancelledException) {
                runCatching { shell("rm -f \"$remotePath\"", 5_000) }
            }
            throw t
        } finally {
            try {
                callTimed(10_000) {
                    if (closed.compareAndSet(false, true)) {
                        stream.close()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "close push stream: ${t.message}")
            }
        }
        checkCancel()
        val remoteSize = remoteFileSize(remotePath)
        if (remoteSize != null && remoteSize > 0 && size > 0 && remoteSize != size) {
            error("传输不完整：本地 ${size}B，远端 ${remoteSize}B")
        }
        if (remoteSize == null || remoteSize == 0L) {
            val exists = shell("ls -l \"$remotePath\" 2>&1", 8_000)
            if (exists.contains("No such") || exists.contains("cannot access")) {
                error("文件未传到设备。$exists")
            }
        }
    }

    /**
     * Run a **single-line** shell command with one outer timeout.
     * Nested timeouts previously left streams half-open → "Stream closed".
     */
    private fun shell(command: String, timeoutMs: Long): String {
        // Only flatten newlines; do NOT collapse spaces (breaks quoted paths)
        val oneLine = command.replace("\r", "").replace('\n', ' ').trim()
        Log.i(TAG, "shell: ${oneLine.take(200)}")
        return callTimed(timeoutMs) {
            var stream: AdbStream? = null
            try {
                stream = manager.openStream("shell:$oneLine")
                val input = stream.openInputStream()
                val bos = ByteArrayOutputStream()
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    bos.write(buf, 0, n)
                    // safety cap 8MB shell output
                    if (bos.size() > 8 * 1024 * 1024) break
                }
                bos.toString(StandardCharsets.UTF_8.name())
            } catch (t: Throwable) {
                val m = t.message.orEmpty()
                if (m.contains("closed", ignoreCase = true)) {
                    throw IllegalStateException("连接通道已关闭，请断开后重新连接", t)
                }
                throw t
            } finally {
                try {
                    stream?.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun <T> callTimed(timeoutMs: Long, block: () -> T): T {
        val future = ioPool.submit(Callable { block() })
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw IllegalStateException("操作超时（${timeoutMs / 1000} 秒），请重试")
        } catch (e: Exception) {
            val c = e.cause ?: e
            when (c) {
                is RuntimeException -> throw c
                is Exception -> throw c
                else -> throw IllegalStateException(c.message ?: "操作失败", c)
            }
        }
    }

    private fun ensureTcpReachable(host: String, port: Int) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 3_000)
            }
        } catch (t: Throwable) {
            throw IllegalStateException(
                "无法访问 $host:$port。请确认同一 Wi‑Fi、关闭 VPN、网络调试已开。",
                t,
            )
        }
    }

    private fun wrap(t: Throwable, host: String, port: Int, pairing: Boolean): Throwable {
        if (t is AdbPairingRequiredException) {
            return IllegalStateException("需要先配对（展开配对码选项）。", t)
        }
        if (t is IllegalStateException && t.message?.contains("超时") == true) return t
        val msg = t.message ?: t.javaClass.simpleName
        val head = if (pairing) "配对失败" else "连接失败"
        return IllegalStateException("$head（$host:$port）：$msg", t)
    }

    companion object {
        private const val TAG = "InfinstallSession"
    }
}
