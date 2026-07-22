# Loopback 控制面、安全状态、事件流与活动 Turn 取消契约

- 状态：已批准；后端已实现并完成本地离线验收
- 契约版本：1
- 日期：2026-07-17
- 阶段：R6.5
- 批准日期：2026-07-17
- 批准记录：用户明确批准 R6.5 G0，并授权从 G1 开始连续 TDD；远程访问、真实 Telegram、CLI+Web 和前端变更继续冻结
- 前置契约：[版本化渠道消息与流式运行时契约](versioned-channel-message-runtime.md)
- 前置契约：[Telegram Channel Host 契约](telegram-channel-host.md)
- 前置契约：[渠道可靠投递、幂等与恢复契约](channel-reliable-delivery.md)
- 关联 ADR：[ADR-0011：Loopback 控制面事件采用认证 SSE](../adr/0011-use-authenticated-sse-for-loopback-control-events.md)
- 关联设计：[Loopback 控制面设计](../specs/2026-07-17-loopback-control-plane-design.md)

> 本契约只定义默认关闭、单 JVM、仅 Loopback 的 Java 控制面后端。它不授权公网或局域网暴露、反向代理、TLS 终止、远程身份、RBAC、真实用户数据、数据库 Reconcile、自动重跑 Turn、修改 Tool 权限或迁移 Python Dashboard 数据接口。

## 1. 目的

R6.1–R6.4 已建立版本化消息、Provider Streaming、Telegram 渠道和持久可靠投递，但当前运维人员只能从安全日志和健康快照间接判断运行状态，也没有一个受控入口取消指定的活动 Turn。

本契约建立最小控制面，固定以下能力：

1. 通过认证的 Loopback HTTP 查询 Channel Host、可靠性和控制面安全快照。
2. 查询本 JVM 中已经显式注册且仍存活的活动 Turn，不暴露原始 Session、Route、Sender 或消息正文。
3. 通过独立有界 SSE 订阅未来的 R6.1 Outbound Message 投影。
4. 对一个活动 Turn 请求 `REQUESTED` 取消，并复用该 Turn 已有的取消源和 First Writer Wins 语义。
5. 控制面、认证、订阅或审计失败不得阻塞、反压或改变 Agent、Channel、Claim、Outbox 和会话提交主链路。

## 2. 需要批准的决策

1. 新增 `agent.control-plane.mode=DISABLED|LOOPBACK`，默认 `DISABLED`；只有 `LOOPBACK` 创建控制面组件和 HTTP 映射。
2. `LOOPBACK` 只允许服务绑定 `127.0.0.1` 或 `::1`，同时校验原始连接地址、`Host` 和可选 `Origin`；不信任 Forwarded Header。
3. 使用进程内、短生命周期、256-bit 随机 Bearer Token。Token 只在创建 Session 时返回一次，不写磁盘、不进日志、不进入 URL 或 Cookie。
4. 事件流使用 Spring MVC/Servlet SSE；浏览器必须用带 `Authorization` Header 的 Fetch Streaming，不使用不能设置 Header 的原生 `EventSource`。
5. 新订阅只收到安全的 `stream.opened` 快照和此后事件；V1 不保存或重放事件历史，带 `Last-Event-ID` 的请求稳定拒绝。
6. 每个订阅使用独立有界队列。慢消费者只断开自己的 SSE，不取消 Turn、不阻塞真实渠道，也不让 Agent 发布失败。
7. 控制面只接入 Servlet 模式中的 Telegram 普通路径和可靠路径。当前 `--cli` 明确不启动 HTTP Server；同步 `/api/v1/chat` 也不使用 R6.1 `InboundMessage`，两者均不在 V1 活动 Turn 范围。
8. R6.4 的 `EXECUTION_UNKNOWN` 是持久不确定记录，不是本 JVM 中可证明存活的执行；它只作为安全计数出现，永不进入活动列表、事件订阅或取消入口。
9. 终态只保留无正文的进程内短期 Tombstone，用于重复取消的确定结果；不增加数据库表，不修改 Channel Ledger。
10. 任何已认证的 V1 本地 Operator Session 都可查看安全状态并取消任一已注册活动 Turn。V1 不声称具备人类身份、角色或多租户授权。

## 3. 范围与非目标

### 3.1 V1 包含

