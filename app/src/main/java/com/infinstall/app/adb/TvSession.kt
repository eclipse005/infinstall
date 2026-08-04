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
            val p = path.trim().ifEmpty { "/sdcard" }.trimEnd('/').ifEmpty { "/" }
            // TYPE \t SIZE \t MTIME \t PERM \t NAME
            val script = """
                p="$p"
                ls -1A "${'$'}p" 2>/dev/null | while IFS= read -r n; do
                  [ -z "${'$'}n" ] && continue
                  f="${'$'}p/${'$'}n"
                  if [ -d "${'$'}f" ]; then t=D
                  elif [ -L "${'$'}f" ]; then t=L
                  elif [ -f "${'$'}f" ]; then t=F
                  else t=O
                  fi
                  sz=${'$'}(stat -c %s "${'$'}f" 2>/dev/null || wc -c < "${'$'}f" 2>/dev/null || echo 0)
                  mt=${'$'}(stat -c %Y "${'$'}f" 2>/dev/null || echo 0)
                  pm=${'$'}(stat -c %A "${'$'}f" 2>/dev/null || echo '?')
                  sz=${'$'}(echo "${'$'}sz" | tr -d '[:space:]')
                  mt=${'$'}(echo "${'$'}mt" | tr -d '[:space:]')
                  printf '%s\t%s\t%s\t%s\t%s\n' "${'$'}t" "${'$'}sz" "${'$'}mt" "${'$'}pm" "${'$'}n"
                done
            """.trimIndent()
            val out = shell(script, 25_000)
            out.lineSequence()
                .map { it.trimEnd('\r') }
                .filter { it.isNotEmpty() && '\t' in it }
                .mapNotNull { line ->
                    val parts = line.split('\t', limit = 5)
                    if (parts.size < 5) return@mapNotNull null
                    val type = parts[0]
                    val size = parts[1].toLongOrNull() ?: 0L
                    val mtime = parts[2].toLongOrNull() ?: 0L
                    val perm = parts[3].ifBlank { "?" }
                    val name = parts[4]
                    if (name.isBlank() || name == "." || name == "..") return@mapNotNull null
                    RemoteFile(
                        name = name,
                        isDir = type == "D" || (type == "L" && false), // links: treat file unless -d below
                        size = size,
                        mtimeSec = mtime,
                        permissions = perm,
                        isLink = type == "L",
                    ).let { f ->
                        // directory symlink: type L but we want open as dir if target is dir — re-check via type D first
                        if (type == "D") f.copy(isDir = true)
                        else if (type == "L") f.copy(isDir = false) // open as file/link; user can still navigate if needed
                        else f.copy(isDir = false)
                    }
                }
                .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
                .toList()
        }
    }

    suspend fun statRemote(path: String): RemoteFileProps = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkLinked()
            val p = path.trim()
            val script = """
                f="$p"
                if [ ! -e "${'$'}f" ]; then echo 'MISSING'; exit 0; fi
                if [ -L "${'$'}f" ]; then t=link
                elif [ -d "${'$'}f" ]; then t=dir
                elif [ -f "${'$'}f" ]; then t=file
                else t=other
                fi
                sz=${'$'}(stat -c %s "${'$'}f" 2>/dev/null || wc -c < "${'$'}f" 2>/dev/null || echo 0)
                mt=${'$'}(stat -c %Y "${'$'}f" 2>/dev/null || echo 0)
                pm=${'$'}(stat -c %A "${'$'}f" 2>/dev/null || echo '?')
                ow=${'$'}(stat -c %U:%G "${'$'}f" 2>/dev/null || echo '?')
                lt=""
                if [ -L "${'$'}f" ]; then lt=${'$'}(readlink "${'$'}f" 2>/dev/null || true); fi
                r=0; w=0
                [ -r "${'$'}f" ] && r=1
                [ -w "${'$'}f" ] && w=1
                nm=${'$'}(basename "${'$'}f")
                printf 'OK\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n' \
                  "${'$'}t" "${'$'}sz" "${'$'}mt" "${'$'}pm" "${'$'}ow" "${'$'}r" "${'$'}w" "${'$'}nm" "${'$'}lt"
            """.trimIndent()
            val out = shell(script, 15_000).lines().map { it.trimEnd('\r') }
            if (out.firstOrNull() == "MISSING" || out.firstOrNull() != "OK") {
                error("文件不存在或无法访问")
            }
            fun line(i: Int) = out.getOrNull(i + 1).orEmpty()
            val type = line(0)
            RemoteFileProps(
                path = p,
                name = line(7).ifBlank { p.substringAfterLast('/') },
                isDir = type == "dir",
                isLink = type == "link",
                size = line(1).toLongOrNull() ?: 0L,
                mtimeSec = line(2).toLongOrNull() ?: 0L,
                permissions = line(3).ifBlank { "?" },
                owner = line(4).ifBlank { "?" },
                typeLabel = when (type) {
                    "dir" -> "文件夹"
                    "link" -> "链接"
                    "file" -> "文件"
                    else -> "其他"
                },
                readable = line(5) == "1",
                writable = line(6) == "1",
                linkTarget = line(8).takeIf { it.isNotBlank() },
            )
        }
    }

    suspend fun deleteRemote(path: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkLinked()
            val out = shell("rm -rf \"$path\" 2>&1; echo EXIT:\$?", 20_000)
            if (!out.contains("EXIT:0")) {
                error(out.replace("EXIT:", "").ifBlank { "删除失败" })
            }
        }
    }

    suspend fun mkdirRemote(path: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkLinked()
            val out = shell("mkdir -p \"$path\" 2>&1; echo EXIT:\$?", 10_000)
            if (!out.contains("EXIT:0")) error(out.ifBlank { "创建文件夹失败" })
        }
    }

    suspend fun renameRemote(fromPath: String, toPath: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkLinked()
            val out = shell("mv -n \"$fromPath\" \"$toPath\" 2>&1; echo EXIT:\$?", 15_000)
            if (!out.contains("EXIT:0")) error(out.ifBlank { "重命名失败" })
        }
    }

    suspend fun copyRemote(fromPath: String, toPath: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkLinked()
            val out = shell("cp -a \"$fromPath\" \"$toPath\" 2>&1; echo EXIT:\$?", 120_000)
            if (!out.contains("EXIT:0")) error(out.ifBlank { "复制失败" })
        }
    }

    suspend fun moveRemote(fromPath: String, toPath: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkLinked()
            val out = shell("mv \"$fromPath\" \"$toPath\" 2>&1; echo EXIT:\$?", 60_000)
            if (!out.contains("EXIT:0")) error(out.ifBlank { "移动失败" })
        }
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
