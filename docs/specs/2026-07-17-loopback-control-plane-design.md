# Loopback 控制面、安全状态、SSE 与活动 Turn 取消设计

- 状态：已批准；后端已实现并完成本地离线验收
- 日期：2026-07-17
- 阶段：R6.5
- 批准日期：2026-07-17
- 批准记录：用户明确批准 R6.5 G0，并授权从 G1 开始连续 TDD；远程访问、真实 Telegram、CLI+Web 和前端变更继续冻结
- Contract：[Loopback 控制面契约](../contracts/loopback-control-plane.md)
- ADR：[ADR-0011：Loopback 控制面事件采用认证 SSE](../adr/0011-use-authenticated-sse-for-loopback-control-events.md)
- 实施计划：[R6.5 Loopback 控制面工作计划](../plans/2026-07-17-r6-loopback-control-plane-implementation.md)

## 1. 设计目标

在不改变 R6.1 Message Contract、R6.3 Telegram 身份规则、R6.4 Claim/Outbox 状态机和现有会话提交语义的前提下，增加一个默认关闭的本地运维纵向切片：

```text
Telegram Volatile / Reliable Turn
        |
        +--> existing cancellation source <----- POST /cancel
        |
        +--> authoritative Outbound Sink
                    |
                    | accepted first
                    v
             non-blocking Observer Tap
                    |
                    v
          ActiveTurnRegistry + EventHub
             |                  |
             v                  v
      GET status/turns     authenticated SSE
```

关键顺序是 `primary.publish(message)` 成功后才调用 Observer。控制面可以少看一个事件，但不能先于真实渠道看到未被主 Sink 接受的事件，更不能因为 Dashboard 故障让 Agent 发布失败。

## 2. 现状约束

### 2.1 可复用边界

- `ChannelHost.snapshots()` 已隔离 Adapter Snapshot 异常，并返回安全 `ChannelStatusSnapshot`。
- Telegram Volatile 路径用 `BoundedOutboundBuffer` 同时持有出站队列和取消源。
- Telegram Reliable 路径用 `ReliableInboundCoordinator.ActiveTurn` 的 `TurnCancellationSource`，并把权威终态先提交至 Channel Ledger。
- `MessageTurnService` 已产生严格 `TURN_STARTED -> Delta* -> Terminal` 序列。
- R6.1 Message 值已限制正文长度、稳定码和 `retryable` 派生关系。
- `server.address` 当前默认是 `127.0.0.1`，但控制面仍需在显式模式下独立验证绑定和请求来源。

### 2.2 不能伪装成已支持的能力

- `--cli` 使用 `WebApplicationType.NONE`，其契约明确不启动 HTTP Server。V1 不增加混合宿主。
- `/api/v1/chat` 是同步 `SafeChatUseCase`，没有 R6.1 `InboundMessage`、活动 Registry 或异步取消 Handle。
- R6.4 `EXECUTION_UNKNOWN` 表示进程崩溃后的持久不确定性，不代表当前有 Thread 或取消源。
- Python Dashboard 当前主要提供 Session/Message/Proactive/Plugin 管理，不具备本契约的 Session、SSE 和活动 Turn 取消协议，不能直接作为兼容 API。

## 3. 模块与依赖

```text
agent-kernel
  control/
    ControlPlaneContract
    ControlTurnState / ControlCancelResult
    ControlEventProjection / ControlStableCode

agent-application
  control/
    ActiveTurnRegistry
    ActiveTurnRegistration
    ActiveTurnSnapshot
    ControlCancellationHandle
    ControlEventHub / ControlSubscription
    ObservedOutboundMessageSink

agent-bootstrap
  control/
    ControlPlaneProperties / Mode
    LoopbackRequestGuard
    OperatorSessionStore
    ControlPlaneAudit
    ControlPlaneStatusService
    ControlPlaneController
    ControlPlaneSseController
    ControlPlaneRuntime
  telegram/
    现有 Volatile/Reliable Adapter 的窄注册与 Sink 包装
```

约束：

- Kernel 不依赖 Spring、Servlet、Jackson、SLF4J、JDBC 或 Telegram。
- Application 不解析 HTTP Header、不生成 Token、不读取 `ChannelHost`。
- Bootstrap 只做安全入口、装配、JSON/SSE 投影和安全审计；不重新实现 Message/取消状态机。
- 不新增 Maven 模块、第三方依赖、数据库表、Executor 或 Scheduler。
- HTTP DTO 不复用包含原始 `turnId/sessionId/route` 的 `OutboundMessage` 序列化。

