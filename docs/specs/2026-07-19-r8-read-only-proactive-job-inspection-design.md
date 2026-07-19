# R8 本地 Proactive Job 只读检视设计

- 状态：已实现并验证
- 日期：2026-07-19
- 关联契约：[本地 Proactive Job 只读检视契约](../contracts/read-only-local-proactive-job-inspection.md)
- 关联 ADR：[ADR-0034](../adr/0034-expose-only-active-hash-safe-proactive-job-inspection.md)

## 设计决策

不把枚举能力加到 `ProactiveJobStore` 并返回 `ScheduledJob`。`ScheduledJob` 有 `targetHash` 与 `idempotencyKey`，它适合
调度事务，不能作为模型 Tool 的读取 DTO。新增独立 Kernel `ProactiveJobInspectionPort` 与
`ProactiveJobInspectionSnapshot`，由 `JdbcProactiveJobStore` 同时实现；其 SQL 只选择安全列并限定活动状态。

`ProactiveRuntime` 在成功启动现有 Scheduler 后持有该 Port 的只读引用，并以 `Optional` 暴露。Disabled Runtime 保持
`Optional.empty()`，因此不会解析 workspace 或创建数据库。Bootstrap 只从已构建的 Runtime 取得 Port，绝不自行构造
Schema 或 Store。

Application 的 `ProactiveJobInspectionToolset` 是普通静态 `Tool`，不依赖 Turn/Session/Memory Context。它严格解析
`limit`，调用 Port，复核条数、活动状态和稳定排序，再由 `ToolCatalogJson` 生成最小 JSON。Tool Catalog 将其作为
Deferred Builtin，关键词是“计划、调度、proactive、任务”。R8 Store 的周期原始单位是毫秒；为了保持 Tool 的
`every_seconds` 不丢失精度，非正整秒周期一律安全失败。

## 分层与错误处理

```text
ToolCatalog (Deferred)
  -> ProactiveJobInspectionToolset (parse / output budget / safe result)
    -> ProactiveJobInspectionPort (Kernel safe DTO)
      -> JdbcProactiveJobStore (active-only SQL projection)
        -> 已启动的 ProactiveRuntime
```

Tool 参数错误映射到 `PROACTIVE_JOB_INSPECTION_INVALID_ARGUMENT`；Port、SQLite、模型不变量或 Runtime 缺失一律映射到
`PROACTIVE_JOB_INSPECTION_UNAVAILABLE`。错误文本不能跨过 Tool 边界。没有可用 Port 时不会退化为打开数据库或返回
全量历史。

## 测试策略

先以缺失 Kernel DTO/Port、SQLite list API、Toolset 和 Bootstrap Properties 形成可执行 RED；随后按 Kernel → SQLite →
Application → Bootstrap 顺序最小实现并运行同一聚焦命令 GREEN。Fixture 由生产 Contract 消费，不只是解析 JSON。
并发、Claim、Recovery 继续由 R8 既有测试覆盖；本切片不复制其矩阵，只验证查询不返回终态、排序和安全投影。实现已通过
默认、`failure`、`compat` 三套阶段门禁。
