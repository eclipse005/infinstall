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
| 安装 | push + `pm install` | sync SEND + shell pm |
| 连接 | pair 一次 + connect | `AdbSession` |

## 会话规则

离开 Connected 仅当：

1. 用户 disconnect  
2. 再次 connect 前清理  
3. 确定性 TCP 死亡（reset 等）— **不含** Stream closed  

## Shell 规则（SERVICES.md）

- `shell:command…`  
- 单次 openStream + 结束标记  
- 可瞬时重试 1 次；不拆会话  

## 文件规则（SYNC）

- 每次操作 open `sync:` → 干活 → QUIT → close  
- 块大小 ≤ 64KiB（DATA）  
- SEND 路径格式 `path,mode`（mode 含 S_IFREG）  
