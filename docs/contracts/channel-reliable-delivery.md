# 渠道可靠投递、幂等与恢复契约

- 状态：离线实现已完成并通过本地门禁；远程 CI 与真实 Smoke 待完成/授权
- 契约版本：1
- 日期：2026-07-16
- 阶段：R6.4
- 批准日期：2026-07-16
- 批准记录：用户明确批准本 Contract、Spec、ADR 和实施计划，并授权从 F1 开始连续 TDD
- 前置契约：[版本化渠道消息与流式运行时契约](versioned-channel-message-runtime.md)
- 前置契约：[Telegram Channel Host 契约](telegram-channel-host.md)
- 关联 ADR：[ADR-0010：使用独立 Java SQLite 渠道账本与事务 Outbox](../adr/0010-use-dedicated-sqlite-channel-ledger.md)
- 关联设计：[渠道可靠投递、幂等与恢复设计](../specs/2026-07-16-channel-reliable-delivery-design.md)
- 实施计划：[R6.4 渠道可靠投递工作计划](../plans/2026-07-16-channel-reliable-delivery-implementation.md)
- 运维草案：[渠道账本备份、恢复与回退手册](../runbooks/channel-ledger-backup-rollback.md)

> 本次批准只授权离线 Java/SQLite 实现和 Loopback 故障注入。它不授权真实 Telegram Token、网络、用户数据、部署、自动重放整个 Turn、人工修改 UNKNOWN、消息中间件或 Exactly Once 声明。

## 1. 目的

R6.3 只在单进程内维护 Telegram offset、重复窗口和投递状态。进程可能在“接受 Update 后、Turn 启动前”“Turn 已提交后、终态入网前”或“远端已发送后、本地确认前”崩溃。单靠内存无法同时避免重复运行 Agent 和重复发送消息。

本契约建立一个默认关闭、Java 自有、可审计的持久 Inbox/Delivery Ledger，固定以下目标：

1. 同一渠道实例中的同一外部消息最多跨越一次 Agent Turn 执行边界。
2. Turn 执行幂等与出站投递重试严格分离；投递恢复绝不重新运行 Agent。
3. 只有能证明远端未接受的请求可以自动重试。
4. 无法证明成功或失败时进入 `UNKNOWN`，不得猜测或盲目重发。
5. offset、入站决策、Turn 占用、终态 Outbox、分片和发送尝试均有事务与故障注入证据。
6. 新 Schema 可校验、可备份、可回退，且不修改 Python `sessions.db` 或 Java Memory 数据库。

本契约不宣称端到端 Exactly Once。Telegram Bot API 没有项目可控的消息幂等键；网络成功与本地提交之间始终存在无法消除的未知窗口。

## 2. 需要批准的决策

1. 新建独立 Java-owned SQLite 数据库 `<workspace>/channels/channel-ledger.db`，不向 `sessions.db` 或 `memory/agent-memory.db` 加表。
2. 新增显式 `agent.channels.reliability.mode=DISABLED|SQLITE`；默认 `DISABLED` 保持 R6.3 行为和零新数据库副作用，`SQLITE` 才初始化账本。
3. 账本按 `channel + channelInstanceId` 分区。Telegram 实例 ID 只由可信 Token 的 Bot ID 部分做域隔离 SHA-256 得到；不保存、输出或散列完整 Token。
4. 入站在执行前先持久占用；只有 `RESERVED -> RUNNING` 与 offset 推进在同一事务成功后，才可调用 `MessageTurnService`。
5. 旧进程遗留的 `RUNNING` 永不自动重跑，恢复为 `EXECUTION_UNKNOWN`；只有能证明从未跨越执行边界的 `RESERVED/START_RETRYABLE` 可以复用原 Turn ID 再次启动。
6. 权威终态必须先与 Outbox/分片在一个事务中持久化，再允许 Producer 返回；实际网络发送在事务外由有界 Worker 执行。
7. Telegram `sendMessage` 返回正的远端 `message_id` 才算确认成功。429 是明确未接受的安全重试；Timeout、I/O、5xx、中断和成功响应损坏均为 `UNKNOWN`。
8. 多分片严格串行；已确认分片不重发，未知分片和其后分片均停止自动投递。
9. 自动重试只用于明确 429，最多两次总尝试，并先持久化 `RETRY_WAIT` 与 `retry_after`。
10. 已解决记录按规则清理；未解决 `UNKNOWN` 不自动删除。达到硬容量上限时渠道 Fail Closed，不静默丢审计或去重状态。