- Java-owned、版本化 `control-plane/loopback-control-plane-v1.json` Contract Fixture。
- 进程内 Operator Session、Token 校验、到期和主动注销。
- 安全状态快照、活动 Turn 列表、SSE 事件订阅和目标取消 API。
- Telegram Volatile 与 SQLite Reliable 两条当前生产路径的活动 Turn 注册、事件 Tap 和取消接入。
- 有界 Session、活动 Turn、Tombstone、Subscriber、队列、心跳、流生命期和关闭预算。
- Loopback、Host、Origin、无 CORS、无 Cookie、无 URL Token 和安全审计测试。
- 默认 Disabled 零映射、零 Token、零 Registry、零 Subscriber 和零后台线程。

### 3.2 V1 不包含

- 远程 Dashboard、局域网/公网监听、反向代理、Forwarded Header、TLS、OAuth、OIDC、用户密码或 RBAC。
- Python `/api/dashboard/*` 的 Session 历史、消息删除、Proactive、Plugin 或管理接口兼容。
- 为 `--cli` 同时启动 Web Server，或让 Dashboard 管理 CLI Turn。
- 将同步 `/api/v1/chat` 改造成异步、流式或可取消入口。
- 对 `EXECUTION_UNKNOWN`、`UNKNOWN` Delivery、Claim、Outbox 或 Receipt 做人工修复。
- 查询 Prompt、入站正文、Memory、Tool Arguments/Result、Thinking、Secret、原始异常、SQL 或真实文件路径。
- 事件历史、断线续传、跨进程订阅、持久 Token、跨节点 Registry 或消息 Broker。
- 修改会话、配置、渠道 Allowlist、Tool Mode、审批结果或 Side Effect Ledger。
- 自动重跑 Agent Turn、重发 `UNKNOWN`、Exactly Once 声明或真实网络 Smoke。

## 4. 信任模型与启动门禁

### 4.1 模式

| 模式 | 行为 |
| --- | --- |
| `DISABLED` | 默认值；不创建 Session Store、Turn Registry、Event Hub 或控制器，`/api/v1/control/**` 没有映射 |
| `LOOPBACK` | 创建进程内控制面；启动前验证服务只绑定单个 Loopback 地址 |

模式值大小写严格，`loopback`、空值和未知值拒绝。`LOOPBACK` 配合 `0.0.0.0`、`::`、主机网卡地址或无法证明为 Loopback 的地址时必须启动失败，不能静默降级为 Disabled。`server.forward-headers-strategy` 必须保持 `NONE`；任何会让容器翻译 Forwarded Header 的策略都在创建 Runtime 前启动失败，不能把代理链当成本地来源。

V1 的 Loopback Session 只防止远端网络和普通跨站浏览器请求直接使用控制 API。它不能防御同一主机上已经取得当前用户权限的恶意进程；需要更强隔离时必须新增远程身份与权限 Contract。

### 4.2 每请求防御

即使 Server 已绑定 Loopback，每个控制请求仍必须满足：

1. Servlet 原始 `remoteAddr` 是 Loopback。
2. 原始 `Host` 只允许 `127.0.0.1[:port]`、`[::1][:port]` 或 `localhost[:port]`。
3. 若请求携带 `Origin`，它必须与原始 Scheme 和 Host 完全同源。
4. `X-Forwarded-For`、`Forwarded`、`X-Forwarded-Host` 和 `X-Forwarded-Proto` 不参与信任判断。
5. 不返回 `Access-Control-Allow-*`；跨域预检不开放控制 API。

拒绝发生在读取业务 Body 和调用用例之前。错误响应与成功响应均设置 `Cache-Control: no-store` 和 `X-Content-Type-Options: nosniff`。

## 5. Operator Session 与认证

### 5.1 创建和注销

```text
POST   /api/v1/control/session
DELETE /api/v1/control/session
```

- `POST` 不接受 Body；非空 Body 返回 `400 CONTROL_REQUEST_INVALID`。
- 创建请求必须先通过 Loopback、Host 和 Origin 门禁，但不携带既有 Token。
- 成功返回 `201`：

```json
{
  "schemaVersion": 1,
  "accessToken": "<base64url-without-padding>",
  "tokenType": "Bearer",
  "expiresAt": "2026-07-17T08:15:00Z"
}
```

