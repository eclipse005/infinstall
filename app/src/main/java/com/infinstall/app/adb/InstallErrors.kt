package com.infinstall.app.adb

/**
 * Turn raw `pm install` / shell noise into short Chinese user messages.
 */
object InstallErrors {

    fun humanize(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return "安装失败，请重试"
        val lower = text.lowercase()

        // Already friendly
        if (text.startsWith("安装") && !lower.contains("failure") && !lower.contains("__ec")) {
            return text.lineSequence().first().trim()
        }

        return when {
            "install_failed_update_incompatible" in lower ||
                "signatures do not match" in lower ||
                "signature" in lower && "conflict" in lower ->
                "与已安装版本签名不一致。请先在设备上卸载旧版，再安装。"

            "install_failed_version_downgrade" in lower || "downgrade" in lower ->
                "不能安装更低版本。请卸载旧版，或安装更高版本号的 APK。"

            "install_failed_already_exists" in lower ->
                "应用已存在。可先卸载旧版再装，或使用覆盖安装。"

            "install_failed_insufficient_storage" in lower ||
                "not enough space" in lower ||
                "no space" in lower ->
                "设备存储空间不足，请清理后重试。"

            "install_parse_failed" in lower ||
                "invalid_apk" in lower ||
                "not a valid zip" in lower ||
                "bad.zip" in lower ->
                "APK 无效或已损坏，请重新获取安装包。"

            "install_failed_older_sdk" in lower || "older_sdk" in lower ->
                "设备系统版本过低，无法安装此应用。"

            "install_failed_newer_sdk" in lower ->
                "此 APK 要求更高系统版本。"

            "install_failed_user_restricted" in lower ||
                "install_failed_verification_failure" in lower ->
                "设备限制安装（安全策略/家长控制/未允许未知来源）。"

            "install_failed_aborted" in lower || "cancelled" in lower ->
                "安装已取消。"

            "install_failed_shared_user_incompatible" in lower ->
                "与设备上其他应用共享用户冲突，无法安装。"

            "install_failed_dexopt" in lower ->
                "设备优化安装包失败，可重启设备后重试。"

            "install_failed_container_error" in lower ||
                "install_failed_media_unavailable" in lower ->
                "存储位置不可用，请检查 SD 卡后重试。"

            "install_failed_missing_shared_library" in lower ->
                "缺少设备所需的系统库，无法安装。"

            "permission denied" in lower ->
                "无权限安装（设备限制了安装）。"

            "success" in lower && "failure" !in lower ->
                "安装成功"

            else -> {
                val bracket = Regex(
                    """Failure\s*\[([^\]]+)\]""",
                    RegexOption.IGNORE_CASE,
                ).find(text)
                if (bracket != null) {
                    val code = bracket.groupValues[1].trim()
                    "安装失败：$code"
                } else {
                    // Drop technical noise: __EC, pm install paths, stream dump
                    val cleaned = text.lineSequence()
                        .map { it.trim() }
                        .filter { line ->
                            line.isNotEmpty() &&
                                !line.contains("__EC:", ignoreCase = true) &&
                                !line.contains("__INF_", ignoreCase = true) &&
                                !line.startsWith("pkg:") &&
                                line.length < 200
                        }
                        .take(3)
                        .joinToString(" ")
                    if (cleaned.isNotBlank() && cleaned.length < 120) {
                        "安装失败：$cleaned"
                    } else {
                        "安装失败，请重试"
                    }
                }
            }
        }
    }
}
