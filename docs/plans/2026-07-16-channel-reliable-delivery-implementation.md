# R6.4 渠道可靠投递、幂等与恢复工作计划

- 状态：已批准，连续 TDD 实施中
- 当前任务：F5 Delivery、Part 与 Attempt Ledger
- 日期：2026-07-16
- 分支：`agent/r6-reliable-delivery`
- Worktree：`/Users/namei/idea/agent/java-agent-r6-reliable-delivery`
- 基线：`origin/main@0bd41770a6a043c84d4e30bb5dbe0e6307b29f83`
- Contract：[渠道可靠投递、幂等与恢复契约](../contracts/channel-reliable-delivery.md)
- Spec：[渠道可靠投递、幂等与恢复设计](../specs/2026-07-16-channel-reliable-delivery-design.md)
- ADR：[ADR-0010：使用独立 Java SQLite 渠道账本与事务 Outbox](../adr/0010-use-dedicated-sqlite-channel-ledger.md)
- 总体计划：[R6 渠道、消息总线与控制面总体工作计划](2026-07-15-r6-channel-message-control-plane-master-plan.md)

> 用户已于 2026-07-16 明确批准关联 Contract、Spec、ADR 和本计划。F1–F13 只授权离线实现；真实 Telegram、Secret、用户数据、部署和超出契约的状态/Schema 仍是独立暂停条件。

## 1. 目标

以连续 TDD 完成默认关闭、纯离线的 R6.4 纵向切片：

```text
Telegram Update
  -> durable Event / Claim / offset
  -> at-most-one Turn execution boundary
  -> durable authoritative terminal Outbox
  -> ordered Part delivery / Receipt / UNKNOWN
  -> bounded recovery / cleanup
```

最终只声明“R6.4 离线实现已验证”。真实 Token、Telegram 网络、用户数据和部署继续需要单独授权。

## 2. 实施原则

1. F0 获批后从 F1 开始；每个行为 Task 只运行一次聚焦 RED、最小 GREEN、自审、`git diff --check` 和独立提交。
2. Fixture-only、文档和最终验收遵循项目例外，不人为破坏已有实现制造 RED。
3. 所有 SQLite 使用 JUnit 临时目录；所有 HTTP 使用 Loopback Fake Server 和固定假 Token。
4. 并发/崩溃使用 Barrier、Latch、注入 Clock/Owner/Thread Starter/Fault Point，不用墙钟 Sleep。
5. 网络调用永不位于 SQLite 事务；`UNKNOWN` 测试必须断言调用次数，防止隐式重试。
6. Task F6、F10、F13 执行集中阶段门禁；其他 Task 不重复跑完整 Reactor。
7. 原始脏工作树 `/Users/namei/idea/agent/java-agent` 和真实 Workspace 不修改。
8. 任一行为需要超出批准的 Schema、状态、重试或数据范围时立即暂停。

## 3. Task F0：冻结 Contract、Spec、ADR、Runbook 与计划

状态：已完成并批准。

交付：

- `docs/contracts/channel-reliable-delivery.md`
- `docs/specs/2026-07-16-channel-reliable-delivery-design.md`
- `docs/adr/0010-use-dedicated-sqlite-channel-ledger.md`
- 本实施计划
- `docs/runbooks/channel-ledger-backup-rollback.md`
- 文档导航、R6 总计划、Roadmap、重写指南和能力矩阵状态同步

必须明确批准：

- 独立 `workspace/channels/channel-ledger.db` 和七表 V1 Schema。
- `DISABLED|SQLITE` 显式模式与默认零副作用。
- 渠道实例 Hash、Inbox/Claim、执行边界与 offset 同事务。
- Transactional Outbox、终态正文暂存、Receipt、429 和 `UNKNOWN`。
- V0 Backup/Migration、恢复、清理、容量和回退语义。

验证：

```bash
./mvnw --batch-mode --no-transfer-progress spotless:check
git diff --check
```