- Token 由 `SecureRandom` 生成至少 256 bit 熵，只返回一次；服务端只保存固定长度摘要，并使用常量时间比较。
- Session 有不可由 Token 推导的随机 `actorRef`，只用于安全审计；API 不返回它。
- Session 使用硬到期时间，不因请求自动续期。到期、注销或进程关闭立即失效。
- Session 达到上限时先清理已到期项；仍满则返回 `429 CONTROL_SESSION_CAPACITY_EXCEEDED`。
- `DELETE` 需要当前有效 Bearer Token；首次注销返回 `204`。后续再使用该 Token 时与其他失效 Token 一样统一返回 `401`，不暴露内部注销状态。

### 5.2 认证规则

除创建 Session 外，所有控制 API 必须携带：

```text
Authorization: Bearer <accessToken>
```

缺失、格式错误、未知、到期和已注销统一返回 `401 CONTROL_AUTHENTICATION_REQUIRED`，不形成 Token Oracle。禁止：

- Query String、Path、Cookie、Local Storage 日志或异常中的 Token。
- Client 自定义取消理由、Actor、Role 或 Turn ID。
- 将 Token、摘要、Authorization Header 或完整 URI 写入普通日志和审计事件。

同源 Dashboard 应只把 Token 保存在页面内存；刷新页面后重新创建 Session。V1 不使用 Cookie，因此不存在环境凭证自动附带造成的传统 CSRF，但 Origin 门禁仍用于阻断跨站创建 Session。

## 6. 通用 HTTP 约定

- Base Path 固定为 `/api/v1/control`，JSON 使用 UTF-8，字段名区分大小写，未知枚举拒绝。
- 所有时间为 UTC ISO-8601 `Instant`；所有计数非负且有实现上限。
- `requestId` 由 Server 生成并通过 `X-Request-Id` 返回；调用者不能覆盖。
- `turnRef` 是每次进程注册时生成的至少 128-bit Base64URL 随机引用，不等于也不能反推出 Message `turnId`、Session、Route 或 Ledger ID。
- 不接受分页、过滤、排序、Reason 或其他 V1 未定义的 Query/Body 字段。
- JSON 错误固定为：

```json
{
  "schemaVersion": 1,
  "code": "CONTROL_AUTHENTICATION_REQUIRED",
  "retryable": false,
  "requestId": "<server-generated>"
}
```

`retryable` 只能由稳定错误码推导，调用者和 Adapter 不能自行修改。V1 控制错误默认不可重试；仅 `CONTROL_SNAPSHOT_UNAVAILABLE` 和 `CONTROL_SHUTTING_DOWN` 可由 Contract 标记为可重试。

## 7. 安全状态 API

```text
GET /api/v1/control/status
```

成功返回 `200`。V1 固定投影：

```json
{
  "schemaVersion": 1,
  "observedAt": "2026-07-17T08:00:00Z",
  "host": {
    "state": "RUNNING",
    "code": ""
  },
  "control": {
    "state": "READY",
    "code": "",
    "activeTurns": 1,
    "recentTerminalTombstones": 0,
    "eventSubscribers": 1,
    "maxSubscriberQueueDepth": 0,
    "subscriberBufferCapacity": 64,
    "slowConsumerDisconnects": 0
  },
  "channels": [
    {
      "name": "telegram",
      "state": "RUNNING",
      "code": "",
      "activeTurns": 1,
      "consecutiveFailures": 0,
      "reliability": {
        "mode": "SQLITE",
        "ledgerState": "READY",
        "pendingDeliveries": 0,
        "unknownExecutions": 0,
        "unknownDeliveries": 0,
        "code": ""
      }
    }
  ]
}
```

规则：

- Channel 按 `name` 排序；快照复用 `ChannelHost` 的安全隔离，不让一个 Adapter 异常使整个 Agent 失败。
- `unknownExecutions` 只是 R6.4 计数，不生成 `turnRef`。
- `maxSubscriberQueueDepth` 是当前所有控制订阅队列的最大深度，不暴露 Subscriber ID。
- Registry 或单个快照不可用时返回安全 `DEGRADED` 和稳定码；查询失败不取消 Turn、不停止 Channel。
- 响应不得加入 Session/Route/Sender/消息、模型、Memory、Tool、Secret、路径、SQL、线程名、异常文本或 Token。

## 8. 活动 Turn API

```text
GET /api/v1/control/turns
```

成功返回 `200`：

```json
{
  "schemaVersion": 1,
  "observedAt": "2026-07-17T08:00:00Z",
  "items": [
    {
      "turnRef": "<opaque-random-reference>",
      "channel": "telegram",
      "state": "ACTIVE",
      "startedAt": "2026-07-17T07:59:59Z",
      "lastSequence": 3,
      "subscriberCount": 1
    }
  ]
}
```

