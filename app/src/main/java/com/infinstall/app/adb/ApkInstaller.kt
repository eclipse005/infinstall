package com.infinstall.app.adb

import com.infinstall.app.adb.model.AdbException
import com.infinstall.app.adb.model.TransferProgress
import com.infinstall.app.adb.session.AdbSession
import com.infinstall.app.adb.transport.AdbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Official two-phase install (same as host `adb install`):
 *
 * 1. **Transfer** — `sync SEND` to `/data/local/tmp/ii….apk`
 * 2. **Install** — `pm install -r -t -d -g …`
 *
 * If transfer already succeeded and install failed, the remote file is **kept**.
 * Retrying the same APK (same content hash) **skips transfer** and only runs pm.
 * Remote tmp is deleted only after install Success (or session disconnect).
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
            val contentKey = writeAndHash(input, local)
            if (local.length() <= 0L) throw AdbException("APK 为空")
            installLocalFile(local, contentKey, onProgress)
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
        contentKey: String,
        onProgress: (TransferProgress) -> Unit,
    ) {
        onProgress(TransferProgress(0.05f, "正在传输"))
        var lastPct = -1
        var labeledInstall = false
        try {
            val run = session.installLocalApk(local, contentKey) { sent, total ->
                val f = (sent.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
                val pct = (f * 100).toInt()
                if (pct == lastPct) return@installLocalApk
                lastPct = pct
                // Reuse path: transport reports 100% in one shot before pm
                if (f >= 0.999f && !labeledInstall) {
                    labeledInstall = true
                    onProgress(TransferProgress(0.90f, "已传输，正在安装"))
                } else if (!labeledInstall) {
                    onProgress(TransferProgress(0.05f + f * 0.85f, "正在传输"))
                }
            }
            val lower = run.pmOutput.lowercase()
            val ok = "success" in lower && "failure" !in lower
            if (!ok) {
                android.util.Log.e("ApkInstaller", "pm install failed raw=${run.pmOutput.take(400)}")
                // Remote kept for retry — tell user clearly
                val tip = InstallErrors.humanize(run.pmOutput.ifBlank { "安装无输出" })
                throw AdbException(
                    if (run.reusedTransfer) tip
                    else "$tip（文件已传至设备，重试将跳过传输）",
                )
            }
            onProgress(TransferProgress(1f, "安装成功"))
        } catch (t: Throwable) {
            if (t is AdbException) throw t
            val stage = generateSequence(t) { it.cause }
                .filterIsInstance<AdbTransport.InstallStageException>()
                .firstOrNull()
            val base = InstallErrors.humanize(t.message ?: "安装失败")
            throw AdbException(
                if (stage != null) "$base（文件已传至设备，重试将跳过传输）" else base,
                t,
            )
        }
    }

    /** Write stream to [dest] while computing content key (size + sha256). */
    private fun writeAndHash(input: InputStream, dest: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        DigestInputStream(input, md).use { din ->
            FileOutputStream(dest).use { out -> din.copyTo(out) }
        }
        val size = dest.length()
        val hex = md.digest().joinToString("") { b -> "%02x".format(b) }
        return "$size:$hex"
    }
}
