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
 * **唯一**官方安装路径（与文档中 `adb install` 分解步骤一致）:
 *
 * ```
 * adb push app.apk /data/local/tmp/….apk   →  sync SEND
 * adb shell pm install -r -t -g …         →  shell:pm install
 * adb shell rm /data/local/tmp/….apk      →  shell:rm
 * ```
 *
 * 入口无论「安装页选文件」还是「文件页装远端 APK」，都落到 [installLocalFile]。
 * 不再使用 stdin / 双路径 fallback，避免行为分裂。
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
        // 统一：先拉到本机，再走同一套 push + pm（不直接从 /sdcard pm，电视上常失败）
        val cache = File.createTempFile("apk_remote_", ".apk")
        try {
            session.syncPull(remotePath, cache) { }
            if (cache.length() <= 0L) throw AdbException("无法读取远端 APK")
            installLocalFile(cache) { /* 文件页装包：进度由外层 banner 表达即可 */ }
        } finally {
            cache.delete()
        }
    }

    /**
     * 唯一安装实现：sync 推到 /data/local/tmp → pm install → 删除临时文件。
     */
    private suspend fun installLocalFile(
        local: File,
        onProgress: (TransferProgress) -> Unit,
    ) {
        val size = local.length()
        if (size <= 0L) throw AdbException("APK 为空")

        // 短 ASCII 路径，保证 shell:pm… 的 OPEN destination < libadb ~104 字节限制
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

            onProgress(TransferProgress(0.90f, "正在安装"))
            val raw = session.pmInstall(remote)
            val ok = raw.contains("Success", ignoreCase = true) &&
                !raw.contains("Failure", ignoreCase = true)
            if (!ok) {
                throw AdbException(InstallErrors.humanize(raw.ifBlank { "安装无输出" }))
            }
            onProgress(TransferProgress(1f, "安装成功"))
        } finally {
            runCatching { session.shell("rm -f ${session.q(remote)}", 8_000) }
        }
    }
}
