package com.infinstall.app.adb

import com.infinstall.app.adb.model.AdbException
import com.infinstall.app.adb.model.TransferProgress
import com.infinstall.app.adb.session.AdbSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Official install path ≈ `adb install`:
 * 1. sync SEND APK to /data/local/tmp
 * 2. shell: pm install …
 * 3. shell: rm temp
 *
 * Progress labels are **stage** names only (UI progress bar shows %).
 */
class ApkInstaller(private val session: AdbSession) {

    suspend fun install(
        input: InputStream,
        displayName: String,
        cacheDir: File,
        onProgress: (TransferProgress) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        session.clearCancel()
        onProgress(TransferProgress(0f, "准备中"))
        val local = File(cacheDir, "apk_${System.currentTimeMillis()}.apk")
        try {
            FileOutputStream(local).use { input.copyTo(it) }
            val size = local.length()
            if (size <= 0L) throw AdbException("APK 为空")

            val remote = "/data/local/tmp/infinstall_${System.currentTimeMillis()}.apk"
            onProgress(TransferProgress(0.05f, "正在传输"))
            var lastPct = -1
            session.syncPush(local, remote) { sent, total ->
                val f = (sent.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
                // Only update progress fraction; keep stable stage label (no per-% spam)
                val pct = (f * 100).toInt()
                if (pct != lastPct) {
                    lastPct = pct
                    onProgress(TransferProgress(0.05f + f * 0.80f, "正在传输"))
                }
            }

            onProgress(TransferProgress(0.90f, "正在安装"))
            val result = session.shell(
                "pm install -r -t -g ${session.q(remote)}; echo __EC:\$?",
                90_000,
            )
            runCatching { session.shell("rm -f ${session.q(remote)}", 8_000) }

            val ok = result.contains("Success", ignoreCase = true) ||
                Regex("""__EC:0\b""").containsMatchIn(result)
            val fail = result.contains("Failure", ignoreCase = true)
            when {
                ok && !fail -> onProgress(TransferProgress(1f, "安装成功"))
                else -> throw AdbException(InstallErrors.humanize(result))
            }
        } finally {
            local.delete()
        }
    }

    suspend fun installRemotePath(remotePath: String) = withContext(Dispatchers.IO) {
        val result = session.shell(
            "pm install -r -t -g ${session.q(remotePath)}; echo __EC:\$?",
            90_000,
        )
        val ok = result.contains("Success", ignoreCase = true) ||
            Regex("""__EC:0\b""").containsMatchIn(result)
        if (!ok) {
            throw AdbException(InstallErrors.humanize(result))
        }
    }
}
