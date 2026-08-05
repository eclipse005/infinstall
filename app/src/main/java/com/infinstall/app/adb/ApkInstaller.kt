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
 * Single official install recipe (same decomposition as host `adb install`):
 *
 * ```
 * adb push  app.apk  /data/local/tmp/ii….apk
 * adb shell pm install -r -t -d -g /data/local/tmp/ii….apk
 * adb shell rm -f /data/local/tmp/ii….apk
 * ```
 *
 * Local installs run the whole recipe under **one** session serial lock
 * ([AdbSession.installLocalApk]) so no other ADB stream can interleave.
 *
 * Remote file: copy on device to tmp, then pm + rm (same end state).
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
            installLocalFile(local, onProgress)
        } finally {
            local.delete()
        }
    }

    suspend fun installRemotePath(remotePath: String) = withContext(Dispatchers.IO) {
        session.clearCancel()
        val tmp = "/data/local/tmp/ii${System.currentTimeMillis()}.apk"
        try {
            val cpOut = session.shell("cp -f ${session.q(remotePath)} ${session.q(tmp)}")
            val lower = cpOut.lowercase()
            if ("no such" in lower || "permission denied" in lower || "read-only" in lower) {
                throw AdbException("无法复制 APK 到临时目录，请检查路径与权限")
            }
            val st = session.syncStat(tmp)
            if (st.mode == 0 && st.size == 0L) {
                throw AdbException("临时 APK 未就绪，安装中止")
            }
            val raw = session.pmInstall(tmp)
            val ok = "success" in raw.lowercase() && "failure" !in raw.lowercase()
            if (!ok) {
                android.util.Log.e("ApkInstaller", "pm install failed raw=${raw.take(400)}")
                throw AdbException(InstallErrors.humanize(raw.ifBlank { "安装无输出" }))
            }
        } catch (t: Throwable) {
            throw if (t is AdbException) t else AdbException(InstallErrors.humanize(t.message ?: ""), t)
        } finally {
            runCatching { session.shell("rm -f ${session.q(tmp)}") }
        }
    }

    private suspend fun installLocalFile(
        local: File,
        onProgress: (TransferProgress) -> Unit,
    ) {
        val size = local.length()
        if (size <= 0L) throw AdbException("APK 为空")
        val remote = "/data/local/tmp/ii${System.currentTimeMillis()}.apk"

        onProgress(TransferProgress(0.05f, "正在传输"))
        var lastPct = -1
        var sawPushDone = false
        try {
            // Entire push + pm + rm under one serial lock (see AdbSession.installLocalApk)
            val raw = session.installLocalApk(local, remote) { sent, total ->
                val f = (sent.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
                val pct = (f * 100).toInt()
                if (pct != lastPct) {
                    lastPct = pct
                    if (f >= 0.999f && !sawPushDone) {
                        sawPushDone = true
                        onProgress(TransferProgress(0.90f, "正在安装（可能需要一分钟）"))
                    } else if (!sawPushDone) {
                        onProgress(TransferProgress(0.05f + f * 0.85f, "正在传输"))
                    }
                }
            }
            val lower = raw.lowercase()
            val ok = "success" in lower && "failure" !in lower
            if (!ok) {
                android.util.Log.e("ApkInstaller", "pm install failed raw=${raw.take(400)}")
                throw AdbException(InstallErrors.humanize(raw.ifBlank { "安装无输出" }))
            }
            onProgress(TransferProgress(1f, "安装成功"))
        } catch (t: Throwable) {
            // installPushPmRm already tries rm; best-effort if we failed before that
            runCatching { session.shell("rm -f ${session.q(remote)}") }
            throw if (t is AdbException) t
            else AdbException(InstallErrors.humanize(t.message ?: "安装失败"), t)
        }
    }
}
