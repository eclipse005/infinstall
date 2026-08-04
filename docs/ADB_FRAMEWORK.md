# Infinstall ADB 健壮性最佳实践

## 你的理解是对的

> 连接成功后，只要对端 adbd 还在、网络还在，**会话就应该一直保持 Connected**。

超时、列目录失败、删除无权限、安装失败 —— 这些都是**操作失败**，不是断线。

## 分层

```
UI / MainViewModel
        │  只观察 SessionState，禁止自己“判死”
        ▼
    TvSession          门面
        ▼
    AdbSession         ★ 唯一生命周期所有者（状态机）
        ▼
    AdbTransport       唯一 openStream / shell / push / pull
        ▼
 InfinstallAdbManager  libadb 密钥与底层 connect/pair
```

| 组件 | 职责 |
|------|------|
| `SessionState` | Disconnected / Connecting / Pairing / Connected |
| `AdbSession` | connect / pair / disconnect；暴露 `StateFlow` |
| `AdbTransport` | 串行 I/O、超时、结束标记 shell |
| `RemoteFs` / `ApkInstaller` | 业务；失败只抛错 |

## 状态机（硬规则）

```
Disconnected
    │ connect()
    ▼
Connecting ──失败──► Disconnected
    │ 成功（握手 + 写状态 + probe）
    ▼
Connected  ◄────────────────────────────┐
    │                                    │
    │  任意 shell/push/pull 超时或业务错  │  只记 lastError
    │  ─────────────────────────────────►│  仍为 Connected
    │                                    │
    │  用户 disconnect()                 │
    └──────────────────────────────────► Disconnected
    │
    │  openStream 证实传输层已死
    │  （reset / broken pipe / socket closed）
    └──────────────────────────────────► Disconnected
```

### 禁止

1. 心跳 / 轮询 `manager.isConnected` 后自动 disconnect  
2. 超时后 disconnect  
3. 列表/删除/安装失败后 `markRemoteGone`  
4. ViewModel 再包一层 `withTimeout` 包住持锁 ADB 调用  
5. 已连接时再开第二条 TCP 探 adbd  

### 允许离开 Connected 的路径

1. 用户点「断开」  
2. 再次 `connect()` 前清理旧会话  
3. **确定性**传输死亡（`openStream` 抛出 reset/broken pipe 等）  

## Shell 约定

- 单行命令  
- 自动追加 `; echo __INF_END__`，读到标记即结束（不干等 EOF）  
- 写操作用 `__EC:$?` 校验  

## ViewModel 约定

- `connected` UI 标志跟随 `session.state`  
- 操作失败 → 横幅/日志文案，**顶栏仍显示已连接**  
- 只有 `Disconnected` 且此前是已连接时，才提示「连接已结束」  

## 无线调试 vs 5555

| 场景 | 端口 |
|------|------|
| 电视网络调试 | 多为 5555，常无需配对 |
| 无线调试 | pair(配对端口) → connect(**主页顶部连接端口**) |

## 改动检查清单

- [ ] 是否只在 `AdbSession` 改 `SessionState`？  
- [ ] 超时是否只抛 `AdbException`、不 disconnect？  
- [ ] ViewModel 失败路径是否仍保持 `connected=true`（若 session 仍 Connected）？  
- [ ] 是否新增了心跳杀会话？  
- [ ] Shell 是否单行 + 结束标记？  
