# ADR-0042：将 R14-P6 限制为临时 SQLite 的获批 NOTE 写入演练

- 状态：已接受（仅本地演练）
- 日期：2026-07-20
- 决策人：项目用户
- 关联：[R14-P3 Fake Memory Mutation](../contracts/r14-p3-approved-proactive-memory-mutation.md)、[Java 原生语义记忆契约](../contracts/semantic-memory-persistence-optimizer.md)

## 背景

P3 已证明“逐 Mutation 审批、Capsule、Reservation、`UNKNOWN`、Anchor”可以把一个固定 `NOTE` 交给 Fake Port；它特意
不调用 Java Memory SQLite。直接把该 Port 接到生产 `JdbcJavaMemoryStore` 会同时扩大数据保留、运行时装配、Embedding、
自动执行与回退权限，不能由 P3 推定。

## 决策

R14-P6 唯一选择 `proactive_memory_note_write`（`r14-proactive-memory-note-v1`，`WRITE`）作为下一项能力。它只允许在
JUnit 临时目录创建的 Java-owned `agent-memory.db` 上，使用确定性 Fake Embedding 演练单个获批 `FIXED_LOCAL` `NOTE`
的真实 SQLite DML 和恢复语义。

P6 不复用 P3 的 Capability 名称、Operation 或“Fake 成功”来声明生产权限。它建立独立的 Approval、Capsule、Anchor、
Idempotency Key 和恢复状态；Scope 只由 Capsule 内的 Job/Target 派生，永不使用或暴露用户 Session。临时数据库在每个
测试结束时关闭并删除，因此 P6 的保留期仅为测试生命周期。

## 后果

- 必须以新的版本化 Fixture、RED/GREEN、临时 Java SQLite 集成测试证明 `CREATED`、`REINFORCED`、幂等、事务回滚、
  `UNKNOWN` 和零重放。
- 必须使用现有 `JdbcJavaMemoryStore` 的受信事务与 Mutation Ledger；不得复制 SQL、直接改表或读取 Python/用户数据库。
- 既有 `MemoryWriteService` 的 `sessionId` 输入及 `EXPLICIT_API` Source 不得被悄悄复用于 P6；P6 需要专用的、
  无 Session 的 Application Capability。
- 不增加 Bootstrap Bean/配置、Tool Schema、Worker、Scheduler、HTTP/CLI Route、网络、真实 Embedding、真实
  Workspace 或生产数据写入。生产启用、长期保留和自动运行另立后续 Contract。
