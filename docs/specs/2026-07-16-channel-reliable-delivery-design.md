# 渠道可靠投递、幂等与恢复设计

- 状态：已批准，待实施
- 日期：2026-07-16
- 阶段：R6.4
- 批准记录：用户于 2026-07-16 明确批准本设计，并授权从 F1 开始连续 TDD
- Contract：[渠道可靠投递、幂等与恢复契约](../contracts/channel-reliable-delivery.md)
- ADR：[ADR-0010：使用独立 Java SQLite 渠道账本与事务 Outbox](../adr/0010-use-dedicated-sqlite-channel-ledger.md)
- 实施计划：[R6.4 渠道可靠投递工作计划](../plans/2026-07-16-channel-reliable-delivery-implementation.md)

## 1. 设计目标

在不改变 R6.1 Message Contract、R6.2 Provider/CLI、R6.3 Telegram 身份规则和现有会话提交语义的前提下，为 Telegram 增加一个显式启用的持久可靠性层：

```text
Telegram Update
      |
      v
Durable Inbox Event + Turn Claim ----> persistent next_offset
      |
      v
MessageTurnService（最多跨越一次执行边界）
      |
      v
Durable Terminal Sink
      |
      v
Delivery + ordered Parts + Attempt Audit
      |
      v
Bounded Delivery Worker ----> Telegram sendMessage Receipt
```

设计优先级是防止不可控重复，而不是自动恢复率。凡是不能证明“此前没有执行/没有发送”的窗口，都转入稳定未知状态并停止自动动作。

## 2. 模块与依赖

```text
agent-kernel
  channel/reliability/
    ChannelInstanceId
    InboundFingerprint / DeliveryFingerprint
    InboxEvent / TurnClaim
    ChannelDelivery / DeliveryPart / DeliveryAttempt
    状态与稳定错误枚举
  port/
    ChannelLedgerPort

agent-application
  ReliableInboundCoordinator
  DurableTerminalCoordinator
  ChannelDeliveryCoordinator
  ChannelRecoveryService
  ChannelDeliveryTransport

adapter-sqlite
  ChannelLedgerSchemaV1
  ChannelLedgerSchemaInitializer
  JdbcChannelLedger
  ChannelLedgerBackup

agent-bootstrap
  channel/reliability/
    ChannelReliabilityProperties
    ChannelReliabilityRuntime
  telegram/
    TelegramChannelInstance
    ReliableTelegramOutboundSink
    TelegramDeliveryTransport
    TelegramDeliveryWorker
    TelegramChannelAdapter（接入持久 offset/claim）
```

约束：

- Kernel 不引用 JDBC、SQLite、Spring、Telegram、HTTP 或 Jackson 生产类型。
- Application 只依赖 Kernel Port 和项目值，不解析 Telegram JSON。
- SQLite Adapter 不引用 Telegram；它保存通用渠道值。
- Telegram Bootstrap 不执行 SQL，只调用 Application Service。
- 不新增 Maven 模块，不让 `adapter-sqlite` 依赖 `agent-application`。
- R6.3 的 Volatile 路径保留；Reliability Disabled 时不构造上述运行时对象。

类名可在不改变模块、职责和 Contract 的前提下做局部调整；端口语义、状态与数据字段不能静默变化。

## 3. Kernel 协议

### 3.1 `ChannelLedgerPort`

Port 按用例暴露原子操作，不向上暴露 Connection、SQL 或宽泛 `save(Object)`：

```java
interface ChannelLedgerPort {
  InboundDecision recordEvent(InboundEventCommand command);
  TurnStartResult startReservedTurn(TurnStartCommand command);
  TerminalRecordResult recordTerminal(TerminalRecordCommand command);
  DeliveryClaim claimNextDelivery(DeliveryClaimCommand command);
  DeliveryUpdate recordDeliveryOutcome(DeliveryOutcomeCommand command);
  RecoveryBatch recover(RecoveryCommand command);
  CleanupBatch cleanup(CleanupCommand command);
  ChannelLedgerSnapshot snapshot(ChannelInstanceId instance);
}
```

每个 Command：

- 使用强类型 ID/枚举和注入的 `Instant`。
- 携带调用者读取的 Revision/Owner 时必须在 SQL 中 CAS。
- 不接受调用者提供的指纹文本；由 Kernel Factory 根据原始项目值计算。
- 有明确最大长度/数量，并在到达 JDBC 前验证。

查询/结果不包含 Payload，除非该操作确实需要发送下一 Part。普通状态快照只含计数和稳定码。

### 3.2 稳定结果

入站：

