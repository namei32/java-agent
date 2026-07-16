# R6.4 渠道可靠投递、幂等与恢复工作计划

- 状态：F1–F13 离线实现与高风险 Review 修复已完成，本地三套门禁全绿；进入 PR 发布门禁
- 发布顺序：修复提交远程 CI -> PR Ready -> 明确批准 -> 合并与主分支复验
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

状态：已完成。

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

RED/GREEN 证据（2026-07-16）：聚焦命令首次执行 8 个测试时，除占位异常恰好满足的回滚断言外，
其余场景均因 Feedback、Terminal、Claim 和 Outcome 尚未实现而失败；随后把 Delivery JDBC 逻辑从
Inbox/Claim 骨架中分离，补齐 Header/连续 Part、关联键与指纹重放、`DELIVERING + IN_FLIGHT +
STARTED` 原子领取，以及四类结果提交。自审为两个 Commit Fault 增加实际 Fault Point 命中计数，
避免占位异常造成假 GREEN；并增加同 Feedback Event 不同 Payload 的冲突证据。最终 8 个聚焦测试
全部通过：两 Worker 只有一个领取成功，成功分片严格串行且 Receipt 重放幂等，429 只在持久 Due
Time 后进行第二次总尝试，第二次 429 转 `RETRY_BUDGET_EXCEEDED`，Permanent/Unknown 只终止各自
Delivery；Owner 不匹配和远端成功后的 SQLite Commit Fault 均不能伪造 Receipt，Attempt 保持
`STARTED` 等待恢复。

## 9. Task F6：恢复、清理、容量与 SQLite 阶段门禁

状态：已完成。

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

RED/GREEN 证据（2026-07-16）：聚焦 failure 测试先固定五类恢复、清理、容量和事务故障场景；
未实现时 Delivery 恢复数量、清理结果和 Fault Point 断言失败。最小实现把 Claim 与 Delivery 的旧
Owner 恢复放在同一有界事务中：未跨发送边界的 Pending/Due Retry 保留，`DELIVERING +
IN_FLIGHT + STARTED` 只转为 `UNKNOWN`，不做隐式重试；已解决 Delivery 只收缩 Payload/Part/
Attempt，保留 Header、Claim Tombstone 和 Unknown。清理只删除 Cursor 以下、无 Claim 关联且早于
Cutoff 的 Event，容量只统计未收缩或未解决的 Delivery。恢复和清理提交前 Fault 均证明整笔回滚。

首次 GREEN 过程中，新增 `payload_pruned` 读取路径暴露两处 SQL 列清单不同步，定向测试分别以
“值数量不匹配”和“结果集缺列”稳定复现后修复；自审又增加“终态 Payload 已收缩仍可凭 Header
指纹和 Claim Tombstone 幂等重放”的保护场景。最终聚焦 failure 命令执行 6 个测试全部通过；F2–
F5 默认 Profile 联合回归 36 个测试、F6 failure Profile 6 个测试均通过。

阶段门禁证据（2026-07-16）：`spotless:check` 对八模块通过；`-pl adapter-sqlite -am verify`
执行 Kernel 51 个、SQLite 76 个测试全部通过；全 Reactor `clean verify` 执行 505 个测试全部通过；
全 Reactor `-Pfailure verify` 执行 125 个故障场景全部通过。默认与 failure Profile 均已显式执行，
所有新增 SQLite 文件均位于 JUnit `@TempDir`，没有访问真实 Workspace、Telegram、Secret 或外网。

## 10. Task F7：Application 入站协调器

状态：已完成。

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

RED/GREEN 证据（2026-07-16）：聚焦命令首次在 Application 测试编译阶段因
`ReliableInboundCoordinator`、稳定 Ledger Failure 分类、事件/结果值、Clock/Owner/ID 和受控
Starter/Turn 窄接口缺失而失败。最小实现后，同一命令执行 6 个测试全部通过：已映射事件先持久
Event/Claim，Worker 只有在 `startTurn` 返回 `STARTED` 后才调用 Turn；In Progress、Terminal、
Unknown 和 Stale 均零模型/Tool/MCP 边界调用；Starter 明确失败无条件释放 Permit/Active Map，并经
恢复后复用持久原 Turn ID。Busy 与无活动取消只使用构造时冻结的固定分片，Ignored/Control 不保存
正文；目标 Cancellation 只在 Event Commit 返回后触发。

