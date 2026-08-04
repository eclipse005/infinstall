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
 * Install APK using host-side `adb install` equivalent:
 * 1) Prefer `cmd package install -S <size>` with APK on stdin
 * 2) Fallback: sync push to /data/local/tmp + short `shell:pm install`
 *
 * Remote path install: pull/copy to temp then same pipeline (sdcard install is flaky on TV).
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
            if (local.length() <= 0L) throw AdbException("APK 为空")
            runInstallFile(local, onProgress)
        } finally {
            local.delete()
        }
    }

    suspend fun installRemotePath(remotePath: String) = withContext(Dispatchers.IO) {
        session.clearCancel()
        // Never pm-install directly from /sdcard on many TVs — copy to tmp first via sync pull
        val cache = File.createTempFile("apk_remote_", ".apk")
        try {
            session.syncPull(remotePath, cache) { }
            if (cache.length() <= 0L) throw AdbException("无法读取远端 APK")
            runInstallFile(cache) { _, _ -> }
        } finally {
            cache.delete()
        }
    }

    private suspend fun runInstallFile(
        local: File,
        onProgress: (TransferProgress) -> Unit,
    ) {
        val size = local.length().coerceAtLeast(1)
        onProgress(TransferProgress(0.05f, "正在传输"))
        var lastPct = -1
        val raw = try {
            session.installApkFile(local) { sent, total ->
                val f = (sent.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
                val pct = (f * 100).toInt()
                if (pct != lastPct) {
                    lastPct = pct
                    // 0.05..0.88 for transfer, leave headroom for "installing" if any
                    onProgress(TransferProgress(0.05f + f * 0.83f, "正在传输"))
                }
            }
        } catch (t: Throwable) {
            val msg = t.message.orEmpty()
            throw AdbException(InstallErrors.humanize(msg), t)
        }

        onProgress(TransferProgress(0.95f, "正在安装"))
        val ok = raw.contains("Success", ignoreCase = true) &&
            !raw.contains("Failure", ignoreCase = true)
        if (!ok) {
            throw AdbException(InstallErrors.humanize(raw.ifBlank { "安装无输出" }))
        }
        onProgress(TransferProgress(1f, "安装成功"))
    }
}
