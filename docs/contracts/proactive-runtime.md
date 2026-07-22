# R8 主动运行时、Drift 与 Subagent 契约

- 阶段：R8
- 状态：已由“完成 R7–R9”授权冻结；生产默认 `DISABLED`
- 日期：2026-07-18
- Python 基准：`agent/scheduler.py`、`agent/core/proactive_*`、`agent/core/drift_turn.py`、`agent/subagent.py`
- 关联 ADR：[ADR-0013：使用本地 SQLite 租约主动运行时](../adr/0013-use-local-sqlite-leased-proactive-runtime.md)
- 关联设计：[R8 主动运行时设计](../specs/2026-07-18-proactive-runtime-design.md)
- 后续安全检视：[本地 Proactive Job 只读检视契约](read-only-local-proactive-job-inspection.md)

## 1. 目标与范围

R8 迁移单 JVM 的可恢复 Scheduler、受限 Proactive 决策、只读 Drift 与有界 Subagent。V1 只支持项目拥有的
SQLite 状态库、固定时钟、显式预算和可审计取消；不引入集群协调、外部队列、Peer Agent、自动网络抓取、
真实消息推送或副作用工具。

## 2. 模式与调度 Job

`agent.proactive.mode` 严格为 `DISABLED|LOCAL_SQLITE`。Disabled 不创建 DB、线程、Job、随机 ID 或 Provider
调用。LOCAL_SQLITE 也必须显式配置 allowlisted 本地 `ProactivePlan`，不读取 Python Scheduler 文件。

Job 有不透明 `jobRef`、`schedule`（一次 `AT` 或固定周期 `EVERY`）、UTC `nextRunAt`、`idempotencyKey`、
状态、尝试次数、最大尝试和固定目标引用。V1 不支持 cron、时区推断或模糊自然语言时间；这些留给 V2。

状态仅为 `SCHEDULED -> CLAIMED -> RUNNING -> SUCCEEDED|SKIPPED|FAILED|CANCELLED`。Claim 是单 JVM SQLite
租约；崩溃恢复只重新取得已过期且尚无终态的 Job，不重复已提交的 delivery/idempotency key。

## 3. Proactive、Drift 与 Subagent

Proactive 先执行 Gate：模式、目标、冷却、被动 Turn busy、预算与重复投递；任一 Gate 拒绝稳定 `SKIPPED`，
不调用模型。允许执行时，Planner 只产生安全 `ProactiveDecision`：`SKIP` 或 `REQUESTED`，不直接发送消息。
实际 Delivery 仍通过既有 `MessageTurnService`/Channel 主路径，且 V1 默认使用 NoOp Delivery。

Drift 是一次有界、只读的诊断子流程：它不能写 Workspace、修改配置、调用网络、调用副作用 Tool 或自动推送。
Subagent 是一次性、父 Job 绑定的受限执行：没有继承 Tool、Memory、Session、Approval 或 Plugin 权限；V1 固定
为无 Tool、无网络、有限 Prompt/结果/步骤/时间预算。父取消、租约失效或关闭必须传播为同一个稳定取消原因。

## 4. 资源、安全与审计

全局 Job、每目标 Job、并发执行、计划长度、结果长度、运行时间和重试次数都有硬上限。Scheduler 不使用墙钟
Sleep 作为测试同步原语；Clock、Executor、Lease 失效和执行器均可注入。审计只记录安全引用 Hash、动作、
结果、稳定码、次数、耗时和预算，不记录 Prompt、模型文本、Memory、Tool、路径、Token 或原始目标。

稳定码：`PROACTIVE_CONTRACT_INVALID`、`PROACTIVE_DISABLED`、`PROACTIVE_BUDGET_EXHAUSTED`、`PROACTIVE_COOLDOWN`、
`PROACTIVE_TARGET_BUSY`、`PROACTIVE_DUPLICATE`、`PROACTIVE_LEASE_LOST`、`PROACTIVE_TIMEOUT`、
`DRIFT_READ_ONLY`、`SUBAGENT_BUDGET_EXHAUSTED`、`SUBAGENT_CANCELLED`。

## 5. 验收与暂停

Fixture 覆盖计划、Claim、恢复、重复、冷却、取消、关闭、预算和安全投影。默认、`failure`、`compat` 与
SQLite Leak/Schema/架构门禁全部通过前不进入 R9。真实 Provider、Telegram、Workspace、外部内容源、真实
推送、Peer Agent 和分布式调度都需要单独授权。