预计提交：`docs: 提议 R6.4 渠道可靠投递契约`

验证证据（2026-07-16）：`spotless:check` 对八模块 Reactor 全部通过，`git diff --check`
通过；只新增/修改文档，没有创建 Fixture、SQLite 文件、生产代码、Worker 或网络访问。

批准证据（2026-07-16）：用户明确回复“批准”，批准本计划关联的 Contract、Spec 和 ADR，
并授权从 F1 开始连续 TDD；真实网络、Secret、用户数据和部署未包含在批准范围。

## 4. Task F1：Java-owned Fixture 与 Kernel Contract

状态：已完成。

先创建：

- `testdata/golden/channels/channel-reliable-delivery-v1.json`
- `agent-kernel/src/test/.../ChannelReliableDeliveryContractTest.java`
- `agent-kernel/src/test/.../ChannelFingerprintTest.java`

Fixture 固定实例、指纹、Claim/Delivery 状态、合法/非法转换、重试分类和稳定错误。随后创建最小 Kernel 类型与 `ChannelLedgerPort`；Fixture 必须由生产 Factory/Validator 消费，不能只反序列化后比较常量。

RED/GREEN：

```bash
./mvnw -pl agent-kernel -am \
  -Dtest=ChannelReliableDeliveryContractTest,ChannelFingerprintTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

自审：

- Kernel 零 JDBC/Spring/Telegram/Jackson 生产依赖。
- 指纹长度前缀、UTF-8、域版本、边界和 Secret 输入。
- 所有 ID/枚举/计数不可构造非法状态。

预计提交：`feat: 冻结渠道可靠投递 Kernel Contract`

RED/GREEN 证据（2026-07-16）：聚焦命令首次在 Kernel 测试编译阶段因
`ChannelInstanceId`、`ChannelFingerprint`、状态枚举、`DeliveryEnvelope` 和
`ChannelLedgerPort` 缺失而失败；实现后同一命令执行 8 个测试全部通过。Fixture 的 40 个
Java-owned Case 固定了渠道实例域隔离、请求/Event/Delivery 指纹和四套状态转换；测试同时证明
Turn ID 不参与请求指纹、完整 Token 形态不能作为实例键、Payload/稳定标识安全脱敏，以及 Port
只暴露八个无 JDBC/Spring/Telegram 类型的窄操作。首次 GREEN 尝试发现碰撞测试使用了不合法的
小写决策码，改为语义等价的合法大写样例后复跑全绿；Spotless 与 `git diff --check` 通过。

## 5. Task F2：SQLite Schema V1、严格校验与 Backup

状态：已完成。

先写：

- `ChannelLedgerSchemaInitializerTest`
- `ChannelLedgerSchemaV1Test`
- `ChannelLedgerBackupTest`

RED 覆盖 Empty 创建、V0 Backup + Migration、V1 幂等、Future/Negative/Unknown 表列索引/View/Trigger 拒绝、`quick_check`、Backup 失败零迁移和 Backup 可重新打开。

最小实现：

- `ChannelLedgerSchemaV1`
- `ChannelLedgerSchemaInitializer`
- `ChannelLedgerBackup`
- 稳定 Repository Failure/Exception

RED/GREEN：

```bash
./mvnw -pl adapter-sqlite -am \
  -Dtest=ChannelLedgerSchemaInitializerTest,ChannelLedgerSchemaV1Test,ChannelLedgerBackupTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

自审：固定文件名；Online Backup 在迁移前；显式事务；WAL/FULL/FK/Busy Timeout；临时 DB/WAL/SHM/Backup 清理。

预计提交：`feat: 建立版本化渠道账本 Schema`

