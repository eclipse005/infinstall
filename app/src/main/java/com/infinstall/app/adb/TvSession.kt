package com.infinstall.app.adb

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

data class TvAppInfo(
    val packageName: String,
    val label: String,
)

/**
 * Thread-safe session around a dadb connection.
 * Product code talks about "电视连接", not ADB.
 */
class TvSession(private val appContext: Context) {
    private val mutex = Mutex()
    private var dadb: Dadb? = null
    var host: String? = null
        private set
    var port: Int? = null
        private set

    val isConnected: Boolean get() = dadb != null

    private fun loadOrCreateKeyPair(): AdbKeyPair {
        val dir = File(appContext.filesDir, "tv_keys").apply { mkdirs() }
        val privateKey = File(dir, "key")
        val publicKey = File(dir, "key.pub")
        if (!privateKey.exists() || !publicKey.exists()) {
            AdbKeyPair.generate(privateKey, publicKey)
        }
        return AdbKeyPair.read(privateKey, publicKey)
    }

    suspend fun connect(host: String, port: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeLocked()
            val keyPair = loadOrCreateKeyPair()
            val client = Dadb.create(host.trim(), port, keyPair)
            try {
                // Probe: simple shell; fails fast if unauthorized / not adb
                val probe = client.shell("echo infinstall_ok")
                val combined = probe.allOutput
                if (combined.contains("unauthorized", ignoreCase = true)) {
                    client.close()
                    error("unauthorized")
                }
                dadb = client
                this@TvSession.host = host.trim()
                this@TvSession.port = port
            } catch (t: Throwable) {
                try {
                    client.close()
                } catch (_: Exception) {
                }
                throw t
            }
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        mutex.withLock { closeLocked() }
    }

    private fun closeLocked() {
        try {
            dadb?.close()
        } catch (_: Exception) {
        }
        dadb = null
        host = null
        port = null
    }

    suspend fun installApk(
        input: InputStream,
        displayName: String,
        cacheDir: File,
        onStatus: (String) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val client = dadb ?: error("未连接电视")
            onStatus("正在准备 $displayName …")
            val local = File(cacheDir, "install_${System.currentTimeMillis()}.apk")
            try {
                FileOutputStream(local).use { out ->
                    input.copyTo(out)
                }
                onStatus("正在传输并安装 $displayName …")
                client.install(local)
                onStatus("已安装 $displayName")
            } finally {
                local.delete()
            }
        }
    }

    suspend fun listThirdPartyApps(): List<TvAppInfo> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val client = dadb ?: error("未连接电视")
            val result = client.shell("pm list packages -3")
            val packages = result.allOutput
                .lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
                .filter { it.isNotEmpty() }
                .toList()

            packages.map { pkg ->
                val label = resolveLabel(client, pkg)
                TvAppInfo(packageName = pkg, label = label)
            }.sortedBy { it.label.lowercase() }
        }
    }

    private fun resolveLabel(client: Dadb, packageName: String): String {
        return try {
            // dumpsys is verbose; try pm path / simple fallback to package name
            val dump = client.shell(
                "dumpsys package $packageName | grep -m1 applicationLabel",
            ).allOutput
            val match = Regex("applicationLabel=([^\\s]+)").find(dump)
            match?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: packageName
        } catch (_: Exception) {
            packageName
        }
    }

    suspend fun uninstall(packageName: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val client = dadb ?: error("未连接电视")
            client.uninstall(packageName)
        }
    }
}