- 只列 `ACTIVE` 或 `CANCELLATION_REQUESTED` 的当前进程注册项。
- 在首个 Outbound Message 被主 Sink 接受前，`lastSequence` 为 `null`；之后只能单调增加。
- 排序固定为 `startedAt`、`turnRef` 升序。
- 终态 Tombstone、CLI、同步 HTTP、Ledger Claim/Delivery 和 `EXECUTION_UNKNOWN` 不列出。
- `turnRef` 只在认证控制面内有效，进程重启或 Tombstone 到期后不可恢复。

## 9. SSE 事件流

```text
GET /api/v1/control/turns/{turnRef}/events
Accept: text/event-stream
Authorization: Bearer <accessToken>
```

### 9.1 建立流

- 未知或已过期引用返回 `404 CONTROL_TURN_NOT_FOUND`。
- 已终态但 Tombstone 仍存在时返回 `409 CONTROL_TURN_ALREADY_TERMINAL`，不重放终态正文。
- 携带 `Last-Event-ID` 时返回 `409 CONTROL_EVENT_REPLAY_UNAVAILABLE`。
- 达到全局 Subscriber 上限时返回 `429 CONTROL_SUBSCRIBER_CAPACITY_EXCEEDED`。
- 成功使用 `Content-Type: text/event-stream`、`Cache-Control: no-store`，并先发送：

```text
id: 0
event: control.stream.opened.v1
data: {"schemaVersion":1,"turnRef":"...","state":"ACTIVE","lastSequence":3,"replaySupported":false,"subscribedAt":"2026-07-17T08:00:00Z"}

```

该事件是当前安全快照，不代表补发了 `TURN_STARTED` 或历史 Delta。

### 9.2 Message 投影

主 Sink 成功接受一个 R6.1 `OutboundMessage` 后，控制面才可非阻塞投影：

```text
id: 1
event: control.turn.message.v1
data: {"schemaVersion":1,"turnRef":"...","sequence":4,"type":"CONTENT_DELTA","content":"...","code":"","retryable":false}

```

投影删除原始 `turnId`、`sessionId` 和 `route`。字段规则继续由 R6.1 决定：

- `TURN_STARTED`：`sequence=0`，正文和 Code 为空。
- `CONTENT_DELTA`：允许 Assistant 可见 Delta；不含 Thinking、Tool 或 Memory 数据。
- `TURN_COMPLETED`：允许权威完整 Assistant 文本。
- `TURN_CANCELLED`：正文为空，Code 为已有稳定取消码。
- `TURN_FAILED`：正文为空，Code 与 `retryable` 必须由已有失败枚举一致派生。

控制面不能生成、改写、截断或补齐 Outbound Message。主 Sink 拒绝的消息不能先被 Dashboard 看见；Observer 投影失败也不能让主 Sink 失败。

### 9.3 顺序、心跳与关闭

- SSE `id` 是每个订阅从 `0` 开始的独立连续序号，不等于 R6.1 `sequence`。
- Message 内的 `sequence` 保持原 Turn 顺序；新订阅可能从任意更大序号开始。
- 心跳只发送 `: keepalive` Comment，不携带状态或正文，也不延长最大流生命期。
- 收到 Terminal Message 后发送该消息并正常关闭流。Terminal 会立即停止该订阅继续 Fan-out，但全局 Subscriber 容量直到 Servlet 请求的 `finally` 关闭 Subscription 后才释放。
- 达到最大生命期时可发送 `control.stream.closed.v1`、Code `CONTROL_STREAM_LIFETIME_EXCEEDED` 后关闭；这不取消 Turn。
- 应用关闭、Session 到期/注销或客户端断开时释放 Subscription；控制面关闭本身不改变 Turn 取消原因。

### 9.4 慢消费者

每个 Subscriber 都有独立固定容量队列。`offer` 不等待：

- 队列已满时，原子移除并关闭该 Subscriber，审计 `CONTROL_SLOW_CONSUMER`；它停止接收 Fan-out，但在对应 Servlet 请求真正结束前仍占用全局 Subscriber 容量。
- 不丢旧事件后继续伪装连续流，不把慢消费者转成 Agent `BACKPRESSURE_EXCEEDED`。
- 不阻塞 Publisher，不关闭其他 Subscriber，不取消 Turn，不修改 Channel Buffer。
- 因连接已经建立，慢消费者不保证收到最终错误事件；客户端以 EOF 为准并重新查询安全状态。