## 4. Kernel Contract

### 4.1 稳定值

Kernel 固定：

- `ControlTurnState`: `ACTIVE`、`CANCELLATION_REQUESTED`。
- `ControlCancelResult`: `CANCELLATION_REQUESTED`、`ALREADY_REQUESTED`、`ALREADY_CANCELLED`、`ALREADY_TERMINAL`、`NOT_FOUND`。
- `ControlTerminalKind`: `COMPLETED`、`CANCELLED`、`FAILED`、`SOURCE_ENDED`。
- `ControlStableCode`: 只包含 Contract 定义的安全错误和状态码，并由每个 Code 自己固定 `retryable`。
- `ControlEventProjection`: 只含 `sequence/type/content/code/retryable`，构造时再次验证 R6.1 字段组合。

`turnRef` 使用专门值对象，格式为无 Padding Base64URL，长度固定。它的 `toString()` 必须脱敏；JSON 只通过显式 DTO 获取值。

### 4.2 Fixture 消费

48 Case Fixture 分层消费：

- Kernel 消费枚举、状态、事件投影和错误派生。
- Application 消费注册、取消、Tombstone、队列和竞态。
- Bootstrap 消费绑定、Header、Session 和 HTTP/SSE 映射。

测试不能只读取 JSON 后比较常量。简单 Case 必须经过生产 Factory/Validator/Projector 生成 Actual；依赖 HTTP、并发、SQLite、阻塞 Writer 或 Spring 生命周期的 Case 则显式指向唯一聚焦测试方法，并校验该源码所有者存在，避免在 Compat 中复制整套故障矩阵。

## 5. Active Turn Registry

### 5.1 注册对象

Application 入口建议为：

```java
ActiveTurnRegistration register(
    String channel,
    ControlCancellationHandle cancellation,
    Instant startedAt);
```

Registry 内部条目只保存：

- 随机 `turnRef`。
- 合法小写 Channel 名。
- `startedAt`、最后 Message Sequence、控制状态。
- 已有取消 Handle。
- 当前 Subscription 数。

不保存 Inbound Message、原始 Turn/Session/Route/Sender、Ledger ID、正文或 Thread。Adapter 仅在即将调用 `MessageTurnService` 时注册；如果 Registry 达到容量，返回 No-op Registration、记录安全降级，原 Turn 继续执行。

`ActiveTurnRegistration` 是单次关闭句柄：

- `observe(message)`：先验证顺序，更新安全状态，然后非阻塞 Fan-out。
- `closeWithoutTerminal(code)`：移除活动项、创建 `SOURCE_ENDED` Tombstone 并关闭流。
- 观察到 Terminal 时自动移除、写 Tombstone、Fan-out Terminal 并关闭流。
- 重复关闭和 Terminal/Close 竞态幂等。

### 5.2 Tombstone

Tombstone 使用 Registry 自己的锁/CAS 维护有界 `turnRef -> terminal` Map 和到期顺序：

- 不含 Assistant 正文和原始 ID。
- 查询活动列表时不返回。
- 只为取消和订阅建立阶段提供 `ALREADY_TERMINAL`。
- 通过注入 Clock 在注册、查询和取消前惰性清理。
- 容量满时先清理到期项，再移除最早到期项；不阻止主 Turn。

进程重启后 Registry/Tombstone 全部丢失，这是 V1 明确语义。

## 6. 取消适配

`ControlCancellationHandle` 必须是对现有权威取消源的窄封装：

- Volatile Telegram：调用同一个 `BoundedOutboundBuffer.requestCancellation()`，并读取 `buffer.cancellation().reason()`。
- Reliable Telegram：调用同一个 `TurnCancellationSource.cancel(REQUESTED)`，并读取其 Token 的 Reason。

Registry 不持有第二个 `TurnCancellationSource`。取消算法：

1. 查找活动条目；不存在时再查 Tombstone。
2. 在不持有会被取消回调重入的全局锁时调用 Handle。
3. Handle 返回 First Writer 结果后，通过条目 CAS 更新 `CANCELLATION_REQUESTED` 或读取已有原因。
4. 同时到达的 Terminal 可以先胜出；调用者得到 `ALREADY_TERMINAL`，但不能复活条目。
5. 映射结果后写安全审计，不等待 Provider/Tool/Thread 真正结束。