## 3. 范围

版本 1 包含：

- Java-owned Channel Reliability Contract Fixture。
- SQLite Schema V1、严格校验、V0 到 V1 迁移、SQLite Online Backup 与回退演练。
- Telegram Update 事件、单调 offset、逻辑外部消息占用和并发去重。
- Turn 执行边界、启动失败、安全恢复和稳定重复结果。
- Turn 权威终态以及 `SESSION_BUSY`、无活动 Turn 的取消反馈 Outbox。
- 终态分片、发送尝试、远端 Receipt、429 延迟重试、`UNKNOWN` 和永久失败。
- 启动恢复、有界清理、容量门禁、健康状态和纯离线纵向测试。

版本 1 不包含：

- 自动重跑 `RUNNING`、`TERMINAL_RECORDED` 或 `EXECUTION_UNKNOWN` 的 Agent Turn。
- 对 `UNKNOWN` 自动重发、自动补偿、自动猜测远端结果或生成新投递键。
- 人工 Reconcile API、Dashboard、管理 CLI 或直接 SQL 修改状态。
- 多进程 Active/Active、分布式锁、消息中间件或跨节点 Leader Election。
- 主动消息、计划任务、群聊、附件、实时编辑、Webhook 或其他渠道实现。
- 修改会话提交顺序、Tool Side Effect Ledger 或 Approval 语义。
- 真实 Telegram 网络、Secret、用户数据和部署启用。

## 4. 固定不变量

1. `Turn Execution Idempotency != Outbound Delivery Retry`。
2. Runtime 生成并持有 Turn ID、指纹、实例 ID、Lease Owner 和 Delivery ID；外部正文、模型和调用者不能覆盖。
3. 同一 `channel + instance + externalMessageId` 的匹配重放复用原记录；指纹不同即 `IDEMPOTENCY_CONFLICT` 并 Fail Closed。
4. 只有持有当前 Revision/Owner 的执行者可以改变占用或投递状态；所有转换使用事务内比较并更新。
5. Turn 只能在持久状态已经是 `RUNNING` 且对应 offset 已推进后进入 Application。
6. `RUNNING` 一旦可能进入模型、Tool、MCP 或会话提交边界，就永不自动回到可执行状态。
7. 一个 Turn 只能记录一个权威终态。相同终态重放幂等；类型、Code、Retryable 或内容指纹变化均冲突。
8. 网络调用前必须持久化 `IN_FLIGHT` 尝试；网络调用与 SQLite 事务不得互相包裹。
9. 只有明确证明请求未被接受的失败可以重试；证明不足一律 `UNKNOWN`。
10. `UNKNOWN`、永久失败和部分投递不得伪装成成功，也不得触发第二终态。
11. 不持久化入站正文、Delta、Thinking、Tool Arguments/Result、Memory、原始异常、HTTP Body、Token 或完整请求 URI。
12. 默认模式不创建目录/数据库/Worker，不读取额外 Secret，不改变现有 HTTP、CLI 或 Telegram 离线行为。

## 5. 渠道实例与指纹

### 5.1 渠道实例

仅用 `channel=telegram` 作为 offset 分区会在更换 Bot 时复用旧游标。版本 1 因此使用：

```text
channelInstanceId =
  sha256(lengthPrefix("channel-instance-v1", "telegram", decimalBotId))
```

