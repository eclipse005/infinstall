package com.infinstall.app.adb

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

data class TvAppInfo(
    val packageName: String,
    val label: String,
)

/**
 * Session for install/manage on a remote device.
 * Product wording: 连接电视 — not "ADB".
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
     * Android 11+ wireless debugging: pair with code (one-time per device/trust).
     * Pairing port is NOT the same as the later connection port.
     */
    suspend fun pair(host: String, pairingPort: Int, pairingCode: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val code = pairingCode.trim()
                require(code.length in 5..8 && code.all { it.isDigit() }) {
                    "配对码应为设备上显示的 6 位数字"
                }
                manager.pair(host.trim(), pairingPort, code)
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
            val ok = manager.connect(h, port)
            if (!ok && !manager.isConnected) {
                error("连接失败")
            }
            // probe
            val out = shellLocked("echo infinstall_ok")
            if (out.contains("unauthorized", ignoreCase = true)) {
                try {
                    manager.disconnect()
                } catch (_: Exception) {
                }
                error("unauthorized")
            }
            this@TvSession.host = h
            this@TvSession.port = port
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
            check(manager.isConnected) { "未连接电视" }
            onStatus("正在准备 $displayName …")
            val local = File(cacheDir, "install_${System.currentTimeMillis()}.apk")
            try {
                FileOutputStream(local).use { out -> input.copyTo(out) }
                val size = local.length()
                onStatus("正在传输并安装 $displayName（${size / 1024} KB）…")
                // Streaming install via package manager (works on modern Android / most boxes)
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
                    // Fallback: push + pm install
                    onStatus("改用备用安装方式…")
                    installViaPushPmLocked(local)
                } else if (text.isNotEmpty() &&
                    !text.contains("Success", ignoreCase = true) &&
                    !text.contains("success", ignoreCase = true)
                ) {
                    // Some devices print little; try verify by exit via second path if clearly failed
                    if (text.contains("Exception") || text.contains("denied")) {
                        error(text)
                    }
                }
                onStatus("已安装 $displayName")
            } finally {
                local.delete()
            }
        }
    }

    private fun installViaPushPmLocked(local: File) {
        val remote = "/data/local/tmp/infinstall_${System.currentTimeMillis()}.apk"
        val size = local.length()
        // exec:cat redirect may not work; use shell dd via base stream write
        val push = manager.openStream("exec:sh -c 'cat > $remote'")
        push.openOutputStream().use { os ->
            local.inputStream().use { it.copyTo(os) }
            os.flush()
        }
        // close write side by closing stream
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
        if (!installOut.contains("Success", ignoreCase = true) &&
            installOut.isNotBlank() &&
            installOut.contains("failed", ignoreCase = true)
        ) {
            error(installOut)
        }
    }

    suspend fun listThirdPartyApps(): List<TvAppInfo> = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(manager.isConnected) { "未连接电视" }
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
            check(manager.isConnected) { "未连接电视" }
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
}