RED/GREEN 证据（2026-07-16）：聚焦命令首次在测试编译阶段因
`ChannelLedgerSchemaInitializer`、`ChannelLedgerSchemaV1`、Backup Port 和稳定 Repository
Failure/Exception 缺失而失败；最小实现后 Empty/V0/V1、七表五索引、Foreign Key/Check、
WAL/FULL/FK/Busy Timeout、Online Backup、幂等初始化、损坏数据库和漂移拒绝共 10 个测试通过。
首次 GREEN 尝试同时暴露 Backup Port 不能表达文件 I/O 故障，扩展受检失败边界后，部分备份会被
删除并稳定映射为 `BACKUP_FAILED`。自审新增 SQLite `INTEGER` 亲和性回归测试，先证明 `0.5`
会被旧读取逻辑截断并错误进入 Backup，再通过 `typeof(...)` 严格验证修复；同时补齐未知表、列和
Trigger 拒绝。最终同一聚焦命令执行 12 个测试全部通过，源 V0 在 Backup 前保持 DELETE Journal，
既有 V1 Journal 漂移不被静默修复，`git diff --check` 与聚焦 Spotless 通过。

## 6. Task F3：持久 Event、Cursor 与冲突

状态：已完成。

先写 `JdbcChannelLedgerInboxTest`：

- Ignore/Control/Event 与 Cursor 原子提交。
- Event ID/Sequence 唯一和指纹匹配重放。
- 同 ID 不同指纹冲突且 Cursor 不前进。
- offset 单调、批次升序、Long Max Fail Closed。
- 两连接并发 Event 只有一个 Winner。
- Commit Fault 后无半条 Event/Cursor。

实现 `JdbcChannelLedger` 的连接/事务骨架、`recordEvent` 和 Cursor 查询。

RED/GREEN：

```bash
./mvnw -pl adapter-sqlite -am \
  -Dtest=JdbcChannelLedgerInboxTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

预计提交：`feat: 原子记录渠道事件与游标`

RED/GREEN 证据（2026-07-16）：聚焦命令首次在测试编译阶段因 `JdbcChannelLedger` 和可控
Fault Point 缺失而失败；实现 `BEGIN IMMEDIATE` 事务骨架、Event/Sequence 双键查询、Cursor
CAS 推进和只读 Snapshot 后，同一命令执行 6 个测试全部通过。测试证明 Ignore/Control 决策与
Cursor 同事务提交，首个 Cursor Revision 为 0、后续单调增加，匹配重放不增加行或 Revision；
相同 Event ID 的不同指纹、同 Sequence 的不同 Event ID、低于 Cursor 的未记录事件和
`Long.MAX_VALUE` 均 Fail Closed。两个真实 SQLite 连接同时争抢同一 Sequence 时恰有一个 Winner；
提交前注入 SQLException 后 Event/Cursor 都为零行，异常只暴露稳定 `OPERATION_FAILED`，不泄漏
底层消息。

## 7. Task F4：Turn Claim、启动边界与恢复

状态：已完成。

先写 `JdbcChannelTurnClaimTest`，覆盖：

- 新 Claim、同外部消息重放、不同内容冲突。
- 两线程/连接并发只产生一个 Turn ID。
- `RESERVED -> RUNNING + Cursor` 单事务 CAS。
- Starter 明确失败 -> `START_RETRYABLE`，保留原 Turn ID，最多三次。
- 旧 Owner Reserved 可恢复，Running 只到 `EXECUTION_UNKNOWN`。
- Revision/Owner/Lease 错误不能改变状态。
- DB Fault 后 Agent Invocation Counter 为零。

RED/GREEN：

```bash
./mvnw -pl adapter-sqlite -am \
  -Dtest=JdbcChannelTurnClaimTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

预计提交：`feat: 固定渠道 Turn 执行占用边界`

