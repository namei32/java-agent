# ADR-0034：仅暴露活动且 hash-safe 的本地 Proactive Job 检视

- 状态：已接受
- 日期：2026-07-19
- 阶段：R8 后续安全可观测性切片

## 背景

Python 有 `list_schedules`，但 Java R8 `ScheduledJob` 内含 `targetHash`、`idempotencyKey` 等调度内部字段；直接把
Store 或 Job 交给 Tool 会使未来字段扩张成为信息泄露路径。

## 决策

新增仅含安全字段的 Kernel `ProactiveJobInspectionPort` 和 Snapshot。SQLite 只返回活动 Job，Application 仅通过默认关闭的
Deferred `READ_ONLY` Tool 输出该 Snapshot。Bootstrap 只复用已启动的 `ProactiveRuntime`，从不为检视启动 Scheduler 或
创建数据库。

## 后果

这提供最小本地可观察性，却不等价于 Python `list_schedules`，也不能支持创建、取消或投递。Tool 的缺失、Port 故障和
不安全的返回都必须失败关闭；完整历史、内容、身份、hash/key、租约和路径保持不可见。
