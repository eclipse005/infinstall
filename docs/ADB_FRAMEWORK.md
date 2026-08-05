# Infinstall ADB 设计（源头级）

## 库

| 项 | 选择 |
|----|------|
| 嵌入式 ADB | **libadb-android 3.1.1**（JitPack 最新正式 tag；Google **无** App 内官方 lib） |
| TLS | Conscrypt 2.5.3 |
| 已知库缺陷 | OPEN destination 过长/UTF-8 会 BufferOverflow（master 仍在） |

**对策**：OPEN 只允许短 ASCII：`shell:` / `sync:` / 短 `shell:<cmd>`（one-shot，对齐 host `adb shell cmd`）。长路径与复杂命令走 stream 正文或 sync 协议体。

对照研究见 [ADB_OFFICIAL_RESEARCH.md](./ADB_OFFICIAL_RESEARCH.md)。

## 分层

```
UI → TvSession → AdbSession (唯一会话状态机)
                    ↓
               AdbTransport (串行 I/O；报告 LinkHealth，不拥有会话)
                  ├─ shell: / shell:<cmd>
                  └─ sync:  LIST/STAT/SEND/RECV
```

## 安装（唯一路径）

```
sync SEND → /data/local/tmp/ii….apk
shell:pm install -r -t -d -g /data/local/tmp/ii….apk   # one-shot OPEN
rm 临时文件
```

远端 APK：设备内 `cp` 到 tmp，再同一 pm。

## 会话生命周期（粘性 / sticky）

与 host `adb` 语义一致：**连上且 adbd 链路仍在 → 长期 Connected**。

### 允许离开 Connected 的原因（仅此）

| 原因 | 说明 |
|------|------|
| 用户点断开 | [disconnect] |
| connect 失败 | 握手/探活失败 |
| **链路已死** | connection reset / broken pipe / manager 非 connected |

### 明确不离开 Connected

| 现象 | 处理 |
|------|------|
| 单次操作超时 | 报操作失败 |
| Stream closed | 关流；可重试操作；**不断会话** |
| 权限/空列表/装包失败 | 报错文案 |
| 空闲观察 soft-miss / Transient | 记日志，**不断会话** |
| 二次 TCP 连 host:port | **禁止**（单客户端 adbd 会误杀） |

### 空闲链路观察（link-watch）

目的：用户闲置时，对端关掉无线调试后 UI 能变为「未连接」。

设计：

1. 仅在 **已有 ADB 会话** 上做最轻协议触达（`shell:echo …`）  
2. **禁止**再 TCP connect 到同一 host:port  
3. 结果模型 `LinkHealth`：

| 结果 | 会话 |
|------|------|
| Ok | 保持 Connected |
| Busy（总线被安装/传输占用） | 保持 Connected |
| Transient（超时、软输出、单流异常） | **保持 Connected** |
| Dead（reset / manager 已断） | → Disconnected，一条提示 |

**没有**失败计数阈值、没有 stale 强断——那些是补丁，不是会话模型。

UI：`connected` **只**跟 `SessionState`；被动断开只填 **一条** `errorMessage`。

## 规则摘要

1. 长命令/路径不进 OPEN（短 ASCII one-shot 除外）  
2. 超时先 close AdbStream 再 cancel worker  
3. **操作失败 ≠ 断会话**；**仅链路死亡断会话**  
4. 全连接串行（一个 adbd client）  
5. 文件元数据/传输用 sync  