- `decimalBotId` 只来自通过格式校验的 Token 冒号前部分。
- 完整 Token、冒号后 Secret 和 Allowlist 不参与实例 ID。
- Token 轮换但 Bot ID 不变时复用账本；切换 Bot 时得到新分区和初始 offset。
- 实例 ID 是敏感稳定标识，只允许存入本地账本，不进入普通日志、异常或健康响应。

未来渠道必须独立批准其稳定实例来源；不能直接采用用户可控字符串。

### 5.2 入站指纹

`inbound-fingerprint-v1` 使用长度前缀 UTF-8 字段和 SHA-256，覆盖：

- Schema 版本、channel、instance、external event ID/sequence。
- 归一化决策类型。
- 对接受的消息覆盖 external message ID、Session、Route、Sender、occurredAt 和精确规范化正文。
- 对控制/忽略覆盖稳定 Control/Ignore Code，不包含未经授权正文。

Turn ID、线程、当前时间和重试次数不参与指纹。账本只保存 Hash，不保存入站正文。

### 5.3 投递指纹

`delivery-fingerprint-v1` 覆盖：

- channel、instance、目标、来源类型和关联 ID。
- Message Contract 版本、终态 Type、Code 与 Contract 派生的 Retryable。
- 分片算法版本、分片数量、每片序号与精确 UTF-8 内容。

投递重放必须同时命中关联唯一键和指纹；相同关联 ID 的不同内容不得创建第二条 Delivery。

## 6. 入站状态与 offset

### 6.1 事件与逻辑消息分离

- `inbox event` 表示平台投递事件；Telegram 使用 `update_id` 作为事件 ID 和序号。
- `turn claim` 表示能启动 Agent 的逻辑消息；Telegram 使用现有 `InboundMessage.messageId`。
- 一个逻辑消息的重复事件可以关联同一个 Claim，但不能生成第二个 Turn。
- Ignore、Control 和 Busy 决策也记录 Event，确保 offset 推进可审计。

Telegram 每批 Update 必须先按 `update_id` 升序处理；同批重复 ID 必须指纹一致。负数、溢出、同 ID 不同内容或低于已提交 offset 的新冲突事件使 Adapter Fail Closed。

### 6.2 Turn Claim 状态

```text
NEW
  -> RESERVED
       -> RUNNING
            -> TERMINAL_RECORDED
            -> EXECUTION_UNKNOWN
       -> START_RETRYABLE
            -> RESERVED
```

规则：

- `RESERVED`：记录已创建，但执行边界尚未跨越，offset 尚未确认。
- `RUNNING`：在同一 `BEGIN IMMEDIATE` 事务中完成 Owner 校验、状态转换和 `next_offset=update_id+1` 后，才允许调用 Agent。
- `START_RETRYABLE`：Thread Starter 明确失败，或启动恢复证明旧 Owner 只停留在 `RESERVED`；保留原 Turn ID，最多三次启动尝试。
- `TERMINAL_RECORDED`：唯一终态与全部 Delivery Part 已原子持久化；只恢复投递，不重跑 Turn。
- `EXECUTION_UNKNOWN`：旧 Owner 遗留 `RUNNING`、终态持久化失败或无法证明是否越过执行边界；不自动恢复。
- 启动尝试耗尽转为 `EXECUTION_UNKNOWN`，不创建新 Turn ID。

重复处理结果固定为：

| 现有状态 | 行为 |
| --- | --- |
| `RESERVED/START_RETRYABLE` 且指纹相同 | 复用原 Turn ID，受启动预算与 Owner CAS 控制 |
| `RUNNING` | 返回 `IN_PROGRESS`，不调用 Agent |
| `TERMINAL_RECORDED` | 返回 `ALREADY_TERMINAL` 并唤醒安全投递扫描，不调用 Agent |
| `EXECUTION_UNKNOWN` | 返回 `EXECUTION_UNKNOWN`，不调用 Agent、不自动回复 |
| 任意状态但指纹不同 | `IDEMPOTENCY_CONFLICT`，Adapter Fail Closed |