RED/GREEN 证据（2026-07-16）：聚焦命令首次执行 8 个测试全部因 TURN Event 尚未实现而以稳定
`OPERATION_FAILED` 失败；补齐 Claim/Turn ID 唯一占用、Request Fingerprint 重放、Revision
CAS、`RESERVED -> RUNNING + Cursor` 单事务和分批恢复后转绿。自审新增“Cursor 已越过 Event”
回归用例，先证明旧实现会在返回 STALE 前错误提交 `START_RETRYABLE -> RESERVED`，再把所有前置
校验移到写入前修复。随后用现有八操作 Port 补齐 Starter 失败预算：恢复把未跨界的 `RESERVED`
记为 `START_RETRYABLE` 并增加尝试，重投复用原 Turn ID 重新占用，第三次失败后转
`EXECUTION_UNKNOWN` 且 Cursor 仍不推进。最终 10 个聚焦测试全部通过；两个连接并发只产生一个
Claim/Turn ID，错误 Request/Turn ID、Revision/Owner/Lease 和启动 Commit Fault 都不能跨越
Agent 边界，旧 `RUNNING` 只恢复为 Unknown。

## 8. Task F5：Delivery、Part 与 Attempt Ledger

状态：进行中。

先写 `JdbcChannelDeliveryTest`：

- 唯一终态/反馈 Outbox、相同指纹幂等、不同指纹冲突。
- 1..16 Part 连续约束与 Payload Hash。
- Claim Part 前原子 `IN_FLIGHT + STARTED`。
- Success Receipt、429 Due、Permanent、Unknown 的状态与 Header 推进。
- 已确认 Part 不重领；未知 Part 后续不发送。
- 两 Worker 并发领取最多一个成功。
- 网络成功后 DB Fault 留下 In-flight，恢复为 Unknown。

RED/GREEN：

```bash
./mvnw -pl adapter-sqlite -am \
  -Dtest=JdbcChannelDeliveryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

预计提交：`feat: 实现渠道终态事务 Outbox`

## 9. Task F6：恢复、清理、容量与 SQLite 阶段门禁

状态：未开始。

先写带 `@Tag("failure")` 的 `ChannelLedgerRecoveryFailureTest`：

- 多批恢复稳定分页且幂等。
- Pending/Due Retry 保留，In-flight/Started 转 Unknown。
- 收缩已解决 Payload，不删 Claim Tombstone/Unknown。
- 只清理 Cursor 以下过期 Event。
- 每批最多配置行数。
- 到达硬容量先清理，仍满则稳定拒绝。
- 清理/恢复中断、Busy/Corrupt 不泄漏 Connection 或部分 Commit。

聚焦 RED/GREEN：

```bash
./mvnw -pl adapter-sqlite -am -Pfailure \
  -Dtest=ChannelLedgerRecoveryFailureTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Task F6 阶段门禁：

```bash
./mvnw --batch-mode --no-transfer-progress spotless:check
./mvnw --batch-mode --no-transfer-progress -pl adapter-sqlite -am verify
./mvnw --batch-mode --no-transfer-progress clean verify
./mvnw --batch-mode --no-transfer-progress -Pfailure verify
```

记录实际测试数、Profile 选择和临时数据库范围；不得预填数字。

预计提交：`feat: 完成渠道账本恢复与容量门禁`

## 10. Task F7：Application 入站协调器

状态：未开始。

先写 `ReliableInboundCoordinatorTest`：

- Mapper 后先 Claim，只有 `RUNNING + Cursor` 成功才调用 Turn。
- 重复 In Progress/Terminal/Unknown 均零模型、Tool、MCP 调用。
- Starter 失败恢复原 Turn ID，许可/Map/Thread 全释放。
- Busy/Control 固定反馈命令，不持久化未授权正文。
- Cancellation 在 Event Commit 后执行且只影响目标 Turn。
- Ledger 不可用/冲突/容量映射为稳定 Channel Failure。

RED/GREEN：

