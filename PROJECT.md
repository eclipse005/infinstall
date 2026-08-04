# 无限安装 / Infinstall

## 产品

| 项 | 内容 |
|----|------|
| 中文名 | **无限安装** |
| 英文名 | **Infinstall** |
| 定位 | 手机无线给智能电视 / 机顶盒安装 APK、传输文件 |
| 不是 | 通用 ADB 调试器 |

## 主流程

1. **连接**（IP 主路径，配对码次要；同网段 IP 预填）
2. **安装**（默认页：选 APK → 推送 → `pm install`；可取消、真进度）
3. **文件**（浏览/排序/上传下载/新建/重命名/复制剪切粘贴/删除/属性/远程装 APK）

## 明确不做

- 局域网乱扫、二维码配对（电视无摄像头）
- 主 UI 强调 ADB
- 以应用卸载列表为主功能

## 技术

Kotlin · Compose · libadb-android · Conscrypt · GitHub Actions · `scripts/fetch-apk.sh`
