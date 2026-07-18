# ADR-0018：待审批操作使用单一隔离事务存储

- 状态：已采纳；创建、消费与 Reservation 的无执行存储部分已实施，恢复编排待实施
- 日期：2026-07-18
- 决策：R11 B2b Pending Operation

## 背景

B2a 的 `approval-inbox.db` 只能保存人工的一次性决定。若把恢复参数、Approval 消费和 Side Effect Ledger 放在不同 SQLite 文件，批准、取消、崩溃与消费之间会出现不可证明的一致性窗口；把它们塞进 `sessions.db` 又会把 Tool 安全边界与 Conversation 持久化耦合。

## 决策

将 `approval-inbox.db` 在 B2b 演进为 Schema v2 的唯一隔离 Tool Operation Store。Approval Inbox、Pending Operation、加密 Capsule、Approval `CONSUMED`、Ledger Reservation/状态和稳定审计使用同一 SQLite 连接与事务更新。`sessions.db` 保持独立，只接受基于 `nextSequence` 的条件 Conversation 提交。

执行成功但 Conversation 条件提交失败时，Store 记录 `COMMIT_UNREPORTED`，不再次执行副作用。进程重启不自动执行 Operation。

## 后果

- 可以原子地证明批准的一次性消费与 Ledger `RESERVED`，避免跨库双写猜测。
- 必须实现从 B2a Schema v1 到 v2 的显式、可回滚前检查迁移；迁移失败时启动失败，不得删除 B2a 历史记录。
- 无法得到 Tool Store 与 Conversation 的全局事务；因此恢复优先保证 Side Effect 不重放，Conversation 采用条件、最多一次提交。
- 不改变 B2a 的默认关闭、Loopback 限制或零执行结论；任何具体 Tool 仍需独立 Capability Contract。
