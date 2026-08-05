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

## 安装（两阶段，可复用传输）

与 host `adb install` 相同，逻辑上明确拆成两步：

```
【整段占有串行总线】
1. 传输  sync SEND → /data/local/tmp/ii….apk
2. 安装  shell:pm install -r -t -d -g …
3. 清理  仅 Success 后 rm；失败则保留远端文件
```

| 情况 | 行为 |
|------|------|
| 传输成功、安装失败 | **保留**远端 APK；会话内记住 contentKey（size+sha256） |
| 再装**同一** APK | STAT 校验通过 → **跳过传输**，只跑 pm |
| 安装成功 | rm 临时文件，清除 stage |
| 断开连接 | 清除 stage 记录 |

远端文件管理里的 APK：设备内 `cp` 到 tmp → pm → rm（源文件不动）。

### Stream closed（设计层处理）

ADB 单流结束时常抛 `Stream closed`，**≠ 会话死亡**：

1. 读 one-shot/shell 时：当正常 EOF 处理，保留已读到的 `Success`  
2. 再 OPEN 时：若 manager 仍 connected，**重试 1 次**（官方研究 P0）  
3. UI 文案：通道瞬时异常，请再试（不要英文 stream closed）  
4. 安装整段单锁：避免第一次装完后插队流把第二次 OPEN 打成 stream closed

## 会话生命周期（粘性 / sticky）

与 host `adb` 语义一致：**连上且 adbd 链路仍在 → 长期 Connected**。

### 连接顺序（正确）

```
Connecting
  → managerConnect（CNXN/TLS 握手成功）
  → Connected          ← 此时链路已存在，UI 显示已连接
  → 可选 shell 软检查（失败且 manager 仍 connected → 保持 Connected）
  → link-watch 启动
```

**禁止**在尚未 `Connected` 时因 `ensureLive` 拒绝 shell 而把刚握手成功的连接拆掉。

### 允许离开 Connected 的原因（仅此）

| 原因 | 说明 |
|------|------|
| 用户点断开 | [disconnect] |
| connect 失败 | **握手**失败（managerConnect 失败 / manager 未 connected） |
| **链路已死** | connection reset / broken pipe / not connected / manager 断开 |

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
