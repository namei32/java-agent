# R8 本地 Proactive Job 只读检视实施计划

- 状态：I1–I5 已完成并验证
- 日期：2026-07-19
- 前置：[R8 主动运行时契约](../contracts/proactive-runtime.md)、[只读检视契约](../contracts/read-only-local-proactive-job-inspection.md)

## 任务与完成标准

1. **I1 Fixture 与 Kernel Contract。** 新增 Java-owned Fixture、Manifest 条目、`ProactiveJobInspectionSnapshot` 和
   `ProactiveJobInspectionPort`；Fixture 由生产 Kernel 值对象消费，固定活动状态及安全字段。
   状态：已完成；Kernel RED/GREEN 与 Fixture 消费通过。
2. **I2 SQLite 安全投影。** 先为 `JdbcProactiveJobStore` 添加只查活动状态、稳定排序、受限 limit 的 RED；实现独立 Port
   且不读取 hash/key。
   状态：已完成；SQLite RED/GREEN 与活动状态安全投影通过。
3. **I3 Application Tool。** 先为严格参数、结果预算、端口错误与敏感字段不泄露添加 RED；实现 Deferred `READ_ONLY`
   `list_local_proactive_jobs`。
   状态：已完成；Deferred Tool RED/GREEN、严格参数和安全失败投影通过。
4. **I4 Bootstrap 默认关闭。** 先验证严格 Properties、Disabled 零注册、活动 Runtime 接线和不活动 fail-closed；随后
   接入 Tool Catalog。
   状态：已完成；严格 Properties、默认关闭、Active Runtime 接线和 Spring 注入通过。
5. **I5 阶段验证与文档。** 审查 SQL 投影、禁止 API、Fixture SHA、文档、架构依赖与工作树；执行默认、`failure`、`compat`
   三套门禁。
   状态：已完成；SQL 安全投影、禁止 API、Fixture SHA、工作树与文档已审查，`clean verify`、`-Pfailure verify`、
   `-Pcompat verify` 全部通过。

## 明确不做

不实现 Python `list_schedules` 的身份/内容/时区/运行次数输出；不实现 `schedule`、`cancel_schedule`、Delivery、真实
Telegram、外部 Source、远程 MCP、CLI+Web、前端或任何写入。不改变 R11-B2c 的删除路径选择与授权要求。