```bash
./mvnw -pl agent-application -am \
  -Dtest=ReliableInboundCoordinatorTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

最小实现 `ReliableInboundCoordinator`、Clock/Owner/ID Port 以及与现有 `MessageTurnService` 的窄接口；不引用 Telegram。

预计提交：`feat: 接入持久入站执行门禁`

## 11. Task F8：Durable Terminal 与 Delivery Coordinator

状态：未开始。

先写：

- `DurableTerminalCoordinatorTest`
- `ChannelDeliveryCoordinatorTest`
- `ChannelDeliveryWorkerTest`

覆盖：

- Started/Delta 不落 Payload，唯一终态先持久后返回。
- Commit 失败零网络；Wake 丢失仍由扫描发现。
- 发送前 Attempt 已持久，网络在事务外。
- Confirmed/429/Permanent/Unknown 精确转换。
- Retry Due/总尝试预算、Shutdown/中断/Worker Join。
- 一个失败 Delivery 不阻塞其他 Delivery。

RED/GREEN：

```bash
./mvnw -pl agent-application -am \
  -Dtest=DurableTerminalCoordinatorTest,ChannelDeliveryCoordinatorTest,ChannelDeliveryWorkerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

预计提交：`feat: 编排持久终态与有界投递`

## 12. Task F9：Telegram Receipt 与发送确定性分类

状态：未开始。

先扩展：

- `JdkTelegramBotApiIT`
- `TelegramDeliveryTransportTest`
- `TelegramTerminalRendererTest`（迁移旧内存重试预期）

覆盖：

- 合法正 `message_id` Receipt。
- 429 明确拒绝与有界 Retry-After。
- 401/403/404/其他 4xx 永久拒绝。
- Timeout、I/O、5xx、中断、Body 超限/损坏、2xx 缺 Receipt 均 Unknown。
- 异常无 Token、URI、Body、Description、Cause。
- 移除 `TelegramDeliveryPolicy` 双重重试。

RED/GREEN：

```bash
./mvnw -pl agent-bootstrap -am \
  -Dtest=TelegramDeliveryTransportTest,TelegramTerminalRendererTest \
  -Dit.test=JdkTelegramBotApiIT \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

预计提交：`feat: 固定 Telegram 投递确认语义`

## 13. Task F10：Telegram 持久纵向装配与第二阶段门禁

状态：未开始。

先写：

- `TelegramReliableChannelAdapterTest`
- `ChannelReliabilityPropertiesTest`
- `TelegramReliabilityBootstrapTest`

覆盖：

- Bot ID Instance Hash、Token 轮换/隔离。
- 首次 Poll 前 Schema/恢复/持久 offset。
- Ignore/Control/Busy/Accepted 的事务顺序。
- Durable Sink 先终态后网络；Delivery Worker 单例。
- Disabled 零目录/DB/Worker/Token Read/网络。
- SQLite 失败只使 Telegram Failed，Servlet HTTP/CLI 仍健康。
- Context Close 的 Poll/Turn/Delivery/Connection 有界释放。

RED/GREEN：

```bash
./mvnw -pl agent-bootstrap -am \
  -Dtest=TelegramReliableChannelAdapterTest,ChannelReliabilityPropertiesTest,TelegramReliabilityBootstrapTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Task F10 阶段门禁：

```bash
./mvnw --batch-mode --no-transfer-progress spotless:check
./mvnw --batch-mode --no-transfer-progress -pl agent-bootstrap -am verify
./mvnw --batch-mode --no-transfer-progress clean verify
./mvnw --batch-mode --no-transfer-progress -pl agent-kernel dependency:tree
```

审计 Kernel/Application 无 JDBC/Telegram/Spring 逆向依赖，默认 Context 无新副作用。

预计提交：`feat: 装配 Telegram SQLite 可靠投递`

## 14. Task F11：Loopback 重启纵向集成

状态：未开始。

新增 `TelegramReliableDeliveryIT`，使用真实：

- `JdkTelegramBotApi`
- Loopback `HttpServer`
- `JdbcChannelLedger`
- `MessageTurnService`
- 临时 `sessions.db` 与 `channel-ledger.db`

场景：

- 普通 Turn、会话提交、终态落库、远端 Receipt、Cursor 恢复。
- 同 Update 重投/并发只调用 Agent 一次。
- 终态 Commit 后进程重启只恢复发送。
- 429 重启后按 Due Time同 Part 重试。
- 多分片部分成功 + Unknown 停止。
- 不同 Bot 实例隔离。

