# 无限安装（Infinstall）

用手机**无线**给智能电视 / 机顶盒 / 另一台安卓设备安装、管理应用。

## 功能

- **连接电视**：主路径填 IP + 端口（多为 5555）；配对码为次要选项
- **安装 APK**：选文件 / 分享、进度、可批量
- **应用管理**：可读应用名列表、卸载
- **手机 + 平板** UI 自适应

不做局域网扫描、不做二维码配对（面向电视/盒子）。

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
