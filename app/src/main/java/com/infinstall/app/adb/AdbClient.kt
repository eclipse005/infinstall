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
 * Unified ADB framework for Infinstall.
 *
 * Rules:
 * 1. Only this class opens AdbStream / talks to libadb
 * 2. One operation at a time (mutex)
 * 3. Single-line shell commands only
 * 4. Hard timeout + always close streams
 * 5. Never open a second TCP client to adbd while connected
 */
class AdbClient private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val manager get() = InfinstallAdbManager.get(appContext)
    private val mutex = Mutex()
    private val pool = Executors.newCachedThreadPool()
    private val cancelFlag = AtomicBoolean(false)

    @Volatile
    var host: String? = null
        private set

    @Volatile
    var port: Int? = null
        private set

    val isConnected: Boolean
        get() = host != null && port != null && manager.isConnected

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
            require(c.length in 5..8) { "配对码应为 6 位数字" }
            tcpCheck(h, pairPort)
            try {
                timed(60_000) {
                    if (!manager.pair(h, pairPort, c)) error("配对失败")
                }
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
                // host/port MUST be recorded before any shellLocked/ensureConnected.
                // Previously host stayed null until after the probe → always threw「未连接设备」.
                this@AdbClient.host = h
                this@AdbClient.port = port
                if (!manager.isConnected) {
                    throw AdbException("连接未建立")
                }
                val probe = shellLocked("echo infinstall_ok", 8_000)
                Log.i(TAG, "connected $h:$port probe=${probe.take(60)}")
                if (!probe.contains("infinstall_ok")) {
                    Log.w(TAG, "probe without expected marker (still connected)")
                }
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
        try {
            manager.disconnect()
        } catch (_: Exception) {
        }
        host = null
        port = null
    }

    // ═══════ shell ═══════

    /** Public shell (serialized). */
    suspend fun shell(command: String, timeoutMs: Long = 15_000): String =
        withContext(Dispatchers.IO) {
            mutex.withLock { shellLocked(command, timeoutMs) }
        }

    /**
     * Must hold [mutex]. Single-line command only.
     */
    fun shellLocked(command: String, timeoutMs: Long): String {
        ensureConnected()
        val cmd = command.replace("\r", "").replace('\n', ' ').trim()
        Log.i(TAG, "shell(${timeoutMs}ms): ${cmd.take(200)}")
        return timed(timeoutMs) {
            var stream: AdbStream? = null
            try {
                stream = manager.openStream("shell:$cmd")
                readAll(stream, maxBytes = 4 * 1024 * 1024)
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
            ensureConnected()
            checkCancel()
            val total = local.length()
            if (total <= 0L) throw AdbException("本地文件为空")

            val parent = remotePath.substringBeforeLast('/', "")
            if (parent.isNotEmpty() && parent != remotePath) {
                shellLocked("mkdir -p ${q(parent)}", 10_000)
            }

            // sh -c 'cat > path' then close stream = EOF for cat
            val shCmd = "cat > ${q(remotePath)}"
            var stream: AdbStream? = null
            try {
                stream = timed(15_000) {
                    manager.openStream("exec:sh -c ${q(shCmd)}")
                }
                timed(180_000) {
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
            ensureConnected()
            checkCancel()
            local.parentFile?.mkdirs()
            if (local.exists()) local.delete()

            var stream: AdbStream? = null
            try {
                stream = timed(15_000) {
                    manager.openStream("shell:cat ${q(remotePath)}")
                }
                timed(180_000) {
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

    private fun ensureConnected() {
        // Prefer live manager state — host is bookkeeping for UI / last endpoint.
        if (!manager.isConnected) throw AdbException("未连接设备")
    }

    private fun sizeFromLs(lsLine: String): Long? {
        val line = lsLine.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("total") }
            ?: return null
        if (line.contains("No such", ignoreCase = true)) return null
        val tokens = line.split(Regex("\\s+"))
        // perms nlink owner group size ...
        if (tokens.size >= 5) return tokens[4].toLongOrNull()
        return tokens.firstNotNullOfOrNull { it.toLongOrNull() }
    }

    private fun readAll(stream: AdbStream, maxBytes: Int): String {
        val input = stream.openInputStream()
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (bos.size() < maxBytes) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            bos.write(buf, 0, n)
        }
        return bos.toString(StandardCharsets.UTF_8.name())
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
            throw AdbException("操作超时（${timeoutMs / 1000}s）")
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
            Socket().use { s -> s.connect(InetSocketAddress(host, port), 3_000) }
        } catch (t: Throwable) {
            throw AdbException("无法访问 $host:$port（同一 Wi‑Fi、关 VPN、开调试）", t)
        }
    }

    private fun mapStream(t: Throwable): Throwable {
        if (t is TransferCancelledException || t is AdbException) return t
        val m = (t.message ?: "").lowercase()
        if ("closed" in m) return AdbException("连接通道已关闭，请断开后重新连接", t)
        return AdbException(t.message ?: "通信失败", t)
    }

    private fun mapConnect(t: Throwable, host: String, port: Int, pairing: Boolean): Throwable {
        if (t is AdbPairingRequiredException) {
            return AdbException("需要先配对（展开下方「配对码」选项）", t)
        }
        if (t is AdbException) {
            // Keep our message, but clarify common wireless-debug port mix-up on connect
            if (!pairing && t.message == "未连接设备") {
                return AdbException("连接失败（$host:$port）：会话未就绪，请重试", t)
            }
            return t
        }
        val m = (t.message ?: t.javaClass.simpleName)
        val head = if (pairing) "配对失败" else "连接失败"
        val hint = if (!pairing && port != 5555) {
            "。若刚配对：请用无线调试主页顶部的「连接端口」，不要用配对弹窗里的端口"
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