并发测试使用 Barrier/Latch 固定：两次控制取消竞争、控制取消与 Telegram `/cancel`、Disconnect、Backpressure、Shutdown、Terminal 竞争。合法结果集合固定，但每次执行只能出现一个 First Writer。

## 7. Observer Tap 与 Event Hub

### 7.1 Sink 包装

```java
public void publish(OutboundMessage message) {
  primary.publish(message);
  registration.observeSafely(message);
}
```

- `primary` 永远先执行。
- `observeSafely` 捕获并隔离所有 Observer Runtime Failure；不能吞掉 JVM Fatal Error。
- Reliable Terminal 只有在 `DurableTerminalCoordinator` 完成 Ledger Commit 后才观察。
- Volatile Message 只有进入真实 `BoundedOutboundBuffer` 后才观察。
- 不从真实 Channel Buffer `poll` 或复制其消费权。

### 7.2 Subscriber

每个 `ControlSubscription` 拥有：

- 固定容量 `ArrayDeque<ControlEventProjection>`。
- Lock/Condition 或同等 JDK 原语。
- 从 0 开始的独立 SSE 传输序号。
- 创建时间、到期时间和原子关闭原因。

Publisher 在 Registry 的 Message 顺序内对 Subscriber 做 `offer`，不得等待。单队列满或 Terminal 到达时先从 Turn Fan-out 移除并唤醒 Writer，但保留 Event Hub 的全局容量预约，直到对应 Servlet 请求在 `finally` 中关闭 Subscription。SSE Writer 使用有界 `poll(heartbeatInterval)`：无事件发送 Comment，有事件按序写；不会创建每事件任务。

客户端断开通常表现为 Writer I/O 失败；Controller 在 `finally` 关闭 Subscription。关闭必须可重复，并准确减少全局和 Turn Subscriber 计数。

### 7.3 无重放

Hub 不保存“每个 Turn 最近 N 条事件”。建立流时只原子读取：

- 当前活动状态。
- 最后已观察 Message Sequence。
- 新 Subscription 已加入的边界。

随后发送 `stream.opened`。若 Message 与建立流竞争，必须保证它要么反映在 `lastSequence` 中、要么进入新队列，不能两边都漏；可在单条目锁内完成 Snapshot + Subscribe。

## 8. Operator Session Store

Session Store 位于 Bootstrap，因为它是 HTTP 信任边界，不是 Agent 领域：

- 注入 `SecureRandom`、`Clock` 和摘要函数测试替身。
- Token 原始字节生成后只短暂存在于创建响应；Map Key 使用 SHA-256 摘要包装值。
- 比较使用 `MessageDigest.isEqual`；遍历最多 16 个 Session，时间和内存均有硬上限。
- Session 保存随机 `actorRef`、摘要、创建/到期时间和注销状态，不保存 IP、User-Agent 或 Origin。
- 创建/认证/注销前惰性移除到期 Session；无后台清理线程。
- `toString()`、异常和审计只显示 `<redacted>`。

Session 注销和到期需要关闭属于该 Session 的 SSE，但不取消 Turn。Hub 因此保存 `actorRef -> Subscription` 的有界反向索引，不保存 Token。

## 9. HTTP 安全链

控制面使用专用 Servlet Filter/Interceptor 顺序：

```text
raw request
  -> path/method/body-size gate
  -> remoteAddr/Host/Origin gate
  -> server requestId
  -> Bearer authentication (except POST session)
  -> controller/use case
  -> no-store/nosniff response
  -> safe audit
```

实现不引入 Spring Security，但不能把校验散落到每个 Controller。Filter 只作用于 `/api/v1/control/**`，不改变 `/api/v1/chat` 和 Actuator 行为。

Body 在 V1 全部应为空；Filter 使用 Content-Length 和受限读取双重校验，拒绝 Chunked 非空 Body。Token 解析只接受一个 `Authorization` Header、精确 `Bearer` Scheme 和固定长度 Base64URL；重复 Header 拒绝。

Host Parser 必须正确处理 IPv4、Bracketed IPv6 和可选十进制端口，拒绝 Userinfo、空 Host、多个 Host、非法端口和尾随内容。请求判断使用原始 Servlet 字段，不读取 Forwarded Header。

启动门禁同时要求 `server.forward-headers-strategy=NONE`。任何会先由容器翻译远端地址、Scheme 或 Host 的策略都在 `ControlPlaneRuntime` 创建前拒绝，避免请求级原始字段判断被代理配置绕过。