自审新增跨实例同 Session 控制回归，先稳定复现另一 Bot 实例错误得到 `CONTROL_APPLIED`，再把活动
键改为 `ChannelInstanceId + sessionId` 后转绿；同时把 ID Provider 失败纳入 Permit 无条件释放边界。
Kernel 的 `ChannelLedgerFailureCarrier` 让 Application 在不依赖 SQLite 的前提下映射 unavailable、
conflict、capacity 和 stale，SQLite Repository Exception 已实现该投影。最终聚焦测试 6/6、相关
三模块 Spotless、SQLite 编译与 `git diff --check` 均通过；未运行非阶段性的完整 Reactor。

## 11. Task F8：Durable Terminal 与 Delivery Coordinator

状态：已完成。

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

RED/GREEN 证据（2026-07-16）：三个聚焦测试首次执行在 Application 测试编译阶段以 66 个缺失
符号错误失败，证明 Durable Terminal、Transport Result、Delivery Coordinator 和 Worker 尚未
存在。最小实现后，同一聚焦命令执行 11 个测试全部通过：Started/Delta 只进入易失本地流，Terminal
按稳定指纹先写 Outbox 再 Wake/通知；Commit 失败不触发 Wake，重复相同终态重放，不同终态稳定
冲突。Delivery 在领取事务返回后才调用网络，并把 Confirmed、合法 429、Permanent、Unknown 和
Transport 异常分别投影到固定 Attempt Outcome；Retry-After 超限 Fail Closed，总尝试预算以账本
结果为准。

Worker 每次启动先扫描权威账本，使用 Signal Version + Condition 防止“空扫描到等待”窗口丢 Wake，
失败或 Unknown Delivery 不阻塞后续工作，关闭会中断并在配置期限内 Join。自审发现 Batch 恰好耗尽
时可能丢失已知 Retry Due，以及亚毫秒关闭期限可能退化为无限 `join(0)`；实现已跨批保留最早 Due、
显式响应线程中断并保证 Join 至少 1ms。F7/F8 联合回归执行 17 个测试全部通过；相关模块
`verify` 执行 Kernel 51 个、Application 152 个测试全部通过，Spotless 同步通过。未运行非阶段性的
全 Reactor 门禁，未访问 Telegram、Secret、真实 Workspace 或外网。

## 12. Task F9：Telegram Receipt 与发送确定性分类

状态：已完成。

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

RED/GREEN 证据（2026-07-16）：聚焦命令首次在 Bootstrap 测试编译阶段以 20 个错误失败，缺失
`TelegramSendReceipt`、`TelegramDeliveryTransport`、永久拒绝分类和纯 Terminal Projector。
实现后，Transport/Renderer 9 个单元测试与 JDK Loopback Client 27 个集成测试全部通过：只有
`ok=true` 且正整数 `message_id` 生成脱敏 Receipt；401/403/404 和明确 4xx 转 Permanent，显式
429 只返回 Retry-After，不在 Transport 内重试；Timeout、I/O、5xx、中断、响应超限/损坏和缺失
Receipt 均为 Unknown，异常不保留 Cause、Token、URI、Body 或 Description。

`TelegramTerminalRenderer` 已收缩为确定性的终态文本/分片 Projector，旧
`TelegramDeliveryPolicy` 被删除。易失兼容路径也只发送一次，429 不再 Sleep/重试，避免与持久
Coordinator 形成双重预算。Bootstrap Reactor 回归执行上游 Kernel 51、Application 152、SQLite
76、Spring AI 35、MCP 32 和 Bootstrap 147 个单元测试均通过；首次执行只剩旧
`TelegramChannelIT` 的“429 后内存重试”预期失败，迁移为单次发送后，JDK Client + Channel IT
31 个集成测试全部通过。Compat Telegram Golden Fixture 25 个场景也全部通过。所有 HTTP 都使用
Loopback Stub，没有访问真实 Telegram、Token、Workspace 或外网。

