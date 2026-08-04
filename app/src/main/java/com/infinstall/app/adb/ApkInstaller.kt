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
 * APK install. Failures do not disconnect the session.
 */
class ApkInstaller(private val session: AdbSession) {

    suspend fun install(
        input: InputStream,
        displayName: String,
        cacheDir: File,
        onProgress: (TransferProgress) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        session.clearCancel()
        onProgress(TransferProgress(0f, "准备 $displayName"))
        val local = File(cacheDir, "apk_${System.currentTimeMillis()}.apk")
        try {
            FileOutputStream(local).use { input.copyTo(it) }
            val size = local.length()
            if (size <= 0L) throw AdbException("APK 为空")

            val remote = "/data/local/tmp/infinstall_${System.currentTimeMillis()}.apk"
            onProgress(TransferProgress(0.02f, "传输中（${size / 1024} KB）"))
            session.push(local, remote) { sent, total ->
                val f = (sent.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
                onProgress(TransferProgress(0.05f + f * 0.80f, "传输 ${(f * 100).toInt()}%"))
            }

            onProgress(TransferProgress(0.90f, "正在安装…"))
            val result = session.shell(
                "pm install -r -t -g ${session.q(remote)}; echo __EC:\$?",
                90_000,
            )
            runCatching { session.shell("rm -f ${session.q(remote)}", 8_000) }

            val ok = result.contains("Success", ignoreCase = true) || result.contains("__EC:0")
            val fail = result.contains("Failure", ignoreCase = true)
            when {
                ok && !fail -> onProgress(TransferProgress(1f, "安装成功"))
                else -> {
                    val detail = result.lineSequence()
                        .filter { !it.contains("__EC:") }
                        .joinToString("\n")
                        .trim()
                        .ifBlank { result.trim() }
                    throw AdbException("安装失败\n$detail")
                }
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
        val ok = result.contains("Success", ignoreCase = true) || result.contains("__EC:0")
        if (!ok) {
            throw AdbException(
                result.lineSequence().filter { !it.contains("__EC:") }.joinToString("\n")
                    .ifBlank { "安装失败" },
            )
        }
    }
}