- `RESERVED_NEW`
- `START_RETRYABLE`
- `IN_PROGRESS`
- `ALREADY_TERMINAL`
- `EXECUTION_UNKNOWN`
- `IGNORED_RECORDED`
- `CONTROL_RECORDED`
- `FEEDBACK_QUEUED`

投递：

- `CLAIMED`
- `NOT_DUE`
- `EMPTY`
- `DELIVERED`
- `RETRY_SCHEDULED`
- `FAILED`
- `UNKNOWN`

冲突、Schema、容量和数据库不可用使用稳定项目异常，异常 Message 不含 ID、正文、路径或 SQL。

## 4. SQLite 物理设计

### 4.1 文件与连接

- 路径：`workspace.resolve("channels").resolve("channel-ledger.db")`。
- 文件名必须精确为 `channel-ledger.db`，防止误把初始化器指向会话库。
- 每次连接设置 `busy_timeout=5000`、`foreign_keys=ON`、`synchronous=FULL`。
- Fresh DB 可以直接设置 WAL；V0 必须先完成并验证 Backup，再设置 WAL；既有 V1 只验证已经是 WAL，不静默切换。
- 初始化先运行 `PRAGMA quick_check(1)`。
- 写操作使用短 `BEGIN IMMEDIATE`；网络、Sleep、线程 Join 和大文本计算均在事务外。

### 4.2 V1 表

`channel_schema`：

- `singleton INTEGER PRIMARY KEY CHECK(singleton=1)`
- `version INTEGER NOT NULL CHECK(version>=0)`
- `updated_at TEXT NOT NULL`

`channel_cursors`：

- `channel TEXT NOT NULL`
- `instance_id TEXT NOT NULL`
- `next_sequence INTEGER NOT NULL CHECK(next_sequence>=0)`
- `revision INTEGER NOT NULL CHECK(revision>=0)`
- `updated_at TEXT NOT NULL`
- Primary Key：`channel, instance_id`

`channel_inbox_events`：

- `channel, instance_id, external_event_id`
- `external_sequence INTEGER NOT NULL CHECK(external_sequence>=0)`
- `event_fingerprint TEXT NOT NULL`
- `decision TEXT NOT NULL`
- `turn_id TEXT NULL`
- `created_at, updated_at TEXT NOT NULL`
- Primary Key：`channel, instance_id, external_event_id`
- Unique：`channel, instance_id, external_sequence`

`channel_turn_claims`：

- `channel, instance_id, external_message_id`
- `request_fingerprint TEXT NOT NULL`
- `turn_id TEXT NOT NULL UNIQUE`
- `state TEXT NOT NULL`
- `start_attempts INTEGER NOT NULL CHECK(start_attempts BETWEEN 0 AND 3)`
- `owner_id, lease_expires_at TEXT NULL`
- `revision INTEGER NOT NULL CHECK(revision>=0)`
- `created_at, updated_at TEXT NOT NULL`
- Primary Key：`channel, instance_id, external_message_id`

`channel_deliveries`：

- `delivery_id TEXT PRIMARY KEY`
- `channel, instance_id, target_id TEXT NOT NULL`
- `source_kind, correlation_id, message_type TEXT NOT NULL`
- `payload_fingerprint TEXT NOT NULL`
- `state TEXT NOT NULL`
- `part_count INTEGER NOT NULL CHECK(part_count BETWEEN 1 AND 16)`
- `next_part_index INTEGER NOT NULL CHECK(next_part_index>=0)`
- `payload_pruned INTEGER NOT NULL CHECK(payload_pruned IN (0, 1))`
- `owner_id, lease_expires_at TEXT NULL`
- `last_error_code TEXT NULL`
- `revision INTEGER NOT NULL CHECK(revision>=0)`
- `created_at, updated_at TEXT NOT NULL`
- Unique：`channel, instance_id, source_kind, correlation_id`

`channel_delivery_parts`：

- `delivery_id TEXT NOT NULL`
- `part_index INTEGER NOT NULL CHECK(part_index>=0)`
- `payload_text TEXT NOT NULL`
- `payload_fingerprint TEXT NOT NULL`
- `state TEXT NOT NULL`
- `attempt_count INTEGER NOT NULL CHECK(attempt_count BETWEEN 0 AND 2)`
- `next_attempt_at TEXT NULL`
- `remote_message_id TEXT NULL`
- `last_error_code TEXT NULL`
- `updated_at TEXT NOT NULL`
- Primary Key：`delivery_id, part_index`
- Foreign Key：Delivery，删除已解决 Delivery 时级联

`channel_delivery_attempts`：

