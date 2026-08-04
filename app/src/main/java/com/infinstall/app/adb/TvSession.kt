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
)

/**
 * Remote session. Every network/ADB call is hard-timeout'd so UI never spins forever.
 */
class TvSession(private val appContext: Context) {
    private val mutex = Mutex()
    private val manager get() = InfinstallAdbManager.get(appContext)
    private val ioPool = Executors.newCachedThreadPool()

    @Volatile
    var host: String? = null
        private set

    @Volatile
    var port: Int? = null
        private set

    val isConnected: Boolean
        get() = manager.isConnected && host != null

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
     * Detect drop without using ADB shell (shell can hang).
     * If TCP to host:port fails, remote debugging is almost certainly off.
     */
    fun isTcpAlive(): Boolean {
        val h = host ?: return false
        val p = port ?: return false
        return try {
            Socket().use { s ->
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(h, p), 2_000)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Install APK: push to /data/local/tmp then pm install.
     * Never blocks forever — every step has a deadline.
     */
    suspend fun installApk(
        input: InputStream,
        displayName: String,
        cacheDir: File,
        onStatus: (String) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkLinked()
            onStatus("准备中…")
            val local = File(cacheDir, "apk_${System.currentTimeMillis()}.apk")
            try {
                FileOutputStream(local).use { out -> input.copyTo(out) }
                val size = local.length()
                if (size <= 0L) error("APK 文件为空")
                onStatus("传输中（${size / 1024} KB）…")
                val remote = "/data/local/tmp/infinstall_${System.currentTimeMillis()}.apk"
                pushFile(local, remote, onProgress = { sent ->
                    if (size > 0 && sent % (512 * 1024) < 64 * 1024) {
                        onStatus("传输中 ${sent * 100 / size}%")
                    }
                })
                onStatus("正在安装…")
                val result = shell("pm install -r -t \"$remote\"", 90_000)
                shell("rm -f \"$remote\"", 10_000)
                Log.i(TAG, "pm install result: ${result.take(200)}")
                when {
                    result.contains("Success", ignoreCase = true) -> onStatus("安装成功")
                    result.isBlank() -> {
                        // some devices print little; treat empty after no exception as uncertain
                        error("安装无响应，请到设备上确认是否已安装")
                    }
                    else -> error(result.ifBlank { "安装失败" })
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
        onStatus: (String) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkLinked()
            val cache = File(appContext.cacheDir, "push_${System.currentTimeMillis()}.bin")
            try {
                FileOutputStream(cache).use { out -> input.copyTo(out) }
                onStatus("传输 ${cache.length() / 1024} KB…")
                pushFile(cache, remotePath) { sent ->
                    val total = cache.length().coerceAtLeast(1)
                    if (sent % (256 * 1024) < 32 * 1024) {
                        onStatus("传输 ${sent * 100 / total}%")
                    }
                }
                onStatus("已保存到 $remotePath")
            } finally {
                cache.delete()
            }
        }
    }

    suspend fun listDir(path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkLinked()
            val p = path.trim().ifEmpty { "/sdcard" }
            // one line per entry: D|name or F|size|name
            val script =
                "ls -1A \"$p\" 2>/dev/null | while IFS= read -r n; do " +
                    "if [ -d \"$p/\$n\" ]; then echo \"D|\$n\"; " +
                    "elif [ -f \"$p/\$n\" ]; then sz=\$(wc -c < \"$p/\$n\" 2>/dev/null || echo 0); echo \"F|\$sz|\$n\"; " +
                    "fi; done"
            val out = shell(script, 20_000)
            out.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { line ->
                    when {
                        line.startsWith("D|") -> RemoteFile(line.removePrefix("D|"), isDir = true)
                        line.startsWith("F|") -> {
                            val rest = line.removePrefix("F|")
                            val bar = rest.indexOf('|')
                            if (bar <= 0) null
                            else {
                                val sz = rest.substring(0, bar).trim().toLongOrNull() ?: 0L
                                val name = rest.substring(bar + 1)
                                RemoteFile(name, isDir = false, size = sz)
                            }
                        }
                        else -> null
                    }
                }
                .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
                .toList()
        }
    }

    suspend fun deleteRemote(path: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkLinked()
            val out = shell("rm -rf \"$path\" 2>&1; echo EXIT:\$?", 15_000)
            if (out.contains("EXIT:0")) return@withLock
            if (out.contains("No such") || out.contains("Permission")) {
                error(out)
            }
        }
    }

    suspend fun mkdirRemote(path: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkLinked()
            shell("mkdir -p \"$path\"", 10_000)
        }
    }

    // ————— internals —————

    private fun checkLinked() {
        if (!manager.isConnected || host == null) error("未连接设备")
        if (!isTcpAlive()) {
            safeDisconnect()
            error("连接已断开（设备可能已关闭网络调试）")
        }
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
        // ensure parent dir
        val parent = remotePath.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) {
            shell("mkdir -p \"$parent\"", 10_000)
        }
        val size = local.length()
        val stream = callTimed(15_000) {
            // exec: avoids shell profile; sh -c for redirect
            manager.openStream("exec:sh -c 'cat > \"$remotePath\"'")
        }
        val closed = AtomicBoolean(false)
        try {
            callTimed(120_000) {
                val os = stream.openOutputStream()
                FileInputStream(local).use { fis ->
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = fis.read(buf)
                        if (n <= 0) break
                        os.write(buf, 0, n)
                        sent += n
                        onProgress(sent)
                    }
                    os.flush()
                }
                // AdbOutputStream.close only flushes — must close AdbStream for EOF to cat
            }
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
        // verify size if possible
        val remoteSize = shell("wc -c < \"$remotePath\" 2>/dev/null", 10_000)
            .trim()
            .lines()
            .lastOrNull()
            ?.trim()
            ?.toLongOrNull()
        if (remoteSize != null && remoteSize > 0 && size > 0 && remoteSize != size) {
            error("传输不完整：本地 ${size}B，远端 ${remoteSize}B")
        }
        if (remoteSize == null || remoteSize == 0L) {
            // still try install if file might exist without wc
            val exists = shell("ls -l \"$remotePath\" 2>&1", 8_000)
            if (exists.contains("No such") || exists.contains("cannot")) {
                error("文件未传到设备：$exists")
            }
        }
    }

    private fun shell(command: String, timeoutMs: Long): String {
        return callTimed(timeoutMs) {
            val stream = manager.openStream("shell:$command")
            try {
                readFully(stream, timeoutMs.coerceAtMost(timeoutMs))
            } finally {
                try {
                    stream.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun readFully(stream: AdbStream, timeoutMs: Long): String {
        // dedicated thread so we can hard-timeout blocking AdbStream.read
        return callTimed(timeoutMs) {
            val input = stream.openInputStream()
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                bos.write(buf, 0, n)
            }
            bos.toString(StandardCharsets.UTF_8.name())
        }
    }

    private fun <T> callTimed(timeoutMs: Long, block: () -> T): T {
        val future = ioPool.submit(Callable { block() })
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw IllegalStateException("操作超时（${timeoutMs / 1000} 秒）。设备可能卡住或已断开调试。")
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
