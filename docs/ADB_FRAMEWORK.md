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

## 轻探活（keepalive）

- 连接成功后启动；用户断开 / 会话死亡时停止  
- 每 **12s** 空闲探测：`tryLightPing`  
  - 总线忙（正在传/装/列）→ **Busy，视为仍存活**，不累计失败  
  - `echo __PING_OK__` 成功 → 清零失败计数  
  - 失败 → 计数 +1；**连续 2 次** 才 `Disconnected`  
  - 连接级死亡（reset 等）→ 立即断开  
- UI 提示：`设备端调试已关闭或网络中断，请重新连接`  
