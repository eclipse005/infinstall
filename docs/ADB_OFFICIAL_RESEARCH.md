# ADB 官方文档与协议研究（Infinstall）

> 来源（2026-08 查阅）：
> - [Android Developers: Android Debug Bridge (adb)](https://developer.android.com/tools/adb)
> - [libadb-android SERVICES.md](https://github.com/MuntashirAkon/libadb-android/blob/master/SERVICES.md)（摘自 AOSP SERVICES.TXT）
> - AOSP adb SYNC / 协议公开说明（push/pull 使用 `sync:` 服务）
> - libadb-android README（3.1.1）

---

## 1. ADB 是什么（官方三件套）

官方定义：`adb` 是 client–server 程序，三部分：

| 组件 | 职责 |
|------|------|
| **Client** | 发命令（PC 上的 `adb` CLI，或我们 App 里的 libadb） |
| **Server** | PC 本机守护进程，监听 **5037**，管理多 client / 多设备 |
| **Daemon (adbd)** | 跑在设备上，执行实际服务 |

**重要对照：**

- PC 上的 `adb`：Client → 本机 Server(5037) → 设备 adbd  
- **Infinstall（libadb）**：App 内 Client **直接**连设备 adbd（没有本机 5037 server）  

这在 App 场景是合法路径，但意味着：

1. 我们自己负责连接生命周期（没有 host server 帮我们重连/排队）  
2. 我们必须严格遵守 adbd 的 **service 协议**（shell / sync / …）  
3. 不能假设“像 CLI 一样随便连开几百个 shell”

---

## 2. 两种无线连接（官方明确区分）

### 2.1 经典 TCP/IP（`adb tcpip 5555`）

适用：Android 10 及以下主路径；11+ 仍可用（常需先 USB 开一次）。

```
USB 连上 → adb tcpip 5555 → 拔线 → adb connect <IP>:5555
```

- 端口固定常见为 **5555**  
- **不需要**配对码  
- 电视/盒子“网络调试 / ADB over network”很多是这种  

官方：连接丢失时 → 同 Wi‑Fi → 再 `adb connect`；不行再 `adb kill-server` 重来。

### 2.2 Wireless debugging（Android 11+ 手机；**TV/Wear 要 Android 13+**）

官方步骤要点：

1. 同 Wi‑Fi  
2. 开发者选项 → **无线调试**  
3. **配对一次**（配对端口 + 6 位码）  
4. 之后用 **连接端口** `connect`（与配对端口 **不同**）  
5. 配对关系会保持，直到用户在设备上 Forget / 撤销 ADB 授权  

官方原文要点：

> You only need to pair your device to your workstation once.  
> The device will remain paired … until you explicitly forget it …

Android 17 + Platform-Tools 37 还有 **ADB Wi‑Fi 2.0**（可信网络自动连、mDNS），TV 机顶盒多数还不到这个时代。

### 2.3 对 Infinstall 的产品含义

| 场景 | 要不要配对 | 端口 |
|------|------------|------|
| 电视经典网络调试 | 通常否 | 多为 **5555** |
| 手机/平板无线调试 | 是（一次） | 主页顶部**连接端口**，≠ 配对端口 |
| 电视无线调试 | 需 Android **13+** | 同上，端口常变 |

**最佳实践：** UI 必须把「配对端口」和「连接端口」彻底拆开；连上后不要因单次命令失败就判死会话。

---

## 3. 设备上 adbd 提供的服务（协议层最佳实践）

libadb / AOSP `SERVICES.TXT` 明确列出本地服务，App 通过 `openStream("service")` 请求。

### 3.1 `shell:` —— 跑命令（官方 CLI 的 `adb shell`）

```
shell:command arg1 arg2 ...
shell:          # 交互 shell
```

**官方/库文档硬规则（我们以前容易踩）：**

1. 参数用 **空格** 分隔  
2. 参数里有空格 → 用 **双引号** `"..."`  
3. **参数里不能含双引号**，否则 “things will go very wrong”  
4. 复杂命令更适合整段交给设备 sh，而不是在 service 名里堆嵌套引号  

**最佳实践：**

- 短命令：`shell:ls -lA /sdcard`  
- 路径有空格/中文：确保在设备 shell 层正确 quoting  
- **一个 openStream = 一次服务**；用完必须 close  
- 串行化所有 stream（同一连接上并发 shell 极易 Stream closed）  
- **不要**把“Stream closed”当成整条 TCP 会话死亡（多为单流问题）

### 3.2 `sync:` —— 官方文件同步（push / pull / list）

官方 `adb push` / `adb pull` **不是**靠 `cat` 管道，而是：

```
openStream("sync:")
→ LIST / STAT / SEND / RECV 等子命令
```

| 子操作 | 用途 |
|--------|------|
| LIST | 列目录（带 mode/size/time） |
| STAT | 单文件属性 |
| SEND | 推文件到设备（= push） |
| RECV | 从设备拉文件（= pull） |

**这是文件管理的官方正确路径。**

### 3.3 我们现在在做什么（差距）

| 能力 | 官方做法 | Infinstall 现状 | 风险 |
|------|----------|-----------------|------|
| 列目录 | `sync:` LIST 或 `shell:ls` | 仅 `shell:ls -lA` | 可工作，依赖 toybox 文本解析 |
| 推文件 | **`sync:` SEND** | `exec:sh -c 'cat > path'` | **脆弱**：流半开、EOF、权限、Stream closed |
| 拉文件 | **`sync:` RECV** | `shell:cat path` | 同上 |
| 删除/改名 | shell `rm`/`mv` | shell | 合理 |
| 安装 APK | CLI: `adb install` ≈ push + `pm install` | push + `pm install` | push 不稳则装包不稳 |

**结论：通信失败、删一个 PNG 就炸，很大概率出在「用 shell 模拟文件 I/O」而不是官方 SYNC。**

---

## 4. 连接生命周期（官方语义）

官方描述连接状态大致是：

- `device`：已连上 adb server / adbd  
- `offline`：不响应  
- 丢连接：检查网络 → **再 connect** → 必要时重启 host adb server  

映射到 Infinstall：

| 事件 | 应不应该断开会话 |
|------|------------------|
| 用户点断开 | 是 |
| 换 IP 重新连接 | 是（先 disconnect 再 connect） |
| TCP 真断 / connection reset | 是 |
| shell 单次 Stream closed | **否** → 关流、重试 1 次 |
| 删除失败 / 无权限 | **否** |
| 超时 | **否**（报操作失败） |
| 心跳探测失败一次 | **否**（官方也没有“心跳失败就 kill-server”） |

**用户正确直觉：连上且 adbd 仍开着 → 应长期保持 Connected。**

---

## 5. 无线调试稳定性（官方排障要点）

官方强调：

1. 同 Wi‑Fi；部分 AP 防火墙不适合 adb  
2. 关 VPN  
3. Platform-Tools 尽量新（PC 侧）  
4. 无线调试 pairing 坏了：关无线调试 → 重启设备 → 重新 pair  
5. TV 官方无线调试要求 **Android 13+**  

App 侧额外：

- **禁止**已连接后再开第二条 TCP 去“探活”（很多 adbd 单客户端）  
- RSA/证书固定保存（我们有 `adbkey_v3`）  
- `setApi` 应对 **对端设备** 能力，不能过低  

---

## 6. 安装 APK（官方）

```
adb install [-r -t -g] path.apk
```

底层常见实现：

1. 用 **sync SEND** 把 APK 推到设备临时路径  
2. `shell:pm install ...`  
3. 删临时文件  

我们第 2、3 步对齐官方；**第 1 步应用 SYNC，而不是 cat。**

---

## 7. 对 Infinstall 的「官方对齐」改造清单（按优先级）

### P0 —— 正确性 / 稳定性（必须）

1. **实现 `sync:` 协议**（LIST / STAT / SEND / RECV）  
   - 列目录、属性、上传、下载全部走 SYNC  
   - shell 只做：`rm` / `mv` / `mkdir` / `pm install` / 极少量探测  
2. **连接状态机**（已有方向，继续坚持）  
   - 仅用户断开 / 真断网 / connect 失败才离开 Connected  
3. **严格串行 openStream** + 必 close + 单次失败可重试 1 次  
4. **Shell quoting** 遵守 SERVICES：空格用双引号规则；路径特殊字符要测  

### P1 —— 体验

5. 配对成功只影响信任；连接端口单独输入  
6. 操作失败文案带真实异常类名，不说“已断开”  
7. 可选：Connected 下失败 N 次连续 stream 错误再提示「建议重连」（仍不自动断）

### P2 —— 进阶

8. 若 libadb 对某些 TV 的 TLS 不稳，记录设备型号与日志策略  
9. 大文件 SEND 分块与进度（SYNC 协议天然支持）

---

## 8. 为什么之前会「各种断联 / 通信失败」（对照官方）

| 现象 | 对照官方后的解释 |
|------|------------------|
| 配对成功却连不上 | 配对端口 ≠ 连接端口；或 connect 后状态写反 |
| 列表 0 大小 | 用了 `ls -1` 无元数据（非协议问题） |
| 假断开 | 心跳 / 二次 TCP / 超时 disconnect 违反“会话粘性” |
| 删一个成功、再删 PNG 通信失败 | 连续 shell 流 + 非 SYNC 文件路径；Stream closed 被误杀会话 |
| 整体不稳 | **文件路径没用官方 sync:**，全靠 shell 硬撑 |

---

## 9. 下一步（先研究后动手）

**在改代码前已确认的唯一正确大方向：**

> 把文件相关能力迁到 **`sync:`**；shell 只保留系统命令；连接生命周期严格粘性。

实现顺序建议：

1. `AdbSync`：open `sync:` → STAT / LIST / SEND / RECV  
2. `RemoteFs` 改调 Sync（list/props/upload/download）  
3. 删除/重命名仍 shell，但 **单次 openStream**  
4. 真机回归：连续删 10 个 PNG、列大目录、装包  

---

## 10. 参考链接

- https://developer.android.com/tools/adb  
- https://github.com/MuntashirAkon/libadb-android  
- https://github.com/MuntashirAkon/libadb-android/blob/master/SERVICES.md  
- AOSP adb `SERVICES.TXT` / `SYNC.TXT`（Android Open Source Project）