## 10. Status 聚合

`ControlPlaneStatusService` 在 Controller 层组合：

- `ChannelHost.state()` 与 `snapshots()`。
- `ActiveTurnRegistry` 安全计数。
- `ControlEventHub` Subscriber、最大 Queue Depth 和慢消费者累计数。

每个来源独立捕获 Runtime Failure，映射 `DEGRADED` 和稳定码。状态聚合不持有 Channel/Registry Lock 执行 JSON 序列化，不调用网络、数据库写入或线程 Join。

R6.4 Reliability Snapshot 已提供 Pending/Unknown 计数。控制面只读取它；不得根据 `unknownExecutions` 构造活动项或取消 Handle。

## 11. Telegram 接入

### 11.1 Volatile 路径

在 `produce(active)` 中、调用 `turns.process(...)` 前尝试注册：

1. cancellation Handle 包装当前 `active.buffer()`。
2. `ObservedOutboundMessageSink` 的 Primary 仍是同一个 Buffer。
3. `turns.process(inbound, observedSink, buffer.cancellation())`。
4. `finally` 关闭 Registration；已有 Terminal 时幂等，无 Terminal 时 `SOURCE_ENDED`。

Telegram 自己的 `/cancel` 和关闭仍直接使用原 Buffer，不经过 HTTP Controller。

### 11.2 Reliable 路径

注册必须发生在 `ReliableInboundCoordinator` 已成功把 Claim 转为 `RUNNING`、即将调用注入的 `ReliableTurnProcessor` 之后：

1. cancellation Handle 包装 Coordinator 现有 `TurnCancellationSource`。
2. Primary Sink 仍是 `ReliableTelegramOutboundSink`。
3. Terminal 的 Observer Tap 位于 Durable Commit 之后。
4. 终态 Commit 失败时不发假 Terminal；Registration 以 `SOURCE_ENDED` 关闭，Ledger 后续按 R6.4 恢复为 `EXECUTION_UNKNOWN`。

为避免 Application 依赖 Telegram，`ReliableInboundCoordinator` 只增加通用可选 `ActiveTurnObserver` Port 或在 Processor 包装层接入；具体选择在 G4 RED 后以最小依赖变更确定，但不能改变 Claim 状态机。

### 11.3 Disabled 路径

Control Plane Disabled 时注入单例 No-op Observer：

- 不生成随机 ID。
- 不创建 Registry、队列或审计事件。
- 不改变构造线程、调用次数、取消原因和 Outbound 顺序。

## 12. SSE HTTP 实现

Controller 使用 Servlet Response 直接写 UTF-8 SSE 或 Spring MVC 等价原语，必须保证：

- 建立流前完成全部安全/容量/Turn 状态校验，以便仍可返回 JSON 错误。
- Header Flush 后不再尝试把异常改写成 JSON。
- 每个 Frame 对换行、UTF-8 和 JSON 做标准编码；不手工拼接未转义正文。
- Writer 每次事件 Flush，失败即关闭 Subscription；不在日志中记录正文或 IOException Message。
- `stream-max-lifetime`、Session 到期和应用 Shutdown 都能唤醒正在等待的 Writer。
- 关闭预算用 Deadline，不逐个 Subscriber 重新获得完整超时。

测试用可控 `SseWriter`/Servlet Fake 验证 Frame，而非真实墙钟或外网。Loopback 集成测试只绑定 ephemeral `127.0.0.1`。

## 13. 生命周期与关闭顺序

```text
ControlPlaneShutdownCoordinator.close()
  1. StreamTracker accepting=false，拒绝新 Servlet 流
  2. revoke all Operator Sessions
  3. ControlPlaneRuntime.close()，关闭并唤醒全部 Subscription
  4. 在一个 shared shutdown-timeout deadline 内等待 active Servlet 流归零
  5. 超时只写一次安全审计，然后返回
  6. Registry / Tombstones 由 Runtime 幂等清理
```

这一路径不主动取消 Turn。应用整体关闭随后由现有 `ChannelHost.close()` 写入 `SHUTDOWN`。如果 Spring 实际销毁顺序相反，Registry 观察既有 Terminal/Shutdown 后再幂等清理；测试必须覆盖两种顺序。

关闭超时只使安全日志标记 `CONTROL_SHUTDOWN_TIMEOUT`，并记录剩余流数与有界等待时间；不能无限等待、逐流重置 Deadline 或阻止 JVM 最终退出。已完成清理的 Session/Subscription 不重复释放许可。默认审计 Sink 只输出白名单结构化字段，不能记录原始 Actor、Turn、Token 或异常正文。

