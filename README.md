# 无限安装（Infinstall）

用手机**无线**给智能电视 / 机顶盒安装、管理应用。  
不用 U 盘：电视开启网络调试后，在 App 里填 IP 连接，选 APK 安装。

## 功能

- **连接电视**：局域网自动发现（优先）→ 连接历史 → 手动 IP + 端口（默认 5555）；失败中文提示
- **安装 APK**：文件选择 / 分享到本 App、进度、可批量
- **应用管理**：列出第三方应用、确认卸载
- **手机 + 平板**：同一 App 自适应；手机单栏，平板宽屏双栏/限宽，大触控、好阅读

不做扫码。自动发现不到时再手输 IP 即可。

## 最简流程（推荐）

不做 Release，不手动在网页下 zip：

```text
推代码 → GitHub Actions 编译 → 本机自动拉下来解出 APK → 你点安装
```

代码推上去之后（或由协助开发的人 push 后），在仓库根目录执行：

```bash
./scripts/fetch-apk.sh
```

脚本会：等 Actions 成功 → 用 `gh run download` **自动解压** artifact → 把  
`Infinstall-debug.apk` 拷到 **`/sdcard/Download/`**。  
然后用文件管理器打开「下载」，点 APK 安装即可。

也可指定目录：

```bash
OUT_DIR=/sdcard/Download ./scripts/fetch-apk.sh
```

> 网页上点 Artifacts 仍会下到 zip，那是 GitHub 网站限制。用上面的脚本则不会让你手解压。

## 使用前（电视）

1. 打开开发者选项
2. 开启「网络调试」或「网络 ADB」（名称因品牌而异）
3. 手机与电视同一 Wi‑Fi
4. 打开本 App：优先点「扫描设备」；或从历史进入；或手输 IP（端口多为 `5555`）
5. 若电视弹窗询问调试授权，在电视上点允许

## 技术

Kotlin · Jetpack Compose · [dadb](https://github.com/mobile-dev-inc/dadb)

## 许可

按仓库内声明；未声明时仅供个人学习与使用。