## 10. 活动 Turn 取消

```text
POST /api/v1/control/turns/{turnRef}/cancel
```

请求不接受 Body 或 Query；控制面总是请求已有 `TurnCancellationCode.REQUESTED`。成功和幂等结果：

| HTTP | `result` | 含义 |
| --- | --- | --- |
| `202` | `CANCELLATION_REQUESTED` | 本请求首次赢得已有取消源的 First Writer Wins |
| `200` | `ALREADY_REQUESTED` | 同一 Turn 已由任一受信入口请求 `REQUESTED` |
| `200` | `ALREADY_CANCELLED` | Channel Disconnect、Backpressure 或 Shutdown 已先赢得取消原因 |
| `200` | `ALREADY_TERMINAL` | 当前 Registry 已结束且 Tombstone 尚在 |
| `404` | `CONTROL_TURN_NOT_FOUND` | 引用未知、进程已重启或 Tombstone 已到期 |

成功 Body：

```json
{
  "schemaVersion": 1,
  "turnRef": "<opaque-random-reference>",
  "result": "CANCELLATION_REQUESTED",
  "state": "CANCELLATION_REQUESTED"
}
```

固定规则：

1. Registry 只调用该 Turn 已有的 `BoundedOutboundBuffer.requestCancellation()` 或 `TurnCancellationSource.cancel(REQUESTED)`；不得创建竞争取消源。
2. First Writer Wins 仍由已有取消源权威决定。控制面不能把 `CHANNEL_DISCONNECTED`、`BACKPRESSURE_EXCEEDED` 或 `SHUTDOWN` 覆盖成 `REQUESTED`。
3. 取消只是协作请求；`202` 不承诺 Provider/Tool 已立即停止，也不承诺产生 `TURN_CANCELLED`，最终状态以 Message Runtime 为准。
4. Registry 和终态在竞态下只允许返回上表中的一个真实结果；不得对已终态对象重新进入 Application。
5. 取消不触碰 Ledger Claim、Outbox、Delivery、Receipt、Session 数据、Tool Approval 或 Side Effect Ledger。

## 11. Registry、Tombstone 与生命周期

- 只在 Telegram Turn 即将调用 `MessageTurnService` 时注册；注册失败或容量饱和不阻止该 Turn，控制面进入 `DEGRADED/CONTROL_TURN_REGISTRY_SATURATED`。
- 生产调用仍只发生一次。Registry 保存安全 `turnRef`、Channel、开始时间、最后序号、状态、Subscriber 计数和现有取消 Handle，不复制 Inbound 正文。
- 主 Sink 接受 Terminal 后立即从活动集合移除并写入无正文 Tombstone；异常退出且没有 Terminal 时写入 `SOURCE_ENDED` Tombstone 并关闭订阅，不伪造 Message Terminal。
- Tombstone 只含 `turnRef`、终态类别、稳定码和到期时间；达到数量上限按最早到期顺序清理。
- 进程关闭由一个 Coordinator 先停止创建 Session/Subscriber，再关闭 Session 与 Runtime、唤醒流，并在一个共享 `shutdown-timeout` Deadline 内等待所有已进入 Servlet 的流退出，最后清空 Token/Registry/Tombstone；不能为每条流重新获得完整超时。Channel Host 自己负责以已有 `SHUTDOWN` 语义停止 Turn。
- 超时不取消 Turn，也不无限阻止 JVM 退出；只产生一次 `CONTROL_SHUTDOWN_TIMEOUT` 安全审计，记录剩余流数和实际有界等待时间。
- Session 和 Tombstone 清理在有界请求/注册操作中惰性执行，不新增无限后台扫描线程。

## 12. 配置上限

| 配置 | 默认值 | 硬边界 |
| --- | ---: | ---: |
| `agent.control-plane.mode` | `DISABLED` | `DISABLED|LOOPBACK` |
| `session-ttl` | `15m` | `1m..1h` |
| `max-sessions` | `4` | `1..16` |
| `max-active-turns` | `128` | `1..1024` |
| `terminal-retention` | `5m` | `1m..30m` |
| `max-terminal-tombstones` | `1024` | `16..4096` |
| `max-subscribers` | `8` | `1..32` |
| `subscriber-buffer-capacity` | `64` | `1..256` |
| `heartbeat-interval` | `15s` | `5s..60s` |
| `stream-max-lifetime` | `15m` | `1m..1h` |
| `shutdown-timeout` | `2s` | `100ms..10s` |

