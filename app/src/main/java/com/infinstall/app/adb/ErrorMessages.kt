package com.infinstall.app.adb

object ErrorMessages {
    fun humanize(throwable: Throwable, host: String? = null, port: Int? = null): String {
        val msg = (throwable.message.orEmpty() + " " + throwable.javaClass.simpleName).lowercase()
        val where = when {
            host != null && port != null -> "（$host:$port）"
            host != null -> "（$host）"
            else -> ""
        }
        return when {
            "pairing" in msg || "pair" in msg && ("fail" in msg || "error" in msg || "exception" in msg) ->
                "配对失败$where。请确认：电视/设备上已打开「使用配对码配对设备」，配对码未过期，IP 与配对端口抄写正确，且与手机同一 Wi‑Fi。"
            "pairing required" in msg || "adpairing" in msg || "need pair" in msg ->
                "需要先配对。Android 11 及以上请用「配对设备」：在无线调试里查看配对码与配对端口，完成配对后再用「直接连接」填连接端口。"
            "timeout" in msg || "timed out" in msg ->
                "连接超时$where。请确认同一 Wi‑Fi，IP/端口正确；无线调试的连接端口与配对端口不是同一个。"
            "connection refused" in msg || "refused" in msg || "econnrefused" in msg ->
                "连接被拒绝$where。端口可能已变（无线调试端口会变），请到设备上重新查看当前 IP 与端口。"
            "network is unreachable" in msg || "enotunreach" in msg || "no route" in msg ->
                "网络不可达$where。请检查是否同一网段。"
            "failed to connect" in msg || "connect failed" in msg || "unable to connect" in msg || "连接失败" in msg ->
                "无法连上设备$where。请检查 IP、端口，以及是否已开启网络调试 / 无线调试。"
            "unauthorized" in msg || "not authorized" in msg || "authentication" in msg && "fail" in msg ->
                "设备尚未授权本机。若弹出允许调试，请在设备上点允许；Android 11+ 无线调试请先完成配对。"
            "closed" in msg || "socket closed" in msg || "broken pipe" in msg ->
                "连接已断开$where。请重新连接。"
            "permission" in msg && "denied" in msg ->
                "操作被拒绝。部分系统应用无法卸载，或设备策略限制安装。"
            "install" in msg && ("fail" in msg || "error" in msg) ->
                "安装失败：${throwable.message ?: "未知错误"}。请确认 APK 完整且与设备兼容。"
            "no space" in msg || "enospc" in msg ->
                "设备存储空间不足，请清理后再安装。"
            "配对码" in msg ->
                throwable.message ?: "配对码无效"
            else ->
                "操作失败${if (throwable.message.isNullOrBlank()) "" else "：${throwable.message}"}。"
        }
    }
}
