# 无限安装（Infinstall）

用手机**无线**给智能电视 / 机顶盒 / 另一台安卓设备安装、管理应用。

## 功能

- **连接**
  - **直接连接**：输入 IP + 端口（历史可一点再连）
  - **配对设备**：Android 11+ 无线调试的配对码 + IP + 配对端口；配对后再用直接连接
- **安装 APK**：文件选择 / 分享、进度、可批量
- **应用管理**：第三方应用列表、卸载
- **手机 + 平板** UI 自适应

不做自动扫描局域网、不做扫码连接。

## 下载安装包

```bash
# 代码 push 后
./scripts/fetch-apk.sh
# → /sdcard/Download/Infinstall-debug.apk
```

## 使用摘要

**直接连接**：设备开网络调试或（已配对的）无线调试 → 填 IP 与端口 → 连接。  

**配对（Android 11+）**：无线调试 → 使用配对码配对 → 在本 App「配对设备」填 IP、配对端口、配对码 → 成功后「直接连接」用**连接端口**。

## 技术

Kotlin · Jetpack Compose · [libadb-android](https://github.com/MuntashirAkon/libadb-android)