### 6.3 offset 提交

- Ignore、无副作用 Control 和 Busy + 固定反馈 Outbox 可在同一事务中记录决策并推进 offset。
- 接受的普通消息只有在 `RUNNING` 成功后推进 offset；`RESERVED` 或启动失败不确认平台事件。
- offset 只单调增加。相同或更低值只能作为已记录重放验证，不能回退游标。
- `update_id=Long.MAX_VALUE` 无法安全计算下一值，必须 Fail Closed。
- 崩溃发生在 SQLite Commit 前时平台可重投；Commit 后重启从持久 `next_offset` 继续。

## 7. 权威终态与事务 Outbox

### 7.1 终态记录顺序

Telegram 的 Outbound Sink 对 Started/Delta 维持 R6.3 有界消费，但不持久化正文。收到唯一终态时：

1. 使用 R6.1 `OutboundSequenceValidator` 验证完整序列。
2. 投影固定 Telegram 文本并按 `telegram-text-chunks-v1` 分片。
3. 在一个 `BEGIN IMMEDIATE` 事务中校验 Claim 为 `RUNNING`、插入或验证 Delivery/Parts，并把 Claim 改为 `TERMINAL_RECORDED`。
4. Commit 成功后才从 Sink 返回；Worker 唤醒失败不撤销已持久终态，后续扫描仍可发现。
5. SQLite 失败时不发网络，不生成替代终态；Claim 保持 `RUNNING`，恢复为 `EXECUTION_UNKNOWN`。

Completed 表示现有 `ChatService` 已完成会话提交；Cancelled/Failed 不改变其既有不提交规则。R6.4 不把会话库与渠道库伪装成一个跨数据库事务。

### 7.2 固定反馈

以下非 Turn 回复使用同一 Outbox，但 `sourceKind=CHANNEL_FEEDBACK`：

- 已授权消息因当前会话/全局容量而 Busy。
- 已授权 `/cancel` 或 `/stop` 在当前无活动 Turn。

其关联 ID 绑定对应 Event；Event 决策、Delivery/Parts 和 offset 在同一事务提交。未经授权或忽略输入仍不回复。

### 7.3 分片

- 保持 R6.3 的 4,000 UTF-16 Code Unit、禁止切断 Surrogate Pair 规则。
- 分片序号从 0 连续递增，最多 16 片；超过预算 Fail Closed。
- Worker 只领取 `next_part_index`，一片确认后才处理下一片。
- 已确认远端 `message_id` 的 Part 永不重发。
- 某片 `UNKNOWN` 或永久失败后，后续 Part 保持未发送并终止该 Delivery 自动处理。

## 8. Delivery 状态、Receipt 与重试

### 8.1 状态

Delivery Header 状态：

- `PENDING`
- `DELIVERING`
- `DELIVERED`
- `FAILED`
- `UNKNOWN`

Part 状态：

- `PENDING`
- `IN_FLIGHT`
- `RETRY_WAIT`
- `DELIVERED`
- `FAILED`
- `UNKNOWN`

在网络调用前，Ledger 必须原子完成：

- 领取 Header Owner/Lease。
- 将目标 Part 改为 `IN_FLIGHT`。
- 增加 Attempt Number。
- 插入 `STARTED` Attempt Audit。

### 8.2 Telegram 发送结果

`TelegramBotApi.sendMessage` 在 R6.4 改为返回只含正 `message_id` 的安全 Receipt。发送分类固定为：

| 结果 | Ledger 结果 | 自动行为 |
| --- | --- | --- |
| HTTP 2xx、`ok=true`、合法正 `message_id`，且 SQLite 确认 Commit | `DELIVERED` | 处理下一片 |
| 明确 HTTP/Bot API 429 和合法有界 `retry_after` | `RETRY_WAIT` | 按远端时间最多再尝试一次 |
| 401/403/404 或可证明未接受的其他 4xx | `FAILED` | 不重试 |
| Timeout、I/O、5xx、中断、响应超限/损坏、2xx 但 Receipt 缺失 | `UNKNOWN` | 不重试 |
| 远端成功但本地结果 Commit 失败 | 恢复时 `UNKNOWN` | 不重试 |
| Attempt 处于 `IN_FLIGHT` 时进程崩溃 | 恢复时 `UNKNOWN` | 不重试 |