## 13. Task F10：Telegram 持久纵向装配与第二阶段门禁

状态：已完成。

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

RED/GREEN 证据（2026-07-16）：三个聚焦测试首次在 Bootstrap 测试编译阶段以 29 个缺失符号
错误失败，证明可靠 Adapter、运行时、配置、Instance 派生和健康投影尚未存在。最小实现后，同一
聚焦命令执行 13 个测试全部通过：Instance 只使用 Bot ID 派生，Token 轮换保持分区、不同 Bot
隔离；Schema 初始化、完整恢复和持久 offset 均先于 Delivery Worker 与首次 Poll；Accepted、Busy、
Ignored、Control 先写账本，再越过执行、反馈或取消边界；权威终态先进入事务 Outbox，Attempt
`STARTED` 先于 Loopback/Fake 网络调用。显式 `SQLITE` 只装配一个可靠 Adapter 与一个 Delivery
Worker；默认 `DISABLED`、Telegram Disabled 和 CLI 路径不创建目录、数据库、Worker 或网络副作用。

自审补齐了启动失败健康保真和统一关闭预算：`ChannelHost` 仅在 Adapter 已给出合法 `FAILED`
快照时保留其稳定账本状态，否则继续使用通用 `START_FAILED`；Poll、活动 Turn 和 Delivery Worker
分别使用总关闭期限的 1/4、1/2、1/4。可靠入站协调器停止后拒绝新事件，取消并 Join 已登记 Turn，
启动前取消的任务不会跨越 `RUNNING + Cursor` 执行边界，Permit 与活动映射最终释放。SQLite 初始化
失败只使 Telegram 暴露 `CHANNEL_LEDGER_UNAVAILABLE`，Servlet Context 仍健康且零网络访问。

阶段门禁证据（2026-07-16）：`spotless:check` 对八模块通过；`-pl agent-bootstrap -am verify` 和
全 Reactor `clean verify` 均执行 544 个测试全部通过，其中 Kernel 51、Application 153、Workspace
4、SQLite 76、Spring AI 35、MCP 32、Bootstrap 160 个单元测试与 33 个集成测试。Kernel
`dependency:tree` 只含 JUnit、AssertJ、Byte Buddy 和 Jackson 测试依赖；源码审计未发现
Kernel/Application 对 JDBC、Spring 或 Telegram 的反向依赖。所有新增 SQLite 与网络测试只使用
JUnit 临时目录和 Fake/Loopback 边界；未访问真实 Workspace、Telegram、Secret、用户数据或外网，
仓库中无 DB/WAL/SHM/Backup 产物。

## 14. Task F11：Loopback 重启纵向集成

状态：已完成。

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

验收证据（2026-07-16）：本 Task 只新增纵向集成 Fixture，不为制造 RED 改坏已完成的生产实现。
首次聚焦命令中的 6 个行为场景即全部通过，命令最终只因新测试尚未经过 Spotless 而失败；格式化并
增加 Worker 释放断言后，同一命令完整通过。测试使用真实 `JdkTelegramBotApi`、Loopback
`HttpServer`、`JdbcSessionRepository`、`JdbcChannelLedger` 路径和 `MessageTurnService`，所有
`sessions.db` 与 `channel-ledger.db` 均位于 JUnit `@TempDir`。

六个场景证明：普通 Turn 只提交一轮会话、唯一终态、远端 Receipt 与持久 Cursor，Token 轮换后
首次 Poll 从该 Cursor 恢复且不再次调用 Agent；两个同时释放的相同 Update 只跨越一次 Agent
执行边界；Outbox Commit 后、Delivery Worker 启动前的重启只恢复发送；429 的绝对 Due Time 与
同一 Part Payload 跨重启保留；多分片第一片成功、第二片 Unknown 后停止且重启不重发；不同 Bot
ID 在同一数据库中的 Cursor、Claim 和 Delivery 完全隔离。每个场景结束均验证 Poll、Turn、
Delivery 和 Loopback Worker 已释放；HTTP 仅访问 `127.0.0.1`，使用固定假 Token/ID/正文，没有
读取真实 Workspace、Secret、用户数据或外网。