聚焦：

```bash
./mvnw -pl agent-bootstrap -am \
  -Dit.test=TelegramReliableDeliveryIT \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

预计提交：`test: 验收 Telegram 可靠投递纵向切片`

## 15. Task F12：故障矩阵、Compat、Runbook 演练

状态：未开始。

新增/扩展：

- `TelegramReliableDeliveryFailureIT`（`@Tag("failure")`）
- `ChannelReliableDeliveryGoldenTest`（`@Tag("compat")`）
- `ChannelLedgerRollbackIT`
- Backup/Restore Runbook 最终命令与预期

故障矩阵逐点注入：

- Claim Commit 前/后。
- Running Commit 前/后。
- Agent 完成后/Terminal Commit 前。
- Outbox Commit 后/Wake 前。
- Attempt Started 后/HTTP 前。
- HTTP 成功后/Result Commit 前。
- 每个分片边界、Recovery/Cleanup/Shutdown。

验证调用次数、持久状态、Cursor、Conversation Commit、Thread/Connection/Socket 为预期。

聚焦：

```bash
./mvnw -pl agent-bootstrap -am -Pfailure \
  -Dit.test=TelegramReliableDeliveryFailureIT,ChannelLedgerRollbackIT \
  -Dfailsafe.failIfNoSpecifiedTests=false verify

./mvnw -pl agent-bootstrap -am -Pcompat \
  -Dtest=ChannelReliableDeliveryGoldenTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

预计提交：`test: 固定渠道可靠投递故障与回退`

## 16. Task F13：最终门禁、文档、PR 与合并准备

状态：未开始。

先更新 Contract/Spec/ADR/计划状态、Fixture Manifest Hash、文档导航、R6 总计划、Roadmap、重写指南、能力矩阵、配置模板和 Runbook。记录实际证据，不预填测试数。

最终命令：

```bash
./mvnw --batch-mode --no-transfer-progress spotless:check
./mvnw --batch-mode --no-transfer-progress clean verify
./mvnw --batch-mode --no-transfer-progress -Pfailure verify
./mvnw --batch-mode --no-transfer-progress -Pcompat verify
./mvnw --batch-mode --no-transfer-progress -pl agent-kernel dependency:tree
git diff --check
```

最终审计：

- Git 无真实 Token、Bot/Chat/User/Message ID、用户正文、DB/WAL/SHM/Backup。
- 默认、Reliability Disabled、Telegram Disabled、CLI 和配置检查零新 DB/Worker/网络。
- 所有网络测试只访问 Loopback；无未 Join Worker、无界 Queue/Executor、Connection/Socket 泄漏。
- Kernel/Application 依赖方向正确；SQLite 只在 Adapter。
- 原始脏工作树和真实 Workspace 未变化。
- 文档只声明 At-Most-One Execution Boundary + Auditable Delivery，不声明 Exactly Once。

所有本地门禁和自审全绿后，才执行：

1. 推送 `agent/r6-reliable-delivery`。
2. 创建 Draft PR，标注“纯离线、真实 Telegram 未授权”。
3. 等待默认、`failure`、`compat` 远程 CI。
4. CI 修复只针对已定位根因；生产语义变化重新批准。
5. Review/CI 全绿后再请求/执行合并。

预计提交：`docs: 完成 R6.4 离线验收`

## 17. 暂停条件

- F0 尚未明确批准。
- 需要改变已批准表、字段、唯一键、状态或 Retry/Retention 上限。
- 需要真实 Workspace/数据库、Token、Telegram 网络、用户数据或部署。
- 需要保存入站正文、远端原始响应、Tool/MCP 数据或 Secret。
- 需要自动重跑 Running/Unknown Turn，或自动重发 Unknown Delivery。
- 需要多进程、Broker、新 Maven 模块、人工 Reconcile API 或 Dashboard。
- 需要修改 `sessions.db`、Memory Schema 或 Side Effect Ledger。