JDK Client 无法可靠证明部分 I/O 失败发生在发送前，因此版本 1 保守归类 `UNKNOWN`。本地参数校验在创建 Delivery 前完成，不形成网络 Attempt。

### 8.3 重试预算

- 每个 Part 最多两次总网络尝试。
- 只有第一次明确 429 可创建一次 `RETRY_WAIT`；第二次任何非成功结果均终止。
- `retry_after` 必须为正且不超过现有 `max-retry-after` 硬上限；缺失或超限转 `FAILED/RETRY_BUDGET_EXCEEDED`，不自行猜测等待时间。
- 等待时间和下次执行点先持久化；Worker 使用可中断、有界等待，不在 SQLite 事务中 Sleep。
- 重试复用相同 Delivery/Part/指纹，不创建新键。

## 9. SQLite Schema V1

数据库固定为 `channel-ledger.db`。V1 使用以下七张 Java-owned 表：

| 表 | 主键/唯一键 | 关键字段 |
| --- | --- | --- |
| `channel_schema` | 单行 `singleton=1` | `version`、`updated_at` |
| `channel_cursors` | `channel, instance_id` | `next_sequence`、`revision`、`updated_at` |
| `channel_inbox_events` | `channel, instance_id, external_event_id`；同实例 `external_sequence` 唯一 | `event_fingerprint`、`decision`、`turn_id`、时间 |
| `channel_turn_claims` | `channel, instance_id, external_message_id`；`turn_id` 全局唯一 | `request_fingerprint`、`state`、`start_attempts`、Owner/Lease、Revision、时间 |
| `channel_deliveries` | `delivery_id`；同实例 `source_kind, correlation_id` 唯一 | 目标、类型、指纹、状态、Part 游标、`payload_pruned`、Owner/Lease、稳定错误码、Revision、时间 |
| `channel_delivery_parts` | `delivery_id, part_index` | `payload_text`、指纹、状态、尝试数、下次时间、远端消息 ID、稳定错误码 |
| `channel_delivery_attempts` | `delivery_id, part_index, attempt_number` | 开始/完成时间、结果、远端消息 ID、稳定错误码 |

约束：

- 所有枚举使用显式 `CHECK`；时间使用规范 UTC ISO-8601 文本；Boolean 不使用宽松整数。
- 所有 ID、Hash、Payload、计数和时间均有非空/长度/范围约束。
- Foreign Key 始终开启；Part/Attempt 只属于现有 Delivery。
- `payload_pruned` 是严格 0/1 标记；只有已解决 Delivery 可以在同一事务删除 Part/Attempt 并置 1，未解决 Delivery 必须为 0 且 Part 数量/序号完整。
- Schema 校验要求表、列、索引、Foreign Key、View 和 Trigger 与版本精确匹配；未知对象、未来版本或损坏数据库 Fail Closed。
- 连接固定 `foreign_keys=ON`、`busy_timeout=5000`、`journal_mode=WAL`、`synchronous=FULL`。
- 写事务使用显式 `BEGIN IMMEDIATE/COMMIT/ROLLBACK`，网络调用绝不位于事务内。

Fresh 空数据库直接创建 V1。V0 只允许精确的单行 `channel_schema(version=0)`；迁移前必须在切换 Journal Mode 或任何 Schema/Data 写入之前成功完成 SQLite Online Backup，随后切换并验证 WAL，再在单事务内建表、索引、更新时间和版本。Backup 失败不得开始迁移。既有 V1 必须已经是 WAL；模式漂移时 Fail Closed，不静默改写。V1 重复初始化必须幂等。

