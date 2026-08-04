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
 * Remote filesystem ops. Failures are operation errors — they do not disconnect [AdbSession].
 */
class RemoteFs(private val session: AdbSession) {

    suspend fun list(path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val p = normalize(path)
        val out = session.shell("ls -lA ${session.q(p)}", 20_000)
        val lower = out.lowercase()
        if (lower.contains("no such file") || lower.contains("not a directory") ||
            lower.contains("permission denied")
        ) {
            val first = out.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            throw AdbException(first ?: "无法打开目录")
        }
        out.lineSequence()
            .map { it.trimEnd('\r') }
            .mapNotNull { LsParser.parseLongLine(it) }
            .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
            .toList()
    }

    suspend fun props(path: String): RemoteFileProps = withContext(Dispatchers.IO) {
        val p = path.trim()
        val out = session.shell("ls -ld ${session.q(p)}", 12_000)
        val line = out.lineSequence()
            .map { it.trimEnd('\r').trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("total") }
            ?: throw AdbException("无法读取属性")
        if (line.lowercase().contains("no such file")) {
            throw AdbException("文件不存在")
        }
        val parsed = LsParser.parseLongLine(line)
            ?: throw AdbException("无法解析属性行：$line")
        val name = p.trimEnd('/').substringAfterLast('/').ifBlank { parsed.name }
        RemoteFileProps(
            path = p,
            name = name,
            isDir = parsed.isDir,
            isLink = parsed.isLink,
            size = parsed.size,
            mtimeSec = parsed.mtimeSec,
            permissions = parsed.permissions,
            owner = "?",
            typeLabel = when {
                parsed.isDir -> "文件夹"
                parsed.isLink -> "链接"
                else -> "文件"
            },
            readable = true,
            writable = true,
            linkTarget = null,
        )
    }

    suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        val p = path.trim()
        val out = session.shell("rm -rf ${session.q(p)}; echo __EC:\$?", 30_000)
        val check = session.shell("ls -ld ${session.q(p)} 2>&1; echo __EC:\$?", 8_000)
        val stillThere = !check.contains("No such", ignoreCase = true) &&
            check.lineSequence().any { LsParser.parseLongLine(it) != null }
        if (stillThere) {
            throw AdbException(cleanErr("删除失败（可能无权限）", out, p))
        }
    }

    suspend fun mkdir(path: String) = withContext(Dispatchers.IO) {
        val p = path.trim()
        val out = session.shell("mkdir -p ${session.q(p)}; echo __EC:\$?", 12_000)
        requireOk(out, "创建失败", p)
    }

    suspend fun rename(from: String, to: String) = withContext(Dispatchers.IO) {
        val out = session.shell(
            "mv ${session.q(from)} ${session.q(to)}; echo __EC:\$?",
            20_000,
        )
        requireOk(out, "重命名失败", from)
    }

    suspend fun copy(from: String, to: String) = withContext(Dispatchers.IO) {
        val out = session.shell(
            "cp -a ${session.q(from)} ${session.q(to)}; echo __EC:\$?",
            120_000,
        )
        requireOk(out, "复制失败", from)
    }

    suspend fun move(from: String, to: String) = withContext(Dispatchers.IO) {
        val out = session.shell(
            "mv ${session.q(from)} ${session.q(to)}; echo __EC:\$?",
            60_000,
        )
        requireOk(out, "移动失败", from)
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
            onProgress(TransferProgress(0f, "开始传输 ${total / 1024} KB"))
            session.push(cache, remotePath) { sent, t ->
                val f = (sent.toFloat() / t.coerceAtLeast(1)).coerceIn(0f, 1f)
                onProgress(TransferProgress(f, "传输 ${(f * 100).toInt()}%"))
            }
            onProgress(TransferProgress(1f, "已保存 $remotePath"))
        } finally {
            cache.delete()
        }
    }

    suspend fun download(
        remotePath: String,
        local: File,
        onProgress: (got: Long) -> Unit = {},
    ) = session.pull(remotePath, local, onProgress)

    private fun normalize(path: String): String {
        val p = path.trim().ifEmpty { "/sdcard" }
        return if (p != "/" && p.endsWith('/')) p.trimEnd('/') else p
    }

    private fun exitCode(out: String): Int? {
        val m = Regex("""__EC:(\d+)""").find(out) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    private fun requireOk(out: String, prefix: String, path: String) {
        val ec = exitCode(out)
        when {
            ec == 0 -> return
            ec != null -> throw AdbException(cleanErr(prefix, out, path) + " [ec=$ec]")
            else -> {
                val lower = out.lowercase()
                if ("permission denied" in lower || "no such" in lower || "read-only" in lower) {
                    throw AdbException(cleanErr(prefix, out, path))
                }
                throw AdbException(cleanErr("$prefix（无退出码回执）", out, path))
            }
        }
    }

    private fun cleanErr(prefix: String, out: String, path: String): String {
        val msg = out.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("__EC:") }
            .joinToString(" ")
            .ifBlank { "未知错误" }
        return "$prefix：$msg\n$path"
    }
}

object LsParser {
    fun parseLongLine(line: String): RemoteFile? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("total", ignoreCase = true)) return null
        if (trimmed.length < 10) return null

        val mode = trimmed.substringBefore(' ')
        if (mode.isEmpty()) return null
        val c = mode[0]
        if (c != '-' && c != 'd' && c != 'l' && c != 'c' && c != 'b' && c != 'p' && c != 's') {
            return null
        }
        val isDir = c == 'd'
        val isLink = c == 'l'
        val rest = trimmed.substring(mode.length).trim()
        val tokens = rest.split(Regex("\\s+"))
        if (tokens.size < 5) return null

        var sizeIdx = 3
        var size = tokens.getOrNull(sizeIdx)?.toLongOrNull()
        if (size == null) {
            sizeIdx = tokens.indexOfFirst { it.toLongOrNull() != null && it.length <= 14 }
            size = tokens.getOrNull(sizeIdx)?.toLongOrNull() ?: 0L
        }
        if (sizeIdx < 0) {
            sizeIdx = 3
            size = 0L
        }

        var nameStart = sizeIdx + 1
        var mtimeSec = 0L
        if (nameStart < tokens.size) {
            val t = tokens[nameStart]
            when {
                t.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) -> {
                    mtimeSec = parseIsoDateTime(
                        tokens.getOrNull(nameStart),
                        tokens.getOrNull(nameStart + 1),
                    )
                    nameStart += 2
                }
                t.matches(Regex("[A-Za-z]{3}")) -> nameStart += 3
                else -> nameStart += 2
            }
        }
        if (nameStart >= tokens.size) return null
        var name = tokens.subList(nameStart, tokens.size).joinToString(" ")
        if (isLink) {
            val arrow = tokens.indexOf("->")
            if (arrow > nameStart) {
                name = tokens.subList(nameStart, arrow).joinToString(" ")
            } else if (" -> " in name) {
                name = name.substringBefore(" -> ")
            }
        }
        name = name.trim().trimEnd('/')
        if (name.contains('/')) name = name.substringAfterLast('/')
        if (name.isEmpty() || name == "." || name == "..") return null

        return RemoteFile(
            name = name,
            isDir = isDir,
            size = if (isDir) 0L else size,
            mtimeSec = mtimeSec,
            permissions = mode,
            isLink = isLink,
        )
    }

    private fun parseIsoDateTime(date: String?, time: String?): Long {
        if (date == null || time == null) return 0L
        return try {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getDefault()
            (fmt.parse("$date $time")?.time ?: 0L) / 1000L
        } catch (_: Exception) {
            0L
        }
    }
}
