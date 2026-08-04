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
            "timeout" in msg || "timed out" in msg ->
                "连接超时$where。请确认手机和电视在同一 Wi‑Fi，电视已开启「网络调试」，端口一般为 5555。"
            "connection refused" in msg || "refused" in msg || "econnrefused" in msg ->
                "连接被拒绝$where。电视可能未开启网络调试，或端口不是 $port。"
            "network is unreachable" in msg || "enotunreach" in msg || "no route" in msg ->
                "网络不可达$where。请检查是否同一网段，或电视是否已连网。"
            "failed to connect" in msg || "connect failed" in msg || "unable to connect" in msg ->
                "无法连上电视$where。请检查 IP 是否正确，以及电视是否开启网络调试。"
            "unauthorized" in msg || "not authorized" in msg || "user rejected" in msg ->
                "电视尚未授权本机调试。请看电视屏幕是否弹出「允许网络调试」，点允许（可勾选始终允许）。"
            "closed" in msg || "socket closed" in msg || "broken pipe" in msg ->
                "连接已断开$where。请重新连接；若电视休眠，请唤醒后再试。"
            "permission" in msg && "denied" in msg ->
                "操作被拒绝。部分系统应用无法卸载，或电视策略限制安装。"
            "install" in msg && ("fail" in msg || "error" in msg) ->
                "安装失败：${throwable.message ?: "未知错误"}。请确认 APK 完整，且与电视 CPU/系统兼容。"
            "no space" in msg || "enospc" in msg ->
                "电视存储空间不足，请清理后再安装。"
            else ->
                "操作失败${if (throwable.message.isNullOrBlank()) "" else "：${throwable.message}"}。"
        }
    }
}
