package com.infinstall.app.adb

object ErrorMessages {
    fun humanize(throwable: Throwable, host: String? = null, port: Int? = null): String {
        // Prefer our wrapped messages (already Chinese + detail)
        val direct = throwable.message
        if (!direct.isNullOrBlank() && (
                direct.contains("连不上") ||
                    direct.contains("配对失败") ||
                    direct.contains("连接失败") ||
                    direct.contains("要求先配对") ||
                    direct.contains("配对码")
                )
        ) {
            return direct
        }

        val msg = (direct.orEmpty() + " " + throwable.javaClass.simpleName).lowercase()
        val where = when {
            host != null && port != null -> "（$host:$port）"
            host != null -> "（$host）"
            else -> ""
        }
        val tail = if (!direct.isNullOrBlank()) "\n详情：${throwable.javaClass.simpleName}: $direct" else ""

        return when {
            "pairing required" in msg || throwable is io.github.muntashirakon.adb.AdbPairingRequiredException ->
                "需要先配对。平板打开「无线调试 → 使用配对码配对设备」，在弹窗还开着时完成配对，再用连接端口连接。$tail"
            "tls" in msg || "ssl" in msg || "conscrypt" in msg ->
                "安全连接（TLS）失败$where。请确认本 App 为最新版，并重装后再试配对。$tail"
            "timeout" in msg || "timed out" in msg ->
                "连接超时$where。确认同一 Wi‑Fi、端口正确；配对时弹窗需保持打开。$tail"
            "connection refused" in msg || "refused" in msg || "econnrefused" in msg ->
                "连接被拒绝$where。无线调试端口会变化，请重新查看平板上当前显示的端口。$tail"
            "network is unreachable" in msg || "enotunreach" in msg || "no route" in msg ->
                "网络不可达$where。请确认同一局域网，关闭 VPN/访客 Wi‑Fi。$tail"
            "unauthorized" in msg || "not authorized" in msg || "authentication" in msg ->
                "设备未授权本机。无线调试请先配对；若弹出允许调试请点允许。$tail"
            else ->
                "操作失败$where${if (direct.isNullOrBlank()) "" else "：$direct"}。"
        }
    }
}