## 15. Task F12：故障矩阵、Compat、Runbook 演练

状态：已完成。

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

验收证据（2026-07-16）：`TelegramReliableDeliveryFailureIT` 的 8 个确定性场景全部通过：Claim
Commit 前由 SQLite Trigger 证明 Claim/Event/Cursor 整体回滚且 Agent 零调用；Claim Commit 后、
Turn Thread 启动前的重启恢复同一 Claim 并只执行一次；Running Commit 前证明 Cursor 与 Running
状态同事务回滚，重启后只执行一次；Running Commit 后在受控阻塞 Agent 完成前即可观察持久
Running/Cursor；Agent 与 Conversation 完成后、Terminal Commit 前失败时保留会话，重启只转
`EXECUTION_UNKNOWN`，不重跑 Agent；Attempt `STARTED` 后、HTTP 前模拟进程崩溃时 HTTP 零调用，
重启只转 `UNKNOWN`；第二分片 HTTP 成功后、Result Commit 前失败时第一片保持已确认、第二片转
`UNKNOWN`，两片均不重发；关闭会在预算内中断已跨 Running 边界的 Agent，并释放 Poll/Turn/
Delivery/Loopback Worker。

F11 的 Held Delivery Worker 继续覆盖 Outbox Commit 后/Wake 前窗口；
`ChannelLedgerRecoveryFailureTest` 覆盖 Recovery/Cleanup Commit 故障的整事务回滚。failure 聚焦命令
完整通过，新增 Failsafe 场景为 Telegram 8 项、Rollback 1 项；所有 HTTP 只访问 `127.0.0.1`。

`ChannelLedgerRollbackIT` 使用 `@TempDir` 完成 V0 Online Backup、V1 Migration、两次 Staging 校验
和同文件系统原子恢复、Quarantine 保留、Backup Hash 不变及恢复后重新迁移。Runbook 已补最终停机
命令、预期和停止条件。`ChannelReliableDeliveryGoldenTest @Tag("compat")` 完整消费 Java-owned V1
Fixture 的 40 个场景，并增加 Telegram Instance/Token 轮换、Terminal Renderer 与证据路径的 2 个
跨层约束；Compat 聚焦命令共 42 项全绿，Fixture 与 Manifest Hash 未变化。

## 16. Task F13：最终门禁、文档、PR 与合并准备

状态：离线实现和高风险 Review 修复已完成；修复提交须重新通过远程三套门禁后才能转为
Ready，合并仍需明确批准。

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

本地验收证据（2026-07-16）：

- `spotless:check` 对八模块 Reactor 全绿。
- 首次执行完整 `clean verify` 时，`TelegramReliableDeliveryIT` 在持久 Cursor 已提交、Poll Worker
  尚未刷新适配器内存快照的短窗口内立即读取 `nextOffset`，暴露纯测试观察竞态。测试改为有界等待
  适配器观察到持久 Cursor；聚焦 `TelegramReliableDeliveryIT` 6 项全绿，随后原样重跑
  `clean verify`，默认 Profile 共 559 项全绿。生产状态机、事务边界和重试语义均未改变。
- `-Pfailure verify` 共 133 项全绿；`-Pcompat verify` 共 665 项全绿，无失败、错误或跳过。
  其中 `ChannelReliableDeliveryGoldenTest` 完整执行 42 项，覆盖 V1 Fixture 的 40 个 Java-owned
  场景与 2 个跨层约束。
- `agent-kernel dependency:tree` 全绿；Kernel 只有 JUnit、AssertJ、Byte Buddy 和 Jackson 测试
  依赖，Kernel/Application 生产源码中没有 JDBC、Spring 或 Telegram 反向依赖。
- `git diff --check` 通过；默认配置仍为 Reliability `DISABLED`、Telegram `false`。仓库未跟踪
  DB/WAL/SHM/Backup；分支新增的 Token 形态字符串均是测试中的显式 Fake Token 或“Secret 不得
  进入实例键”拒绝样例，没有真实凭据、用户正文或非 Loopback Endpoint。