## 10. 恢复、Lease 与并发

版本 1 仍是单 JVM Writer；SQLite 唯一约束和 CAS 必须承受同 JVM 多线程竞争，但不承诺多实例 Active/Active。

- 每次进程启动生成不透明 128-bit Owner ID，不落日志。
- Lease 只证明当前操作所有权和识别旧 Owner；Lease 过期本身不是重跑 Turn 或重发网络的授权。
- 接受新 Poll 前先完成当前实例的有界恢复：
  - 旧 Owner `RESERVED` -> `START_RETRYABLE`。
  - 旧 Owner `RUNNING` -> `EXECUTION_UNKNOWN`。
  - `DELIVERING/IN_FLIGHT/STARTED` -> `UNKNOWN`。
  - 到期 `RETRY_WAIT` 和 `PENDING` 保持可安全领取。
- 恢复按固定批次进行；超出单批不会无限阻塞 Spring 启动。依赖该 Ledger 的 Adapter 保持 `STARTING/DEGRADED`，HTTP、CLI 和不依赖该账本的能力继续可用。
- 同一 Claim/Part 的并发领取最多一个 CAS 成功；失败者只读取稳定状态。

## 11. 保留、容量与敏感数据

### 11.1 数据最小化

允许持久化：

- 渠道/实例 Hash、外部事件与消息 ID、Turn/Delivery ID。
- 路由投递目标（恢复发送所必需）。
- 入站/投递指纹、稳定状态/错误码、时间和计数。
- 权威终态或固定反馈的分片正文，直到符合清理条件。
- Telegram 成功 Receipt 的远端消息 ID。

禁止持久化：

- 入站用户正文、Delta、Prompt、Thinking、Tool/MCP 参数或结果、Memory。
- Token、Authorization、完整 HTTP URI/Body、Telegram description。
- Java 异常正文/Stack Trace、线程名、Owner 原文的日志投影。

渠道账本包含敏感目标和回答正文，必须沿用 Workspace 本地数据保护；不得提交 Git、复制到测试 Fixture 或出现在运行报告中。

### 11.2 清理与容量

- 已投递 Payload 和已完成 Attempt 明细默认保留 30 天，之后分批删除或收缩为不含正文的 Tombstone。
- Ignore/Control Event 只有在低于持久 offset 且超过保留期后才能删除。
- Claim 去重 Tombstone、`EXECUTION_UNKNOWN`、Delivery `UNKNOWN` 和未解决失败不自动删除。
- 每轮清理最多 100 行；不自动 `VACUUM`，避免长期全库锁。
- 版本 1 对 Event、Claim、Delivery 和 Attempt 设置配置上限及编译期硬上限；达到上限时停止接受新入站并报告 `CHANNEL_LEDGER_CAPACITY_EXCEEDED`。
- 清理失败不删除未确认数据；连续失败使依赖 Adapter `DEGRADED/FAILED`，不影响会话数据库。

## 12. 配置、默认与健康

新增配置前缀 `agent.channels.reliability`：

| 配置 | 默认 | V1 硬边界 |
| --- | ---: | ---: |
| `mode` | `DISABLED` | `DISABLED/SQLITE` |
| `recovery-batch-size` | 100 | 1..1000 |
| `cleanup-batch-size` | 100 | 1..1000 |
| `retention` | 30d | 1d..365d |
| `max-inbox-records` | 100000 | 1000..1000000 |
| `max-delivery-records` | 10000 | 100..100000 |

`DISABLED`：

- 不创建 `channels/` 或 `channel-ledger.db`。
- 不装配 Ledger Worker，不执行恢复/清理。
- Telegram 保持 R6.3 进程内可靠性边界；仍不等于生产可靠投递。

`SQLITE`：

- 只有显式启用的 Servlet 渠道使用 Ledger。
- Schema/恢复失败使 Telegram Adapter Fail Closed 且不访问网络；应用 HTTP/CLI 仍可启动。
- 健康只暴露模式、稳定状态、待处理/未知计数和稳定错误码，不含目标、正文、外部 ID、Owner、路径或异常。

