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
 * Both UI entry points call [installLocalFile] only.
 * Remote file: copy **on device** to tmp (no phone round-trip), then same pm.
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
            // Device-local copy — same end state as push, no phone bandwidth
            val cpOut = session.shell("cp -f ${session.q(remotePath)} ${session.q(tmp)}")
            val lower = cpOut.lowercase()
            if ("no such" in lower || "permission denied" in lower || "read-only" in lower) {
                throw AdbException("无法复制 APK 到临时目录，请检查路径与权限")
            }
            val st = session.syncStat(tmp)
            if (st.mode == 0 && st.size == 0L) {
                throw AdbException("临时 APK 未就绪，安装中止")
            }
            runPmAndCleanup(tmp, expectedSize = st.size.takeIf { it > 0 })
        } catch (t: Throwable) {
            runCatching { session.shell("rm -f ${session.q(tmp)}") }
            throw if (t is AdbException) t else AdbException(InstallErrors.humanize(t.message ?: ""), t)
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
        try {
            session.syncPush(local, remote) { sent, total ->
                val f = (sent.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
                val pct = (f * 100).toInt()
                if (pct != lastPct) {
                    lastPct = pct
                    onProgress(TransferProgress(0.05f + f * 0.80f, "正在传输"))
                }
            }
            val st = session.syncStat(remote)
            if (st.size > 0L && st.size != size) {
                throw AdbException("传输不完整：本地 ${size}B，远端 ${st.size}B")
            }
            onProgress(TransferProgress(0.90f, "正在安装（可能需要一分钟）"))
            runPmAndCleanup(remote, expectedSize = size)
            onProgress(TransferProgress(1f, "安装成功"))
        } catch (t: Throwable) {
            runCatching { session.shell("rm -f ${session.q(remote)}") }
            throw if (t is AdbException) t
            else AdbException(InstallErrors.humanize(t.message ?: "安装失败"), t)
        }
    }

    private suspend fun runPmAndCleanup(remote: String, expectedSize: Long?) {
        try {
            if (expectedSize != null && expectedSize > 0) {
                val st = session.syncStat(remote)
                if (st.size > 0 && st.size != expectedSize) {
                    throw AdbException("安装前校验失败：远端大小 ${st.size}B")
                }
            }
            val raw = session.pmInstall(remote)
            val lower = raw.lowercase()
            val ok = "success" in lower && "failure" !in lower
            if (!ok) {
                android.util.Log.e("ApkInstaller", "pm install failed raw=${raw.take(400)}")
                throw AdbException(InstallErrors.humanize(raw.ifBlank { "安装无输出" }))
            }
        } finally {
            runCatching { session.shell("rm -f ${session.q(remote)}") }
        }
    }
}
