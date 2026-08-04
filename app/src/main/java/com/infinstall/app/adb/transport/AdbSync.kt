package com.infinstall.app.adb.transport

import android.util.Log
import com.infinstall.app.adb.model.AdbException
import com.infinstall.app.adb.model.RemoteFile
import com.infinstall.app.adb.model.TransferCancelledException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Official ADB file-sync protocol (`sync:` service).
 *
 * Spec (AOSP SYNC.TXT / file_sync_client):
 * - Request:  4-byte id + le32 pathLen + path bytes
 * - STAT rsp: 4-byte "STAT" + le32 mode + le32 size + le32 mtime
 * - LIST rsp: zero+ "DENT" + mode + size + mtime + namelen + name; ends "DONE"+zeros
 * - SEND:     "SEND" + "path,mode"; then "DATA"+chunk*; "DONE"+mtime; rsp OKAY|FAIL
 * - RECV:     "RECV" + path; then "DATA"+chunk* or "DONE" or "FAIL"
 *
 * This is what `adb push` / `adb pull` / `adb ls` use — not shell cat.
 */
class AdbSync(
    private val input: InputStream,
    private val output: OutputStream,
) {
    private val din = DataInputStream(input)
    private val dout = DataOutputStream(output)

    data class Stat(
        val mode: Int,
        val size: Long,
        val mtimeSec: Long,
    ) {
        val isDir: Boolean get() = (mode and S_IFMT) == S_IFDIR
        val isLink: Boolean get() = (mode and S_IFMT) == S_IFLNK
        val isReg: Boolean get() = (mode and S_IFMT) == S_IFREG
        val exists: Boolean get() = mode != 0 || size != 0L || mtimeSec != 0L
    }

    fun stat(path: String): Stat {
        writeRequest(ID_STAT, path.toByteArray(StandardCharsets.UTF_8))
        dout.flush()
        val id = readId()
        if (id != ID_STAT) {
            throw AdbException("sync STAT 异常响应: $id")
        }
        val mode = readLe32()
        val size = readLe32().toLong() and 0xFFFF_FFFFL
        val mtime = readLe32().toLong() and 0xFFFF_FFFFL
        return Stat(mode, size, mtime)
    }

    fun list(path: String): List<RemoteFile> {
        writeRequest(ID_LIST, path.toByteArray(StandardCharsets.UTF_8))
        dout.flush()
        val out = ArrayList<RemoteFile>()
        while (true) {
            val id = readId()
            when (id) {
                ID_DONE -> {
                    // DONE is followed by 12 zero bytes (mode/size/time)
                    skipFully(12)
                    break
                }
                ID_DENT -> {
                    val mode = readLe32()
                    val size = readLe32().toLong() and 0xFFFF_FFFFL
                    val mtime = readLe32().toLong() and 0xFFFF_FFFFL
                    val nameLen = readLe32()
                    if (nameLen < 0 || nameLen > 64 * 1024) {
                        throw AdbException("sync LIST 文件名长度异常: $nameLen")
                    }
                    val nameBytes = ByteArray(nameLen)
                    din.readFully(nameBytes)
                    val name = String(nameBytes, StandardCharsets.UTF_8)
                    if (name == "." || name == "..") continue
                    val isDir = (mode and S_IFMT) == S_IFDIR
                    val isLink = (mode and S_IFMT) == S_IFLNK
                    out.add(
                        RemoteFile(
                            name = name,
                            isDir = isDir,
                            size = if (isDir) 0L else size,
                            mtimeSec = mtime,
                            permissions = modeToPerms(mode),
                            isLink = isLink,
                        ),
                    )
                }
                ID_FAIL -> {
                    val msg = readFailMessage()
                    throw AdbException("列目录失败：$msg")
                }
                else -> throw AdbException("sync LIST 未知响应: $id")
            }
        }
        return out.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    fun push(
        local: File,
        remotePath: String,
        mode: Int = DEFAULT_FILE_MODE,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
        checkCancel: () -> Unit = {},
    ) {
        val total = local.length()
        if (total < 0) throw AdbException("本地文件无效")
        // AOSP: "path,mode" where mode is full st_mode decimal (e.g. 33188 = 0100644)
        val stMode = if (mode and S_IFMT == 0) (S_IFREG or mode) else mode
        val pathModeStr = "$remotePath,$stMode"
        writeRequest(ID_SEND, pathModeStr.toByteArray(StandardCharsets.UTF_8))
        dout.flush()

        FileInputStream(local).use { fis ->
            val buf = ByteArray(MAX_DATA)
            var sent = 0L
            while (true) {
                checkCancel()
                val n = fis.read(buf)
                if (n <= 0) break
                writePacket(ID_DATA, buf, 0, n)
                sent += n
                onProgress(sent, total.coerceAtLeast(1))
            }
        }
        // DONE packet: id + mtime in the length field (AOSP convention)
        val mtime = (System.currentTimeMillis() / 1000L).toInt()
        writeLeId(ID_DONE)
        writeLe32(mtime)
        dout.flush()

        when (val id = readId()) {
            ID_OKAY -> {
                // OKAY is followed by le32 msglen (usually 0)
                val len = readLe32()
                if (len > 0) skipFully(len.coerceAtMost(64 * 1024))
            }
            ID_FAIL -> throw AdbException("推送失败：${readFailMessage()}")
            else -> throw AdbException("推送异常响应: $id")
        }
        onProgress(total.coerceAtLeast(1), total.coerceAtLeast(1))
    }

    fun pull(
        remotePath: String,
        local: File,
        onProgress: (got: Long) -> Unit = {},
        checkCancel: () -> Unit = {},
    ) {
        local.parentFile?.mkdirs()
        if (local.exists()) local.delete()

        writeRequest(ID_RECV, remotePath.toByteArray(StandardCharsets.UTF_8))
        dout.flush()

        FileOutputStream(local).use { fos ->
            var got = 0L
            while (true) {
                checkCancel()
                when (val id = readId()) {
                    ID_DATA -> {
                        val len = readLe32()
                        if (len < 0 || len > MAX_DATA) {
                            throw AdbException("sync DATA 长度异常: $len")
                        }
                        val buf = ByteArray(len)
                        din.readFully(buf)
                        fos.write(buf)
                        got += len
                        onProgress(got)
                    }
                    ID_DONE -> {
                        // DONE is followed by 4-byte mtime (or 0)
                        try {
                            readLe32()
                        } catch (_: Exception) {
                        }
                        break
                    }
                    ID_FAIL -> throw AdbException("下载失败：${readFailMessage()}")
                    else -> throw AdbException("下载异常响应: $id")
                }
            }
        }
    }

    fun quit() {
        try {
            writeRequest(ID_QUIT, ByteArray(0))
            dout.flush()
        } catch (t: Throwable) {
            Log.w(TAG, "sync QUIT: ${t.message}")
        }
    }

    // ── wire helpers ───────────────────────────────────────

    private fun writeRequest(id: String, data: ByteArray) {
        writeLeId(id)
        writeLe32(data.size)
        if (data.isNotEmpty()) dout.write(data)
    }

    private fun writePacket(id: String, buf: ByteArray, off: Int, len: Int) {
        writeLeId(id)
        writeLe32(len)
        dout.write(buf, off, len)
        dout.flush()
    }

    private fun writeLeId(id: String) {
        require(id.length == 4)
        dout.write(id.toByteArray(StandardCharsets.US_ASCII))
    }

    private fun writeLe32(v: Int) {
        val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v)
        dout.write(b.array())
    }

    private fun readId(): String {
        val b = ByteArray(4)
        din.readFully(b)
        return String(b, StandardCharsets.US_ASCII)
    }

    private fun readLe32(): Int {
        val b = ByteArray(4)
        din.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun readFailMessage(): String {
        val len = try {
            readLe32()
        } catch (_: Exception) {
            return "FAIL"
        }
        if (len <= 0 || len > 256 * 1024) return "FAIL"
        val b = ByteArray(len)
        return try {
            din.readFully(b)
            String(b, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            "FAIL"
        }
    }

    private fun skipFully(n: Int) {
        var left = n
        val buf = ByteArray(minOf(n, 4096))
        while (left > 0) {
            val r = din.read(buf, 0, minOf(buf.size, left))
            if (r < 0) break
            left -= r
        }
    }

    companion object {
        private const val TAG = "AdbSync"
        private const val MAX_DATA = 64 * 1024

        private const val ID_STAT = "STAT"
        private const val ID_LIST = "LIST"
        private const val ID_SEND = "SEND"
        private const val ID_RECV = "RECV"
        private const val ID_DATA = "DATA"
        private const val ID_DONE = "DONE"
        private const val ID_DENT = "DENT"
        private const val ID_OKAY = "OKAY"
        private const val ID_FAIL = "FAIL"
        private const val ID_QUIT = "QUIT"

        // st_mode bits (Linux)
        private const val S_IFMT = 0xF000
        private const val S_IFREG = 0x8000
        private const val S_IFDIR = 0x4000
        private const val S_IFLNK = 0xA000

        /** Regular file rw-rw-r-- (0644) with S_IFREG → 33188 */
        const val DEFAULT_FILE_MODE = S_IFREG or 0x1A4 // 0644

        private fun modeToPerms(mode: Int): String {
            val type = when (mode and S_IFMT) {
                S_IFDIR -> 'd'
                S_IFLNK -> 'l'
                S_IFREG -> '-'
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
}