生产模板继续固定 Telegram `enabled=false` 和 Reliability `DISABLED`。R6.4 离线实现完成不构成真实部署授权。

## 13. 备份与回退

- 所有自动化只使用临时数据库，不读取或写入真实 Workspace。
- Fresh DB 不需要虚构备份；已有 V0/未来迁移必须在写 Schema 前生成并验证非空 Online Backup。
- 自动迁移仅支持已批准的前一版本；未来版本、未知表或无法备份一律拒绝。
- 回退必须先设置 Telegram Disabled 并停止所有 Ledger Writer；不支持运行中替换数据库。
- 回退使用已验证 Backup 恢复完整 SQLite 状态，再运行只读 `quick_check`、Schema Version 和计数检查。
- 不在旧二进制下打开更高版本数据库；必要时保留新数据库并切回 R6.3 `DISABLED` 路径。

具体步骤以关联 Runbook 为准；Runbook 演练只针对临时副本。

## 14. Fixture 与测试要求

Java-owned V1 Fixture 必须覆盖：

- 实例 ID 稳定、Token 轮换同 Bot、不同 Bot 隔离以及 Secret 不入 Hash 输入。
- Event/Claim 指纹匹配与冲突、并发 Claim、同 Turn ID 唯一。
- `RESERVED`、`START_RETRYABLE`、`RUNNING`、终态和 `EXECUTION_UNKNOWN` 转换。
- offset 与执行边界同事务、Commit 前/后崩溃、Long 溢出。
- 唯一终态、内容冲突、固定反馈与分片指纹。
- 发送成功、429、永久拒绝、Timeout、5xx、响应损坏、Commit 失败和进程崩溃。
- 多分片部分成功、未知分片停止、已确认分片不重发。
- V0 备份迁移、V1 幂等、未来/未知/损坏 Schema 拒绝与回退。
- 恢复批次、Lease 竞争、清理边界、容量 Fail Closed。
- 默认 Disabled 零 DB、零 Worker、零网络，以及 HTTP/CLI 不受 Ledger 故障影响。

竞态测试使用 Barrier/Latch、注入 Clock、Owner、Thread Starter、SQLite Fault Point 和 Loopback Fake Server；禁止用墙钟 Sleep 证明正确性。所有真实发送场景仍使用假 Token 和 Loopback。

## 15. 完成标准

只有同时满足以下条件，R6.4 才能标记“离线已实现”：

1. 本 Contract、Spec、ADR、实施计划和 Schema 决策已获明确批准。
2. F1–F13 十三个实现任务均有有效 RED/GREEN 或项目规定的纯验收例外，并以单一目的提交。
3. 重复、并发、启动失败、每个崩溃窗口、429、永久拒绝和 `UNKNOWN` 均有确定测试。
4. 临时 V0 数据库的 Backup/Migration/Restore 演练通过；未访问真实 Workspace。
5. 默认、`failure`、`compat`、架构、Secret、Workspace、线程和 Socket 门禁通过。
6. 默认部署仍为 `DISABLED`，没有真实 Telegram 请求或 Secret。
7. 文档不作 Exactly Once 保证或完成声明，并明确所有需要人工核查的未知状态。

## 16. 暂停条件

出现以下任一情况必须停止并重新请求批准：

- 需要修改 `sessions.db`、`agent-memory.db`、Python 数据或真实 Workspace。
- 需要保存入站正文、Tool/MCP 数据、Secret 或原始远端响应。
- 需要自动重跑 `RUNNING/UNKNOWN` Turn，或自动重发 `UNKNOWN` Delivery。
- 需要多进程 Active/Active、Broker、分布式锁或新 Maven 模块。
- 需要人工 Reconcile 写 API、Dashboard、真实网络、Token、用户数据或部署。
- Telegram 无法提供足以区分已确认成功、明确拒绝与未知的安全投影。
