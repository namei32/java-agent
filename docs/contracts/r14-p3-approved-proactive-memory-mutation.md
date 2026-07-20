# R14 P3 获批的 Java-native 主动记忆 Mutation 契约

- 阶段：R14 P3
- 状态：已实现为未接线的本地 Fake Capability；默认仍 `DISABLED`
- 日期：2026-07-19
- 关联 ADR：[ADR-0041：将 R14-P3 至 P5 限制为获批的本地 Fake Capability](../adr/0041-restrict-r14-p3-p5-to-approved-local-fakes.md)
- 前置：[R14 P2 本地主动候选与 Fake Delivery Preparation](r14-p2-local-proactive-preparation.md)、[Pending Operation Recovery Capability](pending-operation-recovery-capability.md)

> 本契约只授权临时 Java SQLite 或 Fake Port 上的 `proactive_memory_capture` 验证。它不访问 Python `memory2.db`、真实
> Workspace、真实 Embedding/Provider、生产 Memory DML 或任何后台自动运行；生产默认仍为 `DISABLED`。

## 1. 唯一获批 Mutation

P3 v1 只允许 `CAPTURE_FIXED_LOCAL_NOTE`：把 P2 已净化、仍有效且尚未被消费的 `FIXED_LOCAL` 候选，作为 Java-native
`NOTE` 的敏感输入创建或强化。它的 Scope 由 `proactive:<jobRef>:<targetHash>` 的内部 hash 派生；不使用、猜测或暴露
Chat Session。类型、Scope、来源、风险、Schema、审批摘要、幂等键和 Capsule 参数均不可由模型、Plugin、MCP 或配置
字符串覆盖。

所谓 Optimizer 在 P3 v1 只表示“候选先经过固定、无模型的预算/去重资格检查，再形成待审批 Mutation”；它不合并正文、
不重分类、不批量 supersede、不创建或删除 Tool 权限，也不启动 Scheduler。每一种未来 Mutation（合并、软失效、分类
调整或物理删除）必须单独增补本 Contract、Fixture、审批摘要、恢复和审计规则。

## 2. Approval、Capsule 与恢复

`proactive_memory_capture` 版本为 `r14-proactive-memory-v1`，风险为 `WRITE`。Producer 在候选原子 claim 后再次检查
Lease 与取消，创建独立 Operation、Approval、job/target-hash Anchor 与 AES-GCM Capsule。AAD 至少绑定 Operation Ref、
Approval ID/Fingerprint、Anchor、Job Ref、target hash 和 Anchor version。外部 Outcome 仅允许安全 operation ref 与状态；
不得渲染候选正文、Memory Scope、item ID、Embedding、Session、Approval、Capsule、异常或数据库路径。

Recovery 仅在 Capsule 认证通过、Approval 已批准且未过期、Anchor 仍为 `PENDING_APPROVAL`、唯一 Reservation 已获取时，
单次调用注入的 `ProactiveMemoryMutationPort`。Port、审计、Ledger 或写入不确定时必须固化 `UNKNOWN`；成功后 Anchor
提交未报告时为 `COMMIT_UNREPORTED`。两者均不可重放。取消、过期、竞争失败、篡改、关闭和未批准均为零 Port 调用。

## 3. Java-native Port 与安全结果

Port 只接收经认证的 `NOTE`、hash-only Scope、内部 Operation Key、固定 Fake Embedding 标识和净化正文；它不接收模型、
URL、路径、Session、任意 SQL、Provider 响应或外部请求。安全 Receipt 只允许 `CREATED` 或 `REINFORCED`，不得包含 item
ID、正文、向量、Scope 或 DB 细节。临时 Java SQLite 验证可以使用既有 Java-native Memory Schema，但不得注册生产 Adapter
或读取任何用户库。

## 4. 实现证据、验收与禁止项

实现包含 `r14-proactive-memory-mutation-v1` 的 7 个版本化 Fixture 场景、候选单次 claim、独立
Pending/Approval/Anchor/AES-GCM Capsule，以及只注入 `ProactiveMemoryMutationPort` 的显式恢复协调器。恢复测试覆盖
批准、未批准、取消、过期、Capsule 篡改、并发单获胜者、Port/审计/Ledger/Anchor 失败、`UNKNOWN`、
`COMMIT_UNREPORTED` 与零重放。Port 目前只由测试 Fake 实现；没有 Java SQLite Adapter、Memory DML 或 Bootstrap 接线。

版本化 Fixture 必须覆盖：空/过期候选、取消、唯一创建、并发单获胜者、Store 失败安全重试、Capsule 脱敏、未批准、
取消、到期、认证失败、Port/审计/提交失败、`UNKNOWN`、`COMMIT_UNREPORTED` 与零重放。测试只使用固定 Clock、Fake
Embedding、Fake/临时 Java SQLite 和本地数据。

P3 不增加 Bootstrap 配置、Bean、Route、Worker、Scheduler、真实 Memory API、Tool 执行、网络、渠道、日志正文或生产
数据访问。真正的自动提取、模型摘要、Optimizer 合并、真实 Embedding 与生产 Memory DML 继续需要新授权。
