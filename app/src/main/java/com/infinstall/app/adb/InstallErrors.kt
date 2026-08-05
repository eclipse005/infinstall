package com.infinstall.app.adb

/**
 * Turn raw `pm install` / shell noise into short Chinese user messages.
 */
object InstallErrors {

    fun humanize(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return "安装失败，请重试"
        val lower = text.lowercase().replace('_', ' ').replace('-', ' ')

        // Already friendly
        if (text.startsWith("安装") && !text.lowercase().contains("failure") &&
            !text.lowercase().contains("__ec")
        ) {
            return text.lineSequence().first().trim()
        }

        return when {
            "no matching abis" in lower ||
                "failed to extract native libraries" in lower ||
                "install failed no matching abis" in lower ->
                "此 APK 的 CPU 架构与设备不匹配（例如只有 x86，而电视/平板是 ARM）。" +
                    "请换与设备架构一致的安装包，重试同一文件也无法安装。"

            "install failed update incompatible" in lower ||
                "signatures do not match" in lower ||
                ("signature" in lower && "conflict" in lower) ->
                "与已安装版本签名不一致。请先在设备上卸载旧版，再安装。"

            "install failed version downgrade" in lower || "downgrade" in lower ->
                "不能安装更低版本。请卸载旧版，或安装更高版本号的 APK。"

            "install failed already exists" in lower ->
                "应用已存在。可先卸载旧版再装，或使用覆盖安装。"

            "install failed insufficient storage" in lower ||
                "not enough space" in lower ||
                "no space" in lower ->
                "设备存储空间不足，请清理后重试。"

            "install parse failed" in lower ||
                "invalid apk" in lower ||
                "not a valid zip" in lower ||
                "bad.zip" in lower ->
                "APK 无效或已损坏，请重新获取安装包。"

            "install failed older sdk" in lower || "older sdk" in lower ->
                "设备系统版本过低，无法安装此应用。"

            "install failed newer sdk" in lower ->
                "此 APK 要求更高系统版本。"

            "install failed user restricted" in lower ||
                "install failed verification failure" in lower ->
                "设备限制安装（安全策略/家长控制/未允许未知来源）。"

            "install failed aborted" in lower || "cancelled" in lower ->
                "安装已取消。"

            "install failed shared user incompatible" in lower ->
                "与设备上其他应用共享用户冲突，无法安装。"

            "install failed dexopt" in lower ->
                "设备优化安装包失败，可重启设备后重试。"

            "install failed container error" in lower ||
                "install failed media unavailable" in lower ->
                "存储位置不可用，请检查 SD 卡后重试。"

            "install failed missing shared library" in lower ->
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
                    humanizeCode(code)
                } else {
                    if ("pm install" in lower || "echo" in lower || "__inf_" in lower) {
                        return "安装失败，请重试。若曾装过同名应用，请先卸载旧版。"
                    }
                    val cleaned = text.lineSequence()
                        .map { it.trim() }
                        .filter { line ->
                            line.isNotEmpty() &&
                                !line.contains("__EC:", ignoreCase = true) &&
                                !line.contains("__INF_", ignoreCase = true) &&
                                !line.startsWith("pkg:") &&
                                !line.contains("pm install", ignoreCase = true) &&
                                line.length < 120
                        }
                        .take(2)
                        .joinToString(" ")
                    when {
                        cleaned.contains("Success", ignoreCase = true) -> "安装成功"
                        cleaned.isNotBlank() && cleaned.length < 80 ->
                            "安装失败：$cleaned"
                        else -> "安装失败，请重试（可先卸载旧版后再装）"
                    }
                }
            }
        }
    }

    /**
     * True when re-pushing the same APK cannot fix the error (package/device mismatch).
     * Used to avoid “重试将跳过传输” as if that would make install succeed.
     */
    fun isPermanentPackageError(raw: String): Boolean {
        val lower = raw.lowercase().replace('_', ' ').replace('-', ' ')
        return "no matching abis" in lower ||
            "failed to extract native libraries" in lower ||
            "update incompatible" in lower ||
            "signatures do not match" in lower ||
            "version downgrade" in lower ||
            "older sdk" in lower ||
            "newer sdk" in lower ||
            "invalid apk" in lower ||
            "parse failed" in lower ||
            "missing shared library" in lower ||
            "shared user incompatible" in lower
    }

    private fun humanizeCode(code: String): String {
        val c = code.uppercase().replace(' ', '_')
        val tip = when {
            "NO_MATCHING_ABIS" in c || "MATCHING_ABIS" in c ->
                "CPU 架构不匹配，请换与设备一致的 APK（ARM/ARM64 等）"
            "UPDATE_INCOMPATIBLE" in c || "SIGNATURE" in c ->
                "与已安装版本签名不一致，请先卸载旧版再装"
            "VERSION_DOWNGRADE" in c ->
                "不能降级安装，请卸载旧版或使用更高版本"
            "INSUFFICIENT_STORAGE" in c ->
                "设备存储空间不足"
            "ALREADY_EXISTS" in c ->
                "应用已存在"
            "INVALID_APK" in c || "PARSE" in c ->
                "APK 无效或损坏"
            "OLDER_SDK" in c ->
                "设备系统版本过低"
            "NEWER_SDK" in c ->
                "此 APK 要求更高系统版本"
            "USER_RESTRICTED" in c || "VERIFICATION" in c ->
                "设备限制安装"
            "ABORTED" in c ->
                "安装被中止"
            "DEXOPT" in c ->
                "设备优化失败，可重启后再试"
            else -> null
        }
        return if (tip != null) "安装失败：$tip" else "安装失败：$code"
    }
}