## 14. 配置与装配

`ControlPlaneProperties` 绑定 Contract 中全部上限。建议配置：

```yaml
agent:
  control-plane:
    mode: ${AGENT_CONTROL_PLANE_MODE:DISABLED}
    session-ttl: ${AGENT_CONTROL_PLANE_SESSION_TTL:15m}
    max-sessions: ${AGENT_CONTROL_PLANE_MAX_SESSIONS:4}
    max-active-turns: ${AGENT_CONTROL_PLANE_MAX_ACTIVE_TURNS:128}
    terminal-retention: ${AGENT_CONTROL_PLANE_TERMINAL_RETENTION:5m}
    max-terminal-tombstones: ${AGENT_CONTROL_PLANE_MAX_TERMINAL_TOMBSTONES:1024}
    max-subscribers: ${AGENT_CONTROL_PLANE_MAX_SUBSCRIBERS:8}
    subscriber-buffer-capacity: ${AGENT_CONTROL_PLANE_SUBSCRIBER_BUFFER_CAPACITY:64}
    heartbeat-interval: ${AGENT_CONTROL_PLANE_HEARTBEAT_INTERVAL:15s}
    stream-max-lifetime: ${AGENT_CONTROL_PLANE_STREAM_MAX_LIFETIME:15m}
    shutdown-timeout: ${AGENT_CONTROL_PLANE_SHUTDOWN_TIMEOUT:2s}
```

装配条件同时要求 Servlet Application 和 `mode=LOOPBACK`。`--cli` 即使传入 LOOPBACK 也应在启动校验中稳定拒绝，而不是静默启动一个无 HTTP 的 Registry。

## 15. 测试策略

### 15.1 确定性单元测试

- 注入 Clock、Random/ID Generator、Thread/Writer、Barrier、Latch 和 Fault Point。
- 不使用 `Thread.sleep`、全局 Thread 枚举或真实网络。
- 并发测试记录调用次数、First Writer 原因、队列深度和最终 Registry/Tombstone。
- 安全测试扫描所有 JSON、SSE、异常和 `toString()` 的敏感 Marker。

### 15.2 Loopback 集成测试

- `127.0.0.1` Ephemeral Server + 固定 Fake Token Generator。
- Session -> Status/Turns -> SSE -> Cancel 完整链路。
- Telegram API 使用现有 Loopback Stub；SQLite 使用 JUnit 临时目录。
- Reliable 路径断言 Cancel 不改变 Claim/Outbox/Delivery 表，`EXECUTION_UNKNOWN` 无 API Handle。
- 远端来源通过 Filter/Mock Request 精确模拟，不绑定真实网卡。

### 15.3 Failure Profile

- Registry 饱和、Subscriber 满、Writer 断开、Audit 失败、Snapshot 失败。
- Session 到期/注销与 SSE、Terminal、Shutdown 竞态。
- 取消与 Disconnect/Backpressure/Telegram Control/Terminal 竞态。
- Durable Terminal Commit 失败和控制面 Observer 失败。

## 16. 前端边界

后端 API 稳定前不修改 React/Vite。现有 Python Dashboard 的 Session 历史、批量删除、Proactive 和 Plugin API 均不移植到本切片。

后端 PR 通过后，先形成独立前端 Contract，决定：

- 复用视觉资产还是创建最小控制视图。
- 前端源码归属 Java 仓库还是独立静态资产仓库。
- Java 同源静态托管或开发期 Vite Proxy。
- Token 仅内存、Fetch SSE Parser、断线无重放提示和取消确认。

该选择可能引入 Node 构建和跨仓库变更，属于 R6.5 的单独批准点，不在本 G0 文档中静默决定。

## 17. 实施约束

- 不改变 R6.1 Outbound Message 字段、序号、终态或 `retryable`。
- 不改变 R6.4 Schema、恢复、`UNKNOWN` 或 Delivery Retry。
- 不引入 Spring Security 并不意味着省略安全 Filter；所有入口必须经过同一 Guard。
- 不把 `turnRef` 当认证凭证；Bearer 和 Loopback 门禁始终必须同时成立。
- 控制面观测允许降级，Agent 主链路不可因观测不可用而失败。
- 任一实现需要远程访问、Cookie、CORS、历史存储、新依赖/模块或 CLI+Web 时暂停并重新设计。
