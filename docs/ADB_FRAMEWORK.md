# Infinstall ADB 设计（源头级）

## 库

| 项 | 选择 |
|----|------|
| 嵌入式 ADB | **libadb-android 3.1.1**（JitPack 最新正式 tag；Google **无** App 内官方 lib） |
| TLS | Conscrypt 2.5.3 |
| 已知库缺陷 | OPEN destination 过长/UTF-8 会 BufferOverflow（master 仍在） |

**设计对策（非打补丁）**：OPEN **只**允许短 ASCII 服务名 `shell:` / `sync:`。命令与路径一律走 stream 正文或 sync 协议体。

## 分层

```
UI → TvSession → AdbSession (状态机)
                    ↓
               AdbTransport (串行 I/O，超时关流)
                  ├─ shell:  写命令到 stdin
                  └─ sync:   AdbSync LIST/STAT/SEND/RECV
```

## 安装（唯一）

```
sync SEND → /data/local/tmp/ii….apk
shell: 写 "pm install -r -t -d -g '…'"
shell: rm
```

远端文件：设备内 `cp` 到 tmp，再同一 pm（不经手机来回）。

## 规则

1. 不把命令塞进 OPEN  
2. 超时先 close AdbStream 再 cancel worker  
3. 操作失败 ≠ 断会话  
4. UI `connected` 只跟 `SessionState`  
5. 文件元数据/传输只用 sync  

## 心跳（keepalive）

连接成功后后台协程循环；用户断开 / 会话死亡时停止。

**只**在已有 ADB 会话上 one-shot `shell:echo __PING_OK__`（约每 **8s**）。  
**禁止**再 TCP 连一次 `host:port`——不少电视/盒子 adbd 只接受一个客户端，二次连接会误判「已断开」甚至干扰现有会话。

规则：

- 总线忙（安装/传输）→ 本轮算健康  
- 软失败连续 **3** 次 → `Disconnected`  
- 明确对端死亡（reset / manager 已断）→ 立即断开  
- **60s** 无健康轮次 → stale 强制断开  
- UI 只显示 **一条** 橙色提示；顶栏变 `未连接`
