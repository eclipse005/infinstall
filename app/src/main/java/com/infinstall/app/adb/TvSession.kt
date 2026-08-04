package com.infinstall.app.adb

import android.content.Context
import android.util.Log
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

data class TvAppInfo(
    val packageName: String,
    val label: String,
)

/**
 * Session for install/manage on a remote device.
 * All remote ops share a mutex — keep each op short so install isn't blocked by app list.
 */
class TvSession(private val appContext: Context) {
    private val mutex = Mutex()
    private val manager get() = InfinstallAdbManager.get(appContext)

    var host: String? = null
        private set
    var port: Int? = null
        private set

    val isConnected: Boolean
        get() = manager.isConnected

    suspend fun pair(host: String, pairingPort: Int, pairingCode: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val h = host.trim()
                val code = pairingCode.filter { it.isDigit() }
                require(code.length in 5..8) { "配对码应为 6 位数字" }
                ensureTcpReachable(h, pairingPort, "配对端口")
                try {
                    if (!manager.pair(h, pairingPort, code)) error("配对失败")
                } catch (t: Throwable) {
                    throw wrap(t, h, pairingPort, pairing = true)
                }
            }
        }

    suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (manager.isConnected) {
                try {
                    manager.disconnect()
                } catch (_: Exception) {
                }
            }
            val h = host.trim()
            ensureTcpReachable(h, port, "连接端口")
            try {
                val ok = manager.connect(h, port)
                if (!ok && !manager.isConnected) {
                    error("连接失败。请确认网络调试已开启，端口正确。")
                }
                // quick probe — must finish fast
                val probe = shellLocked("echo infinstall_ok", maxWaitMs = 5_000)
                if (!probe.contains("infinstall_ok") && probe.isNotBlank()) {
                    Log.w(TAG, "unexpected probe: ${probe.take(120)}")
                }
                this@TvSession.host = h
                this@TvSession.port = port
            } catch (t: Throwable) {
                try {
                    manager.disconnect()
                } catch (_: Exception) {
                }
                this@TvSession.host = null
                this@TvSession.port = null
                throw wrap(t, h, port, pairing = false)
            }
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                manager.disconnect()
            } catch (_: Exception) {
            }
            host = null
            port = null
        }
    }

    /**
     * Lightweight liveness check. Returns false if peer is gone.
     */
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        if (!manager.isConnected) return@withContext false
        try {
            // tryLock: if install/list holds lock, skip this tick (not dead)
            if (!mutex.tryLock()) return@withContext true
            try {
                val out = shellLocked("echo ping_ok", maxWaitMs = 4_000)
                out.contains("ping_ok") || manager.isConnected
            } finally {
                mutex.unlock()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ping failed", t)
            false
        }
    }

    suspend fun installApk(
        input: InputStream,
        displayName: String,
        cacheDir: File,
        onStatus: (String) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(manager.isConnected) { "未连接设备" }
            onStatus("准备 $displayName")
            val local = File(cacheDir, "install_${System.currentTimeMillis()}.apk")
            try {
                FileOutputStream(local).use { out -> input.copyTo(out) }
                val size = local.length()
                onStatus("传输安装 $displayName（${size / 1024} KB）")
                try {
                    val stream = manager.openStream("exec:cmd package install -r -t -S $size")
                    stream.openOutputStream().use { os ->
                        local.inputStream().use { it.copyTo(os) }
                        os.flush()
                    }
                    val result = stream.openInputStream().use { readUntilIdle(it, 60_000) }
                    stream.close()
                    val text = result.trim()
                    if (text.contains("Failure", ignoreCase = true) ||
                        text.contains("Error", ignoreCase = true)
                    ) {
                        onStatus("改用备用方式…")
                        installViaPushPmLocked(local)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "stream install failed, fallback", t)
                    onStatus("改用备用方式…")
                    installViaPushPmLocked(local)
                }
                onStatus("完成 $displayName")
            } finally {
                local.delete()
            }
        }
    }

    private fun installViaPushPmLocked(local: File) {
        val remote = "/data/local/tmp/infinstall_${System.currentTimeMillis()}.apk"
        val push = manager.openStream("exec:sh -c 'cat > $remote'")
        push.openOutputStream().use { os ->
            local.inputStream().use { it.copyTo(os) }
            os.flush()
        }
        try {
            push.close()
        } catch (_: Exception) {
        }
        val installOut = shellLocked("pm install -r -t \"$remote\"", maxWaitMs = 60_000)
        shellLocked("rm -f \"$remote\"", maxWaitMs = 5_000)
        if (installOut.contains("Failure", ignoreCase = true) ||
            installOut.contains("Error", ignoreCase = true)
        ) {
            error(installOut.ifBlank { "安装失败" })
        }
    }

    /**
     * Fast list only — no per-app dumpsys (that blocked install forever).
     */
    suspend fun listThirdPartyApps(): List<TvAppInfo> = withContext(Dispatchers.IO) {
        withTimeout(20_000) {
            mutex.withLock {
                check(manager.isConnected) { "未连接设备" }
                val raw = shellLocked("pm list packages -3", maxWaitMs = 12_000)
                raw.lineSequence()
                    .map { it.trim() }
                    .filter { it.startsWith("package:") }
                    .map { it.removePrefix("package:").trim() }
                    .filter { it.isNotEmpty() }
                    .map { pkg ->
                        TvAppInfo(packageName = pkg, label = friendlyFallbackName(pkg))
                    }
                    .sortedBy { it.label.lowercase() }
                    .toList()
            }
        }
    }

    /**
     * Optional: resolve a few labels without blocking forever (call after list shown).
     */
    suspend fun resolveLabel(packageName: String): String? = withContext(Dispatchers.IO) {
        if (!manager.isConnected) return@withContext null
        if (!mutex.tryLock()) return@withContext null
        try {
            val dump = shellLocked(
                "dumpsys package $packageName 2>/dev/null | grep -E \"application-label|nonLocalizedLabel|applicationLabel\" | head -n 15",
                maxWaitMs = 6_000,
            )
            pickBestLabel(dump)
        } catch (_: Exception) {
            null
        } finally {
            mutex.unlock()
        }
    }

    private fun pickBestLabel(dump: String): String? {
        val keys = listOf(
            "application-label-zh-CN",
            "application-label-zh",
            "application-label",
            "application-label-en",
        )
        for (key in keys) {
            Regex("$key:'([^']*)'").find(dump)?.groupValues?.getOrNull(1)
                ?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        Regex("nonLocalizedLabel=(\\S+)").find(dump)?.groupValues?.getOrNull(1)
            ?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return null
    }

    private fun friendlyFallbackName(packageName: String): String {
        val last = packageName.substringAfterLast('.').ifBlank { packageName }
        return last.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
    }

    suspend fun uninstall(packageName: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(manager.isConnected) { "未连接设备" }
            val out = shellLocked("pm uninstall ${packageName.trim()}", maxWaitMs = 20_000)
            if (out.contains("Failure", ignoreCase = true) ||
                out.contains("DELETE_FAILED", ignoreCase = true)
            ) {
                error(out.ifBlank { "卸载失败" })
            }
        }
    }

    private fun shellLocked(command: String, maxWaitMs: Long = 15_000): String {
        // End marker so we don't hang forever waiting for stream close
        val wrapped = "$command; echo __INF_EOF__"
        val stream = manager.openStream("shell:$wrapped")
        return try {
            stream.openInputStream().use { input ->
                val text = readUntilIdle(input, maxWaitMs)
                text.substringBefore("__INF_EOF__").trimEnd()
            }
        } finally {
            try {
                stream.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun readUntilIdle(input: InputStream, maxWaitMs: Long): String {
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(8 * 1024)
        val deadline = System.currentTimeMillis() + maxWaitMs
        var lastDataAt = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            val available = try {
                input.available()
            } catch (_: Exception) {
                break
            }
            if (available > 0) {
                val toRead = minOf(tmp.size, available)
                val n = input.read(tmp, 0, toRead)
                if (n <= 0) break
                buf.write(tmp, 0, n)
                lastDataAt = System.currentTimeMillis()
                val soFar = buf.toString(StandardCharsets.UTF_8.name())
                if (soFar.contains("__INF_EOF__")) break
            } else {
                // no bytes ready — if we already have EOF marker or idle after data, stop
                val soFar = buf.toString(StandardCharsets.UTF_8.name())
                if (soFar.contains("__INF_EOF__")) break
                if (buf.size() > 0 && System.currentTimeMillis() - lastDataAt > 800) {
                    // try one blocking short read
                    try {
                        input.read(tmp, 0, 1).let { n ->
                            if (n > 0) {
                                buf.write(tmp, 0, n)
                                lastDataAt = System.currentTimeMillis()
                            } else {
                                break
                            }
                        }
                    } catch (_: Exception) {
                        break
                    }
                } else {
                    Thread.sleep(40)
                }
            }
        }
        return buf.toString(StandardCharsets.UTF_8.name())
    }

    private fun ensureTcpReachable(host: String, port: Int, label: String) {
        try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), 3_000)
            }
        } catch (t: Throwable) {
            throw IllegalStateException(
                "无法访问$label $host:$port。请检查同一 Wi‑Fi、IP/端口，并关闭 VPN。",
                t,
            )
        }
    }

    private fun wrap(t: Throwable, host: String, port: Int, pairing: Boolean): Throwable {
        if (t is AdbPairingRequiredException) {
            return IllegalStateException("需要先配对（展开下方配对码选项）。", t)
        }
        val detail = buildString {
            var c: Throwable? = t
            var i = 0
            while (c != null && i < 3) {
                if (i > 0) append(" | ")
                append(c.javaClass.simpleName)
                if (!c.message.isNullOrBlank()) append(": ").append(c.message)
                c = c.cause
                i++
            }
        }
        val head = if (pairing) "配对失败（$host:$port）" else "连接失败（$host:$port）"
        return IllegalStateException("$head\n$detail", t)
    }

    companion object {
        private const val TAG = "InfinstallSession"
    }
}
