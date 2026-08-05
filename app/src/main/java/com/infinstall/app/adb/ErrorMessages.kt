package com.infinstall.app.adb

object ErrorMessages {
    fun humanize(throwable: Throwable, host: String? = null, port: Int? = null): String {
        // Prefer our wrapped messages (already Chinese + detail)
        val direct = throwable.message.orEmpty()
        if (throwable is AdbException && direct.isNotBlank()) {
            val dLow = direct.lowercase()
            if ("stream closed" in dLow || ("stream" in dLow && "closed" in dLow)) {
                return "通道瞬时异常，请再试一次（一般不用重连）"
            }
            // Install failures: strip technical pm/__EC noise if still present
            if (direct.contains("Failure", ignoreCase = true) ||
                direct.contains("INSTALL_", ignoreCase = true) ||
                direct.contains("__EC:") ||
                direct.contains("pm install", ignoreCase = true)
            ) {
                return InstallErrors.humanize(direct)
            }
            // Prefer first line of multi-line AdbException (no English stack crumbs)
            return direct.lineSequence().first().trim()
        }
        if (direct.isNotBlank() && (
                direct.contains("连不上") ||
                    direct.contains("配对失败") ||
                    direct.contains("连接失败") ||
                    direct.contains("删除失败") ||
                    direct.contains("创建失败") ||
                    direct.contains("重命名失败") ||
                    direct.contains("复制失败") ||
                    direct.contains("移动失败") ||
                    direct.contains("通道已关闭") ||
                    direct.contains("连接已中断") ||
                    direct.contains("请重新连接") ||
                    direct.contains("传输不完整") ||
                    direct.contains("安装失败") ||
                    direct.contains("无法打开") ||
                    direct.contains("未连接")
                )
        ) {
            return direct
        }

        val msg = (direct + " " + throwable.javaClass.simpleName).lowercase()
        val where = when {
            host != null && port != null -> "（$host:$port）"
            host != null -> "（$host）"
            else -> ""
        }

        return when {
            "bufferoverflow" in msg.replace(" ", "") || "buffer overflow" in msg ->
                "ADB 通道缓冲错误（路径/命令过长）。请重试；若仍失败请断开后重新连接。"
            "stream closed" in msg || "stream cos" in msg ||
                ("closed" in msg && "stream" in msg) ||
                "通道瞬时" in direct ->
                "通道瞬时异常，请再点一次（一般不用重连）。"
            "pairing required" in msg || throwable is io.github.muntashirakon.adb.AdbPairingRequiredException ->
                "需要先配对（展开配对码选项）。"
            "tls" in msg || "ssl" in msg || "conscrypt" in msg ->
                "安全连接失败$where，请重试或重新配对。"
            "timeout" in msg || "timed out" in msg || "超时" in msg ->
                "操作超时$where，请重试。"
            "connection refused" in msg || "refused" in msg ->
                "连接被拒绝$where，请确认网络调试仍开启。"
            "unauthorized" in msg || "not authorized" in msg ->
                "设备未授权，请在设备上点允许调试。"
            direct.isNotBlank() -> direct
            else -> "操作失败$where，请重试。"
        }
    }
}
