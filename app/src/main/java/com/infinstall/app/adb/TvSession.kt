package com.infinstall.app.adb

import android.content.Context
import android.util.Log
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    /**
     * Android 11+ wireless debugging pairing.
     * Pairing dialog must stay open on the target until this finishes.
     */
    suspend fun pair(host: String, pairingPort: Int, pairingCode: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val h = host.trim()
                val code = pairingCode.filter { it.isDigit() }
                require(code.length in 5..8) { "配对码应为设备上显示的 6 位数字" }

                ensureTcpReachable(h, pairingPort, "配对端口")

                try {
                    val ok = manager.pair(h, pairingPort, code)
                    if (!ok) error("配对返回失败（未知原因）")
                    Log.i(TAG, "pair ok $h:$pairingPort")
                } catch (t: Throwable) {
                    Log.e(TAG, "pair failed $h:$pairingPort", t)
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
                    error("连接超时或被拒绝。若是无线调试，请确认已配对，且端口是「无线调试」主界面上的连接端口（不是配对端口）。")
                }
                // light probe
                val out = runCatching { shellLocked("echo infinstall_ok") }.getOrDefault("")
                Log.i(TAG, "connect probe: ${out.take(80)}")
                this@TvSession.host = h
                this@TvSession.port = port
            } catch (t: Throwable) {
                Log.e(TAG, "connect failed $h:$port", t)
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

    suspend fun installApk(
        input: InputStream,
        displayName: String,
        cacheDir: File,
        onStatus: (String) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(manager.isConnected) { "未连接设备" }
            onStatus("正在准备 $displayName …")
            val local = File(cacheDir, "install_${System.currentTimeMillis()}.apk")
            try {
                FileOutputStream(local).use { out -> input.copyTo(out) }
                val size = local.length()
                onStatus("正在传输并安装 $displayName（${size / 1024} KB）…")
                val stream = manager.openStream("exec:cmd package install -r -t -S $size")
                stream.openOutputStream().use { os ->
                    local.inputStream().use { it.copyTo(os) }
                    os.flush()
                }
                val result = stream.openInputStream().use { readAll(it) }
                stream.close()
                val text = result.trim()
                if (text.contains("Failure", ignoreCase = true) ||
                    text.contains("Error", ignoreCase = true)
                ) {
                    onStatus("改用备用安装方式…")
                    installViaPushPmLocked(local)
                }
                onStatus("已安装 $displayName")
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
        val installOut = shellLocked("pm install -r -t \"$remote\"")
        shellLocked("rm -f \"$remote\"")
        if (installOut.contains("Failure", ignoreCase = true) ||
            installOut.contains("Error", ignoreCase = true)
        ) {
            error(installOut.ifBlank { "安装失败" })
        }
    }

    suspend fun listThirdPartyApps(): List<TvAppInfo> = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(manager.isConnected) { "未连接设备" }
            val raw = shellLocked("pm list packages -3")
            val packages = raw.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
                .filter { it.isNotEmpty() }
                .toList()
            packages.map { pkg ->
                TvAppInfo(packageName = pkg, label = resolveLabelLocked(pkg))
            }.sortedBy { it.label.lowercase() }
        }
    }

    private fun resolveLabelLocked(packageName: String): String {
        return try {
            val dump = shellLocked("dumpsys package $packageName | grep -m1 applicationLabel")
            val match = Regex("applicationLabel=(\\S+)").find(dump)
            match?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: packageName
        } catch (_: Exception) {
            packageName
        }
    }

    suspend fun uninstall(packageName: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(manager.isConnected) { "未连接设备" }
            val out = shellLocked("pm uninstall ${packageName.trim()}")
            if (out.contains("Failure", ignoreCase = true) ||
                out.contains("DELETE_FAILED", ignoreCase = true)
            ) {
                error(out.ifBlank { "卸载失败" })
            }
        }
    }

    private fun shellLocked(command: String): String {
        val stream = manager.openStream("shell:$command")
        return try {
            stream.openInputStream().use { readAll(it) }
        } finally {
            try {
                stream.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun readAll(input: InputStream): String {
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(tmp)
            if (n <= 0) break
            buf.write(tmp, 0, n)
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
                "手机连不上平板的$label $host:$port。" +
                    "请核对：① 同一 Wi‑Fi（不要一个用流量/访客网络）；② IP 是否抄对；" +
                    "③ $label 是否就是当前弹窗/页面上显示的端口；" +
                    "④ 路由器是否开了 AP 隔离。原始错误：${t.javaClass.simpleName}: ${t.message}",
                t,
            )
        }
    }

    private fun wrap(t: Throwable, host: String, port: Int, pairing: Boolean): Throwable {
        if (t is AdbPairingRequiredException) {
            return IllegalStateException(
                "设备要求先配对。请打开平板「无线调试 → 使用配对码配对设备」，在配对弹窗仍显示时完成配对，再用连接端口连接。",
                t,
            )
        }
        val detail = buildString {
            var c: Throwable? = t
            var i = 0
            while (c != null && i < 4) {
                if (i > 0) append(" | ")
                append(c.javaClass.simpleName)
                if (!c.message.isNullOrBlank()) append(": ").append(c.message)
                c = c.cause
                i++
            }
        }
        val head = if (pairing) {
            "配对失败（$host:$port）。请保持平板配对弹窗不要关闭，配对码 6 位、配对端口与弹窗一致。"
        } else {
            "连接失败（$host:$port）。无线调试请先配对；连接时用主界面 IP:端口，不要用配对端口。"
        }
        return IllegalStateException("$head\n详情：$detail", t)
    }

    companion object {
        private const val TAG = "InfinstallSession"
    }
}
