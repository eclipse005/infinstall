package com.infinstall.app.adb

import android.content.Context
import android.util.Base64
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

    suspend fun pair(host: String, pairingPort: Int, pairingCode: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val h = host.trim()
                val code = pairingCode.filter { it.isDigit() }
                require(code.length in 5..8) { "配对码应为 6 位数字" }

                ensureTcpReachable(h, pairingPort, "配对端口")

                try {
                    val ok = manager.pair(h, pairingPort, code)
                    if (!ok) error("配对失败")
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
                    error("连接失败。无线调试请先配对，并使用「连接端口」。")
                }
                runCatching { shellLocked("echo infinstall_ok") }
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
            onStatus("准备 $displayName")
            val local = File(cacheDir, "install_${System.currentTimeMillis()}.apk")
            try {
                FileOutputStream(local).use { out -> input.copyTo(out) }
                val size = local.length()
                onStatus("安装中 $displayName（${size / 1024} KB）")
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
            val packages = listPackageNamesLocked()
            if (packages.isEmpty()) return@withLock emptyList()

            // One remote script: dump labels for all packages (prefer zh-CN)
            val labels = resolveLabelsBatchLocked(packages)
            packages.map { pkg ->
                val label = labels[pkg]?.takeIf { it.isNotBlank() && it != pkg }
                    ?: friendlyFallbackName(pkg)
                TvAppInfo(packageName = pkg, label = label)
            }.sortedBy { it.label.lowercase() }
        }
    }

    private fun listPackageNamesLocked(): List<String> {
        val raw = shellLocked("pm list packages -3")
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    /**
     * Batch-resolve human labels via dumpsys (supports application-label-zh-CN etc.).
     */
    private fun resolveLabelsBatchLocked(packages: List<String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        // Script printed as: PKG\tLABEL
        val script = buildString {
            appendLine("#!/system/bin/sh")
            // packages embedded safely
            append("PKGS=\"")
            packages.forEachIndexed { i, p ->
                if (i > 0) append(' ')
                // package names are safe [a-z0-9_.]
                append(p.filter { it.isLetterOrDigit() || it == '.' || it == '_' })
            }
            appendLine("\"")
            appendLine(
                """
                for p in ${'$'}PKGS; do
                  [ -z "${'$'}p" ] && continue
                  d=${'$'}(dumpsys package "${'$'}p" 2>/dev/null)
                  lab=""
                  for key in application-label-zh-CN application-label-zh application-label application-label-en; do
                    # formats: application-label-zh-CN:'名字'  or application-label:'Name'
                    line=${'$'}(printf '%s\n' "${'$'}d" | grep -F "${'$'}key:" 2>/dev/null | head -n 1)
                    if [ -n "${'$'}line" ]; then
                      lab=${'$'}(printf '%s\n' "${'$'}line" | sed -n "s/.*${'$'}key:'\\([^']*\\)'.*/\\1/p")
                      if [ -z "${'$'}lab" ]; then
                        lab=${'$'}(printf '%s\n' "${'$'}line" | sed -n "s/.*${'$'}key:[[:space:]]*//p" | head -c 80)
                      fi
                    fi
                    [ -n "${'$'}lab" ] && break
                  done
                  if [ -z "${'$'}lab" ]; then
                    lab=${'$'}(printf '%s\n' "${'$'}d" | grep -oE 'nonLocalizedLabel=[^[:space:]]+' 2>/dev/null | head -n 1 | cut -d= -f2-)
                  fi
                  if [ -z "${'$'}lab" ]; then
                    lab=${'$'}(printf '%s\n' "${'$'}d" | grep -oE 'applicationLabel=[^[:space:]]+' 2>/dev/null | head -n 1 | cut -d= -f2-)
                  fi
                  # strip CR
                  lab=${'$'}(printf '%s' "${'$'}lab" | tr -d '\r')
                  printf '@@%s@@%s\n' "${'$'}p" "${'$'}lab"
                done
                """.trimIndent(),
            )
        }

        return try {
            val b64 = Base64.encodeToString(
                script.toByteArray(StandardCharsets.UTF_8),
                Base64.NO_WRAP,
            )
            // base64 -d works on Android toybox; fallback base64 -D on some
            val out = shellLocked(
                "echo $b64 | (base64 -d 2>/dev/null || base64 -D 2>/dev/null || busybox base64 -d) | sh",
            )
            out.lineSequence().forEach { line ->
                val m = Regex("^@@([^@]+)@@(.*)$").find(line.trim()) ?: return@forEach
                val pkg = m.groupValues[1].trim()
                val lab = m.groupValues[2].trim()
                if (pkg.isNotEmpty() && lab.isNotEmpty()) {
                    result[pkg] = lab
                }
            }
            // Fallback per-package if batch produced nothing (broken base64/sh)
            if (result.isEmpty() && packages.isNotEmpty()) {
                packages.take(40).forEach { pkg ->
                    resolveLabelSingleLocked(pkg)?.let { result[pkg] = it }
                }
            }
            result
        } catch (t: Throwable) {
            Log.w(TAG, "batch label failed", t)
            packages.take(40).forEach { pkg ->
                resolveLabelSingleLocked(pkg)?.let { result[pkg] = it }
            }
            result
        }
    }

    private fun resolveLabelSingleLocked(packageName: String): String? {
        return try {
            val dump = shellLocked("dumpsys package $packageName")
            pickBestLabel(dump)
        } catch (_: Exception) {
            null
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
            Regex("$key:(\\S+)").find(dump)?.groupValues?.getOrNull(1)
                ?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("0x") }?.let { return it }
        }
        Regex("nonLocalizedLabel=(\\S+)").find(dump)?.groupValues?.getOrNull(1)
            ?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        Regex("applicationLabel=(\\S+)").find(dump)?.groupValues?.getOrNull(1)
            ?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("0x") }?.let { return it }
        return null
    }

    private fun friendlyFallbackName(packageName: String): String {
        val last = packageName.substringAfterLast('.').ifBlank { packageName }
        return last.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
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
                "无法访问$label $host:$port。请检查同一 Wi‑Fi、IP/端口，并关闭 VPN。",
                t,
            )
        }
    }

    private fun wrap(t: Throwable, host: String, port: Int, pairing: Boolean): Throwable {
        if (t is AdbPairingRequiredException) {
            return IllegalStateException("需要先配对，请用「配对设备」。", t)
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
        val head = if (pairing) {
            "配对失败（$host:$port）"
        } else {
            "连接失败（$host:$port）"
        }
        return IllegalStateException("$head\n$detail", t)
    }

    companion object {
        private const val TAG = "InfinstallSession"
    }
}
