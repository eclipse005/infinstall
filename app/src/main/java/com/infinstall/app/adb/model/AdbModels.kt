package com.infinstall.app.adb.model

/** One remote filesystem entry. */
data class RemoteFile(
    val name: String,
    val isDir: Boolean,
    val size: Long = 0L,
    val mtimeSec: Long = 0L,
    val permissions: String = "?",
    val isLink: Boolean = false,
) {
    fun fullPath(parent: String): String {
        val base = parent.trimEnd('/')
        return when {
            base.isEmpty() || base == "/" -> "/$name"
            else -> "$base/$name"
        }
    }
}

data class RemoteFileProps(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val isLink: Boolean,
    val size: Long,
    val mtimeSec: Long,
    val permissions: String,
    val owner: String,
    val typeLabel: String,
    val readable: Boolean,
    val writable: Boolean,
    val linkTarget: String? = null,
)

data class TransferProgress(
    /** 0f..1f */
    val fraction: Float,
    val label: String,
)

class TransferCancelledException : Exception("已取消")

class AdbException(message: String, cause: Throwable? = null) : Exception(message, cause)
