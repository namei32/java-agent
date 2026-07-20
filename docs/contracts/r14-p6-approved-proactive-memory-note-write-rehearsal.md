# R14 P6 获批的 Java-native NOTE 写入演练契约

- 阶段：R14 P6
- 状态：已实现并通过阶段三套完整门禁（仅本地 `@TempDir` 演练）
- 日期：2026-07-20
- Capability：`proactive_memory_note_write` / `r14-proactive-memory-note-v1` / `WRITE`
- 关联 ADR：[ADR-0042](../adr/0042-restrict-r14-p6-to-temp-sqlite-note-rehearsal.md)
- 前置：P2 本地候选、P3 的逐 Mutation 安全闭环，以及现有 Java-native Memory V2 Schema/`JdbcJavaMemoryStore`

> P6 授权的是**临时 Java SQLite 测试中的真实 DML 演练**，不是生产自动记忆授权。默认运行时仍为 `DISABLED`，不访问
> Python `memory2.db`、真实 Workspace、真实 Embedding/Provider、网络或渠道。

## 1. 唯一 Mutation、Scope 与保留期

只允许一个已净化、仍有效、未被消费的 `FIXED_LOCAL` 候选写成 `NOTE`，结果仅能是 `CREATED` 或 `REINFORCED`。正文、
类型、情绪权重（固定 `0`）、发生时间、Scope、Embedding Model、Item ID、请求 ID、风险和版本均不能由模型、Prompt、
Plugin、MCP、配置或外部输入覆盖。

Scope 的唯一 preimage 是 Capsule 内部的 `r14-p6-note:<jobRef>:<targetHash>`；专用 Capability 先在内存中将其做
SHA-256 绑定，再以该 Binding 构造 `MemoryScope`，只让既有 Store 保存 Binding。它不接收 Chat Session，也不把 preimage、Binding 或原始
Job/Target 置入公开 Outcome、审计、日志、Tool Result 或异常。

P6 的唯一允许数据库位于 JUnit `@TempDir` 下的 Java-owned `memory/agent-memory.db`。每个测试关闭所有连接并删除目录；
保留期严格等于该测试生命周期。没有 P6 配置键、Workspace 路径、环境变量或运行时数据库选择，因此零生产保留权限。

## 2. 审批、Capsule、幂等与恢复

Producer 必须在原子 claim、Lease 与取消检查后创建独立 P6 Pending、Approval、Anchor 和 AES-GCM Capsule。Approval 摘要
不含正文、Scope、Item ID 或数据库路径；AAD 至少绑定 Operation Ref、Approval ID/Fingerprint、Anchor、Job/Target
绑定及 Anchor Version。任何认证失败、未批准、到期、取消、关闭或并发落败均为零 SQLite DML、零 Fake Embedding 调用。

`requestId` 固定从 P6 Operation Ref 派生；参数哈希必须使用现有 Java Memory V1 规范（`UPSERT`、`NOTE`、规范化
Content Hash、固定情绪权重和固定 happenedAt）。恢复先以 `(MemoryScope, requestId, argumentHash)` 查询既有 Mutation
Ledger：已有相同记录时仅返回其安全 `CREATED`/`REINFORCED` Receipt，零 Embedding、零强化；冲突则 Fail Closed。

首次写入只可通过专用 Capability 调用 `MemoryWritePort`/`JdbcJavaMemoryStore` 的受信事务。DML 抛出或提交结果无法确定时，
P6 Operation 固化为 `UNKNOWN`，不得自动再次写入；Anchor 提交未报告时为 `COMMIT_UNREPORTED`，也不得重放。Operator
只能在后续独立 Contract 中执行人工核对，P6 不提供 Route、Worker 或自动协调器。

## 3. SQLite、Embedding 与回退演练

- 只能调用既有 `JavaMemorySchemaInitializer`、`JdbcJavaMemoryStore` 和 `MemoryWritePort`；不得复制 SQL、修改 Schema、
  操作 `memory2.db` 或用户库。
- P6 采用确定性 Fake Embedding；不得实例化 Spring AI、读取 Provider 配置、发送网络请求或产生费用。
- 集成测试必须验证 Mutation Ledger 与 `memory_items` 在同一事务中提交；在受控 Store 失败时两者均回滚。
- 演练结束时删除临时目录；测试还须证明不残留正文、向量、Scope Binding、Capsule 或数据库文件。

既有 `MemoryWriteService` 只能服务显式 Session API，并固定 `EXPLICIT_API` Source；P6 必须建立专用 Application
Capability，不能伪造 Session ID 或篡改 Source 以复用该服务。

## 4. 验收 Fixture 与禁止项

`r14-proactive-memory-note-write-v1` 至少覆盖 30 个版本化场景：Disabled 零 I/O、唯一候选、拒绝/到期/取消、Capsule
篡改、Scope 脱敏、`CREATED`、`REINFORCED`、同 Operation 重放、请求冲突、并发单获胜者、Embedding 失败、SQLite
事务回滚、DML 不确定、Audit/Ledger/Anchor 失败、`UNKNOWN`、`COMMIT_UNREPORTED`、临时文件删除和零自动重放。

P6 不增加 Bootstrap、Spring Bean、配置、Tool Catalog、HTTP/CLI/控制面、Scheduler、Worker、真实 Embedding、真实
Workspace、用户/Python 数据、网络、Peer、渠道、前端或生产 DML。自动摘要、合并、分类、删除、Optimizer、跨 Scope
检索和长期保留仍需要各自的新 Capability Contract。
