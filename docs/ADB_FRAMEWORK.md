# Infinstall ADB 框架说明（给后续改动看）

## 分层（只允许单向依赖）

```
UI / MainViewModel
        ↓
    TvSession          # 门面，禁止 openStream
        ↓
 RemoteFs | ApkInstaller
        ↓
    AdbClient          # 唯一 openStream / shell / push / pull
        ↓
 InfinstallAdbManager  # libadb 密钥 + connect/pair
```

**硬规则：**

1. 只有 `AdbClient` 可以 `openStream` / 碰 `InfinstallAdbManager` 的数据面。
2. 同一时刻只允许一个操作（`mutex`）。
3. Shell 必须是**单行**命令；不要嵌套超时包超时。
4. 任何 `AdbStream` 必须在 `finally` 里关闭。
5. **已连接时禁止再开第二条 TCP 去探测 adbd**（单客户端 adbd 会误杀会话）。
6. 连接成功后：**先写入 host/port + linked，再 shell 探测**。
7. **超时 / 单次操作失败 ≠ 断开会话**。禁止在 ViewModel 里因 list/delete 失败就 `markRemoteGone`。
8. **禁止心跳自动 disconnect**。`linked` 是 UI 连接态真源，`manager.isConnected` 不可靠。
9. Shell 用 `__INF_END__` 结束标记，不要干等 stream EOF。
10. ViewModel **不要**再包一层 `withTimeout` 包住持 mutex 的 ADB 调用（会取消协程、打乱流）。

## 连接状态机

```
空闲 → tcpCheck → manager.connect → 写 host/port → shell probe → 已连接
         ↘ 失败：disconnect，host/port=null
已连接 → shell/push/pull（持 mutex）
超时 / Stream closed → disconnect，要求用户重连
```

- `isConnected` = `host != null && port != null && manager.isConnected`
- `ensureConnected()` 只看 `manager.isConnected`（数据面）
- 配对（pair）≠ 连接（connect）；**配对端口 ≠ 连接端口**

## 无线调试 vs 5555

| 场景 | 端口 |
|------|------|
| 经典 adbd 网络调试（电视常见） | 多为 **5555**，一般无需配对 |
| Android 11+「无线调试」 | 先 pair(配对端口+码)，再 connect(**主页顶部连接端口**) |

## 历史事故（禁止重演）

| 症状 | 根因 |
|------|------|
| 文件大小全 0K | `ls -1` 只有名字 → 必须用 `ls -lA` |
| 假「设备已断开」 | 已连接时再 TCP 探测 adbd |
| Stream closed / 操作半死 | 多行 shell、嵌套 timeout、流未关 |
| 配对成功却「未连接设备」 | connect 里 probe 前未写 host，且 shell 检查 host |
| 取消无提示 | `copy` 里先清 flag 再读 flag |

## 改动检查清单

- [ ] 新 shell 是否单行？是否有 `__EC:$?` 或等价校验？
- [ ] 是否只通过 `AdbClient` 开流？
- [ ] connect 路径是否在任何 shell 前设置了 host/port？
- [ ] 失败时是否会错误地 `markRemoteGone`（单文件失败 ≠ 断连）？
- [ ] 超时后会话是否清干净？
- [ ] UI 默认端口 5555；配对成功后是否引导填「连接端口」？
- [ ] 本地无法完整编 Android 时，至少过一遍上述状态机 + CI 构建