- `delivery_id, part_index, attempt_number`
- `started_at TEXT NOT NULL`
- `completed_at TEXT NULL`
- `outcome TEXT NOT NULL`
- `remote_message_id, error_code TEXT NULL`
- Primary Key：`delivery_id, part_index, attempt_number`
- Foreign Key：Part，删除已解决 Part 时级联

V1 Validator 还必须验证预期索引、Foreign Key Action、列顺序/类型/Nullability/Default、无未知用户表、无 View、无 Trigger。具体 SQL 由生产 `ChannelLedgerSchemaV1` 和 Fixture 同时锁定，不能只测试“表存在”。

### 4.3 Migration

- Empty：单事务创建 V1。
- V0：只允许精确 Header；在 Journal Mode 或 Schema/Data 写入前执行 SQLite Online Backup，验证 Backup 文件与 `quick_check`，再设置 WAL 并单事务迁移。
- V1：严格验证后返回，不写 `updated_at`，保证重复初始化无观察变化。
- Future/negative/unknown：稳定 `SCHEMA_INCOMPATIBLE`。
- Backup/Migration 失败：Rollback，保持源版本；删除不完整 Backup。

测试 Backup 使用注入 Clock/UUID/Backup Port，并在临时目录把 Backup 重新打开验证，而不是只检查文件长度。

## 5. Telegram 入站数据流

### 5.1 Poll 前恢复

Reliability Runtime 在 Telegram 发起第一次网络请求前：

1. 初始化并严格验证 Schema。
2. 生成本进程 Owner ID。
3. 按当前 Telegram Instance 运行恢复批次，直到没有需要立即归类的旧 Owner `RESERVED/RUNNING/IN_FLIGHT`。
4. 读取持久 `next_sequence`；没有游标时使用 0。
5. 启动 Delivery Worker，再进入 Long Poll。

任一步数据库失败都使 Telegram 状态为 `FAILED/CHANNEL_LEDGER_UNAVAILABLE`，不调用 `getUpdates`。ChannelHost 捕获该 Adapter 故障；普通 HTTP/CLI Bean 继续存在。

### 5.2 Update 处理

每个 Poll 响应先验证并按 ID 升序。Mapper 仍是唯一 Telegram -> `InboundMessage` 身份边界。

Ignore：

- 计算不含正文的 Event Fingerprint。
- 原子插入/验证 Event 并推进 Cursor。
- 不创建 Claim/Delivery。

Control：

- 记录 Event。
- 有活动 Turn 时在 Commit 后调用现有定向 Cancellation。
- 无活动 Turn 时在同一事务创建固定 Feedback Delivery。
- 推进 Cursor；重启不重放 Cancellation 命令。

Accepted：

1. Runtime 计算 Event/Request Fingerprint。
2. `recordEvent` 插入 Event 和 `RESERVED` Claim，或返回现有状态。
3. 新/可重试 Claim 使用原 Turn ID 建立现有活动会话/全局许可。
4. Worker 真正运行前调用 `startReservedTurn`；该事务 CAS 为 `RUNNING` 并推进 Cursor。
5. 只有成功结果调用 `MessageTurnService.process`。
6. Thread Starter 明确失败时 Claim -> `START_RETRYABLE`，不推进 Cursor。

Busy：

- 若在 Claim 前即可证明容量不足，不创建 Turn Claim。
- Event、固定 Busy Delivery 和 Cursor 同事务提交。
- 若 Claim 已建立后发生不应出现的调度冲突，则 Fail Closed，不把同一消息降级为 Busy。

### 5.3 进程崩溃矩阵

| 崩溃点 | 持久状态 | 重启行为 |
| --- | --- | --- |
| Event/Claim Commit 前 | 无 | Telegram 重投并正常处理 |
| `RESERVED` 后、`RUNNING` 前 | `RESERVED`，Cursor 未进 | 恢复为 `START_RETRYABLE`，同 Turn ID 可再启动 |
| `RUNNING + Cursor` Commit 前 | `RESERVED` | 生产代码尚不得调用 Agent，可安全重投 |
| `RUNNING + Cursor` Commit 后、Agent 前 | `RUNNING` | `EXECUTION_UNKNOWN`，不自动重跑 |
| Agent/Tool/Commit 中 | `RUNNING` | `EXECUTION_UNKNOWN` |
| Terminal Outbox Commit 后 | `TERMINAL_RECORDED` | 只恢复 Delivery |

“Commit 后、Agent 前”宁可进入未知也不自动重跑，因为跨进程无法证明线程没有越过调用点。

## 6. Durable Terminal Sink

