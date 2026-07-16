# ADR-0010：使用独立 Java SQLite 渠道账本与事务 Outbox

- 状态：已接受
- 日期：2026-07-16
- 阶段：R6.4
- 批准记录：用户于 2026-07-16 明确接受本决策，并授权从 F1 开始连续 TDD
- 关联 Contract：[渠道可靠投递、幂等与恢复契约](../contracts/channel-reliable-delivery.md)
- 关联设计：[渠道可靠投递、幂等与恢复设计](../specs/2026-07-16-channel-reliable-delivery-design.md)
- 前置决策：[ADR-0002：采用 SQLite 兼容优先的渐进迁移](0002-sqlite-compatible-incremental-migration.md)
- 前置决策：[ADR-0009：Telegram 首渠道使用 JDK HttpClient 与 Bot API 长轮询](0009-use-jdk-httpclient-for-telegram-long-polling.md)

## 背景

R6.3 的 Telegram Adapter 只保存进程内 offset、重复窗口、活动 Turn 和发送结果。它能防止一个进程生命周期内的重复启动，却不能解决以下崩溃窗口：

- Update 已映射但 Turn Worker 尚未开始。
- Turn 已进入模型、Tool 或会话提交边界，但进程尚未记录终态。
- 终态已生成，但尚未发送。
- Telegram 已接受消息，但本地尚未记录成功。
- 多分片前几片成功，后续一片结果未知。

现有 `sessions.db` 是 Python/Java 共用的会话兼容数据库，`memory/agent-memory.db` 是独立 Java 语义记忆库。把渠道 offset、Lease、Outbox 和 Attempt Audit 塞进任一数据库，都会扩大其事务与迁移含义，也会让渠道故障影响普通会话或记忆能力。

Telegram Bot API 不接受项目提供的消息幂等键。即使使用 SQLite Outbox，也无法原子提交“远端已发送”和“本地已确认”，因此不能诚实宣称 Exactly Once。

## 决策

1. R6.4 使用独立 Java-owned SQLite 数据库 `<workspace>/channels/channel-ledger.db`。
2. 数据库包含版本 Header、渠道实例游标、Inbox Event、Turn Claim、Delivery、Part 和 Attempt Audit，不修改 Python 核心表。
3. Application 通过项目自有 `ChannelLedgerPort` 编排；JDBC/SQLite 只存在于 `adapter-sqlite`，Telegram/HTTP 只存在于 `agent-bootstrap`。
4. 入站采用事务 Inbox：外部事件和逻辑消息先占用；`RUNNING` 与 offset 同事务提交后才跨越 Agent 执行边界。
5. 出站采用事务 Outbox：唯一权威终态和全部分片先落库，再由有界 Worker 在事务外发送。
6. 网络尝试先持久 `IN_FLIGHT`；只有明确 429 可以自动重试，无法确认的结果进入 `UNKNOWN`。
7. 数据库按可信渠道实例分区。Telegram 使用 Token 中稳定 Bot ID 的域隔离 Hash，不使用完整 Token。
8. 功能由 `agent.channels.reliability.mode=SQLITE` 显式启用；默认 `DISABLED` 不创建数据库或 Worker。
9. V1 只支持单 JVM Writer，不引入 Broker、分布式锁或多实例 Active/Active。

## 理由

- 独立数据库把渠道敏感数据、迁移、保留、备份和失败域与会话/记忆隔离。
- SQLite 已是项目批准依赖，单机部署无需增加基础设施或动态服务。
- `BEGIN IMMEDIATE`、唯一约束和 Revision CAS 足以证明当前单 JVM 的并发占用。
- Transactional Inbox/Outbox 能封闭大多数本地崩溃窗口，同时允许对不可消除的远端窗口诚实记录 `UNKNOWN`。
- 先落终态再发送，恢复只需要继续安全 Delivery，不需要重建 Prompt、重跑模型或重复 Tool。
- 独立实例 ID 避免新 Bot 错用旧 Bot offset；只 Hash Bot ID 又能支持同一 Bot 的 Token 轮换。
- 默认关闭保持现有 CI、HTTP、CLI 与 R6.3 路径无新增磁盘/线程/网络副作用。

## 备选方案

| 方案 | 优点 | 拒绝/后置原因 |
| --- | --- | --- |
| 给 `sessions.db` 加 Inbox/Outbox 表 | 会话与渠道数据在一个文件 | 扩大 Python 兼容 Schema 与迁移风险；渠道清理/损坏影响被动聊天 |
| 给 `agent-memory.db` 加表 | 已有 Java-owned 版本 Schema | 可靠投递与语义记忆生命周期无关；Memory Disabled 时渠道仍应可独立启用 |
| 继续只用内存 | 实现简单、零磁盘 | 无法处理重启、重复 Update 或发送后崩溃 |
| Kafka/RabbitMQ/Redis Streams | 多节点吞吐和运维能力更强 | 新基础设施、部署和信任边界远超当前单机纵向切片 |
| 依赖 Telegram offset，不保存 Claim | 表更少 | offset 不能证明 Turn 是否已越过模型/副作用边界 |
| 收到终态后同步发送再记数据库 | 延迟低 | 发送成功与本地记录间崩溃会盲目重复；也阻塞 Producer |
| 对 Timeout/5xx 自动重试 | 表面可用性更高 | Telegram 不支持项目幂等键，可能产生重复用户消息 |
| 保存完整入站消息以便重跑 | 可以在重启后继续 Agent | 增加敏感数据副本，并可能重复模型、Tool、MCP 或副作用 |
| 声称 Exactly Once | 对外表述简单 | 远端发送与 SQLite 无共同事务，技术上不可证明 |

## 后果

正面结果：

- 同一外部消息的执行边界和同一终态的投递身份可持久审计。
- Telegram 重启从每个 Bot 实例自己的 offset 恢复。
- Pending/429 Delivery 可安全恢复，已确认分片不会重发。
- 不确定结果被显式隔离，不会以自动重试制造更多重复。
- 会话、记忆和默认 Disabled 路径不承担新 Schema。

代价：

- 新增独立数据库、版本 Schema、Backup/Restore 和保留策略。
- 终态正文和目标为恢复发送而暂存，成为需要保护的本地敏感数据。
- `UNKNOWN` 需要未来经批准的人工核查能力；V1 只能 Fail Closed。
- 两个 SQLite 文件不能形成原子跨库事务；会话成功但终态持久化失败时只能记录执行未知。
- 单 JVM 约束不支持多个应用实例同时消费同一个 Bot。

## 重新评估条件

出现以下任一证据时新增 ADR，而不是静默扩大本决策：

- 需要多实例 Active/Active 或跨机器容灾。
- 可靠积压超过 SQLite 单机容量/锁等待预算。
- 渠道提供真正的服务端 Idempotency Key 或可查询发送状态。
- 需要人工 Reconcile、主动消息或跨渠道 Fan-out。
- 需要加密字段、外部 KMS 或不同数据驻留策略。
- 第二个渠道证明共享数据库的故障域不可接受。

## 实施验证要求

实现必须通过临时 SQLite 的 Schema/迁移/Backup/Restore 测试、并发 Claim/Lease 测试、Loopback Telegram 故障注入、默认 Disabled 零副作用，以及默认、`failure`、`compat` 三套门禁。真实 Telegram、Secret、用户数据和部署仍未授权。
