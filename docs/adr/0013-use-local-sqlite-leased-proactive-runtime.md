# ADR-0013：使用本地 SQLite 租约主动运行时

- 状态：已接受
- 日期：2026-07-18
- 阶段：R8

## 决策

Scheduler 状态、计划、Claim、尝试与终态使用项目拥有的 SQLite Schema；执行只在单 JVM 的有界 Virtual Thread
Executor 中进行。租约用于崩溃后的可证明恢复，而不是宣称跨节点 Exactly Once。

## 原因与后果

这与现有 SQLite、Claim/Outbox、取消和关闭模型保持一致，不引入 Kafka、Redis、Quartz 或集群锁。代价是
V1 不支持多节点、cron/时区复杂语义和无边界后台任务；任何实际推送仍经过既有 Channel/Delivery 契约。
