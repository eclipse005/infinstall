package com.infinstall.app.adb

import com.infinstall.app.adb.model.AdbException
import com.infinstall.app.adb.model.RemoteFile
import com.infinstall.app.adb.model.RemoteFileProps
import com.infinstall.app.adb.model.TransferProgress
import com.infinstall.app.adb.session.AdbSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Remote FS:
 * - Metadata & transfer → **sync only** (official)
 * - Mutating commands → **shell only**, then verify with sync when it matters
 */
class RemoteFs(private val session: AdbSession) {

    suspend fun list(path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        session.syncList(normalize(path))
    }

    suspend fun props(path: String): RemoteFileProps = withContext(Dispatchers.IO) {
        val p = path.trim()
        val st = session.syncStat(p)
        if (st.mode == 0 && st.size == 0L && st.mtimeSec == 0L) {
            throw AdbException("文件不存在")
        }
        val name = p.trimEnd('/').substringAfterLast('/').ifBlank { p }
        RemoteFileProps(
            path = p,
            name = name,
            isDir = st.isDir,
            isLink = st.isLink,
            size = st.size,
            mtimeSec = st.mtimeSec,
            permissions = modeToPerms(st.mode),
            owner = "?",
            typeLabel = when {
                st.isDir -> "文件夹"
                st.isLink -> "链接"
                else -> "文件"
            },
            readable = true,
            writable = true,
            linkTarget = null,
        )
    }

    suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        val p = path.trim()
        val st0 = runCatching { session.syncStat(p) }.getOrNull()
        val isDir = st0?.isDir == true
        // Safer than always rm -rf: files use rm -f, dirs use rm -r
        val cmd = if (isDir) "rm -r ${session.q(p)}" else "rm -f ${session.q(p)}"
        val out = session.shell(cmd, 30_000)
        val lower = out.lowercase()
        if ("permission denied" in lower || "read-only" in lower) {
            throw AdbException(cleanErr("删除失败", out, p))
        }
        val st = runCatching { session.syncStat(p) }.getOrNull()
        if (st != null && (st.mode != 0 || st.size != 0L)) {
            throw AdbException("删除失败（仍存在，可能无权限）\n$p")
        }
    }

    suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        val p = path.trim()
        val out = session.shell("mkdir -p ${session.q(p)}", 12_000)
        val lower = out.lowercase()
        if ("permission denied" in lower || "read-only" in lower) {
            throw AdbException(cleanErr("创建失败", out, p))
        }
        val st = session.syncStat(p)
        if (!st.isDir && st.mode == 0) {
            throw AdbException("创建失败（目录未出现）\n$p")
        }
    }

    suspend fun rename(from: String, to: String) = withContext(Dispatchers.IO) {
        val out = session.shell("mv ${session.q(from)} ${session.q(to)}", 20_000)
        val lower = out.lowercase()
        if ("permission denied" in lower || "no such" in lower || "read-only" in lower) {
            throw AdbException(cleanErr("重命名失败", out, from))
        }
        val st = session.syncStat(to)
        if (st.mode == 0 && st.size == 0L) {
            throw AdbException("重命名失败（目标未出现）\n$to")
        }
    }

    suspend fun copy(from: String, to: String) = withContext(Dispatchers.IO) {
        val out = session.shell("cp -a ${session.q(from)} ${session.q(to)}", 120_000)
        val lower = out.lowercase()
        if ("permission denied" in lower || "no such" in lower || "read-only" in lower) {
            throw AdbException(cleanErr("复制失败", out, from))
        }
        val st = session.syncStat(to)
        if (st.mode == 0 && st.size == 0L) {
            throw AdbException("复制失败（目标未出现）\n$to")
        }
    }

    suspend fun move(from: String, to: String) = withContext(Dispatchers.IO) {
        val out = session.shell("mv ${session.q(from)} ${session.q(to)}", 60_000)
        val lower = out.lowercase()
        if ("permission denied" in lower || "no such" in lower || "read-only" in lower) {
            throw AdbException(cleanErr("移动失败", out, from))
        }
        val st = session.syncStat(to)
        if (st.mode == 0 && st.size == 0L) {
            throw AdbException("移动失败（目标未出现）\n$to")
        }
    }

    suspend fun upload(
        input: InputStream,
        remotePath: String,
        cacheDir: File,
        onProgress: (TransferProgress) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        session.clearCancel()
        val cache = File(cacheDir, "up_${System.currentTimeMillis()}.bin")
        try {
            FileOutputStream(cache).use { input.copyTo(it) }
            val total = cache.length()
            if (total <= 0L) throw AdbException("本地文件为空")
            onProgress(TransferProgress(0f, "正在传输"))
            session.syncPush(cache, remotePath) { sent, t ->
                val f = (sent.toFloat() / t.coerceAtLeast(1)).coerceIn(0f, 1f)
                onProgress(TransferProgress(f, "正在传输"))
            }
            onProgress(TransferProgress(1f, "已保存"))
        } finally {
            cache.delete()
        }
    }

    suspend fun download(
        remotePath: String,
        local: File,
        onProgress: (got: Long) -> Unit = {},
    ) = session.syncPull(remotePath, local, onProgress)

    private fun normalize(path: String): String {
        val p = path.trim().ifEmpty { "/sdcard" }
        return if (p != "/" && p.endsWith('/')) p.trimEnd('/') else p
    }

    private fun cleanErr(prefix: String, out: String, path: String): String {
        val msg = out.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("__INF_") }
            .joinToString(" ")
            .ifBlank { "未知错误" }
        return "$prefix：$msg\n$path"
    }

    private fun modeToPerms(mode: Int): String {
        val sIfmt = 0xF000
        val type = when (mode and sIfmt) {
            0x4000 -> 'd'
            0xA000 -> 'l'
            0x8000 -> '-'
            else -> '?'
        }
        fun bit(mask: Int, ch: Char) = if (mode and mask != 0) ch else '-'
        return buildString {
            append(type)
            append(bit(0x100, 'r')); append(bit(0x80, 'w')); append(bit(0x40, 'x'))
            append(bit(0x20, 'r')); append(bit(0x10, 'w')); append(bit(0x8, 'x'))
            append(bit(0x4, 'r')); append(bit(0x2, 'w')); append(bit(0x1, 'x'))
        }
    }
}