`ReliableTelegramOutboundSink` 包装现有 `BoundedOutboundBuffer`：

- Started/Delta：仍发布到有界 Buffer，保持顺序、取消和背压测试；不写 Ledger。
- Terminal：先用独立 Validator 验证，投影文本/分片，然后调用 `DurableTerminalCoordinator.record`。
- Ledger Commit 成功后，向 Buffer 发布终态只用于完成本地消费者生命周期；即使该通知因关闭失败，持久 Delivery 仍是权威恢复来源。
- 终态 Commit 失败时抛稳定 `OutboundDeliveryException`，不尝试 Telegram 网络。

为避免“已经持久终态却因通知失败又把 Claim 当成执行未知”，Terminal Commit 结果必须优先于 Buffer Wake 结果。关闭流程直接唤醒 Delivery Worker 扫描，不依赖 Buffer 中仍有终态。

相同终态被第二次传入：

- 指纹完全匹配：返回已有 Delivery ID，不增加 Part/Attempt。
- 任何字段不同：`TERMINAL_CONFLICT`，不发送。

## 7. Delivery Worker

每个启用可靠性的 Telegram Adapter 最多一个 Delivery Worker，不创建无界 Executor/Queue。

循环：

1. 在有界批次内领取当前 Instance 最早的 Due Delivery。
2. 事务内 Header -> `DELIVERING`、Part -> `IN_FLIGHT`、Attempt -> `STARTED`。
3. 事务外调用 `ChannelDeliveryTransport.send`。
4. 把安全结果提交给 `ChannelDeliveryCoordinator`。
5. 成功则保存远端 ID、推进 Part 游标；还有 Part 时立即进入下一轮。
6. 429 则保存 `RETRY_WAIT` 和绝对 Due Time，释放 Owner。
7. Permanent/Unknown 终止 Delivery。

领取顺序固定 `next_attempt_at/created_at/delivery_id`，但不承诺不同 Conversation 间的用户可见全局顺序。一个未知/失败 Delivery 不阻塞其他 Delivery。

Worker Wait 由 Condition/可控 Sleeper 驱动：

- 新 Outbox Commit 后 Signal。
- 最近 Due Time 提供最大等待。
- Shutdown 中断等待并有界 Join。
- 不使用无限轮询或墙钟 Busy Loop。

## 8. Telegram 发送适配

R6.4 将 `TelegramBotApi.sendMessage` 改为返回 `TelegramSendReceipt`。JDK Client 必须解析响应：

- `result` 是 Object。
- `message_id` 是正的 64-bit Integer。
- 不把整个 Message DTO 带回项目。

`TelegramDeliveryTransport` 把 API 结果投影为 Application 分类：

- `CONFIRMED(remoteMessageId)`
- `REJECTED_RETRYABLE(retryAfter, RATE_LIMITED)`
- `REJECTED_PERMANENT(code)`
- `OUTCOME_UNKNOWN(code)`

为了正确区分 HTTP 4xx 与损坏成功响应，R6.3 的单一 `INVALID_RESPONSE` 不能继续直接驱动投递重试。Client 可以新增仅包内的请求结果分类，但所有异常仍只能携带稳定 Code/RetryAfter，不能保留 Cause、URI、Body 或 description。

Poll 的已有安全重试保持不变；Send 的旧内存 `TelegramDeliveryPolicy` 被持久 Coordinator 取代，不能形成双重重试。

## 9. 恢复与清理

### 9.1 恢复

恢复只处理当前 Instance，并按主键稳定分页：

- Claim `RESERVED` 且 Owner 不同 -> `START_RETRYABLE`。
- Claim `RUNNING` 且 Owner 不同 -> `EXECUTION_UNKNOWN`。
- Delivery/Part `IN_FLIGHT` 或 Attempt `STARTED` 且 Owner 不同 -> `UNKNOWN`。
- `PENDING` 保持；到期 `RETRY_WAIT` 可领取。
- `DELIVERED/FAILED/UNKNOWN` 不变化。

恢复写入稳定 `RECOVERED_AFTER_RESTART` Code，不保存旧 Owner。处理超过单批时继续下一批，但每批释放事务；恢复未完成前不 Poll 新 Update。

### 9.2 清理

清理只处理已解决且超过 Cutoff 的数据：

- Delivery `DELIVERED`：在同一事务删除 Attempt/Part、置 `payload_pruned=1`，保留最小 Header Tombstone 和指纹。
- Delivery `FAILED`：超过保留期后可用相同事务收缩，但保留稳定失败与指纹。
- Event Ignore/Control：低于 Cursor 且过期可删。
- Turn Claim `TERMINAL_RECORDED`：保留去重 Tombstone；不保留入站正文（本来就未保存）。
- `EXECUTION_UNKNOWN`、Delivery `UNKNOWN`：不自动删除/收缩关键状态。