- `channel-reliable-delivery-v1.json` SHA-256 为
  `f21efc2426c18af880239bb41e81deb142c0eab070d8eb89b034237ce7071399`，与 Manifest 一致。
- 原始脏工作树 `/Users/namei/idea/agent/java-agent` 未被本计划修改；所有数据库和 HTTP 验证继续
  位于 JUnit 临时目录与 `127.0.0.1` Loopback 范围。

远程验收证据（2026-07-16）：PR #7 首轮的 `failure` 与 `compat` 通过；默认 Job 在
`ChannelDeliveryWorkerTest.failedAndUnknownDeliveriesDoNotBlockLaterWork` 暴露测试观察竞态：
`DELIVERED` Latch 在第 3 次调用内释放，断言线程可能早于 Worker 的第 4 次 EMPTY 扫描读取调用数。
经用户明确批准，只为测试增加“EMPTY 已扫描”的确定性 Latch，没有修改生产 Worker、状态机或重试
语义。聚焦测试 4 项、本地默认 559 项、`failure` 133 项和 `compat` 665 项重新全绿；修复提交
`5eac80a` 触发的远程 Run `29503828149` 中，“默认构建”“失败路径”“Python/Java 兼容”三项均
通过。

高风险 Review 修复与本地复验证据（2026-07-16）：

- 修复 V0 Backup 目标文件冲突时误删既有文件：初始化器先用原子 `createFile` 占有目标，只清理由
  本次尝试创建的文件；确定性 RED/GREEN 测试证明既有哨兵文件保持原内容，Backup Port 不会被调用。
- 修复 SQLite 将不同精度的 ISO-8601 文本按字典序比较造成的同秒时间倒序：Due、Retention 与
  Recovery 的比较和排序统一使用 SQLite 时间值；测试覆盖整秒与 `+100ms` 的 Retry Claim、投递
  先后和清理边界。
- 修复 JDBC `getLong` 将 SQLite `REAL 0.5` 静默截断为合法计数：账本只接受 SQLite 返回的整数
  Java 类型，严格拒绝小数、文本、空值和负数；`payload_pruned` 进一步限定为 `0/1`。
- 修复 Worker 关闭超时时提前释放 Runtime Session：只有 Poll、Turn 与 Delivery Worker 全部停止
  后才释放 Session；测试以不可中断的受控发送证明，同一 Runtime 在旧 Worker 存活时不能重开。
- 修复后 `spotless:check`、默认 `clean verify` 565 项、`-Pfailure verify` 134 项、
  `-Pcompat verify` 671 项全部通过，零失败、错误或跳过；Kernel 依赖树、`git diff --check`、默认
  禁用、敏感信息、数据库产物和反向依赖审计均通过。
- 40 场景 Fixture 未修改，SHA-256 仍为
  `f21efc2426c18af880239bb41e81deb142c0eab070d8eb89b034237ce7071399` 并与 Manifest 一致；本轮
  没有改变已批准的 Schema、状态、重试上限或真实网络边界。

当前结论仅为“R6.4 离线实现和 Review 修复已通过本地自动化验证，修复前 Head 已通过远程
自动化验证”。Review 修复 Head 仍须重新通过远程门禁，之后才能把 PR #7 转为 Ready；合并仍需
明确批准。真实 Telegram Token、网络 Smoke、用户数据与部署仍未获授权，因此本阶段不声明已合并
或已上线。

## 17. 暂停条件

- F0 尚未明确批准。
- 需要改变已批准表、字段、唯一键、状态或 Retry/Retention 上限。
- 需要真实 Workspace/数据库、Token、Telegram 网络、用户数据或部署。
- 需要保存入站正文、远端原始响应、Tool/MCP 数据或 Secret。
- 需要自动重跑 Running/Unknown Turn，或自动重发 Unknown Delivery。
- 需要多进程、Broker、新 Maven 模块、人工 Reconcile API 或 Dashboard。
- 需要修改 `sessions.db`、Memory Schema 或 Side Effect Ledger。
