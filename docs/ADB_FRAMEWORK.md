# Infinstall ADB — 官方最佳实践实现

对齐文档：`docs/ADB_OFFICIAL_RESEARCH.md`  
官方来源：[developer.android.com/tools/adb](https://developer.android.com/tools/adb) · AOSP SERVICES/SYNC · libadb-android

## 分层

```
UI / MainViewModel
      ↓
  TvSession
      ↓
  AdbSession          ★ 会话状态机（粘性 Connected）
      ↓
  AdbTransport        串行 openStream
      ├─ shell:       仅命令（rm/mv/mkdir/pm/probe）
      └─ sync:        AdbSync（LIST/STAT/SEND/RECV）← 官方文件通道
```

## 官方对照

| 能力 | 官方 | 本实现 |
|------|------|--------|
| 列目录 | `sync` LIST / `adb ls` | `AdbSync.list` |
| 属性 | `sync` STAT | `AdbSync.stat` |
| 上传 | `sync` SEND / `adb push` | `AdbSync.push` |
| 下载 | `sync` RECV / `adb pull` | `AdbSync.pull` |
| 删除/改名/建目录 | shell | `shell:rm/mv/mkdir` 单次流 |
| 安装 | `adb install` ≡ push + `pm install` + rm | **唯一路径**：sync SEND → `pm install` → rm |
| 连接 | pair 一次 + connect | `AdbSession` |

## 会话规则

离开 Connected 仅当：

1. 用户 disconnect  
2. 再次 connect 前清理  
3. 确定性 TCP 死亡（reset 等）— **不含** Stream closed  

## Shell 规则（SERVICES.md + libadb 限制）

- **禁止** `openStream("shell:" + 长命令)`  
  - libadb 3.1.1 bug：destination ≥ ~104 字节 → `BufferOverflowException`（issue #25）  
  - 中文路径删除极易触发  
- **正确做法**：`openStream("shell:")`，再把命令写入 OutputStream，读到 `__INF_END__`  
- 文件操作优先 `sync:`（destination 很短）  
- 可瞬时重试 1 次；不拆会话  


## 文件规则（SYNC）

- 每次操作 open `sync:` → 干活 → QUIT → close  
- 块大小 ≤ 64KiB（DATA）  
- SEND 路径格式 `path,mode`（mode 含 S_IFREG）  

## 安装（唯一官方路径）

与 [adb install](https://developer.android.com/tools/adb#move) 分解一致，**只有这一套**：

```
1. sync SEND  →  /data/local/tmp/ii<ts>.apk   （= adb push）
2. shell:pm install -r -t -d -g <path>        （= adb shell pm install）
3. shell:rm -f <path>                         （清理）
```

- 安装页选文件、文件页装远端 APK，都进 `ApkInstaller.installLocalFile`  
- **不做** stdin 流式 / 多路径 fallback（避免行为分裂）  
- 远端 APK：先 sync RECV 到手机缓存，再走同一套  