达到配置上限前先运行一批清理；仍超限则拒绝新事件。容量错误不得删除最旧未解决记录。

## 10. 配置与装配

`ChannelReliabilityProperties` 绑定 `agent.channels.reliability` 并验证 Contract 的所有范围。

Disabled：

- 不创建 Schema Initializer/JDBC Ledger/Recovery/Delivery Worker。
- 不创建 `workspace/channels`。
- Telegram 使用原 `TelegramChannelAdapter` Volatile 构造路径。

SQLite：

- Spring 只构造惰性 Runtime；数据库初始化发生在 Adapter `start()`，使 ChannelHost 可以隔离失败。
- 当前仅 Telegram 注入 Runtime；未来渠道需要独立 Contract。
- Telegram 关闭时不读取 Token，因此无法计算实例 ID，也不得为未启用渠道初始化数据库。
- Telegram 启用且 SQLite 时，从已校验 Token 派生实例 ID，然后启动 Ledger。

`application.yml` 和 `.env.example` 只增加非 Secret 默认值；生产模板仍同时保持 Telegram 和 Reliability Disabled。

## 11. 生命周期与健康

启动顺序：

```text
validate config/token
  -> initialize/validate ledger
  -> recover instance
  -> start delivery worker
  -> start poll worker
```

关闭顺序：

```text
stop new poll
  -> interrupt/join poll
  -> cancel active turns
  -> durable terminal grace
  -> stop/join delivery worker
  -> release runtime resources
```

若 Grace 内 active Turn 未能持久终态，Claim 在下次启动变为 `EXECUTION_UNKNOWN`。关闭不能把 `IN_FLIGHT` 重新标记 Pending。

健康快照新增稳定维度：

- `reliabilityMode`
- `ledgerState`
- `pendingDeliveries`
- `unknownExecutions`
- `unknownDeliveries`
- `lastStableErrorCode`

计数设硬上限并允许“至少 N”投影；不暴露实例、目标、Turn、Delivery、路径或正文。

## 12. 安全与可观察性

- 普通日志只记录渠道名、状态转换、饱和计数和稳定错误码。
- SQL 异常不直接传播；不记录 SQL 参数。
- Receipt 只存本地账本，不写日志。
- Fingerprint 使用项目自有长度前缀编码；禁止字符串简单拼接。
- 测试 Fixture 使用固定虚构 ID/正文，不从真实数据库生成。
- Secret/Workspace 扫描扩展到 `channel-ledger.db`、Backup、WAL/SHM 和 Token 前缀误输出。
- 测试生成的 `.db/.db-wal/.db-shm/.bak` 必须位于临时目录并在结束时清理。

## 13. 验收场景

至少证明：

1. 两线程同时处理同 Event/Message，只有一个 Claim 与一次 Agent 调用。
2. 同 ID 不同正文/Route 拒绝，Cursor 不跳过冲突。
3. Thread 启动失败可复用原 Turn ID；`RUNNING` 崩溃不能重跑。
4. Terminal 持久化失败不触网；成功后即使 Wake 丢失也能由扫描发送。
5. 成功发送后 DB Commit 失败恢复为 `UNKNOWN`，调用次数仍为一。
6. 429 在持久 Due Time 后用相同 Part 重试一次；Timeout/5xx/损坏响应零重试。
7. 第二片未知时第一片不重发、第三片不发送。
8. 不同 Bot Instance 的 Cursor、Claim 和 Delivery 完全隔离。
9. V0 Backup 可恢复；未知/Future Schema 零写入。
10. Ledger 损坏/满载只关闭 Telegram 可靠路径，不影响 HTTP/CLI。
11. Disabled 路径零目录、DB、Worker、Token Read 和网络。
12. 重启恢复、清理和 Shutdown 无线程、许可、Connection 或 Socket 泄漏。

## 14. 明确不解决的问题

- 用户如何查看或裁决 `UNKNOWN`。
- 如何查询 Telegram 是否已经收到某条未知发送。
- 如何把失败后的已提交 Assistant 回答重新展示给用户。
- 多实例 Bot 消费和跨主机恢复。
- 加密数据库/字段、外部密钥管理和远程备份。
- 真实 Telegram Smoke 与部署灰度。

这些问题不能通过在 V1 中偷偷重试、直接改 SQL 或保存更多敏感输入来绕过。