所有值在构造线程、Session 或 HTTP 映射前严格校验。配置不接受 Token、用户名、密码、任意 Origin Allowlist 或远程地址。

## 13. 安全审计

创建/拒绝 Session、认证失败、查询失败、订阅建立/关闭、慢消费者、取消和关闭超时形成稳定审计事件，字段只允许：

- Server 时间、Server Request ID。
- `action`、`result`、稳定 Code。
- 随机 `actorRef` 的域隔离 Hash。
- `turnRef` 的域隔离 Hash（仅 Turn 操作）。
- 有界计数和耗时。

禁止记录 Authorization、Token/摘要、原始 `turnRef`、Session/Route/Sender、正文、Memory、Tool、Secret、路径、Query、Header 全量或异常 Message。默认 Sink 只以结构化安全字段写本地日志，不是静默 No-op；审计 Sink 失败必须隔离，不能改变 HTTP 结果或 Agent 主链路。

## 14. 版本化 Fixture

V1 Fixture 固定 48 个 Case。简单值与投影由生产 Validator/Factory/Projection 直接消费；需要 HTTP、并发、SQLite、阻塞 Writer 或 Spring 销毁顺序的 Case 必须显式声明唯一聚焦测试所有者，并校验所有者源码和方法实际存在：

| 分组 | Case 数 | 固定语义 |
| --- | ---: | --- |
| Mode/Loopback/Origin | 8 | 默认 Disabled、合法绑定、Wildcard/Remote/Host/Origin/Forwarded/CORS 拒绝 |
| Session/Auth | 8 | 创建、摘要、常量时间匹配、到期、注销、容量、统一 401、安全投影 |
| Status/Turn Snapshot | 8 | 排序、稳定字段、快照失败隔离、未知计数、Registry 饱和和敏感字段缺席 |
| Cancellation | 10 | 首次、重复、其他原因先赢、终态、过期、并发、目标隔离和无 Ledger 变更 |
| SSE/Projection | 10 | opened、未来事件、顺序、终态、无重放、慢消费者、断开、生命期和敏感字段缺席 |
| Shutdown/Failure Isolation | 4 | Session/流清理、Observer/Audit 失败隔离、关闭预算和默认零线程 |

Fixture 不使用 Python，不访问网络，不生成真实 Token，不依赖墙钟 Sleep。随机数、Clock、Thread/Stream Writer 和故障点必须可注入。Golden 只负责逐 Case Contract 追踪，不复制聚焦测试的 HTTP/并发故障矩阵。

## 15. 兼容与变更规则

- V1 字段和枚举大小写严格。新增必填字段、改变错误码/HTTP 状态、允许重放、扩大身份/网络范围或改变取消语义均需要 Contract V2。
- 可在不改变语义时新增服务端内部指标，但不能把它们静默加入 V1 JSON。
- Python Dashboard API 不是本契约的兼容源；只可复用后续批准的视觉资产，不继承其历史查询、删除或主动任务权限。
- 需要原生 `EventSource`、Cookie、远程代理、多用户或跨进程恢复时必须新增 ADR 和安全评审。

## 16. 验收与暂停条件

实现完成至少证明：

1. Disabled 启动无控制映射、Token、Registry、Subscriber、后台线程和新磁盘文件。
2. 非 Loopback 绑定、远端地址、恶意 Host、跨 Origin、缺失/失效 Token 均无法进入用例。
3. 状态和活动列表不含敏感数据，单个快照失败不影响 Agent。
4. Telegram 普通/可靠 Turn 都能投影未来事件并通过同一取消源取消目标 Turn。
5. 慢/断开 Dashboard 不反压 Agent、渠道或其他 Subscriber。
6. 重复/竞态取消、Terminal、Shutdown 和 Session 到期结果确定且有并发测试。
7. `EXECUTION_UNKNOWN` 只显示计数，不能订阅或取消；Ledger 数据完全不变。
8. 默认、`failure`、`compat`、格式、依赖、Secret、线程/订阅泄漏和工作树门禁全部通过。

遇到以下情况立即暂停并重新批准：远程访问、Cookie/CORS、CLI+Web 混合宿主、新数据库/模块/依赖、历史重放、Ledger Reconcile、Python Dashboard 写接口、真实 Token/用户数据、修改 Message Contract 或 Side Effect/Approval 语义。
